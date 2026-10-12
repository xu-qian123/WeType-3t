package com.xposed.wetypehook.wetype.hook

import android.view.MotionEvent
import android.view.View
import com.xposed.wetypehook.wetype.gesture.KeyGestureResolver
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookBefore
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * 微信输入法按键下滑手势 Hook 组
 * 拦截 QWERTY 与 T9 的原生触摸分发，对接 KeyGestureResolver 状态机
 */
internal object WeTypeGestureHooks {

    private const val TAG = "WeTypeGesture"
    private var resolver: KeyGestureResolver? = null
    @Volatile
    private var isDispatchingCancel = false

    fun install(sourceDir: String?, classLoader: ClassLoader) {
        if (sourceDir.isNullOrEmpty()) {
            Log.e("Failed: Cannot install gesture hooks without sourceDir")
            return
        }

        runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(sourceDir).use { bridge ->
                val keyDataMethod = resolveKeyDataMethod(bridge, classLoader)
                val keyIdMethod = resolveKeyIdMethod(bridge, classLoader)
                val newResolver = KeyGestureResolver(keyDataMethod, keyIdMethod)
                resolver = newResolver

                installTouchHooks(bridge, classLoader, newResolver)
            }
            Log.i("Success: WeType gesture hooks installation completed")
        }.onFailure {
            Log.e("Failed: Installing WeType gesture hooks: ${it.message}")
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

    private fun resolveKeyIdMethod(bridge: DexKitBridge, classLoader: ClassLoader): Method? {
        return runCatching {
            bridge.findMethod {
                searchPackages("com.tencent.wetype.plugin.hld.keyboard")
                matcher {
                    name = "getId"
                }
            }.firstOrNull { it.paramTypes.isEmpty() && it.returnType?.name != "void" }
                ?.getMethodInstance(classLoader)
        }.getOrNull()
    }

    /**
     * 安装触摸 hook：QWERTY 与 T9 指纹可能命中同一基类方法，去重后每个
     * 独立方法只装一个 before-hook，QWERTY/T9 运行时按视图类名判定
     * （与 WeTypeKeyLabelHooks.isT9Key 同规则），避免双 hook 互相覆盖状态机。
     */
    private fun installTouchHooks(bridge: DexKitBridge, classLoader: ClassLoader, resolver: KeyGestureResolver) {
        val qwertyMethod = findTouchMethod(
            bridge, classLoader,
            usingString = "onTouch move2 lastKeyOperation is null",
            filterReturnBoolean = true
        )
        // T9's UP handler wraps the event in c$a; its owner has a separate direct dispatcher.
        // Searching only methods with MotionEvent misses that owner and hooks QWERTY twice.
        val t9Method = runCatching {
            val up = bridge.findMethod {
                searchPackages("com.tencent.wetype.plugin.hld.keyboard")
                matcher { usingStrings("onTouch up isUpperSlidedCancelState true"); returnType = "boolean" }
            }.map { it.getMethodInstance(classLoader) }.singleOrNull {
                it.parameterTypes.size == 1 && it.parameterTypes.none { type -> type == MotionEvent::class.java }
            } ?: return@runCatching null
            up.declaringClass.declaredMethods.singleOrNull {
                it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[1] == MotionEvent::class.java &&
                    it.parameterTypes[0].name.contains("keyboard.selfdraw") &&
                    !Modifier.isStatic(it.modifiers)
            }?.apply { isAccessible = true }
        }.getOrNull()
        if (qwertyMethod == null) Log.e("[$TAG] Failed to locate QWERTY touch method")
        if (t9Method == null) Log.e("[$TAG] Failed to locate T9 touch method")

        val targets = linkedSetOf<Method>()
        qwertyMethod?.let { targets += it }
        t9Method?.let { targets += it }
        if (targets.isEmpty()) return
        if (qwertyMethod != null && qwertyMethod == t9Method) {
            Log.i("[$TAG] QWERTY/T9 fingerprints hit the same method; single hook with runtime keyboard-type detection")
        }

        targets.forEach { method ->
            runCatching {
                Log.i(
                    "[$TAG] Target ${method.declaringClass.name}#${method.name} " +
                        "static=${Modifier.isStatic(method.modifiers)} " +
                        "params=${method.parameterTypes.joinToString { it.name }}"
                )
                method.hookBefore { param ->
                    if (isDispatchingCancel) return@hookBefore
                    val view = param.thisObject as? View ?: return@hookBefore
                    val motionEvent = param.args.firstOrNull { it is MotionEvent } as? MotionEvent ?: return@hookBefore
                    val keyContext = param.args.firstOrNull { it !== motionEvent }

                    val consumed = resolver.onInterceptTouch(
                        view = view,
                        keyContext = keyContext,
                        event = motionEvent,
                        isT9 = isT9View(view)
                    ) {
                        runCatching {
                            isDispatchingCancel = true
                            try {
                                val cancelEvent = MotionEvent.obtain(motionEvent).apply {
                                    setAction(MotionEvent.ACTION_CANCEL)
                                }
                                val cancelArgs = param.args.toMutableList()
                                val evIndex = cancelArgs.indexOfFirst { it is MotionEvent }
                                if (evIndex >= 0) cancelArgs[evIndex] = cancelEvent
                                try { method.invoke(param.thisObject, *cancelArgs.toTypedArray()) }
                                finally { cancelEvent.recycle() }
                            } finally {
                                isDispatchingCancel = false
                            }
                        }
                    }
                    if (consumed) {
                        param.result = true
                    }
                }
                Log.i("[$TAG] Hooked touch: ${method.declaringClass.name}#${method.name}")
            }.onFailure {
                Log.e("[$TAG] Failed to hook touch ${method.declaringClass.name}#${method.name}: ${it.message}")
            }
        }
        // 保留历史日志关键字，便于旧诊断脚本比对
        if (qwertyMethod != null) Log.i("[$TAG] Hooked QWERTY touch: ${qwertyMethod.declaringClass.name}#${qwertyMethod.name}")
        if (t9Method != null && t9Method != qwertyMethod) {
            Log.i("[$TAG] Hooked T9 touch: ${t9Method.declaringClass.name}#${t9Method.name}")
        }
    }

    private fun findTouchMethod(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        usingString: String,
        filterReturnBoolean: Boolean
    ): Method? {
        return runCatching {
            bridge.findMethod {
                searchPackages("com.tencent.wetype.plugin.hld.keyboard")
                matcher {
                    usingStrings(usingString)
                    if (filterReturnBoolean) returnType = "boolean"
                }
            }.firstOrNull { data ->
                data.paramTypes.any { it.name == "android.view.MotionEvent" }
            }?.getMethodInstance(classLoader)
        }.getOrNull()
    }

    private fun isT9View(view: View): Boolean {
        if (isT9Class(view.javaClass)) return true
        var parent = view.parent
        repeat(8) {
            val parentView = parent as? View ?: return false
            if (isT9Class(parentView.javaClass)) return true
            parent = parentView.parent
        }
        return false
    }

    private fun isT9Class(clazz: Class<*>): Boolean {
        val name = clazz.name.lowercase(Locale.ROOT)
        return name.contains("t9") || name.contains("nine")
    }
}
