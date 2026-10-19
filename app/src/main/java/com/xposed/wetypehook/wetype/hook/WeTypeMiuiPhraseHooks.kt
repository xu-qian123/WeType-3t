package com.xposed.wetypehook.wetype.hook

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.content.res.ColorStateList
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.PopupWindow
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.WeTypeHyperMaterial
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.WeakHashMap

/** MIUI's popup owns insideView; the IME service owns the keyboard frames behind it. */
internal object WeTypeMiuiPhraseHooks {
    private class Session(val inside: View, val originalBackground: Drawable?) {
        val transparent = ColorDrawable(Color.TRANSPARENT)
        val hidden = WeakHashMap<View, Float>()
        var carrier: FrameLayout? = null
        var material: WeTypeHyperMaterial? = null
        var observer: ViewTreeObserver? = null
        var listener: ViewTreeObserver.OnGlobalLayoutListener? = null
        var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
        var imeObserver: ViewTreeObserver? = null
        var imePreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
        var loggedFrames = false
        var dark = false
        val textColors = WeakHashMap<android.widget.TextView, ColorStateList>()
        val contentBackgrounds = WeakHashMap<View, Pair<Drawable, Drawable>>()
    }
    private val prepared = WeakHashMap<PopupWindow, Pair<View, Drawable?>>()
    private val entrance = WeakHashMap<PopupWindow, Pair<Int, android.transition.Transition?>>()
    private val sessions = WeakHashMap<PopupWindow, Session>()
    private val pending = WeakHashMap<PopupWindow, Runnable>()
    private val installed = mutableSetOf<Class<*>>()
    private val handler = Handler(Looper.getMainLooper())

    fun install(classLoader: ClassLoader, dexPath: String? = null) {
        val target = MiuiPhraseDiscovery.resolve(classLoader, dexPath) ?: return
        val clazz = target.clazz
        if (!installed.add(clazz)) return
        runCatching {
            val init = target.showMethods
            PopupWindow::class.java.getDeclaredMethod("showAtLocation", View::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).hookBefore { param ->
                val popup = param.thisObject as? PopupWindow ?: return@hookBefore
                if (clazz.isInstance(popup) && WeTypeSettings.readSnapshotXposed().miuiClipboardGlass &&
                    !prepared.containsKey(popup) && !sessions.containsKey(popup)) {
                    entrance[popup] = popup.animationStyle to popup.enterTransition
                    // Translating the optical surface during MIUI's entrance produces a dark sweep.
                    popup.animationStyle = 0
                    popup.enterTransition = null
                    resolveInsideView(popup)?.let { inside ->
                        prepared[popup] = inside to inside.background
                        replaceBackground(inside, ColorDrawable(Color.TRANSPARENT))
                    }
                }
            }
            // MIUI reuses the popup and can reopen it without running initPopupWindow again.
            // Every successful show must reattach optics and keyboard hiding after dismissal cleanup.
            PopupWindow::class.java.getDeclaredMethod("showAtLocation", View::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).hookAfter { param ->
                val popup = param.thisObject as? PopupWindow ?: return@hookAfter
                if (clazz.isInstance(popup) && WeTypeSettings.readSnapshotXposed().miuiClipboardGlass) {
                    android.util.Log.i("WeTypeMiuiGlass", "popup shown; scheduling appearance session")
                    scheduleApply(popup)
                }
            }
            check(init.isNotEmpty()) { "MIUI initPopupWindow not found" }
            init.forEach { method ->
                method.hookAfter { param ->
                    (param.thisObject as? PopupWindow)?.let(::scheduleApply)
                }
            }
            // Some new MIUI builds only override dismiss; older builds also restoreInputArea.
            clazz.declaredMethods.filter { it.name in setOf("restoreInputArea", "dismiss") }.forEach { method ->
                method.hookBefore { param ->
                    (param.thisObject as? PopupWindow)?.let(::restore)
                }
            }
            // Inherited or renamed cleanup is still covered by PopupWindow's stable API.
            PopupWindow::class.java.getDeclaredMethod("dismiss").hookBefore { param ->
                (param.thisObject as? PopupWindow)?.takeIf { clazz.isInstance(it) }?.let(::restore)
            }
            Log.i("MIUI clipboard glass hooks installed: ${init.size} init methods, ${target.source}, ${clazz.name}")
        }.onFailure {
            installed.remove(clazz)
            Log.e(it)
        }
    }

