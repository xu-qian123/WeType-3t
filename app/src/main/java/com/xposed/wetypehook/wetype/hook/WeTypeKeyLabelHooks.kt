package com.xposed.wetypehook.wetype.hook

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import com.xposed.wetypehook.wetype.gesture.GestureAction
import com.xposed.wetypehook.wetype.gesture.GestureActionExecutor
import com.xposed.wetypehook.wetype.gesture.KeyGestureResolver
import com.xposed.wetypehook.wetype.settings.WeTypeGestureSettings
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookAfter
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 按键底部标签（移植自 WeType-Tool 显示底部标签）。
 *
 * 定位：宿主 `com.tencent.wetype.plugin.hld.keyboard.selfdraw.drawmethod` 包下
 * `void (Canvas, selfdraw.*)` 单键绘制方法，hookAfter 用同一 Canvas 把绑定动作短名
 * 画在按键上。View/Rect 字段运行时按类型发现（抗宿主混淆更名）。
 * 取键/密码抑制/手写抑制/按字数缩放等语义与原版一致；定位与颜色走本模块设置。
 */
internal object WeTypeKeyLabelHooks {

    private const val TAG = "WeTypeKeyLabel"
    private const val DRAW_METHOD_PACKAGE = "com.tencent.wetype.plugin.hld.keyboard.selfdraw.drawmethod"
    private const val DRAW_CONTEXT_CLASS = "com.tencent.wetype.plugin.hld.keyboard.selfdraw.j"
    private const val CANVAS_CLASS = "android.graphics.Canvas"

    private data class KeyDrawAccess(val viewField: Field, val drawRectGetter: Method)

    private data class LabelPaintStyle(
        val textSizePx: Float,
        val color: Int
    )

    private data class DrawToken(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val key: Char,
        val isT9: Boolean
    )

    private class DrawFrame(
        val view: View,
        val canvas: Canvas,
        val drawingTime: Long
    ) {
        val tokens = HashSet<DrawToken>()
    }

    private val accessCache = ConcurrentHashMap<Class<*>, KeyDrawAccess?>()
    private val t9ClassCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private var cachedPaintStyle: LabelPaintStyle? = null
    private val currentDrawFrame = ThreadLocal<DrawFrame>()
    private val loggedGeometry = ConcurrentHashMap.newKeySet<Char>()

    private var keyDataMethod: Method? = null

