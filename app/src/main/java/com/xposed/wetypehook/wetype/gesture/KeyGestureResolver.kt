package com.xposed.wetypehook.wetype.gesture

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.xposed.wetypehook.wetype.settings.WeTypeGestureSettings
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * 触摸手势判定器与状态机
 * 逆向还原自 WeType-Tool C1474 + C1476 + C1774 + C1769
 */
class KeyGestureResolver(
    private val keyDataMethod: Method? = null,
    private val keyIdMethod: Method? = null
) {

    private var activeViewRef = WeakReference<View>(null)
    private var startX = 0f
    private var startY = 0f
    private var thresholdPx = 20f
    private var boundAction = GestureAction.None
    private var triggered = false
    private var isCurrentT9 = false

    fun reset() {
        activeViewRef.clear()
        startX = 0f
        startY = 0f
        thresholdPx = 20f
        boundAction = GestureAction.None
        triggered = false
        isCurrentT9 = false
    }

    /**
     * 触摸事件拦截
     * 返回 true 表示该事件被手势消费，不应继续派发给原始按键
     *
     * @param view 键盘视图 (thisObject)
     * @param keyContext 按键数据上下文 (param.args[0]，如 selfdraw.j)
     * @param event 触摸事件
     * @param isT9 是否为九宫格模式
     * @param cancelNative 取消原生事件回调 (用 ACTION_CANCEL 调用原方法)
     */
    fun onInterceptTouch(
        view: View,
        keyContext: Any?,
        event: MotionEvent,
        isT9: Boolean,
        cancelNative: (() -> Unit)? = null
    ): Boolean {
        val isEnabled = if (isT9) {
            WeTypeSettings.isT9GestureEnabledXposed()
        } else {
            WeTypeSettings.isQwertyGestureEnabledXposed()
        }
        if (!isEnabled) {
            reset()
            return false
        }

        val actionMasked = event.actionMasked

        // 多指操作直接放行并重置
        if (event.pointerCount != 1) {
            if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                reset()
            }
            return false
        }

        if (actionMasked == MotionEvent.ACTION_DOWN) {
            startX = event.x
            startY = event.y
            activeViewRef = WeakReference(view)
            isCurrentT9 = isT9
            triggered = false

            // 从 keyContext (selfdraw.j / KeyData) 或 view 中解析按键字符
            val keyChar = resolveKeyChar(keyContext ?: view, keyDataMethod, isT9)
            if (keyChar == '\u0000') {
                reset()
                return false
            }
            val bindings = WeTypeGestureSettings.cachedBindings(WeTypeSettings.getGestureBindingsJsonXposed())
            val action = bindings[keyChar] ?: GestureAction.None
            boundAction = action

            val thresholdDp = if (isT9) {
                WeTypeSettings.getT9GestureThresholdXposed()
            } else {
                WeTypeSettings.getGestureThresholdXposed()
            }.coerceIn(10, 48)

            val density = view.resources.displayMetrics.density
            thresholdPx = max(1f, thresholdDp * density)

            if (action == GestureAction.None || action == GestureAction.Disable) {
                reset()
                return false
            }
            return false
        }

        // 已触发状态下，后续 MOVE、UP、CANCEL 全量消费，避免输入原字符
        if (triggered) {
            if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                reset()
            }
            return true
        }

        if ((actionMasked == MotionEvent.ACTION_MOVE || actionMasked == MotionEvent.ACTION_UP) && boundAction != GestureAction.None) {
            val deltaX = event.x - startX
            val deltaY = event.y - startY

            // 动态横向漂移上限计算 (QWERTY 容许大漂移，T9 更严格)
            val driftLimit = max(
                thresholdPx * (if (isT9) 0.9f else 2.5f),
                abs(deltaY) * (if (isT9) 0.75f else 1.2f)
            )

            // 下滑判定: 纵向位移超过阈值且横向未超容差
            if (deltaY >= thresholdPx && abs(deltaX) <= driftLimit) {
                val actionToExecute = boundAction
                triggered = true
                Log.i("Gesture triggered: action=${actionToExecute.title}, deltaY=$deltaY, threshold=$thresholdPx")

                // 1. 发送 ACTION_CANCEL 中止原生按键事件 (优先回调原方法派发 CANCEL)
                if (cancelNative != null) {
                    cancelNative.invoke()
                } else {
                    sendCancelEvent(view, event)
                }

                // 2. 键盘触觉反馈
                val vibrate = if (isT9) WeTypeSettings.isT9GestureVibrationXposed() else WeTypeSettings.isGestureVibrationXposed()
                if (vibrate) {
                    runCatching {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }

                // 3. 执行动作
                view.post {
                    GestureActionExecutor.execute(actionToExecute, view)
                }

                if (actionMasked == MotionEvent.ACTION_UP) {
                    reset()
                }
                return true
            }
        }

        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            reset()
        }
        return false
    }

    /** 兼容旧接口重载 */
    fun onInterceptTouch(view: View, event: MotionEvent, isT9: Boolean): Boolean =
        onInterceptTouch(view, null, event, isT9, null)

    /**
     * 派发 ACTION_CANCEL 给按键视图
     */
    private fun sendCancelEvent(view: View, originalEvent: MotionEvent) {
        val cancelEvent = MotionEvent.obtain(originalEvent).apply {
            action = MotionEvent.ACTION_CANCEL
        }
        runCatching {
            view.dispatchTouchEvent(cancelEvent)
        }.also {
            cancelEvent.recycle()
        }
    }

    /**
     * 解析按键字符 (QWERTY 键名 / T9 键号)，抽成伴生方法供底部标签绘制复用。
     */
    fun resolveKeyChar(keyContext: Any?): Char = resolveKeyChar(keyContext, keyDataMethod, isCurrentT9)

    companion object {
        private data class KeyAccessor(
            val keyDataGetter: ((Any) -> Any?)?,
            val mainTextMethod: Method?,
            val idMethod: Method?
        )
        private val accessorCache = ConcurrentHashMap<Class<*>, KeyAccessor>()

        fun resolveKeyChar(keyContext: Any?, keyDataMethod: Method? = null, isT9: Boolean = false): Char {
            if (keyContext == null) return '\u0000'

            // 1. 如果是 TextView，直接取文本
            if (keyContext is TextView) {
                val text = keyContext.text?.toString()
                if (!text.isNullOrEmpty()) return normalizeKeyText(text, isT9)
            }

            // 2. 尝试从 keyDataMethod 反射调用 (可能直接作用于 KeyData 或 keyContext)
            keyDataMethod?.takeIf { it.declaringClass.isInstance(keyContext) }?.let { method ->
                runCatching {
                    method.isAccessible = true
                    val result = method.invoke(keyContext) as? String
                    if (!result.isNullOrEmpty()) {
                        val normalized = normalizeKeyText(result, isT9)
                        if (normalized != '\u0000') return normalized
                    }
                }
            }

            // 3. 动态从 keyContext (如 selfdraw.j / KeyData) 提取 mainText / id
            for (rawText in resolveRawTexts(keyContext)) {
                val normalized = normalizeKeyText(rawText, isT9)
                if (normalized != '\u0000') return normalized
            }

            // 4. View tag 兜底
            if (keyContext is View) {
                val tagStr = keyContext.tag?.toString()
                if (!tagStr.isNullOrEmpty()) {
                    val normalized = normalizeKeyText(tagStr, isT9)
                    if (normalized != '\u0000') return normalized
                }
            }

            return '\u0000'
        }

        private fun normalizeKeyText(text: String, isT9: Boolean): Char {
            val trimmed = text.trim()
            if (trimmed.equals("space", ignoreCase = true) || trimmed == "空格" || trimmed.equals("spacebar", ignoreCase = true)) {
                return ' '
            }
            if (isT9) {
                val mapped = mapT9Text(trimmed)
                if (mapped != null) return mapped
                if (trimmed.length == 1 && trimmed[0] in '1'..'9') return trimmed[0]
                return '\u0000'
            }
            // QWERTY 模式：严格仅接受单个英文字母按键 'a'..'z' / 'A'..'Z'，坚决排除功能键（如中英切换、换行、删除、数字等）
            if (trimmed.length == 1) {
                val ch = trimmed[0]
                if (ch in 'a'..'z' || ch in 'A'..'Z') {
                    return ch.lowercaseChar()
                }
            }
            return '\u0000'
        }

        private fun mapT9Text(str: String): Char? {
            val letters = str.filter { it in 'a'..'z' || it in 'A'..'Z' }.lowercase(Locale.ROOT)
            return when (letters) {
                "abc" -> '2'
                "def" -> '3'
                "ghi" -> '4'
                "jkl" -> '5'
                "mno" -> '6'
                "pqrs" -> '7'
                "tuv" -> '8'
                "wxyz" -> '9'
                else -> resolveT9KeyNumber(str)
            }
        }

        private fun resolveRawTexts(obj: Any): List<String> {
            val texts = mutableListOf<String>()
            val clazz = obj.javaClass
            val accessor = accessorCache.getOrPut(clazz) { buildAccessor(clazz) }

            // 先尝试直接调用 mainTextMethod 或 idMethod
            accessor.mainTextMethod?.let { m ->
                runCatching { m.invoke(obj) as? String }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(texts::add)
            }
            accessor.idMethod?.let { m ->
                runCatching { m.invoke(obj)?.toString() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(texts::add)
            }

            // 再尝试从 keyData 提取
            val keyData = accessor.keyDataGetter?.invoke(obj)
            if (keyData != null) {
                val kdClass = keyData.javaClass
                val kdAccessor = accessorCache.getOrPut(kdClass) { buildAccessor(kdClass) }
                kdAccessor.mainTextMethod?.let { m ->
                    runCatching { m.invoke(keyData) as? String }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                }
                kdAccessor.idMethod?.let { m ->
                    runCatching { m.invoke(keyData)?.toString() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                }
            }

            return texts
        }

        private fun buildAccessor(clazz: Class<*>): KeyAccessor {
            var mainTextM: Method? = null
            var idM: Method? = null
            for (m in clazz.methods) {
                if (m.parameterTypes.isEmpty()) {
                    if (m.name == "getMainText" && m.returnType == String::class.java) {
                        m.isAccessible = true
                        mainTextM = m
                    } else if (m.name == "getId") {
                        m.isAccessible = true
                        idM = m
                    }
                }
            }

            // 寻找 KeyData getter
            var getter: ((Any) -> Any?)? = null
            var search: Class<*>? = clazz
            while (search != null && search != Any::class.java && getter == null) {
                for (field in search.declaredFields) {
                    if (field.type.name.contains("KeyData")) {
                        field.isAccessible = true
                        getter = { target -> runCatching { field.get(target) }.getOrNull() }
                        break
                    }
                }
                search = search.superclass
            }
            if (getter == null) {
                for (m in clazz.methods) {
                    if (m.parameterTypes.isEmpty() && m.returnType.name.contains("KeyData")) {
                        m.isAccessible = true
                        getter = { target -> runCatching { m.invoke(target) }.getOrNull() }
                        break
                    }
                }
            }

            return KeyAccessor(getter, mainTextM, idM)
        }
    }
}