    private fun scheduleApply(popup: PopupWindow) {
        pending.remove(popup)?.let(handler::removeCallbacks)
        // initPopupWindow calls showAtLocation itself. Never queue a stale reopen after dismiss.
        if (!popup.isShowing) return
        val ref = WeakReference(popup)
        lateinit var task: Runnable
        task = Runnable {
            ref.get()?.let { current ->
                // View.post before attachment is queued until ViewRoot attaches the tree.
                // A dismissed/replaced popup must never be revived by that queued callback.
                if (pending[current] === task) {
                    pending.remove(current)
                    if (current.isShowing) apply(current)
                }
            }
        }
        pending[popup] = task
        popup.contentView?.post(task)
    }

    private fun apply(popup: PopupWindow) {
        if (!WeTypeSettings.readSnapshotXposed().miuiClipboardGlass) {
            restore(popup)
            return
        }
        runCatching {
            val inside = resolveInsideView(popup) ?: run {
                Log.i("MIUI clipboard: insideView / inside_view unavailable; preserving system panel")
                return
            }
            // isShowing becomes true before the first traversal attaches PopupDecorView.
            // Never allocate a Session until its optical children can acquire a ViewRoot.
            if (!inside.isAttachedToWindow) {
                Log.i("MIUI clipboard: waiting for popup attachment")
                scheduleApply(popup)
                return
            }
            sessions[popup]?.let { old ->
                if (old.inside === inside) return
                restore(popup)
            }
            val service = resolveService(popup)
                ?: return run { Log.i("MIUI clipboard: ambiguous service; preserving system panel") }
            val saved = prepared.remove(popup)
            val state = Session(inside, if (saved?.first === inside) saved.second else inside.background)
            // MIUI's package context can retain the theme of a previous editor. Use the live IME.
            state.dark = service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            sessions[popup] = state
            // Preserve padding: setBackground can otherwise change the popup's content geometry.
            val padding = intArrayOf(inside.paddingLeft, inside.paddingTop, inside.paddingRight, inside.paddingBottom)
            inside.background = state.transparent
            inside.setPadding(padding[0], padding[1], padding[2], padding[3])
            val sharedUnderlay = WeTypeWindowHooks.hasAppearanceUnderlay(service, inside)
            if (!sharedUnderlay) createMaterial(state)
            val popupRef = WeakReference(popup)
            val serviceRef = WeakReference(service)
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                val current = popupRef.get()
                if (current == null || !current.isShowing || !WeTypeSettings.readSnapshotXposed().miuiClipboardGlass) {
                    if (current != null) restore(current)
                } else {
                    hideKeyboard(state, serviceRef.get())
                    updateGeometry(state)
                    updateContentColors(state)
                }
            }
            state.listener = listener
            state.observer = inside.viewTreeObserver.also { it.addOnGlobalLayoutListener(listener) }
            // RecyclerView may bind cards after global layout. Style them before that frame draws.
            val preDraw = ViewTreeObserver.OnPreDrawListener {
                if (popupRef.get()?.let { sessions[it] === state && it.isShowing } == true) {
                    hideKeyboard(state, serviceRef.get())
                    updateContentColors(state)
                }
                true
            }
            state.preDrawListener = preDraw
            state.observer?.addOnPreDrawListener(preDraw)
            val imePreDraw = ViewTreeObserver.OnPreDrawListener {
                if (popupRef.get()?.let { sessions[it] === state && it.isShowing } == true) {
                    hideKeyboard(state, serviceRef.get())
                }
                true
            }
            state.imePreDrawListener = imePreDraw
            state.imeObserver = service.window?.window?.decorView?.viewTreeObserver?.also {
                it.addOnPreDrawListener(imePreDraw)
            }
            hideKeyboard(state, service)
            updateGeometry(state)
            updateContentColors(state)
            Log.i("MIUI clipboard: popup background transparent, keyboard underlay hidden, material=${state.material != null}, sharedUnderlay=$sharedUnderlay")
        }.onFailure {
            restore(popup)
            Log.e(it)
        }
    }

    private fun createMaterial(state: Session) {
        // The MIUI layout has a FrameLayout root and inside_view as its content child.
        // Keep system content/adapter ownership intact and put optics behind that child.
        val parent = state.inside.parent as? FrameLayout ?: return
        val carrier = FrameLayout(state.inside.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        state.carrier = carrier
        parent.addView(carrier, parent.indexOfChild(state.inside), FrameLayout.LayoutParams(0, 0))
        val settings = WeTypeSettings.readSnapshotXposed()
        val material = WeTypeHyperMaterial(carrier, settings.glassOverrides.forMiuiPopup(),
            shadowEnabled = false, transparentStart = true)
        state.material = material
        if (!material.apply(state.dark, if (state.dark) settings.darkColor else settings.lightColor)) {
            material.clear()
            parent.removeView(carrier)
            state.carrier = null
            state.material = null
            // On unsupported ROMs, retain the system background instead of a blank popup.
            state.inside.background = state.originalBackground
        }
    }

    private fun updateGeometry(state: Session) {
        val carrier = state.carrier ?: return
        val inside = state.inside
        if (inside.width <= 0 || inside.height <= 0) return
        carrier.measure(View.MeasureSpec.makeMeasureSpec(inside.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(inside.height, View.MeasureSpec.EXACTLY))
        carrier.layout(inside.left, inside.top, inside.right, inside.bottom)
        carrier.pivotX = inside.pivotX
        carrier.pivotY = inside.pivotY
        carrier.scaleX = inside.scaleX
        carrier.scaleY = inside.scaleY
        carrier.translationX = inside.translationX
        carrier.translationY = inside.translationY
        val radius = 20f * inside.resources.displayMetrics.density
        state.material?.updateGeometry(WeTypeCornerRadii(radius, radius, radius, radius))
    }

    private fun hideKeyboard(state: Session, service: InputMethodService?) {
        if (service == null) return
        // Hide children, not mInputFrame itself: its dimensions keep IME insets stable.
        // The module's glass carrier is a decor sibling and must remain visible.
        val decor = service.window?.window?.decorView
        var frames = 0
        for ((name, resource) in listOf("mInputFrame" to "inputArea", "mCandidatesFrame" to "candidatesArea")) {
            val frame = (fieldValue(service, name) as? ViewGroup)
                ?: decor?.findViewById<ViewGroup>(service.resources.getIdentifier(resource, "id", "android"))
                ?: continue
            frames++
            for (index in 0 until frame.childCount) {
                val child = frame.getChildAt(index)
                if (!state.hidden.containsKey(child)) state.hidden[child] = child.alpha
                if (child.alpha != 0f) child.alpha = 0f
            }
        }
        if (!state.loggedFrames) {
            state.loggedFrames = true
            android.util.Log.i("WeTypeMiuiGlass", "IME frames=$frames hiddenChildren=${state.hidden.size}")
        }
    }

    private fun restore(popup: PopupWindow) {
        pending.remove(popup)?.let(handler::removeCallbacks)
        entrance.remove(popup)?.let { (style, transition) ->
            popup.animationStyle = style
            popup.enterTransition = transition
        }
        prepared.remove(popup)?.let { (view, background) -> replaceBackground(view, background) }
        val state = sessions.remove(popup) ?: return
        state.listener?.let { listener ->
            state.observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
        }
        state.preDrawListener?.let { listener ->
            state.observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        state.imePreDrawListener?.let { listener ->
            state.imeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        state.material?.clear()
        state.carrier?.let { (it.parent as? ViewGroup)?.removeView(it) }
        if (state.inside.background === state.transparent) state.inside.background = state.originalBackground
        state.hidden.forEach { (view, alpha) -> if (view.alpha == 0f) view.alpha = alpha }
        state.hidden.clear()
        state.textColors.forEach { (view, colors) -> view.setTextColor(colors) }
        state.contentBackgrounds.forEach { (view, backgrounds) ->
            if (view.background === backgrounds.second) replaceBackground(view, backgrounds.first)
        }
    }

    private fun replaceBackground(view: View, background: Drawable?) {
        val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        view.background = background
        view.setPadding(padding[0], padding[1], padding[2], padding[3])
    }

    private fun updateContentColors(state: Session) {
        val foreground = if (state.dark) 0xFFF0F0F0.toInt() else 0xFF252525.toInt()
        fun visit(view: View, inList: Boolean = false) {
            if (view is android.widget.TextView) {
                if (!state.textColors.containsKey(view)) state.textColors[view] = view.textColors
                if (view.textColors.defaultColor != foreground) view.setTextColor(foreground)
            }
            // MIUI button images can contain both an opaque circular plate and a glyph.
            // Tinting the whole image erases that contrast; retain the host's image tint.
            // Tab backgrounds encode MIUI's active tab; a uniform tint erases that distinction.
            val tab = !inList && view is android.widget.TextView
            if (view !== state.inside && view !is android.widget.ImageView && !tab) {
                val background = view.background
                val existing = state.contentBackgrounds[view]
                if (background != null && background !== existing?.second) {
                    background.constantState?.newDrawable(view.resources)?.mutate()?.let { tinted ->
                        tinted.setTint(if (state.dark) 0x60FFFFFF else 0x38FFFFFF)
                        tinted.level = background.level
                        state.contentBackgrounds[view] = background to tinted
                        replaceBackground(view, tinted)
                        // A fresh StateList/Ripple drawable can start in its transparent transition.
                        // Resolve the actual idle state now, rather than waiting for a touch event.
                        tinted.state = view.drawableState
                        tinted.jumpToCurrentState()
                        view.invalidate()
                    }
                }
            }
            val childInList = inList || view.javaClass.simpleName == "RecyclerView"
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), childInList)
        }
        visit(state.inside)
    }

    private fun resolveInsideView(popup: PopupWindow): View? {
        (fieldValue(popup, "insideView") as? View)?.let { return it }
        val root = popup.contentView ?: return null
        // Resource IDs differ across MIUI builds; resolve by entry name rather than constants.
        fun find(view: View): View? {
            if (view.id != View.NO_ID && runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() == "inside_view") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        find(root)?.let { return it }
        // A renamed layout must still have one vertical content child with tabs and a list.
        val parent = root as? FrameLayout ?: return null
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        return (0 until parent.childCount).map { parent.getChildAt(it) }.filter { child ->
            child is android.widget.LinearLayout && child.orientation == android.widget.LinearLayout.VERTICAL &&
                descendants(child).let { views ->
                    views.count { it is android.widget.TextView } >= 2 &&
                        views.any { it.javaClass.simpleName == "RecyclerView" }
                }
        }.singleOrNull()
    }

    private fun resolveService(popup: PopupWindow): InputMethodService? {
        (fieldValue(popup, "mInputMethodService") as? InputMethodService)?.let { return it }
        return generateSequence(popup.javaClass as Class<*>) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && InputMethodService::class.java.isAssignableFrom(it.type) }
            .mapNotNull { runCatching { it.isAccessible = true; it.get(popup) as? InputMethodService }.getOrNull() }
            .distinct().singleOrNull()
    }

    private fun fieldValue(owner: Any, name: String): Any? = runCatching {
        findField(owner.javaClass, name)?.get(owner)
    }.getOrNull()

    private fun findField(clazz: Class<*>?, name: String): Field? {
        var current = clazz
        while (current != null) {
            try { return current.getDeclaredField(name).apply { isAccessible = true } }
            catch (_: NoSuchFieldException) { current = current.superclass }
        }
        return null
    }

    fun prepareForHotReload(): Boolean {
        handler.removeCallbacksAndMessages(null)
        pending.clear()
        sessions.keys.toList().forEach(::restore)
        prepared.keys.toList().forEach(::restore)
        entrance.keys.toList().forEach(::restore)
        installed.clear()
        return true
    }
}