    fun install(sourceDir: String?, classLoader: ClassLoader) {
        if (sourceDir.isNullOrEmpty()) {
            Log.e("Failed: Cannot install key label hooks without sourceDir")
            return
        }

        runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(sourceDir).use { bridge ->
                keyDataMethod = resolveKeyDataMethod(bridge, classLoader)
                val targets = bridge.findMethod {
                    searchPackages(DRAW_METHOD_PACKAGE)
                    matcher {
                        returnType = "void"
                        paramCount = 2
                    }
                }.mapNotNull { data ->
                    if (data.paramTypes.size != 2) return@mapNotNull null
                    if (data.paramTypes[0].name != CANVAS_CLASS) return@mapNotNull null
                    // WeType Tool hooks the final per-key renderer whose context is exactly
                    // selfdraw.j. Matching the whole selfdraw package also catches wrapper
                    // renderers and can paint the same label several times in one frame.
                    if (data.paramTypes[1].name != DRAW_CONTEXT_CLASS) return@mapNotNull null
                    val method = runCatching { data.getMethodInstance(classLoader) }.getOrNull()
                        ?: return@mapNotNull null
                    if (Modifier.isAbstract(method.modifiers)) return@mapNotNull null
                    method
                }
                if (targets.isEmpty()) {
                    Log.e("[$TAG] Failed to locate single-key draw method")
                    return
                }
                targets.forEach { method ->
                    method.hookAfter { param ->
                        drawKeyLabel(param.args.getOrNull(0), param.args.getOrNull(1))
                    }
                }
                Log.i("[$TAG] Hooked ${targets.size} single-key draw methods")
            }
        }.onFailure {
            Log.e("Failed: Installing WeType key label hooks: ${it.message}")
            Log.i(it)
        }
    }

    private fun resolveKeyDataMethod(bridge: DexKitBridge, classLoader: ClassLoader): Method? {
        return runCatching {
            bridge.findMethod {
                searchPackages("com.tencent.wetype.plugin.hld.keyboard")
                matcher {
                    name = "getMainText"
                    returnType = "java.lang.String"
                }
            }.firstOrNull()?.getMethodInstance(classLoader)
        }.getOrNull()
    }

    private fun drawKeyLabel(canvasArg: Any?, drawCtx: Any?) {
        // Most keyboards redraw on every press even when labels are disabled.
        if (!WeTypeSettings.isShowGestureKeyLabelsXposed()) return
        val canvas = canvasArg as? Canvas ?: return
        if (drawCtx == null) return
        val access = accessFor(drawCtx) ?: return
        val keyView = runCatching { access.viewField.get(drawCtx) as? View }.getOrNull() ?: return
        val rect = runCatching { access.drawRectGetter.invoke(drawCtx) as? Rect }.getOrNull() ?: return
        if (rect.isEmpty) return

        val isT9 = isT9Key(keyView, drawCtx)
        val enabled = if (isT9) {
            WeTypeSettings.isT9GestureEnabledXposed()
        } else {
            WeTypeSettings.isQwertyGestureEnabledXposed()
        }
        if (!enabled) return
        if (isSuppressedHost(keyView)) return

        val keyChar = KeyGestureResolver.resolveKeyChar(drawCtx, keyDataMethod, isT9)
        if (keyChar == '\u0000') return
        val action = bindings()[keyChar] ?: GestureAction.None
        if (action == GestureAction.None || action == GestureAction.Disable) return
        val text = action.shortTitle
        if (text.isEmpty()) return

        if (!markFirstDrawInFrame(keyView, canvas, rect, keyChar, isT9)) return

        val snapshot = LabelStyleSnapshot.capture(keyView) ?: return
        ensurePaint(snapshot)
        // 原版按字数缩放：≥4 字 0.78×，3 字 0.88×，画完恢复。
        val baseTextSize = paint.textSize
        val baseMetrics = paint.fontMetrics
        paint.textSize = when {
            text.length >= 4 -> baseTextSize * 0.78f
            text.length == 3 -> baseTextSize * 0.88f
            else -> baseTextSize
        }
        val metrics = paint.fontMetrics
        val position = resolveKeyLabelPosition(
            keyLeft = rect.left.toFloat(),
            keyTop = rect.top.toFloat(),
            keyRight = rect.right.toFloat(),
            keyBottom = rect.bottom.toFloat(),
            fontAscent = metrics.ascent,
            fontDescent = baseMetrics.descent,
            anchorTop = snapshot.anchorTop,
            marginLeft = snapshot.marginLeftPx,
            marginTop = snapshot.marginTopPx,
            marginRight = snapshot.marginRightPx,
            marginBottom = snapshot.marginBottomPx
        )
        if (position != null) {
            if ((keyChar == 'z' || keyChar == 'm') && loggedGeometry.add(keyChar)) {
                Log.i("[$TAG] key=$keyChar drawRect=$rect x=${position.first} offset=${snapshot.marginLeftPx - snapshot.marginRightPx}")
            }
            // Reference Tool uses advance-centred text and computes the bottom
            // baseline before applying its three/four-character size reduction.
            canvas.drawText(text, position.first, maxOf(-metrics.ascent + 1f, position.second), paint)
        }
        paint.textSize = baseTextSize
    }

    /** Mirrors the reference implementation's per-frame/per-key draw suppression. */
    private fun markFirstDrawInFrame(
        keyView: View,
        canvas: Canvas,
        rect: Rect,
        keyChar: Char,
        isT9: Boolean
    ): Boolean {
        val drawingTime = keyView.drawingTime.takeIf { it > 0L } ?: android.os.SystemClock.uptimeMillis()
        var frame = currentDrawFrame.get()
        if (frame == null || frame.view !== keyView || frame.canvas !== canvas || frame.drawingTime != drawingTime) {
            frame = DrawFrame(keyView, canvas, drawingTime)
            currentDrawFrame.set(frame)
        }
        // Match Tool's per-rectangle/per-key suppression, including split layouts.
        return frame.tokens.add(DrawToken(rect.left, rect.top, rect.right, rect.bottom, keyChar, isT9))
    }

    private data class LabelStyleSnapshot(
        val textSizePx: Float,
        val color: Int,
        val anchorTop: Boolean,
        val marginLeftPx: Float,
        val marginTopPx: Float,
        val marginRightPx: Float,
        val marginBottomPx: Float
    ) {
        companion object {
            fun capture(keyView: View): LabelStyleSnapshot? {
                val metrics = keyView.resources.displayMetrics
                val density = metrics.density
                if (density <= 0) return null
                val fontScale = keyView.resources.configuration.fontScale
                val textSizePx = WeTypeSettings.getGestureLabelTextSizeSpXposed()
                    .coerceIn(6, 16) * density * fontScale
                val alpha = WeTypeSettings.getGestureLabelAlphaXposed().coerceIn(0, 255)
                val isDark = isDarkMode(keyView)
                val base = (keyView as? TextView)?.currentTextColor
                    ?: resolveThemeTextColor(keyView, isDark)
                return LabelStyleSnapshot(
                    textSizePx = textSizePx,
                    color = (base and 0x00FFFFFF) or (alpha shl 24),
                    anchorTop = WeTypeSettings.getGestureLabelPositionXposed() ==
                        WeTypeSettings.GESTURE_LABEL_POSITION_TOP,
                    marginLeftPx = WeTypeSettings.getGestureLabelMarginLeftDpXposed()
                        .coerceIn(0, 24) * density,
                    marginTopPx = WeTypeSettings.getGestureLabelMarginTopDpXposed()
                        .coerceIn(0, 24) * density,
                    marginRightPx = WeTypeSettings.getGestureLabelMarginRightDpXposed()
                        .coerceIn(0, 24) * density,
                    marginBottomPx = WeTypeSettings.getGestureLabelMarginBottomDpXposed()
                        .coerceIn(0, 24) * density
                )
            }

            private fun isDarkMode(view: View): Boolean {
                val isNightUi = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                val context = view.context ?: return isNightUi
                val bgColor: Int? = runCatching { WeTypeSettings.getCurrentBackgroundColorXposed(context) }.getOrNull()
                if (bgColor != null && Color.alpha(bgColor) > 50) {
                    val luminance = (Color.red(bgColor) * 0.299 + Color.green(bgColor) * 0.587 + Color.blue(bgColor) * 0.114) / 255
                    return luminance < 0.5
                }
                return isNightUi
            }

            private fun resolveThemeTextColor(view: View, isDark: Boolean): Int {
                val defaultColor = if (isDark) 0xFFCAC4D0.toInt() else 0xFF5F6368.toInt()
                return runCatching {
                    val typedValue = TypedValue()
                    if (view.context.theme.resolveAttribute(
                            android.R.attr.textColorSecondary,
                            typedValue,
                            true
                        )
                    ) {
                        val color = if (typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                            typedValue.data
                        } else {
                            val resId = typedValue.resourceId
                            if (resId != 0) view.context.getColor(resId) else defaultColor
                        }
                        val lum = (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114) / 255
                        if ((isDark && lum > 0.45) || (!isDark && lum <= 0.55)) {
                            return color
                        }
                    }
                    defaultColor
                }.getOrDefault(defaultColor)
            }
        }
    }

    private fun ensurePaint(snapshot: LabelStyleSnapshot) {
        val style = LabelPaintStyle(snapshot.textSizePx, snapshot.color)
        if (cachedPaintStyle != style) {
            paint.reset()
            paint.isAntiAlias = true
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT
            paint.textSize = snapshot.textSizePx
            paint.color = snapshot.color
            cachedPaintStyle = style
        }
    }

    private fun bindings(): Map<Char, GestureAction> {
        return WeTypeGestureSettings.cachedBindings(WeTypeSettings.getGestureBindingsJsonXposed())
    }

    private fun accessFor(drawCtx: Any): KeyDrawAccess? {
        val clazz = drawCtx.javaClass
        accessCache[clazz]?.let { return it }
        var viewField: Field? = null
        // Verified against the installed host: j.k is hitRect, j.l is drawRect,
        // and j.t() returns drawRect. Never select the first Rect by type: touch
        // bounds may be wider/asymmetric around Z and M.
        val drawRectGetter = listOf("getDrawRect", "t").firstNotNullOfOrNull { name ->
            runCatching { clazz.getMethod(name) }.getOrNull()?.takeIf {
                it.returnType == Rect::class.java && !Modifier.isStatic(it.modifiers)
            }
        } ?: run {
            Log.e("[$TAG] Cannot resolve drawRect getter in ${clazz.name}; skipping labels")
            return null
        }
        var search: Class<*>? = clazz
        while (search != null && search != Any::class.java && viewField == null) {
            for (field in search.declaredFields) {
                if (viewField == null && View::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    viewField = field
                }
            }
            search = search.superclass
        }
        val access = if (viewField != null) {
            KeyDrawAccess(viewField, drawRectGetter)
        } else {
            null
        }
        if (access != null) accessCache[clazz] = access
        if (access != null) {
            Log.i(
                "[$TAG] Resolved draw context ${clazz.name}: " +
                    "view=${viewField?.name} drawRect=${drawRectGetter.name}()"
            )
        } else {
            Log.e("[$TAG] Failed to resolve View/Rect fields in ${clazz.name}")
        }
        return access
    }

    private fun isT9Key(view: View, drawCtx: Any): Boolean {
        if (isT9Class(view.javaClass) || isT9Class(drawCtx.javaClass)) return true
        var parent = view.parent
        repeat(8) {
            val parentView = parent as? View ?: return false
            if (isT9Class(parentView.javaClass)) return true
            parent = parentView.parent
        }
        return false
    }

    private fun isT9Class(clazz: Class<*>): Boolean {
        return t9ClassCache.getOrPut(clazz) {
            val name = clazz.name.lowercase(Locale.ROOT)
            name.contains("t9") || name.contains("nine")
        }
    }

    /**
     * 抑制场景（与原版一致）：密码输入框、手写键盘不画标签。
     */
    private fun isSuppressedHost(view: View): Boolean {
        var current: View? = view
        repeat(8) {
            val name = current?.javaClass?.name?.lowercase(Locale.ROOT).orEmpty()
            if (name.contains("handwrite") || name.contains("hand_write")) return true
            current = current?.parent as? View ?: return@repeat
        }
        val context = view.context ?: return false
        val ims = GestureActionExecutor.resolveInputMethodService(context) ?: return false
        val editorInfo = runCatching { ims.currentInputEditorInfo }.getOrNull() ?: return false
        val inputClass = editorInfo.inputType and EditorInfo.TYPE_MASK_CLASS
        val variation = editorInfo.inputType and EditorInfo.TYPE_MASK_VARIATION
        if (inputClass == EditorInfo.TYPE_CLASS_TEXT) {
            if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) {
                return true
            }
        } else if (inputClass == EditorInfo.TYPE_CLASS_NUMBER) {
            if (variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD) return true
        }
        return false
    }
}
