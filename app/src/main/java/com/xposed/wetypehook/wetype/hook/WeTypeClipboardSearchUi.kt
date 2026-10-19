package com.xposed.wetypehook.wetype.hook

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log as AndroidLog
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.ProceedWithOriginal
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.hookReplace
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Slice 4：剪贴板搜索（原生翻译同形，键盘页顶部生长一行）。
 *
 * 链路：剪贴板页红钮只导航（N.O2 回键盘），不在剪贴板页挂任何行；
 * 键盘页条根（ImeRadiusConstraintLayout + skin.t）经
 * `ImeCandidateView.s0(row, 0, FrameLayout(MATCH,WRAP))` 插 candidate_top_view
 * 空插槽，WRAP 自扩→窗口向上长出一行，键盘容器及祖先高度不动。
 * 圆角/肤色/模糊走 WeTypeWindowHooks 现有背景链；收起按钮原生参数+skin.j，
 * X 取宿主关闭图标，输入框 skin.w。不准碰键盘高度。
 */
internal object WeTypeClipboardSearchUi {

    private val submitSession = ClipboardSearchSubmitSession()
    private var submitDeadline = 0L
    private var nativeSubmitInstalled = false
    private var nativeNavigation: java.lang.reflect.Method? = null
    private var nativeActionDispatcher: java.lang.reflect.Method? = null
    private var nativeActionListener: Any? = null
    private var nativeServiceCompanion: Any? = null
    private var nativeServiceGetter: java.lang.reflect.Method? = null
    private var nativePanel: Any? = null
    private var nativeManager: Any? = null
    private var nativeEngine: Any? = null
    private var nativePendingInput: java.lang.reflect.Method? = null
    private var nativeCandidateView: java.lang.reflect.Method? = null
    private var searchManager: Any? = null
    private var nativeSearchRestore: (() -> Unit)? = null
    private var activeImeService: java.lang.ref.WeakReference<Any>? = null
    private val searchButtonOwners = WeakHashMap<View, Boolean>()
    private const val TAG_RESULT_SEARCH = "wetype_clipboard_result_search"
    private val resultSearchLabels = Collections.newSetFromMap(WeakHashMap<android.widget.TextView, Boolean>())
    private var searchPrefill: String? = null

    /** Keep the query visible with results; editing reuses the verified native input route. */
    private fun ensureResultSearchBar(list: View, root: ViewGroup) {
        val parent = list.parent as? ViewGroup ?: return
        val existingRow = parent.findViewWithTag<View>(TAG_RESULT_SEARCH)
        if (existingRow != null) {
            existingRow.visibility = View.VISIBLE
            refreshResultSearchLabels()
            return
        }
        // Preserve the host's layout contract; only wrap containers with positional-free children.
        if (parent !is LinearLayout && parent !is android.widget.FrameLayout) return
        val index = parent.indexOfChild(list)
        val originalParams = list.layoutParams
        val wrapper = LinearLayout(list.context).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(list.context).apply {
            tag = TAG_RESULT_SEARCH
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = dpToPx(resources, 12f)
            setPadding(pad, 0, pad, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "搜索剪贴板"
            setOnClickListener { onSearchButtonClick(root) }
        }
        val label = android.widget.TextView(list.context).apply {
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER_VERTICAL
            hint = "搜索剪贴板"
            compoundDrawablePadding = dpToPx(resources, 8f)
            resolveSearchIconRes(this, hostClassLoader ?: context.classLoader)?.let { iconRes ->
                context.getDrawable(iconRes)?.mutate()?.let { icon ->
                    val size = dpToPx(resources, 20f)
                    icon.setBounds(0, 0, size, size)
                    setCompoundDrawablesRelative(icon, null, null, null)
                }
            }
            contentDescription = "修改剪贴板搜索关键词"
            setOnClickListener { onSearchButtonClick(root) }
        }
        resultSearchLabels.add(label)
        row.addView(label, LinearLayout.LayoutParams(0, dpToPx(list.resources, 44f), 1f))
        val clear = android.widget.TextView(list.context).apply {
            text = "×"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            contentDescription = "清空剪贴板搜索"
            setOnClickListener { pendingKeyword = ""; applyKeywordDirect("") }
        }
        row.addView(clear, LinearLayout.LayoutParams(dpToPx(list.resources, 44f), dpToPx(list.resources, 44f)))
        wrapper.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            val margin = dpToPx(list.resources, 12f)
            setMargins(margin, dpToPx(list.resources, 6f), margin, dpToPx(list.resources, 8f))
        })
        parent.removeViewAt(index)
        parent.addView(wrapper, index, originalParams)
        val wrapContent = originalParams.height == ViewGroup.LayoutParams.WRAP_CONTENT
        wrapper.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (wrapContent) ViewGroup.LayoutParams.WRAP_CONTENT else 0,
            if (wrapContent) 0f else 1f
        ))
        refreshResultSearchLabels()
    }

    private fun refreshResultSearchLabels() {
        val keyword = WeTypeClipboardSearchFilter.currentKeywordValue()
        resultSearchLabels.forEach { label ->
            label.text = keyword
            val color = resolveThemeIconColor(label)
            label.setTextColor(color)
            label.setHintTextColor((color and 0x00FFFFFF) or 0x99000000.toInt())
            label.compoundDrawablesRelative[0]?.setTint((color and 0x00FFFFFF) or 0xB3000000.toInt())
            (label.parent as? ViewGroup)?.let { row ->
                if (row.tag == TAG_RESULT_SEARCH && label.tag != color) {
                    val dark = color == Color.WHITE
                    val radius = dpToPx(label.resources, 22f).toFloat()
                    val fill = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = radius
                        setColor(if (dark) 0x18FFFFFF else 0x09000000)
                        setStroke(dpToPx(label.resources, 1f), if (dark) 0x28FFFFFF else 0x18000000)
                    }
                    val mask = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = radius
                        setColor(Color.WHITE)
                    }
                    row.background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(if (dark) 0x20FFFFFF else 0x14000000),
                        fill, mask
                    )
                    label.tag = color
                }
                (row.getChildAt(1) as? android.widget.TextView)?.apply {
                    setTextColor(color)
                    visibility = if (keyword.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun ownsSearchBox(box: EditText): Boolean = translatorShellByUs &&
        nativeKEditRefF41?.get() === box && nativeKRefF41?.get()?.parent != null

    private fun readNativeField(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val field = runCatching { cls.getDeclaredField(name) }.getOrNull()
            if (field != null) { field.isAccessible = true; return field.get(target) }
            cls = cls.superclass
        }
        throw NoSuchFieldException("${target.javaClass.name}.$name")
    }

    private fun singleton(cls: Class<*>): Any {
        val field = cls.declaredFields.single {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == cls
        }
        field.isAccessible = true
        return field.get(null) ?: error("Missing native singleton")
    }

    /**
     * 原生提交契约（3.5.3/3.5.4，全部按签名现取）：
     * 导航 3.5.3=N#p3 / 3.5.4=N#n3；候选 getter 按 ImeCandidateView 返回型；
     * pending 3.5.3=i0#B2 / 3.5.4=i0#D2；emit 3.5.3=i0#W1 / 3.5.4=i0#Y1。
     * 任一契约取不到即整链 fail-closed，禁兜底。
     */
    private fun installNativeSubmitBridge(anchor: View): Boolean {
        if (nativeSubmitInstalled) return true
        return runCatching {
            val cl = hostClassLoader ?: error("Missing host loader")
            val n = Class.forName("com.tencent.wetype.plugin.hld.model.N", false, cl)
            val panelClass = Class.forName("com.tencent.wetype.plugin.hld.keyboard.t", false, cl)
            check(panelClass.isEnum)
            val panel = panelClass.enumConstants?.single { (it as Enum<*>).name == "CustomPhraseAndClipboard" }
                ?: error("Missing CustomPhraseAndClipboard panel")
            val navigation = n.declaredMethods.firstOrNull {
                (it.name == "o3" || it.name == "n3" || it.name == "p3") && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == panelClass &&
                    it.parameterTypes[1] == android.os.Bundle::class.java &&
                    it.returnType == Void.TYPE
            } ?: n.declaredMethods.firstOrNull {
                it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == panelClass &&
                    it.parameterTypes[1] == android.os.Bundle::class.java &&
                    it.returnType == Void.TYPE
            } ?: error("Missing native scene navigation")
            val candidateClass = Class.forName("com.tencent.wetype.plugin.hld.candidate.ImeCandidateView", false, cl)
            val candidate = n.declaredMethods.firstOrNull {
                it.parameterTypes.isEmpty() && candidateClass.isAssignableFrom(it.returnType)
            } ?: error("Missing native candidate view getter")
            val engine = Class.forName("com.tencent.wetype.plugin.hld.model.i0", false, cl)
            val pending = engine.declaredMethods.firstOrNull {
                (it.name == "B2" || it.name == "D2" || it.name == "E2") && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == java.lang.Boolean.TYPE &&
                    CharSequence::class.java.isAssignableFrom(it.returnType)
            } ?: error("Missing native pending input getter")
            val emit = engine.declaredMethods.firstOrNull {
                (it.name == "W1" || it.name == "Y1" || it.name == "Z1") && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == java.lang.Boolean.TYPE &&
                    it.parameterTypes[2] == java.lang.Boolean.TYPE &&
                    it.returnType == Void.TYPE
            } ?: error("Missing native pending input emitter")
            val dispatcher = Class.forName("com.tencent.wetype.plugin.hld.key.d", false, cl)
                .getDeclaredMethod("O", Integer.TYPE, Any::class.java)
            check(dispatcher.returnType == Void.TYPE)
            val serviceClass = Class.forName("com.tencent.wetype.plugin.hld.WxHldService", false, cl)
            val service = serviceClass.getDeclaredMethod("c")
            check(service.returnType == Void.TYPE)
            val companionPair = resolveServiceCompanion(serviceClass)
            val q = Class.forName(NATIVE_HEIGHT_MGR_CLASS, false, cl)
            check(q.getDeclaredMethod("T0", CharSequence::class.java).returnType == Void.TYPE)
            check(q.getDeclaredMethod("T", java.lang.Boolean.TYPE).returnType == Void.TYPE)
            nativeManager = singleton(n)
            nativeEngine = singleton(engine)
            nativeNavigation = navigation.apply { isAccessible = true }
            nativeCandidateView = candidate.apply { isAccessible = true }
            nativePendingInput = pending.apply { isAccessible = true }
            nativePanel = panel
            nativeServiceCompanion = companionPair?.first
            nativeServiceGetter = companionPair?.second?.apply { isAccessible = true }
            nativeActionListener = singleton(dispatcher.declaringClass)
            nativeActionDispatcher = dispatcher
            AndroidLog.i(TAG, "native search contract ready: nav=${navigation.name} " +
                "pending=${pending.name} emit=${emit.name} service=${companionPair != null}")
            dispatcher.isAccessible = true
            dispatcher.hookBefore { param ->
                if (param.args.firstOrNull() != 2) return@hookBefore
                val box = nativeKEditRefF41?.get() ?: return@hookBefore
                if (!ownsSearchBox(box) || !box.hasFocus()) return@hookBefore
                val liveService = runCatching {
                    nativeServiceGetter?.invoke(nativeServiceCompanion)
                }.getOrNull()
                if (liveService != null) {
                    activeImeService = java.lang.ref.WeakReference(liveService)
                } else {
                    AndroidLog.w(TAG, "ime service identity unavailable; commit guarded by session phase")
                }
                if (submitSession.confirm() == null) { param.result = null; return@hookBefore }
                submitDeadline = android.os.SystemClock.uptimeMillis() + 1000L
                // Keep the original w(): it commits the engine's selected text asynchronously.
            }
            dispatcher.hookAfter { param ->
                if (param.args.firstOrNull() == 2 &&
                    submitSession.phase == ClipboardSearchSubmitSession.Phase.COMMITTING) {
                    awaitSearchCommit(submitSession.id, "native-action")
                }
            }
            emit.isAccessible = true
            emit.hookBefore { param ->
                if (param.thisObject === nativeEngine &&
                    submitSession.phase == ClipboardSearchSubmitSession.Phase.COMMITTING &&
                    nativeKEditRefF41?.get()?.let(::ownsSearchBox) == true) {
                    // forceEmitPendingInput is asynchronous. A preceding finishComposingText is not its result.
                    submitSession.requireNativeCommit(submitSession.id)
                }
            }
            service.isAccessible = true
            service.hookBefore { param ->
                val tracked = activeImeService?.get()
                if ((tracked == null || param.thisObject === tracked) &&
                    submitSession.phase == ClipboardSearchSubmitSession.Phase.COMMITTING &&
                    nativeKEditRefF41?.get()?.let(::ownsSearchBox) == true) {
                    // w() may call c() after commit; c() would translate/send to the host editor.
                    param.result = null
                }
            }
            nativeSubmitInstalled = true
            true
        }.getOrElse {
            AndroidLog.e(TAG, "native search contract unavailable: ${it.message}")
            false
        }
    }

    /**
     * 宿主 Companion 字段名随版本漂移（3.5.3 raw=G，3.5.4 变化）：
     * 只认“静态字段 + 零参 getter 返回 WxHldService 实现的接口”的组合，取不到返回 null。
     */
    private fun resolveServiceCompanion(serviceClass: Class<*>): Pair<Any, java.lang.reflect.Method>? {
        for (field in serviceClass.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            val companionType = field.type
            if (companionType == serviceClass) continue
            val getters = companionType.declaredMethods.filter { m ->
                m.parameterTypes.isEmpty() && m.returnType != Void.TYPE && m.returnType.isInterface &&
                    runCatching { m.returnType.isAssignableFrom(serviceClass) }.getOrDefault(false)
            }
            if (getters.isEmpty()) continue
            val getter = getters.firstOrNull { it.name == "e" } ?: getters.first()
            val companion = runCatching {
                field.isAccessible = true
                field.get(null)
            }.getOrNull() ?: continue
            return companion to getter
        }
        return null
    }

    /** 在 [root] 子树按类名 BFS 找宿主视图；找不到返回 null。 */
    private fun findHostViewByClass(root: View, className: String): View? {
        val queue: ArrayDeque<View> = ArrayDeque()
        queue.add(root)
        var hops = 0
        while (queue.isNotEmpty() && hops < 800) {
            val v = queue.removeFirst()
            hops++
            if (v.javaClass.name == className) return v
            if (v is ViewGroup) {
                for (i in 0 until minOf(v.childCount, 32)) {
                    v.getChildAt(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }

    /** Observe native commit completion, never turn ordinary text delivery into intention. */
    private fun awaitSearchCommit(token: Long, source: String) {
        if (!submitSession.at(token, ClipboardSearchSubmitSession.Phase.COMMITTING)) return
        val box = nativeKEditRefF41?.get() ?: return
        if (!ownsSearchBox(box)) { submitSession.fail(token); return }
        if (android.os.SystemClock.uptimeMillis() >= submitDeadline) {
            submitSession.fail(token)
            AndroidLog.e(TAG, "search submit did not finish native commit before deadline")
            return
        }
        box.postOnAnimation {
            if (!submitSession.at(token, ClipboardSearchSubmitSession.Phase.COMMITTING) || !ownsSearchBox(box)) return@postOnAnimation
            val noPending = runCatching {
                (nativePendingInput?.invoke(nativeEngine, true) as? CharSequence)?.isEmpty() == true
            }.getOrDefault(false)
            val noComposing = android.view.inputmethod.BaseInputConnection.getComposingSpanStart(box.text) < 0
            if (!submitSession.readyToSnapshot(token, noPending, noComposing)) {
                awaitSearchCommit(token, source)
                return@postOnAnimation
            }
            if (!submitSession.committed(token, box.text?.toString().orEmpty())) return@postOnAnimation
            pendingKeyword = submitSession.keyword
            overlayPending = false
            val anchor = overlayParentRef?.get() ?: return@postOnAnimation
            box.clearFocus()
            // Q0 merely schedules exit. Keep isolation/restoration state until the card is detached.
            driveTranslatorShell(anchor, false)
            awaitSearchShellExit(token)
        }
    }

    private fun currentImeRoot(): ViewGroup? = runCatching {
        val candidate = nativeCandidateView?.invoke(nativeManager) as? View ?: return@runCatching null
        if (!candidate.isAttachedToWindow) return@runCatching null
        candidate.rootView as? ViewGroup
    }.getOrNull()

    private fun awaitSearchShellExit(token: Long) {
        if (!submitSession.at(token, ClipboardSearchSubmitSession.Phase.EXITING)) return
        val root = currentImeRoot() ?: run { submitSession.fail(token); return }
        val mgr = searchManager ?: run { submitSession.fail(token); return }
        val idle = runCatching { mgr.javaClass.getDeclaredMethod("t0").invoke(mgr) == false }.getOrDefault(false)
        val detached = nativeKRefF41?.get()?.isAttachedToWindow == false || nativeKRefF41?.get()?.parent == null
        if (submitSession.shellExited(token, idle, detached)) {
            translatorShellByUs = false
            releaseNativeSearchState()
            pendingKeyword = submitSession.keyword
            if (!openNativeClipboard()) { submitSession.fail(token); return }
            observeSearchLanding(token)
            return
        }
        if (android.os.SystemClock.uptimeMillis() >= submitDeadline) {
            submitSession.fail(token)
            AndroidLog.e(TAG, "search submit did not finish native shell exit before deadline")
            return
        }
        root.postOnAnimation { awaitSearchShellExit(token) }
    }

    /** N.p3(enum, Bundle) is WxHldService.U1 switchScene=12's native navigation contract. */
    private fun openNativeClipboard(): Boolean = runCatching {
        val method = nativeNavigation ?: return@runCatching false
        if (currentImeRoot() == null) return@runCatching false
        method.invoke(nativeManager, nativePanel, android.os.Bundle().apply {
            putInt("target_tab_index", TAB_CLIPBOARD)
            putInt("key_from", 1)
        })
        true // dispatch only; observeSearchLanding establishes visible completion
    }.getOrElse {
        AndroidLog.e(TAG, "native clipboard dispatch failed: ${it.message}")
        false
    }

    private fun observeSearchLanding(token: Long) {
        if (!submitSession.at(token, ClipboardSearchSubmitSession.Phase.OPENING)) return
        val root = currentImeRoot() ?: run { submitSession.fail(token); return }
        if (submitSession.observe(token, nativeClipboardPage(root))) {
            pendingKeyword = ""
            applyKeywordDirect(submitSession.keyword)
            overlayParentRef = null
            return
        }
        if (android.os.SystemClock.uptimeMillis() >= submitDeadline) {
            submitSession.fail(token)
            AndroidLog.e(TAG, "search submit did not show clipboard results before deadline")
            return
        }
        root.postOnAnimation { observeSearchLanding(token) }
    }

    private fun nativeClipboardPage(root: ViewGroup): ClipboardSearchSubmitSession.Page {
        val absent = ClipboardSearchSubmitSession.Page(false, false, false, false, false)
        return runCatching {
            // 当前键盘视图随版本漂移（3.5.3=N#o0，3.5.4 无同型入口）：按类名从窗内 BFS 现取。
            val host = findHostViewByClass(root, S15_CLASS) ?: return@runCatching absent
            if (host.rootView !== root) return@runCatching absent
            // 3.5.3 binary fields: K=currentTabIndex, I=mTabIdClipboard, C=emptyClipboardView.
            val tab = readNativeField(host, "K") as? Int
            val clipboard = readNativeField(host, "I") as? Int
            val ids = Class.forName(WETYPE_ID_CLASS, false, host.javaClass.classLoader)
            val listId = ids.getField("t15_clipboard_list").getInt(null)
            val list = host.findViewById<View>(listId)
            val empty = readNativeField(host, "C") as? View
            ClipboardSearchSubmitSession.Page(true, visiblyInsideWindow(host, root), tab != null && tab == clipboard,
                list?.let { visiblyInsideWindow(it, root) } == true,
                empty?.let { visiblyInsideWindow(it, root) } == true)
        }.getOrDefault(absent)
    }

    private fun visiblyInsideWindow(view: View, root: ViewGroup): Boolean {
        if (!view.isAttachedToWindow || !view.isShown || view.alpha <= 0f || view.width <= 0 || view.height <= 0) return false
        val rect = android.graphics.Rect()
        val window = android.graphics.Rect()
        return view.getGlobalVisibleRect(rect) && root.getGlobalVisibleRect(window) && rect.intersect(window) && !rect.isEmpty
    }

    /** Snapshot before repurposing; restore the same native instance for later real translation. */
    private fun captureNativeSearchState(parts: NativeKPartsF41): () -> Unit {
        val box = parts.edit
        val watchers = (readNativeField(box, "mListeners") as? List<*>)?.filterIsInstance<TextWatcher>().orEmpty()
        val editor = readNativeField(box, "mEditor")
        val content = editor?.let { readNativeField(it, "mInputContentType") }
        val editorAction = content?.let { readNativeField(it, "onEditorActionListener") } as? android.widget.TextView.OnEditorActionListener
        val listenerGetter = View::class.java.getDeclaredMethod("getListenerInfo").apply { isAccessible = true }
        val boxListeners = listenerGetter.invoke(box)
        val key = boxListeners?.let { readNativeField(it, "mOnKeyListener") } as? View.OnKeyListener
        val exitListenerInfo = parts.exit?.let { listenerGetter.invoke(it) }
        val exit = exitListenerInfo?.let { readNativeField(it, "mOnClickListener") } as? View.OnClickListener
        val hint = box.hint
        val mode = parts.modeTv.text
        // 上行左图标快照：独立ImageView drawable + compound左drawable（二选一命中的才记）。
        val modeIcon = parts.modeIcon
        val modeIconDrawable = runCatching { modeIcon?.drawable?.constantState?.newDrawable() ?: modeIcon?.drawable }.getOrNull()
        val modeIconTint = runCatching { modeIcon?.imageTintList }.getOrNull()
        val modeIconFilter = runCatching { modeIcon?.colorFilter }.getOrNull()
        val modeIconAlpha = runCatching { modeIcon?.alpha }.getOrNull() ?: 0.4f
        val modeCompounds = runCatching { parts.modeTv.compoundDrawables }.getOrNull()?.copyOf()
        val dropdown = parts.dropdown
        val getter = dropdown.javaClass.getDeclaredMethod("getOnItemClick").apply { isAccessible = true }
        val click = getter.invoke(dropdown)
        val setter = dropdown.javaClass.declaredMethods.single { it.name == "setOnItemClick" && it.parameterTypes.size == 1 }
            .apply { isAccessible = true }
        val adapter = resolveDropdownAdapterF41(dropdown) ?: error("Missing native language adapter")
        val languageList = (readNativeField(adapter, "c") as List<*>).toList()
        val bind = adapter.javaClass.getDeclaredMethod("k", List::class.java).apply { isAccessible = true }
        return {
            nativeKWatchersF41.remove(box)?.let { box.removeTextChangedListener(it) }
            val current = (readNativeField(box, "mListeners") as? List<*>)?.filterIsInstance<TextWatcher>().orEmpty()
            for (watcher in watchers) if (current.none { it === watcher }) box.addTextChangedListener(watcher)
            hintLayoutListenersF41.remove(box)?.let { box.removeOnLayoutChangeListener(it) }
            box.setOnEditorActionListener(editorAction)
            box.setOnKeyListener(key)
            box.hint = hint
            parts.modeTv.text = mode
            // 图标还账：ImageView优先，compound兜底；只恢复我方改过的那一路。
            runCatching {
                if (modeIcon != null) {
                    modeIcon.clearColorFilter()
                    modeIcon.imageTintList = modeIconTint
                    if (modeIconDrawable != null) {
                        modeIcon.setImageDrawable(modeIconDrawable)
                    }
                    modeIcon.alpha = modeIconAlpha
                } else if (modeCompounds != null) {
                    parts.modeTv.setCompoundDrawables(
                        modeCompounds[0], modeCompounds[1], modeCompounds[2], modeCompounds[3]
                    )
                }
            }
            parts.exit?.setOnClickListener(exit)
            setter.invoke(dropdown, click)
            bind.invoke(adapter, languageList)
            trackedBoxes.remove(box)
            cachedConnections.remove(box)
        }
    }

    private fun releaseNativeSearchState() {
        val restore = nativeSearchRestore
        nativeSearchRestore = null
        nativeKRefF41 = null
        nativeKEditRefF41 = null
        nativeKModeTvRefF41 = null
        nativeKModeIconRefF41 = null
        searchManager = null
        runCatching { restore?.invoke() }.onFailure { AndroidLog.e(TAG, "native search restoration failed: ${it.message}") }
    }

    private const val TAG = "WeTypeClipboardSearch"
    private const val S15_CLASS = "com.tencent.wetype.plugin.hld.keyboard.S15CustomPhraseAndClipboardKeyboard"
    private const val WETYPE_ID_CLASS = "com.tencent.wetype.plugin.hld.s"
    private const val WETYPE_DRAWABLE_CLASS = "com.tencent.wetype.plugin.hld.r"

    private const val TAG_SEARCH_BUTTON = "wetype_clipboard_search_btn_s4"
    private const val TAG_SEARCH_BOX = "wetype_clipboard_search_box_s4"
    private const val TAG_SEARCH_BOX_CONTAINER = "wetype_clipboard_search_box_container_s6"
    private const val TAG_SEARCH_CLEAR = "wetype_clipboard_search_clear_s6"

    // 条根（方案B）：ImeRadiusConstraintLayout + setRadius(背景圆角设置，
    // 与 WeTypeWindowHooks.resolveCornerRadii 同源） + setBorderWidth(1f)
    //   + skin.t(ime_color_09/16)；收起 = TextView"收起" gravity17/alignment4 +
    //   skin.j(ime_color_06) + m1.A3(40,true) + padding(g0(20),g0(40))；
    // 输入框 skin.w；分隔条 skin.h。X 原生翻译条没有，取宿主关闭图标。
    private const val NATIVE_RADIUS_CLASS =
        "com.tencent.wetype.plugin.hld.view.ImeRadiusConstraintLayout"
    private const val NATIVE_TOPVIEW_CLASS =
        "com.tencent.wetype.plugin.hld.translatingwhilewriting.k"
    private const val NATIVE_SKIN_UTILS_CLASS = "com.tencent.wetype.skin.utils.d"
    // 缩放/字号类名随版本漂移（设备 3.5.3 实证 q1/m1；3.5.4 基线 r1/n1，
    // 均为宿主原生静态/实例入口，按序取首个命中，禁任何非原生兜底）。
    private val NATIVE_SCALE_CLASSES = arrayOf(
        "com.tencent.wetype.plugin.hld.utils.q1",
        "com.tencent.wetype.plugin.hld.utils.r1"
    )
    private val NATIVE_FONT_CLASSES = arrayOf(
        "com.tencent.wetype.plugin.hld.utils.m1",
        "com.tencent.wetype.plugin.hld.utils.n1"
    )
    private const val NATIVE_SKIN_ROOT = "t"
    private const val NATIVE_SKIN_EXIT = "j"
    private const val NATIVE_SKIN_INPUT = "w"
    private const val NATIVE_EXIT_FONT = 40
    private const val NATIVE_EXIT_PAD_START_G0 = 20
    private const val NATIVE_EXIT_PAD_END_G0 = 40
    private const val NATIVE_RADIUS_F0 = 32

    // 原生输入框几何（设备 3.5.3 k.java 实测）：A3(45,true) + padding g0(40)/e0(40)
    // + 高 e0(80+63·行数) + 行高 e0(63) + gravity 8388627 + 绿光标。
    private const val NATIVE_INPUT_FONT = 45
    private const val NATIVE_INPUT_H_E0 = 143
    private const val NATIVE_INPUT_LINE_H_E0 = 63
    private const val NATIVE_INPUT_PAD_SIDE_G0 = 40
    private const val NATIVE_INPUT_PAD_TB_E0 = 40
    private const val NATIVE_INPUT_GRAVITY = 8388627
    private const val NATIVE_CURSOR_DRAWABLE = "ime_green_cursor"
    // 原生条根边距参照（k 内 rootContainer LP）：top e0(36) bottom e0(20) 左右 g0(20)。
    // F缝修结论：缝来源是外边距叠加（top36+bottom20≈56e0≈一整行），不是 slot WRAP 撑出整行
    //（slot/cand WRAP 只包内容 trueH167，键盘容器及祖先不动；publish(trueH167)+N三连+float
    // 只同步一次，500/1200ms 延迟复挂早退无二次 publish）。条改最小贴合边距，左右仍原生。
    private const val NATIVE_ROOT_M_TOP_E0 = 36
    private const val NATIVE_ROOT_M_BOTTOM_E0 = 20
    private const val NATIVE_ROOT_M_SIDE_G0 = 20
    // F12（接F11顶1.5dp/底10px，在此基础上改，不reset）：条底→图标顶从10px放到20px；
    // 顶漏量修（用户报：条顶→输入法顶视觉还很长，根本不是5px——旧量法只量槽顶→条顶mTop，
    // 漏了槽顶→窗顶/灰底顶那段）：窗顶→条顶视觉缝也压到1.5dp当量，只收槽/窗多余垫
    //（slot/cand paddingTop/topMargin现量现记→0，不动条真高167/box143，不碰键盘容器及祖先），
    // WRAP链/publish(trueH)/N三连/float只同步高度一次不撑灰顶（见expandStripChainForWrap）。
    // 新基准仍=图标顶（图标cluster cy-半高，半高现算不写死，89x89仅参考；找不到cluster则fail-closed
    // diagnostically，不回退容器顶）。条底贴图标顶19~21px（取20px，现算px）；
    // 槽WRAP自收敛（槽总高=条真高+新顶1.5dp+新底20px，不多留）；postAlign下移量=gap-iconGap，
    // 底放到20px后条下移再多下约10px到19~21px为止；左右仍原生g0(20)。中性布局尺寸，非视觉仿制。
    private const val STRIP_M_TOP_DP = 1.5f
    private const val STRIP_M_BOTTOM_PX = 20
    // F6挂后post对线轮询上限（150ms×12≈1.8s，覆盖挂载瞬间工具栏暂隐回归；只读几何，
    // 命中即按iconTop重算mBottom=gap-empty微调+requestLayout，不碰顶1.5dp/壳/s0/WRAP+顶垫等）。
    private const val POST_ALIGN_RETRY_MAX = 12
    // F8 stale修（C11实锤：条挂上OPEN_OK 1052/1220/168顶3.5dp PASS，但模块自认barTop=1365
    // iconTop=1365 gap=1，视觉条底1220→图标顶1371缝151px FAIL；151px为灰候选区底，条把工具栏
    // 顶下去后模块对的仍是条挂前旧图标位stale）。条挂后等布局稳定300ms重扫新位现算，
    // 最多12轮；F10起只动条根translationY（工具栏钉死），不碰顶1.5dp/底20px/壳/s0/WRAP+顶垫等）。
    private const val POST_STABLE_DELAY_MS = 300L
    // F14（接F13窗顶跟随，在此基础上改，不reset）：C17实锤底缝视觉160px/日志152px FAIL（要19~21px）；
    // postAlign 12轮空转，totalDy累到1981px但stripBottom恒1213不动；中间态曾gap20 pass=true后回落。
    // 空转根因：F13每轮translationY(+dy)与slot topMargin(-totalDy)互抵净位移≈0（slot上收dy恰抵消条下移dy），
    // 加每轮N三连/float重排重置位移，stripBottom永不动却盲累加到1981。
    // 修：只打条根自身（row translationY优先，不动验出则改走row自身topMargin增量，二选一以复测为准）；
    // 每轮施加后下轮先复测stripBottom真动（delta>=1px）才算落实，不动则停轮记诊断不再累加；
    // totalDy封顶300px超顶停轮；N三连/float只在PASS落实后重算一次，不在每轮重算。
    // 顶1.5dp/底20px/工具栏/键盘钉死/壳/s0/圆角B/DEL/commit/logo/退壳全不动。
    private const val STRIP_SHIFT_CAP_PX = 300
    // F15（接F14条根+复测+封顶300已合入未提交，在此基础上改，不reset）：用户问顶为什么压不下去，
    // 实情条translationY下移132px贴底20px但槽/窗高没跟下来顶留132px灰（C18视觉顶135px），F13槽跟随空转到1981，
    // F14为保底缝把跟随停了。现顶底同成立：恢复槽跟随但带F14同款safeguards，slot topMargin负值跟随量=条累计下移
    // totalDy（每轮复测窗灰顶真动了才累加，不动停轮，封顶300px）；N三连/float只在顶底双PASS后重算一次不在每轮重算。
    // 闭环目标：窗灰顶→条白顶4~7px PASS窗 + 条底→图标顶19~21px PASS窗，双PASS才停轮（最多12轮300ms）。
    // 工具栏/键盘钉死零位移；壳/s0/圆角B/DEL/commit/logo/退壳（槽垫/条位移全还）全不动。
    private const val STRIP_TOP_GAP_MIN_PX = 4
    private const val STRIP_TOP_GAP_MAX_PX = 7
    // F16（接F15双闭环已合入未提交，在此基础上改，不reset）：C19实锤窗灰顶1041条1047~1212(167)工具栏顶1365
    // Q顶1494，槽区1041~1365=324条只占167空132（=之前条下移量，translation与slot跟随互抵净零STALL），
    // 顶6px PASS是窗顶对的底152px FAIL是槽太高。修：量槽实高vs条真高+顶5px+底20px=192差即多余约132，
    // 逐查slot paddingTop/cand原140残留/publish(trueH)与N三连双计/WRAP未收；显式槽高=条真高+25px现算
    // 或等量负bottomMargin二选一以槽实高192±3为准；条translationY清零回布局位(top margin 1.5dp)不再位移贴底，
    // 底20px由槽高保证；顶4~7底19~21双PASS量法不动；工具栏/键盘钉死；壳/s0/圆角B/DEL/commit/logo/退壳全不动。
    private const val STRIP_SLOT_TOL_PX = 3
    // F17（接F16槽192已合入未提交，在此基础上改，不reset）：C20实锤槽自身192 PASS（slotH=192 target=192），
    // 视觉顶5 PASS，工具栏/键盘零位移PASS，但槽底1233→工具栏顶1365=132px夹层，底缝152 FAIL、SLOT-STALL。
    // 夹层在槽外、工具栏上：候选区paddingBottom、工具栏容器topMargin、或中间兄弟视图（空候选列表？）。
    // 修：挂后dump槽底→工具栏顶之间链（各兄弟类名/高/visibility/margin/padding，定位132归属），
    // 空兄弟GONE（记账还账）、padding/margin收零（记账还账），目标槽底→工具栏顶0px
    // （工具栏视觉顶=图标顶-内边距，图标顶对条底20px由槽高192保证）；顶4~7底19~21双PASS量法不动，
    // 槽192±3、工具栏键盘零位移；壳/s0/圆角B/DEL/commit/logo/退壳（夹层视图/垫全还）全不动。
    private const val STRIP_INTER_TOL_PX = 1
    // 底部面板撑开展示：翻译条高度变化→q 收集 Y()→`f.u0(f,0,0,0,true,false,false,55)`
    // 重排浮窗（q.java:483/C0664a + f.java:2090），同参同序调用。
    // jadx 把关键字包名 float 显示成 p000float；dex 实际名为 float（3.5.3/3.5.4 均实证）。
    private val NATIVE_FLOAT_CLASSES = arrayOf(
        "com.tencent.wetype.plugin.hld.float.f",
        "com.tencent.wetype.plugin.hld.p000float.f"
    )
    // 几何常量（中性布局尺寸，非视觉仿制；视觉色/底/图标/字号一律原生，零硬编码兜底）
    private val NATIVE_CLEAR_ICON_NAMES = arrayOf(
        "icon_close_black",
        "icon_tips_close",
        "icon_navigation_close",
        "icon_toolbar_close",
        "ic_clear_black_24",
        "abc_ic_clear_material"
    )
    private const val ROW_PADDING_H_DP = 8f
    private const val ROW_PADDING_V_DP = 4f
    private const val CLEAR_BOX_DP = 32f
    private const val CLEAR_ICON_PADDING_DP = 8f
    private const val ROW_ICON_DP = 20f
    private const val BTN_GAP_DP = 4f

    // F41翻译卡原生复用（用户否决藏栏/少抬升路线，在此基础上改，不reset）：
    // 完全复用中英互译翻译样式（圆角矩形白卡上下两行），充分利用扩充高度，不再纠结只抬高一点。
    // 上行左下拉文本改搜索类型（全量匹配/模糊匹配），下拉切换搜索模式
    // （切模式即换过滤，不过滤逻辑可先stub但UI切换生效）；
    // 右收起复用原生收起（走原生S()/J0/V0/U0/C0/s()还账，经Q0(false)即P0(false)→J0+V0+U0+C0+S+s+N三连）。
    // 下行输入行复用原生输入框（hint改搜索剪贴板，输入即过滤剪贴板列表，复用现有过滤链keywordListener）。
    // 卡高用原生k.getCurrentHeight（q1.e0(d0+156)，k.java 1440-1442），不再钳小窗；
    // J3 clamp 439→192若与翻译卡原生高冲突则以翻译卡原生高为准（仅防爆钳，禁为少抬升而压高）。
    // 工具栏/键盘 following 翻译态原生（不为留缝20去动bar/keyboard，F32下移补偿与翻译卡冲突则删）。
    // 验证门回到翻译态原生可见性判定（q.t0/innerState+toolbar.x.A+k挂载），像素只diag。
    // 任一步原生取不到即整条fail-closed，禁仿制兜底（禁GradientDrawable/硬编码色/系统图标/f0(32)）。
    // 反编译基线：ImeCandidateView candidate_top_view插槽+s0/r0挂载+翻译壳Q0
    // （q.java $t 1429-1544：1482 g3 fast-path，1485-1495 innerState+toolbar.x.A，
    // 1499-1516 r0，1528-1530 A2/e0/N2）→N#J3写mCandidateView LP
    // （窗高=S_cache≈140+k.getCurrentHeight()，k.getCurrentHeight=q1.e0(d0+156)，
    // k.java 1440-1442，X() margins top e0(36)/bottom e0(20)/side g0(20)+lineHeight e0(63)）；
    // 皮肤单例k$t二进制名取；圆角B方案WeTypeSettings.getCornerRadiusXposed。
    private const val SEARCH_MODE_FULL = 0
    private const val SEARCH_MODE_FUZZY = 1
    private const val SEARCH_MODE_FULL_ID = 1001
    private const val SEARCH_MODE_FUZZY_ID = 1002
    @Volatile
    private var searchModeF41: Int = SEARCH_MODE_FULL
    @Volatile
    private var nativeKRefF41: java.lang.ref.WeakReference<View>? = null
    @Volatile
    private var nativeKEditRefF41: java.lang.ref.WeakReference<EditText>? = null
    @Volatile
    private var nativeKModeTvRefF41: java.lang.ref.WeakReference<android.widget.TextView>? = null
    @Volatile
    private var nativeKModeIconRefF41: java.lang.ref.WeakReference<ImageView>? = null
    @Volatile
    private var f41IconHooked = false
    private val f41IconSetterHooked: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet<String>())
    @Volatile
    private var cachedSearchIconRes: Int? = null
    private val nativeKWatchersF41: MutableMap<EditText, TextWatcher> =
        Collections.synchronizedMap(WeakHashMap<EditText, TextWatcher>())
    private fun searchModeNameF41(mode: Int): String = when (mode) {
        SEARCH_MODE_FUZZY -> "模糊匹配"
        else -> "全量匹配"
    }
    // C46小缺口补记（只修C45 FAIL三项，其余双行卡/过滤/收起/零仿制/二次一致不动）：
    // 下拉展开重绑覆盖→拦b#k数据源+展开重喂；直点收起→exit包装+Q0(false)补记；hint竞态→setHint拦+重申。
    private const val SEARCH_HINT_F41 = "搜索剪贴板"
    @Volatile
    private var feedingDropdownF41 = false
    @Volatile
    private var f41DropdownSrcHooked = false
    @Volatile
    private var f41ExpandHooked = false
    @Volatile
    private var f41HintHooked = false
    @Volatile
    private var f41CollapseHooked = false
    @Volatile
    private var inCollapseF41 = false
    private val wrappedExitViewsF41: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val hintLayoutListenersF41: MutableMap<EditText, View.OnLayoutChangeListener> =
        Collections.synchronizedMap(WeakHashMap<EditText, View.OnLayoutChangeListener>())
    // C47隔离+回车直跳（只修用户反馈两项，其余双行卡/下拉/收起/零仿制/圆角B/J3/栏键不动）：
    // 搜索实例隔离：保存/恢复原生watcher，并拦已验证的q.T0/T签名。
    // 回车=sourceContentEditView EditorAction(SEARCH/DONE/GO/回车键)+OnKey双路接jumpBackToClipboardF41。
    @Volatile
    private var f41WatcherGuardHooked = false
    @Volatile
    private var f41TransGuardHooked = false

    /**
     * F5图标线（新缝基准）：centerY=图标cluster均值，halfH=簇内图标高均值/2（现算不写死，
     * 89x89仅参考，禁44px写死），top=centerY-halfH即图标顶（缝基准）。n=簇样本数。
     */
    private data class IconLine(val centerY: Float, val halfH: Float, val top: Float, val n: Int)

    @Volatile
    private var keywordListenerImpl: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackedBoxes: MutableSet<EditText> =
        Collections.newSetFromMap(WeakHashMap<EditText, Boolean>())

    @Volatile
    private var hooked = false

    private const val TAB_CLIPBOARD = 0
    private const val CLIPBOARD_PANEL_ID = 4
    private val tabIndexByHost: MutableMap<Any, Int> =
        Collections.synchronizedMap(WeakHashMap<Any, Int>())
    /**
     * S5e-A4：host/Method 强引用（WeakHashMap 弱键到跳页时可被 GC 致 map 空，
     * 编程式分支静默返 false；实证跳页全程无 jump via programmatic 日志）。
     * 进程级单例持有，不泄漏出进程；上限 8 个 host 轮转防增生。
     */
    private val tabHostStrongRefs: MutableSet<Any> =
        Collections.synchronizedSet(LinkedHashSet<Any>())
    /** host -> f1(int) 强引用（与 host 同轮转，同上限；兜底动态查找保留）。 */
    private val f1MethodStrongByHost: MutableMap<Any, java.lang.reflect.Method> =
        Collections.synchronizedMap(LinkedHashMap<Any, java.lang.reflect.Method>())
    /**
     * S5e-A4：host -> Y0(Bundle) 强引用（实证当前版本切页通道：Y0 单 Bundle 参，
     * 内含 target_tab_index；f1 为 (int,boolean,boolean) 三参，单参直调已失效）。
     */
    private val y0MethodStrongByHost: MutableMap<Any, java.lang.reflect.Method> =
        Collections.synchronizedMap(LinkedHashMap<Any, java.lang.reflect.Method>())
    /**
     * S5e-A4：最近一次 Y0 实包 clone（同形回放只改 target_tab_index；合成包
     * 切页不完整——只改内部态不渲染列表）。
     */
    private val y0BundleStrongByHost: MutableMap<Any, android.os.Bundle> =
        Collections.synchronizedMap(LinkedHashMap<Any, android.os.Bundle>())
    /**
     * S5e-A4：最近一次 i0 实参 arg0（原生 key 对象，无法合成，只能缓存同形回放；
     * 上限 8 轮转，随 host 联动淘汰）。
     */
    private val i0ArgStrongByHost: MutableMap<Any, Any> =
        Collections.synchronizedMap(LinkedHashMap<Any, Any>())
    /** 离页打字流：剪贴板点红钮 → overlayPending → 键盘页顶部长行 → 带词跳回。 */
    @Volatile
    private var overlayPending = false
    @Volatile
    private var pendingKeyword = ""
    @Volatile
    private var hostClassLoader: ClassLoader? = null
    @Volatile
    private var overlayParentRef: java.lang.ref.WeakReference<ViewGroup>? = null
    @Volatile
    private var insetsLogged = false

    // S5：调试广播已删（SEARCH_STRIP/test_text/show链路移除，说明保留）。
    // 验收后主控复验走剪贴板红钮链路（点红钮→键盘条），不再走adb广播。

    fun setKeywordListener(listener: ((String) -> Unit)?) {
        keywordListenerImpl = listener
    }

    fun clearSearch() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearSearchOnMain()
        } else {
            mainHandler.post { clearSearchOnMain() }
        }
    }

    fun install(classLoader: ClassLoader) {
        if (hooked) return
        hostClassLoader = classLoader
        try {
            val s15 = runCatching { Class.forName(S15_CLASS, false, classLoader) }.getOrNull()
            if (s15 == null) {
                AndroidLog.e(TAG, "S15 host class not found, skip search UI mount")
                return
            }
            val triggerNames = setOf("f1", "Y0", "i0", "a1", "d1", "O")
            var count = 0
            for (method in s15.declaredMethods) {
                if (method.name !in triggerNames) continue
                try {
                    method.isAccessible = true
                    method.hookAfter { param ->
                        try {
                            recordTabIndex(param.thisObject, method.name, param.args)
                            ensureSearchUi(param.thisObject)
                        } catch (t: Throwable) {
                            AndroidLog.e(TAG, "ensureSearchUi dispatch failed: ${t.message}")
                        }
                    }
                    count++
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "hook S15#${method.name} failed: ${t.message}")
                }
            }
            hooked = true
            AndroidLog.i(TAG, "hooked S15 methods for search UI mount: $count")
            hookSearchInputRouting(classLoader)
            hookKeyboardTeardown(classLoader)
            hookInsetsForStrip(classLoader)
            // S5e-A4：缓存 k3 剪贴板面板实参（正向导航渲染调用，同形回放用）。
            // F31：J3执行完后钳mCandidateView LP高（条挂载才写，未挂载不动）。
            hookCandidateWindowAfterJ3F31(classLoader)
            hookClipboardPanelToggle(classLoader)
            hookModeIconStability(classLoader)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "install search UI failed: ${t.message}")
        }
    }

    /**
     * S5e-A4：hook model.N#k3 只缓存面板实参（强引用，上限 8 轮转）。
     * 实证用户点图标行调 k3(keyboard.t@CustomPhraseAndClipboard, Bundle{target=0})。
     */


    /**
     * F31（接F30快照已合入未提交，在此基础上改，不reset）：Worker-R反编译定案（3.5.3设备版）
     * 窗候选区高=S_cache+V（V=k.getCurrentHeight()），由N#J3(updateInputView)经m1.r3/q3写进
     * mCandidateView LP高（父按固定LP以EXACTLY impose，ImeCandidateView.onMeasure纯透传）。
     * 我方进翻译壳后摘掉k挂己条，但k对象仍在、V照发，故窗仍按旧高（条住小洞，缝为差值）。
     * Y/collect/u0/P0-Q0均不直驱窗高；ImeCandidateView.onMeasure禁钳（会和父EXACTLY打架）。
     * 修：新增Xposed-after-hook钩N#J3（类名复用NATIVE_CAND_CTL_CLASS即model.N，方法名J3/
     * updateInputView按形找全hook，找不到记日志不炸）。J3执行完后，若我方条已挂载则把
     * mCandidateView LP高改写=条真高+顶1.5dp+底20px现算（复用slotTargetHeightForF16，
     * 不裸写死）+requestLayout；条未挂载一律不动（翻译/其他模式原样）。退壳拆条无需还账
     * （hook条件自带开关：条不在即no-op）。其余顶底槽/栏键钉死/壳/s0/圆角B/DEL/commit/logo全不动。
     */
    private fun hookCandidateWindowAfterJ3F31(classLoader: ClassLoader) {
        try {
            val nClass = runCatching {
                Class.forName(NATIVE_CAND_CTL_CLASS, false, classLoader)
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F31 window clamp: N missing, disabled")
                return
            }
            val targets = nClass.declaredMethods.filter {
                it.name == "J3" || it.name == "updateInputView"
            }
            if (targets.isEmpty()) {
                AndroidLog.e(TAG, "strip F31 window clamp: N#J3(updateInputView) not found, disabled")
                return
            }
            var count = 0
            for (m in targets) {
                try {
                    m.isAccessible = true
                    m.hookAfter { param ->
                        try {
                            clampCandidateWindowAfterJ3F31(param.thisObject)
                        } catch (t: Throwable) {
                            AndroidLog.e(TAG, "strip F31 window clamp apply failed: ${t.message}")
                        }
                    }
                    count++
                    AndroidLog.i(TAG, "strip F31 window clamp: hooked N#${m.name}(" +
                        "${m.parameterTypes.joinToString { it.simpleName }})")
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F31 window clamp: hook N#${m.name} failed: ${t.message}")
                }
            }
            AndroidLog.i(TAG, "strip F31 window clamp installed: $count overload(s)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F31 window clamp install failed: ${t.message}")
        }
    }

    internal fun isSearchStripExpanded(): Boolean {
        if (inCollapseF41) return false
        if (!translatorShellByUs) return false
        if (submitSession.phase in setOf(
                ClipboardSearchSubmitSession.Phase.COMMITTING,
                ClipboardSearchSubmitSession.Phase.EXITING,
                ClipboardSearchSubmitSession.Phase.OPENING
            )) {
            return false
        }
        val card = nativeKRefF41?.get()
        if (card != null) {
            return card.isAttachedToWindow && card.parent != null
        }
        return true
    }

    private fun isClipboardPanel(target: Any?): Boolean {
        val enumObj = target as? Enum<*> ?: return false
        return enumObj.name == "CustomPhraseAndClipboard"
    }

    /**
     * 搜索面板展开态下点击剪贴板按钮：宿主侧同样是 `N#n3(CustomPhraseAndClipboard, bundle)`
     * （keyboard toolbar 的 `voice.C0608t#u`）。原实现按 `k3/p3/l3` 过滤，3.5.4 上这些是
     * int/synthetic 形参，永远匹配不到（实机日志 `switch methods not found, disabled`），
     * 导致搜索壳未收、窗口高残留（中间空白）。现在 `hookBefore` 里同步收壳（含窗高/焦点还账），
     * 再放行原生切页：同一次点击、同一调用栈完成，无延迟。
     */
    private fun hookClipboardPanelToggle(classLoader: ClassLoader) {
        try {
            val nClass = runCatching {
                Class.forName(NATIVE_CAND_CTL_CLASS, false, classLoader)
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip clipboard toggle hook: N missing, disabled")
                return
            }
            val panelClass = runCatching {
                Class.forName("com.tencent.wetype.plugin.hld.keyboard.t", false, classLoader)
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip clipboard toggle hook: panel class missing, disabled")
                return
            }
            val switchMethods = nClass.declaredMethods.filter {
                it.returnType == Void.TYPE &&
                    it.parameterTypes.isNotEmpty() &&
                    it.parameterTypes[0] == panelClass
            }
            if (switchMethods.isEmpty()) {
                AndroidLog.e(TAG, "strip clipboard toggle hook: switch methods not found, disabled")
                return
            }
            var count = 0
            for (m in switchMethods) {
                try {
                    m.isAccessible = true
                    m.hookBefore { param ->
                        val targetPanel = param.args.firstOrNull()
                        if (!isClipboardPanel(targetPanel) || !isSearchStripExpanded()) {
                            return@hookBefore
                        }
                        AndroidLog.i(TAG, "clipboard requested via N#${m.name} while search strip expanded -> collapse synchronously, native switch proceeds")
                        runCatching { collapseStripF41() }
                            .onFailure { AndroidLog.e(TAG, "clipboard toggle collapse failed: ${it.message}") }
                    }
                    count++
                    AndroidLog.i(TAG, "strip clipboard toggle: hooked N#${m.name}")
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip clipboard toggle: hook N#${m.name} failed: ${t.message}")
                }
            }
            AndroidLog.i(TAG, "strip clipboard toggle hook installed: $count method(s)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip clipboard toggle hook failed: ${t.message}")
        }
    }

    /**
     * F31钳值：mCandidateView LP高=条真高+顶1.5dp+底20px现算（复用slotTargetHeightForF16，
     * 与槽目标同口径px空间现量，不裸写死）。门禁：条已挂载（decor内TAG_SEARCH_BOX_CONTAINER
     * VISIBLE且有parent）才写，否则一律不动。只改候选视图自身LP高+requestLayout，已等于目标
     * 即返（防布局回环）；不碰父LP/onMeasure/槽/栏/键盘/壳/s0/圆角/DEL/commit/logo。
     */
    private fun clampCandidateWindowAfterJ3F31(nHost: Any?) {
        try {
            if (nHost == null) return
            val decor = overlayParentRef?.get() ?: return
            // F41：翻译卡原生高为准，不再钳小窗。若原生k在（已复用），一律不动（仅防爆钳，禁为少抬升而压高）。
            // 原生复用点：k#getCurrentHeight（q1.e0(d0+156)），窗高N#J3原生写S_cache+k高。
            val nativeK = runCatching { findTranslatorTopView(decor) }.getOrNull()
            if (nativeK != null && nativeK.parent != null) {
                val kH = runCatching { nativeKHeightF41(nativeK) }.getOrNull()
                AndroidLog.i(TAG, "strip F41 window: SKIP clamp (native k wins kH=$kH，J3原生高为准，仅防爆，diag only)")
                return
            }
            val strip = decor.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) ?: return
            if (strip.visibility != View.VISIBLE || strip.parent == null) return
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { runCatching { clampCandidateWindowAfterJ3F31(nHost) } }
                return
            }
            val target = runCatching {
                slotTargetHeightForF16(strip, STRIP_M_BOTTOM_PX)
            }.getOrNull() ?: return
            if (target <= 0) return
            val candView = findCandidateViewForJ3F31(nHost, decor) ?: run {
                AndroidLog.e(TAG, "strip F31 window clamp: mCandidateView not found, keep orig")
                return
            }
            val lp = candView.layoutParams ?: run {
                AndroidLog.e(TAG, "strip F31 window clamp: candidate LP null, keep orig")
                return
            }
            if (lp.height == target) return
            val before = lp.height
            lp.height = target
            candView.layoutParams = lp
            runCatching { candView.requestLayout() }
            AndroidLog.i(TAG, "strip F31 window clamp: candLP $before->$target " +
                "(strip真高+顶1.5dp+底${STRIP_M_BOTTOM_PX}px现算，J3写值已覆)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F31 window clamp failed: $t")
        }
    }

    /**
     * F31候选视图定位（只读，不碰视图）：先找N实例mCandidateView字段（同名优先，
     * 否则ImeCandidateView类型首个命中，再否则同类实例值首个命中），找不到再回退decor树
     * 按类名找（与mountStripInCandidateContainer同口径BFS）。任一未命中返null，上层keep orig。
     */
    private fun findCandidateViewForJ3F31(nHost: Any, decor: ViewGroup): ViewGroup? {
        return try {
            val cl = hostClassLoader ?: nHost.javaClass.classLoader
            val candCls = runCatching {
                Class.forName(
                    "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView", false, cl
                )
            }.getOrNull()
            val fields = nHost.javaClass.declaredFields
            for (f in fields) {
                if (f.name != "mCandidateView") continue
                try {
                    f.isAccessible = true
                    val v = f.get(nHost) as? ViewGroup
                    if (v != null) return v
                } catch (_: Throwable) {
                    continue
                }
            }
            if (candCls != null) {
                for (f in fields) {
                    if (f.type != candCls) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(nHost) as? ViewGroup
                        if (v != null) return v
                    } catch (_: Throwable) {
                        continue
                    }
                }
                for (f in fields) {
                    try {
                        f.isAccessible = true
                        val v = f.get(nHost) as? ViewGroup ?: continue
                        if (candCls.isInstance(v)) return v
                    } catch (_: Throwable) {
                        continue
                    }
                }
            }
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            var hops = 0
            while (q.isNotEmpty() && hops < 400) {
                val v = q.removeFirst()
                hops++
                if (candCls != null && candCls.isInstance(v)) return v as? ViewGroup
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    // F32（接F31钳窗已合入未提交，在此基础上改，不reset）：C35+crop31肉眼双确认（以此为准）：
    // J3钳439→192x7生效顶5 PASS；但条态工具栏容器卡在barTop1190（候选框1113~1305内）被条
    // （1118~1286）盖住图标，Q顶上移到1320，底缝34/-95 FAIL。基线排布是工具栏在候选框下方
    // 当兄弟（基线候选1340~1365仅25高、栏1365~），钳窗后栏没跟下来。
    // 修：条挂稳态后，把条态工具栏容器（barTop≈1190那个RecyclerView，按类+屏位找，记账）
    // 移到候选框下方：top=候选框底（现量）-栏内衬（图标顶-barTop现量），使图标顶=条底+20px；
    // requestLayout+300ms复测图标可见（像素亮斑n>=5）且缝19~21，否则还账；键盘容器若被钳窗
    // 带跑（Q顶≠基线1494±8）则补偿移回（记账）。退壳拆条全还（栏位/键盘位）。壳/s0/J3钳/
    // 圆角B/DEL/commit/logo全不动。
    private const val STRIP_F32_Q_BASELINE = 1494
    private const val STRIP_F32_Q_TOL_PX = 8
    private const val STRIP_F32_GAP_MIN_PX = 19
    private const val STRIP_F32_GAP_MAX_PX = 21
    /** F32栏位记账（只动translationY，退壳拆条全还；不动LP/垫/边/visibility/条/壳/s0/J3/圆角B/DEL/commit/logo）。 */
    private val stripBarTransOrigF32: MutableMap<View, Float> =
        Collections.synchronizedMap(WeakHashMap<View, Float>())
    /** F32键盘位记账（只动translationY，退壳拆条全还；不动LP/垫/边/条/壳/s0/J3/圆角B/DEL/commit/logo）。 */
    private val stripKbTransOrigF32: MutableMap<View, Float> =
        Collections.synchronizedMap(WeakHashMap<View, Float>())
    @Volatile
    private var stripBarAppliedF32: View? = null
    @Volatile
    private var stripBarDyF32: Float = 0f
    @Volatile
    private var stripKbAppliedF32: View? = null
    @Volatile
    private var stripKbDyF32: Float = 0f

    // F40（接F39保栏已合入未提交，在此基础上改，不reset）：C43 FAIL根因全修。
    // 挂载初值对（mounted iconTop/barTop/gapToIcon=20）但F32-move后logo_iv V->V(isShown=false)、
    // logoContainer G->V单vis、bar y=1190被顶走175、条y压栏；窗F20全程77到不了192、F28三步STALL；
    // 缝stack gap=-95→REVERT；Q F33/F22 qTop=-1连带判挂；toolbar-zero F32-verify PASS假阳。
    // 修：①挂载基线现量现记（mount初值iconTop/barTop/stripBottom，gap 19~21+viewN>=5才记，不过写死1365，
    // 1365仅C44验收参考）；②F32栏不动dy=0只条落位，1190位移/压栏/isShown=false即REVERT fail-closed，
    // 不再新施translationY；③toolbar-zero含isShown双真+位置（iconTop≈基线±8+dy0+F32栏+压栏），否则FAIL/REVERT；
    // ④Q kb/decor回退（QWERTYUIOP>=5+极差<=0.06H+>midY，digit仅可见时要求，对标1494±8，kbId模糊回退+hop放宽）；
    // ⑤窗藏栏不推/显栏推对对象（J3唯一钳439->192不动，push只publish+N/float重刷，不写LP）。
    // 缝19~21+viewN>=5仍唯一门像素只diag、顶4~7、圆角B、收起还账、J3唯一钳、q.t序全不动；
    // 禁仿制禁GradientDrawable/硬编码色/系统图标/f0(32)。
    private const val STRIP_F40_POS_TOL_PX = 8
    @Volatile
    private var stripMountIconTopF40: Int = -1
    @Volatile
    private var stripMountBarTopF40: Int = -1
    @Volatile
    private var stripMountStripBottomF40: Int = -1
    /** F40挂载基线现量现记（只读几何+记账，不碰视图）：mount后条/栏布局稳时记iconTop/barTop/stripBottom；仅gap19~21+viewN>=5才覆盖（mount初值对才记，错位不污染基线）。 */
    private fun noteMountBaselineF40(decor: ViewGroup, row: View) {
        // F41丢弃：F40挂载基线（藏栏/少抬升门）不再记，回到翻译态原生判定，像素只diag。
        AndroidLog.i(TAG, "strip F41 baseline: SKIP F40 mount-baseline (translation native wins, diag only)")
        if (true) return
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER || row.parent == null) return
            val rloc = IntArray(2)
            runCatching { row.getLocationOnScreen(rloc) }
            if (rloc[1] <= 0 || row.height <= 0) return
            val stripBottom = rloc[1] + row.height
            val logo = runCatching { resolveLogoView(decor) }.getOrNull()
            val viewLine = runCatching { scanSquareIconLine(decor, logo) }.getOrNull() ?: run {
                AndroidLog.i(TAG, "strip F40 mount-baseline: SKIP viewLine missing (fail-closed，不污染基线)")
                return
            }
            if (viewLine.top <= 0 || viewLine.n < 5) {
                AndroidLog.i(TAG, "strip F40 mount-baseline: SKIP viewN=${viewLine.n} top=${viewLine.top.toInt()} (需n>=5才记)")
                return
            }
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
            val bloc = IntArray(2)
            if (bar != null) runCatching { bar.getLocationOnScreen(bloc) }
            val barTop = if (bar != null) bloc[1] else -1
            if (barTop <= 0) {
                AndroidLog.i(TAG, "strip F40 mount-baseline: SKIP barTop unlaid=$barTop (fail-closed)")
                return
            }
            val gap = (viewLine.top - stripBottom).toInt()
            if (gap !in STRIP_F32_GAP_MIN_PX..STRIP_F32_GAP_MAX_PX) {
                AndroidLog.i(TAG, "strip F40 mount-baseline: SKIP gap=$gap(需19~21才记，不污染基线) " +
                    "viewTop=${viewLine.top.toInt()} n=${viewLine.n} barTop=$barTop stripBottom=$stripBottom")
                return
            }
            stripMountIconTopF40 = viewLine.top.toInt()
            stripMountBarTopF40 = barTop
            stripMountStripBottomF40 = stripBottom
            AndroidLog.i(TAG, "strip F40 mount-baseline: iconTop=$stripMountIconTopF40 barTop=$stripMountBarTopF40 " +
                "stripBottom=$stripMountStripBottomF40 gap=$gap(19~21) viewN=${viewLine.n} (mount初值，bar不动基准，stripBottom+20≈iconTop)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F40 mount-baseline failed: $t")
        }
    }
    /** F40基线清理（退壳拆条全还时调，幂等，只清记账不碰视图）。 */
    private fun clearMountBaselineF40() {
        stripMountIconTopF40 = -1
        stripMountBarTopF40 = -1
        stripMountStripBottomF40 = -1
    }
    /** F40祖先显性链（只动VISIBLE，不碰LP/垫/边/位移/条/壳/s0/J3/圆角B/DEL/commit/logo自身值）：把v到decor直系祖先中GONE/INVISIBLE逐个VISIBLE+requestLayout，返改动数。isShown=false多为祖先藏，用此还账。 */
    private fun ensureAncestorsVisibleF40(v: View, decor: ViewGroup): Int {
        var n = 0
        try {
            var p = v.parent
            var guard = 0
            while (p is ViewGroup && guard < 10) {
                if (p === decor) break
                if (p.visibility != View.VISIBLE) {
                    runCatching { p.visibility = View.VISIBLE }
                    runCatching { p.requestLayout() }
                    n++
                }
                if (p.parent == null) break
                p = p.parent
                guard++
            }
            if (n > 0) runCatching { v.requestLayout() }
        } catch (_: Throwable) {
        }
        return n
    }

    // F39保栏（接F34残留清理，在此基础上改，不reset）：剪贴板搜索态强制保栏，
    // translating=true亦须VISIBLE可测，不做三态判定，不断言藏栏合理；
    // 零位移以customToolbarRv/logo/logoContainerRl+bar行dy=0且VISIBLE判定；
    // Q基线只留normal=1494±8（translating藏栏1404仅真正翻译态，搜索态不走）；
    // 工具栏探测加Q行显式veto；bar定位排除Q容器子树。F32几何+F33门不动，
    // 缝19~21+视图n>=5仍唯一门，像素只diag，窗windowBar 192±4（F31 439->192+正确push），
    // q-after保持SKIP/PASS/DRIFT-diag三分态DRIFT只diag不REVERT；fail-closed禁仿制兜底
    // （禁GradientDrawable/硬编码色/系统图标），圆角B仍WeTypeSettings.getCornerRadiusXposed，
    // J3唯一钳点、挂条全序q.t r0→A2/e0/N2→J3不动，收起/跳回全还账不动。
    private data class QRowDetailF34(
        val qTop: Int,
        val qTops: List<Int>,
        val qCount: Int,
        val digitTop: Int?,
        val digitCount: Int,
        val qLefts: List<Int>,
        val qDxs: List<Int>,
        val qKeyH: Int,
        val midY: Int,
        val decorH: Int
    )
    /** F34 Q行明细（复用F33 findQTopOnScreen可信门：kb容器内QWERTYUIOP>=5+极差<=0.06H+digitTop<QTop+>midY；附lefts/dxs/keyH供veto比对10键11缝）。缺测返null。只读。 */
    private fun qRowDetailF34(decor: ViewGroup): QRowDetailF34? {
        return try {
            val cl = hostClassLoader ?: decor.context?.classLoader ?: return null
            val kbId = runCatching { resolveKeyboardContainerId(cl) }.getOrNull() ?: return null
            val kbScope = runCatching { decor.findViewById<View>(kbId) as? ViewGroup }.getOrNull() ?: return null
            val dloc = IntArray(2)
            runCatching { decor.getLocationOnScreen(dloc) }
            val decorH = decor.height.takeIf { it > 0 } ?: return null
            val midY = dloc[1] + (decorH * 0.5f).toInt()
            val digitSet = setOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
            val qSet = setOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
            var digitBest: Int? = null
            var digitCount = 0
            var qBest: Int? = null
            var qCount = 0
            val qTops = ArrayList<Int>()
            val qLefts = ArrayList<Int>()
            var qHsum = 0
            var qHn = 0
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(kbScope)
            var hops = 0
            // F40：hop 600→800（kb子树深键偶发截断致qTop=-1连带判挂；800与他处同量级，仍有界）。
            while (q.isNotEmpty() && hops < 800) {
                val v = q.removeFirst()
                hops++
                if (v.getTag() == TAG_SEARCH_BUTTON || v.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                    v.getTag() == TAG_SEARCH_CLEAR || v.getTag() == TAG_SEARCH_BOX
                ) continue
                if (v is android.widget.TextView && v.visibility == View.VISIBLE && v.isShown) {
                    val t = runCatching { v.text?.toString()?.trim() }.getOrNull() ?: ""
                    if (t.length == 1) {
                        val up = t.uppercase()
                        val loc = IntArray(2)
                        runCatching { v.getLocationOnScreen(loc) }
                        val laid = loc[1] > 0 && v.width > 0 && v.height > 0
                        if (laid && loc[1] > midY) {
                            if (t in digitSet) {
                                digitCount++
                                if (digitBest == null || loc[1] < digitBest!!) digitBest = loc[1]
                                continue
                            }
                            if (up in qSet) {
                                qCount++
                                qTops.add(loc[1])
                                qLefts.add(loc[0])
                                if (v.height > 0) { qHsum += v.height; qHn++ }
                                if (qBest == null || loc[1] < qBest!!) qBest = loc[1]
                                continue
                            }
                        }
                        continue
                    }
                }
                if (v is ViewGroup) {
                    for (idx in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(idx)?.let { q.add(it) }
                    }
                }
            }
            if (qBest == null || qCount < 5) return null
            qTops.sort()
            qLefts.sort()
            val qRange = if (qTops.size >= 2) qTops.last() - qTops.first() else 0
            val rangeTol = (decorH * 0.06f).toInt().coerceAtLeast(28)
            if (qBest <= midY) return null
            if (qRange > rangeTol) return null
            // F40：digit仅可见时要求（digitBest==null即放宽，对标1494±8由调用方另判；此处逆序才拒）。
            if (digitBest != null && qBest < digitBest) return null
            val dxs = ArrayList<Int>()
            for (k in 0 until qLefts.size - 1) dxs.add(qLefts[k + 1] - qLefts[k])
            val keyH = if (qHn > 0) qHsum / qHn else 0
            QRowDetailF34(qBest, qTops.toList(), qCount, digitBest, digitCount, qLefts.toList(), dxs, keyH, midY, decorH)
        } catch (_: Throwable) {
            null
        }
    }
    /** F34 Q误检veto（只读）：toolbar簇cy落入Q带或dxs≈Q键距即true判Q误检（返null skip不记iconTop）；Q明细缺测返false不veto。 */
    private fun isQMisdetectVetoF34(decor: ViewGroup, clusterCy: Float, clusterLefts: List<Int>, wPx: Int): Boolean {
        return try {
            val qd = runCatching { qRowDetailF34(decor) }.getOrNull() ?: return false
            val keyH = qd.qKeyH.takeIf { it > 0 } ?: (qd.decorH * 0.03f).toInt().coerceAtLeast(28)
            val qTop = qd.qTop
            val qBottom = qTop + keyH
            if (clusterCy >= (qTop - 28f) && clusterCy <= (qBottom + 28f)) {
                AndroidLog.e(TAG, "strip F34 Q veto: cy落入Q带 skip clusterCy=${clusterCy.toInt()} Q带=[$qTop,$qBottom]+28 " +
                    "qN=${qd.qCount} digitTop=${qd.digitTop ?: -1} midY=${qd.midY} (10键11缝Q误检，不记iconTop)")
                return true
            }
            if (clusterLefts.size >= 2 && qd.qDxs.isNotEmpty()) {
                val cSorted = clusterLefts.sorted()
                val cDxs = ArrayList<Int>()
                for (k in 0 until cSorted.size - 1) cDxs.add(cSorted[k + 1] - cSorted[k])
                if (cDxs.isNotEmpty()) {
                    val cMed = cDxs.sorted()[cDxs.size / 2]
                    val qMed = qd.qDxs.sorted()[qd.qDxs.size / 2]
                    val tol = (wPx * 0.02f).toInt().coerceAtLeast(8)
                    if (kotlin.math.abs(cMed - qMed) <= tol) {
                        AndroidLog.e(TAG, "strip F34 Q veto: dxs≈Q键距 skip cMed=$cMed qMed=$qMed tol=0.02W=$tol " +
                            "cDxs=$cDxs qDxs=${qd.qDxs} qTop=$qTop (Q误检，不记iconTop)")
                        return true
                    }
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }
    /** F39 customToolbarRv定位（全宿主原生s字段现取，exact优先fuzzy回退，找不到返null fail-closed；禁仿制兜底禁GradientDrawable/硬编码色/系统图标）。 */
    private fun resolveCustomToolbarViewF39(decor: ViewGroup): View? {
        return try {
            val cl = hostClassLoader ?: decor.context?.classLoader ?: return null
            val sCls = runCatching { Class.forName(WETYPE_ID_CLASS, false, cl) }.getOrNull() ?: return null
            val exacts = arrayOf("customToolbarRv", "custom_toolbar_rv", "toolbar_rv", "customToolbarRecyclerView")
            for (n in exacts) {
                val id = runCatching { sCls.getField(n).getInt(null) }.getOrNull()
                    ?: runCatching { sCls.getDeclaredField(n).also { it.isAccessible = true }.getInt(null) }.getOrNull()
                if (id != null && id != 0) {
                    val v = runCatching { decor.findViewById<View>(id) }.getOrNull()
                    if (v != null) return v
                }
            }
            for (f in sCls.declaredFields) {
                try {
                    if (f.type != Int::class.javaPrimitiveType && f.type != Integer::class.java) continue
                    val nm = f.name.lowercase()
                    if (!nm.contains("toolbar")) continue
                    if (!(nm.contains("custom") || nm.contains("rv") || nm.contains("recycler") || nm.contains("list"))) continue
                    f.isAccessible = true
                    val id = runCatching { f.getInt(null) }.getOrNull() ?: continue
                    if (id == 0) continue
                    val v = runCatching { decor.findViewById<View>(id) }.getOrNull()
                    if (v != null) return v
                } catch (_: Throwable) {
                    continue
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
    /** F39 logoContainerRl定位（全宿主原生s字段现取，exact优先fuzzy回退，找不到返null fail-closed；禁仿制兜底）。 */
    private fun resolveLogoContainerViewF39(decor: ViewGroup): View? {
        return try {
            val cl = hostClassLoader ?: decor.context?.classLoader ?: return null
            val sCls = runCatching { Class.forName(WETYPE_ID_CLASS, false, cl) }.getOrNull() ?: return null
            val exacts = arrayOf("logoContainerRl", "logo_container_rl", "logoContainer", "toolbar_logo_container")
            for (n in exacts) {
                val id = runCatching { sCls.getField(n).getInt(null) }.getOrNull()
                    ?: runCatching { sCls.getDeclaredField(n).also { it.isAccessible = true }.getInt(null) }.getOrNull()
                if (id != null && id != 0) {
                    val v = runCatching { decor.findViewById<View>(id) }.getOrNull()
                    if (v != null) return v
                }
            }
            for (f in sCls.declaredFields) {
                try {
                    if (f.type != Int::class.javaPrimitiveType && f.type != Integer::class.java) continue
                    val nm = f.name.lowercase()
                    if (!nm.contains("logo")) continue
                    if (!(nm.contains("container") || nm.contains("rl") || nm.contains("layout"))) continue
                    f.isAccessible = true
                    val id = runCatching { f.getInt(null) }.getOrNull() ?: continue
                    if (id == 0) continue
                    val v = runCatching { decor.findViewById<View>(id) }.getOrNull()
                    if (v != null) return v
                } catch (_: Throwable) {
                    continue
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
    /** F39 toolbar.x.A读（只读）：toolbar视图x字段->A字段->getValue() Boolean，任一步缺失返null fail-closed。 */
    private fun queryToolbarLiveF39(toolbarView: View?): Boolean? {
        return try {
            if (toolbarView == null) return null
            var c: Class<*>? = toolbarView.javaClass
            var xField: java.lang.reflect.Field? = null
            while (c != null && c != Any::class.java && c != View::class.java && c != ViewGroup::class.java) {
                xField = runCatching { c.getDeclaredField("x") }.getOrNull()
                if (xField != null) break
                c = c.superclass
            }
            xField ?: return null
            xField.isAccessible = true
            val xObj = runCatching { xField.get(toolbarView) }.getOrNull() ?: return null
            var c2: Class<*>? = xObj.javaClass
            var aField: java.lang.reflect.Field? = null
            while (c2 != null && c2 != Any::class.java) {
                aField = runCatching { c2.getDeclaredField("A") }.getOrNull()
                if (aField != null) break
                c2 = c2.superclass
            }
            aField ?: return null
            aField.isAccessible = true
            val live = runCatching { aField.get(xObj) }.getOrNull() ?: return null
            val getValue = runCatching {
                live.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterTypes.isEmpty() }
            }.getOrNull() ?: return null
            getValue.isAccessible = true
            runCatching { getValue.invoke(live) as? Boolean }.getOrNull()
        } catch (_: Throwable) {
            null
        }
    }
    /** F39 toolbar.x.A写回（原生调用为准）：live==true才setValue(false)恢复显栏；缺失fail-closed不动；禁硬写死高度。返true=已恢复。 */
    private fun tryRestoreToolbarLiveF39(decor: ViewGroup): Boolean {
        return try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { runCatching { tryRestoreToolbarLiveF39(decor) } }
                return false
            }
            val custom = runCatching { resolveCustomToolbarViewF39(decor) }.getOrNull()
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
            val anchor: View = custom ?: bar ?: return false
            val cur = runCatching { queryToolbarLiveF39(anchor) }.getOrNull() ?: return false
            if (cur != true) return false
            var c: Class<*>? = anchor.javaClass
            var xField: java.lang.reflect.Field? = null
            while (c != null && c != Any::class.java && c != View::class.java && c != ViewGroup::class.java) {
                xField = runCatching { c.getDeclaredField("x") }.getOrNull()
                if (xField != null) break
                c = c.superclass
            }
            xField ?: return false
            xField.isAccessible = true
            val xObj = runCatching { xField.get(anchor) }.getOrNull() ?: return false
            var c2: Class<*>? = xObj.javaClass
            var aField: java.lang.reflect.Field? = null
            while (c2 != null && c2 != Any::class.java) {
                aField = runCatching { c2.getDeclaredField("A") }.getOrNull()
                if (aField != null) break
                c2 = c2.superclass
            }
            aField ?: return false
            aField.isAccessible = true
            val live = runCatching { aField.get(xObj) }.getOrNull() ?: return false
            val setValue = runCatching {
                live.javaClass.methods.firstOrNull {
                    it.name == "setValue" && it.parameterTypes.size == 1
                }
            }.getOrNull() ?: run {
                val postValue = runCatching {
                    live.javaClass.methods.firstOrNull {
                        it.name == "postValue" && it.parameterTypes.size == 1
                    }
                }.getOrNull() ?: return false
                postValue.isAccessible = true
                runCatching { postValue.invoke(live, false) }
                AndroidLog.i(TAG, "strip F39 keep-bar live.x.A true->false via postValue (原生还账，禁硬写死高度)")
                return true
            }
            setValue.isAccessible = true
            runCatching { setValue.invoke(live, false) }
            AndroidLog.i(TAG, "strip F39 keep-bar live.x.A true->false via setValue (原生还账，禁硬写死高度)")
            true
        } catch (_: Throwable) {
            false
        }
    }
    /** F39保栏（挂条路径显式恢复/阻止藏）：customToolbarRv/logo/logoContainerRl原生取后VISIBLE且dy=0；
     * 翻译链自动藏栏则先经toolbar.x.A原生还账（setValue false），再直复VISIBLE/dy=0；取不到fail-closed不动；
     * 禁仿制兜底禁GradientDrawable/硬编码色/系统图标；禁硬写死高度；J3唯一钳点不动。返true=有视图可保。 */
    private fun ensureToolbarVisibleF39(decor: ViewGroup, tag: String): Boolean {
        // F41丢弃：F39保栏强改不再执行。工具栏following翻译态原生（藏栏为翻译原生行为，不强制显栏）。
        AndroidLog.i(TAG, "strip F41 keep-bar [$tag]: SKIP F39 (translation native wins, diag only)")
        if (true) return false
        return try {
            if (runCatching { isAiBarShowing(decor) }.getOrDefault(false)) {
                AndroidLog.i(TAG, "strip F39 keep-bar [$tag]: SKIP AI条态logo暂隐不强制 (fail-closed)")
                return false
            }
            runCatching { tryRestoreToolbarLiveF39(decor) }
            val custom = runCatching { resolveCustomToolbarViewF39(decor) }.getOrNull()
            val logo = runCatching { resolveLogoView(decor) }.getOrNull()
            val logoC = runCatching { resolveLogoContainerViewF39(decor) }.getOrNull()
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
            if (custom == null && logo == null && logoC == null && bar == null) {
                AndroidLog.e(TAG, "strip F39 keep-bar [$tag]: SKIP all-missing (fail-closed)")
                return false
            }
            var changed = false
            val parts = ArrayList<String>()
            fun one(nm: String, v: View?) {
                if (v == null) {
                    parts.add("$nm:null")
                    return
                }
                val vis = v.visibility
                val visStr = when (vis) { View.VISIBLE -> "V"; View.GONE -> "G"; View.INVISIBLE -> "I"; else -> "$vis" }
                val dy = runCatching { v.translationY }.getOrDefault(0f)
                val needVis = (vis != View.VISIBLE || !v.isShown)
                // F40：bar不动dy=0无豁免（F39的isF32Bar豁免致1190位移残留，已删；F32不再新施，残留由还账清）。
                val needDy = kotlin.math.abs(dy) > 1f
                if (needVis && v.parent != null) {
                    runCatching { v.visibility = View.VISIBLE }
                    runCatching { v.requestLayout() }
                    (v.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
                    changed = true
                }
                // F40：VISIBLE但isShown=false多为直系祖先藏（C43 logo_iv V->V isShown=false），逐级还显。
                if (v.visibility == View.VISIBLE && !v.isShown && v.parent != null) {
                    val ancN = runCatching { ensureAncestorsVisibleF40(v, decor) }.getOrDefault(0)
                    if (ancN > 0) {
                        runCatching { v.requestLayout() }
                        changed = true
                    }
                    parts.add("$nm:ancestors+$ancN")
                }
                if (needDy && v.parent != null) {
                    runCatching { v.translationY = 0f }
                    runCatching { v.requestLayout() }
                    (v.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
                    changed = true
                }
                val loc = IntArray(2)
                runCatching { v.getLocationOnScreen(loc) }
                val afterVis = v.visibility
                val afterStr = when (afterVis) { View.VISIBLE -> "V"; View.GONE -> "G"; View.INVISIBLE -> "I"; else -> "$afterVis" }
                parts.add("$nm:$visStr->${afterStr}(isShown=${v.isShown} dy=${dy.toInt()}->${v.translationY.toInt()} y=${loc[1]} h=${v.height})")
            }
            one("customToolbarRv", custom)
            one("logo_iv", logo)
            one("logoContainerRl", logoC)
            if (bar != null && bar !== custom && bar !== logo && bar !== logoC) one("bar", bar)
            // F40：双真（VISIBLE+isShown，bar亦含isShown；F39只验bar vis致假阳，已修；logoContainer G->V须双真）。
            val allVis = listOf(custom, logo, logoC).filterNotNull().all { it.visibility == View.VISIBLE && it.isShown } &&
                (bar == null || (bar.visibility == View.VISIBLE && bar.isShown))
            if (allVis) {
                AndroidLog.i(TAG, "strip F39 keep-bar [$tag]: VISIBLE visOk=true changed=$changed " + parts.joinToString(" | ") + " (搜索态强制保栏，translating藏栏不跟，dy=0)")
            } else {
                AndroidLog.e(TAG, "strip F40 keep-bar [$tag]: FAIL visOk=false changed=$changed " + parts.joinToString(" | ") + " (保住isShown=true，logo_iv isShown=false即FAIL还账不判PASS，logoContainer须VISIBLE+isShown双真)")
            }
            allVis
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F39 keep-bar failed [$tag]: $t")
            false
        }
    }
    /** F40工具栏零位移判定（只读，gateF32 verify）：custom/logo/logoC/bar行dy=0且VISIBLE+isShown双真才是PASS；
     * 另含位置（iconTop≈mount基线±8，现量不写死1365；1365仅C44验收参考）+F32栏dy+压栏（条须在栏上，不许条y压栏）。
     * 任一不满足即FAIL（F39的VISIBLE-vis假PASS已修）；Q位移由q-after按1494±8另判。原生取不到即SKIP fail-closed。返true=PASS/false=FAIL/null=SKIP。 */
    private fun verifyToolbarZeroShiftF34(decor: ViewGroup, tag: String): Boolean? {
        // F41丢弃：验证门回到翻译态原生可见性判定，像素只diag。本门不再gate（返null=SKIP diag）。
        AndroidLog.i(TAG, "strip F41 verify [$tag]: SKIP F34 toolbar-zero (translation native wins, diag only)")
        runCatching { verifyNativeTranslationStateDiagF41(decor, "F34-$tag") }
        if (true) return null
        return try {
            val logo = runCatching { resolveLogoView(decor) }.getOrNull()
            val custom = runCatching { resolveCustomToolbarViewF39(decor) }.getOrNull()
            val logoC = runCatching { resolveLogoContainerViewF39(decor) }.getOrNull()
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
            val targets = ArrayList<Triple<String, View, Int>>()
            if (logo != null) targets.add(Triple("logo_iv", logo, logo.visibility))
            if (custom != null) targets.add(Triple("customToolbarRv", custom, custom.visibility))
            if (logoC != null) targets.add(Triple("logoContainerRl", logoC, logoC.visibility))
            if (targets.isEmpty() && bar == null) {
                AndroidLog.i(TAG, "strip F34 toolbar-zero [$tag]: SKIP toolbar/logo全缺测 (fail-closed)")
                return null
            }
            if (targets.isEmpty() && bar != null) targets.add(Triple("barFallback", bar, bar.visibility))
            var allDyZero = true
            var allVisOk = true
            val parts = ArrayList<String>()
            for ((nm, v, vis) in targets) {
                val dy = runCatching { v.translationY }.getOrDefault(0f)
                val dyOk = kotlin.math.abs(dy) <= 1f
                if (!dyOk) allDyZero = false
                val visStr = when (vis) { View.VISIBLE -> "V"; View.GONE -> "G"; View.INVISIBLE -> "I"; else -> "$vis" }
                val visOk = (vis == View.VISIBLE && v.isShown)
                if (!visOk) allVisOk = false
                val loc = IntArray(2)
                runCatching { v.getLocationOnScreen(loc) }
                parts.add("$nm:dy=${dy.toInt()}(ok=$dyOk) vis=$visStr(isShown=${v.isShown} ok=$visOk) y=${loc[1]} h=${v.height}")
            }
            // F40：F32栏位移亦须dy=0（targets非空时bar被排除致1190假阳，已修；此处显式补验）。
            var f32DyOk = true
            val f32Bar = stripBarAppliedF32
            if (f32Bar != null && f32Bar.parent != null &&
                bar != null && f32Bar !== custom && f32Bar !== logo && f32Bar !== logoC && f32Bar !== bar
            ) {
                val fdy = runCatching { f32Bar.translationY }.getOrDefault(0f)
                f32DyOk = kotlin.math.abs(fdy) <= 1f
                if (!f32DyOk) allDyZero = false
                val floc = IntArray(2)
                runCatching { f32Bar.getLocationOnScreen(floc) }
                val fvisOk = (f32Bar.visibility == View.VISIBLE && f32Bar.isShown)
                if (!fvisOk) allVisOk = false
                parts.add("f32bar:dy=${fdy.toInt()}(ok=$f32DyOk) visOk=$fvisOk y=${floc[1]} h=${f32Bar.height}")
            } else if (bar != null && (targets.none { it.second === bar })) {
                // F40：原生bar未在targets内时亦补dy/isShown（barTop1190位移须FAIL）。
                val bdy = runCatching { bar.translationY }.getOrDefault(0f)
                val bdyOk = kotlin.math.abs(bdy) <= 1f
                if (!bdyOk) allDyZero = false
                val bloc0 = IntArray(2)
                runCatching { bar.getLocationOnScreen(bloc0) }
                val bvisOk = (bar.visibility == View.VISIBLE && bar.isShown)
                if (!bvisOk) allVisOk = false
                parts.add("bar:dy=${bdy.toInt()}(ok=$bdyOk) visOk=$bvisOk y=${bloc0[1]} h=${bar.height}")
            }
            // F40位置：iconTop≈mount基线±8（现量现比，不写死1365；基线缺测则SKIP位置不FAIL）。
            var posOk: Boolean? = null
            var viewTopF40 = -1
            var baseTopF40 = -1
            val viewLineF40 = runCatching { scanSquareIconLine(decor, logo) }.getOrNull()
            if (viewLineF40 != null && viewLineF40.top > 0) {
                viewTopF40 = viewLineF40.top.toInt()
                baseTopF40 = stripMountIconTopF40
                if (baseTopF40 > 0) {
                    posOk = kotlin.math.abs(viewTopF40 - baseTopF40) <= STRIP_F40_POS_TOL_PX
                }
            }
            // F40栏位：barTop≈mount基线±8（1190位移即FAIL；基线缺测则SKIP）。
            var barPosOk: Boolean? = null
            var barTopNowF40 = -1
            if (bar != null) {
                val bl = IntArray(2)
                runCatching { bar.getLocationOnScreen(bl) }
                barTopNowF40 = bl[1]
                val baseBar = stripMountBarTopF40
                if (barTopNowF40 > 0 && baseBar > 0) {
                    barPosOk = kotlin.math.abs(barTopNowF40 - baseBar) <= STRIP_F40_POS_TOL_PX
                }
            }
            // F40压栏：条须在栏上（stripBottom<=barTop），条y压栏即FAIL。
            var overlapF40 = false
            var stripBottomF40 = -1
            val rowF40 = runCatching { decor.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) }.getOrNull()
            if (rowF40 != null && barTopNowF40 > 0) {
                val rl = IntArray(2)
                runCatching { rowF40.getLocationOnScreen(rl) }
                if (rl[1] > 0 && rowF40.height > 0) {
                    stripBottomF40 = rl[1] + rowF40.height
                    overlapF40 = stripBottomF40 > barTopNowF40
                }
            }
            val posGate = (posOk == null || posOk == true)
            val barGate = (barPosOk == null || barPosOk == true)
            val pass = allDyZero && allVisOk && posGate && barGate && !overlapF40
            if (pass) {
                AndroidLog.i(TAG, "strip F40 toolbar-zero [$tag]: PASS " +
                    "dy0=$allDyZero visOk=$allVisOk(VISIBLE+isShown双真) " +
                    "posOk=${posOk ?: "skip"}(viewTop=$viewTopF40≈base=$baseTopF40±$STRIP_F40_POS_TOL_PX) " +
                    "barPosOk=${barPosOk ?: "skip"}(barTop=$barTopNowF40) " +
                    "overlap=$overlapF40(stripBottom=${stripBottomF40}须<=barTop，不许条y压栏) " +
                    parts.joinToString(" | ") + " (Q位移由q-after按1494±8另判)")
            } else {
                AndroidLog.e(TAG, "strip F40 toolbar-zero [$tag]: FAIL " +
                    "dy0=$allDyZero visOk=$allVisOk(VISIBLE+isShown双真，logo_iv isShown=false即FAIL) " +
                    "posOk=${posOk ?: "skip"}(viewTop=$viewTopF40≈base=$baseTopF40±$STRIP_F40_POS_TOL_PX) " +
                    "barPosOk=${barPosOk ?: "skip"}(barTop=$barTopNowF40，1190位移即FAIL) " +
                    "overlap=$overlapF40(stripBottom=${stripBottomF40}须<=barTop，不许条y压栏) " +
                    parts.joinToString(" | ") + " (去假阳：VISIBLE-vis不判PASS)")
            }
            pass
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F34 toolbar-zero failed: $t")
            null
        }
    }
    // F35/F37已剔除（审计定案：无用户拍板且与铁律冲突）：emit兜底与隐藏链路删除，miss仅记常规diag。

    /**
     * F32栏容器定位（只读）：按类+屏位找条态工具栏RecyclerView（barTop≈1190那个）。
     * 类含RecyclerView、VISIBLE、宽高>0、自家tag排除；屏位须在候选框纵向内
     * （candTop-50~candBottom，现量不写死1190；多个取top最小即最靠上候选框内那个）。
     * F34加：RecyclerView in cand[Top-50,Bottom]外再排除Q容器子树（kb容器内即Q键行容器，
     * 含Q即跳过防Q误检为栏；Q明细缺测则不排除fail-closed）。未命中返null，上层fail-closed不动。
     */
    private fun findBarRecyclerF32(decor: ViewGroup, candTop: Int, candBottom: Int): ViewGroup? {
        return try {
            val cands = ArrayList<Pair<ViewGroup, Int>>()
            // F34 Q容器子树排除（只读）：kb容器即Q键行所在子树，栏绝不在其内；在其内即Q误检跳过。
            val kbScopeF34: ViewGroup? = runCatching {
                val cl = hostClassLoader ?: decor.context?.classLoader ?: return@runCatching null
                val kbId = resolveKeyboardContainerId(cl) ?: return@runCatching null
                decor.findViewById<View>(kbId) as? ViewGroup
            }.getOrNull()
            var qExcludedF34 = 0
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            var hops = 0
            while (q.isNotEmpty() && hops < 800) {
                val v = q.removeFirst()
                hops++
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
                if (v === decor) continue
                if (v.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                    v.getTag() == TAG_SEARCH_BUTTON ||
                    v.getTag() == TAG_SEARCH_CLEAR ||
                    v.getTag() == TAG_SEARCH_BOX
                ) continue
                if (v !is ViewGroup) continue
                if (!v.javaClass.name.contains("RecyclerView")) continue
                if (v.visibility != View.VISIBLE || !v.isShown) continue
                if (v.width <= 0 || v.height <= 0) continue
                val loc = IntArray(2)
                runCatching { v.getLocationOnScreen(loc) }
                val top = loc[1]
                if (top <= 0) continue
                if (candTop > 0 && candBottom > candTop) {
                    if (top < candTop - 50 || top > candBottom) continue
                }
                if (v.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) != null) continue
                // F34：Q容器子树内即Q键行误检，跳过不记bar（kb容器子树=Q容器，栏不在其内）。
                if (kbScopeF34 != null && (v === kbScopeF34 || isAncestorOf(kbScopeF34, v))) {
                    qExcludedF34++
                    continue
                }
                cands.add(v to top)
            }
            if (cands.isEmpty()) {
                // F36 inKb门（审计后：只留kb本身判定）：cands==0时diagBar ConstraintLayout回退，
                // 仅当bar自身即kb容器（===或id==kb容器id）才veto；我条排除仍按contains+自tag判定。
                // 取不到即fail-closed不动。F32几何+F33门+toolbar dy=0+圆角B不动。
                val nativeBarF35 = runCatching { findStripToolbarBar(decor) }.getOrNull()
                if (nativeBarF35 != null) {
                    val clsF35 = nativeBarF35.javaClass.name
                    val isClF35 = clsF35.contains("ConstraintLayout")
                    val locF35 = IntArray(2)
                    runCatching { nativeBarF35.getLocationOnScreen(locF35) }
                    val topF35 = locF35[1]
                    var inRangeF35 = true
                    if (candTop > 0 && candBottom > candTop) {
                        if (topF35 < candTop - 50 || topF35 > candBottom) inRangeF35 = false
                    }
                    val hasStripF35 = nativeBarF35.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) != null
                    val inKbF35 = kbScopeF34 != null && (nativeBarF35 === kbScopeF34 || isAncestorOf(kbScopeF34, nativeBarF35))
                    val visOkF35 = nativeBarF35.visibility == View.VISIBLE && nativeBarF35.isShown &&
                        nativeBarF35.width > 0 && nativeBarF35.height > 0 && topF35 > 0
                    val selfTagF36 = nativeBarF35.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                        nativeBarF35.getTag() == TAG_SEARCH_BUTTON ||
                        nativeBarF35.getTag() == TAG_SEARCH_CLEAR ||
                        nativeBarF35.getTag() == TAG_SEARCH_BOX
                    val isKbItselfF36 = kbScopeF34 != null && (nativeBarF35 === kbScopeF34 ||
                        (nativeBarF35.id != View.NO_ID && kbScopeF34.id != View.NO_ID && nativeBarF35.id == kbScopeF34.id))
                    val kbVetoF36 = isKbItselfF36
                    if (isClF35 && visOkF35 && inRangeF35 && !hasStripF35 && !selfTagF36 && !kbVetoF36) {
                        AndroidLog.i(TAG, "strip F32 bar pick: $clsF35 barTop=$topF35 h=${nativeBarF35.height} " +
                            "kids=${nativeBarF35.childCount} cand=[$candTop,$candBottom] cands=0 qExcluded=$qExcludedF34 inKb=$inKbF35 " +
                            "selfTag=$selfTagF36 isKbItself=$isKbItselfF36 kbVeto=$kbVetoF36 (ConstraintLayout-fallback原生取放行)")
                        return nativeBarF35
                    }
                    AndroidLog.e(TAG, "strip F32 bar miss: no RecyclerView in cand[$candTop,$candBottom] " +
                        "cands=0 diagBar=$clsF35 diagTop=${locF35[1]} fallbackReject=(isCL=$isClF35 visOk=$visOkF35 inRange=$inRangeF35 hasStrip=$hasStripF35 inKb=$inKbF35 selfTag=$selfTagF36 isKbItself=$isKbItselfF36 kbVeto=$kbVetoF36) (fail-closed不动)")
                    return null
                }
                AndroidLog.e(TAG, "strip F32 bar miss: no RecyclerView in cand[$candTop,$candBottom] " +
                    "cands=0 diagBar=null diagTop=0 (fail-closed不动)")
                return null
            }
            cands.sortBy { it.second }
            val pick = cands.first().first
            val ploc = IntArray(2)
            runCatching { pick.getLocationOnScreen(ploc) }
            AndroidLog.i(TAG, "strip F32 bar pick: ${pick.javaClass.name} barTop=${ploc[1]} h=${pick.height} " +
                "kids=${pick.childCount} cand=[$candTop,$candBottom] cands=${cands.size} qExcluded=$qExcludedF34 (按类+屏位+F34排除Q容器)")
            pick
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F32 bar find failed: $t")
            null
        }
    }

    /**
     * F32候选框底现量（只读）：mCandidateView屏底=candTop+高现算（复用findCandNoRowF28同口径）。
     * 未布局返null，上层fail-closed。返Triple(candTop,candBottom,candH)。
     */
    private fun candBottomScreenF32(decor: ViewGroup): Triple<Int, Int, Int>? {
        return try {
            val cand = findCandNoRowF28(decor) ?: run {
                AndroidLog.e(TAG, "strip F32 cand miss: ImeCandidateView unlaid (fail-closed)")
                return null
            }
            val loc = IntArray(2)
            runCatching { cand.getLocationOnScreen(loc) }
            val h = cand.height.takeIf { it > 0 } ?: cand.measuredHeight
            if (loc[1] <= 0 || h <= 0) {
                AndroidLog.e(TAG, "strip F32 cand unlaid: top=${loc[1]} h=$h")
                return null
            }
            Triple(loc[1], loc[1] + h, h)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * F40稳态入口（接F32，在此基础上改，不reset）：C43 barTop 1365→1190被顶走175、条y压栏，
     * F39只保vis没保住isShown/位置。修：以mount基线为准，bar不动（dy=0）只条落位；
     * 若栏被顶离基线（±8外，如1190）/条y压栏/logo isShown=false即还账fail-closed，不再新施translationY。
     * 已施加残留先验后还，不双施。键盘：Q顶≠1494±8则补偿移回（只动键盘容器translationY记账）。
     * 任一未布局fail-closed不动；壳/s0/J3/圆角B/DEL/commit/logo全不动。
     */
    private fun tryMoveBarBelowCandF32(row: View, decor: ViewGroup) {
        // F41：丢弃F32下移补偿（与翻译卡原生冲突则删）。工具栏/键盘following翻译态原生，
        // 不为留缝20去动bar/keyboard；像素只diag。
        AndroidLog.i(TAG, "strip F41 bar: SKIP F32 move (translation native wins, diag only)")
        return
    }
    // F41-discarded: 以下F32/F40藏栏/少抬升增量已丢弃（保留函数备查，入口已SKIP不执行）。
    private fun tryMoveBarBelowCandF32Discarded(row: View, decor: ViewGroup) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            if (row.parent == null) return
            // F40：已施加残留先验后还（C43的1190位移多为上轮F32残留，不双施；好则留，坏则还）。
            val appliedBefore = stripBarAppliedF32
            if (appliedBefore != null && appliedBefore.parent != null) {
                val keepBefore = runCatching { verifyToolbarZeroShiftF34(decor, "F40-applied-check") }.getOrNull()
                if (keepBefore == true) {
                    AndroidLog.i(TAG, "strip F40 bar-immobile: already applied KEEP dy=${stripBarDyF32.toInt()} (no double)")
                    runCatching { tryCompensateKeyboardF32(decor) }
                    return
                }
                runCatching { revertBarF32(appliedBefore) }
                AndroidLog.e(TAG, "strip F40 bar-immobile: REVERT applied残留 toolbar-zero!=PASS (还账fail-closed，bar不动dy=0)")
                return
            }
            // F39保栏：挂条前后不断言translating藏栏合理，不以translating=true为由SKIP缝门/不推bar；
            // 先显式保栏（custom/logo/logoC原生取VISIBLE+dy=0+祖先显，live.x.A原生还账），双真失败即还账不推bar。
            val keepOk = runCatching { ensureToolbarVisibleF39(decor, "F32-move") }.getOrDefault(false)
            if (!keepOk) {
                val applied = stripBarAppliedF32
                if (applied != null) runCatching { revertBarF32(applied) }
                AndroidLog.e(TAG, "strip F40 bar-immobile: REVERT keep-bar FAIL(isShown=false，保住isShown=true不判PASS) (还账fail-closed)")
                return
            }
            val candMeas = candBottomScreenF32(decor) ?: return
            val candTop = candMeas.first
            val candBottom = candMeas.second
            val rloc = IntArray(2)
            runCatching { row.getLocationOnScreen(rloc) }
            if (rloc[1] <= 0 || row.height <= 0) {
                AndroidLog.e(TAG, "strip F32 bar skip: strip unlaid top=${rloc[1]} h=${row.height}")
                return
            }
            val stripBottom = rloc[1] + row.height
            val logo = runCatching { resolveLogoView(decor) }.getOrNull()
            // F33门改（接F32栏位，在此基础上改，不reset）：C36铁证栏1165→1280 dy115后缝20 PASS几何对了，
            // 但像素门卫nPix=-1连挂两版全败致REVERT——模块内decor.draw取像素失败两版
            // （F22 drawable采样brightPass=0；F23波段captureBandBitmapF23 decor.draw合成nPix=-1），
            // 而外部tools/open-strip.sh同算法（seed238/mask228灰带白圆）可用；
            // 故门从“像素n>=5且缝19~21”改“缝19~21且视图n>=5”：缝/iconTop一律取纯视图簇
            // scanSquareIconLine（视图层级计数，可见性/高>0/方形槽+Image叶，现算不写死），
            // resolveIconLineF22混合值与像素扫描仅记diag不再参与几何/PASS/FAIL；
            // 位移/退壳/壳/s0/J3钳/圆角B/DEL/commit/logo全不动。
            val viewLineBeforeF33 = runCatching { scanSquareIconLine(decor, logo) }.getOrNull()
            if (viewLineBeforeF33 == null || viewLineBeforeF33.top <= 0) {
                AndroidLog.e(TAG, "strip F32 bar skip: viewLine missing n=${viewLineBeforeF33?.n} (fail-closed)")
                return
            }
            val iconDiagBeforeF33 = runCatching { resolveIconLineF22(decor, logo) }.getOrNull()
            if (iconDiagBeforeF33 != null) {
                val dTop = kotlin.math.abs(iconDiagBeforeF33.top - viewLineBeforeF33.top).toInt()
                AndroidLog.i(TAG, "strip F32 bar diag: viewTop=${viewLineBeforeF33.top.toInt()} " +
                    "viewN=${viewLineBeforeF33.n} mixedTop=${iconDiagBeforeF33.top.toInt()} " +
                    "mixedN=${iconDiagBeforeF33.n} dTop=$dTop (mixed/pixel diagOnly)")
            }
            val iconTopForGeomF33 = viewLineBeforeF33.top
            val viewNBeforeF33 = viewLineBeforeF33.n
            val bar = findBarRecyclerF32(decor, candTop, candBottom) ?: run {
                AndroidLog.e(TAG, "strip F32 bar skip: bar-miss cand[$candTop,$candBottom] (fail-closed)")
                return
            }
            val bloc = IntArray(2)
            runCatching { bar.getLocationOnScreen(bloc) }
            val barTop = bloc[1]
            if (barTop <= 0) {
                AndroidLog.e(TAG, "strip F32 bar skip: barTop unlaid=$barTop")
                return
            }
            // F40：压栏即REVERT（条须在栏上，不许条y=[1118,1285]压栏1190；stripBottom须<=barTop）。
            if (stripBottom > barTop) {
                val applied = stripBarAppliedF32
                if (applied != null) runCatching { revertBarF32(applied) }
                AndroidLog.e(TAG, "strip F40 bar-immobile: REVERT overlap stripBottom=$stripBottom>barTop=$barTop " +
                    "(条y压栏，基线栏≈mount基线${stripMountBarTopF40}，bar不动dy=0，还账fail-closed)")
                return
            }
            // F40：栏离基线即REVERT（mount基线±8外，如1365→1190被顶走175；基线缺测则SKIP位置只验缝）。
            val baseBarF40 = stripMountBarTopF40
            if (baseBarF40 > 0 && kotlin.math.abs(barTop - baseBarF40) > STRIP_F40_POS_TOL_PX) {
                val applied = stripBarAppliedF32
                if (applied != null) runCatching { revertBarF32(applied) }
                AndroidLog.e(TAG, "strip F40 bar-immobile: REVERT displaced barTop=$barTop≈基线$baseBarF40±${STRIP_F40_POS_TOL_PX}外 " +
                    "(1190即还账fail-closed，bar不动dy=0，只条落位)")
                return
            }
            val innerPad = (iconTopForGeomF33 - barTop).toInt()
            if (innerPad < 0) {
                AndroidLog.e(TAG, "strip F32 bar skip: innerPad<0 iconTop=${iconTopForGeomF33.toInt()}(view) barTop=$barTop (fail-closed)")
                return
            }
            val gapBefore = (iconTopForGeomF33 - stripBottom).toInt()
            if (gapBefore in STRIP_F32_GAP_MIN_PX..STRIP_F32_GAP_MAX_PX && viewNBeforeF33 >= 5) {
                val zeroOk = runCatching { verifyToolbarZeroShiftF34(decor, "F40-move-pass") }.getOrNull()
                if (zeroOk == true) {
                    AndroidLog.i(TAG, "strip F40 bar-immobile: PASS gap=$gapBefore(19~21) viewN=$viewNBeforeF33(>=5) " +
                        "viewTop=${viewLineBeforeF33.top.toInt()} barTop=$barTop candBottom=$candBottom stripBottom=$stripBottom " +
                        "toolbar-zero=PASS(isShown双真+位置±8+dy0) dy=0 no move (只条落位)")
                    runCatching { tryCompensateKeyboardF32(decor) }
                    return
                }
                val applied = stripBarAppliedF32
                if (applied != null) runCatching { revertBarF32(applied) }
                AndroidLog.e(TAG, "strip F40 bar-immobile: REVERT gap=$gapBefore viewN=${viewNBeforeF33}但toolbar-zero!=PASS (去假阳，还账)")
                return
            }
            // F40：缝未对但栏在基线且无压栏——bar不动dy=0，只条落位（条落位由mount/postAlign/槽保证，本步不再平移栏，fail-closed记账）。
            AndroidLog.e(TAG, "strip F40 bar-immobile: SKIP no bar move gap=$gapBefore(需19~21) viewN=$viewNBeforeF33 " +
                "viewTop=${viewLineBeforeF33.top.toInt()} barTop=$barTop(base=${if (baseBarF40 > 0) baseBarF40 else "缺测"}±$STRIP_F40_POS_TOL_PX) " +
                "candBottom=$candBottom stripBottom=$stripBottom innerPad=$innerPad " +
                "(bar不动dy=0只条落位，不再translationY；缝19~21+viewN>=5唯一门，像素只diag)")
            runCatching { tryCompensateKeyboardF32(decor) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F32 bar move failed: $t")
        }
    }

    /** F40单步复测（接F32 bar-immobile，在此基础上改，不reset）：缝19~21且视图n>=5且toolbar-zero PASS（含isShown双真+位置±8+dy0+F32栏+压栏）才留，否则还账；像素仅记diag；禁盲累加。位移/退壳/壳/s0/J3钳/圆角B/DEL/commit/logo全不动。 */
    private fun scheduleBarVerifyF32(row: View, decor: ViewGroup, bar: View, dy: Float) {
        // F41丢弃：F32复测不再执行（翻译原生在位即PASS，像素只diag）。
        AndroidLog.i(TAG, "strip F41 bar: SKIP F32 verify (translation native wins, diag only)")
        if (true) return
        try {
            row.postDelayed({
                try {
                    if (stripBarAppliedF32 !== bar) return@postDelayed
                    if (row.parent == null || bar.parent == null) {
                        runCatching { revertBarF32(bar) }
                        AndroidLog.e(TAG, "strip F32 verify=REVERT(detached): rowParent=${row.parent == null} barParent=${bar.parent == null} (还账)")
                        return@postDelayed
                    }
                    val logo = runCatching { resolveLogoView(decor) }.getOrNull()
                    // F33门改：视图n>=5才留，像素仅diag（保留扫描继续修但不gate；C36 nPix=-1误杀缝20 PASS已修。
                    // 模块内decor.draw取像素失败两版，外部tools/open-strip.sh同算法可用，见captureBandBitmapF23注释）。
                    // 缝/iconTop一律取纯视图簇scanSquareIconLine；混合resolve与像素波段仅记diag不参与gap/PASS/FAIL。
                    val viewLineF33 = runCatching { scanSquareIconLine(decor, logo) }.getOrNull()
                    val pixelLine = runCatching { scanPixelBandF23(decor, logo, row) }.getOrNull()
                    val mixedDiagF33 = runCatching { resolveIconLineF22(decor, logo) }.getOrNull()
                    val rloc = IntArray(2)
                    runCatching { row.getLocationOnScreen(rloc) }
                    val stripBottom = if (rloc[1] > 0 && row.height > 0) rloc[1] + row.height else -1
                    val bloc = IntArray(2)
                    runCatching { bar.getLocationOnScreen(bloc) }
                    val barTopAfter = bloc[1]
                    if (viewLineF33 == null || stripBottom <= 0) {
                        runCatching { revertBarF32(bar) }
                        AndroidLog.e(TAG, "strip F32 verify=REVERT(unmeasurable): viewNull=${viewLineF33 == null} " +
                            "stripBottom=$stripBottom barTop=$barTopAfter dy=${dy.toInt()} (还账)")
                        return@postDelayed
                    }
                    val gap = (viewLineF33.top - stripBottom).toInt()
                    val nView = viewLineF33.n
                    val viewTopF33 = viewLineF33.top.toInt()
                    val nPix = pixelLine?.n ?: -1
                    val pixTop = pixelLine?.top?.toInt() ?: -1
                    val mixedTopF33 = mixedDiagF33?.top?.toInt() ?: -1
                    val gapPass = gap in STRIP_F32_GAP_MIN_PX..STRIP_F32_GAP_MAX_PX
                    val viewPass = nView >= 5
                    runCatching { verifyStripStacking(decor, row, "F32-verify") }
                    // F40去假阳门：toolbar-zero须PASS（含isShown双真+位置±8+dy0+F32栏+压栏），否则即使缝对亦REVERT。
                    val zeroOkF40 = runCatching { verifyToolbarZeroShiftF34(decor, "F32-verify") }.getOrNull()
                    val zeroPassF40 = zeroOkF40 == true
                    // F40栏位门：barTop离mount基线±8外（如1190）即FAIL；压栏（stripBottom>barTop）即FAIL。
                    var barPosPassF40 = true
                    val baseBarVerify = stripMountBarTopF40
                    if (baseBarVerify > 0 && barTopAfter > 0 &&
                        kotlin.math.abs(barTopAfter - baseBarVerify) > STRIP_F40_POS_TOL_PX
                    ) barPosPassF40 = false
                    val overlapF40 = stripBottom > 0 && barTopAfter > 0 && stripBottom > barTopAfter
                    if (gapPass && viewPass && zeroPassF40 && barPosPassF40 && !overlapF40) {
                        AndroidLog.i(TAG, "strip F40 verify=KEEP(300ms): gap=$gap(19~21) nView=$nView(viewTop=$viewTopF33>=5) " +
                            "nPix=$nPix(pixTop=$pixTop diagOnly) mixedTop=$mixedTopF33(diagOnly) stripBottom=$stripBottom " +
                            "barTop=$barTopAfter dy=${dy.toInt()} zero=PASS posOk barPosOk noOverlap (留)")
                    } else {
                        runCatching { revertBarF32(bar) }
                        val after = runCatching {
                            val bl = IntArray(2)
                            bar.getLocationOnScreen(bl)
                            bl[1]
                        }.getOrNull() ?: -1
                        AndroidLog.e(TAG, "strip F40 verify=REVERT(300ms): gap=$gap(19~21? $gapPass) " +
                            "nView=$nView(>=5? $viewPass viewTop=$viewTopF33) nPix=$nPix(diagOnly pixTop=$pixTop) " +
                            "mixedTop=$mixedTopF33(diagOnly) stripBottom=$stripBottom " +
                            "barTop=$barTopAfter->afterRevert=$after zero=${zeroOkF40 ?: "skip"}(须PASS) " +
                            "barPosOk=$barPosPassF40(base=${if (baseBarVerify > 0) baseBarVerify else "缺测"}±$STRIP_F40_POS_TOL_PX) " +
                            "overlap=$overlapF40(须false) dy=${dy.toInt()} (还账)")
                    }
                    val qTopAfter = runCatching { findQTopOnScreen(decor) }.getOrNull()
                    // Q基线只留normal=1494±8；q-after保持SKIP/PASS/DRIFT-diag三分态，DRIFT只diag不REVERT；缝19~21+视图n>=5仍唯一门（上已判），像素只diag。
                    val qBaseAfterF34 = STRIP_F32_Q_BASELINE
                    if (qTopAfter == null) {
                        AndroidLog.i(TAG, "strip F32 q-after: SKIP qTop缺测不断言 " +
                            "(baseline=$qBaseAfterF34±$STRIP_F32_Q_TOL_PX) kbDy=${stripKbDyF32.toInt()}")
                    } else {
                        val qOk = kotlin.math.abs(qTopAfter - qBaseAfterF34) <= STRIP_F32_Q_TOL_PX
                        AndroidLog.i(TAG, "strip F32 q-after: ${if (qOk) "PASS" else "DRIFT-diag"} " +
                            "qTop=$qTopAfter(baseline=$qBaseAfterF34±$STRIP_F32_Q_TOL_PX) " +
                            "kbDy=${stripKbDyF32.toInt()} (不断言/不REVERT)")
                    }
                    // F40工具栏零位移证据行（gateF32 verify，去假阳）：dy=0且VISIBLE+isShown双真+位置±8+F32栏+压栏才是PASS。
                    runCatching { verifyToolbarZeroShiftF34(decor, "F32-verify") }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F32 verify failed: $t")
                    runCatching { revertBarF32(bar) }
                }
            }, POST_STABLE_DELAY_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F32 verify schedule failed: $t")
        }
    }

    /** F32栏单视图还账（幂等，只撤销我方translationY增量）。 */
    private fun revertBarF32(bar: View) {
        try {
            val orig = synchronized(stripBarTransOrigF32) { stripBarTransOrigF32.remove(bar) }
            if (orig != null) {
                runCatching {
                    if (bar.parent != null) {
                        bar.translationY = orig
                        runCatching { bar.requestLayout() }
                        (bar.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
                    }
                }
                AndroidLog.i(TAG, "strip F32 bar reverted: ${bar.javaClass.name} trans->orig=$orig")
            }
            if (stripBarAppliedF32 === bar) {
                stripBarAppliedF32 = null
                stripBarDyF32 = 0f
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F32 bar revert failed: $t")
        }
    }

    /**
     * F32键盘补偿（只读判据+记账位移）：Q基线只留normal=1494±8，
     * 漂移超差则键盘容器translationY补偿移回。只动键盘容器自身translationY，不碰LP/垫/边/条/栏/壳/s0/J3/圆角B/DEL/commit/logo。返补偿量px。
     * 零位移判定：本函数仅补键盘，零位移PASS/FAIL以verifyToolbarZeroShiftF34工具栏行dy=0且VISIBLE为准，Q位移由q-after按1494±8另判（此处DRIFT仅记账不REVERT）。
     */
    private fun tryCompensateKeyboardF32(decor: ViewGroup): Int {
        // F41丢弃：F32键盘补偿不再执行。键盘following翻译态原生，不动。
        AndroidLog.i(TAG, "strip F41 kb: SKIP F32 compensate (translation native wins, diag only)")
        if (true) return 0
        return try {
            val qTop = runCatching { findQTopOnScreen(decor) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F32 kb skip: qTop missing (fail-closed)")
                return 0
            }
            // Q基线只留normal=1494±8。
            val qBaseF34 = STRIP_F32_Q_BASELINE
            val drift = qTop - qBaseF34
            if (kotlin.math.abs(drift) <= STRIP_F32_Q_TOL_PX) {
                AndroidLog.i(TAG, "strip F32 kb pass: qTop=$qTop(baseline=$qBaseF34±$STRIP_F32_Q_TOL_PX) no move")
                runCatching { verifyToolbarZeroShiftF34(decor, "F32-kb-pass") }
                return 0
            }
            if (stripKbAppliedF32 != null && stripKbAppliedF32?.parent != null) {
                AndroidLog.i(TAG, "strip F32 kb skip: already applied dy=${stripKbDyF32.toInt()} qTop=$qTop (no double)")
                return stripKbDyF32.toInt()
            }
            val cl = hostClassLoader ?: decor.context?.classLoader ?: run {
                AndroidLog.e(TAG, "strip F32 kb skip: loader missing")
                return 0
            }
            val kbId = resolveKeyboardContainerId(cl) ?: run {
                AndroidLog.e(TAG, "strip F32 kb skip: kbId missing")
                return 0
            }
            val kb = decor.findViewById<View>(kbId) ?: run {
                AndroidLog.e(TAG, "strip F32 kb skip: kb container missing id=$kbId qTop=$qTop drift=$drift")
                return 0
            }
            if (kb.parent == null) {
                AndroidLog.e(TAG, "strip F32 kb skip: kb detached")
                return 0
            }
            val dy = (qBaseF34 - qTop).toFloat()
            synchronized(stripKbTransOrigF32) {
                if (!stripKbTransOrigF32.containsKey(kb)) {
                    stripKbTransOrigF32[kb] = kb.translationY
                }
            }
            kb.translationY = kb.translationY + dy
            stripKbAppliedF32 = kb
            stripKbDyF32 = dy
            runCatching { kb.requestLayout() }
            (kb.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
            AndroidLog.i(TAG, "strip F32 kb compensate: qTop $qTop->$qBaseF34 drift=$drift " +
                "dy=${dy.toInt()} kb=${kb.javaClass.name} (记账，退壳拆条全还)")
            runCatching { verifyToolbarZeroShiftF34(decor, "F32-kb-compensate") }
            dy.toInt()
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F32 kb compensate failed: $t")
            0
        }
    }

    /** F32键盘还账（幂等，只撤销我方translationY增量）。 */
    private fun revertKeyboardF32() {
        try {
            var n = 0
            synchronized(stripKbTransOrigF32) {
                val it = stripKbTransOrigF32.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        if (e.key.parent != null) {
                            e.key.translationY = e.value
                            runCatching { e.key.requestLayout() }
                            (e.key.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            stripKbAppliedF32 = null
            stripKbDyF32 = 0f
            if (n > 0) AndroidLog.i(TAG, "strip F32 kb reverted n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F32 kb revert failed: $t")
        }
    }

    /** F32退壳拆条全还（幂等）：栏位+键盘位逐个还，只撤销我方增量。 */
    private fun restoreBarKeyboardF32() {
        try {
            var n = 0
            val bar = stripBarAppliedF32
            if (bar != null) {
                runCatching { revertBarF32(bar) }
                n++
            } else {
                synchronized(stripBarTransOrigF32) {
                    if (stripBarTransOrigF32.isNotEmpty()) {
                        val it = stripBarTransOrigF32.entries.iterator()
                        while (it.hasNext()) {
                            val e = it.next()
                            runCatching {
                                if (e.key.parent != null) {
                                    e.key.translationY = e.value
                                    runCatching { e.key.requestLayout() }
                                }
                            }
                            it.remove()
                            n++
                        }
                    }
                }
                stripBarDyF32 = 0f
            }
            val kbBefore = synchronized(stripKbTransOrigF32) { stripKbTransOrigF32.size }
            runCatching { revertKeyboardF32() }
            if (kbBefore > 0) n += kbBefore
            if (n > 0) AndroidLog.i(TAG, "strip F32 restored n=$n (栏位/键盘位全还)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F32 restore failed: $t")
        }
    }

    private fun hookSearchInputRouting(classLoader: ClassLoader) {
        try {
            val svc = runCatching {
                Class.forName("com.tencent.wetype.plugin.hld.WxHldService", false, classLoader)
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "WxHldService not found, skip input routing")
                return
            }
            for (method in svc.declaredMethods) {
                if (method.name != "A") continue
                val pt = method.parameterTypes
                if (pt.size != 1 || pt[0] != java.lang.Boolean.TYPE) continue
                if (!InputConnection::class.java.isAssignableFrom(method.returnType)) continue
                try {
                    method.isAccessible = true
                    method.hookReplace { param ->
                        try {
                            activeImeService = java.lang.ref.WeakReference(param.thisObject)
                            val forceReal = param.args.firstOrNull() as? Boolean ?: false
                            // S5b-A5：路由保留供吃字（commitText 透传不动）；删由
                            // StripInputConnection wrapper 直删条框并消费（不依赖路由旁路）。
                            // 候选可见可点：forceReal=true 是宿主取真 IC 弹候选/选词通道，
                            // 条禁抢占（此前含 forceReal 一律回条 IC 会致候选不弹），
                            // 此路直接放行原生；仅 forceReal=false 且条聚焦可见才回条 IC。
                            if (forceReal && submitSession.phase != ClipboardSearchSubmitSession.Phase.COMMITTING) {
                                return@hookReplace ProceedWithOriginal
                            }
                            searchInputConnection()?.let {
                                return@hookReplace it
                            }
                        } catch (t: Throwable) {
                            AndroidLog.e(TAG, "search input routing failed: ${t.message}")
                        }
                        ProceedWithOriginal
                    }
                    AndroidLog.i(TAG, "hooked WxHldService#A for search input routing")
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "hook WxHldService#A failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "install input routing failed: ${t.message}")
        }
    }

    /** Finish 拆键盘页搜索条（防泄漏到别页）。 */
    private fun hookKeyboardTeardown(classLoader: ClassLoader) {
        try {
            val svc = runCatching {
                Class.forName("com.tencent.wetype.plugin.hld.WxHldService", false, classLoader)
            }.getOrNull() ?: return
            for (method in svc.declaredMethods) {
                if (method.name != "onFinishInputView") continue
                try {
                    method.isAccessible = true
                    method.hookAfter { teardownStrip() }
                    AndroidLog.i(TAG, "hooked onFinishInputView for strip teardown")
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "hook onFinishInputView failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "install keyboard teardown failed: ${t.message}")
        }
    }

    /**
     * 可触摸区上扩：条在键盘上方候选插槽内，VISIBLE 模式
     * 可触摸区由 visibleTopInsets 决定，必须同步下扩，否则收起点穿。
     */
    private fun hookInsetsForStrip(classLoader: ClassLoader) {
        try {
            val svc = runCatching {
                Class.forName("com.tencent.wetype.plugin.hld.WxHldService", false, classLoader)
            }.getOrNull() ?: return
            val m = svc.declaredMethods.firstOrNull {
                it.name == "onComputeInsets" && it.parameterTypes.size == 1
            } ?: return
            val imsRegion = android.inputmethodservice.InputMethodService.Insets.TOUCHABLE_INSETS_REGION
            val imsVisible = android.inputmethodservice.InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE
            m.isAccessible = true
            m.hookAfter { param ->
                try {
                    val insets = param.args[0]
                        as? android.inputmethodservice.InputMethodService.Insets
                        ?: return@hookAfter
                    if (!insetsLogged) {
                        insetsLogged = true
                        AndroidLog.i(TAG, "insets mode: touchable=${insets.touchableInsets} " +
                            "contentTop=${insets.contentTopInsets} " +
                            "visibleTop=${insets.visibleTopInsets}")
                    }
                    if (insets.touchableInsets == imsRegion) return@hookAfter
                    val parent = overlayParentRef?.get() ?: return@hookAfter
                    val card = parent.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER)
                        ?: return@hookAfter
                    if (card.visibility != View.VISIBLE) return@hookAfter
                    val loc = IntArray(2)
                    runCatching { card.getLocationOnScreen(loc) }
                    val cardTop = loc[1]
                    if (cardTop <= 0) return@hookAfter
                    val eps = runCatching { dpToPx(card.resources, 4f) }.getOrDefault(0)
                    val target = cardTop - eps
                    var changed = false
                    if (target < insets.contentTopInsets) {
                        insets.contentTopInsets = target
                        changed = true
                    }
                    if (insets.touchableInsets == imsVisible &&
                        target < insets.visibleTopInsets
                    ) {
                        insets.visibleTopInsets = target
                        changed = true
                    }
                    if (changed) {
                        AndroidLog.i(TAG, "insets expanded for strip cardTop=$cardTop " +
                            "visibleTop=${insets.visibleTopInsets}")
                    }
                } catch (_: Throwable) {
                }
            }
            AndroidLog.i(TAG, "hooked onComputeInsets for strip touch")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "install insets hook failed: ${t.message}")
        }
    }

    private val cachedConnections = WeakHashMap<EditText, InputConnection>()

    /** 展开且聚焦的搜索框的 InputConnection，无则 null 走原生。 */
    private fun searchInputConnection(): InputConnection? {
        try {
            synchronized(trackedBoxes) {
                val it = trackedBoxes.iterator()
                while (it.hasNext()) {
                    val box = it.next()
                    try {
                        if (box.parent == null) {
                            it.remove()
                            cachedConnections.remove(box)
                            continue
                        }
                        if (box.visibility != View.VISIBLE || !box.hasFocus()) continue
                        var p = box.parent
                        var hidden = false
                        while (p is View) {
                            if ((p as View).visibility != View.VISIBLE) { hidden = true; break }
                            p = (p as View).parent
                        }
                        if (hidden) continue
                        // 防劫持：条框 NO_SUGGESTIONS（宿主联想不劫持条词）；
                        // StripInputConnection 只包条框，commitText 透传不动；
                        // 摘k后引擎保持正常拼音链路。
                        val ei = EditorInfo().apply {
                            inputType = InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            imeOptions = EditorInfo.IME_ACTION_SEARCH
                        }

                        val cached = cachedConnections[box]
                        if (cached != null) return cached

                        // S5b-A5：条框 IC 外包 wrapper 直删条框（删走 wrapper，
                        // 吃字 commitText 透传不动，PASS禁动）。A(true) 路由保留供吃字，
                        // 删不再依赖路由旁路。
                        // FAIL③：ENTER经onEnter回跳（聚焦+可见双门卫下放行jump，
                        // 主线程post找容器，fail-closed）。
                        val raw = box.onCreateInputConnection(ei) ?: continue
                        val wrapper = StripInputConnection(
                            raw,
                            java.lang.ref.WeakReference(box),
                            onEnter = {
                                try {
                                    box.post {
                                        try {
                                            // F41：原生k框（trackedBoxes含原生edit）走原生跳回；自绘框走旧容器路。
                                            // C48：IC三路（performEditorAction/sendKeyEvent/finishComposing）经此必达editor链，
                                            // jump链带editor记账（source透传，见jumpBackToClipboardF41）。
                                            if (nativeKEditRefF41?.get() === box) {
                                                AndroidLog.i(TAG, "strip F41 editor action: jumping back " +
                                                    "action=ic-route-native-k (IC双路必达)")
                                                AndroidLog.i(TAG, "strip F41 enter route: jumping back (native k)")
                                                jumpBackToClipboardF41("ic-route-native-k")
                                                return@post
                                            }
                                            var node: View? = box
                                            while (node != null && node.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                                                node = node.parent as? View
                                            }
                                            if (node != null) {
                                                AndroidLog.i(TAG, "strip enter route: jumping back")
                                                jumpBackToClipboard(node)
                                            } else {
                                                // F41兜底：容器失联但原生k在，仍走原生跳回（fail-closed不丢词）。
                                                if (nativeKRefF41?.get()?.parent != null) {
                                                    AndroidLog.i(TAG, "strip F41 editor action: jumping back " +
                                                        "action=ic-route-fallback (IC兜底必达)")
                                                    AndroidLog.i(TAG, "strip F41 enter route fallback: native k jump")
                                                    jumpBackToClipboardF41("ic-route-fallback")
                                                } else {
                                                    AndroidLog.e(TAG, "strip enter route: container not found")
                                                }
                                            }
                                        } catch (t: Throwable) {
                                            AndroidLog.e(TAG, "strip enter route apply failed: ${t.message}")
                                        }
                                    }
                                } catch (t: Throwable) {
                                    AndroidLog.e(TAG, "strip enter route dispatch failed: ${t.message}")
                                }
                            }
                        )
                        cachedConnections[box] = wrapper
                        return wrapper
                    } catch (_: Throwable) {
                        continue
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * S5e-A5：条框 IC wrapper 删路径（实证 2026-09-09 新鲜进程：点⌫键盘调
     * `sendKeyEvent(DOWN/UP code=67)`，不走 deleteSurroundingText；super 转发到
     * 会话外现造的 raw IC 被静默吞掉，条仍 hello 且零日志）。DEL 在 wrapper 内
     * 直删条框一字并消费返 true（DOWN 删字，UP 配对吞掉）；super 实证无删字
     * 副作用，无双发。吃字 commitText/commitContent 一律不覆写透传 super。
     * 只包条框 IC（searchInputConnection 仅对条框建 wrapper）。
     * FAIL③：换行键（1002,1975）走sendKeyEvent(ENTER)/performEditorAction，
     * 聚焦+可见双门卫下必须放行jumpBackToClipboard；ENTER 经 IC/onKey/
     * EditorAction 三路放行，不被门卫吞掉。
     */
    private class StripInputConnection(
        target: InputConnection,
        private val boxRef: java.lang.ref.WeakReference<EditText>,
        private val onEnter: (() -> Unit)? = null
    ) : InputConnectionWrapper(target, true) {

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            try {
                val box = boxRef.get()
                if (box != null && isActive(box)) {
                    deleteOne(box, "deleteSurroundingText")
                    return true
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip delete wrapper failed: ${t.message}")
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun deleteSurroundingTextInCodePoints(
            beforeLength: Int,
            afterLength: Int
        ): Boolean {
            try {
                val box = boxRef.get()
                if (box != null && isActive(box)) {
                    deleteOne(box, "deleteSurroundingTextInCodePoints")
                    return true
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip delete wrapper failed: ${t.message}")
            }
            return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }

        // S5e-A5：DEL 走 sendKeyEvent（实证），wrapper 内消费直删一字返 true。
        // DOWN 删字；UP 配对吞掉（super 实证无副作用，吞 UP 防残留分发）。
        // FAIL③：ENTER 走同通道（DOWN跳回，UP配对吞掉），聚焦+可见双门卫下
        // 放行jumpBackToClipboard（经onEnter回外层，禁super吞掉致无jumping back）。
        // 非 DEL/ENTER 一律 super（吃字/commitText透传不动）。非激活态走 super。
        override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
            try {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_DEL) {
                    val box = boxRef.get()
                    if (box != null && isActive(box)) {
                        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                            deleteOne(box, "sendKeyEvent")
                        }
                        return true
                    }
                }
                if (event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                ) {
                    val box = boxRef.get()
                    if (box != null && isActive(box)) {
                        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                            AndroidLog.i(TAG, "strip F41 editor action: jumping back action=ic-enter-key " +
                                "key=${event.keyCode} (sendKeyEvent双路必达editor链)")
                            AndroidLog.i(TAG, "strip enter key: jumping back via sendKeyEvent")
                            runCatching { onEnter?.invoke() }
                                .onFailure { AndroidLog.e(TAG, "strip enter route failed: $it") }
                            // onEnter缺失（旧缓存wrapper）则回退box.post找容器直跳。
                            if (onEnter == null) {
                                box.post {
                                    try {
                                        var node: View? = box
                                        while (node != null && node.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                                            node = node.parent as? View
                                        }
                                        if (node != null) {
                                            AndroidLog.i(TAG, "strip enter key: jumping back via fallback")
                                        }
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                        }
                        return true
                    }
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip del keyevent failed: ${t.message}")
            }
            return super.sendKeyEvent(event)
        }

        /** Only editor submission actions carry intention; text commits inherit pass-through. */
        override fun performEditorAction(actionCode: Int): Boolean {
            val box = boxRef.get()
            val submit = actionCode in setOf(EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEND)
            if (submit && box != null && isActive(box)) {
                onEnter?.invoke()
                return true
            }
            return super.performEditorAction(actionCode)
        }

        // Delivery may finish an existing native confirmation, but never creates its intention.
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val token = submitSession.id
            val result = super.commitText(text, newCursorPosition)
            val box = boxRef.get()
            if (result && box != null && ownsSearchBox(box) &&
                submitSession.at(token, ClipboardSearchSubmitSession.Phase.COMMITTING)) {
                submitSession.nativeTextDelivered(token, box.text?.toString().orEmpty())
            }
            return result
        }

        /** 自包含激活态判定（与外层 searchInputConnection 同判据，不依赖外层接收者）。 */
        private fun isActive(box: EditText): Boolean {
            return try {
                if (box.parent == null) return false
                if (box.visibility != View.VISIBLE || !box.hasFocus()) return false
                var p = box.parent
                while (p is View) {
                    if ((p as View).visibility != View.VISIBLE) return false
                    p = (p as View).parent
                }
                true
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * S5b-R2 单删：直删条框一字（box.post 保主线程；TextWatcher 自动透传
         * 关键词走 S5 过滤）。空文本 no-op 返 true（消费空删）。log 固定
         * `strip del 1 via surrounding`（1:1 判据：hello 点 1 删 hell）。
         */
        private fun deleteOne(box: EditText, via: String) {
            try {
                box.post {
                    try {
                        val editable = box.text
                        if (editable == null || editable.isEmpty()) {
                            AndroidLog.i(TAG, "strip del 1 via surrounding: empty, consumed via=$via")
                        } else {
                            val sel = box.selectionStart.coerceIn(0, editable.length)
                            val from = if (sel > 0) sel - 1 else editable.length - 1
                            val to = if (sel > 0) sel else editable.length
                            editable.delete(from.coerceAtLeast(0), to)
                            AndroidLog.i(TAG, "strip del 1 via surrounding ok len=${editable.length} via=$via")
                        }
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "strip delete apply failed: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip delete dispatch failed: ${t.message}")
            }
        }
    }

    private fun recordTabIndex(host: Any, methodName: String, args: Array<Any?>) {
        try {
            when (methodName) {
                "f1" -> {
                    val index = args.firstOrNull() as? Int ?: return
                    tabIndexByHost[host] = index
                    pinTabHost(host)
                }
                "Y0" -> {
                    val bundle = args.firstOrNull() as? android.os.Bundle ?: return
                    if (bundle.containsKey("target_tab_index")) {
                        tabIndexByHost[host] = bundle.getInt("target_tab_index", TAB_CLIPBOARD)
                        pinTabHost(host)
                        // S5e-A4：缓存 Y0 实包 clone 供跳页同形回放（上限 8 轮转）。
                        try {
                            val clone = android.os.Bundle()
                            runCatching { clone.putAll(bundle) }
                            synchronized(y0BundleStrongByHost) {
                                if (y0BundleStrongByHost.size >= 8 &&
                                    !y0BundleStrongByHost.containsKey(host)
                                ) {
                                    val oldest = y0BundleStrongByHost.keys.firstOrNull()
                                    if (oldest != null) y0BundleStrongByHost.remove(oldest)
                                }
                                y0BundleStrongByHost[host] = clone
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
                // S5e-A4：缓存 i0 原生 key 实参（无法合成，回放渲染必需）。
                "i0" -> {
                    val key = args.firstOrNull() ?: return
                    pinTabHost(host)
                    try {
                        synchronized(i0ArgStrongByHost) {
                            if (i0ArgStrongByHost.size >= 8 &&
                                !i0ArgStrongByHost.containsKey(host)
                            ) {
                                val oldest = i0ArgStrongByHost.keys.firstOrNull()
                                if (oldest != null) i0ArgStrongByHost.remove(oldest)
                            }
                            i0ArgStrongByHost[host] = key
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * S5e-A4：记录时即强引用 host + 预解析切页方法强存（防跳页时 GC 失联）。
     * 上限 8 轮转（进程级单例，不泄漏出进程）。f1 单参（旧版）与 Y0 Bundle
     * （当前版本）双通道都解析，有哪个存哪个。
     */
    private fun pinTabHost(host: Any) {
        try {
            synchronized(tabHostStrongRefs) {
                if (!tabHostStrongRefs.contains(host) && tabHostStrongRefs.size >= 8) {
                    val oldest = tabHostStrongRefs.iterator().next()
                    tabHostStrongRefs.remove(oldest)
                    synchronized(f1MethodStrongByHost) { f1MethodStrongByHost.remove(oldest) }
                    synchronized(y0MethodStrongByHost) { y0MethodStrongByHost.remove(oldest) }
                    synchronized(y0BundleStrongByHost) { y0BundleStrongByHost.remove(oldest) }
                    synchronized(i0ArgStrongByHost) { i0ArgStrongByHost.remove(oldest) }
                }
                tabHostStrongRefs.add(host)
            }
            if (!f1MethodStrongByHost.containsKey(host)) {
                val m = findF1SingleInt(host)
                if (m != null) {
                    runCatching { m.isAccessible = true }
                    putCapped(f1MethodStrongByHost, host, m)
                }
            }
            if (!y0MethodStrongByHost.containsKey(host)) {
                val m = findY0Bundle(host)
                if (m != null) {
                    runCatching { m.isAccessible = true }
                    putCapped(y0MethodStrongByHost, host, m)
                } else if (!f1MethodStrongByHost.containsKey(host)) {
                    AndroidLog.w(TAG, "pinTabHost: no jump channel (f1(int)/Y0(Bundle)) on " +
                        "${host.javaClass.name}, programmatic jump will fallback")
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "pinTabHost failed: ${t.message}")
        }
    }

    private fun findF1SingleInt(host: Any): java.lang.reflect.Method? {
        return try {
            host.javaClass.declaredMethods.firstOrNull {
                it.name == "f1" && it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Int::class.javaPrimitiveType ||
                        it.parameterTypes[0] == Integer::class.java)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun findY0Bundle(host: Any): java.lang.reflect.Method? {
        return try {
            host.javaClass.declaredMethods.firstOrNull {
                it.name == "Y0" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == android.os.Bundle::class.java
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** S5e-A4：当前版本 f1 三参形 (int,boolean,boolean)，实证用户点图标行调 f1(0,false,false)。 */
    private fun findF1Triple(host: Any): java.lang.reflect.Method? {
        return try {
            host.javaClass.declaredMethods.firstOrNull {
                it.name == "f1" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == java.lang.Boolean.TYPE &&
                    it.parameterTypes[2] == java.lang.Boolean.TYPE
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun putCapped(
        map: MutableMap<Any, java.lang.reflect.Method>,
        host: Any,
        m: java.lang.reflect.Method
    ) {
        try {
            synchronized(map) {
                if (map.size >= 8 && !map.containsKey(host)) {
                    val oldest = map.keys.firstOrNull()
                    if (oldest != null) map.remove(oldest)
                }
                map[host] = m
            }
        } catch (_: Throwable) {
        }
    }

    private fun isCustomPhraseTab(host: Any): Boolean {
        return try {
            tabIndexByHost[host]?.let { it != TAB_CLIPBOARD } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun hideForCustomTab(keyboardObj: Any) {
        val anchor = runCatching { findHostView(keyboardObj) }.getOrNull() ?: return
        val action = {
            try {
                val root = anchor.rootView as? ViewGroup
                if (root != null) {
                    root.findViewWithTag<View>(TAG_SEARCH_BUTTON)?.visibility = View.GONE
                    root.findViewWithTag<View>(TAG_RESULT_SEARCH)?.visibility = View.GONE
                    clearSearchOnMain()
                    AndroidLog.i(TAG, "custom phrase tab: search UI hidden")
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "hide search UI for custom tab failed: ${t.message}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else anchor.post { action() }
    }

    private fun ensureSearchUi(keyboardObj: Any) {
        try {
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) {
                removeResidualOnMain(keyboardObj)
                return
            }
            if (isCustomPhraseTab(keyboardObj)) {
                hideForCustomTab(keyboardObj)
                return
            }
            val anchor = findHostView(keyboardObj) ?: run {
                AndroidLog.e(TAG, "S15 host view not found, skip mount")
                return
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                mountOnMain(keyboardObj, anchor)
            } else {
                anchor.post { mountOnMain(keyboardObj, anchor) }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "ensureSearchUi failed: ${t.message}")
        }
    }

    private fun removeResidualOnMain(keyboardObj: Any) {
        val anchor = runCatching { findHostView(keyboardObj) }.getOrNull() ?: return
        val action = {
            try {
                val root = anchor.rootView as? ViewGroup
                if (root != null) {
                    root.findViewWithTag<View>(TAG_RESULT_SEARCH)?.visibility = View.GONE
                    root.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER)?.let { container ->
                        // 原生插槽无手动垫高，经统一出口直接摘条。
                        removeStripCard(container)
                    }
                    root.findViewWithTag<View>(TAG_SEARCH_BOX)?.let { box ->
                        (box.parent as? ViewGroup)?.removeView(box)
                    }
                    root.findViewWithTag<View>(TAG_SEARCH_BUTTON)?.let { btn ->
                        (btn.parent as? ViewGroup)?.removeView(btn)
                    }
                    clearSearchOnMain()
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "remove residual search UI failed: ${t.message}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else anchor.post { action() }
    }

    private fun applyCircularShape(btn: View, size: Int = minOf(btn.width, btn.height)) {
        try {
            btn.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    val s = minOf(v.width, v.height).takeIf { it > 0 } ?: size
                    if (s > 0) {
                        val l = (v.width - s).coerceAtLeast(0) / 2
                        val t = (v.height - s).coerceAtLeast(0) / 2
                        outline.setOval(l, t, l + s, t + s)
                    } else {
                        outline.setOval(0, 0, v.width, v.height)
                    }
                }
            }
            btn.clipToOutline = true
        } catch (_: Throwable) {
        }
    }

    /**
     * 剪贴板页：只挂红钮，不挂任何行（不挤列表）。跳回带词则直达 S5 过滤。
     */
    private fun mountOnMain(keyboardObj: Any, anchor: View) {
        try {
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) return
            val hostLoader = anchor.javaClass.classLoader ?: return
            val resources = anchor.resources ?: return

            val root = (anchor.rootView as? ViewGroup) ?: return
            val ids = resolveIds(anchor, hostLoader)
            val clipList = findClipboardListView(root, ids.clipListId)
            if (clipList == null) {
                AndroidLog.i(TAG, "no clipboard list in tree, skip mount (residuals cleared)")
                removeResidualViews(root)
                return
            }
            if (clipList.visibility != View.VISIBLE || isCustomPhraseTab(keyboardObj)) {
                hideForCustomTab(keyboardObj)
                return
            }
            val backBtnId = ids.backBtnId
            if (backBtnId == null) {
                AndroidLog.e(TAG, "resolve back_btn id failed, skip mount")
                return
            }
            val page = findClipboardPageContainer(clipList, backBtnId)
            if (page == null) {
                AndroidLog.e(TAG, "clipboard page container not found, skip mount")
                return
            }
            val backBtn = page.findViewById<View>(backBtnId)
            val bar = backBtn?.parent as? ViewGroup
            if (backBtn == null || bar == null) {
                AndroidLog.e(TAG, "back button or its bar missing, skip mount")
                return
            }

            var button = bar.findViewWithTag<View>(TAG_SEARCH_BUTTON) as? ImageView
            if (button == null) {
                // 全原生：宿主搜索图标缺失则不挂按钮（禁系统图标兜底）。
                val iconRes = resolveSearchIconRes(anchor, hostLoader) ?: run {
                    AndroidLog.e(TAG, "search button dropped: host icon missing")
                    return
                }
                button = ImageView(bar.context).apply {
                    tag = TAG_SEARCH_BUTTON
                    contentDescription = "搜索剪贴板"
                    try {
                        setImageResource(iconRes)
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "set search icon failed: ${t.message}")
                    }
                    applyHostIconTint(this, page, ids.backBtnIvId)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    background = resolveNativeRoundBackground(backBtn, resources)
                    applyCircularShape(this)
                    val initPad = (dpToPx(resources, 32f) * 0.15f).roundToInt()
                    setPadding(initPad, initPad, initPad, initPad)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { view ->
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onSearchButtonClick(root)
                    }
                }
                val backIndex = bar.indexOfChild(backBtn)
                bar.addView(button, if (backIndex >= 0) backIndex + 1 else 0)
                button?.let { alignSearchButton(it, bar, backBtn, backBtnId) }
                AndroidLog.i(TAG, "search button mounted next to back_btn")
            }
            bar.findViewWithTag<View>(TAG_SEARCH_BUTTON)?.let { existing ->
                // A view survives module hot reload, but its previous click closure does too.
                // Rebind once per module generation so updated native contracts are actually used.
                if (searchButtonOwners.put(existing, true) != true) {
                    existing.setOnClickListener { view ->
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onSearchButtonClick(root)
                    }
                }
                existing.background = resolveNativeRoundBackground(backBtn, resources)
                (existing as? ImageView)?.let { icon ->
                    applyHostIconTint(icon, page, ids.backBtnIvId)
                    icon.post {
                        if (icon.isAttachedToWindow) applyHostIconTint(icon, page, ids.backBtnIvId)
                    }
                }
                applyCircularShape(existing)
                alignSearchButton(existing, bar, backBtn, backBtnId)
            }
            root.findViewWithTag<View>(TAG_SEARCH_BUTTON)?.let { btn ->
                if (btn.visibility != View.VISIBLE) {
                    btn.visibility = View.VISIBLE
                    AndroidLog.i(TAG, "search button re-shown on clipboard tab")
                }
            }
            // 剪贴板页返回键：按开关决定是否清理搜索关键词（默认清理）。
            runCatching { ensureBackClearHook(backBtn) }
                .onFailure { AndroidLog.e(TAG, "back clear hook failed: ${it.message}") }
            // 剪贴板页不挂行：清掉误留的行（只留按钮），不挤列表。
            removeStripInClipboard(page, root)
            ensureResultSearchBar(clipList, root)
            // 跳回带词：直达 S5 过滤链路。
            if (pendingKeyword.isNotEmpty()) {
                val kw = pendingKeyword
                pendingKeyword = ""
                applyKeywordDirect(kw)
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "mount search UI failed: ${t.message}")
        }
    }

    /** 剪贴板页清行：page 内/附近同 tag 行全拆（键盘页的行不在此树，误伤不到）。 */
    private fun removeStripInClipboard(page: ViewGroup, root: ViewGroup) {
        try {
            var removed = 0
            var guard = 0
            while (guard++ < 4) {
                val v = page.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) ?: break
                removeStripCard(v)
                removed++
            }
            // 键盘页行若残留在 root（切页未拆），Finish 钩会拆，这里只记数不强拆。
            if (removed > 0) AndroidLog.i(TAG, "clipboard strip residuals removed: $removed")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "remove clipboard strip failed: ${t.message}")
        }
    }

    /** 剪贴板页返回键已包装的己方 listener（view -> wrapper），防重复叠加。 */
    private val backClearWrappers: MutableMap<View, View.OnClickListener> =
        Collections.synchronizedMap(WeakHashMap<View, View.OnClickListener>())

    /**
     * 剪贴板页返回键：按 [WeTypeSettings.KEY_CLIPBOARD_SEARCH_CLEAR_ON_BACK]
     * 决定是否在点击时清理搜索关键词（默认清理，恢复完整列表）。
     *
     * 只包装已有的原生 OnClickListener 并在放行前清理，不过滤逻辑本身
     * 仍走 [applyKeywordDirect] → S5 防抖 + 后台 + setList 回放链。
     * 宿主重设 listener 时下次 mount 会重新包装；已是己方包装则跳过防叠加。
     * 搜索条收起（collapseStripF41）保持原有无条件清理语义，不受此开关影响。
     */
    private fun ensureBackClearHook(backBtn: View) {
        try {
            val listenerInfo = runCatching {
                val getter = View::class.java.getDeclaredMethod("getListenerInfo")
                    .apply { isAccessible = true }
                getter.invoke(backBtn)
            }.getOrNull() ?: return
            val current = runCatching { readNativeField(listenerInfo, "mOnClickListener") }
                .getOrNull() as? View.OnClickListener ?: return
            // 同一 view 已包装过且 listener 未被宿主替换：跳过防叠加。
            // SAM 转换的合成类 enclosing 不可靠，改用实例身份比对。
            if (backClearWrappers[backBtn] === current) return
            val original = current
            val wrapper = View.OnClickListener { v ->
                try {
                    if (WeTypeSettings.isClipboardSearchClearOnBackXposed()) {
                        val kw = runCatching { WeTypeClipboardSearchFilter.currentKeywordValue() }
                            .getOrDefault("")
                        if (kw.isNotEmpty()) {
                            pendingKeyword = ""
                            applyKeywordDirect("")
                            AndroidLog.i(
                                TAG,
                                "clipboard back: cleared search keyword len=${kw.length} (clear-on-back ON)"
                            )
                        }
                    } else {
                        AndroidLog.i(TAG, "clipboard back: keep keyword (clear-on-back OFF)")
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "clipboard back clear failed: ${t.message}")
                } finally {
                    runCatching { original.onClick(v) }
                        .onFailure { AndroidLog.e(TAG, "clipboard back original failed: ${it.message}") }
                }
            }
            backBtn.setOnClickListener(wrapper)
            backClearWrappers[backBtn] = wrapper
            AndroidLog.i(TAG, "clipboard back_btn wrapped for optional keyword clear")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "ensure back clear hook failed: ${t.message}")
        }
    }

    fun applyKeywordDirect(keyword: String) {
        try {
            val run = {
                try {
                    val listener = keywordListenerImpl
                    if (listener == null) {
                        AndroidLog.e(TAG, "keyword listener missing, direct apply dropped")
                    } else {
                        listener.invoke(keyword)
                        refreshResultSearchLabels()
                        AndroidLog.i(TAG, "direct keyword applied len=${keyword.length}")
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "direct keyword apply failed: ${t.message}")
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) run()
            else Handler(Looper.getMainLooper()).post { run() }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "applyKeywordDirect failed: ${t.message}")
        }
    }

    /** 翻译壳占用标记：仅我方进入时才负责退出，不碰用户自己的翻译态。 */
    @Volatile
    private var translatorShellByUs = false
    /**
     * 摘k记账：k父容器/序号（退壳时清账；Q0(false)拆壳容器，无需回挂k，
     * 只记账供校验fail-closed）。
     */
    @Volatile
    private var removedTranslatorKRef: java.lang.ref.WeakReference<View>? = null
    @Volatile
    private var removedTranslatorKParentRef: java.lang.ref.WeakReference<ViewGroup>? = null
    @Volatile
    private var removedTranslatorKIndex: Int = -1

    /**
     * 按二进制名 + 多 loader 链取宿主类（q/k/N 同包，k 可见则同包基本可见）。
     */
    private fun loadHostClass(anchor: View, name: String): Class<*>? {
        val loaders = ArrayList<ClassLoader?>()
        hostClassLoader?.let { hcl ->
            runCatching { loaders.addAll(idClassLoaders(anchor, hcl)) }
        }
        runCatching { loaders.add(Thread.currentThread().contextClassLoader) }
        runCatching { loaders.add(anchor.context?.classLoader) }
        for (cl in loaders) {
            if (cl == null) continue
            val c = runCatching { Class.forName(name, false, cl) }.getOrNull()
            if (c != null) return c
        }
        return null
    }

    /** 翻译管理器单例（q.fXXXX 按型取，不写死字段名）。 */
    private fun translatingMgr(anchor: View): Any? {
        return try {
            val qCls = loadHostClass(anchor, NATIVE_HEIGHT_MGR_CLASS) ?: return null
            qCls.declaredFields
                .firstOrNull { it.type == qCls }
                ?.also { it.isAccessible = true }
                ?.get(null)
        } catch (_: Throwable) {
            null
        }
    }

    /** q.t0()：翻译进行中？ */
    private fun isTranslating(mgr: Any): Boolean {
        return try {
            val t0 = mgr.javaClass.declaredMethods
                .firstOrNull { it.name == "t0" && it.parameterTypes.isEmpty() }
                ?: return false
            t0.isAccessible = true
            (t0.invoke(mgr) as? Boolean) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 翻译壳进出（与工具栏交A/收起同参同序）：
     * 进 `Q0(mgr,true,false,null,6,null)`，出 `Q0(mgr,false,false,null,6,null)`。
     * 只借高槽布局，不挂 k、不走翻译引擎（k 由我方摘除后挂自己的条）。
     */
    private fun driveTranslatorShell(anchor: View, enter: Boolean): Boolean {
        return try {
            val mgr = translatingMgr(anchor) ?: run {
                AndroidLog.e(TAG, "translator shell: q missing")
                return false
            }
            val q0 = mgr.javaClass.declaredMethods.firstOrNull {
                it.name == "Q0" && it.parameterTypes.size == 6 &&
                    java.lang.reflect.Modifier.isStatic(it.modifiers)
            } ?: run {
                AndroidLog.e(TAG, "translator shell: Q0 missing")
                return false
            }
            q0.isAccessible = true
            q0.invoke(null, mgr, enter, false, null, 6, null)
            AndroidLog.i(TAG, "translator shell enter=$enter")
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "translator shell drive failed: $t")
            false
        }
    }

    /** 在 decor 树按类名找翻译 k（宿主挂载结果）。 */
    private fun findTranslatorTopView(decor: ViewGroup): View? {
        return try {
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            var hops = 0
            while (q.isNotEmpty() && hops < 600) {
                val v = q.removeFirst()
                hops++
                if (v.javaClass.name == NATIVE_TOPVIEW_CLASS) return v
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 摘k记账：进壳后宿主挂载的翻译k只借高槽布局，不走翻译引擎；
     * 摘除k并记账（父/序号弱引），随后挂己条。k不在（父空）即失败，
     * 上层 fail-closed。摘k后引擎保持正常拼音链路（防劫持件一）。
     */
    private fun detachTranslatorKForStrip(k: View): Boolean {
        return try {
            val parent = k.parent as? ViewGroup ?: run {
                AndroidLog.e(TAG, "translator k detach failed: no parent")
                return false
            }
            removedTranslatorKIndex = parent.indexOfChild(k)
            removedTranslatorKParentRef =
                java.lang.ref.WeakReference(parent)
            removedTranslatorKRef = java.lang.ref.WeakReference(k)
            parent.removeView(k)
            AndroidLog.i(TAG, "translator k detached idx=$removedTranslatorKIndex " +
                "parent=${parent.javaClass.name}")
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "translator k detach failed: $t")
            false
        }
    }

    /**
     * F41原生复用件（只改文案，不自绘；任一步原生取不到即整条fail-closed，禁仿制兜底）。
     * 复用点：
     * - 壳：translatingwhilewriting.q#Q0(mgr,true/false,…)（driveTranslatorShell已封）。
     * - 槽：ImeCandidateView#s0/r0(View,int,FrameLayout.LP)（q $t 1499-1516原生已挂k，我方不再s0）。
     * - 卡：translatingwhilewriting.k（NATIVE_TOPVIEW_CLASS）整卡复用，只改文案。
     * - 上行左：k内currentLanguageModeTv（TextView，初值r.b(r.c())如“中英互译”）改搜索类型；
     *   下拉容器k内languageOptionsContainer（translatingwhilewriting.d extends RecyclerView）
     *   经b#k(List<m>)喂搜索项 + d#setOnItemClick覆盖为搜索切换。
     * - 下行：k内sourceContentEditView（ImeEditText，hint“输入要翻译的内容”）改hint“搜索剪贴板”，
     *   加TextWatcher复用keywordListener过滤链（C47隔离：原生翻译watcher摘除+q/k翻译链守卫，
     *   只走过滤不调翻译请求，fail-closed）。
     * - 回车：EditorAction(SEARCH/DONE/GO/回车键)+OnKey双路接jumpBackToClipboardF41一点即跳（消费不留翻译页）。
     * - 右收起：k内exitButton（TextView“收起”）原生点击即p→I.n+r0+Q0(false)，
     *   我方不覆盖点击，仅经Q0(false)走原生S()/J0/V0/U0/C0/s()+N三连全还账。
     * - 卡高：k#getCurrentHeight()（q1.e0(d0+156)，k.java 1440-1442），窗高N#J3原生写，
     *   我方不钳小窗（J3仅防爆，bar/keyboard不动，验证门回翻译态原生，像素只diag）。
     */
    private data class NativeKPartsF41(
        val k: View,
        val edit: EditText,
        val modeTv: android.widget.TextView,
        val dropdown: ViewGroup,
        val exit: View?,
        val modeIcon: ImageView? = null
    )

    /** F41：k内件定位（只读+按型/文案现取，不写死字段名/id值；任一缺失返null fail-closed）。 */
    private fun findNativeKPartsF41(k: View): NativeKPartsF41? {
        return try {
            var edit: EditText? = null
            var modeTv: android.widget.TextView? = null
            var exitTv: View? = null
            var dropdown: ViewGroup? = null
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(k)
            var hops = 0
            while (q.isNotEmpty() && hops < 200) {
                val v = q.removeFirst()
                hops++
                if (v is EditText && edit == null) {
                    // k内唯一ImeEditText即sourceContentEditView（hint“输入要翻译的内容”旁证）。
                    val hint = runCatching { v.hint?.toString() }.getOrNull().orEmpty()
                    if (hint.contains("翻译") || v.hint == null || hint.isEmpty() || hint.contains("搜索")) {
                        edit = v
                    } else {
                        edit = edit ?: v
                    }
                }
                if (v is android.widget.TextView && v !is EditText) {
                    val t = runCatching { v.text?.toString() }.getOrNull().orEmpty()
                    if (t == "收起" && exitTv == null) exitTv = v
                    // modeTv初值如“中英互译”，含“译”或“∨”旁证；排除“收起”/“轻触此处继续输入”。
                    if (modeTv == null && t.isNotEmpty() && t != "收起" && !t.contains("轻触")) {
                        // 候选：长度<=8的上行小字（40sp级），先记候选，下文按k直系operationBar内再确证。
                        modeTv = v
                    }
                }
                // dropdown：translatingwhilewriting.d（二进制名$d）即RecyclerView子类。
                if (dropdown == null && v.javaClass.name == "com.tencent.wetype.plugin.hld.translatingwhilewriting.d") {
                    dropdown = v as? ViewGroup
                }
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            if (edit == null) {
                AndroidLog.e(TAG, "strip F41 reuse: SKIP native edit missing (fail-closed)")
                return null
            }
            if (modeTv == null) {
                AndroidLog.e(TAG, "strip F41 reuse: SKIP native modeTv missing (fail-closed)")
                return null
            }
            if (dropdown == null) {
                AndroidLog.e(TAG, "strip F41 reuse: SKIP native dropdown(d) missing (fail-closed)")
                return null
            }
            // modeTv确证：取operationBar容器内（k直系ConstraintLayout子链）文本最短者为准；
            // 上面BFS首个文本可能误取tip（GONE），此处以isShown+父链operationBar旁证纠正。
            val confirmedMode = runCatching { confirmModeTvInOpBarF41(k, modeTv) }.getOrNull() ?: modeTv
            // 上行左翻译图标：modeTv同级ImageView（只读定位，找不到即null，图标替换fail-open不拦mount）。
            val modeIcon = runCatching { findModeIconF41(k, confirmedMode) }.getOrNull()
            NativeKPartsF41(k, edit, confirmedMode, dropdown, exitTv, modeIcon)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 reuse parts failed: $t")
            null
        }
    }

    /** F41：operationBar内modeTv确证（只读）：k子链中operationBarContainer（高e0(100)级）内可见TextView首个非收起即mode。 */
    private fun confirmModeTvInOpBarF41(k: View, fallback: android.widget.TextView): android.widget.TextView {
        return try {
            if (k !is ViewGroup) return fallback
            // 遍历k直系：operationBarContainer为ConstraintLayout且含“收起”兄弟即是。
            for (i in 0 until minOf(k.childCount, 12)) {
                val root = k.getChildAt(i) as? ViewGroup ?: continue
                // rootContainer（ImeRadiusConstraintLayout）内再找operationBar。
                val chain: ArrayDeque<View> = ArrayDeque()
                chain.add(root)
                var hops = 0
                while (chain.isNotEmpty() && hops < 80) {
                    val v = chain.removeFirst()
                    hops++
                    if (v is ViewGroup) {
                        var hasExit = false
                        var candMode: android.widget.TextView? = null
                        for (j in 0 until minOf(v.childCount, 12)) {
                            val c = v.getChildAt(j) ?: continue
                            if (c is android.widget.TextView && c !is EditText) {
                                val t = runCatching { c.text?.toString() }.getOrNull().orEmpty()
                                if (t == "收起") hasExit = true
                                else if (t.isNotEmpty() && !t.contains("轻触") && candMode == null) candMode = c
                            }
                        }
                        if (hasExit && candMode != null) return candMode
                        for (j in 0 until minOf(v.childCount, 25)) {
                            v.getChildAt(j)?.let { chain.add(it) }
                        }
                    }
                }
            }
            fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    /** F41上行左图标定位（只读）：沿modeTv上溯3层祖先，每层扫直接ImageView子视图；找不到返null。 */
    private fun findModeIconF41(k: View, modeTv: android.widget.TextView): ImageView? {
        return try {
            var node: View? = modeTv
            var depth = 0
            while (node != null && node !== k && depth < 3) {
                val parent = node.parent as? ViewGroup ?: break
                var sizedHit: ImageView? = null
                var anyHit: ImageView? = null
                val modeIdx = (0 until parent.childCount).firstOrNull { parent.getChildAt(it) === node } ?: -1
                // 离modeTv最近的ImageView优先（通常在左侧）。
                val order = (0 until minOf(parent.childCount, 16)).sortedBy { kotlin.math.abs(it - modeIdx) }
                for (i in order) {
                    val c = parent.getChildAt(i) as? ImageView ?: continue
                    if (c === node) continue
                    if (anyHit == null) anyHit = c
                    if (c.visibility == View.VISIBLE && c.width > 0 && c.height > 0) {
                        sizedHit = c
                        break
                    }
                }
                if (sizedHit != null) return sizedHit
                // 布局未量测时（宽高=0）也记候选，但优先往上找已量测的；都没有才用未量测兜底。
                if (anyHit != null && depth == 2) return anyHit
                if (anyHit != null && depth == 0) {
                    // 同级有ImageView但尚未量测：先记下，继续往上看有没有更好的，回来再用它。
                    var up: ViewGroup? = parent.parent as? ViewGroup
                    var upHit: ImageView? = null
                    if (up != null) {
                        for (j in 0 until minOf(up.childCount, 16)) {
                            val c = up.getChildAt(j) as? ImageView ?: continue
                            if (c.visibility == View.VISIBLE && c.width > 0 && c.height > 0) {
                                upHit = c
                                break
                            }
                        }
                    }
                    return upHit ?: anyHit
                }
                node = parent
                depth++
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** F41上行左图标外观同步：颜色与"全量匹配"文字完全一致（深浅色跟随），alpha与文字对齐。 */
    private fun syncModeIconAppearance(icon: ImageView) {
        try {
            val modeTv = nativeKModeTvRefF41?.get()
            val targetColor = if (modeTv != null && Color.alpha(modeTv.currentTextColor) > 0) {
                modeTv.currentTextColor
            } else {
                resolveThemeIconColor(icon)
            }
            icon.setColorFilter(targetColor, PorterDuff.Mode.SRC_IN)
            icon.alpha = modeTv?.alpha ?: 1.0f
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 sync icon appearance failed: $t")
        }
    }

    /** 判断ImageView是否为k卡片内的翻译/搜索图标（Child 0 of operationBarContainer）。 */
    private fun isTranslatingLanguageIcon(iv: ImageView): Boolean {
        return try {
            val k = nativeKRefF41?.get() ?: return false
            val parent = iv.parent as? ViewGroup ?: return false
            parent.parent?.parent === k && parent.indexOfChild(iv) == 0
        } catch (_: Throwable) {
            false
        }
    }

    /** F41上行左图标防闪回Hook：宿主onAttachedToWindow协程会重复设回翻译图标，在此拦截锁定为搜索图标并同步深浅色。 */
    private fun hookModeIconStability(classLoader: ClassLoader) {
        if (f41IconHooked) return
        f41IconHooked = true
        try {
            installIconSetterHook(
                ImageView::class.java.getDeclaredMethod("setImageResource", Int::class.javaPrimitiveType),
                classLoader,
                "ImageView"
            )
            // 预装 AppCompat 层锁定：后出现的图标实例（懒加载/重建）同样被覆盖，
            // 无需等到 ensureModeIconSetterHooks 见到实例才装。
            runCatching {
                val appCompat = Class.forName(
                    "androidx.appcompat.widget.AppCompatImageView", false, classLoader
                )
                installNearestSetter(
                    appCompat, classLoader,
                    "setImageResource", Int::class.javaPrimitiveType, ::installIconSetterHook
                )
                installNearestSetter(
                    appCompat, classLoader,
                    "setImageDrawable", Drawable::class.java, ::installIconDrawableSetterHook
                )
            }.onFailure {
                AndroidLog.i(TAG, "strip F41 proactive AppCompat lock skipped: ${it.message}")
            }
            AndroidLog.i(TAG, "strip F41 mode icon stability hook installed")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 mode icon stability hook install failed: $t")
            f41IconHooked = false
        }
    }

    /**
     * 运行时图标类锁定：宿主翻译图标是 AppCompatImageView 的子类实现，
     * 其 setImageResource 覆盖不会进入 ImageView 同名基类方法。
     * 只锁定实际分发命中的最近覆盖，避免基类钩子漏拦宿主协程的复写。
     * setImageDrawable 同理：宿主可能直接设 Drawable 绕开 resId 路径，一并锁定。
     */
    private fun ensureModeIconSetterHooks(icon: ImageView?) {
        try {
            val start = icon?.javaClass ?: return
            val loader = hostClassLoader ?: icon?.context?.classLoader ?: return
            installNearestSetter(
                start, loader,
                "setImageResource", Int::class.javaPrimitiveType, ::installIconSetterHook
            )
            installNearestSetter(
                start, loader,
                "setImageDrawable", Drawable::class.java, ::installIconDrawableSetterHook
            )
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 runtime icon hook failed: $t")
        }
    }

    /**
     * 沿继承链向上找最近的 setter 覆盖并安装钩子（找到即停，不含 ImageView 基类自身，
     * 基类由 [hookModeIconStability] 另行安装）。
     */
    private fun installNearestSetter(
        startClass: Class<*>,
        loader: ClassLoader?,
        methodName: String,
        paramType: Class<*>?,
        installer: (java.lang.reflect.Method, ClassLoader?, String) -> Unit
    ) {
        try {
            val param = paramType ?: return
            var owner: Class<*>? = startClass
            while (owner != null && ImageView::class.java.isAssignableFrom(owner)) {
                val setter = runCatching {
                    owner.getDeclaredMethod(methodName, param)
                }.getOrNull()
                if (setter != null) {
                    if (setter.declaringClass != ImageView::class.java) {
                        installer(setter, loader, "runtime:${owner.name}")
                    }
                    return
                }
                if (owner == ImageView::class.java) return
                owner = owner.superclass
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 nearest $methodName hook failed: $t")
        }
    }

    private fun installIconSetterHook(
        method: java.lang.reflect.Method,
        fallbackLoader: ClassLoader?,
        source: String
    ) {
        val key = "${method.declaringClass.name}#setImageResource(int)"
        if (!f41IconSetterHooked.add(key)) return
        method.isAccessible = true
        method.hookBefore { param ->
            try {
                val iv = param.thisObject as? ImageView ?: return@hookBefore
                if (!shouldLockModeIconTarget(iv)) return@hookBefore
                val searchRes = resolveLockedSearchIconRes(iv, fallbackLoader) ?: return@hookBefore
                val incoming = param.args[0] as? Int
                if (incoming != searchRes) {
                    param.args[0] = searchRes
                    AndroidLog.i(TAG, "strip F41 icon [$source]: intercepted setImageResource $incoming->$searchRes (re-locked search icon)")
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip F41 mode icon hookBefore failed: $t")
            }
        }
        method.hookAfter { param ->
            try {
                val iv = param.thisObject as? ImageView ?: return@hookAfter
                if (!shouldLockModeIconTarget(iv)) return@hookAfter
                syncModeIconAppearance(iv)
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip F41 mode icon hookAfter failed: $t")
            }
        }
        AndroidLog.i(TAG, "strip F41 mode icon setter hook installed: ${method.declaringClass.name} ($source)")
    }

    /**
     * setImageDrawable 旁路锁定：宿主协程若直接设 Drawable（不走 resId），
     * 同样替换为搜索图标 Drawable 并同步外观；解析失败则放行，避免影响原生绘制。
     */
    private fun installIconDrawableSetterHook(
        method: java.lang.reflect.Method,
        fallbackLoader: ClassLoader?,
        source: String
    ) {
        val key = "${method.declaringClass.name}#setImageDrawable(Landroid.graphics.drawable.Drawable)"
        if (!f41IconSetterHooked.add(key)) return
        method.isAccessible = true
        method.hookBefore { param ->
            try {
                val iv = param.thisObject as? ImageView ?: return@hookBefore
                if (!shouldLockModeIconTarget(iv)) return@hookBefore
                val searchRes = resolveLockedSearchIconRes(iv, fallbackLoader) ?: return@hookBefore
                val replacement = runCatching {
                    iv.context.getDrawable(searchRes)
                }.getOrNull() ?: runCatching {
                    val loader = hostClassLoader ?: iv.context.classLoader ?: fallbackLoader
                    ?: return@runCatching null
                    val appCompatRes = Class.forName(
                        "androidx.appcompat.content.res.AppCompatResources", false, loader
                    )
                    val get = appCompatRes.getDeclaredMethod(
                        "getDrawable", android.content.Context::class.java, Int::class.javaPrimitiveType
                    ).apply { isAccessible = true }
                    get.invoke(null, iv.context, searchRes) as? Drawable
                }.getOrNull() ?: return@hookBefore
                param.args[0] = replacement
                AndroidLog.i(TAG, "strip F41 icon [$source]: intercepted setImageDrawable (re-locked search icon)")
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip F41 mode icon drawable hookBefore failed: $t")
            }
        }
        method.hookAfter { param ->
            try {
                val iv = param.thisObject as? ImageView ?: return@hookAfter
                if (!shouldLockModeIconTarget(iv)) return@hookAfter
                syncModeIconAppearance(iv)
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip F41 mode icon drawable hookAfter failed: $t")
            }
        }
        AndroidLog.i(TAG, "strip F41 mode icon drawable setter hook installed: ${method.declaringClass.name} ($source)")
    }

    private fun shouldLockModeIconTarget(iv: ImageView): Boolean {
        if (!translatorShellByUs) return false
        val icon = nativeKModeIconRefF41?.get()
        return (icon != null && iv === icon) || isTranslatingLanguageIcon(iv)
    }

    private fun resolveLockedSearchIconRes(iv: ImageView, fallbackLoader: ClassLoader?): Int? {
        cachedSearchIconRes?.let { return it }
        val loader = hostClassLoader ?: iv.context?.classLoader ?: fallbackLoader ?: return null
        return resolveSearchIconRes(iv, loader)?.also { cachedSearchIconRes = it }
    }

    /** F41上行左图标替换：翻译图标换宿主搜索图标，颜色和亮度同步文字；无图标或无宿主res即保持原生。 */
    private fun applySearchIconF41(parts: NativeKPartsF41) {
        try {
            fun doApply() {
                try {
                    val cl = hostClassLoader ?: parts.k.context?.classLoader
                    if (cl == null) {
                        AndroidLog.e(TAG, "strip F41 icon: SKIP host loader missing (keep translate icon)")
                        return
                    }
                    val searchRes = resolveSearchIconRes(parts.k, cl)
                    if (searchRes == null) {
                        AndroidLog.e(TAG, "strip F41 icon: SKIP no host search drawable (keep translate icon)")
                        return
                    }
                    cachedSearchIconRes = searchRes
                    val icon = parts.modeIcon
                    if (icon != null && icon.parent != null) {
                        runCatching {
                            icon.setImageResource(searchRes)
                            syncModeIconAppearance(icon)
                        }.onSuccess {
                            AndroidLog.i(TAG, "strip F41 icon: translate->search res=$searchRes (ImageView, appearance synced with modeTv)")
                        }.onFailure {
                            AndroidLog.e(TAG, "strip F41 icon: setImageResource failed: ${it.message} (keep translate icon)")
                        }
                        return
                    }
                    // 独立ImageView没找到，回退看modeTv左compoundDrawable。
                    val left = runCatching { parts.modeTv.compoundDrawables?.getOrNull(0) }.getOrNull()
                    if (left != null) {
                        runCatching {
                            parts.modeTv.setCompoundDrawablesWithIntrinsicBounds(searchRes, 0, 0, 0)
                        }.onSuccess {
                            AndroidLog.i(TAG, "strip F41 icon: translate->search res=$searchRes (compoundLeft)")
                        }.onFailure {
                            AndroidLog.e(TAG, "strip F41 icon: compound set failed: ${it.message} (keep translate icon)")
                        }
                        return
                    }
                    AndroidLog.e(TAG, "strip F41 icon: SKIP no mode icon view/compound (keep translate icon)")
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F41 icon apply failed: $t (keep translate icon)")
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) doApply()
            else parts.k.post { runCatching { doApply() } }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 icon dispatch failed: $t")
        }
    }

    /** F41：原生k卡高（k#getCurrentHeight()，q1.e0(d0+156)；取不到返null fail-closed，不写死）。 */
    private fun nativeKHeightF41(k: View): Int? {
        return try {
            val m = k.javaClass.declaredMethods.firstOrNull {
                it.name == "getCurrentHeight" && it.parameterTypes.isEmpty()
            } ?: run {
                AndroidLog.e(TAG, "strip F41 height: k#getCurrentHeight missing (fail-closed)")
                return null
            }
            m.isAccessible = true
            (m.invoke(k) as? Number)?.toInt()
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 height failed: $t")
            null
        }
    }

    /** F41：切搜索模式（UI切换生效+即换过滤stub：重推当前词走现有过滤链）。 */
    private fun setSearchModeF41(mode: Int, reason: String) {
        try {
            if (mode != SEARCH_MODE_FULL && mode != SEARCH_MODE_FUZZY) return
            if (searchModeF41 == mode) return
            searchModeF41 = mode
            val tv = nativeKModeTvRefF41?.get()
            if (tv != null && tv.parent != null) {
                runCatching {
                    if (Looper.myLooper() == Looper.getMainLooper()) tv.text = searchModeNameF41(mode)
                    else tv.post { runCatching { tv.text = searchModeNameF41(mode) } }
                }
            }
            // 切模式即换过滤（stub：同关键词重推一次，复用现有S5防抖+后台+setList回放链）。
            val kw = pendingKeyword
            runCatching { keywordListenerImpl?.invoke(kw) }
            AndroidLog.i(TAG, "strip F41 mode: ${searchModeNameF41(mode)} reason=$reason kwLen=${kw.length} (UI切换生效，切模式即换过滤stub)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 setMode failed: $t")
        }
    }

    /** F41：翻译态原生可见性判定（只读，像素只diag）：q.t0/innerState + k挂载 + toolbar.x.A + exit/isShown。 */
    private fun verifyNativeTranslationStateDiagF41(decor: ViewGroup, tag: String): Boolean {
        return try {
            val mgr = runCatching { translatingMgr(decor) }.getOrNull()
            val translating = if (mgr != null) runCatching { isTranslating(mgr) }.getOrDefault(false) else false
            val k = runCatching { findTranslatorTopView(decor) }.getOrNull()
            val kOn = k != null && k.parent != null && k.visibility == View.VISIBLE
            val kH = if (k != null) runCatching { nativeKHeightF41(k) }.getOrNull() else null
            val live = runCatching {
                val bar = findStripToolbarBar(decor)
                queryToolbarLiveF39(bar)
            }.getOrNull()
            AndroidLog.i(TAG, "strip F41 verify [$tag]: translating=$translating kOn=$kOn kH=$kH toolbarLive=$live " +
                "mode=${searchModeNameF41(searchModeF41)} (翻译态原生判定，像素只diag，不压高不动栏/键)")
            // F41门：壳byUs且k在即PASS（翻译态原生在位）；其余只diag不REVERT。
            translatorShellByUs && kOn
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 verify failed: $t")
            false
        }
    }

    /** F41：复用原生k（只改文案，不自绘；失败fail-closed退壳）。 */
    private fun repurposeNativeKForSearchF41(decor: ViewGroup, k: View): Boolean {
        return try {
            val parts = findNativeKPartsF41(k) ?: run {
                AndroidLog.e(TAG, "strip F41 reuse dropped: parts missing")
                return false
            }
            if (nativeSearchRestore == null) {
                nativeSearchRestore = captureNativeSearchState(parts)
                submitSession.open()
            }
            nativeKRefF41 = java.lang.ref.WeakReference(k)
            nativeKEditRefF41 = java.lang.ref.WeakReference(parts.edit)
            nativeKModeTvRefF41 = java.lang.ref.WeakReference(parts.modeTv)
            nativeKModeIconRefF41 = parts.modeIcon?.let { java.lang.ref.WeakReference(it) }
            overlayParentRef = java.lang.ref.WeakReference(decor)
            searchManager = translatingMgr(k)
            // 圆角B：条圆角跟输入法背景走（WeTypeSettings.getCornerRadiusXposed，与WindowHooks同源）；
            // 搜索框组件卡片圆角比输入法圆角少 8dp（不小于 0dp）。
            // k内rootContainer（ImeRadiusConstraintLayout）setRadius(B)+setBorderWidth(1f)已在X()为f0(32)，
            // 此处仅当B可取才覆盖为B，不可取则保留原生（fail-closed不自绘，禁f0(32)/GradientDrawable/硬编码色）。
            runCatching {
                val root = findNativeKRootContainerF41(k)
                if (root != null) {
                    val radius = try {
                        val baseRadiusDp = WeTypeSettings.getCornerRadiusXposed(root.context).toFloat()
                        val cardRadiusDp = resolveCardCornerRadiusDp(baseRadiusDp)
                        TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            cardRadiusDp,
                            root.resources.displayMetrics
                        ).roundToInt()
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "strip F41 radius: B missing, keep native ($t)")
                        null
                    }
                    if (radius != null) {
                        val setRadius = root.javaClass.declaredMethods.firstOrNull {
                            it.name == "setRadius" && it.parameterTypes.size == 1
                        }
                        if (setRadius != null) {
                            setRadius.isAccessible = true
                            when (setRadius.parameterTypes[0]) {
                                Int::class.javaPrimitiveType, Integer::class.java ->
                                    setRadius.invoke(root, radius)
                                Float::class.javaPrimitiveType, java.lang.Float::class.java ->
                                    setRadius.invoke(root, radius.toFloat())
                            }
                            AndroidLog.i(TAG, "strip F41 radius: B=$radius (base-8dp) applied on ${root.javaClass.simpleName} (原生复用，仅圆角B-8dp)")
                        }
                    }
                }
            }
            // 上行左：改搜索类型文案（保留原生字号/肤色/padding/手势，仅改text）。
            runCatching {
                parts.modeTv.text = searchModeNameF41(searchModeF41)
            }
            // 上行左图标：翻译图标换宿主搜索图标（只换drawable，不碰尺寸/tint/padding；失败只记日志不拦mount）。
            // 先按运行时图标类装好 setImageResource 锁定，避免 AppCompat 覆盖绕过基类钩子后闪回。
            runCatching { ensureModeIconSetterHooks(parts.modeIcon) }
                .onFailure { AndroidLog.e(TAG, "strip F41 runtime icon hook failed: ${it.message}") }
            runCatching { applySearchIconF41(parts) }
            // 下拉：经b#k喂搜索项 + d#setOnItemClick覆盖为搜索切换；失败fail-closed。
            if (!wireNativeDropdownF41(parts)) {
                AndroidLog.e(TAG, "strip F41 reuse dropped: dropdown wire failed")
                return false
            }
            // 下行：hint改搜索剪贴板 + 输入即过滤（复用keywordListener链）；失败fail-closed。
            if (!wireNativeInputF41(parts)) {
                AndroidLog.e(TAG, "strip F41 reuse dropped: input wire failed")
                return false
            }
            searchPrefill?.let { keyword ->
                searchPrefill = null
                parts.edit.setText(keyword)
                parts.edit.setSelection(parts.edit.text?.length ?: 0)
                submitSession.edit(keyword)
            }
            // C46：直点收起可观测（exit包装+Q0补记，只记账不抢原生时序；失败只diag不拦mount）。
            runCatching { wireNativeExitF41(parts) }
            runCatching { ensureCollapseHookF41(parts.k) }
            nativeKRefF41 = java.lang.ref.WeakReference(k)
            nativeKEditRefF41 = java.lang.ref.WeakReference(parts.edit)
            nativeKModeTvRefF41 = java.lang.ref.WeakReference(parts.modeTv)
            nativeKModeIconRefF41 = parts.modeIcon?.let { java.lang.ref.WeakReference(it) }
            overlayParentRef = java.lang.ref.WeakReference(decor)
            overlayPending = false
            // 卡高：原生k.getCurrentHeight现量（充分利用扩充高度，不钳小窗；J3原生写窗，bar/键following原生）。
            val h = runCatching { nativeKHeightF41(k) }.getOrNull()
            AndroidLog.i(TAG, "strip F41 mounted: native k reused mode=${searchModeNameF41(searchModeF41)} " +
                "kH=$h hint=搜索剪贴板 exit=原生收起(S/J0/V0/U0/C0/s) class=${k.javaClass.name}")
            // 焦点：原生q0()已请求，仍显式补一次（只requestFocus，不碰布局/高度/栏/键）。
            runCatching {
                parts.edit.post {
                    runCatching { if (ownsSearchBox(parts.edit)) parts.edit.requestFocus() }
                    AndroidLog.i(TAG, "strip F41 focus: requested hasFocus=${parts.edit.hasFocus()} class=${parts.edit.javaClass.name}")
                }
            }
            // 验证门：翻译态原生可见性判定，像素只diag。
            runCatching { verifyNativeTranslationStateDiagF41(decor, "mount") }
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 reuse failed: $t")
            false
        }
    }

    /** F41：k内rootContainer定位（只读）：ImeRadiusConstraintLayout首个即是（k ctor仅此一白卡根）。 */
    private fun findNativeKRootContainerF41(k: View): ViewGroup? {
        return try {
            if (k !is ViewGroup) return null
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(k)
            var hops = 0
            while (q.isNotEmpty() && hops < 120) {
                val v = q.removeFirst()
                hops++
                if (v.javaClass.name == NATIVE_RADIUS_CLASS) return v as? ViewGroup
                if (v is ViewGroup && v !== k) {
                    // k直系第一层即rootContainer（ConstraintLayout.LayoutParams），优先直系。
                }
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** F41：下拉接线（原生复用）：b#k喂[全量/模糊]m项 + d#setOnItemClick覆盖。 */
    private fun wireNativeDropdownF41(parts: NativeKPartsF41): Boolean {
        return try {
            val cl = hostClassLoader ?: parts.k.context?.classLoader ?: return false
            val mCls = runCatching { Class.forName("com.tencent.wetype.plugin.hld.translatingwhilewriting.m", false, cl) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F41 dropdown: m missing (fail-closed)")
                return false
            }
            val ctor = runCatching { mCls.getDeclaredConstructor(Int::class.javaPrimitiveType, String::class.java) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F41 dropdown: m(int,String) missing (fail-closed)")
                return false
            }
            ctor.isAccessible = true
            val dropdown = parts.dropdown
            // 先经adapter.b#k直喂（绕过d#t的o()强制回默认语言，避免r.d副作用）；取不到adapter则fail-closed。
            val adapter = resolveDropdownAdapterF41(dropdown) ?: run {
                AndroidLog.e(TAG, "strip F41 dropdown: adapter missing (fail-closed)")
                return false
            }
            val kMethod = adapter.javaClass.declaredMethods.firstOrNull {
                it.name == "k" && it.parameterTypes.size == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0])
            } ?: run {
                AndroidLog.e(TAG, "strip F41 dropdown: b#k(List) missing (fail-closed)")
                return false
            }
            kMethod.isAccessible = true
            // C46：mount直喂一次（首屏）；展开时原生重绑由b#k拦+ s0重喂兜底（下 hook）。
            if (!feedSearchItemsF41(adapter, kMethod, mCls, ctor)) return false
            if (!applyDropdownClickHandlerF41(parts, cl)) {
                AndroidLog.e(TAG, "strip F41 dropdown: d#setOnItemClick missing (fail-closed)")
                return false
            }
            // C46：拦数据源（展开重绑仍是我方两项）+ 展开重喂（s0后补喂+补点击）。
            runCatching { ensureDropdownSrcHookF41(adapter, kMethod, parts, mCls, ctor) }
            runCatching { ensureExpandRefeedHookF41(parts, mCls, ctor) }
            AndroidLog.i(TAG, "strip F41 dropdown: wired 全量/模糊 (b#k直喂+d#setOnItemClick覆盖，原生复用)")
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 dropdown wire failed: $t")
            false
        }
    }

    /** C46：下拉adapter定位（只读）：getLanguageListAdapter优先，字段回退；取不到返null。 */
    private fun resolveDropdownAdapterF41(dropdown: ViewGroup): Any? {
        return try {
            val getter = dropdown.javaClass.declaredMethods.firstOrNull {
                it.name == "getLanguageListAdapter" && it.parameterTypes.isEmpty()
            }
            if (getter != null) {
                getter.isAccessible = true
                getter.invoke(dropdown)
            } else {
                val f = dropdown.javaClass.declaredFields.firstOrNull { it.type.name.endsWith(".translatingwhilewriting.b") }
                    ?: dropdown.javaClass.declaredFields.firstOrNull {
                        it.type.superclass?.name?.contains("RecyclerView") == true || it.type.name.contains("RecyclerView")
                    } ?: return null
                f.isAccessible = true
                f.get(dropdown)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** C46：组我方两项m对象（只改文案，id 1001/1002现取常量，不写死语言）。 */
    private fun buildSearchItemsF41(mCls: Class<*>, ctor: java.lang.reflect.Constructor<*>): ArrayList<Any> {
        val full = ctor.newInstance(SEARCH_MODE_FULL_ID, "全量匹配")
        val fuzzy = ctor.newInstance(SEARCH_MODE_FUZZY_ID, "模糊匹配")
        return arrayListOf(full, fuzzy)
    }

    /** C46：经b#k直喂两项（feeding守卫防hook自递归；失败false fail-closed）。 */
    private fun feedSearchItemsF41(adapter: Any, kMethod: java.lang.reflect.Method, mCls: Class<*>, ctor: java.lang.reflect.Constructor<*>): Boolean {
        if (feedingDropdownF41) return true
        return try {
            feedingDropdownF41 = true
            kMethod.invoke(adapter, buildSearchItemsF41(mCls, ctor))
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 dropdown feed failed: $t")
            false
        } finally {
            feedingDropdownF41 = false
        }
    }

    /** C46：覆盖d#setOnItemClick为搜索切换（切模式+收下拉经k#s0反射）。 */
    private fun applyDropdownClickHandlerF41(parts: NativeKPartsF41, cl: ClassLoader): Boolean {
        if (!ownsSearchBox(parts.edit)) return false
        return try {
            val dropdown = parts.dropdown
            val setCb = dropdown.javaClass.declaredMethods.firstOrNull {
                it.name == "setOnItemClick" && it.parameterTypes.size == 1
            } ?: return false
            setCb.isAccessible = true
            val paramType = setCb.parameterTypes[0]
            val handler = java.lang.reflect.Proxy.newProxyInstance(cl, arrayOf(paramType)) { _, method, args ->
                try {
                    if (method.name == "invoke" || method.name == "a") {
                        val info = args?.firstOrNull()
                        val id = runCatching {
                            val g = info?.javaClass?.declaredMethods?.firstOrNull {
                                (it.name == "getId" || it.name == "a") && it.parameterTypes.isEmpty()
                            }
                            g?.also { it.isAccessible = true }?.invoke(info) as? Number
                        }?.getOrNull()?.toInt()
                        if (id == SEARCH_MODE_FULL_ID) {
                            setSearchModeF41(SEARCH_MODE_FULL, "dropdown")
                            runCatching { closeNativeDropdownF41(parts.k) }
                            null
                        } else if (id == SEARCH_MODE_FUZZY_ID) {
                            setSearchModeF41(SEARCH_MODE_FUZZY, "dropdown")
                            runCatching { closeNativeDropdownF41(parts.k) }
                            null
                        } else {
                            AndroidLog.e(TAG, "strip F41 dropdown: unknown id=$id (fail-closed)")
                            null
                        }
                    } else if (method.name == "toString") {
                        "F41SearchModeClick"
                    } else null
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F41 dropdown cb failed: $t")
                    null
                }
            }
            setCb.invoke(dropdown, handler)
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 dropdown cb apply failed: $t")
            false
        }
    }

    /** C46：拦b#k数据源（全局一次）：原生展开重绑走此路即换回我方三项；after补点击+补灰。 */
    private fun ensureDropdownSrcHookF41(adapter: Any, kMethod: java.lang.reflect.Method, parts: NativeKPartsF41, mCls: Class<*>, ctor: java.lang.reflect.Constructor<*>) {
        if (f41DropdownSrcHooked) return
        f41DropdownSrcHooked = true
        try {
            kMethod.hookBefore { param ->
                try {
                    val active = nativeKRefF41?.get()?.let(::findNativeKPartsF41) ?: return@hookBefore
                    if (param.thisObject !== resolveDropdownAdapterF41(active.dropdown)) return@hookBefore
                    if (feedingDropdownF41) return@hookBefore
                    if (!translatorShellByUs) return@hookBefore
                    if (nativeKRefF41?.get()?.parent == null) return@hookBefore
                    val arg = param.args.firstOrNull() as? List<*> ?: return@hookBefore
                    // 已是我方两项（1001/1002）则放行，防抖。
                    val ids = arg.mapNotNull {
                        runCatching {
                            val g = it?.javaClass?.declaredMethods?.firstOrNull {
                                (it.name == "getId" || it.name == "a") && it.parameterTypes.isEmpty()
                            }
                            g?.also { m -> m.isAccessible = true }?.invoke(it) as? Number
                        }.getOrNull()?.toInt()
                    }.toSet()
                    if (ids == setOf(SEARCH_MODE_FULL_ID, SEARCH_MODE_FUZZY_ID)) return@hookBefore
                    param.args[0] = buildSearchItemsF41(mCls, ctor)
                    AndroidLog.i(TAG, "strip F41 dropdown: native rebind intercepted->search items (展开重绑已拦)")
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F41 rebind intercept failed: $t")
                }
            }
            kMethod.hookAfter { param ->
                try {
                    val active = nativeKRefF41?.get()?.let(::findNativeKPartsF41) ?: return@hookAfter
                    if (param.thisObject !== resolveDropdownAdapterF41(active.dropdown)) return@hookAfter
                    if (feedingDropdownF41) return@hookAfter
                    if (!translatorShellByUs) return@hookAfter
                    val cur = nativeKRefF41?.get() ?: return@hookAfter
                    if (cur.parent == null) return@hookAfter
                    val cl = hostClassLoader ?: cur.context?.classLoader ?: return@hookAfter
                    // 原生重绑可能连带重置点击监听，补一次。
                    val p = runCatching {
                        var node: NativeKPartsF41? = null
                        runCatching {
                            val dd = cur.let { findNativeKPartsF41(it) }
                            if (dd != null) node = dd
                        }
                        node
                    }.getOrNull()
                    if (p != null) {
                        runCatching { applyDropdownClickHandlerF41(p, cl) }
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F41 rebind after failed: $t")
                }
            }
            AndroidLog.i(TAG, "strip F41 dropdown: src hook armed (b#k拦)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 src hook arm failed: $t")
            f41DropdownSrcHooked = false
        }
    }

    /** C46：展开重喂兜底（全局一次）：k#s0 toggle后补喂+补点击，防b#k拦漏网。 */
    private fun ensureExpandRefeedHookF41(parts: NativeKPartsF41, mCls: Class<*>, ctor: java.lang.reflect.Constructor<*>) {
        if (f41ExpandHooked) return
        f41ExpandHooked = true
        try {
            val s0 = parts.k.javaClass.declaredMethods.firstOrNull {
                it.name == "s0" && it.parameterTypes.isEmpty()
            } ?: run {
                f41ExpandHooked = false
                return
            }
            s0.isAccessible = true
            s0.hookAfter { param ->
                try {
                    if (!translatorShellByUs) return@hookAfter
                    val cur = nativeKRefF41?.get() ?: return@hookAfter
                    if (param.thisObject !== cur || cur.parent == null) return@hookAfter
                    val token = submitSession.id
                    cur.postDelayed({
                        try {
                            if (token != submitSession.id || nativeKRefF41?.get() !== cur || !translatorShellByUs) return@postDelayed
                            val fresh = runCatching { findNativeKPartsF41(cur) }.getOrNull() ?: return@postDelayed
                            val ad = runCatching { resolveDropdownAdapterF41(fresh.dropdown) }.getOrNull() ?: return@postDelayed
                            val km = ad.javaClass.declaredMethods.firstOrNull {
                                it.name == "k" && it.parameterTypes.size == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0])
                            } ?: return@postDelayed
                            km.isAccessible = true
                            // 展开时重喂：直喂两项（feeding守卫内hookBefore自动放行）。
                            runCatching { feedSearchItemsF41(ad, km, mCls, ctor) }
                            val cl = hostClassLoader ?: cur.context?.classLoader ?: return@postDelayed
                            runCatching { applyDropdownClickHandlerF41(fresh, cl) }
                            AndroidLog.i(TAG, "strip F41 dropdown: expand refeed done (s0后重喂+补点击)")
                        } catch (t: Throwable) {
                            AndroidLog.e(TAG, "strip F41 expand refeed failed: $t")
                        }
                    }, 120)
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F41 expand hook failed: $t")
                }
            }
            AndroidLog.i(TAG, "strip F41 dropdown: expand hook armed (k#s0后重喂)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 expand hook arm failed: $t")
            f41ExpandHooked = false
        }
    }

    /** F41：收下拉（k#s0 toggle；当前展开态才调，状态经currentLanguageModeSelectionState读，缺失则反射t0(0)兜底）。 */
    private fun closeNativeDropdownF41(k: View) {
        try {
            val s0 = k.javaClass.declaredMethods.firstOrNull {
                it.name == "s0" && it.parameterTypes.isEmpty()
            } ?: return
            s0.isAccessible = true
            // s0为toggle：仅当下拉展开（selection==2）才调关；否则不动防误开。
            val sel = runCatching {
                val g = k.javaClass.declaredMethods.firstOrNull {
                    it.name == "getCurrentLanguageModeSelectionState" && it.parameterTypes.isEmpty()
                }
                g?.also { it.isAccessible = true }?.invoke(k)?.let { flow ->
                    val gv = flow.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterTypes.isEmpty() }
                    gv?.also { it.isAccessible = true }?.invoke(flow) as? Number
                }?.toInt()
            }.getOrNull()
            if (sel == 2) {
                s0.invoke(k)
                AndroidLog.i(TAG, "strip F41 dropdown: closed via k#s0 (sel=2)")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 dropdown close failed: $t")
        }
    }

    /** Reuse native input styling; text changes update filtering but never create submission intent. */
    private fun wireNativeInputF41(parts: NativeKPartsF41): Boolean {
        return try {
            val box = parts.edit
            val token = submitSession.id
            // C46：hint稳定搜索剪贴板（只改文案）：直设+布局监听重申+延时重申+setHint拦，原生蓝占位竞态即压回。
            runCatching { applySearchHintF41(box) }
            runCatching { ensureHintStableF41(box) }
            synchronized(trackedBoxes) { trackedBoxes.add(box) }
            synchronized(nativeKWatchersF41) {
                if (nativeKWatchersF41[box] == null) {
                    val w = object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            try {
                                val raw = s?.toString().orEmpty()
                                if (!ownsSearchBox(box)) return
                                submitSession.edit(raw)
                                if (submitSession.phase != ClipboardSearchSubmitSession.Phase.EDITING &&
                                    submitSession.phase != ClipboardSearchSubmitSession.Phase.COMMITTING) return
                                pendingKeyword = raw
                                keywordListenerImpl?.invoke(pendingKeyword)
                            } catch (t: Throwable) {
                                AndroidLog.e(TAG, "strip F41 keyword failed: ${t.message}")
                            }
                            updateClearVisibility(box)
                        }
                    }
                    box.addTextChangedListener(w)
                    nativeKWatchersF41[box] = w
                    // C47隔离：摘原生翻译watcher只留我方（只走keywordListener过滤，不调翻译请求）。
                    runCatching { detachNativeTranslationWatchersF41(box, w) }
                }
            }
            // C47隔离守卫（全局一次，fail-closed）：后加原生watcher拦+q/k翻译链守卫。
            runCatching { ensureWatcherGuardF41() }
            ensureTranslationNetGuardF41(box)
            // C47回车直跳：EditorAction(SEARCH/DONE/GO/回车键)+OnKey双路接jumpBackToClipboardF41，
            // 消费返回true不停留翻译页；300/800ms重申防原生后设覆盖（只改监听，不碰hint/布局）。
            runCatching { applyJumpHandlersF41(box, "wire") }
            box.postDelayed({
                try {
                    if (submitSession.id == token && translatorShellByUs && nativeKEditRefF41?.get() === box && box.parent != null) {
                        applyJumpHandlersF41(box, "reassert300")
                    }
                } catch (_: Throwable) {
                }
            }, 300)
            box.postDelayed({
                try {
                    if (submitSession.id == token && translatorShellByUs && nativeKEditRefF41?.get() === box && box.parent != null) {
                        applyJumpHandlersF41(box, "reassert800")
                    }
                } catch (_: Throwable) {
                }
            }, 800)
            // 清除按钮：原生翻译条无X，不自建X（禁系统图标兜底）；清空走框内删字+clearSearch语义由过滤链空词恢复。
            AndroidLog.i(TAG, "strip F41 input: wired hint=搜索剪贴板 (原生ImeEditText+skin.w保留，输入即过滤复用链)")
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 input wire failed: $t")
            false
        }
    }

    /** Editor/hardware enter paths share the native action session and are idempotent. */
    private fun applyJumpHandlersF41(box: EditText, reason: String) {
        if (!ownsSearchBox(box)) return
        box.setOnEditorActionListener { _, action, event ->
            val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                event?.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            val submit = action in setOf(EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEND) || enter
            if (!submit || !ownsSearchBox(box)) false else {
                if (event == null || event.action == android.view.KeyEvent.ACTION_DOWN)
                    jumpBackToClipboardF41("editor-$action")
                true
            }
        }
        box.setOnKeyListener { _, key, event ->
            if ((key == android.view.KeyEvent.KEYCODE_ENTER || key == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) && ownsSearchBox(box)) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) jumpBackToClipboardF41("enter-key")
                true
            } else false
        }
    }

    /** C47隔离：摘原生翻译watcher（只读快照+官方remove，禁动我方watcher；失败fail-closed只记日志）。 */
    private fun detachNativeTranslationWatchersF41(box: EditText, keep: TextWatcher): Int {
        return try {
            var c: Class<*>? = box.javaClass
            var f: java.lang.reflect.Field? = null
            while (c != null && c != Any::class.java) {
                f = runCatching { c.getDeclaredField("mListeners") }.getOrNull()
                if (f != null) break
                c = c.superclass
            }
            if (f == null) {
                AndroidLog.e(TAG, "strip F41 isolate: SKIP mListeners missing (fail-closed)")
                return 0
            }
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val list = f.get(box) as? ArrayList<TextWatcher>
            if (list == null) {
                AndroidLog.e(TAG, "strip F41 isolate: SKIP mListeners null (fail-closed)")
                return 0
            }
            val snapshot = ArrayList(list)
            var removed = 0
            for (w in snapshot) {
                if (w === keep || !w.javaClass.name.startsWith("$NATIVE_TOPVIEW_CLASS$")) continue
                box.removeTextChangedListener(w)
                removed++
            }
            AndroidLog.i(TAG, "strip F41 isolate: detached $removed native watcher(s), " +
                "kept search watcher (只走keywordListener过滤，不调翻译请求)")
            removed
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 isolate detach failed: $t")
            0
        }
    }

    /** C47隔离：拦后加原生watcher（全局一次；仅我方框+搜索态，非我方一律放行；fail-closed）。 */
    private fun ensureWatcherGuardF41() {
        if (f41WatcherGuardHooked) return
        f41WatcherGuardHooked = true
        try {
            val add = android.widget.TextView::class.java.declaredMethods.firstOrNull {
                it.name == "addTextChangedListener" && it.parameterTypes.size == 1 &&
                    TextWatcher::class.java.isAssignableFrom(it.parameterTypes[0])
            } ?: run {
                f41WatcherGuardHooked = false
                return
            }
            add.isAccessible = true
            add.hookBefore { param ->
                try {
                    val tv = param.thisObject as? EditText ?: return@hookBefore
                    if (nativeKEditRefF41?.get() !== tv) return@hookBefore
                    if (!translatorShellByUs) return@hookBefore
                    val incoming = param.args.firstOrNull() as? TextWatcher ?: return@hookBefore
                    val ours = synchronized(nativeKWatchersF41) { nativeKWatchersF41[tv] }
                    if (incoming === ours || !incoming.javaClass.name.startsWith("$NATIVE_TOPVIEW_CLASS$")) return@hookBefore
                    param.result = null
                    AndroidLog.i(TAG, "strip F41 isolate: suppressed native watcher add in searchMode " +
                        "(fail-closed，只留搜索watcher)")
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F41 watcher guard failed: $t")
                }
            }
            AndroidLog.i(TAG, "strip F41 isolate: watcher guard armed (addTextChangedListener拦)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 watcher guard arm failed: $t")
            f41WatcherGuardHooked = false
        }
    }

    /** 3.5.3 T0 schedules translation; T commits translation. Layout and ordinary translation stay native. */
    private fun ensureTranslationNetGuardF41(anchor: View) {
        if (f41TransGuardHooked) return
        val mgr = translatingMgr(anchor) ?: return
        val source = mgr.javaClass.getDeclaredMethod("T0", CharSequence::class.java)
        val commit = mgr.javaClass.getDeclaredMethod("T", java.lang.Boolean.TYPE)
        for (method in listOf(source, commit)) {
            check(method.returnType == Void.TYPE)
            method.isAccessible = true
            method.hookBefore { param ->
                if (param.thisObject === searchManager && nativeKEditRefF41?.get()?.let(::ownsSearchBox) == true) {
                    param.result = null
                }
            }
        }
        f41TransGuardHooked = true
    }

    /** C46：直设hint搜索剪贴板（只改文案，不碰字号/肤色/padding/光标/行高）。 */
    private fun applySearchHintF41(box: EditText) {
        if (!ownsSearchBox(box)) return
        try {
            if (box.hint?.toString() == SEARCH_HINT_F41) return
            if (Looper.myLooper() == Looper.getMainLooper()) box.hint = SEARCH_HINT_F41
            else box.post { runCatching { if (ownsSearchBox(box) && box.hint?.toString() != SEARCH_HINT_F41) box.hint = SEARCH_HINT_F41 } }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 hint apply failed: $t")
        }
    }

    /** C46：hint稳定（布局监听+延时重申+setHint拦，原生蓝占位竞态压回；只改文案）。 */
    private fun ensureHintStableF41(box: EditText) {
        try {
            synchronized(hintLayoutListenersF41) {
                if (hintLayoutListenersF41[box] == null) {
                    val l = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        try {
                            if (nativeKEditRefF41?.get() !== box) return@OnLayoutChangeListener
                            if (box.hint?.toString() != SEARCH_HINT_F41) {
                                box.hint = SEARCH_HINT_F41
                                AndroidLog.i(TAG, "strip F41 hint: reasserted 搜索剪贴板 (layout, 原生蓝占位已压回)")
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    box.addOnLayoutChangeListener(l)
                    hintLayoutListenersF41[box] = l
                }
            }
            // 延时双补（原生setHint后到时序）：300/800ms各一次，只改文案。
            val token = submitSession.id
            box.postDelayed({ if (submitSession.id == token) applySearchHintF41(box) }, 300)
            box.postDelayed({ if (submitSession.id == token) applySearchHintF41(box) }, 800)
            ensureHintHookF41()
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 hint stable failed: $t")
        }
    }

    /** C46：全局一次拦TextView#setHint（仅我方原生框，非我方框一律放行；fail-closed）。 */
    private fun ensureHintHookF41() {
        if (f41HintHooked) return
        f41HintHooked = true
        try {
            val setHintCs = android.widget.TextView::class.java.declaredMethods.firstOrNull {
                it.name == "setHint" && it.parameterTypes.size == 1 && it.parameterTypes[0] == CharSequence::class.java
            } ?: run {
                f41HintHooked = false
                return
            }
            setHintCs.isAccessible = true
            setHintCs.hookBefore { param ->
                try {
                    val tv = param.thisObject as? EditText ?: return@hookBefore
                    if (nativeKEditRefF41?.get() !== tv) return@hookBefore
                    if (!translatorShellByUs) return@hookBefore
                    val incoming = param.args.firstOrNull()?.toString().orEmpty()
                    if (incoming != SEARCH_HINT_F41) {
                        param.args[0] = SEARCH_HINT_F41
                        AndroidLog.i(TAG, "strip F41 hint: native setHint intercepted [$incoming]->搜索剪贴板")
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F41 hint intercept failed: $t")
                }
            }
            // int型resId版：after补回（before无法把int换成string不进原方法会丢布局副作用，after重申最稳）。
            val setHintRes = android.widget.TextView::class.java.declaredMethods.firstOrNull {
                it.name == "setHint" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (setHintRes != null) {
                setHintRes.isAccessible = true
                setHintRes.hookAfter { param ->
                    try {
                        val tv = param.thisObject as? EditText ?: return@hookAfter
                        if (nativeKEditRefF41?.get() !== tv) return@hookAfter
                        if (!translatorShellByUs) return@hookAfter
                        if (tv.hint?.toString() != SEARCH_HINT_F41) {
                            tv.post { runCatching { applySearchHintF41(tv) } }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
            AndroidLog.i(TAG, "strip F41 hint: hook armed (setHint拦，只改文案)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F41 hint hook arm failed: $t")
            f41HintHooked = false
        }
    }

    /** C46：直点收起可观测（exit包装：原生点击保留+finally补collapse记账；取不到listener只diag）。 */
    private fun wireNativeExitF41(parts: NativeKPartsF41): Boolean {
        val exit = parts.exit ?: return false
        exit.setOnClickListener { if (ownsSearchBox(parts.edit)) collapseStripF41() }
        return true
    }

    /** C46：Q0(false)直收补记（全局一次）：直点原生收起走原生通路时补F41 collapsed记账，不重复Q0。 */
    private fun ensureCollapseHookF41(anchor: View) {
        if (f41CollapseHooked) return
        val mgr = translatingMgr(anchor) ?: return
        val q0 = mgr.javaClass.declaredMethods.single {
            it.name == "Q0" && it.parameterTypes.size == 6 && java.lang.reflect.Modifier.isStatic(it.modifiers)
        }
        q0.isAccessible = true
        q0.hookAfter { param ->
            if (param.args.getOrNull(0) !== searchManager || param.args.getOrNull(1) != false) return@hookAfter
            if (submitSession.phase != ClipboardSearchSubmitSession.Phase.EDITING) return@hookAfter
            val token = submitSession.id
            mainHandler.post {
                if (submitSession.at(token, ClipboardSearchSubmitSession.Phase.EDITING)) collapseStripF41()
            }
        }
        f41CollapseHooked = true
    }

    /** Explicit editor/key action only. Native composing confirmation starts at key.d.O(code=2). */
    private fun jumpBackToClipboardF41(source: String = "editor") {
        val box = nativeKEditRefF41?.get() ?: return
        if (!ownsSearchBox(box) || submitSession.phase != ClipboardSearchSubmitSession.Phase.EDITING) return
        // All explicit submit paths use the same native action handler, including native composing commit.
        runCatching { nativeActionDispatcher?.invoke(nativeActionListener, 2, null) }
            .onFailure { AndroidLog.e(TAG, "search action dispatch failed ($source): ${it.message}") }
    }

    /** F41：收起（复用原生收起）：Q0(false)走原生S()/J0/V0/U0/C0/s()还账，不自拆k。 */
    private fun collapseStripF41() {
        if (inCollapseF41) return
        inCollapseF41 = true
        try {
            val decor = overlayParentRef?.get()
                ?: (currentImeRoot() ?: (nativeKRefF41?.get()?.rootView as? ViewGroup))
            submitSession.cancel()
            overlayPending = false
            pendingKeyword = ""
            releaseNativeSearchState()
            if (decor != null) exitTranslatorShell(decor)
            overlayParentRef = null
            keywordListenerImpl?.invoke("")
        } finally { inCollapseF41 = false }
    }

    /**
     * 壳模式红钮：导航回键盘 → 判用户态 → drive(true)进壳置byUs →
     * 轮询等k挂载 → 摘k记账 → s0挂己条 → publish Y → N三连 → float u0。
     * 用户自己的翻译态（t0=true）直接 fail-closed，不抢不关。
     * 顺序禁乱；任一步失败 fail-closed（禁仿制兜底）。
     */
    private fun onSearchButtonClick(root: ViewGroup) {
        try {
            if (!installNativeSubmitBridge(root)) {
                AndroidLog.e(TAG, "search unavailable: native submit contract not verified for this version")
                return
            }
            AndroidLog.i(TAG, "search button CLICKED")
            val decor = (root.rootView as? ViewGroup) ?: root
            val cl = hostClassLoader
            val kbId = cl?.let { resolveKeyboardContainerId(it) }
            if (kbId == null) {
                AndroidLog.e(TAG, "no keyboard container id, navigation dropped")
                return
            }
            searchPrefill = WeTypeClipboardSearchFilter.currentKeywordValue()
            pendingKeyword = ""
            overlayPending = true
            if (!navigateBackToKeyboard()) {
                overlayPending = false
                return
            }
            AndroidLog.i(TAG, "search button: leaving S15 via N.O2, strip pending on keyboard")
            // F28无条基线（只读）：进壳前键盘常态窗高，从未量过（原候选140？），记baseline+mountCount。
            runCatching { logBaselineNoStripF28(decor, "preShell") }
            val mgr = translatingMgr(decor)
            if (mgr != null && isTranslating(mgr)) {
                AndroidLog.e(TAG, "translator active by user, strip dropped (no抢占)")
                overlayPending = false
                return
            }
            // 进壳：Q0(true)借高槽布局，成功才置byUs；失败 fail-closed。
            if (!driveTranslatorShell(decor, true)) {
                AndroidLog.e(TAG, "translator shell enter failed, strip dropped")
                overlayPending = false
                return
            }
            translatorShellByUs = true
            pollKeyboardThenMount(decor, kbId, 12)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "search button click failed: ${t.message}")
        }
    }

    /**
     * 壳模式轮询（F41翻译卡原生复用）：等键盘页切回 + 等k挂载（150ms×12）。N.O2 是异步切页，
     * 键盘容器在剪贴板页也存在——只判容器存在会挂到隐藏页上。门控：容器已布局 +
     * 剪贴板列表不在展示 + k已挂载；命中即直接复用原生k（只改文案，不摘k不自绘）。
     * F33-F40藏栏/少抬升增量已丢弃（skip-hidden/KEEP-hidden/bar-immobile/保栏强改/Q按mode藏栏基线等
     * 不再走，工具栏/键盘following翻译态原生，卡高用k.getCurrentHeight，J3仅防爆，验证门回翻译态原生）。
     * 超时 fail-closed（清pending + 退壳还账）。
     */
    private fun pollKeyboardThenMount(decor: ViewGroup, kbId: Int, left: Int) {
        try {
            val kb = decor.findViewById<View>(kbId)
            if (kb != null && kb.width > 0 && kb.height > 0 &&
                !isClipboardPageShowing(decor)
            ) {
                val k = findTranslatorTopView(decor)
                if (k != null) {
                    // F41：直接复用原生翻译卡k（不摘k，不挂自绘条；失败fail-closed退壳）。
                    if (!repurposeNativeKForSearchF41(decor, k)) {
                        AndroidLog.e(TAG, "strip F41 reuse failed, strip dropped (fail-closed)")
                        overlayPending = false
                        releaseNativeSearchState()
                        submitSession.cancel()
                        exitTranslatorShell(decor)
                        return
                    }
                    // F41 mount-once：原生k已由q $t经r0挂载，Y流+k高+N#J3原生写窗已撑高；
                    // 我方不再publish/N三连/float（防叠加撑窗），单次复用即停。
                    AndroidLog.i(TAG, "strip F41 mount-once: native k reused, publish/N/float following native " +
                        "(k.getCurrentHeight原生高，J3原生写窗，bar/键不动)")
                    return
                }
            }
            if (left <= 0) {
                AndroidLog.e(TAG, "keyboard page timeout, strip dropped")
                overlayPending = false
                exitTranslatorShell(decor)
                return
            }
            if (left % 4 == 0) {
                AndroidLog.i(TAG, "waiting keyboard page switch, left=$left")
            }
            Handler(Looper.getMainLooper()).postDelayed({
                pollKeyboardThenMount(decor, kbId, left - 1)
            }, 150)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "keyboard poll failed: ${t.message}")
            overlayPending = false
            runCatching { exitTranslatorShell(decor) }
        }
    }

    /**
     * S7：剪贴板列表仍可见 = 切页未完成（或 N.O2 没切过去），此时不挂条。
     * id 走宿主 s 类现取，判据只读可见性，不碰任何视图。
     * G6落点（只修落点判据，其余隔离/双行卡/收起/零仿制/圆角B/J3/栏键不动）：
     * 真落点须VISIBLE/isShown+宽高屏位有效（宽高>0+屏位y>0），键盘页（工具栏+QWERTY但列表隐/零尺寸）
     * 不再报可见；fail-closed，禁仿制兜底（禁GradientDrawable/硬编码色/系统图标/f0(32)）。
     */
    private fun isClipboardPageShowing(decor: ViewGroup): Boolean = nativeClipboardPage(decor).visibleResult

    /** 退翻译壳（仅我方进入时）：exit(false) + k记账清账 + 工具栏旧负位移全还 + 条位移全还 + F17夹层全还 + F18 LCA全还 + F19相交全还 + F20垫全还 + F21稳态全还 + F23定高全还 + F24 kids/N对照全还 + F25过期块全还 + F26残留[4]全还 + F27缝试探全还 + F28跳过对照全还 + F32栏位键盘位全还，还账恢复普通布局。 */
    private fun exitTranslatorShell(anchor: View) {
        try {
            runCatching { restoreToolbarShift() }
            runCatching { restoreAllStripShifts() }
            runCatching { restoreInterlayerF17() }
            runCatching { restoreLcaF18() }
            runCatching { restoreInterSpanF19() }
            runCatching { restoreConstraintPadF20() }
            runCatching { restoreBarSteadyF21() }
            runCatching { restoreFixedParentF23() }
            runCatching { restoreKidsSteadyF24() }
            runCatching { restoreNABSteadyF24() }
            runCatching { restoreStaleWhoF25() }
            runCatching { restoreStale4F26() }
            runCatching { restoreSeamF27() }
            runCatching { restoreSkipF28() }
            runCatching { restoreBarKeyboardF32() }
            runCatching { clearMountBaselineF40() }
            if (!translatorShellByUs) return
            translatorShellByUs = false
            removedTranslatorKRef = null
            removedTranslatorKParentRef = null
            removedTranslatorKIndex = -1
            driveTranslatorShell(anchor, false)
            runCatching { restoreToolbarShift() }
            runCatching { restoreAllStripShifts() }
            runCatching { restoreInterlayerF17() }
            runCatching { restoreLcaF18() }
            runCatching { restoreInterSpanF19() }
            runCatching { restoreConstraintPadF20() }
            runCatching { restoreBarSteadyF21() }
            runCatching { restoreFixedParentF23() }
            runCatching { restoreKidsSteadyF24() }
            runCatching { restoreNABSteadyF24() }
            runCatching { restoreStaleWhoF25() }
            runCatching { restoreStale4F26() }
            runCatching { restoreSeamF27() }
            runCatching { restoreSkipF28() }
            runCatching { restoreBarKeyboardF32() }
            // F40收起还账：mount基线清账（次挂重记；只清记账不碰视图）。
            runCatching { clearMountBaselineF40() }
            // F28拆条后无条稳态基线（只读）：退壳后1000ms量windowBar0，应≈140？记baseline。
            runCatching {
                val decorAfter = (anchor as? ViewGroup)
                    ?: (anchor.rootView as? ViewGroup)
                if (decorAfter != null) scheduleBaselineAfterRemoveF28(decorAfter)
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "translator shell exit failed: ${t.message}")
        }
    }

    /** 经 model.N 回主键盘（单例按形找，路由 O2(false)）。 */
    private fun navigateBackToKeyboard(): Boolean {
        try {
            val cl = hostClassLoader ?: run {
                AndroidLog.e(TAG, "host loader missing, cannot navigate back")
                return false
            }
            val nClass = Class.forName("com.tencent.wetype.plugin.hld.model.N", false, cl)
            var singleton: Any? = null
            var singletonName = "?"
            for (f in nClass.declaredFields) {
                try {
                    if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    if (f.type != nClass) continue
                    f.isAccessible = true
                    val v = f.get(null)
                    if (v != null) {
                        singleton = v
                        singletonName = f.name
                        break
                    }
                } catch (_: Throwable) {
                    continue
                }
            }
            val target = singleton ?: run {
                AndroidLog.e(TAG, "N singleton (static N field) missing, cannot navigate back")
                return false
            }
            // 3.5.3=N#O2(boolean)，3.5.4 更名=N#Q2(boolean)（O2 变为 (boolean, W) 需非空 source）。
            val m = nClass.declaredMethods.firstOrNull {
                (it.name == "O2" || it.name == "Q2") && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                    it.returnType == Void.TYPE
            } ?: run {
                AndroidLog.e(TAG, "N.O2/Q2(boolean) missing, cannot navigate back")
                return false
            }
            m.isAccessible = true
            m.invoke(target, false)
            AndroidLog.i(TAG, "N.${m.name}(false) invoked on $singletonName for keyboard back")
            return true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "navigate back to keyboard failed: $t")
            return false
        }
    }

    private fun resolveKeyboardContainerId(cl: ClassLoader): Int? {
        return try {
            val sCls = Class.forName("com.tencent.wetype.plugin.hld.s", false, cl)
            // F40：exact优先，混淆漂移时fuzzy回退（Int字段名含keyboard+container/rl/layout，现取不写死id值）。
            val exact = runCatching {
                val f = sCls.getDeclaredField("keyboard_container_rl")
                f.isAccessible = true
                f.getInt(null).takeIf { it != 0 }
            }.getOrNull()
            if (exact != null) {
                AndroidLog.i(TAG, "keyboard container id resolved: $exact")
                return exact
            }
            for (f in sCls.declaredFields) {
                try {
                    if (f.type != Int::class.javaPrimitiveType && f.type != Integer::class.java) continue
                    val nm = f.name.lowercase()
                    if (!nm.contains("keyboard")) continue
                    if (!(nm.contains("container") || nm.contains("rl") || nm.contains("layout"))) continue
                    f.isAccessible = true
                    val id = runCatching { f.getInt(null) }.getOrNull() ?: continue
                    if (id == 0) continue
                    AndroidLog.i(TAG, "keyboard container id fuzzy resolved: $id field=${f.name}")
                    return id
                } catch (_: Throwable) {
                    continue
                }
            }
            AndroidLog.e(TAG, "resolve keyboard container id failed: exact+fuzzy miss")
            null
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "resolve keyboard container id failed: $t")
            null
        }
    }

    /**
     * 键盘页顶部长一行（原生翻译同形）：条根（ImeRadiusConstraintLayout，边距
     * top e0(36)/bottom e0(20)/左右 g0(20)）经
     * `ImeCandidateView.s0-shape(row, 0, FrameLayout(MATCH,WRAP))`（实机多为 `r0`）
     * 插 candidate_top_view 空插槽 pos0（标题若在则为标题下一位，仍在工具栏之上）；
     * 挂载后调浮窗 `f.u0(…,55)` 重排，底部面板向上撑（键盘容器及祖先
     * 高度一律不动，候选列表下移后仍可见；候选出现时宿主原生覆盖工具栏区）。
     * s0-shape 不可用则 fail-closed（记日志不挂条），禁任何碰键盘高度的退路。
     * 已存在只复用保焦点。赋值式，延迟重试自校正。
     */
    private fun mountStripOnKeyboard(decor: ViewGroup, kbContainerId: Int) {
        // F41丢弃自绘挂载：直接取原生翻译卡k壳/Q0视图复用（只改文案，不自绘）。
        // 本函数（自绘条build+s0挂载）已不再调用，保留备查；误调即fail-closed退壳，不挂自绘条。
        AndroidLog.e(TAG, "strip F41: SKIP self-draw mount (discarded, use native k reuse)")
        if (true) {
            // 若原生k在，交由F41复用；否则退壳fail-closed（禁仿制兜底）。
            val k = runCatching { findTranslatorTopView(decor) }.getOrNull()
            if (k != null) {
                if (!repurposeNativeKForSearchF41(decor, k)) {
                    overlayPending = false
                    exitTranslatorShell(decor)
                }
            } else {
                overlayPending = false
                runCatching { exitTranslatorShell(decor) }
            }
            return
        }
        try {
            // F28挂载计数（只记诊断）：复挂叠加即>1，500/1200ms复挂查源头。
            runCatching { noteStripMountF28(decor) }
            if (!overlayPending) {
                // 非 pending 下误调（如延迟任务撞上已跳回）：已有条则不动。
                if (decor.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null) return
            }
            val kb = decor.findViewById<View>(kbContainerId)
            if (kb == null) {
                AndroidLog.e(TAG, "keyboard container not found, strip stays pending")
                return
            }
            val existing = decor.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER)
            if (existing != null && existing.parent != null &&
                existing.visibility == View.VISIBLE
            ) {
                // S7：已在只补对齐（首挂时行若未布局会对齐顺延，靠延迟 tick 补上；
                // 已对齐则 dy≈0 无操作，幂等）。
                runCatching { alignToolbarRowWithLogo(decor) }
                return
            }
            if (existing != null) {
                removeStripCard(existing)
            }
            // 全原生：任一步原生解析失败即整条 fail-closed，禁一切仿制兜底。
            // 壳模式：失败必须退壳还账（exit(false)），禁留壳。
            val row = buildKeyboardStrip(decor) ?: run {
                AndroidLog.e(TAG, "strip build failed: native-only, dropped")
                overlayPending = false
                exitTranslatorShell(decor)
                return
            }
            // 唯一路径：原生 candidate_top_view 插槽（s0 同形）。失败即 fail-closed。
            if (!mountStripInCandidateContainer(decor, row)) {
                AndroidLog.e(TAG, "strip mount failed: candidate s0 unavailable, dropped")
                overlayPending = false
                exitTranslatorShell(decor)
                return
            }
            overlayParentRef = java.lang.ref.WeakReference(decor)
            overlayPending = false
            // F39保栏：挂条即保栏（翻译壳Q0(true)后自动藏栏则显式恢复；原生取fail-closed；挂条全序q.t r0→A2/e0/N2→J3不动）。
            runCatching { ensureToolbarVisibleF39(decor, "mount") }
            row.post {
                try {
                    val box = row.findViewWithTag<View>(TAG_SEARCH_BOX) as? EditText
                    val ok = box?.requestFocus() ?: false
                    AndroidLog.i(TAG, "strip mounted; box focus: requested=$ok " +
                        "hasFocus=${box?.hasFocus()} class=${box?.javaClass?.name}")
                    // 翻译同形：内容增高→浮窗重排，底部面板向上撑（键盘高度不动）。
                    row.postDelayed({
                        // 真高按 k.getCurrentHeight() 同义重测：定宽 + 高 UNSPECIFIED，
                        // 否则发布的是被槽位定高压扁的值，流无变化，u0 永不触发。
                        // 高度值全部现量现算，禁写死 84/140/167；WRAP 自扩自缩。
                        val trueH = try {
                            val w = row.width.takeIf { it > 0 } ?: row.measuredWidth
                            row.measure(
                                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                            )
                            row.measuredHeight.takeIf { it > 0 } ?: row.height
                        } catch (t: Throwable) {
                            AndroidLog.e(TAG, "strip remeasure failed: ${t.message}")
                            row.height.takeIf { it > 0 } ?: row.measuredHeight
                        }
                        val box = row.findViewWithTag<View>(TAG_SEARCH_BOX) as? EditText
                        AndroidLog.i(TAG, "strip dims: root=${row.width}x${row.height} " +
                            "trueH=$trueH box=${box?.width}x${box?.height} " +
                            "minH=${box?.minimumHeight} lh=${box?.lineHeight}")
                        AndroidLog.i(TAG, "strip tree: " + describeStripTree(row))
                        // 压扁自检： laid-out 高远小于真高即 slot 定高未包住（C2：84 vs 167），
                        // 溢出绘制会盖住工具栏/候选；链已改 WRAP，此处只记日志供 C3 验收。
                        if (row.height > 0 && trueH > row.height + 8) {
                            AndroidLog.e(TAG, "strip squashed: laid=${row.height} trueH=$trueH " +
                                "slot still fixed, check chain wrap")
                        }
                        // F20：先收ConstraintLayout padB72→0记账复测inter，再收总高publish h=真高+25现算替代原trueH。
                        // 顶4~7/底19~21/槽192±3/工具栏键盘钉死；壳/s0/圆角B/DEL/commit/logo全不动。
                        runCatching { trimConstraintPadF20(row, decor) }
                        runCatching { logWindowToBarF20(row, decor, "mountF20-before") }
                        val publishH = runCatching { publishHeightForF20(row, trueH, STRIP_M_BOTTOM_PX) }.getOrNull() ?: trueH
                        publishStripHeight(row, publishH, "mountF20")
                        refreshCandidateLayout(row, "mountF20")
                        refreshFloatWindow(row, "mountF20")
                        runCatching { logWindowToBarF20(row, decor, "mountF20-afterPublish") }
                        alignToolbarRowWithLogo(decor)
                        // F39保栏：N三连/float后翻译链若藏栏则显式恢复，再验缝/窗；J3唯一钳点不动。
                        runCatching { ensureToolbarVisibleF39(decor, "mountF20-after") }
                        runCatching { verifyToolbarZeroShiftF34(decor, "mountF20-after") }
                        // 条/工具栏/候选上下排布复检：条底 <= 工具栏顶为 pass，重叠即 warn。
                        verifyStripStacking(decor, row, "mounted")
                        // F40挂载基线：mount初值对才记（gap19~21+viewN>=5，bar不动基准stripBottom+20≈iconTop）。
                        runCatching { noteMountBaselineF40(decor, row) }
                        // F30条态快照（只读，同口径，关键字strip-snap+drift；顶底槽壳等全不动）。
                        runCatching { logStripSnapF30(decor, row, "mount") }
                        // F40窗推移：J3钳后显栏推对对象（publish+N/float），藏栏不推，300ms后验192±4。
                        runCatching {
                            row.postDelayed({
                                runCatching { pushWindowAfterJ3F40(row, decor, "mount") }
                            }, POST_STABLE_DELAY_MS)
                        }
                        // F21：inter归属搬稳态——条挂后5秒布局沉降后再dump栏容器+slot父链（steady-state只读定案）
                        // 再按定案单步修（栏垫≈88收零/首孩空占位GONE，单步验delta>=1）。顶底槽栏键钉死，
                        // 壳/s0/圆角B/DEL/commit/logo不动。
                        runCatching { scheduleBarSteadyStateF21(decor, row) }
                    }, 120)
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip post failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "mount strip failed: $t")
            overlayPending = false
            runCatching { exitTranslatorShell(decor) }
        }
    }

    /**
     * 原生翻译同形挂载：条根插候选条顶部容器。翻译 `q` 切翻译态即
     * `ImeCandidateView.s0(k, 0, FrameLayout(MATCH,WRAP))`（`q.java:1656`，
     * 实机混淆名多为 `r0`，按形找不写死名），candidate_top_view 空插槽
     * pos0 即条在上、工具栏在下；候选出现时宿主原生覆盖工具栏区，三者上下
     * 不重叠（标题若在则条为标题下一位，仍在工具栏之上）。容器 WRAP 自扩→
     * 输入法窗向上撑。方法名按形找（View,int,FrameLayout.LP），混淆漂移也不怕。
     * 成功 true，失败 false（上层 fail-closed）。
     */
    private fun mountStripInCandidateContainer(decor: ViewGroup, row: View): Boolean {
        try {
            val cl = hostClassLoader ?: return false
            val candCls = runCatching {
                Class.forName(
                    "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView", false, cl
                )
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "candidate view class not found")
                return false
            }
            var candView: ViewGroup? = null
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            var hops = 0
            while (q.isNotEmpty() && hops < 400) {
                val v = q.removeFirst()
                hops++
                if (candCls.isInstance(v)) {
                    candView = v as? ViewGroup
                    break
                }
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            val cand = candView ?: run {
                AndroidLog.e(TAG, "candidate view not found in decor")
                return false
            }
            val m = cand.javaClass.declaredMethods.firstOrNull { mm ->
                val pt = mm.parameterTypes
                pt.size == 3 && View::class.java.isAssignableFrom(pt[0]) &&
                    pt[1] == Int::class.javaPrimitiveType &&
                    android.widget.FrameLayout.LayoutParams::class.java.isAssignableFrom(pt[2])
            } ?: run {
                AndroidLog.e(TAG, "candidate s0-shape not found on ${cand.javaClass.name}")
                return false
            }
            m.isAccessible = true
            // F12（接F11顶1.5dp/底10px→底20px）：缝基准=图标顶iconTop（IconLine.top=cy-现算半高，
            // 89x89仅参考，不写死44；找不到cluster则fail-closed diagnostically，不回退容器顶）。
            // 条底贴图标顶19~21px（gap取STRIP_M_BOTTOM_PX=20px，现算px）；顶1.5dp现算（dpToPx）；
            // mBottom=gap-empty（empty=iconTop-barTop空行高，可为负以下叠空行贴图标顶）；
            // 槽WRAP自收敛（槽总高=条真高+新顶+新底，不多留）；下移量=旧mTop(F3顶)-新mTop全量
            // 转条下移（F3顶现算仅记日志）+图标下移empty经mBottom实现；左右仍原生g0(20)。
            // 侧边距/图标缺失整条fail-closed（上层退壳还账）；slot/cand WRAP链、publish(trueH)、
            // N三连/float顺序一律不动；延迟复挂早退不二次拉高。
            val mSide = nativeScaledPx("g0", NATIVE_ROOT_M_SIDE_G0) ?: run {
                AndroidLog.e(TAG, "strip mount: root side margin failed")
                return false
            }
            val mTop = dpToPx(decor.resources, STRIP_M_TOP_DP)
            val iconGap = STRIP_M_BOTTOM_PX
            // F5新基准现算（只读几何，不碰视图）：图标顶+容器顶+空行高。
            // F6解耦（C9实锤：挂载瞬间工具栏暂隐是常态，bar/icon缺失绝不dropped整条）：
            // 找不到bar/icon cluster只记诊断+用容器顶(cand容器顶)临时兜缝（仅缝计算用，
            // 不记baseline=iconTop），s0挂载照常；挂后post对线等图标行回归再按iconTop重算。
            // 顶1.5dp/底贴图标顶20px目标；壳/s0/WRAP+顶垫收敛/publish/N三连/float/圆角B等全不动。
            val logoForMount = runCatching { resolveLogoView(decor) }.getOrNull()
            val iconLineOrNull = runCatching { scanSquareIconLine(decor, logoForMount) }.getOrNull()
            val barForMountOrNull = runCatching { findStripToolbarBar(decor) }.getOrNull()
            val blocMount = IntArray(2)
            if (barForMountOrNull != null) {
                runCatching { barForMountOrNull.getLocationOnScreen(blocMount) }
            }
            val barTopMountOrNull = blocMount[1].takeIf { barForMountOrNull != null && it > 0 }
            val iconTopValid = iconLineOrNull != null && iconLineOrNull.top > 0
            // 容器顶临时基准（只读几何，不碰视图）：cand容器屏上顶，挂载瞬间恒可读，
            // 仅缝计算临时用，不作baseline=iconTop。
            val candLocTmp = IntArray(2)
            runCatching { cand.getLocationOnScreen(candLocTmp) }
            val containerTopTmp = candLocTmp[1]
            // F7放宽：不再要求单行容器kids 5~9；有iconTop即按iconTop缝（bar缺席时barTop取行顶=iconTop，
            // empty=0，mBottom=gap），确保条挂后postAlign能命中。仅icon缺席才走临时缝。
            val useTempSeam = !iconTopValid
            val iconLine: IconLine?
            val barTopMount: Int
            val emptyMount: Int
            val mBottom: Int
            // F6临时缝兜底值（仅缝计算用，不记baseline=iconTop；post对线命中即纠正）。
            val containerTopForLog: Int
            if (!useTempSeam) {
                iconLine = iconLineOrNull
                // F7：barTop取行顶——bar缺席/未布局时回退iconTop（行顶），empty=0，mBottom=gap=20px。
                barTopMount = barTopMountOrNull ?: iconLine!!.top.toInt()
                emptyMount = (iconLine!!.top - barTopMount).toInt().coerceAtLeast(0)
                mBottom = iconGap - emptyMount
                containerTopForLog = containerTopTmp
            } else {
                val iconTopDiag = iconLineOrNull?.top?.toInt() ?: -1
                val iconNDiag = iconLineOrNull?.n ?: -1
                val barTopDiag = if (barForMountOrNull == null) -1 else blocMount[1]
                val barKidsDiag = (barForMountOrNull as? ViewGroup)?.childCount ?: -1
                AndroidLog.e(TAG, "strip mount: icon/bar unavailable diag only s0 proceed " +
                    "iconTop=$iconTopDiag n=$iconNDiag barTop=$barTopDiag kids=$barKidsDiag " +
                    "containerTop=$containerTopTmp (temp seam, not iconTop)")
                // 临时缝：有图标顶则用容器顶兜empty，无图标顶则empty=0（mBottom=gap=20px），
                // 仅缝计算用，post对线即按iconTop重算。
                iconLine = null
                barTopMount = barTopMountOrNull ?: containerTopTmp
                containerTopForLog = containerTopTmp
                emptyMount = if (iconTopValid && containerTopTmp > 0) {
                    (iconLineOrNull!!.top - containerTopTmp).toInt().coerceAtLeast(0)
                } else {
                    0
                }
                mBottom = iconGap - emptyMount
            }
            // F3基线现算仅供下移量日志（不参与边距，失败不拦条）：F3顶=nativeTop+(nativeBottom-2dp)。
            val f3TopForLog: Int? = runCatching {
                val nt = nativeScaledPx("e0", NATIVE_ROOT_M_TOP_E0)
                val nb = nativeScaledPx("e0", NATIVE_ROOT_M_BOTTOM_E0)
                if (nt != null && nb != null) {
                    val f3Bottom = dpToPx(decor.resources, 2f)
                    nt + (nb - f3Bottom).coerceAtLeast(0)
                } else null
            }.getOrNull()
            val downShift = if (f3TopForLog != null) (f3TopForLog - mTop).coerceAtLeast(0) else -1
            val lp = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = mSide
                rightMargin = mSide
                topMargin = mTop
                bottomMargin = mBottom
            }
            m.invoke(cand, row, 0, lp)
            if (!useTempSeam && iconLine != null) {
                AndroidLog.i(TAG, "strip mounted via candidate ${m.name} " +
                    "on ${cand.javaClass.simpleName} margins=[$mSide,$mTop,$mBottom] downShift=$downShift " +
                    "f3Top=$f3TopForLog iconTop=${iconLine.top.toInt()} cy=${iconLine.centerY.toInt()} " +
                    "halfH=${iconLine.halfH.toInt()} n=${iconLine.n} barTop=$barTopMount empty=$emptyMount " +
                    "gapToIcon=$iconGap baseline=iconTop " +
                    "nativeRef=[g0(${NATIVE_ROOT_M_SIDE_G0}),e0(${NATIVE_ROOT_M_TOP_E0}," +
                    "${NATIVE_ROOT_M_BOTTOM_E0})]")
            } else {
                // F6临时缝挂载（diag only，不记baseline=iconTop；post对线补baseline=iconTop）。
                AndroidLog.i(TAG, "strip mounted via candidate ${m.name} " +
                    "on ${cand.javaClass.simpleName} margins=[$mSide,$mTop,$mBottom] downShift=$downShift " +
                    "f3Top=$f3TopForLog containerTop=$containerTopForLog barTop=$barTopMount empty=$emptyMount " +
                    "gapToIcon=$iconGap baseline=containerTop(temp, diag only) " +
                    "nativeRef=[g0(${NATIVE_ROOT_M_SIDE_G0}),e0(${NATIVE_ROOT_M_TOP_E0}," +
                    "${NATIVE_ROOT_M_BOTTOM_E0})]")
            }
            // 壳模式撑窗：k已摘记账，候选链 WRAP 包内容
            //（slot+cand 现量现记，禁写死 84/140/167；键盘容器及祖先不动），
            // F12顶收敛：slot/cand多余paddingTop/topMargin现量现记→0（只收槽/窗多余垫，
            // 不动条真高167/box143，工具栏/键盘钉死不碰），随后走高度流/N 三连长窗。
            // 退出时按基线逐个还原。
            expandStripChainForWrap(row, cand)
            verifyStripStacking(decor, row, "mount")
            // F8 stale修（C11：挂载时命中亦可能是stale旧位，条把工具栏顶下去后新位才稳定）：
            // 无论临时缝/回退/直接命中，一律条挂后300ms重扫新位现算，最多12轮，命中即贴；
            // 顶1.5dp/底20px/壳/s0/WRAP+顶垫收敛/publish/N三连/float/圆角B全不动。
            row.postDelayed({
                runCatching { scheduleStripPostAlign(decor, row, iconGap, POST_ALIGN_RETRY_MAX) }
            }, POST_STABLE_DELAY_MS)
            return true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "candidate container mount failed: $t")
            return false
        }
    }

    /**
     * F15窗灰顶测量（只读几何，不碰视图）：窗灰顶=cand（ImeCandidateView，类名含Candidate首个祖先，
     * 找不到则回退slot.parent）屏上顶，条白顶=row屏上顶，topGap=rowTop-windowTop。
     * 返Triple(windowTop,rowTop,topGap)，任一未布局（<=0）返null，调用方记诊断不拦底缝闭环。
     * 目标4~7px（≈1.5dp，STRIP_TOP_GAP_MIN/MAX_PX），与底缝19~21px双PASS才停轮。
     */
    private fun stripWindowTopGap(row: View): Triple<Int, Int, Int>? {
        return try {
            val slot = row.parent as? ViewGroup ?: return null
            var cand: ViewGroup? = null
            var cur: android.view.ViewParent? = row.parent
            var guard = 0
            while (cur is ViewGroup && guard < 4) {
                if (cur.javaClass.name.contains("Candidate")) {
                    cand = cur
                    break
                }
                cur = cur.parent
                guard++
            }
            if (cand == null) {
                cand = slot.parent as? ViewGroup ?: return null
            }
            val rloc = IntArray(2)
            runCatching { row.getLocationOnScreen(rloc) }
            val cloc = IntArray(2)
            runCatching { cand.getLocationOnScreen(cloc) }
            val rowTop = rloc[1]
            val windowTop = cloc[1]
            if (rowTop <= 0 || windowTop <= 0) return null
            Triple(windowTop, rowTop, rowTop - windowTop)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * F15（接F14条根+复测+封顶300，在此基础上改，不reset）：顶压不下去因条下移132px贴底但槽/窗高没跟，
     * 顶留132px灰（C18视觉顶135px）。恢复槽跟随但带F14同款safeguards：slot topMargin负值跟随量=条累计下移
     * totalDy（每轮复测窗灰顶真动了才累加，不动停轮，封顶300px）；N三连/float只在顶底双PASS后重算一次，
     * 不在每轮重算。闭环目标窗灰顶→条白顶4~7px + 条底→图标顶19~21px双PASS停轮（最多12轮300ms）。
     * 工具栏/键盘钉死零位移；壳/s0/圆角B/DEL/commit/logo/退壳（槽垫/条位移全还）全不动。
     * F13互抵空转（trans+slot互抵净0+每轮N/float重置致1981）已修：每轮只requestLayout不做N/float，
     * 下轮复测stripBottom delta>=1px + 窗灰顶delta>=1px（top未PASS时）才算落实，否则停轮不再累加。
     */
    private fun scheduleStripPostAlign(decor: ViewGroup, row: View, iconGap: Int, left: Int) {
        // F41丢弃：post对线（顶1.5dp/底20px/槽192/夹层/位移）不再执行，following翻译态原生。
        AndroidLog.i(TAG, "strip F41 postAlign: SKIP (translation native wins, diag only)")
        if (true) return
        try {
            if (row.parent == null) {
                runCatching { restoreStripShift(row) }
                runCatching { restoreStripRowMargin(row) }
                synchronized(stripPendingVerify) { stripPendingVerify.remove(row) }
                runCatching { synchronized(stripSlotPendingF16) { stripSlotPendingF16.remove(row) } }
                return
            }
            // F16条清零回布局位+槽跟随清零（不再位移贴底，底20px由槽高保证；只撤销我方增量，不碰宿主/工具栏/键盘）。
            runCatching { resetStripShiftToLayoutF16(row) }
            runCatching { resetSlotFollowToTrimmedF16(row) }
            // F22禁止复用旧值：本轮现扫（视图簇cy±28px n>=5 + Q_top带限cy<Q_top-100px）
            // + 像素亮斑交叉（白圆灰底min>225连通域，差>20px记mismatch用像素值diagnostically，
            // 二选一以像素为准）；扫不到记diag不dropped（keep seam重试/保留）。
            val logo = runCatching { resolveLogoView(decor) }.getOrNull()
            val iconLine = runCatching { resolveIconLineF22(decor, logo) }.getOrNull()
            if (iconLine == null || iconLine.top <= 0) {
                if (left <= 0) {
                    AndroidLog.e(TAG, "strip post align F22: fresh rescan still missing diag only (not dropped) " +
                        "iconTop=${iconLine?.top?.toInt() ?: -1} n=${iconLine?.n ?: -1} " +
                        "(need n>=5 cyTol=28px qGuard=100px brightMin=225, keep seam)")
                    return
                }
                if (left % 4 == 0) {
                    AndroidLog.i(TAG, "strip post align F22: waiting fresh icon row+pixel, left=$left " +
                        "(rescan each round, no reuse, diag only)")
                }
                row.postDelayed({
                    scheduleStripPostAlign(decor, row, iconGap, left - 1)
                }, POST_STABLE_DELAY_MS)
                return
            }
            val rloc = IntArray(2)
            runCatching { row.getLocationOnScreen(rloc) }
            if (rloc[1] <= 0 || row.height <= 0) {
                if (left <= 0) {
                    AndroidLog.e(TAG, "strip post align F12: strip not laid out, keep seam")
                    return
                }
                row.postDelayed({
                    scheduleStripPostAlign(decor, row, iconGap, left - 1)
                }, POST_STABLE_DELAY_MS)
                return
            }
            val stripBottom = rloc[1] + row.height
            // F15顶底双测（只读几何，不碰视图）：窗灰顶→条白顶topGap + 条底→图标顶gapToIcon。
            // 窗灰顶=cand顶现算，条白顶=row顶现算，目标4~7px；底目标19~21px，双PASS才停轮。
            val windowMeas = runCatching { stripWindowTopGap(row) }.getOrNull()
            val windowTop = windowMeas?.first ?: -1
            val rowTopCur = windowMeas?.second ?: rloc[1]
            val topGap = windowMeas?.third
            val topPass = topGap != null && topGap in STRIP_TOP_GAP_MIN_PX..STRIP_TOP_GAP_MAX_PX
            val gapToIcon = (iconLine.top - stripBottom).toInt()
            val bottomPass = gapToIcon in (iconGap - 1)..(iconGap + 1)
            // F16双PASS优先（量法不动：F15阈值4~7/19~21原样）：窗灰顶→条白顶4~7px + 条底→图标顶19~21px，双PASS才N三连/float重算一次后停轮。
            // F13互抵空转已修：每轮不做N/float，只requestLayout；双PASS后一次，不在每轮重算重置位移。F16条零位移，槽显式高已收敛。
            if (bottomPass && topPass) {
                val totalDy = stripTotalDyOf(row)
                val slotHOk = (row.parent as? ViewGroup)?.height?.takeIf { it > 0 }
                    ?: (((row.parent as? ViewGroup)?.measuredHeight ?: -1))
                val targetOk = runCatching { slotTargetHeightForF16(row, iconGap) }.getOrNull() ?: -1
                AndroidLog.i(TAG, "strip post align F16 DOUBLE-PASS: stripBottom=$stripBottom " +
                    "iconTop=${iconLine.top.toInt()} cy=${iconLine.centerY.toInt()} " +
                    "halfH=${iconLine.halfH.toInt()} n=${iconLine.n} gapToIcon=$gapToIcon(19~21) " +
                    "windowTop=$windowTop rowTop=$rowTopCur topGap=$topGap(4~7) " +
                    "slotH=$slotHOk target=$targetOk(192±3) totalDy=$totalDy(zeroBar) baseline=iconTop+windowTop")
                runCatching { verifyStripStacking(decor, row, "postAlignF16-double-pass") }
                // F20：mount已publish h=真高+25（≈192），此处N三连/float即跟上重算，只读验窗→栏192±4，不碰顶底槽栏键。
                runCatching { logWindowToBarF20(row, decor, "postAlignF20-double-pass") }
                runCatching { refreshCandidateLayout(row, "postAlignF16-pass") }
                runCatching { refreshFloatWindow(row, "postAlignF16-pass") }
                return
            }
            // F15复测：上一轮位移是否真落实（F14同款：C17 stripBottom恒1213不动却盲累到1981）。
            // pending=上轮(beforeBottom,dyPrev,beforeWindowTop)；本轮先验deltaBottom=cur-before，<1即空转停轮不再累加；
            // 窗灰顶同理：top未PASS时deltaWindow<1即槽跟随未带动窗高，停轮不再累加，封顶300px防1981。
            val pending = synchronized(stripPendingVerify) { stripPendingVerify.remove(row) }
            if (pending != null) {
                val beforeBottom = pending.first
                val dyPrev = pending.second
                val beforeWindowTop = pending.third
                // F15 slotOnly轮（bottom已PASS只跟槽，dyPrev=0）：条屏位不动属预期，不走bottom空转fallback；
                // 双PASS已在上返回，到此即顶仍FAIL。窗顶不动/不可测则停轮（不再盲跟），动则继续闭环。
                // 连续两轮slotOnly（上轮已同步过槽仍顶FAIL）亦停轮防空转。
                if (dyPrev < 0.5f) {
                    if (!topPass) {
                        if (windowTop <= 0 || beforeWindowTop <= 0) {
                            AndroidLog.e(TAG, "strip post align F15 WINDOW-STALL stop(slotOnly, unmeasurable): " +
                                "windowTop=$windowTop<- $beforeWindowTop rowTop=$rowTopCur topGap=$topGap need=4~7 " +
                                "bottomGap=$gapToIcon stripBottom=$stripBottom total=${stripTotalDyOf(row)} " +
                                "cap=$STRIP_SHIFT_CAP_PX (no window measure, no accumulate)")
                            runCatching { verifyStripStacking(decor, row, "postAlignF15-window-stall") }
                            return
                        }
                        val deltaWindowOnly = windowTop - beforeWindowTop
                        if (deltaWindowOnly < 1) {
                            AndroidLog.e(TAG, "strip post align F15 WINDOW-STALL stop(slotOnly): " +
                                "windowTop=$windowTop<- $beforeWindowTop deltaWindow=$deltaWindowOnly " +
                                "rowTop=$rowTopCur topGap=$topGap need=4~7 bottomGap=$gapToIcon " +
                                "stripBottom=$stripBottom total=${stripTotalDyOf(row)} " +
                                "cap=$STRIP_SHIFT_CAP_PX (slot sync no window move, no accumulate)")
                            runCatching { verifyStripStacking(decor, row, "postAlignF15-window-stall") }
                            return
                        }
                    }
                    AndroidLog.i(TAG, "strip post align F15 slotOnly verified: " +
                        "windowTop=$windowTop<- $beforeWindowTop rowTop=$rowTopCur topGap=$topGap " +
                        "gapToIcon=$gapToIcon stripBottom=$stripBottom")
                    // slotOnly轮跳过bottom delta空转判定（dyPrev=0无位移预期）：下文if需加dyPrev>=0.5门禁，
                    // slotOnly直接进verified/窗顶复测，不走trans->margin fallback（dyPrev=0 fallback无意义）。
                    // 连续slotOnly空转由shift段门禁兜底（上轮slotOnly仍顶FAIL则停轮）。
                    if (bottomPass && !topPass) {
                        // 上轮slotOnly仍bottomPASS+topFAIL：槽同步未带动窗高，停轮不再盲跟（防空转）。
                        AndroidLog.e(TAG, "strip post align F15 WINDOW-STALL stop(consecutive slotOnly): " +
                            "windowTop=$windowTop<- $beforeWindowTop rowTop=$rowTopCur topGap=$topGap need=4~7 " +
                            "bottomGap=$gapToIcon stripBottom=$stripBottom total=${stripTotalDyOf(row)} " +
                            "cap=$STRIP_SHIFT_CAP_PX (one slot sync done, top still FAIL, no accumulate)")
                        runCatching { verifyStripStacking(decor, row, "postAlignF15-window-stall") }
                        return
                    }
                    // bottom已漂移（slot同步后窗动带跑条底）：跳过bottom stall，恢复bottom闭环（下文shift段算dyNeed）。
                }
                val delta = stripBottom - beforeBottom
                val curTrans = runCatching { row.translationY }.getOrDefault(0f)
                val transTotal = stripDyOf(row)
                val marginTotal = synchronized(stripRowMarginApplied) {
                    stripRowMarginApplied[row] ?: 0
                }
                // F15：slotOnly轮（dyPrev<0.5）跳过bottom空转fallback（无位移预期），直接进verified。
                if (dyPrev >= 0.5f && delta < 1) {
                    val marginTried = marginTotal != 0
                    if (!marginTried) {
                        // 二选一：trans不动，改走row自身topMargin增量（同dyPrev），以复测为准。
                        // 先撤销上轮空转trans增量，保持total诚实不虚增到1981；槽跟随同步回新total（见下）。
                        runCatching { revertLastStripTrans(row, dyPrev) }
                        val afterRevert = stripDyOf(row)
                        val lpCheck = row.layoutParams as? ViewGroup.MarginLayoutParams
                        if (lpCheck == null) {
                            AndroidLog.e(TAG, "strip post align F15 STALL stop: trans no-move " +
                                "before=$beforeBottom cur=$stripBottom delta=$delta dyPrev=$dyPrev " +
                                "transTotal=$transTotal curTrans=$curTrans no MarginLP " +
                                "windowTop=$windowTop<- $beforeWindowTop topGap=$topGap topPass=$topPass " +
                                "gapToIcon=$gapToIcon total=$afterRevert cap=$STRIP_SHIFT_CAP_PX (no 1981 accumulate)")
                            runCatching { verifyStripStacking(decor, row, "postAlignF15-stall") }
                            return
                        }
                        val marginNew = applyStripRowMarginDown(row, dyPrev)
                        // F15槽跟随同步：slot topMargin=-totalDy（新total=marginNew，trans已 revert），只requestLayout不做N/float。
                        val newTotalFb = stripTotalDyOf(row)
                        runCatching { followSlotTopForStripShift(row, newTotalFb) }
                        runCatching { row.requestLayout() }
                        synchronized(stripPendingVerify) {
                            stripPendingVerify[row] = Triple(stripBottom, dyPrev, windowTop)
                        }
                        AndroidLog.e(TAG, "strip post align F15 STALL trans->margin fallback: " +
                            "before=$beforeBottom cur=$stripBottom delta=$delta dyPrev=$dyPrev " +
                            "revertedTrans=$afterRevert marginTotal=$marginNew newTotal=$newTotalFb " +
                            "windowTop=$windowTop<- $beforeWindowTop topGap=$topGap topPass=$topPass " +
                            "row=${row.javaClass.simpleName} left=$left (verify next round)")
                        runCatching { verifyStripStacking(decor, row, "postAlignF15-fallback") }
                        row.postDelayed({
                            scheduleStripPostAlign(decor, row, iconGap, left - 1)
                        }, POST_STABLE_DELAY_MS)
                        return
                    } else {
                        AndroidLog.e(TAG, "strip post align F15 STALL stop: margin no-move " +
                            "before=$beforeBottom cur=$stripBottom delta=$delta dyPrev=$dyPrev " +
                            "transTotal=$transTotal marginTotal=$marginTotal curTrans=$curTrans " +
                            "windowTop=$windowTop<- $beforeWindowTop topGap=$topGap topPass=$topPass " +
                            "gapToIcon=$gapToIcon total=${transTotal + marginTotal} cap=$STRIP_SHIFT_CAP_PX " +
                            "(no 1981 accumulate)")
                        runCatching { verifyStripStacking(decor, row, "postAlignF15-stall") }
                        return
                    }
                } else {
                    AndroidLog.i(TAG, "strip post align F15 verified bottom: before=$beforeBottom " +
                        "cur=$stripBottom delta=$delta dyPrev=$dyPrev " +
                        "transTotal=$transTotal marginTotal=$marginTotal baseline=iconTop(new)")
                    // F15窗灰顶复测：top未PASS时窗顶必须真动（deltaWindow>=1px）才算槽跟随落实，不动停轮不再累加。
                    if (!topPass && windowTop > 0 && beforeWindowTop > 0) {
                        val deltaWindow = windowTop - beforeWindowTop
                        if (deltaWindow < 1) {
                            AndroidLog.e(TAG, "strip post align F15 WINDOW-STALL stop: windowTop no-move " +
                                "windowTop=$windowTop<- $beforeWindowTop deltaWindow=$deltaWindow " +
                                "rowTop=$rowTopCur topGap=$topGap need=4~7 bottomGap=$gapToIcon " +
                                "stripBottom=$stripBottom deltaBottom=$delta total=${transTotal + marginTotal} " +
                                "cap=$STRIP_SHIFT_CAP_PX (slot follow no window move, no accumulate)")
                            runCatching { verifyStripStacking(decor, row, "postAlignF15-window-stall") }
                            return
                        } else {
                            AndroidLog.i(TAG, "strip post align F15 verified window: " +
                                "windowTop=$windowTop<- $beforeWindowTop deltaWindow=$deltaWindow " +
                                "rowTop=$rowTopCur topGap=$topGap topPass=$topPass")
                        }
                    }
                }
            }
            // F15单PASS不停车：底PASS但顶FAIL（或顶PASS但底FAIL）继续闭环，双PASS已在上返回。
            // F12旧单底PASS语义退役；OVERLAP仍只下不上（only-down）。
            if (gapToIcon < 0) {
                AndroidLog.e(TAG, "strip post align F15: OVERLAP gap=$gapToIcon " +
                    "stripBottom=$stripBottom iconTop=${iconLine.top.toInt()} no down-shift " +
                    "windowTop=$windowTop rowTop=$rowTopCur topGap=$topGap (only-down rule)")
                runCatching { verifyStripStacking(decor, row, "postAlignF15") }
                return
            }
            // F16收敛（C19槽324条167空132，trans+follow互抵STALL；顶6 PASS窗对底152 FAIL槽高）：
            // 条已清零（本轮首reset，不再位移贴底，totalDy恒0无1981）；底20px由槽高保证。
            // 量槽实高vs目标=条真高+顶1.5dp+底20px（现算≈192），差即多余，逐源诊断后显式槽高收敛，以192±3为准。
            // 双PASS量法不动（上已判）；工具栏/键盘钉死；壳/s0/圆角B/DEL/commit/logo/退壳全不动；每轮只requestLayout，N/float只双PASS后一次。
            if (left <= 0) {
                val slotHDiag = runCatching {
                    ((row.parent as? ViewGroup)?.height?.takeIf { it > 0 })
                        ?: ((row.parent as? ViewGroup)?.measuredHeight ?: -1)
                }.getOrDefault(-1)
                AndroidLog.e(TAG, "strip post align F16: rounds exhausted, keep seam " +
                    "gapToIcon=$gapToIcon bottomPass=$bottomPass stripBottom=$stripBottom " +
                    "iconTop=${iconLine.top.toInt()} n=${iconLine.n} " +
                    "windowTop=$windowTop rowTop=$rowTopCur topGap=$topGap topPass=$topPass " +
                    "slotH=$slotHDiag totalDy=${stripTotalDyOf(row)} (no shift, slot converge only)")
                runCatching { verifyStripStacking(decor, row, "postAlignF16") }
                return
            }
            // F19全树dump（接F18抓错对象，不再猜三选一）：decor全树纵向span与[slotBottom,barTop]相交全列，
            // 按交集排序打日志，一次看清88px是谁；只读不碰，行mBottom/empty只记不碰。
            val interCurF17 = runCatching { dumpInterSpanF19(row, decor) }.getOrNull()
            // F16复测：上轮(beforeSlotH,beforeGap,beforeWindowTop)，本轮验slotH收敛+gap收缩，不动停轮防空转。
            val pendingSlot = synchronized(stripSlotPendingF16) { stripSlotPendingF16.remove(row) }
            if (pendingSlot != null) {
                val beforeSlotH = pendingSlot.first
                val beforeGap = pendingSlot.second
                val beforeWin = pendingSlot.third
                val slotCur = (row.parent as? ViewGroup)?.height?.takeIf { it > 0 }
                    ?: (((row.parent as? ViewGroup)?.measuredHeight ?: -1))
                val deltaSlot = if (slotCur > 0 && beforeSlotH > 0) slotCur - beforeSlotH else 999
                val deltaGap = gapToIcon - beforeGap
                // slotOnly预期：条不动（stripBottom不动属预期，不验bottom delta）；验槽动或缝收。
                if (slotCur > 0 && beforeSlotH > 0 &&
                    kotlin.math.abs(deltaSlot) < 1 && kotlin.math.abs(deltaGap) < 1
                ) {
                    // F17：SLOT-STALL时夹层可能是真因（C20槽192 PASS但inter132），不直接停，先验inter。
                    val beforeInterF17 =
                        synchronized(stripInterPendingF17) { stripInterPendingF17.remove(row) }
                    if (interCurF17 != null && interCurF17 > STRIP_INTER_TOL_PX) {
                        if (beforeInterF17 != null &&
                            kotlin.math.abs(interCurF17 - beforeInterF17) < 1
                        ) {
                            val trimOnce =
                                runCatching { trimInterSpanF19(row, decor) }.getOrDefault(false)
                            if (!trimOnce) {
                                AndroidLog.e(TAG, "strip post align F19 INTER-STALL stop: " +
                                    "slotH $beforeSlotH->$slotCur delta=$deltaSlot " +
                                    "gap $beforeGap->$gapToIcon deltaGap=$deltaGap " +
                                    "inter $beforeInterF17->$interCurF17 " +
                                    "windowTop=$windowTop<- $beforeWin rowTop=$rowTopCur " +
                                    "topGap=$topGap topPass=$topPass stripBottom=$stripBottom " +
                                    "(slot+inter no move, no accumulate)")
                                runCatching { verifyStripStacking(decor, row, "postAlignF17-inter-stall") }
                                return
                            }
                            AndroidLog.i(TAG, "strip post align F19 STALL-trim continue: " +
                                "slotH $beforeSlotH->$slotCur gap $beforeGap->$gapToIcon " +
                                "inter $beforeInterF17->$interCurF17 trimApplied=true (verify next round)")
                            synchronized(stripInterPendingF17) {
                                stripInterPendingF17[row] = interCurF17!!
                            }
                        } else {
                            AndroidLog.i(TAG, "strip post align F17 verified inter: " +
                                "slotH $beforeSlotH->$slotCur gap $beforeGap->$gapToIcon " +
                                "inter $beforeInterF17->$interCurF17 " +
                                "windowTop=$windowTop<- $beforeWin rowTop=$rowTopCur topGap=$topGap")
                            if (interCurF17 != null) {
                                synchronized(stripInterPendingF17) {
                                    stripInterPendingF17[row] = interCurF17!!
                                }
                            }
                        }
                    } else if (interCurF17 != null) {
                        AndroidLog.e(TAG, "strip post align F16 SLOT-STALL stop: slotH $beforeSlotH->$slotCur delta=$deltaSlot " +
                            "gap $beforeGap->$gapToIcon deltaGap=$deltaGap windowTop=$windowTop<- $beforeWin " +
                            "rowTop=$rowTopCur topGap=$topGap topPass=$topPass stripBottom=$stripBottom " +
                            "inter=$interCurF17(0) (slot converge no move, no accumulate)")
                        runCatching { verifyStripStacking(decor, row, "postAlignF16-slot-stall") }
                        return
                    } else {
                        val trimOnce =
                            runCatching { trimInterSpanF19(row, decor) }.getOrDefault(false)
                        if (!trimOnce) {
                            AndroidLog.e(TAG, "strip post align F16 SLOT-STALL stop: slotH $beforeSlotH->$slotCur delta=$deltaSlot " +
                                "gap $beforeGap->$gapToIcon deltaGap=$deltaGap windowTop=$windowTop<- $beforeWin " +
                                "rowTop=$rowTopCur topGap=$topGap topPass=$topPass stripBottom=$stripBottom " +
                                "inter=unmeasurable (slot converge no move, no accumulate)")
                            runCatching { verifyStripStacking(decor, row, "postAlignF16-slot-stall") }
                            return
                        }
                        AndroidLog.i(TAG, "strip post align F19 STALL-trim continue: inter unmeasurable " +
                            "but trimApplied=true (verify next round)")
                    }
                } else {
                    AndroidLog.i(TAG, "strip post align F16 verified slot: slotH $beforeSlotH->$slotCur delta=$deltaSlot " +
                        "gap $beforeGap->$gapToIcon deltaGap=$deltaGap windowTop=$windowTop<- $beforeWin " +
                        "rowTop=$rowTopCur topGap=$topGap")
                }
            }
            // F16：不再算dyNeed/封顶/打条+跟随（totalDy恒0）；只做槽显式高收敛，底20px由槽高保证。
            // F19（接F18，不reset）：C22实锤F18 trim applied=true后applied=false inter88→88不动——LCA抓错对象。
            // 逐个验：按交集从大到小每轮只动一个（GONEable空视图GONE/垫边收零），下轮复测delta>=1才留，否则还账试下一个；
            // 全不动记INTER-STALL。禁盲累加。F18/F17猜三选一停用（函数保留仅供退壳还账），新施加只走F19；
            // 顶4~7/底19~21/槽192±3/工具栏键盘钉死；壳/s0/圆角B/DEL/commit/logo不动。
            val slotBefore = (row.parent as? ViewGroup)?.height?.takeIf { it > 0 }
                ?: (((row.parent as? ViewGroup)?.measuredHeight ?: -1))
            val applied = runCatching { convergeSlotHeightF16(row, decor, iconGap) }.getOrDefault(false)
            val trimAppliedF19 = if (!bottomPass && interCurF17 != null && interCurF17 > STRIP_INTER_TOL_PX) {
                runCatching { trimInterSpanF19(row, decor) }.getOrDefault(false)
            } else false
            synchronized(stripSlotPendingF16) { stripSlotPendingF16[row] = Triple(slotBefore, gapToIcon, windowTop) }
            if (interCurF17 != null) {
                synchronized(stripInterPendingF17) { stripInterPendingF17[row] = interCurF17!! }
            }
            AndroidLog.i(TAG, "strip post align F16+F19 converge: applied=$applied trimF19=$trimAppliedF19 " +
                "beforeSlotH=$slotBefore beforeBottom=$stripBottom iconTop=${iconLine.top.toInt()} " +
                "cy=${iconLine.centerY.toInt()} n=${iconLine.n} gapToIcon=$gapToIcon bottomPass=$bottomPass " +
                "inter=$interCurF17(0) windowTop=$windowTop rowTop=$rowTopCur topGap=$topGap topPass=$topPass " +
                "left=$left mode=slotExplicitZeroBar+spanSingleVerify totalDy=${stripTotalDyOf(row)} baseline=iconTop+windowTop")
            runCatching { verifyStripStacking(decor, row, "postAlignF16F19") }
            row.postDelayed({
                scheduleStripPostAlign(decor, row, iconGap, left - 1)
            }, POST_STABLE_DELAY_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip post align failed: ${t.message}")
        }
    }

    /**
     * F14条根下移（只打条根自身translationY，不碰slot/cand/工具栏/键盘；F13槽随动已停用见下）。
     * 空转根因：F13每轮translationY(+dy)+slot topMargin(-totalDy)互抵净位移≈0，兼每轮N三连/float
     * 重置位移，致stripBottom恒1213不动却盲累到1981。F14只打row自身，N三连/float只PASS后一次，
     * 每轮位移由上轮复测delta>=1px才算落实（见scheduleStripPostAlign），不动停轮；total封顶300px。
     * 返累计值；失败返当前累计（fail-closed，不碰工具栏）。
     */
    private fun applyStripShiftDown(row: View, dy: Float): Float {
        return try {
            if (kotlin.math.abs(dy) < 0.5f) return stripDyOf(row)
            val marginCur = synchronized(stripRowMarginApplied) {
                stripRowMarginApplied[row] ?: 0
            }
            val cur = stripDyOf(row)
            if (cur + marginCur >= STRIP_SHIFT_CAP_PX) {
                AndroidLog.e(TAG, "strip shift F14 capped: cur=$cur margin=$marginCur " +
                    "cap=$STRIP_SHIFT_CAP_PX drop dy=$dy")
                return cur
            }
            var useDy = dy
            if (cur + marginCur + useDy > STRIP_SHIFT_CAP_PX) {
                useDy = (STRIP_SHIFT_CAP_PX - cur - marginCur).coerceAtLeast(0f)
                if (useDy < 0.5f) {
                    AndroidLog.e(TAG, "strip shift F14 capped: cur=$cur margin=$marginCur " +
                        "cap=$STRIP_SHIFT_CAP_PX drop dy=$dy")
                    return cur
                }
            }
            synchronized(stripTransOrig) {
                if (!stripTransOrig.containsKey(row)) {
                    stripTransOrig[row] = row.translationY
                }
            }
            val applied = stripDyOf(row)
            val next = applied + useDy
            val orig = synchronized(stripTransOrig) {
                stripTransOrig[row] ?: (row.translationY - applied)
            }
            row.translationY = orig + next
            synchronized(stripDyApplied) { stripDyApplied[row] = next }
            AndroidLog.i(TAG, "strip shift F14 trans row-only: dy=$useDy total=$next " +
                "row=${row.javaClass.simpleName} cap=$STRIP_SHIFT_CAP_PX")
            // F14：不碰slot topMargin，不做N三连/float（PASS后一次）。
            next
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip shift apply failed: $t")
            stripDyOf(row)
        }
    }

    /**
     * F14条根自身topMargin增量（二选一之二：trans复测不动时改走本路，以复测移动为准）。
     * 只动row自身LP topMargin（orig+累计，记账退条时还），不碰slot/cand/工具栏/键盘/N/float。
     * 返累计margin值；失败返当前累计。
     */
    private fun applyStripRowMarginDown(row: View, dy: Float): Int {
        return try {
            if (kotlin.math.abs(dy) < 0.5f) {
                return synchronized(stripRowMarginApplied) { stripRowMarginApplied[row] ?: 0 }
            }
            val lp = row.layoutParams as? ViewGroup.MarginLayoutParams ?: run {
                AndroidLog.e(TAG, "strip shift F14 margin: no MarginLP " +
                    "row=${row.javaClass.simpleName}")
                return synchronized(stripRowMarginApplied) { stripRowMarginApplied[row] ?: 0 }
            }
            synchronized(stripRowMarginOrig) {
                if (!stripRowMarginOrig.containsKey(row)) {
                    stripRowMarginOrig[row] = lp.topMargin
                }
            }
            val applied = synchronized(stripRowMarginApplied) {
                stripRowMarginApplied[row] ?: 0
            }
            var inc = dy.roundToInt().coerceAtLeast(0)
            if (inc <= 0) return applied
            val transCur = stripDyOf(row)
            if (transCur + applied + inc > STRIP_SHIFT_CAP_PX) {
                val allow = STRIP_SHIFT_CAP_PX - transCur.toInt() - applied
                if (allow <= 0) {
                    AndroidLog.e(TAG, "strip shift F14 margin capped: trans=$transCur " +
                        "margin=$applied cap=$STRIP_SHIFT_CAP_PX drop dy=$dy")
                    return applied
                }
                inc = allow
            }
            val orig = synchronized(stripRowMarginOrig) {
                stripRowMarginOrig[row] ?: lp.topMargin
            }
            val next = applied + inc
            lp.topMargin = orig + next
            row.layoutParams = lp
            synchronized(stripRowMarginApplied) { stripRowMarginApplied[row] = next }
            AndroidLog.i(TAG, "strip shift F14 margin row-only: inc=$inc total=$next " +
                "row=${row.javaClass.simpleName} cap=$STRIP_SHIFT_CAP_PX")
            next
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip margin apply failed: $t")
            synchronized(stripRowMarginApplied) { stripRowMarginApplied[row] ?: 0 }
        }
    }

    /** F14：撤销上轮空转trans增量（复测delta<1时保持total诚实，不虚增到1981）。 */
    private fun revertLastStripTrans(row: View, dyPrev: Float) {
        try {
            val applied = synchronized(stripDyApplied) { stripDyApplied[row] ?: return }
            if (applied < 0.5f) return
            val next = (applied - dyPrev).coerceAtLeast(0f)
            val orig = synchronized(stripTransOrig) { stripTransOrig[row] }
            if (orig != null) {
                row.translationY = orig + next
            } else {
                row.translationY = row.translationY - dyPrev
            }
            synchronized(stripDyApplied) {
                if (next < 0.5f) {
                    stripDyApplied.remove(row)
                    synchronized(stripTransOrig) { stripTransOrig.remove(row) }
                } else {
                    stripDyApplied[row] = next
                }
            }
            runCatching { row.requestLayout() }
            AndroidLog.i(TAG, "strip shift F14 revert stalled trans: dyPrev=$dyPrev " +
                "applied $applied->$next")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip revert stalled trans failed: $t")
        }
    }

    /** F14：条根自身topMargin还账（单视图幂等；退壳/拆条时与translationY同还）。 */
    private fun restoreStripRowMargin(row: View) {
        try {
            val applied = synchronized(stripRowMarginApplied) {
                stripRowMarginApplied.remove(row)
            } ?: 0
            val orig = synchronized(stripRowMarginOrig) { stripRowMarginOrig.remove(row) }
            if (orig != null) {
                val lp = row.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null && lp.topMargin != orig) {
                    lp.topMargin = orig
                    row.layoutParams = lp
                    runCatching { row.requestLayout() }
                    AndroidLog.i(TAG, "strip margin F14 restored: $applied->$orig")
                } else if (applied != 0) {
                    AndroidLog.i(TAG, "strip margin F14 cleared margin=$applied orig=$orig")
                }
            } else if (applied != 0) {
                runCatching {
                    val lp = row.layoutParams as? ViewGroup.MarginLayoutParams
                    if (lp != null) {
                        lp.topMargin = lp.topMargin - applied
                        row.layoutParams = lp
                        runCatching { row.requestLayout() }
                    }
                }
                AndroidLog.i(TAG, "strip margin F14 restored (no orig): margin=$applied")
            }
            synchronized(stripPendingVerify) { stripPendingVerify.remove(row) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip margin restore failed: $t")
        }
    }

    /** F14：trans+margin合计位移（封顶判据；复测/日志同源）。 */
    private fun stripTotalDyOf(row: View): Float {
        return try {
            stripDyOf(row) + (synchronized(stripRowMarginApplied) {
                stripRowMarginApplied[row] ?: 0
            }.toFloat())
        } catch (_: Throwable) {
            0f
        }
    }

    /**
     * F15恢复槽跟随但带F14同款safeguards：slot topMargin负值跟随量=条累计下移totalDy（负值，现量现算，
     * 复用stripTopMarginOrig记账退条时还账，封顶300px内，超顶调用方已 clamp，此处再钳位防越界）。
     * 每轮复测窗灰顶真动了才累加（见scheduleStripPostAlign窗口复测），不动停轮不再累加防1981；
     * 本函数只requestLayout slot+cand（WRAP自跟随高度收掉dy），N三连/float只在顶底双PASS后重算一次，
     * 不在每轮重算（F13每轮N/float重置位移致空转根因）。不碰工具栏/键盘/壳/s0/圆角B/DEL/commit/logo。
     */
    private fun followSlotTopForStripShift(row: View, totalDy: Float) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            val slot = row.parent as? ViewGroup ?: return
            // fail-closed：slot必须含我方条（防误收工具栏/键盘容器）。
            if (slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) !== row &&
                slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null
            ) {
                return
            }
            val lp = slot.layoutParams as? ViewGroup.MarginLayoutParams ?: run {
                AndroidLog.e(TAG, "strip follow F15: slot no MarginLP ${slot.javaClass.simpleName}")
                return
            }
            // F15封顶同款：跟随量=-totalDy钳位[-300,0]，totalDy调用方已封顶300，此处双保险不再累到1981。
            val targetTop = (-totalDy).roundToInt().coerceAtMost(0).coerceAtLeast(-STRIP_SHIFT_CAP_PX)
            synchronized(stripTopMarginOrig) {
                if (!stripTopMarginOrig.containsKey(slot)) {
                    stripTopMarginOrig[slot] = lp.topMargin
                }
            }
            if (lp.topMargin != targetTop) {
                val before = lp.topMargin
                lp.topMargin = targetTop
                slot.layoutParams = lp
                AndroidLog.i(TAG, "strip follow F15: slot=${slot.javaClass.simpleName} " +
                    "topMargin $before->$targetTop totalDy=${totalDy.toInt()} cap=$STRIP_SHIFT_CAP_PX " +
                    "window->strip=4~7px strip->icon=19~21px (no per-round N/float)")
            }
            runCatching { slot.requestLayout() }
            // cand WRAP自跟随：只requestLayout不改margin（防双倍收敛），窗高跟随由双PASS后N三连+float一次落实。
            val cand = slot.parent as? ViewGroup
            if (cand != null) runCatching { cand.requestLayout() }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip follow F15 failed: $t")
        }
    }

    /** F16槽收敛 pending：row -> (上轮beforeSlotH, 上轮beforeGap, 上轮beforeWindowTop)，下轮验slotH收敛+gap收缩，不动停轮。 */
    private val stripSlotPendingF16: MutableMap<View, Triple<Int, Int, Int>> =
        Collections.synchronizedMap(WeakHashMap<View, Triple<Int, Int, Int>>())

    /** F17夹层记账（只收槽外→工具栏顶之间，退壳全还；不动条真高/行mBottom/工具栏内边距empty/壳/s0/圆角B/DEL/commit/logo）。 */
    private val stripBottomPadOrigF17: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val stripInterBottomMarginOrigF17: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val stripInterTopMarginOrigF17: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val stripSiblingVisOrigF17: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** F17夹层复测：row -> 上轮beforeInterGap（槽底→工具栏顶），下轮验inter收缩，不动停轮防空转。 */
    private val stripInterPendingF17: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())

    // F18（接F17夹层已合入未提交，在此基础上改，不reset）：C21定案132=88+44——LCA FrameLayout
    // padB=123 + 栏内empty44；F17三选一（空兄弟/candPadB/barMT）全零抓错对象。现状槽192 PASS、
    // 顶5 PASS、工具栏零位移PASS、底152 FAIL。修：条挂后记账收LCA padB→0（只收123中超出部分，
    // 留栏内empty44不动，目标槽底→栏顶0±1、图标顶对条底20px），requestLayout+复测；退壳全还padB。
    // LCA须WRAP跟随收缩；若LCA是MATCH定高改不动，则改收其直接子bottomMargin等量，二选一以
    // 槽底→栏顶0±1为准。顶4~7/底19~21/槽192±3/工具栏键盘零位移；壳/s0/圆角B/DEL/commit/logo/退壳全不动。
    /** F18 LCA记账（只收LCA自身padB→0 + WRAP跟随/直接子MB fallback，退壳全还；不动栏内empty44/条/工具栏/键盘）。 */
    private val stripLcaPadOrigF18: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val stripLcaHeightOrigF18: MutableMap<ViewGroup, Int> =
        Collections.synchronizedMap(WeakHashMap<ViewGroup, Int>())
    private val stripLcaChildMarginOrigF18: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())

    /** F16条真高现量（与mount 120ms重测同义：定宽+高UNSPECIFIED，否则槽定高压扁值，禁写死84/140/167）。 */
    private fun stripTrueHeightOfF16(row: View): Int {
        return try {
            val w = row.width.takeIf { it > 0 } ?: row.measuredWidth
            if (w <= 0) return -1
            row.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            row.measuredHeight.takeIf { it > 0 } ?: row.height.takeIf { it > 0 } ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    /** F16槽目标高=条真高+顶1.5dp现算+底20px现算（≈192，现算不写死192，192±3验收）。 */
    private fun slotTargetHeightForF16(row: View, iconGap: Int): Int? {
        return try {
            val trueH = stripTrueHeightOfF16(row)
            if (trueH <= 0) return null
            val mTopPx = dpToPx(row.resources, STRIP_M_TOP_DP)
            trueH + mTopPx + iconGap
        } catch (_: Throwable) {
            null
        }
    }

    /** F16条清零回布局位（top margin 1.5dp）：只撤销我方trans/margin增量，不碰宿主值；slot跟随另还。 */
    private fun resetStripShiftToLayoutF16(row: View) {
        try {
            val total = stripTotalDyOf(row)
            if (total >= 0.5f) {
                runCatching { restoreStripShift(row) }
                AndroidLog.i(TAG, "strip F16 zero bar: totalDy=$total -> 0 " +
                    "row=${row.javaClass.simpleName} (back to layout mTop 1.5dp, no shift贴底)")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F16 zero bar failed: $t")
        }
    }

    /** F16槽跟随清零回trimmed（topMargin=0）：只还我方负跟随，不碰trim orig记账（退壳全还用）。 */
    private fun resetSlotFollowToTrimmedF16(row: View) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            val slot = row.parent as? ViewGroup ?: return
            if (slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null) return
            val lp = slot.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (lp.topMargin != 0) {
                val before = lp.topMargin
                lp.topMargin = 0
                slot.layoutParams = lp
                runCatching { slot.requestLayout() }
                AndroidLog.i(TAG, "strip F16 zero follow: slot=${slot.javaClass.simpleName} " +
                    "topMargin $before->0 (trimmed, no follow互抵)")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F16 zero follow failed: $t")
        }
    }

    /**
     * F16槽收敛：量槽实高vs目标=条真高+顶1.5dp+底20px（现算≈192），差即多余约132，逐源诊断，
     * 显式槽高=target收多余，以192±3为准（STRIP_SLOT_TOL_PX）；WRAP/垫/cand140/publish+N只诊不断链。
     * 只收多余（excess>tol才设显式），槽矮不扩（only-shrink，fail-closed）；只requestLayout，
     * N三连/float只双PASS后一次（调用方），不在每轮重算；工具栏/键盘/壳/s0/圆角B/DEL/commit/logo不动。
     * 槽高记账复用candHeightOrig（expand已记orig，退壳全还），此处不覆orig。
     * 返true=本轮施加显式高（下轮验slotH+gap），false=已收敛/矮槽/未布局（调用方记pending验STALL）。
     */
    private fun convergeSlotHeightF16(row: View, decor: ViewGroup, iconGap: Int): Boolean {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return false
            val slot = row.parent as? ViewGroup ?: run {
                AndroidLog.e(TAG, "strip F16 converge: no slot parent")
                return false
            }
            if (slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null) {
                AndroidLog.e(TAG, "strip F16 converge: slot mismatch guard")
                return false
            }
            val trueH = stripTrueHeightOfF16(row)
            if (trueH <= 0) {
                AndroidLog.e(TAG, "strip F16 converge: trueH unmeasurable w=${row.width} mw=${row.measuredWidth}")
                return false
            }
            val mTopPx = dpToPx(row.resources, STRIP_M_TOP_DP)
            val target = trueH + mTopPx + iconGap
            val slotH = slot.height.takeIf { it > 0 } ?: slot.measuredHeight.takeIf { it > 0 } ?: run {
                AndroidLog.e(TAG, "strip F16 converge: slotH unmeasurable h=${slot.height} mh=${slot.measuredHeight}")
                return false
            }
            val excess = slotH - target
            // 逐源只读诊断（不碰除槽高外）：slot垫/cand140/publish+N/WRAP。
            val slotPadTop = slot.paddingTop
            val slotPadBottom = slot.paddingBottom
            val slotLpH = slot.layoutParams?.height
            val slotLpStr = when (slotLpH) {
                ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
                ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                else -> "$slotLpH"
            }
            val slotTopMargin = (slot.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: -999
            val slotBottomMargin = (slot.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: -999
            val cand = slot.parent as? ViewGroup
            val candLpH = cand?.layoutParams?.height
            val candLpStr = when (candLpH) {
                ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
                ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                null -> "null"
                else -> "$candLpH"
            }
            val candPadTop = cand?.paddingTop ?: -999
            val slotOrig = synchronized(candHeightOrig) { candHeightOrig[slot] }
            val candOrig = if (cand != null) synchronized(candHeightOrig) { candHeightOrig[cand] } else null
            val rowLp = row.layoutParams as? ViewGroup.MarginLayoutParams
            val rowTopM = rowLp?.topMargin ?: -999
            val rowBottomM = rowLp?.bottomMargin ?: -999
            val rowTrans = runCatching { row.translationY }.getOrDefault(Float.NaN)
            val totalDy = stripTotalDyOf(row)
            AndroidLog.i(TAG, "strip F16 measure: slotH=$slotH target=$target(trueH=$trueH+mTop=$mTopPx+bot=$iconGap) " +
                "excess=$excess tol=$STRIP_SLOT_TOL_PX slotPad=[$slotPadTop,$slotPadBottom] slotLP=$slotLpStr " +
                "slotM=[$slotTopMargin,$slotBottomMargin] candLP=$candLpStr candPadTop=$candPadTop " +
                "origSlot=$slotOrig origCand=$candOrig(cand140残留看orig) rowM=[$rowTopM,$rowBottomM] " +
                "rowTrans=$rowTrans totalDy=$totalDy publish(trueH=$trueH)+N三连每轮不重算只双PASS后一次 " +
                "WRAP链只诊不断")
            if (kotlin.math.abs(excess) <= STRIP_SLOT_TOL_PX) {
                AndroidLog.i(TAG, "strip F16 converged: slotH=$slotH target=$target±$STRIP_SLOT_TOL_PX (192±3)")
                return false
            }
            if (excess < 0) {
                AndroidLog.e(TAG, "strip F16 short slot: slotH=$slotH<target=$target excess=$excess no expand (only-shrink)")
                return false
            }
            // 显式槽高=target（现算），记账复用candHeightOrig（已记不覆，未记则记当前WRAP供退壳全还）。
            synchronized(candHeightOrig) {
                if (!candHeightOrig.containsKey(slot) && slot.layoutParams != null) {
                    candHeightOrig[slot] = slotLpH ?: ViewGroup.LayoutParams.WRAP_CONTENT
                    AndroidLog.i(TAG, "strip F16 orig record: slot origH=${candHeightOrig[slot]} (for退壳全还)")
                }
            }
            val lp = slot.layoutParams ?: run {
                AndroidLog.e(TAG, "strip F16 converge: slot LP null")
                return false
            }
            val before = lp.height
            lp.height = target
            slot.layoutParams = lp
            AndroidLog.i(TAG, "strip F16 apply explicit: slot=${slot.javaClass.simpleName} lpH $before->$target " +
                "excess=${excess}收掉多余 槽实高->192±3 底20px由槽高保证 条零位移")
            runCatching { slot.requestLayout() }
            if (cand != null) runCatching { cand.requestLayout() }
            runCatching { row.requestLayout() }
            return true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F16 converge failed: $t")
            return false
        }
    }

    /**
     * F17夹层链dump（只读几何，不碰视图）：槽底(slot屏底)→工具栏顶(bar屏顶)之间链。
     * 逐项现量：slot/行/cand/bar/LCA类名/高/visibility/margin/padding/屏位，
     * LCA子间各兄弟视图类名/高/visibility/margin/padding/屏位/子数。
     * 返interGap=barTop-slotBottom（C20现量132，目标0±1；缝=20+inter故152=132+20）。
     * 任一未布局返null（调用方记诊断不拦闭环）。行mBottom/工具栏内边距empty只记不碰。
     */
    private fun dumpInterlayerChainF17(row: View, decor: ViewGroup): Int? {
        return try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return null
            val slot = row.parent as? ViewGroup ?: run {
                AndroidLog.e(TAG, "strip F17 dump: no slot parent")
                return null
            }
            if (slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null) {
                AndroidLog.e(TAG, "strip F17 dump: slot mismatch guard")
                return null
            }
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
            if (bar == null) {
                AndroidLog.e(TAG, "strip F17 dump: bar missing (toolbar row not found)")
                return null
            }
            val sloc = IntArray(2)
            runCatching { slot.getLocationOnScreen(sloc) }
            val bloc = IntArray(2)
            runCatching { bar.getLocationOnScreen(bloc) }
            val slotH = slot.height.takeIf { it > 0 } ?: slot.measuredHeight
            val barH = bar.height.takeIf { it > 0 } ?: bar.measuredHeight
            if (sloc[1] <= 0 || bloc[1] <= 0 || slotH <= 0) {
                AndroidLog.e(TAG, "strip F17 dump: unlaid slotTop=${sloc[1]} slotH=$slotH " +
                    "barTop=${bloc[1]} barH=$barH")
                return null
            }
            val slotBottom = sloc[1] + slotH
            val barTop = bloc[1]
            val inter = barTop - slotBottom
            val rloc = IntArray(2)
            runCatching { row.getLocationOnScreen(rloc) }
            val rowLp = row.layoutParams as? ViewGroup.MarginLayoutParams
            val slotLp = slot.layoutParams as? ViewGroup.MarginLayoutParams
            val cand = slot.parent as? ViewGroup
            val candLp = cand?.layoutParams as? ViewGroup.MarginLayoutParams
            val barLp = bar.layoutParams as? ViewGroup.MarginLayoutParams
            val logoTmp = runCatching { resolveLogoView(decor) }.getOrNull()
            val iconLine = runCatching { scanSquareIconLine(decor, logoTmp) }.getOrNull()
            val iconTop = iconLine?.top?.toInt() ?: -1
            val empty = if (iconTop > 0 && barTop > 0) iconTop - barTop else -999
            val rowBottomM = rowLp?.bottomMargin ?: -999
            AndroidLog.i(TAG, "strip F17 dump: slotBottom=$slotBottom(slotTop=${sloc[1]}+h=$slotH " +
                "${slot.javaClass.name} vis=${slot.visibility} padB=${slot.paddingBottom} " +
                "lpH=${slot.layoutParams?.height} mB=${slotLp?.bottomMargin}) " +
                "barTop=$barTop(${bar.javaClass.name} vis=${bar.visibility} h=$barH " +
                "padT=${bar.paddingTop} mT=${barLp?.topMargin}) inter=$inter(target 0) " +
                "rowBottom=${rloc[1] + row.height}(rowTop=${rloc[1]} h=${row.height} mB=$rowBottomM) " +
                "iconTop=$iconTop empty=$empty seam=" +
                (if (iconTop > 0) iconTop - (rloc[1] + row.height) else -999) +
                "(=20+inter) cand=${cand?.javaClass?.name} padB=${cand?.paddingBottom} " +
                "mB=${candLp?.bottomMargin}")
            val lca = runCatching { findLcaF17(slot, bar, decor) }.getOrNull()
            if (lca == null) {
                AndroidLog.e(TAG, "strip F17 dump: LCA missing slot=${slot.javaClass.name} " +
                    "bar=${bar.javaClass.name}")
                return inter
            }
            val lloc = IntArray(2)
            runCatching { lca.getLocationOnScreen(lloc) }
            AndroidLog.i(TAG, "strip F17 dump: LCA=${lca.javaClass.name} top=${lloc[1]} " +
                "h=${lca.height} kids=${lca.childCount} padB=${lca.paddingBottom}")
            val slotChild = runCatching { childOnPathF17(lca, slot) }.getOrNull()
            val barChild = runCatching { childOnPathF17(lca, bar) }.getOrNull()
            if (slotChild == null || barChild == null) {
                AndroidLog.e(TAG, "strip F17 dump: path child missing")
                return inter
            }
            val idxSlot = lca.indexOfChild(slotChild)
            val idxBar = lca.indexOfChild(barChild)
            AndroidLog.i(TAG, "strip F17 dump: slotChild idx=$idxSlot ${slotChild.javaClass.name} " +
                "barChild idx=$idxBar ${barChild.javaClass.name}")
            val lo = minOf(idxSlot, idxBar)
            val hi = maxOf(idxSlot, idxBar)
            if (hi - lo <= 1) {
                AndroidLog.i(TAG, "strip F17 dump: no middle sibling (adjacent), " +
                    "inter归属=padding/margin (candPadB/barTopM/slotBottomM逐项见上)")
            } else {
                var sumH = 0
                for (i in (lo + 1) until hi) {
                    val sib = lca.getChildAt(i) ?: continue
                    val sibLoc = IntArray(2)
                    runCatching { sib.getLocationOnScreen(sibLoc) }
                    val sibLp = sib.layoutParams as? ViewGroup.MarginLayoutParams
                    val sibH = sib.height.takeIf { it > 0 } ?: sib.measuredHeight
                    val kids = (sib as? ViewGroup)?.childCount ?: -1
                    val emptySib = runCatching { isEmptySiblingForF17(sib) }.getOrDefault(false)
                    AndroidLog.i(TAG, "strip F17 dump: sib[$i]=${sib.javaClass.name} " +
                        "vis=${sib.visibility} h=$sibH y=${sibLoc[1]} " +
                        "mT=${sibLp?.topMargin} mB=${sibLp?.bottomMargin} " +
                        "padB=${sib.paddingBottom} kids=$kids empty=$emptySib")
                    if (sib.visibility == View.VISIBLE && sibH > 0) {
                        sumH += sibH + (sibLp?.topMargin ?: 0) + (sibLp?.bottomMargin ?: 0)
                    }
                }
                AndroidLog.i(TAG, "strip F17 dump: middle sumH(含margin)=$sumH inter=$inter")
            }
            inter
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F17 dump failed: $t")
            null
        }
    }

    /** F17最近公共祖先（只读）：slot上行集合，bar上行首个命中即LCA。 */
    private fun findLcaF17(slot: ViewGroup, bar: View, decor: ViewGroup): ViewGroup? {
        return try {
            val barAnc = HashSet<ViewGroup>()
            var p: android.view.ViewParent? = bar.parent
            var guard = 0
            while (p is ViewGroup && guard < 12) {
                barAnc.add(p)
                if (p === decor) break
                p = p.parent
                guard++
            }
            var q: android.view.ViewParent? = slot
            guard = 0
            while (q is ViewGroup && guard < 12) {
                if (q !== slot && barAnc.contains(q)) return q
                if (q === decor) break
                q = q.parent
                guard++
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** F17路径子（只读）：lca下含target的那一子。 */
    private fun childOnPathF17(lca: ViewGroup, target: View): View? {
        var cur: View = target
        var p = target.parent
        var guard = 0
        while (p != null && guard < 12) {
            if (p === lca) return cur
            if (p !is ViewGroup) return null
            cur = p
            p = p.parent
            guard++
        }
        return null
    }

    /**
     * F17空兄弟判定（只读）：VISIBLE且高>0，但子树无可见Image系/非空文本（自家条tag排除）。
     * 空候选列表（0孩/全GONE/无字无图）即empty=true，可GONE；有字有图即false，不碰内容。
     */
    private fun isEmptySiblingForF17(v: View): Boolean {
        return try {
            if (v.visibility != View.VISIBLE) return false
            if (v.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                v.getTag() == TAG_SEARCH_BUTTON ||
                v.getTag() == TAG_SEARCH_CLEAR
            ) return false
            if (v !is ViewGroup) {
                val tv = v as? android.widget.TextView
                if (tv != null) return tv.text?.toString()?.trim().isNullOrEmpty()
                return !isToolbarImageLeaf(v)
            }
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(v)
            var hops = 0
            while (q.isNotEmpty() && hops < 80) {
                val n = q.removeFirst()
                hops++
                if (n !== v) {
                    if (n.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                        n.getTag() == TAG_SEARCH_BUTTON
                    ) continue
                    if (n.visibility != View.VISIBLE || !n.isShown) continue
                    if (n !is ViewGroup) {
                        val tv = n as? android.widget.TextView
                        if (tv != null) {
                            if (!tv.text?.toString()?.trim().isNullOrEmpty()) return false
                            continue
                        }
                        if (isToolbarImageLeaf(n)) return false
                        continue
                    }
                }
                if (n is ViewGroup) {
                    for (i in 0 until minOf(n.childCount, 25)) {
                        n.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * F17消夹层：空兄弟GONE（记账还账）、padding/margin收零（记账还账），目标槽底→工具栏顶0px。
     * 只动夹层：slot底margin/padB、cand底pad/margin、slot上行底垫/底边、bar顶margin(+上行顶边)、
     * LCA间空兄弟GONE。不动行mBottom（槽192内20px）/工具栏内边距empty/条位移/工具栏位移/
     * 键盘容器及祖先高/壳/s0/圆角B/DEL/commit/logo。每轮只requestLayout，N/float只双PASS后一次。
     * 返true=本轮施加（下轮验inter收缩），false=已0/未布局（调用方记pending验INTER-STALL）。
     */
    private fun trimInterlayerF17(row: View, decor: ViewGroup): Boolean {
        return try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return false
            val slot = row.parent as? ViewGroup ?: run {
                AndroidLog.e(TAG, "strip F17 trim: no slot parent")
                return false
            }
            if (slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null) {
                AndroidLog.e(TAG, "strip F17 trim: slot mismatch guard")
                return false
            }
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F17 trim: bar missing, keep seam")
                return false
            }
            val sloc = IntArray(2)
            runCatching { slot.getLocationOnScreen(sloc) }
            val bloc = IntArray(2)
            runCatching { bar.getLocationOnScreen(bloc) }
            val slotH = slot.height.takeIf { it > 0 } ?: slot.measuredHeight
            if (sloc[1] <= 0 || bloc[1] <= 0 || slotH <= 0) {
                AndroidLog.e(TAG, "strip F17 trim: unlaid slotTop=${sloc[1]} slotH=$slotH " +
                    "barTop=${bloc[1]}")
                return false
            }
            val slotBottom = sloc[1] + slotH
            val barTop = bloc[1]
            val inter = barTop - slotBottom
            if (inter <= STRIP_INTER_TOL_PX && inter >= -STRIP_INTER_TOL_PX) {
                AndroidLog.i(TAG, "strip F17 inter-pass: inter=$inter(0) no trim")
                return false
            }
            if (inter < 0) {
                AndroidLog.e(TAG, "strip F17 trim: OVERLAP inter=$inter no trim")
                return false
            }
            var applied = false
            val detail = StringBuilder()
            runCatching {
                val lp = slot.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null && lp.bottomMargin > 0) {
                    synchronized(stripInterBottomMarginOrigF17) {
                        if (!stripInterBottomMarginOrigF17.containsKey(slot)) {
                            stripInterBottomMarginOrigF17[slot] = lp.bottomMargin
                        }
                    }
                    detail.append("slotMBottom->0 ")
                    lp.bottomMargin = 0
                    slot.layoutParams = lp
                    applied = true
                }
            }
            runCatching {
                if (slot.paddingBottom > 0) {
                    synchronized(stripBottomPadOrigF17) {
                        if (!stripBottomPadOrigF17.containsKey(slot)) {
                            stripBottomPadOrigF17[slot] = slot.paddingBottom
                        }
                    }
                    detail.append("slotPadB->0 ")
                    slot.setPadding(slot.paddingLeft, slot.paddingTop, slot.paddingRight, 0)
                    applied = true
                }
            }
            val cand = slot.parent as? ViewGroup
            if (cand != null) {
                runCatching {
                    if (cand.paddingBottom > 0) {
                        synchronized(stripBottomPadOrigF17) {
                            if (!stripBottomPadOrigF17.containsKey(cand)) {
                                stripBottomPadOrigF17[cand] = cand.paddingBottom
                            }
                        }
                        detail.append("candPadB->0 ")
                        cand.setPadding(cand.paddingLeft, cand.paddingTop, cand.paddingRight, 0)
                        applied = true
                    }
                }
                runCatching {
                    val lp = cand.layoutParams as? ViewGroup.MarginLayoutParams
                    if (lp != null && lp.bottomMargin > 0) {
                        synchronized(stripInterBottomMarginOrigF17) {
                            if (!stripInterBottomMarginOrigF17.containsKey(cand)) {
                                stripInterBottomMarginOrigF17[cand] = lp.bottomMargin
                            }
                        }
                        detail.append("candMBottom->0 ")
                        lp.bottomMargin = 0
                        cand.layoutParams = lp
                        applied = true
                    }
                }
            }
            val lca = runCatching { findLcaF17(slot, bar, decor) }.getOrNull()
            if (lca == null) {
                AndroidLog.e(TAG, "strip F17 trim: LCA missing, partial detail=$detail inter=$inter")
            } else {
                runCatching {
                    var n = slot.parent
                    var guard = 0
                    while (n is ViewGroup && n !== lca && guard < 6) {
                        if (n !== slot && n !== cand) {
                            if (n.paddingBottom > 0) {
                                synchronized(stripBottomPadOrigF17) {
                                    if (!stripBottomPadOrigF17.containsKey(n)) {
                                        stripBottomPadOrigF17[n] = n.paddingBottom
                                    }
                                }
                                detail.append("${n.javaClass.simpleName}PadB->0 ")
                                n.setPadding(n.paddingLeft, n.paddingTop, n.paddingRight, 0)
                                applied = true
                            }
                            val lp = n.layoutParams as? ViewGroup.MarginLayoutParams
                            if (lp != null && lp.bottomMargin > 0) {
                                synchronized(stripInterBottomMarginOrigF17) {
                                    if (!stripInterBottomMarginOrigF17.containsKey(n)) {
                                        stripInterBottomMarginOrigF17[n] = lp.bottomMargin
                                    }
                                }
                                detail.append("${n.javaClass.simpleName}MB->0 ")
                                lp.bottomMargin = 0
                                n.layoutParams = lp
                                applied = true
                            }
                        }
                        n = n.parent
                        guard++
                    }
                }
                runCatching {
                    var n: View? = bar
                    var guard = 0
                    while (n != null && guard < 6) {
                        val lp = n.layoutParams as? ViewGroup.MarginLayoutParams
                        if (lp != null && lp.topMargin > 0) {
                            val key = n
                            synchronized(stripInterTopMarginOrigF17) {
                                if (!stripInterTopMarginOrigF17.containsKey(key)) {
                                    stripInterTopMarginOrigF17[key] = lp.topMargin
                                }
                            }
                            detail.append("${n.javaClass.simpleName}MT->0 ")
                            lp.topMargin = 0
                            n.layoutParams = lp
                            applied = true
                        }
                        if (n.parent === lca || n.parent == null) break
                        n = n.parent as? View
                        guard++
                    }
                }
                runCatching {
                    val slotChild = childOnPathF17(lca, slot)
                    val barChild = childOnPathF17(lca, bar)
                    if (slotChild != null && barChild != null) {
                        val idxSlot = lca.indexOfChild(slotChild)
                        val idxBar = lca.indexOfChild(barChild)
                        val lo = minOf(idxSlot, idxBar)
                        val hi = maxOf(idxSlot, idxBar)
                        for (i in (lo + 1) until hi) {
                            val sib = lca.getChildAt(i) ?: continue
                            if (sib.visibility != View.VISIBLE) continue
                            val sibH = sib.height.takeIf { it > 0 } ?: sib.measuredHeight
                            if (sibH <= 0) continue
                            val isEmpty = isEmptySiblingForF17(sib)
                            if (isEmpty) {
                                synchronized(stripSiblingVisOrigF17) {
                                    if (!stripSiblingVisOrigF17.containsKey(sib)) {
                                        stripSiblingVisOrigF17[sib] = sib.visibility
                                    }
                                }
                                sib.visibility = View.GONE
                                detail.append("sib[$i]${sib.javaClass.simpleName}h=$sibH->GONE ")
                                applied = true
                            } else {
                                detail.append("sib[$i]${sib.javaClass.simpleName}h=$sibH(keep) ")
                            }
                        }
                    }
                }
                runCatching { lca.requestLayout() }
            }
            runCatching { slot.requestLayout() }
            if (cand != null) runCatching { cand.requestLayout() }
            runCatching { bar.requestLayout() }
            (bar.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
            runCatching { row.requestLayout() }
            AndroidLog.i(TAG, "strip F17 trim: inter=$inter slotBottom=$slotBottom barTop=$barTop " +
                "applied=$applied $detail")
            applied
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F17 trim failed: $t")
            false
        }
    }

    /** F17退壳还账（幂等）：夹层GONE视图还、底垫/顶边/底边逐个还，只撤销我方增量。 */
    private fun restoreInterlayerF17() {
        try {
            var n = 0
            synchronized(stripSiblingVisOrigF17) {
                val it = stripSiblingVisOrigF17.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        if (e.key.parent != null && e.key.visibility != e.value) {
                            e.key.visibility = e.value
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripBottomPadOrigF17) {
                val it = stripBottomPadOrigF17.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        e.key.setPadding(
                            e.key.paddingLeft, e.key.paddingTop,
                            e.key.paddingRight, e.value
                        )
                        runCatching { e.key.requestLayout() }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripInterBottomMarginOrigF17) {
                val it = stripInterBottomMarginOrigF17.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        val lp = e.key.layoutParams as? ViewGroup.MarginLayoutParams
                        if (lp != null) {
                            lp.bottomMargin = e.value
                            e.key.layoutParams = lp
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripInterTopMarginOrigF17) {
                val it = stripInterTopMarginOrigF17.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        val lp = e.key.layoutParams as? ViewGroup.MarginLayoutParams
                        if (lp != null) {
                            lp.topMargin = e.value
                            e.key.layoutParams = lp
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripInterPendingF17) {
                if (stripInterPendingF17.isNotEmpty()) stripInterPendingF17.clear()
            }
            if (n > 0) AndroidLog.i(TAG, "strip F17 restored n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F17 restore failed: $t")
        }
    }

    /**
     * F18收LCA垫（C21定案：132=88+44——LCA FrameLayout padB=123 + 栏内empty44；
     * F17三选一全零抓错对象）。条挂后记账收LCA padB→0（只收123中超出部分，
     * 留栏内empty44不动，目标槽底→栏顶0±1、图标顶对条底20px），requestLayout+复测。
     * LCA须WRAP跟随收缩；若LCA是MATCH定高改不动，则改收其直接子bottomMargin等量，
     * 二选一以槽底→栏顶0±1为准。只动LCA自身padB/高/直接子MB（记账退壳全还），
     * 不动行mBottom（槽192内20px）/栏内empty44/条位移/工具栏位移/键盘容器及祖先高/
     * 壳/s0/圆角B/DEL/commit/logo。每轮只requestLayout，N三连/float只双PASS后一次。
     * 返true=本轮施加（下轮验inter收缩），false=已0/未布局/OVERLAP（调用方记pending验STALL）。
     */
    private fun trimLcaPadBF18(row: View, decor: ViewGroup): Boolean {
        return try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return false
            val slot = row.parent as? ViewGroup ?: run {
                AndroidLog.e(TAG, "strip F18 trim: no slot parent")
                return false
            }
            if (slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null) {
                AndroidLog.e(TAG, "strip F18 trim: slot mismatch guard")
                return false
            }
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F18 trim: bar missing, keep seam")
                return false
            }
            val sloc = IntArray(2)
            runCatching { slot.getLocationOnScreen(sloc) }
            val bloc = IntArray(2)
            runCatching { bar.getLocationOnScreen(bloc) }
            val slotH = slot.height.takeIf { it > 0 } ?: slot.measuredHeight
            if (sloc[1] <= 0 || bloc[1] <= 0 || slotH <= 0) {
                AndroidLog.e(TAG, "strip F18 trim: unlaid slotTop=${sloc[1]} slotH=$slotH " +
                    "barTop=${bloc[1]}")
                return false
            }
            val slotBottom = sloc[1] + slotH
            val barTop = bloc[1]
            val inter = barTop - slotBottom
            if (inter <= STRIP_INTER_TOL_PX && inter >= -STRIP_INTER_TOL_PX) {
                AndroidLog.i(TAG, "strip F18 inter-pass: inter=$inter(0) no trim")
                return false
            }
            if (inter < 0) {
                AndroidLog.e(TAG, "strip F18 trim: OVERLAP inter=$inter no trim")
                return false
            }
            val lca = runCatching { findLcaF17(slot, bar, decor) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F18 trim: LCA missing slot=${slot.javaClass.name} " +
                    "bar=${bar.javaClass.name} inter=$inter")
                return false
            }
            // 只读诊断：LCA类/高/LP/垫/屏位 + empty44（栏内，留不动）+ 行mBottom（槽192内20px，留不动）。
            val lcaPadB = lca.paddingBottom
            val lcaLpH = lca.layoutParams?.height
            val lcaLpStr = when (lcaLpH) {
                ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
                ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                null -> "null"
                else -> "$lcaLpH"
            }
            val lloc = IntArray(2)
            runCatching { lca.getLocationOnScreen(lloc) }
            val logoTmp = runCatching { resolveLogoView(decor) }.getOrNull()
            val iconLine = runCatching { scanSquareIconLine(decor, logoTmp) }.getOrNull()
            val iconTop = iconLine?.top?.toInt() ?: -1
            val empty44 = if (iconTop > 0 && barTop > 0) iconTop - barTop else -999
            val rowLp = row.layoutParams as? ViewGroup.MarginLayoutParams
            val rowBottomM = rowLp?.bottomMargin ?: -999
            val slotChild = runCatching { childOnPathF17(lca, slot) }.getOrNull()
            val barChild = runCatching { childOnPathF17(lca, bar) }.getOrNull()
            AndroidLog.i(TAG, "strip F18 measure: inter=$inter(slotBottom=$slotBottom " +
                "slotTop=${sloc[1]}+h=$slotH barTop=$barTop) LCA=${lca.javaClass.name} " +
                "top=${lloc[1]} h=${lca.height} kids=${lca.childCount} lpH=$lcaLpStr padB=$lcaPadB " +
                "(C21 123) empty44=$empty44(栏内留不动) rowMB=$rowBottomM(槽192内留不动) " +
                "slotChild=${slotChild?.javaClass?.simpleName} barChild=${barChild?.javaClass?.simpleName}")
            var applied = false
            val detail = StringBuilder()
            // 1) LCA padB→0（只收123中超出部分，栏内empty44不动；记账退壳全还）。
            if (lcaPadB > 0) {
                synchronized(stripLcaPadOrigF18) {
                    if (!stripLcaPadOrigF18.containsKey(lca)) {
                        stripLcaPadOrigF18[lca] = lcaPadB
                    }
                }
                detail.append("lcaPadB $lcaPadB->0 ")
                lca.setPadding(lca.paddingLeft, lca.paddingTop, lca.paddingRight, 0)
                applied = true
            } else {
                detail.append("lcaPadB=0 ")
            }
            // 2) WRAP跟随 vs 直接子MB二选一（以inter 0±1为准；本轮WRAP则不碰子MB，下轮复测仍FAIL再走子MB）。
            val curLpH = lca.layoutParams?.height
            if (curLpH != ViewGroup.LayoutParams.WRAP_CONTENT) {
                val lp = lca.layoutParams
                if (lp != null) {
                    synchronized(stripLcaHeightOrigF18) {
                        if (!stripLcaHeightOrigF18.containsKey(lca)) {
                            stripLcaHeightOrigF18[lca] = curLpH
                                ?: ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                    }
                    val beforeStr = when (curLpH) {
                        ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                        null -> "null"
                        else -> "$curLpH"
                    }
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    lca.layoutParams = lp
                    detail.append("lcaH $beforeStr->WRAP ")
                    applied = true
                }
                // 二选一：本轮已做WRAP，不碰直接子MB（下轮inter复测仍>1再fallback）。
            } else {
                detail.append("lcaH=WRAP ")
                // LCA已WRAP且padB已0但inter仍>1（MATCH定高改不动或余量）：收直接子bottomMargin等量。
                if (lcaPadB <= 0 && inter > STRIP_INTER_TOL_PX) {
                    if (slotChild != null && slotChild !== lca && barChild != null &&
                        slotChild === barChild
                    ) {
                        AndroidLog.e(TAG, "strip F18 fallback: same direct child " +
                            "slotChild=barChild=${slotChild.javaClass.simpleName} " +
                            "gap inside child, LCA MB无意义 inter=$inter (fail-closed)")
                    } else if (slotChild != null && slotChild !== lca) {
                        val clp = slotChild.layoutParams as? ViewGroup.MarginLayoutParams
                        if (clp != null && clp.bottomMargin > 0) {
                            synchronized(stripLcaChildMarginOrigF18) {
                                if (!stripLcaChildMarginOrigF18.containsKey(slotChild)) {
                                    stripLcaChildMarginOrigF18[slotChild] = clp.bottomMargin
                                }
                            }
                            val collect = minOf(clp.bottomMargin, inter)
                            val before = clp.bottomMargin
                            clp.bottomMargin = before - collect
                            slotChild.layoutParams = clp
                            detail.append("${slotChild.javaClass.simpleName}MB $before->${clp.bottomMargin} ")
                            applied = true
                            AndroidLog.i(TAG, "strip F18 fallback MATCH-fixed: direct child MB等量收 " +
                                "collect=$collect inter=$inter")
                        } else {
                            detail.append("slotChildMB=0(no fallback) ")
                            AndroidLog.e(TAG, "strip F18 fallback: slotChild MB=0无可收 " +
                                "child=${slotChild.javaClass.simpleName} inter=$inter")
                        }
                    } else {
                        detail.append("slotChild=null ")
                        AndroidLog.e(TAG, "strip F18 fallback: slotChild null inter=$inter")
                    }
                }
            }
            // 工具栏锚键盘不动：只requestLayout LCA/槽/候选/行，不碰bar位移/键盘/壳/s0/圆角B/DEL/commit/logo。
            runCatching { lca.requestLayout() }
            if (slotChild != null && slotChild !== lca) runCatching { slotChild.requestLayout() }
            runCatching { slot.requestLayout() }
            (slot.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
            runCatching { row.requestLayout() }
            // bar只requestLayout不同位移（工具栏零位移门禁用复测验证，不施加位移）。
            runCatching { bar.requestLayout() }
            AndroidLog.i(TAG, "strip F18 trim: inter=$inter slotBottom=$slotBottom barTop=$barTop " +
                "applied=$applied $detail" +
                "target inter 0±1 + seam 19~21(empty44留不动) + slot192±3 + toolbar0位移")
            applied
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F18 trim failed: $t")
            false
        }
    }

    /** F18退壳还账（幂等）：LCA padB/高/直接子MB逐个还，只撤销我方增量。 */
    private fun restoreLcaF18() {
        try {
            var n = 0
            synchronized(stripLcaChildMarginOrigF18) {
                val it = stripLcaChildMarginOrigF18.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        val lp = e.key.layoutParams as? ViewGroup.MarginLayoutParams
                        if (lp != null) {
                            lp.bottomMargin = e.value
                            e.key.layoutParams = lp
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripLcaHeightOrigF18) {
                val it = stripLcaHeightOrigF18.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        val lp = e.key.layoutParams
                        if (lp != null) {
                            lp.height = e.value
                            e.key.layoutParams = lp
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripLcaPadOrigF18) {
                val it = stripLcaPadOrigF18.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        e.key.setPadding(
                            e.key.paddingLeft, e.key.paddingTop,
                            e.key.paddingRight, e.value
                        )
                        runCatching { e.key.requestLayout() }
                    }
                    it.remove()
                    n++
                }
            }
            if (n > 0) AndroidLog.i(TAG, "strip F18 restored n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F18 restore failed: $t")
        }
    }

    // F19（接F18 LCA垫123已合入未提交，在此基础上改，不reset）：C22实锤F18 trim applied=true后applied=false，
    // inter 88→88纹丝不动——LCA垫123抓错对象（或宿主重置了修改，或找错LCA实例）。现状槽192 PASS、顶5 PASS、
    // 工具栏零位移PASS、底152 FAIL。修：不再猜三选一，直接遍历decor全树，列出纵向span与[slotBottom,barTop]
    // 相交的所有视图（类名/bounds/高/visibility/padding/margin/LP高），按span交集排序打日志，一次看清88px是谁；
    // 逐个验：按交集从大到小，对GONEable空视图GONE、padding/margin收零，每动一个requestLayout+复测inter，
    // 真动了（delta>=1）才留，否则立即还账试下一个；全不动记INTER-STALL。禁盲累加。
    // 顶4~7/底19~21/槽192±3/工具栏键盘钉死；壳/s0/圆角B/DEL/commit/logo/退壳全还全不动。
    /** F19 span命中（只读快照，供dump排序+逐个验顺序用）。 */
    private data class InterSpanHitF19(
        val view: View,
        val overlap: Int,
        val top: Int,
        val bottom: Int,
        val h: Int,
        val vis: Int,
        val padL: Int,
        val padT: Int,
        val padR: Int,
        val padB: Int,
        val marginT: Int,
        val marginB: Int,
        val lpH: Int,
        val cls: String
    )

    /** F19记账（只收相交视图纵向垫/边+GONE，退壳全还；不动LP高/条/工具栏位移/键盘/壳/s0/圆角B/DEL/commit/logo）。 */
    private val stripSpanPadOrigF19: MutableMap<View, IntArray> =
        Collections.synchronizedMap(WeakHashMap<View, IntArray>())
    private val stripSpanMarginOrigF19: MutableMap<View, Pair<Int, Int>> =
        Collections.synchronizedMap(WeakHashMap<View, Pair<Int, Int>>())
    private val stripSpanVisOrigF19: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** F19跨轮验证：上轮施加视图+施加前inter，下轮先验delta>=1才算落实，不动立即还账试下一个（禁盲累加）。 */
    @Volatile
    private var stripSpanLastViewF19: View? = null
    @Volatile
    private var stripSpanLastBeforeF19: Int = -1
    /** F19已验不动黑名单（Weak引用防泄漏；宿主重置致复活亦跳过不再盲试）。 */
    private val stripSpanTriedF19: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** F19现量inter（只读）：槽底=slot屏底，栏顶=bar屏顶，inter=barTop-slotBottom。任一未布局返null。 */
    private fun measureInterGapF19(row: View, decor: ViewGroup): Triple<Int, Int, Int>? {
        return try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return null
            val slot = row.parent as? ViewGroup ?: return null
            if (slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null) return null
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull() ?: return null
            val sloc = IntArray(2)
            runCatching { slot.getLocationOnScreen(sloc) }
            val bloc = IntArray(2)
            runCatching { bar.getLocationOnScreen(bloc) }
            val slotH = slot.height.takeIf { it > 0 } ?: slot.measuredHeight
            if (sloc[1] <= 0 || bloc[1] <= 0 || slotH <= 0) return null
            val slotBottom = sloc[1] + slotH
            val barTop = bloc[1]
            Triple(slotBottom, barTop, barTop - slotBottom)
        } catch (_: Throwable) {
            null
        }
    }

    /** F19全树收集（只读）：decor全树纵向span与[slotBottom,barTop]相交>0即命中。 */
    private fun collectInterSpanHitsF19(
        decor: ViewGroup,
        slotBottom: Int,
        barTop: Int
    ): List<InterSpanHitF19> {
        val out = ArrayList<InterSpanHitF19>()
        try {
            if (barTop <= slotBottom) return out
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            var hops = 0
            while (q.isNotEmpty() && hops < 2500) {
                val v = q.removeFirst()
                hops++
                try {
                    if (v is ViewGroup) {
                        for (i in 0 until minOf(v.childCount, 30)) {
                            v.getChildAt(i)?.let { q.add(it) }
                        }
                    }
                    if (v === decor) continue
                    val loc = IntArray(2)
                    runCatching { v.getLocationOnScreen(loc) }
                    val top = loc[1]
                    if (top <= 0) continue
                    val h = v.height.takeIf { it > 0 } ?: v.measuredHeight
                    if (h <= 0) continue
                    val bottom = top + h
                    val overlap = minOf(bottom, barTop) - maxOf(top, slotBottom)
                    if (overlap <= 0) continue
                    val lp = v.layoutParams
                    val mg = lp as? ViewGroup.MarginLayoutParams
                    out.add(
                        InterSpanHitF19(
                            view = v,
                            overlap = overlap,
                            top = top,
                            bottom = bottom,
                            h = h,
                            vis = v.visibility,
                            padL = v.paddingLeft,
                            padT = v.paddingTop,
                            padR = v.paddingRight,
                            padB = v.paddingBottom,
                            marginT = mg?.topMargin ?: -999,
                            marginB = mg?.bottomMargin ?: -999,
                            lpH = lp?.height ?: -9999,
                            cls = v.javaClass.name
                        )
                    )
                } catch (_: Throwable) {
                    continue
                }
            }
        } catch (_: Throwable) {
        }
        out.sortWith(compareByDescending<InterSpanHitF19> { it.overlap }.thenBy { it.top })
        return out
    }

    /**
     * F19 dump（只读不碰视图）：不再猜三选一，直接遍历decor全树，列出与[slotBottom,barTop]相交的所有视图
     * （类名/bounds/高/visibility/padding/margin/LP高），按span交集排序，一次看清88px是谁。
     * 返inter（目标0±1；缝=20+inter）。任一未布局返null。
     */
    private fun dumpInterSpanF19(row: View, decor: ViewGroup): Int? {
        return try {
            val meas = measureInterGapF19(row, decor) ?: run {
                AndroidLog.e(TAG, "strip F19 dump: unmeasurable (slot/bar unlaid)")
                return null
            }
            val slotBottom = meas.first
            val barTop = meas.second
            val inter = meas.third
            val hits = collectInterSpanHitsF19(decor, slotBottom, barTop)
            val rloc = IntArray(2)
            runCatching { row.getLocationOnScreen(rloc) }
            val logoTmp = runCatching { resolveLogoView(decor) }.getOrNull()
            val iconLine = runCatching { scanSquareIconLine(decor, logoTmp) }.getOrNull()
            val iconTop = iconLine?.top?.toInt() ?: -1
            val seam = if (iconTop > 0) iconTop - (rloc[1] + row.height) else -999
            AndroidLog.i(TAG, "strip F19 dump: slotBottom=$slotBottom barTop=$barTop inter=$inter(target 0) " +
                "hits=${hits.size} stripBottom=${rloc[1] + row.height} iconTop=$iconTop seam=$seam(=20+inter)")
            if (hits.isEmpty()) {
                AndroidLog.i(TAG, "strip F19 dump: no intersecting views (edge-touch only, slot/bar excluded)")
            }
            for ((idx, h) in hits.withIndex()) {
                val visStr = when (h.vis) {
                    View.VISIBLE -> "V"
                    View.GONE -> "G"
                    View.INVISIBLE -> "I"
                    else -> "${h.vis}"
                }
                val lpStr = when (h.lpH) {
                    ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
                    ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                    else -> "${h.lpH}"
                }
                AndroidLog.i(TAG, "strip F19 span[$idx]: overlap=${h.overlap} cls=${h.cls} " +
                    "y=[${h.top},${h.bottom}] h=${h.h} vis=$visStr " +
                    "pad=[${h.padL},${h.padT},${h.padR},${h.padB}] mT=${h.marginT} mB=${h.marginB} lpH=$lpStr")
            }
            inter
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F19 dump failed: $t")
            null
        }
    }

    /** F19保护：条子树/工具栏子树/自家tag一律不碰；祖先只收垫边禁GONE（防整树消失）。 */
    private fun isProtectedSpanViewF19(
        v: View,
        row: View,
        slot: ViewGroup,
        bar: ViewGroup,
        decor: ViewGroup
    ): Boolean {
        return try {
            if (v === decor || v === row || v === slot || v === bar) return true
            if (v.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                v.getTag() == TAG_SEARCH_BUTTON ||
                v.getTag() == TAG_SEARCH_CLEAR ||
                v.getTag() == TAG_SEARCH_BOX
            ) return true
            if (row is ViewGroup && isAncestorOf(row, v)) return true
            var p: android.view.ViewParent? = v.parent
            var guard = 0
            while (p != null && guard < 6) {
                if (p === slot) return true
                if (p === decor) break
                p = p.parent
                guard++
            }
            if (isAncestorOf(bar, v)) return true
            false
        } catch (_: Throwable) {
            true
        }
    }

    /** F19祖先判定：候选含slot或bar即祖先（只收垫边，禁GONE）。 */
    private fun isSpanAncestorF19(v: View, slot: ViewGroup, bar: ViewGroup): Boolean {
        return try {
            if (v !is ViewGroup) return false
            isAncestorOf(v, slot) || isAncestorOf(v, bar)
        } catch (_: Throwable) {
            false
        }
    }

    /** F19单候选施加（记账+requestLayout，不碰LP高/translation；返action描述，无可动返null）。 */
    private fun applyOneSpanHitF19(hit: InterSpanHitF19, isAncestor: Boolean): String? {
        return try {
            val v = hit.view
            if (v.parent == null) return null
            if (!isAncestor) {
                val empty = runCatching { isEmptySiblingForF17(v) }.getOrDefault(false)
                if (empty && v.visibility != View.GONE) {
                    synchronized(stripSpanVisOrigF19) {
                        if (!stripSpanVisOrigF19.containsKey(v)) {
                            stripSpanVisOrigF19[v] = v.visibility
                        }
                    }
                    v.visibility = View.GONE
                    runCatching { v.requestLayout() }
                    return "GONE(empty h=${hit.h})"
                }
            }
            var did = false
            val sb = StringBuilder()
            if (v.paddingTop > 0 || v.paddingBottom > 0) {
                synchronized(stripSpanPadOrigF19) {
                    if (!stripSpanPadOrigF19.containsKey(v)) {
                        stripSpanPadOrigF19[v] = intArrayOf(
                            v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom
                        )
                    }
                }
                val pb = v.paddingBottom
                val pt = v.paddingTop
                v.setPadding(v.paddingLeft, 0, v.paddingRight, 0)
                sb.append("padT $pt->0 padB $pb->0 ")
                did = true
            }
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && (lp.topMargin > 0 || lp.bottomMargin > 0)) {
                synchronized(stripSpanMarginOrigF19) {
                    if (!stripSpanMarginOrigF19.containsKey(v)) {
                        stripSpanMarginOrigF19[v] = (lp.topMargin to lp.bottomMargin)
                    }
                }
                val mt = lp.topMargin
                val mb = lp.bottomMargin
                var changed = false
                if (lp.topMargin > 0) {
                    lp.topMargin = 0
                    changed = true
                }
                if (lp.bottomMargin > 0) {
                    lp.bottomMargin = 0
                    changed = true
                }
                if (changed) {
                    v.layoutParams = lp
                    sb.append("mT $mt->0 mB $mb->0 ")
                    did = true
                }
            }
            if (!did) return null
            runCatching { v.requestLayout() }
            sb.toString().trim()
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F19 apply failed ${hit.cls}: $t")
            null
        }
    }

    /** F19单候选还账（幂等，只撤销我方增量）。 */
    private fun revertSpanViewF19(v: View) {
        try {
            synchronized(stripSpanVisOrigF19) {
                val orig = stripSpanVisOrigF19.remove(v)
                if (orig != null) {
                    runCatching {
                        if (v.parent != null && v.visibility != orig) {
                            v.visibility = orig
                            runCatching { v.requestLayout() }
                        }
                    }
                }
            }
            synchronized(stripSpanPadOrigF19) {
                val orig = stripSpanPadOrigF19.remove(v)
                if (orig != null && orig.size == 4) {
                    runCatching {
                        v.setPadding(orig[0], orig[1], orig[2], orig[3])
                        runCatching { v.requestLayout() }
                    }
                }
            }
            synchronized(stripSpanMarginOrigF19) {
                val orig = stripSpanMarginOrigF19.remove(v)
                if (orig != null) {
                    runCatching {
                        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
                        if (lp != null) {
                            lp.topMargin = orig.first
                            lp.bottomMargin = orig.second
                            v.layoutParams = lp
                            runCatching { v.requestLayout() }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F19 revert failed: $t")
        }
    }

    /**
     * F19逐个验：按交集从大到小每轮只动一个（GONEable空视图GONE/垫边收零），下轮复测inter
     * （delta>=1才留，否则立即还账试下一个）；全不动记INTER-STALL。禁盲累加。
     * 只动相交视图纵向垫/边+GONE，不碰LP高/translation/条192内20px/工具栏位移/键盘/壳/s0/圆角B/DEL/commit/logo。
     * 返true=本轮施加一个（下轮验inter收缩），false=已0/未布局/INTER-STALL。
     */
    private fun trimInterSpanF19(row: View, decor: ViewGroup): Boolean {
        return try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return false
            val slot = row.parent as? ViewGroup ?: run {
                AndroidLog.e(TAG, "strip F19 trim: no slot parent")
                return false
            }
            if (slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null) {
                AndroidLog.e(TAG, "strip F19 trim: slot mismatch guard")
                return false
            }
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F19 trim: bar missing, keep seam")
                return false
            }
            val meas = measureInterGapF19(row, decor) ?: run {
                AndroidLog.e(TAG, "strip F19 trim: unlaid")
                return false
            }
            val slotBottom = meas.first
            val barTop = meas.second
            val inter = meas.third
            if (inter <= STRIP_INTER_TOL_PX && inter >= -STRIP_INTER_TOL_PX) {
                AndroidLog.i(TAG, "strip F19 inter-pass: inter=$inter(0) no trim")
                stripSpanLastViewF19 = null
                stripSpanLastBeforeF19 = -1
                return false
            }
            if (inter < 0) {
                AndroidLog.e(TAG, "strip F19 trim: OVERLAP inter=$inter no trim")
                return false
            }
            val last = stripSpanLastViewF19
            if (last != null) {
                val before = stripSpanLastBeforeF19
                val deltaLast = if (before > 0) before - inter else 999
                if (deltaLast >= 1) {
                    AndroidLog.i(TAG, "strip F19 verified: ${last.javaClass.name} moved inter $before->$inter " +
                        "delta=$deltaLast KEEP (true master candidate)")
                    stripSpanLastViewF19 = null
                    stripSpanLastBeforeF19 = -1
                } else {
                    runCatching { revertSpanViewF19(last) }
                    synchronized(stripSpanTriedF19) { stripSpanTriedF19.add(last) }
                    val afterRevert = measureInterGapF19(row, decor)?.third ?: inter
                    AndroidLog.e(TAG, "strip F19 no-move revert: ${last.javaClass.name} " +
                        "inter $before->$inter delta=$deltaLast(<1) revert+blacklist " +
                        "afterRevert=$afterRevert (try next)")
                    stripSpanLastViewF19 = null
                    stripSpanLastBeforeF19 = -1
                    runCatching { slot.requestLayout() }
                    (slot.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
                    runCatching { row.requestLayout() }
                }
            }
            val cur = measureInterGapF19(row, decor) ?: return false
            val curInter = cur.third
            if (curInter <= STRIP_INTER_TOL_PX) return false
            val hits = collectInterSpanHitsF19(decor, cur.first, cur.second)
            if (hits.isEmpty()) {
                AndroidLog.e(TAG, "strip F19 INTER-STALL: hits=0 inter=$curInter " +
                    "slotBottom=${cur.first} barTop=${cur.second} (no intersecting views)")
                return false
            }
            val triedN = synchronized(stripSpanTriedF19) { stripSpanTriedF19.size }
            for (hit in hits) {
                val v = hit.view
                val alreadyTried = synchronized(stripSpanTriedF19) { stripSpanTriedF19.contains(v) }
                if (alreadyTried) continue
                if (v.parent == null) continue
                if (isProtectedSpanViewF19(v, row, slot, bar, decor)) continue
                val ancestor = isSpanAncestorF19(v, slot, bar)
                val actionable = if (ancestor) {
                    (v.paddingTop > 0 || v.paddingBottom > 0 ||
                        ((v.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0) > 0 ||
                        ((v.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0) > 0)
                } else {
                    val empty = runCatching { isEmptySiblingForF17(v) }.getOrDefault(false)
                    if (empty && v.visibility != View.GONE) true
                    else (v.paddingTop > 0 || v.paddingBottom > 0 ||
                        ((v.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0) > 0 ||
                        ((v.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0) > 0)
                }
                if (!actionable) continue
                val action = applyOneSpanHitF19(hit, ancestor) ?: continue
                stripSpanLastViewF19 = v
                stripSpanLastBeforeF19 = curInter
                runCatching { slot.requestLayout() }
                (slot.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
                runCatching { row.requestLayout() }
                runCatching { bar.requestLayout() }
                val visStr = when (hit.vis) {
                    View.VISIBLE -> "V"
                    View.GONE -> "G"
                    View.INVISIBLE -> "I"
                    else -> "${hit.vis}"
                }
                AndroidLog.i(TAG, "strip F19 trim: inter=$curInter slotBottom=${cur.first} barTop=${cur.second} " +
                    "try overlap=${hit.overlap} cls=${hit.cls} y=[${hit.top},${hit.bottom}] h=${hit.h} vis=$visStr " +
                    "padB=${hit.padB} mB=${hit.marginB} lpH=${hit.lpH} ancestor=$ancestor action=[$action] " +
                    "tried=$triedN/${hits.size} (verify next round delta>=1 else revert)")
                return true
            }
            AndroidLog.e(TAG, "strip F19 INTER-STALL stop: inter=$curInter slotBottom=${cur.first} " +
                "barTop=${cur.second} hits=${hits.size} tried=$triedN actionable=0 " +
                "(all no-move or no-op, no accumulate)")
            runCatching { verifyStripStacking(decor, row, "postAlignF19-inter-stall") }
            false
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F19 trim failed: $t")
            false
        }
    }

    /** F19退壳还账（幂等）：相交视图GONE/垫/边逐个还+跨轮状态清，只撤销我方增量。 */
    private fun restoreInterSpanF19() {
        try {
            var n = 0
            stripSpanLastViewF19 = null
            stripSpanLastBeforeF19 = -1
            synchronized(stripSpanVisOrigF19) {
                val it = stripSpanVisOrigF19.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        if (e.key.parent != null && e.key.visibility != e.value) {
                            e.key.visibility = e.value
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripSpanPadOrigF19) {
                val it = stripSpanPadOrigF19.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        val o = e.value
                        if (o.size == 4) {
                            e.key.setPadding(o[0], o[1], o[2], o[3])
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripSpanMarginOrigF19) {
                val it = stripSpanMarginOrigF19.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        val lp = e.key.layoutParams as? ViewGroup.MarginLayoutParams
                        if (lp != null) {
                            lp.topMargin = e.value.first
                            lp.bottomMargin = e.value.second
                            e.key.layoutParams = lp
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripSpanTriedF19) {
                if (stripSpanTriedF19.isNotEmpty()) stripSpanTriedF19.clear()
            }
            if (n > 0) AndroidLog.i(TAG, "strip F19 restored n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F19 restore failed: $t")
        }
    }

    /** F14封存：F13旧实现（停用，见上；互抵空转根因，不再调用）。 */
    // F20（接F19全树span dump已合入未提交，在此基础上改，不reset）：C23铁证span hits=42全是满高MATCH容器
    // （LinearLayout/FrameLayout[110,2376]、View[1041,2376]h1335、ConstraintLayout[1041,2376]WRAP padB72），
    // 无视图独占88px缝；trim 15连全no-move revert+黑名单，totalDy=0。结论88px不是某个视图，是窗总高多撑的——
    // 窗灰顶1041→工具栏顶1365=324，但需要=槽192（条167+顶5+底20）；多324-192=132=原候选区内容没塌
    // （搜索态候选藏了但占位还在）。另ConstraintLayout padB72可疑，顺手记账收零验证。
    // 修：1)条挂后先收ConstraintLayout padB72→0（记账还账）复测inter，动了就留不动还账；
    // 2)再收总高：publish h=条真高+顶1.5dp+底20px现算（≈192）替代原trueH，N三连/float重算跟上，
    // 目标窗灰顶→工具栏顶192±4（窗顶1041→1173方向，工具栏1365不动）。
    // 顶4~7/底19~21/槽192±3/工具栏键盘钉死；壳/s0/圆角B/DEL/commit/logo/退壳（发布高/垫全还）全不动。
    private const val STRIP_WINDOW_BAR_TOL_PX = 4
    /** F20 ConstraintLayout垫记账（只收padB→0，退壳全还；不动LP高/位移/条/工具栏/键盘/壳/s0/圆角B/DEL/commit/logo）。 */
    private val stripConstraintPadOrigF20: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** F20跨轮验证：上轮施加视图+施加前inter，下轮复测delta>=1才留，否则还账（禁盲累加）。 */
    @Volatile
    private var stripConstraintLastViewF20: View? = null
    @Volatile
    private var stripConstraintLastBeforeF20: Int = -1

    /** F20发布高现算=条真高+顶1.5dp现算+底20px（≈192，现算不写死192/167/25；失败返trueH fail-closed）。 */
    private fun publishHeightForF20(row: View, trueH: Int, iconGap: Int): Int {
        return try {
            if (trueH <= 0) {
                AndroidLog.e(TAG, "strip F20 publish: trueH unmeasurable=$trueH keep orig")
                return trueH
            }
            val mTopPx = dpToPx(row.resources, STRIP_M_TOP_DP)
            val publishH = trueH + mTopPx + iconGap
            AndroidLog.i(TAG, "strip F20 publish: trueH=$trueH+mTop=$mTopPx+bot=$iconGap->h=$publishH " +
                "(orig trueH替代为槽高，N三连/float跟上，窗灰顶→栏顶192±4，栏1365不动)")
            AndroidLog.i(TAG, "strip F29 mount-once: Y-flow publish calc trueH=$trueH mTop=$mTopPx " +
                "bot=$iconGap publishH=$publishH (单挂载只发一次)")
            publishH
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F20 publish calc failed: $t keep trueH=$trueH")
            trueH
        }
    }

    /** F20窗→栏现量（只读）：窗灰顶=cand顶现算（复用stripWindowTopGap），栏顶=工具栏顶现算，gap=栏顶-窗顶目标192±4。任一未布局返null。 */
    private fun measureWindowToBarF20(row: View, decor: ViewGroup): Triple<Int, Int, Int>? {
        return try {
            val wm = stripWindowTopGap(row) ?: return null
            val windowTop = wm.first
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull() ?: return null
            val bloc = IntArray(2)
            runCatching { bar.getLocationOnScreen(bloc) }
            val barTop = bloc[1]
            if (windowTop <= 0 || barTop <= 0) return null
            Triple(windowTop, barTop, barTop - windowTop)
        } catch (_: Throwable) {
            null
        }
    }

    /** F20窗→栏日志（只读不碰视图）：gap目标=槽目标高±4现算（≈192±4），工具栏不动由barTop前后对比验。返gap。 */
    private fun logWindowToBarF20(row: View, decor: ViewGroup, tag: String): Int? {
        return try {
            val m = measureWindowToBarF20(row, decor)
            if (m == null) {
                AndroidLog.e(TAG, "strip F20 windowBar [$tag]: unmeasurable (window/bar unlaid)")
                return null
            }
            val windowTop = m.first
            val barTop = m.second
            val gap = m.third
            val target = runCatching { slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX) }.getOrNull()
            val passStr = if (target != null && target > 0) {
                val pass = gap in (target - STRIP_WINDOW_BAR_TOL_PX)..(target + STRIP_WINDOW_BAR_TOL_PX)
                "target=$target±$STRIP_WINDOW_BAR_TOL_PX pass=$pass"
            } else {
                "target≈192±4(现算slot目标)"
            }
            AndroidLog.i(TAG, "strip F20 windowBar [$tag]: windowTop=$windowTop barTop=$barTop " +
                "gap=$gap($passStr，窗顶1041→1173方向栏1365不动，顶4~7/底19~21/槽192±3/栏键钉死不动)")
            // F40推动对象核验（只读diag，不碰视图）：bar kids与icons不同子树（F37已证），推错对象即STALL。
            runCatching {
                val barD = findStripToolbarBar(decor)
                val logoD = resolveLogoView(decor)
                val iconD = scanSquareIconLine(decor, logoD)
                val barCls = barD?.javaClass?.name ?: "null"
                val barKids = (barD as? ViewGroup)?.childCount ?: -1
                val iconTopD = iconD?.top?.toInt() ?: -1
                val iconN = iconD?.n ?: -1
                val sameAnc: String = if (barD != null && logoD != null) {
                    val lca = runCatching { findCommonAncestor(listOf(barD, logoD), decor) }.getOrNull()
                    if (lca == null) "lca=null(不同子树)"
                    else "lca=${lca.javaClass.simpleName}"
                } else "na"
                AndroidLog.i(TAG, "strip F40 windowBar-push [$tag]: bar=$barCls kids=$barKids iconTop=$iconTopD n=$iconN $sameAnc " +
                    "(bar kids vs icons不同子树，推对对象才达192±4；藏栏不推/显栏推publish+N/float，J3唯一钳439->192不动)")
            }
            gap
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F20 windowBar log failed [$tag]: $t")
            null
        }
    }

    /** F40窗推移（显栏推对对象，藏栏不推）：工具栏双真+位置ok才publish+N三连/float重刷，否则SKIP；
     * 只用publish（高度流）+refresh（N/float），不写任何LP（J3唯一钳439->192不动）；目标192±4由调用方验。 */
    private fun pushWindowAfterJ3F40(row: View, decor: ViewGroup, tag: String) {
        // F41丢弃：F40窗推移不再执行。窗高N#J3原生写k高，藏/显栏following翻译态原生。
        AndroidLog.i(TAG, "strip F41 window: SKIP F40 push [$tag] (translation native wins, diag only)")
        if (true) return
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER || row.parent == null) return
            val keepOk = runCatching { ensureToolbarVisibleF39(decor, "F40-push-$tag") }.getOrDefault(false)
            if (!keepOk) {
                AndroidLog.i(TAG, "strip F40 windowBar-push [$tag]: SKIP藏栏不推 (keep-bar FAIL，isShown=false，不断言/不REVERT)")
                return
            }
            val zeroOk = runCatching { verifyToolbarZeroShiftF34(decor, "F40-push-$tag") }.getOrNull()
            if (zeroOk != true) {
                AndroidLog.i(TAG, "strip F40 windowBar-push [$tag]: SKIP toolbar-zero!=PASS不推 (去假阳，fail-closed)")
                return
            }
            val before = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            // 正确push对象：高度流publish（现算槽高）+N三连/float重刷（窗跟上）；bar kids/icons子树不动。
            val trueH = try {
                val w = row.width.takeIf { it > 0 } ?: row.measuredWidth
                row.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                row.measuredHeight.takeIf { it > 0 } ?: row.height
            } catch (_: Throwable) {
                row.height.takeIf { it > 0 } ?: row.measuredHeight
            }
            val publishH = runCatching { publishHeightForF20(row, trueH, STRIP_M_BOTTOM_PX) }.getOrNull() ?: trueH
            runCatching { publishStripHeight(row, publishH, "F40-push-$tag") }
            runCatching { refreshCandidateLayout(row, "F40-push-$tag") }
            runCatching { refreshFloatWindow(row, "F40-push-$tag") }
            row.postDelayed({
                runCatching { logWindowToBarF20(row, decor, "F40-push-$tag-after") }
            }, POST_STABLE_DELAY_MS)
            AndroidLog.i(TAG, "strip F40 windowBar-push [$tag]: pushed publishH=$publishH(trueH=$trueH) +N/float " +
                "beforeGap=${before?.third ?: -999}(J3 439->192+正确push达192±4)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F40 windowBar-push [$tag] failed: $t")
        }
    }

    /** F20 ConstraintLayout候选查找（只读）：decor全树类名含ConstraintLayout且padB>0、VISIBLE、非我条子树，逐个日志，首个大padB即padB72可疑对象（72仅日志参考，不作过滤写死）。无命中返null。 */
    private fun findConstraintPadTargetF20(decor: ViewGroup, row: View): View? {
        return try {
            val cands = ArrayList<Pair<View, Int>>()
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            var hops = 0
            while (q.isNotEmpty() && hops < 800) {
                val v = q.removeFirst()
                hops++
                try {
                    if (v is ViewGroup) {
                        for (i in 0 until minOf(v.childCount, 30)) {
                            v.getChildAt(i)?.let { q.add(it) }
                        }
                    }
                    if (v === decor || v === row) continue
                    if (v.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                        v.getTag() == TAG_SEARCH_BUTTON ||
                        v.getTag() == TAG_SEARCH_CLEAR ||
                        v.getTag() == TAG_SEARCH_BOX
                    ) continue
                    if (row is ViewGroup && isAncestorOf(row, v)) continue
                    if (v.visibility != View.VISIBLE) continue
                    val cls = v.javaClass.name
                    if (!cls.contains("ConstraintLayout")) continue
                    val padB = v.paddingBottom
                    if (padB <= 0) continue
                    val h = v.height.takeIf { it > 0 } ?: v.measuredHeight
                    if (h <= 0) continue
                    val loc = IntArray(2)
                    runCatching { v.getLocationOnScreen(loc) }
                    if (loc[1] <= 0) continue
                    cands.add(v to padB)
                    AndroidLog.i(TAG, "strip F20 cand: cls=$cls y=${loc[1]} h=$h padB=$padB " +
                        "pad=[${v.paddingLeft},${v.paddingTop},${v.paddingRight},${v.paddingBottom}]")
                } catch (_: Throwable) {
                    continue
                }
            }
            if (cands.isEmpty()) {
                AndroidLog.i(TAG, "strip F20 cand: none (no ConstraintLayout padB>0)")
                return null
            }
            cands.sortByDescending { it.second }
            val pick = cands.first().first
            AndroidLog.i(TAG, "strip F20 cand pick: ${pick.javaClass.name} padB=${pick.paddingBottom} " +
                "(C23 padB72可疑，记账收零验证)")
            pick
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * F20先收ConstraintLayout padB→0（记账还账）复测inter，动了就留不动还账。
     * 只收纵向底垫，不碰LP高/边/位移/条192内20px/工具栏位移/键盘/壳/s0/圆角B/DEL/commit/logo。
     * 每轮只requestLayout，N/float只双PASS后一次（调用方）；退壳全还。
     * 返true=本轮施加（300ms后复测inter delta>=1留否则还账），false=已0/未布局/无候选。
     */
    private fun trimConstraintPadF20(row: View, decor: ViewGroup): Boolean {
        // F41丢弃：垫收敛不再执行，following翻译态原生。
        AndroidLog.i(TAG, "strip F41 trim: SKIP F20 (translation native wins, diag only)")
        if (true) return false
        return try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return false
            // F40：藏栏不推（工具栏isShown=false时不动垫，fail-closed；显栏才推对对象）。
            val keepForTrim = runCatching { ensureToolbarVisibleF39(decor, "F20-trim-guard") }.getOrDefault(true)
            if (!keepForTrim) {
                AndroidLog.i(TAG, "strip F40 windowBar-push [F20-trim]: SKIP藏栏不推 (keep-bar FAIL)")
                return false
            }
            val last = stripConstraintLastViewF20
            if (last != null) {
                val before = stripConstraintLastBeforeF20
                val cur = measureInterGapF19(row, decor)?.third
                if (before > 0 && cur != null) {
                    val delta = before - cur
                    if (delta >= 1) {
                        AndroidLog.i(TAG, "strip F20 padB72 verdict=KEEP: ${last.javaClass.name} " +
                            "inter $before->$cur delta=$delta(>=1留)")
                        stripConstraintLastViewF20 = null
                        stripConstraintLastBeforeF20 = -1
                    } else {
                        runCatching {
                            val orig = synchronized(stripConstraintPadOrigF20) {
                                stripConstraintPadOrigF20.remove(last)
                            }
                            if (orig != null) {
                                last.setPadding(last.paddingLeft, last.paddingTop, last.paddingRight, orig)
                                runCatching { last.requestLayout() }
                            }
                        }
                        val afterRevert = measureInterGapF19(row, decor)?.third ?: cur
                        AndroidLog.e(TAG, "strip F20 padB72 verdict=REVERT: ${last.javaClass.name} " +
                            "inter $before->$cur delta=$delta(<1不动还账) afterRevert=$afterRevert")
                        stripConstraintLastViewF20 = null
                        stripConstraintLastBeforeF20 = -1
                        runCatching { row.requestLayout() }
                    }
                } else {
                    AndroidLog.i(TAG, "strip F20 padB72 pending unmeasurable: before=$before cur=$cur keep")
                    stripConstraintLastViewF20 = null
                    stripConstraintLastBeforeF20 = -1
                }
            }
            val curInter = measureInterGapF19(row, decor)?.third
            if (curInter != null && curInter <= STRIP_INTER_TOL_PX) {
                AndroidLog.i(TAG, "strip F20 inter-pass: inter=$curInter(0) no trim")
                return false
            }
            val target = runCatching { findConstraintPadTargetF20(decor, row) }.getOrNull()
            if (target == null) return false
            val already = synchronized(stripConstraintPadOrigF20) { stripConstraintPadOrigF20.containsKey(target) }
            if (already) {
                AndroidLog.i(TAG, "strip F20 already trimmed: ${target.javaClass.name} wait verify")
                return false
            }
            val beforeInter = curInter ?: -1
            val beforePadB = target.paddingBottom
            synchronized(stripConstraintPadOrigF20) {
                stripConstraintPadOrigF20[target] = beforePadB
            }
            target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, 0)
            runCatching { target.requestLayout() }
            runCatching { row.requestLayout() }
            (row.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
            stripConstraintLastViewF20 = target
            stripConstraintLastBeforeF20 = beforeInter
            AndroidLog.i(TAG, "strip F20 trim: ${target.javaClass.name} padB $beforePadB->0 " +
                "beforeInter=$beforeInter (verify 300ms delta>=1留否则还账，顶底槽栏键全不动)")
            row.postDelayed({
                try {
                    val after = measureInterGapF19(row, decor)?.third
                    val bv = stripConstraintLastViewF20
                    val bb = stripConstraintLastBeforeF20
                    if (bv != null && bb > 0 && after != null) {
                        val delta = bb - after
                        if (delta >= 1) {
                            AndroidLog.i(TAG, "strip F20 padB72 verdict=KEEP(300ms): ${bv.javaClass.name} " +
                                "inter $bb->$after delta=$delta")
                            stripConstraintLastViewF20 = null
                            stripConstraintLastBeforeF20 = -1
                        } else {
                            runCatching {
                                val orig = synchronized(stripConstraintPadOrigF20) {
                                    stripConstraintPadOrigF20.remove(bv)
                                }
                                if (orig != null) {
                                    bv.setPadding(bv.paddingLeft, bv.paddingTop, bv.paddingRight, orig)
                                    runCatching { bv.requestLayout() }
                                }
                            }
                            AndroidLog.e(TAG, "strip F20 padB72 verdict=REVERT(300ms): ${bv.javaClass.name} " +
                                "inter $bb->$after delta=$delta(<1还账)")
                            stripConstraintLastViewF20 = null
                            stripConstraintLastBeforeF20 = -1
                        }
                        runCatching { logWindowToBarF20(row, decor, "F20-verify") }
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F20 verify failed: $t")
                }
            }, POST_STABLE_DELAY_MS)
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F20 trim failed: $t")
            false
        }
    }

    /** F20退壳还账（幂等）：ConstraintLayout padB逐个还，只撤销我方增量。 */
    private fun restoreConstraintPadF20() {
        try {
            var n = 0
            stripConstraintLastViewF20 = null
            stripConstraintLastBeforeF20 = -1
            synchronized(stripConstraintPadOrigF20) {
                val it = stripConstraintPadOrigF20.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        e.key.setPadding(e.key.paddingLeft, e.key.paddingTop, e.key.paddingRight, e.value)
                        runCatching { e.key.requestLayout() }
                    }
                    it.remove()
                    n++
                }
            }
            if (n > 0) AndroidLog.i(TAG, "strip F20 restored n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F20 restore failed: $t")
        }
    }

    // F21（接F20发布高192已合入未提交，在此基础上改，不reset）：C24实锤挂载瞬间window/bar
    // unlaid不可量（beforeInter=-1，verdict跳过），稳态windowBar=280 FAIL；F20 padB72收零把整体搬下
    // 72px但inter 88纹丝不动；之前所有dump全是挂载瞬间量的，布局没沉降。barTop→iconTop恒44，
    // slotBottom→barTop恒88。
    // 修：1)把inter归属dump搬到稳态：条挂后5秒（布局沉降后）再dump栏容器自身（paddingTop/
    // 首个孩子/各孩子高与visibility/自身LP高）+slot父链，日志关键字steady-state，只读不写先定案；
    // 2)按稳态定案修：若栏容器paddingTop≈88记账收零；若首个孩子是空占位（高≈88/GONEable）则GONE；
    // 每动一个requestLayout+复测inter，动了留、不动还账试下一个。禁盲累加。
    // 顶4~7/底19~21/槽192±3/工具栏键盘钉死（只收垫/藏空视图，不位移条与栏）；
    // 壳/s0/圆角B/DEL/commit/logo/退壳全还全不动。
    private const val STEADY_STATE_DELAY_MS = 5000L
    private const val STRIP_BAR_PAD88_PX = 88
    private const val STRIP_BAR_PAD88_TOL_PX = 8
    private const val STRIP_BAR_CHILD88_PX = 88
    private const val STRIP_BAR_CHILD88_TOL_PX = 8
    /** F21栏垫记账（只收栏容器自身paddingTop→0，退壳全还；不动LP高/边/位移/条/键盘/壳/s0/圆角B/DEL/commit/logo）。 */
    private val stripBarPadOrigF21: MutableMap<View, IntArray> =
        Collections.synchronizedMap(WeakHashMap<View, IntArray>())
    /** F21栏首孩记账（只GONE空占位首孩，退壳全还）。 */
    private val stripBarChildVisOrigF21: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** F21跨轮验证：上轮施加视图+施加前inter，下轮复测delta>=1才留，否则还账试下一个（禁盲累加）。 */
    @Volatile
    private var stripBarLastViewF21: View? = null
    @Volatile
    private var stripBarLastBeforeF21: Int = -1
    /** F21已验不动黑名单（Weak防泄漏；宿主重置致复活亦跳过不再盲试）。 */
    private val stripBarTriedF21: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    /** F21稳态已排期（防500/1200ms复挂三定时器叠加；Weak防泄漏）。 */
    private val stripSteadyScheduledF21: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    // F22（接F21稳态dump已合入未提交，在此基础上改，不reset）：C25稳态铁证bar=RecyclerView h=100
    // padT=0 kids=4，iconTop=1437 cy=1416，但barTop→iconTop=44恒定、slotBottom→barTop=88恒定，
    // 多轮多版纹丝不动。主控怀疑：模块锁错行——真图标顶应在条底1285下约20px即1305附近
    // （底20px目标），而1437可能是Q排顶1494附近误检（Q键上半/第二工具栏/AI条残留），或开条前
    // 键盘页旧簇（cy1416正是无条基线值！）被缓存复用。佐证：C9曾抓到n=6/6 y=1416 top=1365
    // （基线），此后各轮iconTop/cy=1365/1416一字不动——条挂后工具栏若真零位移复用旧值是对的；
    // 但F20把整体搬下72px后（strip1120/1286）图标簇仍报1416/1365，旧值已stale。
    // 修：1)图标簇禁止复用旧值：每次postAlign轮询重扫（scanSquareIconLine现扫，cy容差±28px
    // n>=5），Q_top带限行（cy<Q_top-100px，防Q键误检），扫不到记diag不dropped；
    // 2)加视觉交叉：像素亮斑法（白圆在灰底上，min>225连通域）独立验iconTop，与视图簇差>20px
    // 则记mismatch用像素值（diagnostically），二选一以像素为准。
    // 3)闭环目标不动：顶4~7/底19~21/槽192±3/工具栏键盘钉死；壳/s0/圆角B/DEL/commit/logo/退壳全还全不动。
    private const val STRIP_ICON_Q_GUARD_PX = 100
    private const val STRIP_ICON_BRIGHT_MIN = 225
    private const val STRIP_ICON_PIXEL_MISMATCH_PX = 20
    /** F22 stale证据（只记诊断不复用：上轮视图簇top/cy + 条底，供本轮比对旧值是否stale）。 */
    @Volatile
    private var stripIconLastViewTopF22: Float = -1f
    @Volatile
    private var stripIconLastViewCyF22: Float = -1f
    @Volatile
    private var stripIconLastStripBottomF22: Int = -1

    /**
     * F21稳态排期：条挂后5秒（布局沉降后）再dump+修，只排一次（防复挂叠加）。
     * 只读dump先定案（关键字steady-state），再按定案单步修；行已摘则跳过。
     * 顶底槽栏键钉死；壳/s0/圆角B/DEL/commit/logo不动。
     */
    private fun scheduleBarSteadyStateF21(decor: ViewGroup, row: View) {
        // F41丢弃：稳态修（栏垫88/定高280/kids88/过期块/残留/缝）不再执行，following翻译态原生。
        AndroidLog.i(TAG, "strip F41 steady: SKIP F21 (translation native wins, diag only)")
        if (true) return
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            synchronized(stripSteadyScheduledF21) {
                if (stripSteadyScheduledF21.contains(row)) return
                stripSteadyScheduledF21.add(row)
            }
            row.postDelayed({
                try {
                    if (row.parent == null) return@postDelayed
                    runCatching { dumpBarSteadyStateF21(row, decor) }
                    runCatching { trimBarSteadyF21(row, decor) }
                    // F23稳态5秒父链定高（只读dump steady-parent先定案，再单步显式192覆盖验windowBar）。
                    // 顶4~7/底19~21/工具栏键盘钉死；壳/s0/圆角B/DEL/commit/logo不动。
                    runCatching { dumpSlotParentChainSteadyF23(row, decor) }
                    runCatching { tryFixFixedParentSteadyF23(row, decor) }
                    // F24稳态5秒kids+N对照（与F21/F23独立，先后验；只读dump steady-kids先定案，再单步GONE+AB对照）。
                    // 顶4~7/底19~21/工具栏键盘钉死；壳/s0/圆角B/DEL/commit/logo不动。
                    runCatching { dumpImeCandidateKidsSteadyF24(row, decor) }
                    runCatching { tryKidGoneSteadyF24(row, decor) }
                    runCatching { tryNRefreshABSteadyF24(row, decor) }
                    // F25稳态5秒[0]/[4]辨认（与F21/F23/F24独立，先后验；只读dump steady-who先定案，
                    // 再单步藏过期块验windowBar）。顶4~7/底19~21/工具栏键盘钉死；
                    // 壳/s0/圆角B/DEL/commit/logo不动。
                    runCatching { dumpStaleWhoSteadyF25(row, decor) }
                    runCatching { tryHideStaleSteadyF25(row, decor) }
                    // F26稳态5秒[4]深查+残留藏（接F25，在此基础上改，不reset；与F21/F23/F24/F25独立，
                    // 先后验；只读dump steady-4先定案，再单步GONE[4]验windowBar）。[0]活工具栏绝不动。
                    // 顶底槽栏键钉死；壳/s0/圆角B/DEL/commit/logo不动。
                    runCatching { dumpStale4SteadyF26(row, decor) }
                    runCatching { tryHideStale4SteadyF26(row, decor) }
                    // F27稳态5秒缝试探（接F26，在此基础上改，不reset；与F21/F23/F24/F25/F26独立，
                    // 先后验；只读dump F27-seam先定案①entry解名，再单步②GONE kid[0]验[4]高，
                    // 失败链试③[4]WRAP验图标1443±2与windowBar）。[0]6图标活工具栏绝不动。
                    // 目标windowBar→192±4、顶4~7、底19~21；壳/s0/圆角B/DEL/commit/logo不动。
                    runCatching { dumpSeamF27(row, decor) }
                    runCatching { tryKid0GoneF27(row, decor) }
                    // F28稳态5秒跳过对照（接F27，在此基础上改，不reset；与F21/F23/F24/F25/F26/F27独立，
                    // 先后验；①跳过float→②跳过N只publish→③publish167，每步300ms验windowBar近192定胜）。
                    // 顶4~7/底19~21/工具栏键盘/图标1443钉死；壳/s0/圆角B/DEL/commit/logo不动。
                    // F30条态稳态快照（只读，同口径，关键字strip-snap+drift；顶底槽壳等全不动）。
                    runCatching { logStripSnapF30(decor, row, "steady-F21") }
                    runCatching { scheduleSkipContrastF28(decor, row) }
                    // F32稳态栏下移+键盘补偿（接F31钳窗，在此基础上改，不reset；与F21/F23/F24/F25/F26/F27/F28独立，
                    // 先后验；栏按类+屏位找RecyclerView记账移到候选框下方，键盘Q漂移补偿移回）。
                    // 顶4~7由J3钳保证、底19~21由本步保证；壳/s0/J3钳/圆角B/DEL/commit/logo不动。
                    runCatching { tryMoveBarBelowCandF32(row, decor) }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F21 steady-state failed: $t")
                }
            }, STEADY_STATE_DELAY_MS)
            AndroidLog.i(TAG, "strip F21 steady-state scheduled 5000ms row=${row.javaClass.simpleName}")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F21 steady-state schedule failed: $t")
        }
    }

    /**
     * F21稳态dump（只读不写，先定案）：栏容器自身（paddingTop/首个孩子/各孩子高与visibility/
     * 自身LP高）+slot父链 + inter/windowBar现量。日志关键字steady-state。
     * 任一未布局记诊断返null（不拦修，修侧复测为准）。返inter（目标0±1）。
     */
    private fun dumpBarSteadyStateF21(row: View, decor: ViewGroup): Int? {
        return try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                AndroidLog.e(TAG, "strip F21 steady-state: row tag mismatch")
                return null
            }
            val slot = row.parent as? ViewGroup ?: run {
                AndroidLog.e(TAG, "strip F21 steady-state: no slot parent (unlaid)")
                return null
            }
            if (slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null) {
                AndroidLog.e(TAG, "strip F21 steady-state: slot mismatch guard")
                return null
            }
            val meas = measureInterGapF19(row, decor)
            val slotBottom = meas?.first ?: -1
            val barTopMeas = meas?.second ?: -1
            val inter = meas?.third
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            val windowTop = winBar?.first ?: -1
            val barTopWin = winBar?.second ?: -1
            val windowBar = winBar?.third
            // F22稳态亦走统一出口（现扫视图+像素交叉，差>20px用像素diagnostically），只读诊断。
            val logoTmp = runCatching { resolveLogoView(decor) }.getOrNull()
            val iconLine = runCatching { resolveIconLineF22(decor, logoTmp) }.getOrNull()
            val iconTop = iconLine?.top?.toInt() ?: -1
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
            if (bar == null) {
                AndroidLog.e(TAG, "strip F21 steady-state: bar missing slotBottom=$slotBottom " +
                    "barTop=$barTopMeas inter=$inter windowTop=$windowTop windowBar=$windowBar")
                return inter
            }
            val bloc = IntArray(2)
            runCatching { bar.getLocationOnScreen(bloc) }
            val barH = bar.height.takeIf { it > 0 } ?: bar.measuredHeight
            val barLpH = bar.layoutParams?.height ?: -9999
            val barLpStr = when (barLpH) {
                ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
                ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                else -> "$barLpH"
            }
            val barPadT = bar.paddingTop
            val barPadB = bar.paddingBottom
            val kids = bar.childCount
            AndroidLog.i(TAG, "strip F21 steady-state: slotBottom=$slotBottom barTop=$barTopMeas " +
                "inter=$inter(target 0) windowTop=$windowTop barTopWin=$barTopWin " +
                "windowBar=$windowBar(target≈192±4) iconTop=$iconTop " +
                "barTop->iconTop=${if (iconTop > 0 && barTopMeas > 0) iconTop - barTopMeas else -999}(恒44) " +
                "bar=${bar.javaClass.name} y=${bloc[1]} h=$barH vis=${bar.visibility} " +
                "padT=$barPadT padB=$barPadB lpH=$barLpStr kids=$kids")
            for (i in 0 until kids) {
                val kid = bar.getChildAt(i) ?: continue
                val kloc = IntArray(2)
                runCatching { kid.getLocationOnScreen(kloc) }
                val kh = kid.height.takeIf { it > 0 } ?: kid.measuredHeight
                val visStr = when (kid.visibility) {
                    View.VISIBLE -> "V"
                    View.GONE -> "G"
                    View.INVISIBLE -> "I"
                    else -> "${kid.visibility}"
                }
                val empty = runCatching { isEmptySiblingForF17(kid) }.getOrDefault(false)
                val firstMark = if (i == 0) " first" else ""
                AndroidLog.i(TAG, "strip F21 steady-state: barKid[$i]$firstMark " +
                    "cls=${kid.javaClass.name} y=${kloc[1]} h=$kh vis=$visStr empty=$empty")
            }
            if (kids > 0) {
                val first = bar.getChildAt(0)
                if (first != null) {
                    val fh = first.height.takeIf { it > 0 } ?: first.measuredHeight
                    val empty = runCatching { isEmptySiblingForF17(first) }.getOrDefault(false)
                    val padHit = barPadT in
                        (STRIP_BAR_PAD88_PX - STRIP_BAR_PAD88_TOL_PX)..
                        (STRIP_BAR_PAD88_PX + STRIP_BAR_PAD88_TOL_PX)
                    val childHit = fh in
                        (STRIP_BAR_CHILD88_PX - STRIP_BAR_CHILD88_TOL_PX)..
                        (STRIP_BAR_CHILD88_PX + STRIP_BAR_CHILD88_TOL_PX)
                    AndroidLog.i(TAG, "strip F21 steady-state verdict: barPadT=$barPadT" +
                        "(≈88? $padHit) firstH=$fh(≈88? $childHit) firstEmpty=$empty " +
                        "inter=$inter(88? ${inter == 88}) (padHit->收零 childHit+empty->GONE)")
                }
            } else {
                AndroidLog.i(TAG, "strip F21 steady-state verdict: barKids=0 no first child")
            }
            var node: android.view.ViewParent? = slot
            var depth = 0
            while (node is ViewGroup && depth < 8) {
                val n = node as ViewGroup
                val nloc = IntArray(2)
                runCatching { n.getLocationOnScreen(nloc) }
                val nh = n.height.takeIf { it > 0 } ?: n.measuredHeight
                val nLp = n.layoutParams as? ViewGroup.MarginLayoutParams
                AndroidLog.i(TAG, "strip F21 steady-state: slotChain[$depth] " +
                    "cls=${n.javaClass.name} y=${nloc[1]} h=$nh vis=${n.visibility} " +
                    "padT=${n.paddingTop} padB=${n.paddingBottom} mT=${nLp?.topMargin} " +
                    "mB=${nLp?.bottomMargin} lpH=${n.layoutParams?.height}")
                if (n === decor) break
                node = n.parent
                depth++
            }
            inter
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F21 steady-state dump failed: $t")
            null
        }
    }

    /**
     * F21按稳态定案修：若栏容器paddingTop≈88记账收零；若首个孩子是空占位（高≈88/GONEable）
     * 则GONE；每动一个requestLayout+复测inter（300ms后delta>=1留，否则还账试下一个）。
     * 禁盲累加。只收垫/藏空视图，不位移条与栏（不动translation/margin/LP高/槽高/工具栏位移/
     * 键盘/壳/s0/圆角B/DEL/commit/logo）。返true=本轮施加（待复测），false=已0/未布局/STALL。
     */
    private fun trimBarSteadyF21(row: View, decor: ViewGroup): Boolean {
        return try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return false
            val slot = row.parent as? ViewGroup ?: run {
                AndroidLog.e(TAG, "strip F21 steady-state trim: no slot parent")
                return false
            }
            if (slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null) {
                AndroidLog.e(TAG, "strip F21 steady-state trim: slot mismatch guard")
                return false
            }
            val last = stripBarLastViewF21
            if (last != null) {
                val before = stripBarLastBeforeF21
                val cur = measureInterGapF19(row, decor)?.third
                if (before > 0 && cur != null) {
                    val delta = before - cur
                    if (delta >= 1) {
                        AndroidLog.i(TAG, "strip F21 steady-state verdict=KEEP: " +
                            "${last.javaClass.name} inter $before->$cur delta=$delta(>=1留)")
                        stripBarLastViewF21 = null
                        stripBarLastBeforeF21 = -1
                    } else {
                        runCatching { revertBarViewF21(last) }
                        synchronized(stripBarTriedF21) { stripBarTriedF21.add(last) }
                        val after = measureInterGapF19(row, decor)?.third ?: cur
                        AndroidLog.e(TAG, "strip F21 steady-state verdict=REVERT: " +
                            "${last.javaClass.name} inter $before->$cur delta=$delta(<1还账试下一个) " +
                            "afterRevert=$after")
                        stripBarLastViewF21 = null
                        stripBarLastBeforeF21 = -1
                        runCatching { slot.requestLayout() }
                        runCatching { row.requestLayout() }
                    }
                } else {
                    stripBarLastViewF21 = null
                    stripBarLastBeforeF21 = -1
                }
            }
            val curMeas = measureInterGapF19(row, decor)
            val curInter = curMeas?.third
            if (curInter != null && curInter <= STRIP_INTER_TOL_PX && curInter >= -STRIP_INTER_TOL_PX) {
                AndroidLog.i(TAG, "strip F21 steady-state inter-pass: inter=$curInter(0) no trim")
                return false
            }
            if (curMeas == null) {
                AndroidLog.e(TAG, "strip F21 steady-state trim: unmeasurable (slot/bar unlaid)")
                return false
            }
            if (curInter != null && curInter < 0) {
                AndroidLog.e(TAG, "strip F21 steady-state trim: OVERLAP inter=$curInter no trim")
                return false
            }
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F21 steady-state trim: bar missing keep seam inter=$curInter")
                return false
            }
            val barPadT = bar.paddingTop
            val padHit = barPadT in
                (STRIP_BAR_PAD88_PX - STRIP_BAR_PAD88_TOL_PX)..
                (STRIP_BAR_PAD88_PX + STRIP_BAR_PAD88_TOL_PX)
            val padTried = synchronized(stripBarTriedF21) { stripBarTriedF21.contains(bar) }
            val padTrimmed = synchronized(stripBarPadOrigF21) { stripBarPadOrigF21.containsKey(bar) }
            if (padHit && !padTried && !padTrimmed && bar.parent != null) {
                val beforeInter = curInter ?: -1
                synchronized(stripBarPadOrigF21) {
                    stripBarPadOrigF21[bar] = intArrayOf(
                        bar.paddingLeft, bar.paddingTop, bar.paddingRight, bar.paddingBottom
                    )
                }
                bar.setPadding(bar.paddingLeft, 0, bar.paddingRight, bar.paddingBottom)
                runCatching { bar.requestLayout() }
                (bar.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
                runCatching { slot.requestLayout() }
                runCatching { row.requestLayout() }
                stripBarLastViewF21 = bar
                stripBarLastBeforeF21 = beforeInter
                AndroidLog.i(TAG, "strip F21 steady-state trim: ${bar.javaClass.name} " +
                    "padT $barPadT->0 beforeInter=$beforeInter " +
                    "(verify 300ms delta>=1留否则还账，顶底槽栏键不动)")
                runCatching { scheduleBarVerifyF21(row, decor, bar, beforeInter, "padT") }
                return true
            }
            if (bar.childCount > 0) {
                val first = bar.getChildAt(0)
                if (first != null && first.parent != null) {
                    val fh = first.height.takeIf { it > 0 } ?: first.measuredHeight
                    val childHit = fh in
                        (STRIP_BAR_CHILD88_PX - STRIP_BAR_CHILD88_TOL_PX)..
                        (STRIP_BAR_CHILD88_PX + STRIP_BAR_CHILD88_TOL_PX)
                    val empty = runCatching { isEmptySiblingForF17(first) }.getOrDefault(false)
                    val childTried = synchronized(stripBarTriedF21) { stripBarTriedF21.contains(first) }
                    val childTrimmed = synchronized(stripBarChildVisOrigF21) {
                        stripBarChildVisOrigF21.containsKey(first)
                    }
                    if (childHit && empty && first.visibility != View.GONE &&
                        !childTried && !childTrimmed
                    ) {
                        val beforeInter = curInter ?: -1
                        synchronized(stripBarChildVisOrigF21) {
                            stripBarChildVisOrigF21[first] = first.visibility
                        }
                        first.visibility = View.GONE
                        runCatching { first.requestLayout() }
                        runCatching { bar.requestLayout() }
                        (bar.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
                        runCatching { slot.requestLayout() }
                        runCatching { row.requestLayout() }
                        stripBarLastViewF21 = first
                        stripBarLastBeforeF21 = beforeInter
                        AndroidLog.i(TAG, "strip F21 steady-state trim: firstChild " +
                            "${first.javaClass.name} h=$fh->GONE(empty=$empty) " +
                            "beforeInter=$beforeInter (verify 300ms delta>=1留否则还账)")
                        runCatching { scheduleBarVerifyF21(row, decor, first, beforeInter, "firstGONE") }
                        return true
                    } else {
                        AndroidLog.i(TAG, "strip F21 steady-state skip first: h=$fh(≈88? $childHit) " +
                            "empty=$empty vis=${first.visibility} tried=$childTried trimmed=$childTrimmed")
                    }
                }
            }
            AndroidLog.e(TAG, "strip F21 steady-state INTER-STALL stop: inter=$curInter " +
                "slotBottom=${curMeas.first} barTop=${curMeas.second} " +
                "barPadT=$barPadT(≈88? $padHit) tried=$padTried trimmed=$padTrimmed " +
                "(all no-move or no-op, no accumulate)")
            runCatching { verifyStripStacking(decor, row, "postAlignF21-steady-stall") }
            false
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F21 steady-state trim failed: $t")
            false
        }
    }

    /** F21单步复测（300ms后delta>=1留，否则还账试下一个；禁盲累加）。 */
    private fun scheduleBarVerifyF21(
        row: View,
        decor: ViewGroup,
        appliedView: View,
        beforeInter: Int,
        action: String
    ) {
        try {
            row.postDelayed({
                try {
                    val bv = stripBarLastViewF21
                    if (bv == null || bv !== appliedView) return@postDelayed
                    val after = measureInterGapF19(row, decor)?.third
                    if (beforeInter > 0 && after != null) {
                        val delta = beforeInter - after
                        if (delta >= 1) {
                            AndroidLog.i(TAG, "strip F21 steady-state verdict=KEEP(300ms): " +
                                "${bv.javaClass.name}[$action] inter $beforeInter->$after delta=$delta")
                            stripBarLastViewF21 = null
                            stripBarLastBeforeF21 = -1
                        } else {
                            runCatching { revertBarViewF21(bv) }
                            synchronized(stripBarTriedF21) { stripBarTriedF21.add(bv) }
                            val afterRevert = measureInterGapF19(row, decor)?.third ?: after
                            AndroidLog.e(TAG, "strip F21 steady-state verdict=REVERT(300ms): " +
                                "${bv.javaClass.name}[$action] inter $beforeInter->$after " +
                                "delta=$delta(<1还账) afterRevert=$afterRevert (try next)")
                            stripBarLastViewF21 = null
                            stripBarLastBeforeF21 = -1
                            runCatching { row.requestLayout() }
                            runCatching { trimBarSteadyF21(row, decor) }
                        }
                        runCatching { logWindowToBarF20(row, decor, "F21-verify") }
                    } else {
                        stripBarLastViewF21 = null
                        stripBarLastBeforeF21 = -1
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F21 steady-state verify failed: $t")
                }
            }, POST_STABLE_DELAY_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F21 steady-state verify schedule failed: $t")
        }
    }

    /** F21单视图还账（幂等，只撤销我方增量）。 */
    private fun revertBarViewF21(v: View) {
        try {
            synchronized(stripBarChildVisOrigF21) {
                val orig = stripBarChildVisOrigF21.remove(v)
                if (orig != null) {
                    runCatching {
                        if (v.parent != null && v.visibility != orig) {
                            v.visibility = orig
                            runCatching { v.requestLayout() }
                        }
                    }
                }
            }
            synchronized(stripBarPadOrigF21) {
                val orig = stripBarPadOrigF21.remove(v)
                if (orig != null && orig.size == 4) {
                    runCatching {
                        v.setPadding(orig[0], orig[1], orig[2], orig[3])
                        runCatching { v.requestLayout() }
                    }
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F21 steady-state revert failed: $t")
        }
    }

    /** F21退壳还账（幂等）：栏垫/首孩逐个还+跨轮状态清，只撤销我方增量。 */
    private fun restoreBarSteadyF21() {
        try {
            var n = 0
            stripBarLastViewF21 = null
            stripBarLastBeforeF21 = -1
            synchronized(stripBarChildVisOrigF21) {
                val it = stripBarChildVisOrigF21.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        if (e.key.parent != null && e.key.visibility != e.value) {
                            e.key.visibility = e.value
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripBarPadOrigF21) {
                val it = stripBarPadOrigF21.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        val o = e.value
                        if (o.size == 4) {
                            e.key.setPadding(o[0], o[1], o[2], o[3])
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripBarTriedF21) {
                if (stripBarTriedF21.isNotEmpty()) stripBarTriedF21.clear()
            }
            synchronized(stripSteadyScheduledF21) {
                if (stripSteadyScheduledF21.isNotEmpty()) stripSteadyScheduledF21.clear()
            }
            if (n > 0) AndroidLog.i(TAG, "strip F21 steady-state restored n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F21 steady-state restore failed: $t")
        }
    }

    // F23（接F22稳态已合入未提交，在此基础上改，不reset）：C26+主控肉眼双确认（以此为准）：
    // 灰缝真实约150px（非量错），crop22条底1285→图标顶1443；slotBottom1305→barTop1393=88恒定；
    // windowBar=280（要192）；publish已发192但窗高不动；F19 span全满高MATCH容器、无独占视图；
    // 像素交叉在模块内失败（brightPass=0）但外部脚本同算法可用。
    // 修1)稳态5秒后dump slot→decor父链每层实高+LP高+MeasureSpec，找出定高280的那层
    // （哪层LP.height是定值/被N三连publish写死），日志steady-parent；
    // 2)定高层若是宿主N刷新写的，用显式LP高=192（条167+顶5+底20现算）覆盖（记账退壳还账），
    // requestLayout+复测windowBar，真缩（delta>=4）才留，否则还账试N-refresh调参
    // （跳过N三连看窗高是否回192，二选一以windowBar为准）；
    // 3)模块像素扫描直接抄tools/open-strip.sh的detect波段算法
    // （stripBottom→Q_top灰带白圆阈值），修brightPass=0失败。
    // 顶4~7/底19~21/工具栏键盘钉死；壳/s0/圆角B/DEL/commit/logo/退壳全还全不动。
    private const val STRIP_FIXED_DELTA_KEEP_PX = 4
    private const val STRIP_FIXED_280_PX = 280
    private const val STRIP_FIXED_280_TOL_PX = 12
    /** F23定高父记账（只收定高层LP.height→target≈192，退壳全还；不动LP宽/边/位移/条/工具栏/键盘/壳/s0/圆角B/DEL/commit/logo）。 */
    private val stripFixedParentOrigF23: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** F23已验不动黑名单（Weak防泄漏；宿主重置致复活亦跳过不再盲试）。 */
    private val stripFixedParentTriedF23: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    @Volatile
    private var stripFixedParentLastViewF23: View? = null
    @Volatile
    private var stripFixedParentLastBeforeF23: Int = -1
    @Volatile
    private var stripFixedParentLastTargetF23: Int = -1
    /** F23 N-refresh调参：跳过N三连标志（只publish+A2/e0+float，看窗高是否回192；二选一以windowBar为准）。 */
    @Volatile
    private var stripSkipNTripleF23: Boolean = false

    /**
     * F23稳态父链dump（只读不碰视图）：slot→decor每层实高+LP高+MeasureSpec推断，找定高280层。
     * 日志关键字steady-parent。任一未布局记诊断返null（不拦修，修侧复测为准）。
     * 返定高疑犯（首个LP.height定值且实高≈windowBar/280者），无则null。
     * 只读几何+LP，不碰顶4~7/底19~21/工具栏键盘/壳/s0/圆角B/DEL/commit/logo。
     */
    private fun dumpSlotParentChainSteadyF23(row: View, decor: ViewGroup): ViewGroup? {
        return try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                AndroidLog.e(TAG, "strip F23 steady-parent: row tag mismatch")
                return null
            }
            val slot = row.parent as? ViewGroup ?: run {
                AndroidLog.e(TAG, "strip F23 steady-parent: no slot parent (unlaid)")
                return null
            }
            if (slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null) {
                AndroidLog.e(TAG, "strip F23 steady-parent: slot mismatch guard")
                return null
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            val windowTop = winBar?.first ?: -1
            val barTopWin = winBar?.second ?: -1
            val windowBar = winBar?.third ?: -1
            val target = runCatching { slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX) }.getOrNull() ?: -1
            val slotH = slot.height.takeIf { it > 0 } ?: slot.measuredHeight
            val rlocTmp = IntArray(2)
            runCatching { row.getLocationOnScreen(rlocTmp) }
            val stripBottomTmp = if (rlocTmp[1] > 0 && row.height > 0) rlocTmp[1] + row.height else -1
            AndroidLog.i(TAG, "strip F23 steady-parent: slotH=$slotH target=$target(条真高+顶1.5dp+底20现算≈192) " +
                "windowTop=$windowTop barTop=$barTopWin windowBar=$windowBar(要192±4，280即定高未缩) " +
                "stripBottom=$stripBottomTmp (顶4~7/底19~21/栏键钉死不动)")
            var node: ViewGroup? = slot
            var depth = 0
            var suspect: ViewGroup? = null
            while (node != null && depth < 10) {
                val nloc = IntArray(2)
                runCatching { node.getLocationOnScreen(nloc) }
                val nh = node.height.takeIf { it > 0 } ?: node.measuredHeight
                val nmw = node.measuredWidth
                val nmh = node.measuredHeight
                val lp = node.layoutParams
                val lpH = lp?.height ?: -9999
                val lpW = lp?.width ?: -9999
                val lpHStr = when (lpH) {
                    ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
                    ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                    else -> "$lpH"
                }
                val lpWStr = when (lpW) {
                    ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
                    ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                    else -> "$lpW"
                }
                val mg = lp as? ViewGroup.MarginLayoutParams
                // MeasureSpec推断（View不存Spec，以LP模式+实测/布局对照推断，供定高归属用）：
                // LP定值→EXACTLY定值；MATCH→EXACTLY(parent)；WRAP→AT_MOST(parent)。
                val specH = when (lpH) {
                    ViewGroup.LayoutParams.WRAP_CONTENT -> "AT_MOST(parent)"
                    ViewGroup.LayoutParams.MATCH_PARENT -> "EXACTLY(parent)"
                    else -> "EXACTLY(fixed $lpH)"
                }
                val visStr = when (node.visibility) {
                    View.VISIBLE -> "V"
                    View.GONE -> "G"
                    View.INVISIBLE -> "I"
                    else -> "${node.visibility}"
                }
                val transY = runCatching { node.translationY }.getOrDefault(Float.NaN)
                AndroidLog.i(TAG, "strip F23 steady-parent[$depth] cls=${node.javaClass.name} " +
                    "y=${nloc[1]} h=$nh meas=${nmw}x${nmh} vis=$visStr " +
                    "lpH=$lpHStr lpW=$lpWStr pad=[${node.paddingLeft},${node.paddingTop}," +
                    "${node.paddingRight},${node.paddingBottom}] mT=${mg?.topMargin} mB=${mg?.bottomMargin} " +
                    "transY=$transY specH=$specH")
                if (suspect == null && lpH > 0) {
                    val isWindowSized = windowBar > 0 && kotlin.math.abs(nh - windowBar) <= 8
                    val is280 = kotlin.math.abs(nh - STRIP_FIXED_280_PX) <= STRIP_FIXED_280_TOL_PX ||
                        kotlin.math.abs(lpH - STRIP_FIXED_280_PX) <= STRIP_FIXED_280_TOL_PX
                    if (isWindowSized || is280) {
                        suspect = node
                        AndroidLog.i(TAG, "strip F23 steady-parent suspect: depth=$depth " +
                            "cls=${node.javaClass.name} lpH=$lpHStr h=$nh windowBar=$windowBar " +
                            "(定值/被N三连publish写死候选)")
                    }
                }
                if (node === decor) break
                val parent = node.parent as? ViewGroup ?: break
                node = parent
                depth++
            }
            if (suspect != null) {
                AndroidLog.i(TAG, "strip F23 steady-parent fixed: ${suspect.javaClass.name} " +
                    "lpH=${suspect.layoutParams?.height} h=${suspect.height} windowBar=$windowBar target=$target")
            } else {
                AndroidLog.i(TAG, "strip F23 steady-parent fixed: none (全WRAP/MATCH，无280定值层)")
            }
            suspect
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F23 steady-parent dump failed: $t")
            null
        }
    }

    /**
     * F23稳态定高修（按稳态定案单步）：定高层LP.height显式=target≈192（条真高+顶1.5dp+底20现算，
     * 现算不写死192/167），记账退壳还账，requestLayout+300ms复测windowBar，真缩delta>=4才留，
     * 否则还账试N-refresh调参（跳过N三连，二选一以windowBar为准）。
     * 只动定高层LP高，不碰顶底槽栏键/壳/s0/圆角B/DEL/commit/logo。禁盲累加。
     */
    private fun tryFixFixedParentSteadyF23(row: View, decor: ViewGroup) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            val slot = row.parent as? ViewGroup ?: run {
                AndroidLog.e(TAG, "strip F23 fixed: no slot parent")
                return
            }
            if (slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null) return
            // 跨轮验证中则等下轮（禁叠加）。
            if (stripFixedParentLastViewF23 != null) {
                AndroidLog.i(TAG, "strip F23 fixed: verify pending, skip new apply")
                return
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            if (winBar == null) {
                AndroidLog.e(TAG, "strip F23 fixed: windowBar unmeasurable (unlaid)")
                return
            }
            val before = winBar.third
            val target = runCatching { slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F23 fixed: target unmeasurable (trueH unlaid)")
                return
            }
            if (target <= 0) {
                AndroidLog.e(TAG, "strip F23 fixed: target invalid=$target")
                return
            }
            // 已收敛（192±4）则不动。
            if (before in (target - STRIP_WINDOW_BAR_TOL_PX)..(target + STRIP_WINDOW_BAR_TOL_PX)) {
                AndroidLog.i(TAG, "strip F23 fixed: windowBar pass before=$before target=$target(192±4) no fix")
                return
            }
            val suspect = runCatching { dumpSlotParentChainSteadyF23(row, decor) }.getOrNull()
            if (suspect == null) {
                AndroidLog.e(TAG, "strip F23 fixed: no suspect (no 280定值层) before=$before target=$target")
                return
            }
            // fail-closed：疑犯须含slot（定高层须为slot祖先），且非bar子树、非我条子树。
            if (!isAncestorOf(suspect, slot) && suspect !== slot) {
                AndroidLog.e(TAG, "strip F23 fixed: suspect not ancestor of slot " +
                    "suspect=${suspect.javaClass.name} (fail-closed)")
                return
            }
            if (suspect.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null &&
                suspect !== slot
            ) {
                // 疑犯链上无条（抓错分支），fail-closed。
                var hasSlot = false
                try {
                    var p: android.view.ViewParent? = slot
                    var g = 0
                    while (p is ViewGroup && g < 10) {
                        if (p === suspect) { hasSlot = true; break }
                        p = p.parent
                        g++
                    }
                } catch (_: Throwable) {
                }
                if (!hasSlot) {
                    AndroidLog.e(TAG, "strip F23 fixed: suspect off-chain " +
                        "suspect=${suspect.javaClass.name} (fail-closed)")
                    return
                }
            }
            val tried = synchronized(stripFixedParentTriedF23) { stripFixedParentTriedF23.contains(suspect) }
            if (tried) {
                AndroidLog.i(TAG, "strip F23 fixed: suspect blacklisted ${suspect.javaClass.name} skip")
                return
            }
            val already = synchronized(stripFixedParentOrigF23) { stripFixedParentOrigF23.containsKey(suspect) }
            if (already) {
                AndroidLog.i(TAG, "strip F23 fixed: already trimmed ${suspect.javaClass.name} wait verify")
                return
            }
            // slot自身已是显式192（F16），再显式同值无意义则跳过（定高须在其祖先）。
            if (suspect === slot) {
                AndroidLog.i(TAG, "strip F23 fixed: suspect is slot itself (already 192±3) skip, need ancestor")
                return
            }
            val lp = suspect.layoutParams ?: run {
                AndroidLog.e(TAG, "strip F23 fixed: suspect LP null")
                return
            }
            val origH = lp.height
            // 仅定值层才覆盖（WRAP/MATCH不动，防误收敛）。
            if (origH <= 0) {
                AndroidLog.e(TAG, "strip F23 fixed: suspect lpH not fixed ($origH) skip " +
                    "suspect=${suspect.javaClass.name}")
                return
            }
            synchronized(stripFixedParentOrigF23) { stripFixedParentOrigF23[suspect] = origH }
            lp.height = target
            suspect.layoutParams = lp
            runCatching { suspect.requestLayout() }
            runCatching { slot.requestLayout() }
            (slot.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
            runCatching { row.requestLayout() }
            stripFixedParentLastViewF23 = suspect
            stripFixedParentLastBeforeF23 = before
            stripFixedParentLastTargetF23 = target
            AndroidLog.i(TAG, "strip F23 fixed apply: ${suspect.javaClass.name} lpH $origH->$target " +
                "beforeWindowBar=$before target=$target(条真高+顶1.5dp+底20现算) " +
                "(verify 300ms delta>=${STRIP_FIXED_DELTA_KEEP_PX}留否则还账试N-refresh，顶底槽栏键不动)")
            runCatching { scheduleFixedParentVerifyF23(row, decor, suspect, before, target) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F23 fixed failed: $t")
        }
    }

    /** F23单步复测（300ms后delta>=4留，否则还账试N-refresh调参；禁盲累加）。 */
    private fun scheduleFixedParentVerifyF23(
        row: View,
        decor: ViewGroup,
        appliedView: View,
        beforeWindowBar: Int,
        target: Int
    ) {
        try {
            row.postDelayed({
                try {
                    val bv = stripFixedParentLastViewF23
                    if (bv == null || bv !== appliedView) return@postDelayed
                    val after = runCatching { measureWindowToBarF20(row, decor)?.third }.getOrNull()
                    if (after == null || beforeWindowBar <= 0) {
                        stripFixedParentLastViewF23 = null
                        stripFixedParentLastBeforeF23 = -1
                        stripFixedParentLastTargetF23 = -1
                        return@postDelayed
                    }
                    val delta = beforeWindowBar - after
                    val distBefore = kotlin.math.abs(beforeWindowBar - target)
                    val distAfter = kotlin.math.abs(after - target)
                    if (delta >= STRIP_FIXED_DELTA_KEEP_PX && distAfter < distBefore) {
                        AndroidLog.i(TAG, "strip F23 fixed verdict=KEEP: ${bv.javaClass.name} " +
                            "windowBar $beforeWindowBar->$after delta=$delta(>=4真缩) " +
                            "dist $distBefore->$distAfter target=$target")
                        stripFixedParentLastViewF23 = null
                        stripFixedParentLastBeforeF23 = -1
                        stripFixedParentLastTargetF23 = -1
                        runCatching { logWindowToBarF20(row, decor, "F23-fixed-keep") }
                    } else {
                        runCatching { revertFixedParentViewF23(bv) }
                        synchronized(stripFixedParentTriedF23) { stripFixedParentTriedF23.add(bv) }
                        val afterRevert = runCatching { measureWindowToBarF20(row, decor)?.third }?.getOrNull() ?: after
                        AndroidLog.e(TAG, "strip F23 fixed verdict=REVERT: ${bv.javaClass.name} " +
                            "windowBar $beforeWindowBar->$after delta=$delta(<4或未近目标) " +
                            "dist $distBefore->$distAfter target=$target afterRevert=$afterRevert " +
                            "(还账试N-refresh调参)")
                        stripFixedParentLastViewF23 = null
                        stripFixedParentLastBeforeF23 = -1
                        stripFixedParentLastTargetF23 = -1
                        runCatching { row.requestLayout() }
                        runCatching { tryNRefreshParamF23(row, decor, beforeWindowBar, after, target) }
                        runCatching { logWindowToBarF20(row, decor, "F23-fixed-revert") }
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F23 fixed verify failed: $t")
                }
            }, POST_STABLE_DELAY_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F23 fixed verify schedule failed: $t")
        }
    }

    /** F23单视图还账（幂等，只撤销我方增量）。 */
    private fun revertFixedParentViewF23(v: View) {
        try {
            val orig = synchronized(stripFixedParentOrigF23) { stripFixedParentOrigF23.remove(v) }
            if (orig != null) {
                runCatching {
                    val lp = v.layoutParams
                    if (lp != null && lp.height != orig) {
                        lp.height = orig
                        v.layoutParams = lp
                        runCatching { v.requestLayout() }
                    }
                }
                AndroidLog.i(TAG, "strip F23 fixed reverted: ${v.javaClass.name} ->$orig")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F23 fixed revert failed: $t")
        }
    }

    /** F23退壳还账（幂等）：定高层LP高逐个还+跨轮状态清，只撤销我方增量。 */
    private fun restoreFixedParentF23() {
        try {
            var n = 0
            stripFixedParentLastViewF23 = null
            stripFixedParentLastBeforeF23 = -1
            stripFixedParentLastTargetF23 = -1
            stripSkipNTripleF23 = false
            synchronized(stripFixedParentOrigF23) {
                val it = stripFixedParentOrigF23.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        val lp = e.key.layoutParams
                        if (lp != null) {
                            lp.height = e.value
                            e.key.layoutParams = lp
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripFixedParentTriedF23) {
                if (stripFixedParentTriedF23.isNotEmpty()) stripFixedParentTriedF23.clear()
            }
            if (n > 0) AndroidLog.i(TAG, "strip F23 fixed restored n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F23 fixed restore failed: $t")
        }
    }

    /**
     * F23 N-refresh调参（显式LP不动时的二选一）：跳过N三连（只A2/e0+float，不调N2重算窗高），
     * 看窗高是否回192，二选一以windowBar（距target距离）为准。
     * 只读验+原生刷新，不碰顶底槽栏键/壳/s0/圆角B/DEL/commit/logo。
     */
    private fun tryNRefreshParamF23(
        row: View,
        decor: ViewGroup,
        beforeExplicit: Int,
        afterExplicit: Int?,
        target: Int
    ) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            if (row.parent == null) return
            val base = runCatching { measureWindowToBarF20(row, decor)?.third }.getOrNull()
                ?: beforeExplicit
            stripSkipNTripleF23 = true
            val trueH = runCatching { stripTrueHeightOfF16(row) }.getOrNull() ?: -1
            val publishH = if (trueH > 0) {
                runCatching { publishHeightForF20(row, trueH, STRIP_M_BOTTOM_PX) }.getOrNull() ?: target
            } else target
            runCatching { publishStripHeight(row, publishH, "F23-noNtriple") }
            runCatching { refreshCandidateLayoutNoN2F23(row, "F23-noNtriple") }
            runCatching { refreshFloatWindow(row, "F23-noNtriple") }
            AndroidLog.i(TAG, "strip F23 N-refresh try: skip N-triple (A2/e0 only, no N2) " +
                "publishH=$publishH base=$base target=$target afterExplicit=$afterExplicit " +
                "(verify 300ms二选一以windowBar为准，顶底槽栏键不动)")
            row.postDelayed({
                try {
                    val afterNoN = runCatching { measureWindowToBarF20(row, decor)?.third }.getOrNull()
                    if (afterNoN != null) {
                        val distExplicit = if (afterExplicit != null && afterExplicit > 0) {
                            kotlin.math.abs(afterExplicit - target)
                        } else kotlin.math.abs(base - target)
                        val distNoN = kotlin.math.abs(afterNoN - target)
                        val winner = if (distNoN < distExplicit) "noNtriple" else "explicit-or-orig"
                        AndroidLog.i(TAG, "strip F23 N-refresh verdict: afterExplicit=$afterExplicit " +
                            "afterNoN=$afterNoN target=$target distExp=$distExplicit distNoN=$distNoN " +
                            "winner=$winner(以windowBar为准)")
                        runCatching { logWindowToBarF20(row, decor, "F23-noNtriple-verify") }
                    }
                    stripSkipNTripleF23 = false
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F23 N-refresh verify failed: $t")
                    stripSkipNTripleF23 = false
                }
            }, POST_STABLE_DELAY_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F23 N-refresh try failed: $t")
            stripSkipNTripleF23 = false
        }
    }

    /**
     * F23无N2刷新（A2+e0 only，跳过N2→M2→Q2窗高重算）：与refreshCandidateLayout同参同序，
     * 仅去N2，用于二选一验证窗高是否被N三连写死280。缺失只记日志，不拆条。
     */
    private fun refreshCandidateLayoutNoN2F23(anchor: View, reason: String) {
        try {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                mainHandler.post { refreshCandidateLayoutNoN2F23(anchor, reason) }
                return
            }
            val loaders = ArrayList<ClassLoader?>()
            hostClassLoader?.let { hcl ->
                runCatching { loaders.addAll(idClassLoaders(anchor, hcl)) }
            }
            runCatching { loaders.add(Thread.currentThread().contextClassLoader) }
            runCatching { loaders.add(anchor.context?.classLoader) }
            var nCls: Class<*>? = null
            for (cl in loaders) {
                if (cl == null) continue
                nCls = runCatching { Class.forName(NATIVE_CAND_CTL_CLASS, false, cl) }.getOrNull()
                if (nCls != null) break
            }
            val nClass = nCls ?: run {
                AndroidLog.e(TAG, "strip F23 noN2 refresh: N missing ($reason)")
                return
            }
            val inst = nClass.declaredFields
                .firstOrNull { it.type == nClass }
                ?.also { it.isAccessible = true }
                ?.get(null) ?: run {
                    AndroidLog.e(TAG, "strip F23 noN2 refresh: N singleton missing ($reason)")
                    return
                }
            var okA2 = true
            var okE0 = true
            runCatching {
                val a2 = nClass.declaredMethods.firstOrNull {
                    it.name == "A2" && it.parameterTypes.isEmpty()
                } ?: throw NoSuchMethodException("A2")
                a2.isAccessible = true
                a2.invoke(inst)
            }.onFailure {
                okA2 = false
                AndroidLog.e(TAG, "strip F23 noN2 refresh: A2 failed ($reason): $it")
            }
            runCatching {
                val e0 = nClass.declaredMethods.firstOrNull {
                    it.name == "e0" && it.parameterTypes.isEmpty()
                } ?: throw NoSuchMethodException("e0")
                e0.isAccessible = true
                e0.invoke(inst)
            }.onFailure {
                okE0 = false
                AndroidLog.e(TAG, "strip F23 noN2 refresh: e0 failed ($reason): $it")
            }
            AndroidLog.i(TAG, "strip F23 noN2 refresh done A2=$okA2 e0=$okE0 skipN2=true ($reason)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F23 noN2 refresh failed ($reason): $t")
        }
    }

    // F24（接F23稳态父链已合入未提交，在此基础上改，不reset）：C27铁证（以此为准）：
    // slot→decor父链无280定值层；ImeCandidateView h439 lpH439 EXACTLY；ImeRootView h1263 lpH1263 EXACTLY；
    // windowBar=280 FAIL；slotH192/顶5/零漂移PASS；底152 FAIL inter88。
    // 修1)稳态5秒后dump ImeCandidateView直属孩子（类名/bounds/高/visibility/LP，只读），看439=哪些孩子之和，
    // 88px对应哪个孩子，日志steady-kids；空占位孩子（高≈88）记账GONE验证（delta>=4留否则还账）。
    // 2)N刷新A/B对照（与1独立，先后验）：A=现状publish192+N三连+float；B=publish192+跳过N2只A2/e0+float。
    // 稳态5秒后先A量windowBar，再B量windowBar（记账可逆），哪个近192留哪个，日志N-winner。退壳全还。
    // 3)顶4~7/底19~21/工具栏键盘钉死；壳/s0/圆角B/DEL/commit/logo/退壳全还全不动。
    private const val STRIP_KIDS88_PX = 88
    private const val STRIP_KIDS88_TOL_PX = 8
    private const val STRIP_KIDS_DELTA_KEEP_PX = 4
    /** F24空占位孩子记账（只GONE高≈88空占位直属孩子，退壳全还；不动LP高/边/位移/条/工具栏/键盘/壳/s0/圆角B/DEL/commit/logo）。 */
    private val stripKidsVisOrigF24: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** F24已验不动黑名单（Weak防泄漏；宿主重置致复活亦跳过不再盲试）。 */
    private val stripKidsTriedF24: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    @Volatile
    private var stripKidLastViewF24: View? = null
    @Volatile
    private var stripKidLastBeforeF24: Int = -1
    @Volatile
    private var stripKidLastTargetF24: Int = -1
    /** F24 N刷新A/B对照状态（与1独立；只记winner/running，不动视图LP，记账可逆，退壳全还）。 */
    @Volatile
    private var stripNABRunningF24: Boolean = false
    @Volatile
    private var stripNABWinnerF24: String? = null

    /**
     * F24找ImeCandidateView（只读）：优先row上行4层内类名含ImeCandidateView者（即窗灰顶cand同源），
     * 找不到再BFS decor首个含ImeCandidateView且高>0者。返ViewGroup（直属孩子可数），无则null。
     * 只读几何+类名，不碰顶4~7/底19~21/工具栏键盘/壳/s0/圆角B/DEL/commit/logo。
     */
    private fun findImeCandidateViewF24(row: View, decor: ViewGroup): ViewGroup? {
        return try {
            var cur: android.view.ViewParent? = row.parent
            var guard = 0
            while (cur is ViewGroup && guard < 5) {
                if (cur.javaClass.name.contains("ImeCandidateView")) return cur
                if (cur === decor) break
                cur = cur.parent
                guard++
            }
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            var hops = 0
            while (q.isNotEmpty() && hops < 600) {
                val v = q.removeFirst()
                hops++
                if (v is ViewGroup && v !== decor && v.javaClass.name.contains("ImeCandidateView")) {
                    val h = v.height.takeIf { it > 0 } ?: v.measuredHeight
                    if (h > 0) return v
                }
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * F24稳态kids dump（只读不写，先定案）：ImeCandidateView自身（h439/lpH439/EXACTLY）+直属孩子
     * （类名/bounds/高/visibility/LP）+439求和归属+88px归属+windowBar/inter/slotH对照。
     * 日志关键字steady-kids。任一未布局记诊断返null（不拦修，修侧复测为准）。
     */
    private fun dumpImeCandidateKidsSteadyF24(row: View, decor: ViewGroup): ViewGroup? {
        return try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                AndroidLog.e(TAG, "strip F24 steady-kids: row tag mismatch")
                return null
            }
            if (row.parent == null) {
                AndroidLog.e(TAG, "strip F24 steady-kids: row detached")
                return null
            }
            val cand = findImeCandidateViewF24(row, decor) ?: run {
                AndroidLog.e(TAG, "strip F24 steady-kids: ImeCandidateView missing")
                return null
            }
            val cloc = IntArray(2)
            runCatching { cand.getLocationOnScreen(cloc) }
            val candH = cand.height.takeIf { it > 0 } ?: cand.measuredHeight
            val candLpH = cand.layoutParams?.height ?: -9999
            val candLpStr = when (candLpH) {
                ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
                ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                else -> "$candLpH"
            }
            val candVisStr = when (cand.visibility) {
                View.VISIBLE -> "V"
                View.GONE -> "G"
                View.INVISIBLE -> "I"
                else -> "${cand.visibility}"
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            val windowBar = winBar?.third ?: -1
            val target = runCatching { slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX) }.getOrNull() ?: -1
            val slot = row.parent as? ViewGroup
            val slotH = slot?.height?.takeIf { it > 0 } ?: slot?.measuredHeight ?: -1
            val inter = runCatching { measureInterGapF19(row, decor)?.third }.getOrNull()
            val rootView = runCatching {
                val q: ArrayDeque<View> = ArrayDeque()
                q.add(decor)
                var hops = 0
                var hit: View? = null
                while (q.isNotEmpty() && hops < 600) {
                    val v = q.removeFirst()
                    hops++
                    if (v.javaClass.name.contains("ImeRootView")) { hit = v; break }
                    if (v is ViewGroup) {
                        for (i in 0 until minOf(v.childCount, 25)) {
                            v.getChildAt(i)?.let { q.add(it) }
                        }
                    }
                }
                hit
            }.getOrNull()
            val rootH = (rootView?.height?.takeIf { it > 0 } ?: (rootView as? ViewGroup)?.measuredHeight) ?: -1
            val rootLpH = rootView?.layoutParams?.height ?: -9999
            AndroidLog.i(TAG, "strip F24 steady-kids: cand=${cand.javaClass.name} y=${cloc[1]} h=$candH(439? ${candH in 431..447}) " +
                "lpH=$candLpStr vis=$candVisStr kids=${cand.childCount} " +
                "slotH=$slotH(target≈192) windowBar=$windowBar(target≈192±4) inter=$inter(88?) " +
                "ImeRoot h=$rootH lpH=$rootLpH(1263? ${rootH in 1255..1271})")
            var sumVisH = 0
            var sumVisHMargin = 0
            var hit88Idx = -1
            for (i in 0 until cand.childCount) {
                val kid = cand.getChildAt(i) ?: continue
                val kloc = IntArray(2)
                runCatching { kid.getLocationOnScreen(kloc) }
                val kh = kid.height.takeIf { it > 0 } ?: kid.measuredHeight
                val kw = kid.width.takeIf { it > 0 } ?: kid.measuredWidth
                val visStr = when (kid.visibility) {
                    View.VISIBLE -> "V"
                    View.GONE -> "G"
                    View.INVISIBLE -> "I"
                    else -> "${kid.visibility}"
                }
                val lp = kid.layoutParams
                val lpH = lp?.height ?: -9999
                val lpHStr = when (lpH) {
                    ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
                    ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                    else -> "$lpH"
                }
                val mg = lp as? ViewGroup.MarginLayoutParams
                val empty = runCatching { isEmptySiblingForF17(kid) }.getOrDefault(false)
                val is88 = kh in (STRIP_KIDS88_PX - STRIP_KIDS88_TOL_PX)..(STRIP_KIDS88_PX + STRIP_KIDS88_TOL_PX)
                if (is88 && hit88Idx < 0) hit88Idx = i
                if (kid.visibility == View.VISIBLE && kh > 0) {
                    sumVisH += kh
                    sumVisHMargin += kh + (mg?.topMargin ?: 0) + (mg?.bottomMargin ?: 0)
                }
                AndroidLog.i(TAG, "strip F24 steady-kids kid[$i] cls=${kid.javaClass.name} " +
                    "bounds=[${kloc[0]},${kloc[1]},${kloc[0] + kw},${kloc[1] + kh}] h=$kh vis=$visStr " +
                    "lpH=$lpHStr mT=${mg?.topMargin} mB=${mg?.bottomMargin} " +
                    "pad=[${kid.paddingLeft},${kid.paddingTop},${kid.paddingRight},${kid.paddingBottom}] " +
                    "empty=$empty is88=$is88")
            }
            AndroidLog.i(TAG, "strip F24 steady-kids verdict: candH=$candH sumVisH=$sumVisH " +
                "sumVisHMargin=$sumVisHMargin(439=哪些之和看上) hit88Idx=$hit88Idx(88px对应孩子) " +
                "windowBar=$windowBar slotH=$slotH inter=$inter")
            cand
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F24 steady-kids dump failed: $t")
            null
        }
    }

    /**
     * F24空占位GONE（按稳态定案单步）：ImeCandidateView直属孩子中高≈88+empty者记账GONE，
     * requestLayout+300ms复测windowBar，真缩delta>=4且更近192才留，否则还账。
     * 只动该孩子visibility，不碰顶底槽栏键/壳/s0/圆角B/DEL/commit/logo。禁盲累加。
     */
    private fun tryKidGoneSteadyF24(row: View, decor: ViewGroup) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            if (row.parent == null) return
            if (stripKidLastViewF24 != null) {
                AndroidLog.i(TAG, "strip F24 steady-kids: verify pending, skip new apply")
                return
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            if (winBar == null) {
                AndroidLog.e(TAG, "strip F24 steady-kids: windowBar unmeasurable (unlaid)")
                return
            }
            val before = winBar.third
            val target = runCatching { slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F24 steady-kids: target unmeasurable (trueH unlaid)")
                return
            }
            if (target <= 0) {
                AndroidLog.e(TAG, "strip F24 steady-kids: target invalid=$target")
                return
            }
            if (before in (target - STRIP_WINDOW_BAR_TOL_PX)..(target + STRIP_WINDOW_BAR_TOL_PX)) {
                AndroidLog.i(TAG, "strip F24 steady-kids: windowBar pass before=$before target=$target(192±4) no fix")
                return
            }
            val cand = runCatching { dumpImeCandidateKidsSteadyF24(row, decor) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F24 steady-kids: no cand before=$before target=$target")
                return
            }
            val slot = row.parent as? ViewGroup
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
            var pick: View? = null
            for (i in 0 until cand.childCount) {
                val kid = cand.getChildAt(i) ?: continue
                val kh = kid.height.takeIf { it > 0 } ?: kid.measuredHeight
                val childHit = kh in (STRIP_KIDS88_PX - STRIP_KIDS88_TOL_PX)..(STRIP_KIDS88_PX + STRIP_KIDS88_TOL_PX)
                if (!childHit) continue
                if (kid.visibility == View.GONE) continue
                if (kid.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                    kid.getTag() == TAG_SEARCH_BUTTON ||
                    kid.getTag() == TAG_SEARCH_CLEAR ||
                    kid.getTag() == TAG_SEARCH_BOX
                ) continue
                if (slot != null && (kid === slot)) continue
                if (kid is ViewGroup && isAncestorOf(kid, row)) continue
                if (slot != null && kid is ViewGroup && isAncestorOf(kid, slot)) {
                    // slot分支（含我条）保护：不碰，避免拆条。
                    continue
                }
                if (bar != null && (kid === bar)) continue
                if (bar != null && kid is ViewGroup && isAncestorOf(kid, bar)) continue
                if (bar != null && isAncestorOf(bar, kid)) continue
                // 条子树保护：含我条tag即跳过。
                val hasStrip = runCatching {
                    (kid as? ViewGroup)?.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) != null
                }.getOrDefault(false)
                if (hasStrip) continue
                val empty = runCatching { isEmptySiblingForF17(kid) }.getOrDefault(false)
                if (!empty) {
                    AndroidLog.i(TAG, "strip F24 steady-kids skip kid[$i] ${kid.javaClass.name} h=$kh non-empty")
                    continue
                }
                val tried = synchronized(stripKidsTriedF24) { stripKidsTriedF24.contains(kid) }
                if (tried) {
                    AndroidLog.i(TAG, "strip F24 steady-kids skip kid[$i] ${kid.javaClass.name} blacklisted")
                    continue
                }
                val already = synchronized(stripKidsVisOrigF24) { stripKidsVisOrigF24.containsKey(kid) }
                if (already) continue
                pick = kid
                break
            }
            if (pick == null) {
                AndroidLog.e(TAG, "strip F24 steady-kids: no empty ≈88 kid before=$before target=$target")
                return
            }
            val picked: View = pick
            synchronized(stripKidsVisOrigF24) { stripKidsVisOrigF24[picked] = picked.visibility }
            picked.visibility = View.GONE
            runCatching { picked.requestLayout() }
            runCatching { cand.requestLayout() }
            if (slot != null) runCatching { slot.requestLayout() }
            runCatching { row.requestLayout() }
            stripKidLastViewF24 = picked
            stripKidLastBeforeF24 = before
            stripKidLastTargetF24 = target
            AndroidLog.i(TAG, "strip F24 steady-kids apply: ${picked.javaClass.name} h≈88->GONE " +
                "beforeWindowBar=$before target=$target " +
                "(verify 300ms delta>=${STRIP_KIDS_DELTA_KEEP_PX}留否则还账，顶底槽栏键不动)")
            runCatching { scheduleKidVerifyF24(row, decor, picked, before, target) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F24 steady-kids failed: $t")
        }
    }

    /** F24单步复测（300ms后windowBar delta>=4且更近192才留，否则还账；禁盲累加）。 */
    private fun scheduleKidVerifyF24(
        row: View,
        decor: ViewGroup,
        appliedView: View,
        beforeWindowBar: Int,
        target: Int
    ) {
        try {
            row.postDelayed({
                try {
                    val bv = stripKidLastViewF24
                    if (bv == null || bv !== appliedView) return@postDelayed
                    val after = runCatching { measureWindowToBarF20(row, decor)?.third }.getOrNull()
                    if (after == null || beforeWindowBar <= 0) {
                        stripKidLastViewF24 = null
                        stripKidLastBeforeF24 = -1
                        stripKidLastTargetF24 = -1
                        return@postDelayed
                    }
                    val delta = beforeWindowBar - after
                    val distBefore = kotlin.math.abs(beforeWindowBar - target)
                    val distAfter = kotlin.math.abs(after - target)
                    if (delta >= STRIP_KIDS_DELTA_KEEP_PX && distAfter < distBefore) {
                        AndroidLog.i(TAG, "strip F24 steady-kids verdict=KEEP: ${bv.javaClass.name} " +
                            "windowBar $beforeWindowBar->$after delta=$delta(>=4真缩) " +
                            "dist $distBefore->$distAfter target=$target")
                        stripKidLastViewF24 = null
                        stripKidLastBeforeF24 = -1
                        stripKidLastTargetF24 = -1
                        runCatching { logWindowToBarF20(row, decor, "F24-kids-keep") }
                    } else {
                        runCatching { revertKidViewF24(bv) }
                        synchronized(stripKidsTriedF24) { stripKidsTriedF24.add(bv) }
                        val afterRevert = runCatching { measureWindowToBarF20(row, decor)?.third }?.getOrNull() ?: after
                        AndroidLog.e(TAG, "strip F24 steady-kids verdict=REVERT: ${bv.javaClass.name} " +
                            "windowBar $beforeWindowBar->$after delta=$delta(<4或未近目标) " +
                            "dist $distBefore->$distAfter target=$target afterRevert=$afterRevert")
                        stripKidLastViewF24 = null
                        stripKidLastBeforeF24 = -1
                        stripKidLastTargetF24 = -1
                        runCatching { row.requestLayout() }
                        runCatching { logWindowToBarF20(row, decor, "F24-kids-revert") }
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F24 steady-kids verify failed: $t")
                }
            }, POST_STABLE_DELAY_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F24 steady-kids verify schedule failed: $t")
        }
    }

    /** F24单视图还账（幂等，只撤销我方增量）。 */
    private fun revertKidViewF24(v: View) {
        try {
            val orig = synchronized(stripKidsVisOrigF24) { stripKidsVisOrigF24.remove(v) }
            if (orig != null) {
                runCatching {
                    if (v.parent != null && v.visibility != orig) {
                        v.visibility = orig
                        runCatching { v.requestLayout() }
                    }
                }
                AndroidLog.i(TAG, "strip F24 steady-kids reverted: ${v.javaClass.name} ->$orig")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F24 steady-kids revert failed: $t")
        }
    }

    /** F24退壳还账（幂等）：空占位GONE逐个还+跨轮状态清，只撤销我方增量。 */
    private fun restoreKidsSteadyF24() {
        try {
            var n = 0
            stripKidLastViewF24 = null
            stripKidLastBeforeF24 = -1
            stripKidLastTargetF24 = -1
            synchronized(stripKidsVisOrigF24) {
                val it = stripKidsVisOrigF24.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        if (e.key.parent != null && e.key.visibility != e.value) {
                            e.key.visibility = e.value
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripKidsTriedF24) {
                if (stripKidsTriedF24.isNotEmpty()) stripKidsTriedF24.clear()
            }
            if (n > 0) AndroidLog.i(TAG, "strip F24 steady-kids restored n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F24 steady-kids restore failed: $t")
        }
    }

    /**
     * F24 N刷新A/B对照（与1独立，先后验）：A=现状publish192+N三连+float；B=publish192+跳过N2只A2/e0+float。
     * 稳态5秒后先A量windowBar，再B量windowBar（记账可逆：只发publish+原生刷新，不动视图LP），
     * 哪个近192留哪个，日志N-winner。只读验+原生刷新，不碰顶底槽栏键/壳/s0/圆角B/DEL/commit/logo。
     */
    private fun tryNRefreshABSteadyF24(row: View, decor: ViewGroup) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            if (row.parent == null) return
            if (stripNABRunningF24) {
                AndroidLog.i(TAG, "strip F24 N-winner: running, skip new AB")
                return
            }
            val baseMeas = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            if (baseMeas == null) {
                AndroidLog.e(TAG, "strip F24 N-winner: windowBar unmeasurable (unlaid)")
                return
            }
            val base = baseMeas.third
            val target = runCatching { slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F24 N-winner: target unmeasurable (trueH unlaid)")
                return
            }
            if (target <= 0) {
                AndroidLog.e(TAG, "strip F24 N-winner: target invalid=$target")
                return
            }
            if (base in (target - STRIP_WINDOW_BAR_TOL_PX)..(target + STRIP_WINDOW_BAR_TOL_PX)) {
                AndroidLog.i(TAG, "strip F24 N-winner: windowBar pass base=$base target=$target(192±4) no AB")
                return
            }
            val trueH = runCatching { stripTrueHeightOfF16(row) }.getOrNull() ?: -1
            val publishH = if (trueH > 0) {
                runCatching { publishHeightForF20(row, trueH, STRIP_M_BOTTOM_PX) }.getOrNull() ?: target
            } else target
            stripNABRunningF24 = true
            stripNABWinnerF24 = null
            runCatching { publishStripHeight(row, publishH, "F24-A") }
            runCatching { refreshCandidateLayout(row, "F24-A") }
            runCatching { refreshFloatWindow(row, "F24-A") }
            AndroidLog.i(TAG, "strip F24 N-refresh A apply: publish192+N三连+float " +
                "publishH=$publishH base=$base target=$target (verify 300ms再B，顶底槽栏键不动)")
            row.postDelayed({
                try {
                    if (row.parent == null) {
                        stripNABRunningF24 = false
                        return@postDelayed
                    }
                    val afterA = runCatching { measureWindowToBarF20(row, decor)?.third }.getOrNull()
                    if (afterA == null) {
                        AndroidLog.e(TAG, "strip F24 N-refresh A unmeasurable")
                        stripNABRunningF24 = false
                        return@postDelayed
                    }
                    AndroidLog.i(TAG, "strip F24 N-refresh A measured: base=$base afterA=$afterA target=$target")
                    runCatching { publishStripHeight(row, publishH, "F24-B") }
                    runCatching { refreshCandidateLayoutNoN2F23(row, "F24-B") }
                    runCatching { refreshFloatWindow(row, "F24-B") }
                    AndroidLog.i(TAG, "strip F24 N-refresh B apply: publish192+跳过N2只A2/e0+float " +
                        "publishH=$publishH afterA=$afterA target=$target (verify 300ms二选一，顶底槽栏键不动)")
                    row.postDelayed({
                        try {
                            val afterB = runCatching { measureWindowToBarF20(row, decor)?.third }.getOrNull()
                            if (afterB == null || afterA <= 0) {
                                stripNABRunningF24 = false
                                return@postDelayed
                            }
                            val distA = kotlin.math.abs(afterA - target)
                            val distB = kotlin.math.abs(afterB - target)
                            val winner = if (distB < distA) "B(noNtriple)" else "A(Ntriple)"
                            stripNABWinnerF24 = winner
                            AndroidLog.i(TAG, "strip F24 N-winner: base=$base afterA=$afterA afterB=$afterB " +
                                "target=$target distA=$distA distB=$distB winner=$winner(以windowBar为准)")
                            if (winner.startsWith("A")) {
                                runCatching { publishStripHeight(row, publishH, "F24-winner-A") }
                                runCatching { refreshCandidateLayout(row, "F24-winner-A") }
                                runCatching { refreshFloatWindow(row, "F24-winner-A") }
                                AndroidLog.i(TAG, "strip F24 N-winner keep A: re-applied N三连+float " +
                                    "finalWindowBar~$afterA(改前base=$base 改后~$afterA)")
                            } else {
                                AndroidLog.i(TAG, "strip F24 N-winner keep B: already B(noN2)+float " +
                                    "finalWindowBar=$afterB(改前base=$base 改后=$afterB)")
                            }
                            runCatching { logWindowToBarF20(row, decor, "F24-winner-$winner") }
                            stripNABRunningF24 = false
                        } catch (t: Throwable) {
                            AndroidLog.e(TAG, "strip F24 N-winner B verify failed: $t")
                            stripNABRunningF24 = false
                        }
                    }, POST_STABLE_DELAY_MS)
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F24 N-refresh A verify failed: $t")
                    stripNABRunningF24 = false
                }
            }, POST_STABLE_DELAY_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F24 N-winner try failed: $t")
            stripNABRunningF24 = false
        }
    }

    /** F24 N对照退壳还账（幂等）：只清running/winner标志（AB只发publish+原生刷新，不动视图LP，无残留视图账）。 */
    private fun restoreNABSteadyF24() {
        try {
            stripNABRunningF24 = false
            stripNABWinnerF24 = null
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F24 N-winner restore failed: $t")
        }
    }

    // F25（接F24 kids dump+N对照已合入未提交，在此基础上改，不reset）：C28孩子清单（以此为准）：
    // ImeCandidateView y=1113 h=439 kids=10——[0]RecyclerView h140 VISIBLE WRAP非空、[1]FrameLayout h192即slot、
    // [2]FrameLayout h28 INVISIBLE、[3]h0 GONE、[4]RelativeLayout h140 VISIBLE定高、[5]h0 GONE、[6-9]ViewStub GONE。
    // sumVisH=472，hit88Idx=-1。N-winner A/B全280——发布/N/float根本驱动不了窗高，窗高=内容实量。
    // 修：稳态5秒后辨认[0]与[4]内容（只读steady-who先定案）：[0]看adapter项数/首项文字/是否过期候选，
    // [4]看是否含可见工具栏图标/即bar父；搜索态下过期候选列表（无有效候选或与过滤无关）记账GONE，
    // 只藏过期那块，工具栏所在容器绝不动。GONE后requestLayout+300ms复测windowBar，
    // delta>=4且更近192才留，否则还账；连带复测顶4~7/底19~21。退壳全还visibility。
    // 顶底槽栏键钉死；壳/s0/圆角B/DEL/commit/logo全不动。
    private const val STRIP_STALE_DELTA_KEEP_PX = 4
    /** F25过期块记账（只GONE过期候选那块，退壳全还；不动LP高/边/位移/条/工具栏/键盘/壳/s0/圆角B/DEL/commit/logo）。 */
    private val stripStaleVisOrigF25: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** F25已验不动黑名单（Weak防泄漏；宿主重置致复活亦跳过不再盲试）。 */
    private val stripStaleTriedF25: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    @Volatile
    private var stripStaleLastViewF25: View? = null
    @Volatile
    private var stripStaleLastBeforeF25: Int = -1
    @Volatile
    private var stripStaleLastTargetF25: Int = -1

    /** F25 [0]内容快照（只读）：是否Recycler/adapter项数/可见子数/非空文本数/首项文字。 */
    private data class Kid0WhoF25(
        val isRecycler: Boolean,
        val adapterCount: Int,
        val childViews: Int,
        val textCount: Int,
        val firstText: String
    )

    /**
     * F25 [0]内容快照（只读不碰视图）：adapter经getAdapter/getItemCount反射现取（失败记-1），
     * 首项文字取可见子树首个非空TextView（截24字，BFS上限80）。任一异常返空快照（调用方fail-closed）。
     */
    private fun kid0WhoOfF25(kid: View): Kid0WhoF25 {
        return try {
            val cls = kid.javaClass.name
            val isRecycler = cls.contains("RecyclerView")
            var adapterCount = -1
            if (isRecycler) {
                adapterCount = runCatching {
                    val adapter = kid.javaClass.getMethod("getAdapter").invoke(kid)
                        ?: return@runCatching -1
                    val m = adapter.javaClass.getMethod("getItemCount")
                    (m.invoke(adapter) as? Int) ?: -1
                }.getOrDefault(-1)
            }
            val childViews = (kid as? ViewGroup)?.childCount ?: -1
            var textCount = 0
            var firstText = ""
            val vg = kid as? ViewGroup
            if (vg != null) {
                val q: ArrayDeque<View> = ArrayDeque()
                q.add(vg)
                var hops = 0
                while (q.isNotEmpty() && hops < 80) {
                    val n = q.removeFirst()
                    hops++
                    if (n !== vg && n.visibility != View.VISIBLE) continue
                    val tv = n as? android.widget.TextView
                    if (tv != null) {
                        val t = runCatching { tv.text?.toString()?.trim() ?: "" }.getOrDefault("")
                        if (t.isNotEmpty()) {
                            textCount++
                            if (firstText.isEmpty()) firstText = t.take(24)
                        }
                        continue
                    }
                    if (n is ViewGroup) {
                        for (i in 0 until minOf(n.childCount, 25)) {
                            n.getChildAt(i)?.let { q.add(it) }
                        }
                    }
                }
            }
            Kid0WhoF25(isRecycler, adapterCount, childViews, textCount, firstText)
        } catch (_: Throwable) {
            Kid0WhoF25(false, -1, -1, 0, "")
        }
    }

    /** F25工具栏图标计数（只读）：kid子树内可见isToolbarImageLeaf叶子数（BFS上限120，只读）。 */
    private fun toolbarIconsInF25(kid: View): Int {
        return try {
            var n = 0
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(kid)
            var hops = 0
            while (q.isNotEmpty() && hops < 120) {
                val v = q.removeFirst()
                hops++
                if (v !== kid && v.visibility != View.VISIBLE) continue
                if (v !== kid && isToolbarImageLeaf(v)) {
                    n++
                    continue
                }
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            n
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * F25过期判定（只读判据，不碰视图；搜索态=条在位可见由调用方保证）。
     * 过期=无有效候选（adapter==0，或adapter未知且无可见候选文本）或与过滤无关
     * （过滤词非空且首项空/首项与词双向不包含）。证据不足判不过期fail-closed。
     */
    private fun staleExpiredOfF25(who: Kid0WhoF25, keyword: String): Pair<Boolean, String> {
        return try {
            if (who.adapterCount == 0) {
                Pair(true, "adapter空无有效候选")
            } else if (who.adapterCount < 0 && who.textCount == 0) {
                Pair(true, "adapter未知且无可见候选文本")
            } else if (keyword.isNotEmpty()) {
                if (who.firstText.isEmpty()) {
                    Pair(true, "首项空与过滤词无关 kwLen=${keyword.length}")
                } else if (!who.firstText.contains(keyword) && !keyword.contains(who.firstText)) {
                    Pair(true, "首项与过滤词无关 first='${who.firstText}' kwLen=${keyword.length}")
                } else {
                    Pair(false, "首项命中过滤词 first='${who.firstText}' kwLen=${keyword.length}")
                }
            } else {
                Pair(false, "有候选且无过滤词无法定过期 adapter=${who.adapterCount} texts=${who.textCount}")
            }
        } catch (_: Throwable) {
            Pair(false, "判定异常fail-closed")
        }
    }

    /**
     * F25稳态who dump（只读不写，先定案）：[0]内容（adapter项数/首项文字/是否过期候选）
     * +[4]归属（是否含可见工具栏图标/即bar父）+slot序号交叉+windowBar/顶底基线。
     * 日志关键字steady-who。任一未布局记诊断返null（不拦修，修侧复测为准）。
     */
    private fun dumpStaleWhoSteadyF25(row: View, decor: ViewGroup): ViewGroup? {
        return try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                AndroidLog.e(TAG, "strip F25 steady-who: row tag mismatch")
                return null
            }
            if (row.parent == null) {
                AndroidLog.e(TAG, "strip F25 steady-who: row detached")
                return null
            }
            val cand = findImeCandidateViewF24(row, decor) ?: run {
                AndroidLog.e(TAG, "strip F25 steady-who: ImeCandidateView missing")
                return null
            }
            val cloc = IntArray(2)
            runCatching { cand.getLocationOnScreen(cloc) }
            val candH = cand.height.takeIf { it > 0 } ?: cand.measuredHeight
            val kids = cand.childCount
            val slot = row.parent as? ViewGroup
            var slotIdx = -1
            if (slot != null) {
                for (i in 0 until kids) {
                    if (cand.getChildAt(i) === slot) {
                        slotIdx = i
                        break
                    }
                }
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            val windowBar = winBar?.third ?: -1
            val target = runCatching { slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX) }.getOrNull() ?: -1
            AndroidLog.i(TAG, "strip F25 steady-who: cand=${cand.javaClass.name} y=${cloc[1]} h=$candH " +
                "kids=$kids slotIdx=$slotIdx(C28=[1]?) windowBar=$windowBar(target≈192±4) target=$target")
            if (kids <= 4) {
                AndroidLog.e(TAG, "strip F25 steady-who: kids=${kids}无[4]无法辨认(need>=5)")
                return cand
            }
            val keyword = runCatching {
                (row.findViewWithTag<View>(TAG_SEARCH_BOX) as? EditText)?.text?.toString() ?: ""
            }.getOrDefault("")
            val k0 = cand.getChildAt(0)
            if (k0 == null) {
                AndroidLog.e(TAG, "strip F25 steady-who: [0] null")
            } else {
                val kloc = IntArray(2)
                runCatching { k0.getLocationOnScreen(kloc) }
                val kh = k0.height.takeIf { it > 0 } ?: k0.measuredHeight
                val visStr = when (k0.visibility) {
                    View.VISIBLE -> "V"
                    View.GONE -> "G"
                    View.INVISIBLE -> "I"
                    else -> "${k0.visibility}"
                }
                val lpH = k0.layoutParams?.height ?: -9999
                val lpHStr = when (lpH) {
                    ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
                    ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                    else -> "$lpH"
                }
                val who = kid0WhoOfF25(k0)
                val expired = staleExpiredOfF25(who, keyword)
                val icons0 = toolbarIconsInF25(k0)
                AndroidLog.i(TAG, "strip F25 steady-who [0]: cls=${k0.javaClass.name} " +
                    "y=${kloc[1]} h=$kh vis=$visStr lpH=$lpHStr " +
                    "recycler=${who.isRecycler} adapter=${who.adapterCount} " +
                    "childViews=${who.childViews} texts=${who.textCount} first='${who.firstText}' " +
                    "kwLen=${keyword.length} icons=$icons0 " +
                    "expired=${expired.first}(${expired.second})")
            }
            val k4 = cand.getChildAt(4)
            if (k4 == null) {
                AndroidLog.e(TAG, "strip F25 steady-who: [4] null")
            } else {
                val kloc = IntArray(2)
                runCatching { k4.getLocationOnScreen(kloc) }
                val kh = k4.height.takeIf { it > 0 } ?: k4.measuredHeight
                val visStr = when (k4.visibility) {
                    View.VISIBLE -> "V"
                    View.GONE -> "G"
                    View.INVISIBLE -> "I"
                    else -> "${k4.visibility}"
                }
                val lpH = k4.layoutParams?.height ?: -9999
                val lpHStr = when (lpH) {
                    ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
                    ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                    else -> "$lpH"
                }
                val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
                val isBar = bar != null && k4 === bar
                val isBarParent = bar != null && k4 is ViewGroup && isAncestorOf(k4, bar)
                val barInside = bar != null && isAncestorOf(bar, k4)
                val icons4 = toolbarIconsInF25(k4)
                val barSide = isBar || isBarParent || barInside
                AndroidLog.i(TAG, "strip F25 steady-who [4]: cls=${k4.javaClass.name} " +
                    "y=${kloc[1]} h=$kh vis=$visStr lpH=$lpHStr " +
                    "isBar=$isBar isBarParent=$isBarParent barInside=$barInside icons=$icons4 " +
                    "bar=${bar?.javaClass?.name} => barSide=$barSide(绝不动)")
            }
            cand
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F25 steady-who dump failed: $t")
            null
        }
    }

    /**
     * F25藏过期块（按稳态定案单步）：只GONE搜索态过期候选那块——[0]须为RecyclerView且expired，
     * 且[4]须为工具栏侧（即bar/bar父/含bar，C28定案交叉），且[0]自身无任何bar/条/slot关联
     * 且无可见工具栏图标；工具栏所在容器绝不动。requestLayout+300ms复测windowBar，
     * delta>=4且更近192才留，否则还账；连带复测顶4~7/底19~21。
     * 只动该块visibility，不碰顶底槽栏键/壳/s0/圆角B/DEL/commit/logo。禁盲累加。
     */
    private fun tryHideStaleSteadyF25(row: View, decor: ViewGroup) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            if (row.parent == null) return
            if (stripStaleLastViewF25 != null) {
                AndroidLog.i(TAG, "strip F25 steady-who: verify pending, skip new apply")
                return
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            if (winBar == null) {
                AndroidLog.e(TAG, "strip F25 steady-who: windowBar unmeasurable (unlaid)")
                return
            }
            val before = winBar.third
            val target = runCatching { slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F25 steady-who: target unmeasurable (trueH unlaid)")
                return
            }
            if (target <= 0) {
                AndroidLog.e(TAG, "strip F25 steady-who: target invalid=$target")
                return
            }
            if (before in (target - STRIP_WINDOW_BAR_TOL_PX)..(target + STRIP_WINDOW_BAR_TOL_PX)) {
                AndroidLog.i(TAG, "strip F25 steady-who: windowBar pass before=$before target=$target(192±4) no fix")
                return
            }
            val cand = runCatching { dumpStaleWhoSteadyF25(row, decor) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F25 steady-who: no cand before=$before target=$target")
                return
            }
            if (cand.childCount <= 4) {
                AndroidLog.e(TAG, "strip F25 steady-who: kids=${cand.childCount}无[4]不藏 before=$before target=$target")
                return
            }
            val k0 = cand.getChildAt(0) ?: run {
                AndroidLog.e(TAG, "strip F25 steady-who: [0] null不藏 before=$before target=$target")
                return
            }
            val k4 = cand.getChildAt(4)
            val slot = row.parent as? ViewGroup
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
            // [4]工具栏侧定案（C28交叉）：即bar/bar父/含bar任一即bar侧，否则fail-closed不藏。
            val k4BarSide = k4 != null && bar != null &&
                (k4 === bar || (k4 is ViewGroup && isAncestorOf(k4, bar)) || isAncestorOf(bar, k4))
            if (!k4BarSide) {
                AndroidLog.e(TAG, "strip F25 steady-who: [4]非bar侧不藏 " +
                    "[4]=${k4?.javaClass?.name} bar=${bar?.javaClass?.name} before=$before target=$target")
                return
            }
            // [0]内容定案：须RecyclerView且expired。
            val keyword = runCatching {
                (row.findViewWithTag<View>(TAG_SEARCH_BOX) as? EditText)?.text?.toString() ?: ""
            }.getOrDefault("")
            val who = kid0WhoOfF25(k0)
            if (!who.isRecycler) {
                AndroidLog.e(TAG, "strip F25 steady-who: [0]非Recycler不藏 cls=${k0.javaClass.name}")
                return
            }
            val expired = staleExpiredOfF25(who, keyword)
            if (!expired.first) {
                AndroidLog.e(TAG, "strip F25 steady-who: [0]未过期不藏 adapter=${who.adapterCount} " +
                    "first='${who.firstText}' kwLen=${keyword.length}(${expired.second})")
                return
            }
            // [0]自身保护：占位中（非GONE且有高）、非条/slot/bar三方、子树无条、无可见工具栏图标。
            val k0h = k0.height.takeIf { it > 0 } ?: k0.measuredHeight
            if (k0.visibility == View.GONE || k0h <= 0) {
                AndroidLog.e(TAG, "strip F25 steady-who: [0]无占位不藏 vis=${k0.visibility} h=$k0h")
                return
            }
            if (k0.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                k0.getTag() == TAG_SEARCH_BUTTON ||
                k0.getTag() == TAG_SEARCH_CLEAR ||
                k0.getTag() == TAG_SEARCH_BOX
            ) {
                AndroidLog.e(TAG, "strip F25 steady-who: [0]涉条不藏")
                return
            }
            if (slot != null && k0 === slot) {
                AndroidLog.e(TAG, "strip F25 steady-who: [0]即slot不藏")
                return
            }
            if (k0 is ViewGroup && isAncestorOf(k0, row)) {
                AndroidLog.e(TAG, "strip F25 steady-who: [0]含条不藏")
                return
            }
            if (slot != null && k0 is ViewGroup && isAncestorOf(k0, slot)) {
                AndroidLog.e(TAG, "strip F25 steady-who: [0]含slot分支不藏")
                return
            }
            if (bar != null && k0 === bar) {
                AndroidLog.e(TAG, "strip F25 steady-who: [0]即bar不藏")
                return
            }
            if (bar != null && k0 is ViewGroup && isAncestorOf(k0, bar)) {
                AndroidLog.e(TAG, "strip F25 steady-who: [0]为bar父不藏")
                return
            }
            if (bar != null && isAncestorOf(bar, k0)) {
                AndroidLog.e(TAG, "strip F25 steady-who: [0]在bar内不藏")
                return
            }
            val hasStrip = runCatching {
                (k0 as? ViewGroup)?.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) != null
            }.getOrDefault(false)
            if (hasStrip) {
                AndroidLog.e(TAG, "strip F25 steady-who: [0]含条tag不藏")
                return
            }
            val icons0 = toolbarIconsInF25(k0)
            if (icons0 != 0) {
                AndroidLog.e(TAG, "strip F25 steady-who: [0]含图标icons=${icons0}疑bar侧不藏")
                return
            }
            val tried = synchronized(stripStaleTriedF25) { stripStaleTriedF25.contains(k0) }
            if (tried) {
                AndroidLog.i(TAG, "strip F25 steady-who: [0] blacklisted skip")
                return
            }
            val already = synchronized(stripStaleVisOrigF25) { stripStaleVisOrigF25.containsKey(k0) }
            if (already) {
                AndroidLog.i(TAG, "strip F25 steady-who: [0] already trimmed wait verify")
                return
            }
            synchronized(stripStaleVisOrigF25) { stripStaleVisOrigF25[k0] = k0.visibility }
            k0.visibility = View.GONE
            runCatching { k0.requestLayout() }
            runCatching { cand.requestLayout() }
            if (slot != null) runCatching { slot.requestLayout() }
            runCatching { row.requestLayout() }
            stripStaleLastViewF25 = k0
            stripStaleLastBeforeF25 = before
            stripStaleLastTargetF25 = target
            AndroidLog.i(TAG, "strip F25 steady-who apply: [0]${k0.javaClass.name} h=$k0h->GONE " +
                "(${expired.second}) [4]bar侧不动 beforeWindowBar=$before target=$target " +
                "(verify 300ms delta>=${STRIP_STALE_DELTA_KEEP_PX}且近192留否则还账，顶底槽栏键不动)")
            runCatching { scheduleStaleVerifyF25(row, decor, k0, before, target) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F25 steady-who failed: $t")
        }
    }

    /** F25单步复测（300ms后windowBar delta>=4且更近192才留，否则还账；连带复测顶4~7/底19~21；禁盲累加）。 */
    private fun scheduleStaleVerifyF25(
        row: View,
        decor: ViewGroup,
        appliedView: View,
        beforeWindowBar: Int,
        target: Int
    ) {
        try {
            row.postDelayed({
                try {
                    val bv = stripStaleLastViewF25
                    if (bv == null || bv !== appliedView) return@postDelayed
                    val after = runCatching { measureWindowToBarF20(row, decor)?.third }.getOrNull()
                    if (after == null || beforeWindowBar <= 0) {
                        stripStaleLastViewF25 = null
                        stripStaleLastBeforeF25 = -1
                        stripStaleLastTargetF25 = -1
                        return@postDelayed
                    }
                    val delta = beforeWindowBar - after
                    val distBefore = kotlin.math.abs(beforeWindowBar - target)
                    val distAfter = kotlin.math.abs(after - target)
                    if (delta >= STRIP_STALE_DELTA_KEEP_PX && distAfter < distBefore) {
                        AndroidLog.i(TAG, "strip F25 steady-who verdict=KEEP: ${bv.javaClass.name} " +
                            "windowBar $beforeWindowBar->$after delta=$delta(>=4真缩) " +
                            "dist $distBefore->$distAfter target=$target")
                        stripStaleLastViewF25 = null
                        stripStaleLastBeforeF25 = -1
                        stripStaleLastTargetF25 = -1
                        runCatching { logWindowToBarF20(row, decor, "F25-stale-keep") }
                        runCatching { logTopBottomSteadyF25(row, decor, "F25-keep") }
                    } else {
                        runCatching { revertStaleViewF25(bv) }
                        synchronized(stripStaleTriedF25) { stripStaleTriedF25.add(bv) }
                        val afterRevert = runCatching { measureWindowToBarF20(row, decor)?.third }?.getOrNull() ?: after
                        AndroidLog.e(TAG, "strip F25 steady-who verdict=REVERT: ${bv.javaClass.name} " +
                            "windowBar $beforeWindowBar->$after delta=$delta(<4或未近目标) " +
                            "dist $distBefore->$distAfter target=$target afterRevert=$afterRevert")
                        stripStaleLastViewF25 = null
                        stripStaleLastBeforeF25 = -1
                        stripStaleLastTargetF25 = -1
                        runCatching { row.requestLayout() }
                        runCatching { logWindowToBarF20(row, decor, "F25-stale-revert") }
                        runCatching { logTopBottomSteadyF25(row, decor, "F25-revert") }
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F25 steady-who verify failed: $t")
                }
            }, POST_STABLE_DELAY_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F25 steady-who verify schedule failed: $t")
        }
    }

    /** F25顶底连带复测（只读）：窗灰顶→条白顶4~7px + 条底→图标顶19~21px。只记日志，不碰视图。 */
    private fun logTopBottomSteadyF25(row: View, decor: ViewGroup, tag: String) {
        try {
            val wm = runCatching { stripWindowTopGap(row) }.getOrNull()
            val topGap = wm?.third
            val topPass = topGap != null && topGap in STRIP_TOP_GAP_MIN_PX..STRIP_TOP_GAP_MAX_PX
            var bottomGap: Int? = null
            var bottomPass = false
            runCatching {
                val logo = resolveLogoView(decor)
                val line = resolveIconLineF22(decor, logo)
                val rloc = IntArray(2)
                row.getLocationOnScreen(rloc)
                if (line != null && line.top > 0 && rloc[1] > 0 && row.height > 0) {
                    bottomGap = (line.top - (rloc[1] + row.height)).toInt()
                    bottomPass = bottomGap in (STRIP_M_BOTTOM_PX - 1)..(STRIP_M_BOTTOM_PX + 1)
                }
            }
            AndroidLog.i(TAG, "strip F25 topbottom [$tag]: topGap=$topGap(4~7 pass=$topPass) " +
                "bottomGap=$bottomGap(19~21 pass=$bottomPass)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F25 topbottom log failed [$tag]: $t")
        }
    }

    /** F25单视图还账（幂等，只撤销我方增量）。 */
    private fun revertStaleViewF25(v: View) {
        try {
            val orig = synchronized(stripStaleVisOrigF25) { stripStaleVisOrigF25.remove(v) }
            if (orig != null) {
                runCatching {
                    if (v.parent != null && v.visibility != orig) {
                        v.visibility = orig
                        runCatching { v.requestLayout() }
                    }
                }
                AndroidLog.i(TAG, "strip F25 steady-who reverted: ${v.javaClass.name} ->$orig")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F25 steady-who revert failed: $t")
        }
    }

    /** F25退壳还账（幂等）：过期块GONE逐个还+跨轮状态清，只撤销我方增量。 */
    private fun restoreStaleWhoF25() {
        try {
            var n = 0
            stripStaleLastViewF25 = null
            stripStaleLastBeforeF25 = -1
            stripStaleLastTargetF25 = -1
            synchronized(stripStaleVisOrigF25) {
                val it = stripStaleVisOrigF25.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        if (e.key.parent != null && e.key.visibility != e.value) {
                            e.key.visibility = e.value
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripStaleTriedF25) {
                if (stripStaleTriedF25.isNotEmpty()) stripStaleTriedF25.clear()
            }
            if (n > 0) AndroidLog.i(TAG, "strip F25 steady-who restored n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F25 steady-who restore failed: $t")
        }
    }

    // F26（接F25 [0]/[4]辨认已合入未提交，在此基础上改，不reset）：C29定案（以此为准）：
    // [0]RecyclerView y=1412 h140 adapter6 icons6=活工具栏（绝不动）；
    // [4]RelativeLayout y=1305 h140 VISIBLE正坐灰缝上（1305~1445，缝1305→1393），
    // isBar/isBarParent/barInside全false、icons=1、barSide=false——F25因“[4]非bar侧”fail-closed没藏。
    // 但[4]位置=缝位置，极可能是过期占位（旧模式工具栏/候选残留）。
    // 修：稳态5秒后深查[4]子树（只读steady-4先定案）：直属孩子类名/文本/visibility/图标数
    // （那1个icons是什么，残留877/占位图现量现记）、有无有效文本/候选词；[0]活工具栏交叉确认
    // （6图标仍在即工具栏活着，[4]更可能是残留）。[4]若无有效文本、无活图标（icons<=1且单图标为
    // 残留877/占位图）、且[0]工具栏活着，则记账GONE[4]，requestLayout+300ms复测windowBar
    // delta>=4且近192才留，否则还账；连带复测顶4~7/底19~21。工具栏[0]绝不动。退壳全还visibility。
    // 顶底槽栏键钉死；壳/s0/圆角B/DEL/commit/logo全不动。
    private const val STRIP_STALE4_DELTA_KEEP_PX = 4
    /** F26残留[4]记账（只GONE过期占位[4]，退壳全还；不动LP高/边/位移/条/[0]工具栏/键盘/壳/s0/圆角B/DEL/commit/logo）。 */
    private val stripStale4VisOrigF26: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** F26已验不动黑名单（Weak防泄漏；宿主重置致复活亦跳过不再盲试）。 */
    private val stripStale4TriedF26: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    @Volatile
    private var stripStale4LastViewF26: View? = null
    @Volatile
    private var stripStale4LastBeforeF26: Int = -1
    @Volatile
    private var stripStale4LastTargetF26: Int = -1

    /** F26 [4]文本快照（只读）：可见非空TextView数/首项文字（截24字，BFS上限80）。 */
    private data class Kid4TextF26(val textCount: Int, val firstText: String)

    /** F26 [4]子树文本（只读不碰视图）：任一异常返空快照（调用方fail-closed）。 */
    private fun kid4TextsOfF26(k4: View): Kid4TextF26 {
        return try {
            val vg = k4 as? ViewGroup ?: return Kid4TextF26(0, "")
            var textCount = 0
            var firstText = ""
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(vg)
            var hops = 0
            while (q.isNotEmpty() && hops < 80) {
                val n = q.removeFirst()
                hops++
                if (n !== vg && (n.visibility != View.VISIBLE || !n.isShown)) continue
                val tv = n as? android.widget.TextView
                if (tv != null) {
                    val t = runCatching { tv.text?.toString()?.trim() ?: "" }.getOrDefault("")
                    if (t.isNotEmpty()) {
                        textCount++
                        if (firstText.isEmpty()) firstText = t.take(24)
                    }
                    continue
                }
                if (n is ViewGroup) {
                    for (i in 0 until minOf(n.childCount, 25)) {
                        n.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            Kid4TextF26(textCount, firstText)
        } catch (_: Throwable) {
            Kid4TextF26(0, "")
        }
    }

    /** F26单图标叶子收集（只读）：k4子树内可见isToolbarImageLeaf叶子列表（BFS上限120，只读）。 */
    private fun singleIconLeavesOfF26(k4: View): List<View> {
        val out = ArrayList<View>()
        try {
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(k4)
            var hops = 0
            while (q.isNotEmpty() && hops < 120) {
                val v = q.removeFirst()
                hops++
                if (v !== k4 && (v.visibility != View.VISIBLE || !v.isShown)) continue
                if (v !== k4 && isToolbarImageLeaf(v)) {
                    out.add(v)
                    continue
                }
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    /** F26单图标详情（只读）：类名/id(dec/hex+entry名)/尺寸/屏位/可见性/描述/drawable类+内禀，供877/占位判定。 */
    private fun iconDetailOfF26(v: View): String {
        return try {
            val loc = IntArray(2)
            runCatching { v.getLocationOnScreen(loc) }
            val w = v.width.takeIf { it > 0 } ?: v.measuredWidth
            val h = v.height.takeIf { it > 0 } ?: v.measuredHeight
            val visStr = when (v.visibility) {
                View.VISIBLE -> "V"
                View.GONE -> "G"
                View.INVISIBLE -> "I"
                else -> "${v.visibility}"
            }
            val idDec = v.id
            val idHex = "0x" + Integer.toHexString(idDec)
            val idName = runCatching {
                v.resources.getResourceEntryName(idDec)
            }.getOrDefault("?")
            val desc = runCatching { v.contentDescription?.toString() ?: "" }.getOrDefault("")
            var drawableStr = "non-Image"
            runCatching {
                val iv = v as? ImageView
                val d = iv?.drawable
                if (d != null) {
                    val iw = runCatching { d.intrinsicWidth }.getOrDefault(-1)
                    val ih = runCatching { d.intrinsicHeight }.getOrDefault(-1)
                    drawableStr = "${d.javaClass.name}(in=$iw" + "x$ih)"
                } else if (iv != null) {
                    drawableStr = "null-drawable"
                }
            }
            "cls=${v.javaClass.name} id=$idDec($idHex/$idName) " +
                "wh=${w}x$h y=${loc[1]} vis=$visStr desc='$desc' drawable=$drawableStr " +
                "is877=${idDec == 877}"
        } catch (t: Throwable) {
            "detail-failed:$t"
        }
    }

    /**
     * F26工具栏存活判定（只读判据，不碰视图；[0]须为活工具栏才衬[4]为残留）。
     * 存活=RecyclerView且VISIBLE且有高且adapter>0且可见图标>=5（C29现量6，容差>=5防漂移）。
     * F25过期判据不适用工具栏（含过滤词时首项空会误判过期），此处不用expired，只用adapter+icons双硬指标。
     */
    private fun isToolbarAliveF26(k0: View, who0: Kid0WhoF25, icons0: Int): Pair<Boolean, String> {
        return try {
            val k0h = k0.height.takeIf { it > 0 } ?: k0.measuredHeight
            if (!who0.isRecycler) return Pair(false, "[0]非Recycler")
            if (k0.visibility != View.VISIBLE || k0h <= 0) {
                return Pair(false, "[0]无占位 vis=${k0.visibility} h=$k0h")
            }
            if (who0.adapterCount <= 0) {
                return Pair(false, "[0]adapter空 adapter=${who0.adapterCount}")
            }
            if (icons0 < 5) {
                return Pair(false, "[0]图标不足 icons=$icons0(need>=5,C29=6)")
            }
            Pair(true, "adapter=${who0.adapterCount} icons=$icons0(C29=6) h=$k0h")
        } catch (_: Throwable) {
            Pair(false, "判定异常fail-closed")
        }
    }

    /**
     * F26稳态[4]深查dump（只读不写，先定案）：[4]直属孩子（类名/文本/visibility/图标）+
     * 单图标详情（残留877/占位图现量）+有无有效文本/候选词+[0]活工具栏交叉（6图标仍在）。
     * 日志关键字steady-4。任一未布局记诊断返null（不拦修，修侧复测为准）。工具栏[0]绝不动。
     */
    private fun dumpStale4SteadyF26(row: View, decor: ViewGroup): ViewGroup? {
        return try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                AndroidLog.e(TAG, "strip F26 steady-4: row tag mismatch")
                return null
            }
            if (row.parent == null) {
                AndroidLog.e(TAG, "strip F26 steady-4: row detached")
                return null
            }
            val cand = findImeCandidateViewF24(row, decor) ?: run {
                AndroidLog.e(TAG, "strip F26 steady-4: ImeCandidateView missing")
                return null
            }
            val cloc = IntArray(2)
            runCatching { cand.getLocationOnScreen(cloc) }
            val candH = cand.height.takeIf { it > 0 } ?: cand.measuredHeight
            val kids = cand.childCount
            val slot = row.parent as? ViewGroup
            var slotIdx = -1
            if (slot != null) {
                for (i in 0 until kids) {
                    if (cand.getChildAt(i) === slot) {
                        slotIdx = i
                        break
                    }
                }
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            val windowBar = winBar?.third ?: -1
            val target = runCatching { slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX) }.getOrNull() ?: -1
            val inter = runCatching { measureInterGapF19(row, decor)?.third }.getOrNull()
            AndroidLog.i(TAG, "strip F26 steady-4: cand=${cand.javaClass.name} y=${cloc[1]} h=$candH " +
                "kids=$kids slotIdx=$slotIdx windowBar=$windowBar(target≈192±4) target=$target inter=$inter")
            if (kids <= 4) {
                AndroidLog.e(TAG, "strip F26 steady-4: kids=${kids}无[4]无法深查(need>=5)")
                return cand
            }
            val keyword = runCatching {
                (row.findViewWithTag<View>(TAG_SEARCH_BOX) as? EditText)?.text?.toString() ?: ""
            }.getOrDefault("")
            // [0]活工具栏交叉确认（绝不动，只读）。
            val k0 = cand.getChildAt(0)
            if (k0 == null) {
                AndroidLog.e(TAG, "strip F26 steady-4: [0] null无法交叉")
            } else {
                val kloc = IntArray(2)
                runCatching { k0.getLocationOnScreen(kloc) }
                val kh = k0.height.takeIf { it > 0 } ?: k0.measuredHeight
                val visStr = when (k0.visibility) {
                    View.VISIBLE -> "V"
                    View.GONE -> "G"
                    View.INVISIBLE -> "I"
                    else -> "${k0.visibility}"
                }
                val who0 = kid0WhoOfF25(k0)
                val icons0 = toolbarIconsInF25(k0)
                val alive = isToolbarAliveF26(k0, who0, icons0)
                AndroidLog.i(TAG, "strip F26 steady-4 [0]live-cross: cls=${k0.javaClass.name} " +
                    "y=${kloc[1]} h=$kh vis=$visStr recycler=${who0.isRecycler} " +
                    "adapter=${who0.adapterCount} childViews=${who0.childViews} " +
                    "texts=${who0.textCount} first='${who0.firstText}' kwLen=${keyword.length} " +
                    "icons=$icons0(C29=6) alive=${alive.first}(${alive.second})绝不动")
            }
            // [4]深查：归属+直属孩子+文本+单图标详情。
            val k4 = cand.getChildAt(4)
            if (k4 == null) {
                AndroidLog.e(TAG, "strip F26 steady-4: [4] null")
            } else {
                val kloc = IntArray(2)
                runCatching { k4.getLocationOnScreen(kloc) }
                val kh = k4.height.takeIf { it > 0 } ?: k4.measuredHeight
                val kw = k4.width.takeIf { it > 0 } ?: k4.measuredWidth
                val visStr = when (k4.visibility) {
                    View.VISIBLE -> "V"
                    View.GONE -> "G"
                    View.INVISIBLE -> "I"
                    else -> "${k4.visibility}"
                }
                val lpH = k4.layoutParams?.height ?: -9999
                val lpHStr = when (lpH) {
                    ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
                    ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                    else -> "$lpH"
                }
                val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
                val isBar = bar != null && k4 === bar
                val isBarParent = bar != null && k4 is ViewGroup && isAncestorOf(k4, bar)
                val barInside = bar != null && bar is ViewGroup && isAncestorOf(bar, k4)
                val icons4 = toolbarIconsInF25(k4)
                val barSide = isBar || isBarParent || barInside
                val texts4 = kid4TextsOfF26(k4)
                AndroidLog.i(TAG, "strip F26 steady-4 [4]: cls=${k4.javaClass.name} " +
                    "y=${kloc[1]} h=$kh w=$kw vis=$visStr lpH=$lpHStr " +
                    "isBar=$isBar isBarParent=$isBarParent barInside=$barInside icons=$icons4 " +
                    "texts=${texts4.textCount} first='${texts4.firstText}' kwLen=${keyword.length} " +
                    "bar=${bar?.javaClass?.name} => barSide=$barSide(C29=false过期占位可藏,真bar侧绝不动)")
                // 直属孩子逐个现量（类名/文本/visibility/图标）。
                val k4g = k4 as? ViewGroup
                if (k4g == null) {
                    AndroidLog.i(TAG, "strip F26 steady-4 [4]kids: 非容器无直属孩子")
                } else {
                    for (i in 0 until k4g.childCount) {
                        val c = k4g.getChildAt(i) ?: continue
                        val cl = IntArray(2)
                        runCatching { c.getLocationOnScreen(cl) }
                        val ch = c.height.takeIf { it > 0 } ?: c.measuredHeight
                        val cw = c.width.takeIf { it > 0 } ?: c.measuredWidth
                        val cVis = when (c.visibility) {
                            View.VISIBLE -> "V"
                            View.GONE -> "G"
                            View.INVISIBLE -> "I"
                            else -> "${c.visibility}"
                        }
                        val cText = runCatching {
                            (c as? android.widget.TextView)?.text?.toString()?.trim()?.take(24) ?: ""
                        }.getOrDefault("")
                        val cIsIcon = runCatching { isToolbarImageLeaf(c) }.getOrDefault(false)
                        val cDetail = if (cIsIcon) " " + iconDetailOfF26(c) else ""
                        AndroidLog.i(TAG, "strip F26 steady-4 [4]kid[$i]: cls=${c.javaClass.name} " +
                            "y=${cl[1]} h=$ch w=$cw vis=$cVis text='$cText' isIcon=$cIsIcon$cDetail")
                    }
                }
                // 单图标是什么：icons==1时把那1个叶子详情打全（残留877/占位图证据）。
                val leaves = singleIconLeavesOfF26(k4)
                if (leaves.size == 1) {
                    AndroidLog.i(TAG, "strip F26 steady-4 [4]single-icon: " + iconDetailOfF26(leaves[0]) +
                        " (残留877/占位图证据，[0]活着即残留)")
                } else {
                    AndroidLog.i(TAG, "strip F26 steady-4 [4]icons-detail: n=${leaves.size} " +
                        leaves.take(3).map { iconDetailOfF26(it) })
                }
            }
            cand
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F26 steady-4 dump failed: $t")
            null
        }
    }

    /**
     * F26藏残留[4]（按稳态定案单步）：[4]须非bar侧且无有效文本且无活图标（icons<=1，
     * 单图标为残留877/占位图现量证据），且[0]须为活工具栏（Recycler+adapter>0+icons>=5，
     * C29=6，绝不动只读交叉）。命中则记账GONE[4]，requestLayout+300ms复测windowBar，
     * delta>=4且更近192才留，否则还账；连带复测顶4~7/底19~21。
     * 只动[4]自身visibility，不碰顶底槽栏键/壳/s0/圆角B/DEL/commit/logo/[0]工具栏。禁盲累加。
     */
    private fun tryHideStale4SteadyF26(row: View, decor: ViewGroup) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            if (row.parent == null) return
            if (stripStale4LastViewF26 != null) {
                AndroidLog.i(TAG, "strip F26 steady-4: verify pending, skip new apply")
                return
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            if (winBar == null) {
                AndroidLog.e(TAG, "strip F26 steady-4: windowBar unmeasurable (unlaid)")
                return
            }
            val before = winBar.third
            val target = runCatching { slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F26 steady-4: target unmeasurable (trueH unlaid)")
                return
            }
            if (target <= 0) {
                AndroidLog.e(TAG, "strip F26 steady-4: target invalid=$target")
                return
            }
            if (before in (target - STRIP_WINDOW_BAR_TOL_PX)..(target + STRIP_WINDOW_BAR_TOL_PX)) {
                AndroidLog.i(TAG, "strip F26 steady-4: windowBar pass before=$before target=$target(192±4) no fix")
                return
            }
            val cand = runCatching { dumpStale4SteadyF26(row, decor) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F26 steady-4: no cand before=$before target=$target")
                return
            }
            if (cand.childCount <= 4) {
                AndroidLog.e(TAG, "strip F26 steady-4: kids=${cand.childCount}无[4]不藏 before=$before target=$target")
                return
            }
            // [0]活工具栏交叉确认（绝不动）：Recycler+adapter>0+icons>=5，任一不满足即fail-closed不藏[4]。
            val k0 = cand.getChildAt(0) ?: run {
                AndroidLog.e(TAG, "strip F26 steady-4: [0] null不藏 before=$before target=$target")
                return
            }
            val who0 = kid0WhoOfF25(k0)
            val icons0 = toolbarIconsInF25(k0)
            val alive0 = isToolbarAliveF26(k0, who0, icons0)
            if (!alive0.first) {
                AndroidLog.e(TAG, "strip F26 steady-4: [0]非活工具栏不藏 ${alive0.second} " +
                    "before=$before target=$target([4]不单动防误杀)")
                return
            }
            // [4]残留确认：VISIBLE有占位 + 非bar侧 + 无有效文本 + 无活图标(icons<=1)。
            val k4 = cand.getChildAt(4) ?: run {
                AndroidLog.e(TAG, "strip F26 steady-4: [4] null不藏 before=$before target=$target")
                return
            }
            val slot = row.parent as? ViewGroup
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
            val isBar = bar != null && k4 === bar
            val isBarParent = bar != null && k4 is ViewGroup && isAncestorOf(k4, bar)
            val barInside = bar != null && bar is ViewGroup && isAncestorOf(bar, k4)
            if (isBar || isBarParent || barInside) {
                AndroidLog.e(TAG, "strip F26 steady-4: [4]为bar侧不藏 " +
                    "isBar=$isBar isBarParent=$isBarParent barInside=$barInside " +
                    "[4]=${k4.javaClass.name} bar=${bar?.javaClass?.name} before=$before target=$target(绝不动)")
                return
            }
            val k4h = k4.height.takeIf { it > 0 } ?: k4.measuredHeight
            if (k4.visibility != View.VISIBLE || k4h <= 0) {
                AndroidLog.e(TAG, "strip F26 steady-4: [4]无占位不藏 vis=${k4.visibility} h=$k4h")
                return
            }
            if (k4.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                k4.getTag() == TAG_SEARCH_BUTTON ||
                k4.getTag() == TAG_SEARCH_CLEAR ||
                k4.getTag() == TAG_SEARCH_BOX
            ) {
                AndroidLog.e(TAG, "strip F26 steady-4: [4]涉条不藏")
                return
            }
            if (slot != null && k4 === slot) {
                AndroidLog.e(TAG, "strip F26 steady-4: [4]即slot不藏")
                return
            }
            if (k4 is ViewGroup && isAncestorOf(k4, row)) {
                AndroidLog.e(TAG, "strip F26 steady-4: [4]含条不藏")
                return
            }
            if (slot != null && k4 is ViewGroup && isAncestorOf(k4, slot)) {
                AndroidLog.e(TAG, "strip F26 steady-4: [4]含slot分支不藏")
                return
            }
            if (bar != null && k4 === bar) {
                AndroidLog.e(TAG, "strip F26 steady-4: [4]即bar不藏")
                return
            }
            val hasStrip = runCatching {
                (k4 as? ViewGroup)?.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) != null
            }.getOrDefault(false)
            if (hasStrip) {
                AndroidLog.e(TAG, "strip F26 steady-4: [4]含条tag不藏")
                return
            }
            val texts4 = kid4TextsOfF26(k4)
            if (texts4.textCount > 0) {
                AndroidLog.e(TAG, "strip F26 steady-4: [4]有有效文本不藏 " +
                    "texts=${texts4.textCount} first='${texts4.firstText}' before=$before target=$target")
                return
            }
            val icons4 = toolbarIconsInF25(k4)
            if (icons4 > 1) {
                AndroidLog.e(TAG, "strip F26 steady-4: [4]有活图标不藏 icons=$icons4(>1疑活) " +
                    "before=$before target=$target")
                return
            }
            // icons==1时记残留877/占位图证据（只记诊断，单图标+无文本+[0]活着即残留可藏）。
            if (icons4 == 1) {
                val leaf = singleIconLeavesOfF26(k4).firstOrNull()
                val detail = leaf?.let { iconDetailOfF26(it) } ?: "leaf-missing"
                val is877 = leaf?.let { it.id == 877 } ?: false
                AndroidLog.i(TAG, "strip F26 steady-4 [4]single-residual: $detail " +
                    "is877=$is877 texts=0 [0]alive(${alive0.second}) => 残留可藏")
            } else {
                AndroidLog.i(TAG, "strip F26 steady-4 [4]no-icon: icons=0 texts=0 " +
                    "[0]alive(${alive0.second}) => 过期占位可藏")
            }
            val tried = synchronized(stripStale4TriedF26) { stripStale4TriedF26.contains(k4) }
            if (tried) {
                AndroidLog.i(TAG, "strip F26 steady-4: [4] blacklisted skip")
                return
            }
            val already = synchronized(stripStale4VisOrigF26) { stripStale4VisOrigF26.containsKey(k4) }
            if (already) {
                AndroidLog.i(TAG, "strip F26 steady-4: [4] already trimmed wait verify")
                return
            }
            synchronized(stripStale4VisOrigF26) { stripStale4VisOrigF26[k4] = k4.visibility }
            k4.visibility = View.GONE
            runCatching { k4.requestLayout() }
            runCatching { cand.requestLayout() }
            if (slot != null) runCatching { slot.requestLayout() }
            runCatching { row.requestLayout() }
            stripStale4LastViewF26 = k4
            stripStale4LastBeforeF26 = before
            stripStale4LastTargetF26 = target
            AndroidLog.i(TAG, "strip F26 steady-4 apply: [4]${k4.javaClass.name} h=$k4h->GONE " +
                "(无文本icons=${icons4}残留 [0]活${alive0.second}绝不动) beforeWindowBar=$before target=$target " +
                "(verify 300ms delta>=${STRIP_STALE4_DELTA_KEEP_PX}且近192留否则还账，顶底槽栏键不动)")
            runCatching { scheduleStale4VerifyF26(row, decor, k4, before, target) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F26 steady-4 failed: $t")
        }
    }

    /** F26单步复测（300ms后windowBar delta>=4且更近192才留，否则还账；连带复测顶4~7/底19~21；禁盲累加）。 */
    private fun scheduleStale4VerifyF26(
        row: View,
        decor: ViewGroup,
        appliedView: View,
        beforeWindowBar: Int,
        target: Int
    ) {
        try {
            row.postDelayed({
                try {
                    val bv = stripStale4LastViewF26
                    if (bv == null || bv !== appliedView) return@postDelayed
                    val after = runCatching { measureWindowToBarF20(row, decor)?.third }.getOrNull()
                    if (after == null || beforeWindowBar <= 0) {
                        stripStale4LastViewF26 = null
                        stripStale4LastBeforeF26 = -1
                        stripStale4LastTargetF26 = -1
                        return@postDelayed
                    }
                    val delta = beforeWindowBar - after
                    val distBefore = kotlin.math.abs(beforeWindowBar - target)
                    val distAfter = kotlin.math.abs(after - target)
                    if (delta >= STRIP_STALE4_DELTA_KEEP_PX && distAfter < distBefore) {
                        AndroidLog.i(TAG, "strip F26 steady-4 verdict=KEEP: ${bv.javaClass.name} " +
                            "windowBar $beforeWindowBar->$after delta=$delta(>=4真缩) " +
                            "dist $distBefore->$distAfter target=$target")
                        stripStale4LastViewF26 = null
                        stripStale4LastBeforeF26 = -1
                        stripStale4LastTargetF26 = -1
                        runCatching { logWindowToBarF20(row, decor, "F26-stale4-keep") }
                        runCatching { logTopBottomSteadyF25(row, decor, "F26-keep") }
                    } else {
                        runCatching { revertStale4ViewF26(bv) }
                        synchronized(stripStale4TriedF26) { stripStale4TriedF26.add(bv) }
                        val afterRevert = runCatching { measureWindowToBarF20(row, decor)?.third }?.getOrNull() ?: after
                        AndroidLog.e(TAG, "strip F26 steady-4 verdict=REVERT: ${bv.javaClass.name} " +
                            "windowBar $beforeWindowBar->$after delta=$delta(<4或未近目标) " +
                            "dist $distBefore->$distAfter target=$target afterRevert=$afterRevert")
                        stripStale4LastViewF26 = null
                        stripStale4LastBeforeF26 = -1
                        stripStale4LastTargetF26 = -1
                        runCatching { row.requestLayout() }
                        runCatching { logWindowToBarF20(row, decor, "F26-stale4-revert") }
                        runCatching { logTopBottomSteadyF25(row, decor, "F26-revert") }
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F26 steady-4 verify failed: $t")
                }
            }, POST_STABLE_DELAY_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F26 steady-4 verify schedule failed: $t")
        }
    }

    /** F26单视图还账（幂等，只撤销我方增量）。 */
    private fun revertStale4ViewF26(v: View) {
        try {
            val orig = synchronized(stripStale4VisOrigF26) { stripStale4VisOrigF26.remove(v) }
            if (orig != null) {
                runCatching {
                    if (v.parent != null && v.visibility != orig) {
                        v.visibility = orig
                        runCatching { v.requestLayout() }
                    }
                }
                AndroidLog.i(TAG, "strip F26 steady-4 reverted: ${v.javaClass.name} ->$orig")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F26 steady-4 revert failed: $t")
        }
    }

    /** F26退壳还账（幂等）：残留[4]GONE逐个还+跨轮状态清，只撤销我方增量。 */
    private fun restoreStale4F26() {
        try {
            var n = 0
            stripStale4LastViewF26 = null
            stripStale4LastBeforeF26 = -1
            stripStale4LastTargetF26 = -1
            synchronized(stripStale4VisOrigF26) {
                val it = stripStale4VisOrigF26.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        if (e.key.parent != null && e.key.visibility != e.value) {
                            e.key.visibility = e.value
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripStale4TriedF26) {
                if (stripStale4TriedF26.isNotEmpty()) stripStale4TriedF26.clear()
            }
            if (n > 0) AndroidLog.i(TAG, "strip F26 steady-4 restored n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F26 steady-4 restore failed: $t")
        }
    }

    // F27（接F26残留[4]已合入未提交，在此基础上改，不reset）：C30b铁证（以此为准）：
    // [4]RelativeLayout y1305 h140 VISIBLE含1活图标（ImageView 90x90 y1443，非877）+空kid[0]
    // （162宽全高空文本）+GONE TextView；GONE整块[4] windowBar 280→280不动
    // （RelativeLayout重叠布局，高度另有来源）已REVERT。[0]6图标活工具栏绝不动。
    // 缝=1305~1437共132px=[4]空顶，图标钉在[4]底部1443。
    // 修：稳态5秒后：①先解id2131297067 entry名（Resources.getResourceEntryName，日志记名）；
    // ②试GONE kid[0]空块→复测[4]高动否；③试[4] lpH 140→WRAP_CONTENT（记账）→复测图标y
    // （必须1443±2否则还账）与windowBar；④每步requestLayout+300ms复测，delta<4或图标移位
    // 立即还账试下一步，全不动记STALL。目标windowBar→192±4、顶4~7、底19~21。
    // 工具栏[0]/键盘/图标位钉死；壳/s0/圆角B/DEL/commit/logo/退壳全还全不动。
    private const val STRIP_F27_PROBE_ID = 2131297067
    private const val STRIP_F27_DELTA_KEEP_PX = 4
    private const val STRIP_F27_ICON_Y_EXPECT = 1443
    private const val STRIP_F27_ICON_Y_TOL_PX = 2
    private const val STRIP_F27_KID0_W_EXPECT = 162
    private const val STRIP_F27_KID0_W_TOL_PX = 12
    private const val STRIP_F27_K4_H_EXPECT = 140
    /** F27 kid[0]记账（只GONE空块，退壳全还；不动LP高/边/位移/条/[0]工具栏/键盘/壳/s0/圆角B/DEL/commit/logo）。 */
    private val stripF27Kid0VisOrigF27: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** F27 [4]高记账（只改lpH 140→WRAP，退壳全还；不动visibility/边/位移/条/[0]/键盘/壳/s0/圆角B/DEL/commit/logo）。 */
    private val stripF27K4HeightOrigF27: MutableMap<ViewGroup, Int> =
        Collections.synchronizedMap(WeakHashMap<ViewGroup, Int>())
    /** F27已验不动黑名单（Weak防泄漏；宿主重置致复活亦跳过不再盲试）。 */
    private val stripF27TriedKid0F27: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val stripF27TriedK4WrapF27: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    @Volatile
    private var stripF27LastViewF27: View? = null
    @Volatile
    private var stripF27LastKindF27: String = ""
    @Volatile
    private var stripF27LastBeforeWindowBarF27: Int = -1
    @Volatile
    private var stripF27LastBeforeK4HF27: Int = -1
    @Volatile
    private var stripF27LastBeforeIconYF27: Int = -1
    @Volatile
    private var stripF27LastBeforeK0YF27: Int = -1
    @Volatile
    private var stripF27LastTargetF27: Int = -1
    @Volatile
    private var stripF27Kid0KeptF27: Boolean = false
    @Volatile
    private var stripF27K4KeptF27: Boolean = false

    /** F27步骤①：解id2131297067 entry名（只读，Resources.getResourceEntryName，日志记名；异常记?）。返entry名。 */
    private fun resolveF27EntryNameF27(decor: View): String {
        return try {
            val res = decor.resources
            val entry = runCatching { res.getResourceEntryName(STRIP_F27_PROBE_ID) }.getOrDefault("?")
            val hex = "0x" + Integer.toHexString(STRIP_F27_PROBE_ID)
            val found = runCatching {
                (decor as? ViewGroup)?.findViewById<View>(STRIP_F27_PROBE_ID)
            }.getOrNull()
            if (found != null) {
                val floc = IntArray(2)
                runCatching { found.getLocationOnScreen(floc) }
                val fw = found.width.takeIf { it > 0 } ?: found.measuredWidth
                val fh = found.height.takeIf { it > 0 } ?: found.measuredHeight
                val fvis = when (found.visibility) {
                    View.VISIBLE -> "V"
                    View.GONE -> "G"
                    View.INVISIBLE -> "I"
                    else -> "${found.visibility}"
                }
                AndroidLog.i(TAG, "strip F27 entry: id=$STRIP_F27_PROBE_ID($hex/$entry) " +
                    "found=${found.javaClass.name} y=${floc[1]} wh=${fw}x$fh vis=$fvis " +
                    "(C30b待解，[4]/kid[0]/图标归属现量)")
            } else {
                AndroidLog.i(TAG, "strip F27 entry: id=$STRIP_F27_PROBE_ID($hex/$entry) " +
                    "found=null(decor树无此id，宿主资源名已记)")
            }
            entry
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F27 entry resolve failed: $t")
            "?"
        }
    }

    /** F27 [4]高现量（只读）：height>0用height，否则measuredHeight，否则-1。 */
    private fun k4HeightOfF27(k4: View): Int {
        return try {
            k4.height.takeIf { it > 0 } ?: k4.measuredHeight.takeIf { it > 0 } ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    /** F27屏Y现量（只读）：getLocationOnScreen[1]，未布局返-1。 */
    private fun screenYOfF27(v: View): Int {
        return try {
            val loc = IntArray(2)
            runCatching { v.getLocationOnScreen(loc) }
            loc[1].takeIf { it > 0 } ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    /** F27 [4]内活图标Y现量（只读）：singleIconLeaves首叶屏Y，无叶返null（调用方fail-closed还账）。 */
    private fun iconYOfK4F27(k4: View): Int? {
        return try {
            val leaf = singleIconLeavesOfF26(k4).firstOrNull() ?: return null
            val y = screenYOfF27(leaf)
            if (y <= 0) null else y
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * F27稳态dump（只读不写，先定案；步骤①entry解名+ [4]/kid[0]/图标/[0]交叉）。
     * 日志关键字F27-seam。任一未布局记诊断返null（不拦修，修侧复测为准）。工具栏[0]绝不动。
     */
    private fun dumpSeamF27(row: View, decor: ViewGroup): ViewGroup? {
        return try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                AndroidLog.e(TAG, "strip F27-seam: row tag mismatch")
                return null
            }
            if (row.parent == null) {
                AndroidLog.e(TAG, "strip F27-seam: row detached")
                return null
            }
            val entry = runCatching { resolveF27EntryNameF27(decor) }.getOrDefault("?")
            val cand = findImeCandidateViewF24(row, decor) ?: run {
                AndroidLog.e(TAG, "strip F27-seam: ImeCandidateView missing entry=$entry")
                return null
            }
            val cloc = IntArray(2)
            runCatching { cand.getLocationOnScreen(cloc) }
            val candH = cand.height.takeIf { it > 0 } ?: cand.measuredHeight
            val kids = cand.childCount
            val slot = row.parent as? ViewGroup
            var slotIdx = -1
            if (slot != null) {
                for (i in 0 until kids) {
                    if (cand.getChildAt(i) === slot) {
                        slotIdx = i
                        break
                    }
                }
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            val windowBar = winBar?.third ?: -1
            val target = runCatching { slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX) }.getOrNull() ?: -1
            val inter = runCatching { measureInterGapF19(row, decor)?.third }.getOrNull()
            AndroidLog.i(TAG, "strip F27-seam: entry=$entry cand=${cand.javaClass.name} " +
                "y=${cloc[1]} h=$candH kids=$kids slotIdx=$slotIdx " +
                "windowBar=$windowBar(target≈192±4) target=$target inter=$inter " +
                "(C30b缝1305~1437=132=[4]空顶，图标钉1443)")
            if (kids <= 4) {
                AndroidLog.e(TAG, "strip F27-seam: kids=${kids}无[4]无法深查(need>=5)")
                return cand
            }
            val keyword = runCatching {
                (row.findViewWithTag<View>(TAG_SEARCH_BOX) as? EditText)?.text?.toString() ?: ""
            }.getOrDefault("")
            val k0 = cand.getChildAt(0)
            if (k0 == null) {
                AndroidLog.e(TAG, "strip F27-seam: [0] null无法交叉")
            } else {
                val k0y = screenYOfF27(k0)
                val k0h = k0.height.takeIf { it > 0 } ?: k0.measuredHeight
                val visStr = when (k0.visibility) {
                    View.VISIBLE -> "V"
                    View.GONE -> "G"
                    View.INVISIBLE -> "I"
                    else -> "${k0.visibility}"
                }
                val who0 = kid0WhoOfF25(k0)
                val icons0 = toolbarIconsInF25(k0)
                val alive = isToolbarAliveF26(k0, who0, icons0)
                AndroidLog.i(TAG, "strip F27-seam [0]live-cross: cls=${k0.javaClass.name} " +
                    "y=$k0y h=$k0h vis=$visStr recycler=${who0.isRecycler} " +
                    "adapter=${who0.adapterCount} childViews=${who0.childViews} " +
                    "texts=${who0.textCount} first='${who0.firstText}' kwLen=${keyword.length} " +
                    "icons=$icons0(C30b=6) alive=${alive.first}(${alive.second})绝不动")
            }
            val k4 = cand.getChildAt(4)
            if (k4 == null) {
                AndroidLog.e(TAG, "strip F27-seam: [4] null")
            } else {
                val k4y = screenYOfF27(k4)
                val k4h = k4HeightOfF27(k4)
                val k4w = k4.width.takeIf { it > 0 } ?: k4.measuredWidth
                val visStr = when (k4.visibility) {
                    View.VISIBLE -> "V"
                    View.GONE -> "G"
                    View.INVISIBLE -> "I"
                    else -> "${k4.visibility}"
                }
                val lpH = k4.layoutParams?.height ?: -9999
                val lpHStr = when (lpH) {
                    ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
                    ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                    else -> "$lpH"
                }
                val k4id = k4.id
                val k4idName = runCatching { k4.resources.getResourceEntryName(k4id) }.getOrDefault("?")
                val icons4 = toolbarIconsInF25(k4)
                val texts4 = kid4TextsOfF26(k4)
                AndroidLog.i(TAG, "strip F27-seam [4]: cls=${k4.javaClass.name} " +
                    "y=$k4y h=$k4h w=$k4w vis=$visStr lpH=$lpHStr(C30b=140) " +
                    "id=$k4id(0x${Integer.toHexString(k4id)}/$k4idName) " +
                    "icons=$icons4 texts=${texts4.textCount} first='${texts4.firstText}' " +
                    "(C30b RelativeLayout y1305 h140 VISIBLE，缝空顶132+图标钉底1443)")
                val k4g = k4 as? ViewGroup
                if (k4g == null) {
                    AndroidLog.i(TAG, "strip F27-seam [4]kids: 非容器无直属孩子")
                } else {
                    for (i in 0 until k4g.childCount) {
                        val c = k4g.getChildAt(i) ?: continue
                        val cy = screenYOfF27(c)
                        val ch = c.height.takeIf { it > 0 } ?: c.measuredHeight
                        val cw = c.width.takeIf { it > 0 } ?: c.measuredWidth
                        val cVis = when (c.visibility) {
                            View.VISIBLE -> "V"
                            View.GONE -> "G"
                            View.INVISIBLE -> "I"
                            else -> "${c.visibility}"
                        }
                        val cid = c.id
                        val cidName = runCatching { c.resources.getResourceEntryName(cid) }.getOrDefault("?")
                        val cidMark = if (cid == STRIP_F27_PROBE_ID) "<=PROBE" else ""
                        val cText = runCatching {
                            (c as? android.widget.TextView)?.text?.toString()?.trim()?.take(24) ?: ""
                        }.getOrDefault("")
                        val cIsIcon = runCatching { isToolbarImageLeaf(c) }.getOrDefault(false)
                        val cDetail = if (cIsIcon) " " + iconDetailOfF26(c) else ""
                        AndroidLog.i(TAG, "strip F27-seam [4]kid[$i]: cls=${c.javaClass.name} " +
                            "y=$cy h=$ch w=$cw vis=$cVis text='$cText' isIcon=$cIsIcon " +
                            "id=$cid(0x${Integer.toHexString(cid)}/$cidName)$cidMark$cDetail")
                    }
                    val leaves = singleIconLeavesOfF26(k4)
                    if (leaves.size == 1) {
                        val ly = screenYOfF27(leaves[0])
                        AndroidLog.i(TAG, "strip F27-seam [4]single-icon: " + iconDetailOfF26(leaves[0]) +
                            " y=$ly(钉1443±2，移位即还账)")
                    } else {
                        AndroidLog.i(TAG, "strip F27-seam [4]icons-detail: n=${leaves.size} " +
                            leaves.take(3).map { iconDetailOfF26(it) })
                    }
                }
            }
            runCatching { logWindowToBarF20(row, decor, "F27-seam") }
            runCatching { logTopBottomSteadyF25(row, decor, "F27-seam") }
            cand
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F27-seam dump failed: $t")
            null
        }
    }

    /**
     * F27步骤②：试GONE kid[0]空块→复测[4]高动否。
     * kid[0]须为空文本TextView（162宽±12、全高≈[4]h、非图标、VISIBLE），[0]须活工具栏，
     * 非bar/slot/条侧，黑名单/已记账跳过。命中记账GONE+requestLayout+300ms复测windowBar，
     * delta>=4且近192才留，否则还账并链试③；图标移位/[0]移位立即还账试③。
     * 只动kid[0]自身visibility，不碰顶底槽栏键/壳/s0/圆角B/DEL/commit/logo/[0]工具栏。禁盲累加。
     */
    private fun tryKid0GoneF27(row: View, decor: ViewGroup) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            if (row.parent == null) return
            if (stripF27LastViewF27 != null) {
                AndroidLog.i(TAG, "strip F27 kid0: verify pending kind=$stripF27LastKindF27 skip new apply")
                return
            }
            if (stripStale4LastViewF26 != null) {
                AndroidLog.i(TAG, "strip F27 kid0: F26 verify pending, defer 1000ms")
                runCatching {
                    row.postDelayed({ runCatching { tryKid0GoneF27(row, decor) } }, 1000L)
                }
                return
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            if (winBar == null) {
                AndroidLog.e(TAG, "strip F27 kid0: windowBar unmeasurable (unlaid)")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            val before = winBar.third
            val target = runCatching { slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F27 kid0: target unmeasurable (trueH unlaid)")
                return
            }
            if (target <= 0) {
                AndroidLog.e(TAG, "strip F27 kid0: target invalid=$target")
                return
            }
            if (before in (target - STRIP_WINDOW_BAR_TOL_PX)..(target + STRIP_WINDOW_BAR_TOL_PX)) {
                AndroidLog.i(TAG, "strip F27 kid0: windowBar pass before=$before target=$target(192±4) no fix")
                return
            }
            val cand = findImeCandidateViewF24(row, decor) ?: run {
                AndroidLog.e(TAG, "strip F27 kid0: no cand before=$before target=$target")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            if (cand.childCount <= 4) {
                AndroidLog.e(TAG, "strip F27 kid0: kids=${cand.childCount}无[4]不试 before=$before target=$target")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            val k0 = cand.getChildAt(0) ?: run {
                AndroidLog.e(TAG, "strip F27 kid0: [0] null不试 before=$before target=$target")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            val who0 = kid0WhoOfF25(k0)
            val icons0 = toolbarIconsInF25(k0)
            val alive0 = isToolbarAliveF26(k0, who0, icons0)
            if (!alive0.first) {
                AndroidLog.e(TAG, "strip F27 kid0: [0]非活工具栏不试 ${alive0.second} " +
                    "before=$before target=$target([4]不单动防误杀)")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            val k0yBefore = screenYOfF27(k0)
            val k4 = cand.getChildAt(4) ?: run {
                AndroidLog.e(TAG, "strip F27 kid0: [4] null不试 before=$before target=$target")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            val slot = row.parent as? ViewGroup
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
            if (bar != null && (k4 === bar || (k4 is ViewGroup && isAncestorOf(k4, bar)) ||
                        (bar is ViewGroup && isAncestorOf(bar, k4)))
            ) {
                AndroidLog.e(TAG, "strip F27 kid0: [4]为bar侧不试 before=$before target=$target(绝不动)")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            if (slot != null && k4 === slot) {
                AndroidLog.e(TAG, "strip F27 kid0: [4]即slot不试")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            if (k4 is ViewGroup && isAncestorOf(k4, row)) {
                AndroidLog.e(TAG, "strip F27 kid0: [4]含条不试")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            if (k4.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                k4.getTag() == TAG_SEARCH_BUTTON ||
                k4.getTag() == TAG_SEARCH_CLEAR ||
                k4.getTag() == TAG_SEARCH_BOX
            ) {
                AndroidLog.e(TAG, "strip F27 kid0: [4]涉条不试")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            val k4g = k4 as? ViewGroup ?: run {
                AndroidLog.e(TAG, "strip F27 kid0: [4]非容器无kid[0]不试")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            if (k4g.childCount < 1) {
                AndroidLog.e(TAG, "strip F27 kid0: [4]空容器无kid[0]不试")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            val kid0 = k4g.getChildAt(0) ?: run {
                AndroidLog.e(TAG, "strip F27 kid0: kid[0] null不试")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            if (runCatching { isToolbarImageLeaf(kid0) }.getOrDefault(false)) {
                AndroidLog.e(TAG, "strip F27 kid0: kid[0]为图标不藏 ${kid0.javaClass.name}")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            if (kid0.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                kid0.getTag() == TAG_SEARCH_BUTTON ||
                kid0.getTag() == TAG_SEARCH_CLEAR ||
                kid0.getTag() == TAG_SEARCH_BOX
            ) {
                AndroidLog.e(TAG, "strip F27 kid0: kid[0]涉条不藏")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            if (slot != null && kid0 === slot) {
                AndroidLog.e(TAG, "strip F27 kid0: kid[0]即slot不藏")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            if (kid0 is ViewGroup && isAncestorOf(kid0, row)) {
                AndroidLog.e(TAG, "strip F27 kid0: kid[0]含条不藏")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            val kid0Text = runCatching {
                (kid0 as? android.widget.TextView)?.text?.toString()?.trim() ?: ""
            }.getOrDefault("?")
            if (kid0Text.isNotEmpty()) {
                AndroidLog.e(TAG, "strip F27 kid0: kid[0]有文本不藏 text='$kid0Text'")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            val kid0w = kid0.width.takeIf { it > 0 } ?: kid0.measuredWidth
            val kid0h = kid0.height.takeIf { it > 0 } ?: kid0.measuredHeight
            val k4hBefore = k4HeightOfF27(k4)
            val iconYBefore = iconYOfK4F27(k4)
            if (iconYBefore == null) {
                AndroidLog.e(TAG, "strip F27 kid0: iconY unmeasurable不试([4]图标须钉1443)")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            val dw = kotlin.math.abs(kid0w - STRIP_F27_KID0_W_EXPECT)
            if (dw > STRIP_F27_KID0_W_TOL_PX) {
                AndroidLog.e(TAG, "strip F27 kid0: kid[0]非162宽不藏 w=$kid0w(162±12) h=$kid0h " +
                    "k4h=$k4hBefore iconY=$iconYBefore")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            if (kid0.visibility != View.VISIBLE) {
                AndroidLog.e(TAG, "strip F27 kid0: kid[0]非VISIBLE不藏 vis=${kid0.visibility}")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            val tried = synchronized(stripF27TriedKid0F27) { stripF27TriedKid0F27.contains(kid0) }
            if (tried) {
                AndroidLog.i(TAG, "strip F27 kid0: blacklisted skip")
                runCatching { tryK4WrapF27(row, decor) }
                return
            }
            val already = synchronized(stripF27Kid0VisOrigF27) { stripF27Kid0VisOrigF27.containsKey(kid0) }
            if (already) {
                AndroidLog.i(TAG, "strip F27 kid0: already trimmed wait verify")
                return
            }
            synchronized(stripF27Kid0VisOrigF27) { stripF27Kid0VisOrigF27[kid0] = kid0.visibility }
            kid0.visibility = View.GONE
            runCatching { kid0.requestLayout() }
            runCatching { k4.requestLayout() }
            runCatching { cand.requestLayout() }
            if (slot != null) runCatching { slot.requestLayout() }
            runCatching { row.requestLayout() }
            stripF27LastViewF27 = kid0
            stripF27LastKindF27 = "kid0"
            stripF27LastBeforeWindowBarF27 = before
            stripF27LastBeforeK4HF27 = k4hBefore
            stripF27LastBeforeIconYF27 = iconYBefore
            stripF27LastBeforeK0YF27 = k0yBefore
            stripF27LastTargetF27 = target
            AndroidLog.i(TAG, "strip F27 kid0 apply: kid[0]${kid0.javaClass.name} " +
                "w=$kid0w h=$kid0h(empty162全高)->GONE [4]h=$k4hBefore iconY=$iconYBefore(钉1443±2) " +
                "k0y=$k0yBefore([0]活${alive0.second}绝不动) beforeWindowBar=$before target=$target " +
                "(verify 300ms delta>=4且近192留否则还账链试WRAP，顶底槽栏键不动)")
            runCatching { scheduleKid0VerifyF27(row, decor, kid0, k4, k0, before, k4hBefore, iconYBefore, k0yBefore, target) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F27 kid0 failed: $t")
            runCatching { tryK4WrapF27(row, decor) }
        }
    }

    /** F27步骤②复测（300ms后windowBar delta>=4且更近192才留，否则还账链试③；图标/[0]移位立即还账试③；禁盲累加）。 */
    private fun scheduleKid0VerifyF27(
        row: View,
        decor: ViewGroup,
        kid0: View,
        k4: View,
        k0: View,
        beforeWindowBar: Int,
        beforeK4H: Int,
        beforeIconY: Int,
        beforeK0Y: Int,
        target: Int
    ) {
        try {
            row.postDelayed({
                try {
                    val bv = stripF27LastViewF27
                    if (bv == null || bv !== kid0 || stripF27LastKindF27 != "kid0") return@postDelayed
                    val afterWindowBar = runCatching { measureWindowToBarF20(row, decor)?.third }.getOrNull()
                    val afterK4H = k4HeightOfF27(k4)
                    val afterIconY = iconYOfK4F27(k4)
                    val afterK0Y = screenYOfF27(k0)
                    if (afterWindowBar == null || afterIconY == null) {
                        runCatching { revertKid0F27(kid0) }
                        synchronized(stripF27TriedKid0F27) { stripF27TriedKid0F27.add(kid0) }
                        stripF27LastViewF27 = null
                        stripF27LastKindF27 = ""
                        AndroidLog.e(TAG, "strip F27 kid0 verdict=REVERT(unmeasurable): " +
                            "afterBar=$afterWindowBar afterIcon=$afterIconY chain WRAP")
                        runCatching { row.requestLayout() }
                        runCatching { tryK4WrapF27(row, decor) }
                        return@postDelayed
                    }
                    val iconShift = kotlin.math.abs(afterIconY - beforeIconY)
                    val iconExpectOff = kotlin.math.abs(afterIconY - STRIP_F27_ICON_Y_EXPECT)
                    if (iconShift > STRIP_F27_ICON_Y_TOL_PX || iconExpectOff > STRIP_F27_ICON_Y_TOL_PX) {
                        runCatching { revertKid0F27(kid0) }
                        synchronized(stripF27TriedKid0F27) { stripF27TriedKid0F27.add(kid0) }
                        val afterRevert = runCatching { measureWindowToBarF20(row, decor)?.third }?.getOrNull() ?: afterWindowBar
                        AndroidLog.e(TAG, "strip F27 kid0 verdict=REVERT(icon-shift): " +
                            "icon $beforeIconY->$afterIconY shift=$iconShift expectOff=$iconExpectOff(须1443±2) " +
                            "bar $beforeWindowBar->$afterWindowBar afterRevert=$afterRevert chain WRAP")
                        stripF27LastViewF27 = null
                        stripF27LastKindF27 = ""
                        runCatching { row.requestLayout() }
                        runCatching { logWindowToBarF20(row, decor, "F27-kid0-icon-revert") }
                        runCatching { logTopBottomSteadyF25(row, decor, "F27-kid0-revert") }
                        runCatching { tryK4WrapF27(row, decor) }
                        return@postDelayed
                    }
                    if (beforeK0Y > 0 && afterK0Y > 0 && kotlin.math.abs(afterK0Y - beforeK0Y) > 1) {
                        runCatching { revertKid0F27(kid0) }
                        synchronized(stripF27TriedKid0F27) { stripF27TriedKid0F27.add(kid0) }
                        val afterRevert = runCatching { measureWindowToBarF20(row, decor)?.third }?.getOrNull() ?: afterWindowBar
                        AndroidLog.e(TAG, "strip F27 kid0 verdict=REVERT(toolbar-moved): " +
                            "k0y $beforeK0Y->$afterK0Y bar $beforeWindowBar->$afterWindowBar " +
                            "afterRevert=$afterRevert([0]钉死) chain WRAP")
                        stripF27LastViewF27 = null
                        stripF27LastKindF27 = ""
                        runCatching { row.requestLayout() }
                        runCatching { tryK4WrapF27(row, decor) }
                        return@postDelayed
                    }
                    val delta = beforeWindowBar - afterWindowBar
                    val distBefore = kotlin.math.abs(beforeWindowBar - target)
                    val distAfter = kotlin.math.abs(afterWindowBar - target)
                    val deltaK4H = beforeK4H - afterK4H
                    if (delta >= STRIP_F27_DELTA_KEEP_PX && distAfter < distBefore) {
                        AndroidLog.i(TAG, "strip F27 kid0 verdict=KEEP: " +
                            "windowBar $beforeWindowBar->$afterWindowBar delta=$delta(>=4真缩) " +
                            "dist $distBefore->$distAfter target=$target " +
                            "[4]h $beforeK4H->$afterK4H deltaK4H=$deltaK4H iconY $beforeIconY->$afterIconY(钉1443) " +
                            "k0y $beforeK0Y->$afterK0Y(钉死)")
                        stripF27LastViewF27 = null
                        stripF27LastKindF27 = ""
                        stripF27Kid0KeptF27 = true
                        runCatching { logWindowToBarF20(row, decor, "F27-kid0-keep") }
                        runCatching { logTopBottomSteadyF25(row, decor, "F27-kid0-keep") }
                    } else {
                        runCatching { revertKid0F27(kid0) }
                        synchronized(stripF27TriedKid0F27) { stripF27TriedKid0F27.add(kid0) }
                        val afterRevert = runCatching { measureWindowToBarF20(row, decor)?.third }?.getOrNull() ?: afterWindowBar
                        AndroidLog.e(TAG, "strip F27 kid0 verdict=REVERT: " +
                            "windowBar $beforeWindowBar->$afterWindowBar delta=$delta(<4或未近目标) " +
                            "dist $distBefore->$distAfter target=$target afterRevert=$afterRevert " +
                            "[4]h $beforeK4H->$afterK4H deltaK4H=$deltaK4H iconY $beforeIconY->$afterIconY chain WRAP")
                        stripF27LastViewF27 = null
                        stripF27LastKindF27 = ""
                        runCatching { row.requestLayout() }
                        runCatching { logWindowToBarF20(row, decor, "F27-kid0-revert") }
                        runCatching { logTopBottomSteadyF25(row, decor, "F27-kid0-revert") }
                        runCatching { tryK4WrapF27(row, decor) }
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F27 kid0 verify failed: $t")
                    runCatching { tryK4WrapF27(row, decor) }
                }
            }, POST_STABLE_DELAY_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F27 kid0 verify schedule failed: $t")
        }
    }

    /** F27 kid[0]单视图还账（幂等，只撤销我方增量）。 */
    private fun revertKid0F27(v: View) {
        try {
            val orig = synchronized(stripF27Kid0VisOrigF27) { stripF27Kid0VisOrigF27.remove(v) }
            if (orig != null) {
                runCatching {
                    if (v.parent != null && v.visibility != orig) {
                        v.visibility = orig
                        runCatching { v.requestLayout() }
                    }
                }
                AndroidLog.i(TAG, "strip F27 kid0 reverted: ${v.javaClass.name} ->$orig")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F27 kid0 revert failed: $t")
        }
    }

    /**
     * F27步骤③：试[4] lpH 140→WRAP_CONTENT（记账）→复测图标y（必须1443±2否则还账）与windowBar。
     * [0]须活工具栏，非bar/slot/条侧，黑名单/已记账跳过。命中记账WRAP+requestLayout+300ms复测，
     * delta>=4且近192且图标钉1443±2才留，否则还账；图标/[0]移位立即还账。全不动记STALL。
     * 只动[4]自身lpH，不碰visibility/边/位移/条/[0]/键盘/壳/s0/圆角B/DEL/commit/logo。禁盲累加。
     */
    private fun tryK4WrapF27(row: View, decor: ViewGroup) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            if (row.parent == null) return
            if (stripF27LastViewF27 != null) {
                AndroidLog.i(TAG, "strip F27 k4wrap: verify pending kind=$stripF27LastKindF27 skip")
                return
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            if (winBar == null) {
                AndroidLog.e(TAG, "strip F27 k4wrap: windowBar unmeasurable (unlaid)")
                runCatching { logF27StallF27(row, decor, "unmeasurable") }
                return
            }
            val before = winBar.third
            val target = runCatching { slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F27 k4wrap: target unmeasurable (trueH unlaid)")
                return
            }
            if (target <= 0) {
                AndroidLog.e(TAG, "strip F27 k4wrap: target invalid=$target")
                return
            }
            if (before in (target - STRIP_WINDOW_BAR_TOL_PX)..(target + STRIP_WINDOW_BAR_TOL_PX)) {
                AndroidLog.i(TAG, "strip F27 k4wrap: windowBar pass before=$before target=$target(192±4) no fix")
                return
            }
            val cand = findImeCandidateViewF24(row, decor) ?: run {
                AndroidLog.e(TAG, "strip F27 k4wrap: no cand before=$before target=$target")
                runCatching { logF27StallF27(row, decor, "no-cand") }
                return
            }
            if (cand.childCount <= 4) {
                AndroidLog.e(TAG, "strip F27 k4wrap: kids=${cand.childCount}无[4]不试 before=$before target=$target")
                runCatching { logF27StallF27(row, decor, "no-[4]") }
                return
            }
            val k0 = cand.getChildAt(0) ?: run {
                AndroidLog.e(TAG, "strip F27 k4wrap: [0] null不试")
                runCatching { logF27StallF27(row, decor, "no-[0]") }
                return
            }
            val who0 = kid0WhoOfF25(k0)
            val icons0 = toolbarIconsInF25(k0)
            val alive0 = isToolbarAliveF26(k0, who0, icons0)
            if (!alive0.first) {
                AndroidLog.e(TAG, "strip F27 k4wrap: [0]非活工具栏不试 ${alive0.second}")
                runCatching { logF27StallF27(row, decor, "dead-[0]") }
                return
            }
            val k0yBefore = screenYOfF27(k0)
            val k4v = cand.getChildAt(4) ?: run {
                AndroidLog.e(TAG, "strip F27 k4wrap: [4] null不试")
                runCatching { logF27StallF27(row, decor, "null-[4]") }
                return
            }
            val k4 = k4v as? ViewGroup ?: run {
                AndroidLog.e(TAG, "strip F27 k4wrap: [4]非容器不试 ${k4v.javaClass.name}")
                runCatching { logF27StallF27(row, decor, "non-group-[4]") }
                return
            }
            val slot = row.parent as? ViewGroup
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
            if (bar != null && (k4 === bar || isAncestorOf(k4, bar) || isAncestorOf(bar, k4))) {
                AndroidLog.e(TAG, "strip F27 k4wrap: [4]为bar侧不试(绝不动)")
                runCatching { logF27StallF27(row, decor, "bar-side") }
                return
            }
            if (slot != null && k4 === slot) {
                AndroidLog.e(TAG, "strip F27 k4wrap: [4]即slot不试")
                runCatching { logF27StallF27(row, decor, "is-slot") }
                return
            }
            if (isAncestorOf(k4, row)) {
                AndroidLog.e(TAG, "strip F27 k4wrap: [4]含条不试")
                runCatching { logF27StallF27(row, decor, "has-strip") }
                return
            }
            if (k4.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                k4.getTag() == TAG_SEARCH_BUTTON ||
                k4.getTag() == TAG_SEARCH_CLEAR ||
                k4.getTag() == TAG_SEARCH_BOX
            ) {
                AndroidLog.e(TAG, "strip F27 k4wrap: [4]涉条不试")
                runCatching { logF27StallF27(row, decor, "strip-tag") }
                return
            }
            val lp = k4.layoutParams ?: run {
                AndroidLog.e(TAG, "strip F27 k4wrap: [4] LP null不试")
                runCatching { logF27StallF27(row, decor, "null-LP") }
                return
            }
            val beforeLpH = lp.height
            val beforeLpStr = when (beforeLpH) {
                ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
                ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
                else -> "$beforeLpH"
            }
            if (beforeLpH == ViewGroup.LayoutParams.WRAP_CONTENT) {
                AndroidLog.e(TAG, "strip F27 k4wrap: [4]已WRAP不试 lpH=$beforeLpStr")
                runCatching { logF27StallF27(row, decor, "already-WRAP") }
                return
            }
            val k4hBefore = k4HeightOfF27(k4)
            val iconYBefore = iconYOfK4F27(k4)
            if (iconYBefore == null) {
                AndroidLog.e(TAG, "strip F27 k4wrap: iconY unmeasurable不试(须钉1443)")
                runCatching { logF27StallF27(row, decor, "icon-unmeasurable") }
                return
            }
            if (kotlin.math.abs(iconYBefore - STRIP_F27_ICON_Y_EXPECT) > STRIP_F27_ICON_Y_TOL_PX) {
                AndroidLog.e(TAG, "strip F27 k4wrap: iconY基线偏离不试 iconY=$iconYBefore(须1443±2)")
                runCatching { logF27StallF27(row, decor, "icon-baseline-off") }
                return
            }
            val tried = synchronized(stripF27TriedK4WrapF27) { stripF27TriedK4WrapF27.contains(k4) }
            if (tried) {
                AndroidLog.i(TAG, "strip F27 k4wrap: blacklisted skip")
                runCatching { logF27StallF27(row, decor, "blacklisted") }
                return
            }
            val already = synchronized(stripF27K4HeightOrigF27) { stripF27K4HeightOrigF27.containsKey(k4) }
            if (already) {
                AndroidLog.i(TAG, "strip F27 k4wrap: already trimmed wait verify")
                return
            }
            synchronized(stripF27K4HeightOrigF27) { stripF27K4HeightOrigF27[k4] = beforeLpH }
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            k4.layoutParams = lp
            runCatching { k4.requestLayout() }
            runCatching { cand.requestLayout() }
            if (slot != null) runCatching { slot.requestLayout() }
            runCatching { row.requestLayout() }
            stripF27LastViewF27 = k4
            stripF27LastKindF27 = "k4wrap"
            stripF27LastBeforeWindowBarF27 = before
            stripF27LastBeforeK4HF27 = k4hBefore
            stripF27LastBeforeIconYF27 = iconYBefore
            stripF27LastBeforeK0YF27 = k0yBefore
            stripF27LastTargetF27 = target
            AndroidLog.i(TAG, "strip F27 k4wrap apply: [4]${k4.javaClass.name} " +
                "lpH $beforeLpStr->$beforeLpH→WRAP(记账，C30b=140) [4]h=$k4hBefore iconY=$iconYBefore(钉1443±2) " +
                "k0y=$k0yBefore([0]活${alive0.second}绝不动) beforeWindowBar=$before target=$target " +
                "(verify 300ms delta>=4且近192且图标钉1443留否则还账，顶底槽栏键不动)")
            runCatching { scheduleK4WrapVerifyF27(row, decor, k4, k0, before, k4hBefore, iconYBefore, k0yBefore, target, beforeLpStr) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F27 k4wrap failed: $t")
        }
    }

    /** F27步骤③复测（300ms后windowBar delta>=4且更近192且图标钉1443±2才留，否则还账；图标/[0]移位立即还账；全不动记STALL；禁盲累加）。 */
    private fun scheduleK4WrapVerifyF27(
        row: View,
        decor: ViewGroup,
        k4: ViewGroup,
        k0: View,
        beforeWindowBar: Int,
        beforeK4H: Int,
        beforeIconY: Int,
        beforeK0Y: Int,
        target: Int,
        beforeLpStr: String
    ) {
        try {
            row.postDelayed({
                try {
                    val bv = stripF27LastViewF27
                    if (bv == null || bv !== k4 || stripF27LastKindF27 != "k4wrap") return@postDelayed
                    val afterWindowBar = runCatching { measureWindowToBarF20(row, decor)?.third }.getOrNull()
                    val afterK4H = k4HeightOfF27(k4)
                    val afterIconY = iconYOfK4F27(k4)
                    val afterK0Y = screenYOfF27(k0)
                    if (afterWindowBar == null || afterIconY == null) {
                        runCatching { revertK4WrapF27(k4) }
                        synchronized(stripF27TriedK4WrapF27) { stripF27TriedK4WrapF27.add(k4) }
                        stripF27LastViewF27 = null
                        stripF27LastKindF27 = ""
                        AndroidLog.e(TAG, "strip F27 k4wrap verdict=REVERT(unmeasurable): " +
                            "afterBar=$afterWindowBar afterIcon=$afterIconY")
                        runCatching { row.requestLayout() }
                        runCatching { logWindowToBarF20(row, decor, "F27-k4wrap-unmeasurable") }
                        runCatching { logF27StallF27(row, decor, "k4wrap-unmeasurable") }
                        return@postDelayed
                    }
                    val iconShift = kotlin.math.abs(afterIconY - beforeIconY)
                    val iconExpectOff = kotlin.math.abs(afterIconY - STRIP_F27_ICON_Y_EXPECT)
                    if (iconShift > STRIP_F27_ICON_Y_TOL_PX || iconExpectOff > STRIP_F27_ICON_Y_TOL_PX) {
                        runCatching { revertK4WrapF27(k4) }
                        synchronized(stripF27TriedK4WrapF27) { stripF27TriedK4WrapF27.add(k4) }
                        val afterRevert = runCatching { measureWindowToBarF20(row, decor)?.third }?.getOrNull() ?: afterWindowBar
                        val afterRevertIcon = runCatching { iconYOfK4F27(k4) }?.getOrNull() ?: afterIconY
                        AndroidLog.e(TAG, "strip F27 k4wrap verdict=REVERT(icon-shift): " +
                            "icon $beforeIconY->$afterIconY shift=$iconShift expectOff=$iconExpectOff(须1443±2) " +
                            "bar $beforeWindowBar->$afterWindowBar afterRevertBar=$afterRevert " +
                            "afterRevertIcon=$afterRevertIcon lpH $beforeLpStr→WRAP已还")
                        stripF27LastViewF27 = null
                        stripF27LastKindF27 = ""
                        runCatching { row.requestLayout() }
                        runCatching { logWindowToBarF20(row, decor, "F27-k4wrap-icon-revert") }
                        runCatching { logTopBottomSteadyF25(row, decor, "F27-k4wrap-revert") }
                        runCatching { logF27StallF27(row, decor, "k4wrap-icon-shift") }
                        return@postDelayed
                    }
                    if (beforeK0Y > 0 && afterK0Y > 0 && kotlin.math.abs(afterK0Y - beforeK0Y) > 1) {
                        runCatching { revertK4WrapF27(k4) }
                        synchronized(stripF27TriedK4WrapF27) { stripF27TriedK4WrapF27.add(k4) }
                        val afterRevert = runCatching { measureWindowToBarF20(row, decor)?.third }?.getOrNull() ?: afterWindowBar
                        AndroidLog.e(TAG, "strip F27 k4wrap verdict=REVERT(toolbar-moved): " +
                            "k0y $beforeK0Y->$afterK0Y bar $beforeWindowBar->$afterWindowBar " +
                            "afterRevert=$afterRevert([0]钉死) lpH $beforeLpStr→WRAP已还")
                        stripF27LastViewF27 = null
                        stripF27LastKindF27 = ""
                        runCatching { row.requestLayout() }
                        runCatching { logF27StallF27(row, decor, "k4wrap-toolbar-moved") }
                        return@postDelayed
                    }
                    val delta = beforeWindowBar - afterWindowBar
                    val distBefore = kotlin.math.abs(beforeWindowBar - target)
                    val distAfter = kotlin.math.abs(afterWindowBar - target)
                    val deltaK4H = beforeK4H - afterK4H
                    if (delta >= STRIP_F27_DELTA_KEEP_PX && distAfter < distBefore) {
                        AndroidLog.i(TAG, "strip F27 k4wrap verdict=KEEP: " +
                            "windowBar $beforeWindowBar->$afterWindowBar delta=$delta(>=4真缩) " +
                            "dist $distBefore->$distAfter target=$target " +
                            "[4]h $beforeK4H->$afterK4H deltaK4H=$deltaK4H lpH $beforeLpStr→WRAP " +
                            "iconY $beforeIconY->$afterIconY(钉1443±2) k0y $beforeK0Y->$afterK0Y(钉死)")
                        stripF27LastViewF27 = null
                        stripF27LastKindF27 = ""
                        stripF27K4KeptF27 = true
                        runCatching { logWindowToBarF20(row, decor, "F27-k4wrap-keep") }
                        runCatching { logTopBottomSteadyF25(row, decor, "F27-k4wrap-keep") }
                    } else {
                        runCatching { revertK4WrapF27(k4) }
                        synchronized(stripF27TriedK4WrapF27) { stripF27TriedK4WrapF27.add(k4) }
                        val afterRevert = runCatching { measureWindowToBarF20(row, decor)?.third }?.getOrNull() ?: afterWindowBar
                        AndroidLog.e(TAG, "strip F27 k4wrap verdict=REVERT: " +
                            "windowBar $beforeWindowBar->$afterWindowBar delta=$delta(<4或未近目标) " +
                            "dist $distBefore->$distAfter target=$target afterRevert=$afterRevert " +
                            "[4]h $beforeK4H->$afterK4H deltaK4H=$deltaK4H lpH $beforeLpStr→WRAP已还 " +
                            "iconY $beforeIconY->$afterIconY(钉1443)")
                        stripF27LastViewF27 = null
                        stripF27LastKindF27 = ""
                        runCatching { row.requestLayout() }
                        runCatching { logWindowToBarF20(row, decor, "F27-k4wrap-revert") }
                        runCatching { logTopBottomSteadyF25(row, decor, "F27-k4wrap-revert") }
                        runCatching { logF27StallF27(row, decor, "k4wrap-no-move") }
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F27 k4wrap verify failed: $t")
                }
            }, POST_STABLE_DELAY_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F27 k4wrap verify schedule failed: $t")
        }
    }

    /** F27 [4]单视图还账（幂等，只撤销我方增量）。 */
    private fun revertK4WrapF27(v: ViewGroup) {
        try {
            val orig = synchronized(stripF27K4HeightOrigF27) { stripF27K4HeightOrigF27.remove(v) }
            if (orig != null) {
                runCatching {
                    if (v.parent != null) {
                        val lp = v.layoutParams
                        if (lp != null && lp.height != orig) {
                            lp.height = orig
                            v.layoutParams = lp
                            runCatching { v.requestLayout() }
                        }
                    }
                }
                AndroidLog.i(TAG, "strip F27 k4wrap reverted: ${v.javaClass.name} lpH-> $orig")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F27 k4wrap revert failed: $t")
        }
    }

    /** F27全不动记STALL（只读诊断，不碰视图；两步均未KEEP且windowBar仍远192时记）。 */
    private fun logF27StallF27(row: View, decor: ViewGroup, reason: String) {
        try {
            if (stripF27Kid0KeptF27 || stripF27K4KeptF27) return
            if (stripF27LastViewF27 != null) return
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            val windowBar = winBar?.third ?: -1
            val target = runCatching { slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX) }.getOrNull() ?: -1
            AndroidLog.e(TAG, "strip F27 STALL: reason=$reason windowBar=$windowBar target=$target(192±4) " +
                "kid0Kept=$stripF27Kid0KeptF27 k4Kept=$stripF27K4KeptF27 " +
                "(两步delta<4或图标移位已还账，全不动；[0]/键盘/图标钉死；壳/s0/圆角B/DEL/commit/logo不动)")
            runCatching { logWindowToBarF20(row, decor, "F27-STALL-$reason") }
            runCatching { logTopBottomSteadyF25(row, decor, "F27-STALL-$reason") }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F27 STALL log failed: $t")
        }
    }

    /** F27退壳还账（幂等）：kid[0]GONE+[4]WRAP逐个还+跨轮状态清，只撤销我方增量。 */
    private fun restoreSeamF27() {
        try {
            var n = 0
            stripF27LastViewF27 = null
            stripF27LastKindF27 = ""
            stripF27LastBeforeWindowBarF27 = -1
            stripF27LastBeforeK4HF27 = -1
            stripF27LastBeforeIconYF27 = -1
            stripF27LastBeforeK0YF27 = -1
            stripF27LastTargetF27 = -1
            stripF27Kid0KeptF27 = false
            stripF27K4KeptF27 = false
            synchronized(stripF27Kid0VisOrigF27) {
                val it = stripF27Kid0VisOrigF27.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        if (e.key.parent != null && e.key.visibility != e.value) {
                            e.key.visibility = e.value
                            runCatching { e.key.requestLayout() }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripF27K4HeightOrigF27) {
                val it = stripF27K4HeightOrigF27.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        if (e.key.parent != null) {
                            val lp = e.key.layoutParams
                            if (lp != null && lp.height != e.value) {
                                lp.height = e.value
                                e.key.layoutParams = lp
                                runCatching { e.key.requestLayout() }
                            }
                        }
                    }
                    it.remove()
                    n++
                }
            }
            synchronized(stripF27TriedKid0F27) {
                if (stripF27TriedKid0F27.isNotEmpty()) stripF27TriedKid0F27.clear()
            }
            synchronized(stripF27TriedK4WrapF27) {
                if (stripF27TriedK4WrapF27.isNotEmpty()) stripF27TriedK4WrapF27.clear()
            }
            if (n > 0) AndroidLog.i(TAG, "strip F27 restored n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F27 restore failed: $t")
        }
    }

    // F28（接F27缝试探已合入未提交，在此基础上改，不reset）：C31铁证（以此为准）：
    // entry=v_（混淆无信息量，位置1443钉死PASS）；kid0-GONE与k4wrap先后单试全REVERT、STALL；
    // 窗280/顶5/缝152/零位移；slotH192。主控新判：别追视图了，查源头——无条基线窗高从未量过
    // （原候选140？），条167+25=192发布后窗成280，多132恰=缝132；500/1200ms复挂可能叠加发布；
    // N/float可能才是撑窗元凶（N-winner A/B都是mount后稳态量的，从未做“跳过”对照）。
    // 修1)量基线：拆条后（无条稳态）量windowBar0（应≈140？），日志baseline；挂载计数mountCount
    // （复挂叠加即>1，记日志）。2)跳过对照（稳态5秒后，逐个试，每步requestLayout+300ms复测
    // windowBar，delta<4或远离192立即还账试下一个）：①跳过float刷新；②跳过N三连只publish；
    // ③发布高改167（不+25）；以windowBar近192定胜，日志SKIP-winner。退壳/拆条全还。
    // 顶4~7/底19~21/工具栏键盘/图标1443钉死；壳/s0/圆角B/DEL/commit/logo全不动。
    private const val STRIP_F28_DELTA_KEEP_PX = 4
    private const val STRIP_F28_BASELINE_DELAY_MS = 1000L
    @Volatile
    private var stripMountCountF28: Int = 0
    @Volatile
    private var stripBaseline0F28: Int = -1
    @Volatile
    private var stripSkipRunningF28: Boolean = false
    @Volatile
    private var stripSkipWinnerF28: String? = null
    @Volatile
    private var stripSkipLastKindF28: String = ""
    @Volatile
    private var stripSkipLastBeforeF28: Int = -1
    @Volatile
    private var stripSkipLastTargetF28: Int = -1
    private val stripSkipTriedF28: MutableSet<String> =
        Collections.synchronizedSet(HashSet<String>())

    /** F28挂载计数（只记诊断，不碰视图）：每次mountStripOnKeyboard入口+1，复挂>1即叠加可疑。 */
    private fun noteStripMountF28(decor: ViewGroup): Int {
        return try {
            stripMountCountF28 += 1
            val n = stripMountCountF28
            val hasStrip = runCatching {
                decor.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) != null
            }.getOrDefault(false)
            AndroidLog.i(TAG, "strip F28 mountCount=$n hasStrip=$hasStrip " +
                "(复挂叠加即>1，500/1200ms复挂查源头，顶底槽栏键不动)")
            n
        } catch (_: Throwable) {
            stripMountCountF28
        }
    }

    /** F28无条cand查找（只读）：decor全树首个含ImeCandidateView且高>0者，无则null。 */
    private fun findCandNoRowF28(decor: ViewGroup): ViewGroup? {
        return try {
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            var hops = 0
            while (q.isNotEmpty() && hops < 600) {
                val v = q.removeFirst()
                hops++
                if (v is ViewGroup && v !== decor &&
                    v.javaClass.name.contains("ImeCandidateView")
                ) {
                    val h = v.height.takeIf { it > 0 } ?: v.measuredHeight
                    if (h > 0) return v
                }
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** F28无条基线现量（只读）：窗灰顶=cand顶现算，栏顶=工具栏顶现算，gap=栏顶-窗顶。任一未布局返null。 */
    private fun measureBaselineNoStripF28(decor: ViewGroup): Triple<Int, Int, Int>? {
        return try {
            val cand = findCandNoRowF28(decor) ?: return null
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull() ?: return null
            val cloc = IntArray(2)
            runCatching { cand.getLocationOnScreen(cloc) }
            val bloc = IntArray(2)
            runCatching { bar.getLocationOnScreen(bloc) }
            val windowTop = cloc[1]
            val barTop = bloc[1]
            if (windowTop <= 0 || barTop <= 0) return null
            Triple(windowTop, barTop, barTop - windowTop)
        } catch (_: Throwable) {
            null
        }
    }

    /** F28基线日志（只读不碰视图）：无条稳态windowBar0，应≈140？记baseline+mountCount。返gap。 */
    private fun logBaselineNoStripF28(decor: ViewGroup, tag: String): Int? {
        return try {
            val m = measureBaselineNoStripF28(decor)
            if (m == null) {
                AndroidLog.e(TAG, "strip F28 baseline [$tag]: unmeasurable " +
                    "(cand/bar unlaid) mountCount=$stripMountCountF28 baseline0=$stripBaseline0F28")
                return null
            }
            val windowTop = m.first
            val barTop = m.second
            val gap = m.third
            stripBaseline0F28 = gap
            AndroidLog.i(TAG, "strip F28 baseline [$tag]: windowTop=$windowTop barTop=$barTop " +
                "windowBar0=$gap(应≈140？无条基线，从未量过) mountCount=$stripMountCountF28 " +
                "顶4~7/底19~21/栏键钉死不动)")
            // F30无条基线快照（只读，同口径windowTop/图标簇/栏顶/Q排顶，关键字baseline-snap；顶底槽壳等全不动）。
            runCatching { logBaselineSnapF30(decor, tag) }
            gap
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F28 baseline log failed [$tag]: $t")
            null
        }
    }

    /**
     * F28拆条后基线排期（无条稳态1000ms后量，只读）：有条则跳过；无条才量baseline。
     * 只读几何，不碰顶底槽栏键/壳/s0/圆角B/DEL/commit/logo。
     */
    private fun scheduleBaselineAfterRemoveF28(decor: ViewGroup) {
        try {
            val hasStrip = runCatching {
                decor.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) != null
            }.getOrDefault(true)
            if (hasStrip) return
            decor.postDelayed({
                try {
                    val still = runCatching {
                        decor.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) != null
                    }.getOrDefault(true)
                    if (still) return@postDelayed
                    runCatching { logBaselineNoStripF28(decor, "afterRemove-steady") }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F28 baseline steady failed: $t")
                }
            }, STRIP_F28_BASELINE_DELAY_MS)
            AndroidLog.i(TAG, "strip F28 baseline scheduled 1000ms (无条稳态)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F28 baseline schedule failed: $t")
        }
    }

    /** F28回原（只发原生高度流+刷新，不碰视图LP/位移/顶底槽栏键/壳/s0/圆角B/DEL/commit/logo）。 */
    private fun revertSkipToOrigF28(row: View, reason: String) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            if (row.parent == null) return
            val trueH = runCatching { stripTrueHeightOfF16(row) }.getOrNull() ?: -1
            if (trueH <= 0) {
                AndroidLog.e(TAG, "strip F28 revert [$reason]: trueH unmeasurable")
                return
            }
            val publishH = runCatching {
                publishHeightForF20(row, trueH, STRIP_M_BOTTOM_PX)
            }.getOrNull() ?: trueH
            runCatching { publishStripHeight(row, publishH, reason) }
            runCatching { refreshCandidateLayout(row, reason) }
            runCatching { refreshFloatWindow(row, reason) }
            runCatching { row.requestLayout() }
            (row.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
            ((row.parent as? ViewGroup)?.parent as? ViewGroup)?.let {
                runCatching { it.requestLayout() }
            }
            AndroidLog.i(TAG, "strip F28 revert to orig 192+N+float [$reason]: " +
                "trueH=$trueH publishH=$publishH(顶底槽栏键不动)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F28 revert failed [$reason]: $t")
        }
    }

    /**
     * F28跳过对照入口（稳态5秒后逐个试，与F21/F23/F24/F25/F26/F27独立，先后验）。
     * 每步requestLayout+300ms复测windowBar，delta<4或远离192立即还账试下一个；
     * 以windowBar近192定胜，日志SKIP-winner。只发高度流+刷新，不碰顶底槽栏键/
     * 壳/s0/圆角B/DEL/commit/logo。禁盲累加。
     */
    private fun scheduleSkipContrastF28(decor: ViewGroup, row: View) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            if (row.parent == null) return
            // F28跳过对照正常跑（审计定案：工具栏可见钉死，不做隐藏分支）。
            if (stripSkipRunningF28) {
                AndroidLog.i(TAG, "strip F28 SKIP: running kind=$stripSkipLastKindF28 skip new")
                return
            }
            if (stripSkipWinnerF28 != null) {
                AndroidLog.i(TAG, "strip F28 SKIP: winner exists ${stripSkipWinnerF28} no retry")
                return
            }
            if (stripF27LastViewF27 != null || stripStale4LastViewF26 != null ||
                stripStaleLastViewF25 != null || stripKidLastViewF24 != null ||
                stripFixedParentLastViewF23 != null || stripBarLastViewF21 != null ||
                stripNABRunningF24
            ) {
                AndroidLog.i(TAG, "strip F28 SKIP: prior verify pending, defer 1000ms " +
                    "(F27/F26/F25/F24/F23/F21/NAB)")
                runCatching {
                    row.postDelayed({
                        runCatching { scheduleSkipContrastF28(decor, row) }
                    }, STRIP_F28_BASELINE_DELAY_MS)
                }
                return
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            if (winBar == null) {
                AndroidLog.e(TAG, "strip F28 SKIP: windowBar unmeasurable (unlaid)")
                return
            }
            val before = winBar.third
            val target = runCatching {
                slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX)
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F28 SKIP: target unmeasurable (trueH unlaid)")
                return
            }
            if (target <= 0) {
                AndroidLog.e(TAG, "strip F28 SKIP: target invalid=$target")
                return
            }
            if (before in (target - STRIP_WINDOW_BAR_TOL_PX)..(target + STRIP_WINDOW_BAR_TOL_PX)) {
                AndroidLog.i(TAG, "strip F28 SKIP: windowBar pass before=$before " +
                    "target=$target(192±4) no skip")
                return
            }
            stripSkipRunningF28 = true
            synchronized(stripSkipTriedF28) { stripSkipTriedF28.clear() }
            stripSkipWinnerF28 = null
            AndroidLog.i(TAG, "strip F28 SKIP start: before=$before target=$target(192±4) " +
                "baseline0=$stripBaseline0F28 mountCount=$stripMountCountF28 " +
                "(①skip-float→②skip-N只publish→③publish167，每步300ms，顶底槽栏键不动)")
            runCatching { trySkipFloatF28(row, decor) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F28 SKIP schedule failed: $t")
            stripSkipRunningF28 = false
        }
    }

    /** F28 ①跳过float刷新（publish192+N三连、无float，requestLayout+300ms复测）。 */
    private fun trySkipFloatF28(row: View, decor: ViewGroup) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                stripSkipRunningF28 = false
                return
            }
            if (row.parent == null) {
                stripSkipRunningF28 = false
                return
            }
            if (synchronized(stripSkipTriedF28) { stripSkipTriedF28.contains("float") }) {
                runCatching { trySkipNOnlyF28(row, decor) }
                return
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            if (winBar == null) {
                AndroidLog.e(TAG, "strip F28 SKIP ①: windowBar unmeasurable chain N-only")
                synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("float") }
                runCatching { trySkipNOnlyF28(row, decor) }
                return
            }
            val before = winBar.third
            val target = runCatching {
                slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX)
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F28 SKIP ①: target unmeasurable")
                stripSkipRunningF28 = false
                return
            }
            if (before in (target - STRIP_WINDOW_BAR_TOL_PX)..(target + STRIP_WINDOW_BAR_TOL_PX)) {
                AndroidLog.i(TAG, "strip F28 SKIP ①: already pass before=$before target=$target no skip")
                stripSkipRunningF28 = false
                return
            }
            val trueH = runCatching { stripTrueHeightOfF16(row) }.getOrNull() ?: -1
            if (trueH <= 0) {
                AndroidLog.e(TAG, "strip F28 SKIP ①: trueH unmeasurable chain N-only")
                synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("float") }
                runCatching { trySkipNOnlyF28(row, decor) }
                return
            }
            val publishH = runCatching {
                publishHeightForF20(row, trueH, STRIP_M_BOTTOM_PX)
            }.getOrNull() ?: trueH
            runCatching { publishStripHeight(row, publishH, "F28-skipFloat") }
            runCatching { refreshCandidateLayout(row, "F28-skipFloat") }
            AndroidLog.i(TAG, "strip F28 SKIP ① apply skip-float: publishH=$publishH(trueH=$trueH+25) " +
                "+N三连、无float before=$before target=$target " +
                "(requestLayout+300ms复测，顶底槽栏键不动)")
            runCatching { row.requestLayout() }
            (row.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
            ((row.parent as? ViewGroup)?.parent as? ViewGroup)?.let {
                runCatching { it.requestLayout() }
            }
            stripSkipLastKindF28 = "float"
            stripSkipLastBeforeF28 = before
            stripSkipLastTargetF28 = target
            runCatching { scheduleSkipFloatVerifyF28(row, decor, before, target) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F28 SKIP ① failed: $t")
            synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("float") }
            runCatching { trySkipNOnlyF28(row, decor) }
        }
    }

    /** F28 ①复测（300ms后delta>=4且近192才留，否则还账试②；禁盲累加）。 */
    private fun scheduleSkipFloatVerifyF28(
        row: View,
        decor: ViewGroup,
        beforeWindowBar: Int,
        target: Int
    ) {
        try {
            row.postDelayed({
                try {
                    if (stripSkipLastKindF28 != "float") return@postDelayed
                    val after = runCatching {
                        measureWindowToBarF20(row, decor)?.third
                    }.getOrNull()
                    if (after == null || beforeWindowBar <= 0) {
                        synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("float") }
                        stripSkipLastKindF28 = ""
                        AndroidLog.e(TAG, "strip F28 SKIP ① verdict=REVERT(unmeasurable): " +
                            "after=$after chain N-only")
                        runCatching { trySkipNOnlyF28(row, decor) }
                        return@postDelayed
                    }
                    val delta = beforeWindowBar - after
                    val distBefore = kotlin.math.abs(beforeWindowBar - target)
                    val distAfter = kotlin.math.abs(after - target)
                    runCatching { logWindowToBarF20(row, decor, "F28-skipFloat-verify") }
                    runCatching { logTopBottomSteadyF25(row, decor, "F28-skipFloat") }
                    if (delta >= STRIP_F28_DELTA_KEEP_PX && distAfter < distBefore) {
                        stripSkipWinnerF28 = "skip-float"
                        synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("float") }
                        AndroidLog.i(TAG, "strip F28 SKIP-winner: ①skip-float " +
                            "windowBar $beforeWindowBar->$after delta=$delta(>=4真缩) " +
                            "dist $distBefore->$distAfter target=$target(近192定胜)")
                        stripSkipRunningF28 = false
                        stripSkipLastKindF28 = ""
                        runCatching { logWindowToBarF20(row, decor, "F28-winner-skip-float") }
                    } else {
                        runCatching { revertSkipToOrigF28(row, "F28-skipFloat-revert") }
                        synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("float") }
                        stripSkipLastKindF28 = ""
                        AndroidLog.e(TAG, "strip F28 SKIP ① verdict=REVERT: " +
                            "windowBar $beforeWindowBar->$after delta=$delta(<4或远离192) " +
                            "dist $distBefore->$distAfter target=$target 还账试②")
                        runCatching { row.requestLayout() }
                        runCatching { trySkipNOnlyF28(row, decor) }
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F28 SKIP ① verify failed: $t")
                    runCatching { trySkipNOnlyF28(row, decor) }
                }
            }, POST_STABLE_DELAY_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F28 SKIP ① verify schedule failed: $t")
        }
    }

    /** F28 ②跳过N三连只publish（publish192、无N三连、无float，requestLayout+300ms复测）。 */
    private fun trySkipNOnlyF28(row: View, decor: ViewGroup) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                stripSkipRunningF28 = false
                return
            }
            if (row.parent == null) {
                stripSkipRunningF28 = false
                return
            }
            if (synchronized(stripSkipTriedF28) { stripSkipTriedF28.contains("nOnly") }) {
                runCatching { tryPublish167F28(row, decor) }
                return
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            if (winBar == null) {
                AndroidLog.e(TAG, "strip F28 SKIP ②: windowBar unmeasurable chain 167")
                synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("nOnly") }
                runCatching { tryPublish167F28(row, decor) }
                return
            }
            val before = winBar.third
            val target = runCatching {
                slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX)
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F28 SKIP ②: target unmeasurable")
                stripSkipRunningF28 = false
                return
            }
            if (before in (target - STRIP_WINDOW_BAR_TOL_PX)..(target + STRIP_WINDOW_BAR_TOL_PX)) {
                AndroidLog.i(TAG, "strip F28 SKIP ②: already pass before=$before target=$target no skip")
                stripSkipRunningF28 = false
                return
            }
            val trueH = runCatching { stripTrueHeightOfF16(row) }.getOrNull() ?: -1
            if (trueH <= 0) {
                AndroidLog.e(TAG, "strip F28 SKIP ②: trueH unmeasurable chain 167")
                synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("nOnly") }
                runCatching { tryPublish167F28(row, decor) }
                return
            }
            val publishH = runCatching {
                publishHeightForF20(row, trueH, STRIP_M_BOTTOM_PX)
            }.getOrNull() ?: trueH
            runCatching { publishStripHeight(row, publishH, "F28-skipNOnly") }
            AndroidLog.i(TAG, "strip F28 SKIP ② apply skip-N: publishH=$publishH(trueH=$trueH+25) " +
                "只publish、无N三连、无float before=$before target=$target " +
                "(requestLayout+300ms复测，顶底槽栏键不动)")
            runCatching { row.requestLayout() }
            (row.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
            ((row.parent as? ViewGroup)?.parent as? ViewGroup)?.let {
                runCatching { it.requestLayout() }
            }
            stripSkipLastKindF28 = "nOnly"
            stripSkipLastBeforeF28 = before
            stripSkipLastTargetF28 = target
            runCatching { scheduleSkipNOnlyVerifyF28(row, decor, before, target) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F28 SKIP ② failed: $t")
            synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("nOnly") }
            runCatching { tryPublish167F28(row, decor) }
        }
    }

    /** F28 ②复测（300ms后delta>=4且近192才留，否则还账试③；禁盲累加）。 */
    private fun scheduleSkipNOnlyVerifyF28(
        row: View,
        decor: ViewGroup,
        beforeWindowBar: Int,
        target: Int
    ) {
        try {
            row.postDelayed({
                try {
                    if (stripSkipLastKindF28 != "nOnly") return@postDelayed
                    val after = runCatching {
                        measureWindowToBarF20(row, decor)?.third
                    }.getOrNull()
                    if (after == null || beforeWindowBar <= 0) {
                        synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("nOnly") }
                        stripSkipLastKindF28 = ""
                        AndroidLog.e(TAG, "strip F28 SKIP ② verdict=REVERT(unmeasurable): " +
                            "after=$after chain 167")
                        runCatching { tryPublish167F28(row, decor) }
                        return@postDelayed
                    }
                    val delta = beforeWindowBar - after
                    val distBefore = kotlin.math.abs(beforeWindowBar - target)
                    val distAfter = kotlin.math.abs(after - target)
                    runCatching { logWindowToBarF20(row, decor, "F28-skipNOnly-verify") }
                    runCatching { logTopBottomSteadyF25(row, decor, "F28-skipNOnly") }
                    if (delta >= STRIP_F28_DELTA_KEEP_PX && distAfter < distBefore) {
                        stripSkipWinnerF28 = "skip-nOnly"
                        synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("nOnly") }
                        AndroidLog.i(TAG, "strip F28 SKIP-winner: ②skip-nOnly " +
                            "windowBar $beforeWindowBar->$after delta=$delta(>=4真缩) " +
                            "dist $distBefore->$distAfter target=$target(近192定胜)")
                        stripSkipRunningF28 = false
                        stripSkipLastKindF28 = ""
                        runCatching { logWindowToBarF20(row, decor, "F28-winner-skip-nOnly") }
                    } else {
                        runCatching { revertSkipToOrigF28(row, "F28-skipNOnly-revert") }
                        synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("nOnly") }
                        stripSkipLastKindF28 = ""
                        AndroidLog.e(TAG, "strip F28 SKIP ② verdict=REVERT: " +
                            "windowBar $beforeWindowBar->$after delta=$delta(<4或远离192) " +
                            "dist $distBefore->$distAfter target=$target 还账试③")
                        runCatching { row.requestLayout() }
                        runCatching { tryPublish167F28(row, decor) }
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F28 SKIP ② verify failed: $t")
                    runCatching { tryPublish167F28(row, decor) }
                }
            }, POST_STABLE_DELAY_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F28 SKIP ② verify schedule failed: $t")
        }
    }

    /** F28 ③发布高改167不+25（publish真高+N三连+float，requestLayout+300ms复测）。 */
    private fun tryPublish167F28(row: View, decor: ViewGroup) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                stripSkipRunningF28 = false
                return
            }
            if (row.parent == null) {
                stripSkipRunningF28 = false
                return
            }
            if (synchronized(stripSkipTriedF28) { stripSkipTriedF28.contains("pub167") }) {
                runCatching { logSkipStallF28(row, decor, "all-tried") }
                stripSkipRunningF28 = false
                return
            }
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            if (winBar == null) {
                AndroidLog.e(TAG, "strip F28 SKIP ③: windowBar unmeasurable STALL")
                synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("pub167") }
                runCatching { logSkipStallF28(row, decor, "unmeasurable") }
                stripSkipRunningF28 = false
                return
            }
            val before = winBar.third
            val target = runCatching {
                slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX)
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F28 SKIP ③: target unmeasurable")
                stripSkipRunningF28 = false
                return
            }
            if (before in (target - STRIP_WINDOW_BAR_TOL_PX)..(target + STRIP_WINDOW_BAR_TOL_PX)) {
                AndroidLog.i(TAG, "strip F28 SKIP ③: already pass before=$before target=$target no skip")
                stripSkipRunningF28 = false
                return
            }
            val trueH = runCatching { stripTrueHeightOfF16(row) }.getOrNull() ?: -1
            if (trueH <= 0) {
                AndroidLog.e(TAG, "strip F28 SKIP ③: trueH unmeasurable STALL")
                synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("pub167") }
                runCatching { logSkipStallF28(row, decor, "trueH-unmeasurable") }
                stripSkipRunningF28 = false
                return
            }
            val publishH = trueH
            runCatching { publishStripHeight(row, publishH, "F28-pub167") }
            runCatching { refreshCandidateLayout(row, "F28-pub167") }
            runCatching { refreshFloatWindow(row, "F28-pub167") }
            AndroidLog.i(TAG, "strip F28 SKIP ③ apply publish167: publishH=$publishH(trueH不+25) " +
                "+N三连+float before=$before target=$target(192±4) " +
                "(requestLayout+300ms复测，顶底槽栏键不动)")
            runCatching { row.requestLayout() }
            (row.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
            ((row.parent as? ViewGroup)?.parent as? ViewGroup)?.let {
                runCatching { it.requestLayout() }
            }
            stripSkipLastKindF28 = "pub167"
            stripSkipLastBeforeF28 = before
            stripSkipLastTargetF28 = target
            runCatching { schedulePublish167VerifyF28(row, decor, before, target) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F28 SKIP ③ failed: $t")
            synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("pub167") }
            runCatching { logSkipStallF28(row, decor, "exception") }
            stripSkipRunningF28 = false
        }
    }

    /** F28 ③复测（300ms后delta>=4且近192才留，否则还账记STALL；禁盲累加）。 */
    private fun schedulePublish167VerifyF28(
        row: View,
        decor: ViewGroup,
        beforeWindowBar: Int,
        target: Int
    ) {
        try {
            row.postDelayed({
                try {
                    if (stripSkipLastKindF28 != "pub167") return@postDelayed
                    val after = runCatching {
                        measureWindowToBarF20(row, decor)?.third
                    }.getOrNull()
                    if (after == null || beforeWindowBar <= 0) {
                        runCatching { revertSkipToOrigF28(row, "F28-pub167-revert-unmeasurable") }
                        synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("pub167") }
                        stripSkipLastKindF28 = ""
                        AndroidLog.e(TAG, "strip F28 SKIP ③ verdict=REVERT(unmeasurable): " +
                            "after=$after STALL")
                        runCatching { logSkipStallF28(row, decor, "pub167-unmeasurable") }
                        stripSkipRunningF28 = false
                        return@postDelayed
                    }
                    val delta = beforeWindowBar - after
                    val distBefore = kotlin.math.abs(beforeWindowBar - target)
                    val distAfter = kotlin.math.abs(after - target)
                    runCatching { logWindowToBarF20(row, decor, "F28-pub167-verify") }
                    runCatching { logTopBottomSteadyF25(row, decor, "F28-pub167") }
                    if (delta >= STRIP_F28_DELTA_KEEP_PX && distAfter < distBefore) {
                        stripSkipWinnerF28 = "publish167"
                        synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("pub167") }
                        AndroidLog.i(TAG, "strip F28 SKIP-winner: ③publish167 " +
                            "windowBar $beforeWindowBar->$after delta=$delta(>=4真缩) " +
                            "dist $distBefore->$distAfter target=$target(近192定胜)")
                        stripSkipRunningF28 = false
                        stripSkipLastKindF28 = ""
                        runCatching { logWindowToBarF20(row, decor, "F28-winner-publish167") }
                    } else {
                        runCatching { revertSkipToOrigF28(row, "F28-pub167-revert") }
                        synchronized(stripSkipTriedF28) { stripSkipTriedF28.add("pub167") }
                        stripSkipLastKindF28 = ""
                        AndroidLog.e(TAG, "strip F28 SKIP ③ verdict=REVERT: " +
                            "windowBar $beforeWindowBar->$after delta=$delta(<4或远离192) " +
                            "dist $distBefore->$distAfter target=$target 还账 STALL")
                        runCatching { row.requestLayout() }
                        runCatching { logSkipStallF28(row, decor, "pub167-no-move") }
                        stripSkipRunningF28 = false
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip F28 SKIP ③ verify failed: $t")
                    stripSkipRunningF28 = false
                }
            }, POST_STABLE_DELAY_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F28 SKIP ③ verify schedule failed: $t")
        }
    }

    /** F28全不动记STALL（只读诊断，不碰视图；三步均未KEEP且windowBar仍远192时记）。 */
    private fun logSkipStallF28(row: View, decor: ViewGroup, reason: String) {
        try {
            if (stripSkipWinnerF28 != null) return
            if (stripSkipLastKindF28.isNotEmpty()) return
            val winBar = runCatching { measureWindowToBarF20(row, decor) }.getOrNull()
            val windowBar = winBar?.third ?: -1
            val target = runCatching {
                slotTargetHeightForF16(row, STRIP_M_BOTTOM_PX)
            }.getOrNull() ?: -1
            AndroidLog.e(TAG, "strip F28 STALL: reason=$reason windowBar=$windowBar " +
                "target=$target(192±4) tried=${synchronized(stripSkipTriedF28) {
                    stripSkipTriedF28.toList()
                }} winner=${stripSkipWinnerF28} " +
                "(三步delta<4或远离192已还账，全不动；[0]/键盘/图标钉死；壳/s0/圆角B/DEL/commit/logo不动)")
            runCatching { logWindowToBarF20(row, decor, "F28-STALL-$reason") }
            runCatching { logTopBottomSteadyF25(row, decor, "F28-STALL-$reason") }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F28 STALL log failed: $t")
        }
    }

    /** F28退壳还账（幂等）：跳过对照状态清，只撤销我方标志；高度流由调用方原值恢复，无残留视图账。 */
    private fun restoreSkipF28() {
        try {
            stripSkipRunningF28 = false
            stripSkipWinnerF28 = null
            stripSkipLastKindF28 = ""
            stripSkipLastBeforeF28 = -1
            stripSkipLastTargetF28 = -1
            synchronized(stripSkipTriedF28) {
                if (stripSkipTriedF28.isNotEmpty()) stripSkipTriedF28.clear()
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F28 restore failed: $t")
        }
    }

    // F30（接F29 mount-once已合入未提交，在此基础上改，不reset）：C33铁证（以此为准）：
    // mountCount=1叠加排除；Y=192一次；N参正常；float missing；基线windowBar0=25
    // （windowTop1340 barTop1365）；条态280/顶5/缝152/零位移（稳态内）；slot192。
    // 主控新判：跨态对比基线图标cy≈1415（C9视觉）vs条态viewCy1488——工具栏可能被顶下约70px，
    // 之前“零位移”全是稳态内比较，从未跨态比过。
    // 修：①无条键盘页稳态记基线快照（windowTop/图标簇cy top/栏顶/Q排顶，关键字baseline-snap）；
    // ②条态同口径记snap（strip-snap）；③双份diff打日志（各差值，关键字drift）。
    // 全只读几何，不碰视图LP/位移/顶底槽/壳/s0/圆角B/DEL/commit/logo/退壳。
    private data class StripSnapF30(
        val windowTop: Int,
        val iconCy: Int,
        val iconTop: Int,
        val iconN: Int,
        val barTop: Int,
        val qTop: Int
    )
    @Volatile
    private var stripBaselineSnapF30: StripSnapF30? = null
    @Volatile
    private var stripStripSnapF30: StripSnapF30? = null

    /** F30同口径现量（只读）：cand顶/图标簇cy top/栏顶/Q排顶同函数同源，无条条态同一口径。核心未布局返null。 */
    private fun measureSnapF30(decor: ViewGroup): StripSnapF30? {
        return try {
            val cand = findCandNoRowF28(decor) ?: return null
            val cloc = IntArray(2)
            runCatching { cand.getLocationOnScreen(cloc) }
            val windowTop = cloc[1]
            if (windowTop <= 0) return null
            val logo = runCatching { resolveLogoView(decor) }.getOrNull()
            val iconLine = runCatching { resolveIconLineF22(decor, logo) }.getOrNull()
                ?: return null
            if (iconLine.top <= 0 || iconLine.centerY <= 0) return null
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
            val bloc = IntArray(2)
            if (bar != null) runCatching { bar.getLocationOnScreen(bloc) }
            val barTop = if (bar != null) bloc[1] else -1
            val qTop = runCatching { findQTopOnScreen(decor) }.getOrNull() ?: -1
            StripSnapF30(
                windowTop = windowTop,
                iconCy = iconLine.centerY.toInt(),
                iconTop = iconLine.top.toInt(),
                iconN = iconLine.n,
                barTop = barTop,
                qTop = qTop
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** F30无条基线快照（只读不碰视图）：无条键盘页稳态windowTop/图标簇/栏顶/Q排顶，关键字baseline-snap。 */
    private fun logBaselineSnapF30(decor: ViewGroup, tag: String): StripSnapF30? {
        return try {
            val hasStrip = runCatching {
                decor.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) != null
            }.getOrDefault(true)
            if (hasStrip) {
                AndroidLog.e(TAG, "strip F30 baseline-snap [$tag]: skipped hasStrip=true (无条稳态才记)")
                return null
            }
            val s = measureSnapF30(decor)
            if (s == null) {
                AndroidLog.e(TAG, "strip F30 baseline-snap [$tag]: unmeasurable " +
                    "(cand/icon unlaid, mountCount=$stripMountCountF28)")
                return null
            }
            stripBaselineSnapF30 = s
            val windowBar0 = if (s.barTop > 0) s.barTop - s.windowTop else -999
            AndroidLog.i(TAG, "strip F30 baseline-snap [$tag]: windowTop=${s.windowTop} " +
                "iconCy=${s.iconCy} iconTop=${s.iconTop} n=${s.iconN} barTop=${s.barTop} qTop=${s.qTop} " +
                "windowBar0=$windowBar0 mountCount=$stripMountCountF28 (无条基线快照，同口径)")
            runCatching { logDriftF30("after-baseline-$tag") }
            s
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F30 baseline-snap failed [$tag]: $t")
            null
        }
    }

    /** F30条态快照（只读不碰视图）：条态同口径windowTop/图标簇/栏顶/Q排顶，关键字strip-snap。 */
    private fun logStripSnapF30(decor: ViewGroup, row: View, tag: String): StripSnapF30? {
        return try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return null
            if (row.parent == null) return null
            val s = measureSnapF30(decor)
            if (s == null) {
                AndroidLog.e(TAG, "strip F30 strip-snap [$tag]: unmeasurable (cand/icon unlaid)")
                return null
            }
            stripStripSnapF30 = s
            val windowBar = if (s.barTop > 0) s.barTop - s.windowTop else -999
            val rloc = IntArray(2)
            runCatching { row.getLocationOnScreen(rloc) }
            val rowTop = rloc[1]
            val stripBottom = if (rowTop > 0 && row.height > 0) rowTop + row.height else -1
            val topGap = if (rowTop > 0) rowTop - s.windowTop else -999
            val gapToIcon = if (stripBottom > 0) s.iconTop - stripBottom else -999
            AndroidLog.i(TAG, "strip F30 strip-snap [$tag]: windowTop=${s.windowTop} " +
                "iconCy=${s.iconCy} iconTop=${s.iconTop} n=${s.iconN} barTop=${s.barTop} qTop=${s.qTop} " +
                "windowBar=$windowBar rowTop=$rowTop stripBottom=$stripBottom topGap=$topGap gapToIcon=$gapToIcon " +
                "(条态快照，同口径)")
            runCatching { logDriftF30("after-strip-$tag") }
            s
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F30 strip-snap failed [$tag]: $t")
            null
        }
    }

    /** F30跨态diff（只读）：基线vs条态各差值，关键字drift。之前零位移全是稳态内比较，从未跨态比过。 */
    private fun logDriftF30(tag: String): Boolean {
        return try {
            val b = stripBaselineSnapF30 ?: run {
                AndroidLog.i(TAG, "strip F30 drift [$tag]: pending baseline-missing " +
                    "strip=${stripStripSnapF30} (等双态齐再比)")
                return false
            }
            val s = stripStripSnapF30 ?: run {
                AndroidLog.i(TAG, "strip F30 drift [$tag]: pending strip-missing " +
                    "baseline=$b (等双态齐再比)")
                return false
            }
            val dWindowTop = s.windowTop - b.windowTop
            val dIconCy = s.iconCy - b.iconCy
            val dIconTop = s.iconTop - b.iconTop
            val dBarTop = if (s.barTop > 0 && b.barTop > 0) s.barTop - b.barTop else -999
            val dQTop = if (s.qTop > 0 && b.qTop > 0) s.qTop - b.qTop else -999
            val bBar = if (b.barTop > 0) b.barTop - b.windowTop else -999
            val sBar = if (s.barTop > 0) s.barTop - s.windowTop else -999
            val dWindowBar = if (bBar > -999 && sBar > -999) sBar - bBar else -999
            AndroidLog.i(TAG, "strip F30 drift [$tag]: dWindowTop=$dWindowTop dIconCy=$dIconCy " +
                "dIconTop=$dIconTop dBarTop=$dBarTop dQTop=$dQTop dWindowBar=$dWindowBar " +
                "baseline=(win=${b.windowTop} cy=${b.iconCy} top=${b.iconTop} n=${b.iconN} bar=${b.barTop} q=${b.qTop} bar0=$bBar) " +
                "strip=(win=${s.windowTop} cy=${s.iconCy} top=${s.iconTop} n=${s.iconN} bar=${s.barTop} q=${s.qTop} bar=$sBar) " +
                "(跨态对比，工具栏下移约70px看dIconCy/dBarTop)")
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F30 drift failed [$tag]: $t")
            false
        }
    }

    /** F23条底屏坐标（只读）：row屏底，未布局返null。 */
    private fun stripBottomScreenOfF23(row: View): Int? {
        return try {
            val loc = IntArray(2)
            runCatching { row.getLocationOnScreen(loc) }
            if (loc[1] <= 0 || row.height <= 0) return null
            loc[1] + row.height
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * F23波段位图捕获（只读）：decor在[bandTopScreen,bandBottomScreen)合成像素裁剪。
     * 主线程decor.draw到band画布（translate -offset），供圆检测用；失败返null。
     * F33注：模块内像素两版连败（F22 drawable直采brightPass=0；本F23 decor.draw合成C36 nPix=-1），
     * 而外部tools/open-strip.sh同算法（seed238/mask228/灰带白圆/蓝校验）对同屏截图可用——
     * 根因是模块内decor.draw取合成像素失败（与外部adb screencap位图不同源），非算法问题；
     * 故F32验证门内本扫描仅记diag不再参与PASS/FAIL/REVERT（见tryMoveBarBelowCandF32/scheduleBarVerifyF32），
     * 本函数保留继续修但不gate。只读不碰视图/顶底槽栏键/壳/s0/圆角B/DEL/commit/logo。
     */
    private fun captureBandBitmapF23(
        decor: ViewGroup,
        bandTopScreen: Int,
        bandBottomScreen: Int
    ): Bitmap? {
        return try {
            if (bandBottomScreen <= bandTopScreen + 10) return null
            val dloc = IntArray(2)
            runCatching { decor.getLocationOnScreen(dloc) }
            if (dloc[1] <= 0 || decor.width <= 0 || decor.height <= 0) return null
            val offset = bandTopScreen - dloc[1]
            val bandH = bandBottomScreen - bandTopScreen
            if (offset < 0 || offset + bandH > decor.height) return null
            if (decor.width > 1600 || bandH > 800 || bandH <= 0) return null
            val bmp = Bitmap.createBitmap(decor.width, bandH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            runCatching {
                canvas.save()
                canvas.translate(0f, -offset.toFloat())
                decor.draw(canvas)
                canvas.restore()
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /** F23圆命中（波段位图坐标，供聚类用）。 */
    private data class BandCircleF23(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    /** F23 ring_gray（抄open-strip.sh）：上下左右外3px须灰150~235，否则连片淘汰。 */
    private fun isRingGrayF23(pix: IntArray, bw: Int, bh: Int, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        return try {
            val cx = (x0 + x1) / 2
            val cy = (y0 + y1) / 2
            val pts = arrayOf(cx to y0 - 3, cx to y1 + 3, x0 - 3 to cy, x1 + 3 to cy)
            for ((x, y) in pts) {
                if (x < 0 || x >= bw || y < 0 || y >= bh) return false
                val p = pix[y * bw + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val m = minOf(r, g, b)
                if (m > 235 || m < 150) return false
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** F23 has_blue（抄open-strip.sh）：首圆须含蓝logo（b>150且b>r+40 count>30），否则非工具栏组。 */
    private fun hasBlueF23(pix: IntArray, bw: Int, bh: Int, c: BandCircleF23): Boolean {
        return try {
            var n = 0
            var y = c.y0
            while (y < c.y1) {
                var x = c.x0
                while (x < c.x1) {
                    if (x >= 0 && x < bw && y >= 0 && y < bh) {
                        val p = pix[y * bw + x]
                        val r = (p shr 16) and 0xFF
                        val b = p and 0xFF
                        if (b > 150 && b > r + 40) {
                            n++
                            if (n > 30) return true
                        }
                    }
                    x += 3
                }
                y += 3
            }
            n > 30
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * F23波段找圆（直接抄tools/open-strip.sh detect/find_circles：seed238/mask228，
     * W相对0.055~0.115/容差0.02W，填充0.50~0.85，灰底孤立环）。
     * 输入波段位图像素，只读；返圆列表（位图坐标）。
     */
    private fun findCirclesInBandF23(bmp: Bitmap): List<BandCircleF23> {
        val out = ArrayList<BandCircleF23>()
        try {
            val bw = bmp.width
            val bh = bmp.height
            if (bw <= 0 || bh <= 0) return out
            val wMin = (bw * 0.055f).toInt()
            val wMax = (bw * 0.115f).toInt()
            val tol = (bw * 0.02f).toInt()
            val seedThr = 238
            val maskThr = 228
            val pix = IntArray(bw * bh)
            runCatching { bmp.getPixels(pix, 0, bw, 0, 0, bw, bh) }
            val seen = BooleanArray(bw * bh)
            fun lumMinAt(x: Int, y: Int): Int {
                val p = pix[y * bw + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                return minOf(r, g, b)
            }
            var sy = 0
            while (sy < bh) {
                var sx = 0
                while (sx < bw) {
                    val pos = sy * bw + sx
                    if (!seen[pos] && lumMinAt(sx, sy) >= seedThr) {
                        // BFS 4邻（maskThr），记 bounds/计数，超限即弃。
                        val q: ArrayDeque<Int> = ArrayDeque()
                        q.add(pos)
                        seen[pos] = true
                        var x0 = sx
                        var x1 = sx
                        var yy0 = sy
                        var yy1 = sy
                        var n = 0
                        var over = false
                        while (q.isNotEmpty()) {
                            val cur = q.removeFirst()
                            val cx = cur % bw
                            val cy = cur / bw
                            n++
                            if (cx < x0) x0 = cx
                            if (cx > x1) x1 = cx
                            if (cy < yy0) yy0 = cy
                            if (cy > yy1) yy1 = cy
                            if (x1 - x0 > wMax + 15 || yy1 - yy0 > wMax + 20) {
                                over = true
                                break
                            }
                            val nbs = intArrayOf(cur - 1, cur + 1, cur - bw, cur + bw)
                            for (nb in nbs) {
                                if (nb < 0 || nb >= pix.size) continue
                                val nx = nb % bw
                                val ny = nb / bw
                                // 4邻越界（左右换行）排除。
                                if (kotlin.math.abs(nx - cx) + kotlin.math.abs(ny - cy) != 1) continue
                                if (seen[nb]) continue
                                seen[nb] = true
                                if (lumMinAt(nx, ny) >= maskThr) q.add(nb)
                            }
                            // 防超大连通域卡顿。
                            if (n > 20000) {
                                over = true
                                break
                            }
                        }
                        if (!over) {
                            val w = x1 - x0
                            val h = yy1 - yy0
                            if (wMin <= w && w <= wMax && wMin <= h && h <= wMax + 8 &&
                                kotlin.math.abs(w - h) <= tol
                            ) {
                                val fill = n.toFloat() / maxOf(1, w * h).toFloat()
                                if (fill in 0.50f..0.85f) {
                                    if (isRingGrayF23(pix, bw, bh, x0, yy0, x1, yy1)) {
                                        out.add(BandCircleF23(x0, yy0, x1, yy1))
                                    }
                                }
                            }
                        }
                    } else {
                        seen[pos] = true
                    }
                    sx += 2
                }
                sy += 2
            }
        } catch (_: Throwable) {
        }
        return out.sortedWith(compareBy<BandCircleF23> { it.x0 }.thenBy { it.y0 })
    }

    /**
     * F23像素波段扫描（直接抄tools/open-strip.sh detect波段算法：stripBottom→Q_top灰带白圆阈值，
     * seed238/mask228/0.055~0.115W/填充0.50~0.85/灰环150~235/间距0.07~0.45/0.07~0.18，
     * 修brightPass=0失败——旧drawable采样看未合成drawable，波段看合成后像素与外部脚本同源）。
     * 只读decor合成像素+几何，不碰视图/顶底槽栏键/壳/s0/圆角B/DEL/commit/logo。
     * 失败返null（调用方记diag不dropped）。
     */
    private fun scanPixelBandF23(decor: ViewGroup, logo: View?, row: View?): IconLine? {
        var bandBmp: Bitmap? = null
        return try {
            val dm = decor.resources.displayMetrics
            val wPx = dm.widthPixels
            val hPx = dm.heightPixels
            if (wPx <= 0 || hPx <= 0) return null
            val qTop = runCatching { findQTopOnScreen(decor) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "strip F23 pixel band: qTop missing (diag only)")
                return null
            }
            // 波段：stripBottom→Q_top（抄detect白卡/Q分水岭思想，灰带白圆阈值）；
            // y_hi=q_top-0.005H（脚本同值，F22的100px过严会把cy1416/Q1494真簇排除，本次按脚本12px）；
            // y_lo=stripBottom（有条时）否则0.45H（脚本同值）。
            val stripB = row?.let { runCatching { stripBottomScreenOfF23(it) }.getOrNull() }
            val yHi = qTop - (hPx * 0.005f).toInt()
            val yLo = stripB ?: (hPx * 0.45f).toInt()
            if (yHi <= yLo + 10) {
                AndroidLog.e(TAG, "strip F23 pixel band: empty band yLo=$yLo yHi=$yHi qTop=$qTop stripB=$stripB")
                return null
            }
            bandBmp = runCatching { captureBandBitmapF23(decor, yLo, yHi) }.getOrNull()
            if (bandBmp == null) {
                AndroidLog.e(TAG, "strip F23 pixel band: capture null yLo=$yLo yHi=$yHi qTop=$qTop")
                return null
            }
            val bw = bandBmp.width
            val bh = bandBmp.height
            val circles = runCatching { findCirclesInBandF23(bandBmp) }.getOrDefault(emptyList())
            val brightPass = circles.size
            AndroidLog.i(TAG, "strip F23 pixel band: circles=$brightPass brightPass=$brightPass " +
                "yLo=$yLo(stripB=$stripB) yHi=$yHi(qTop-0.005H) qTop=$qTop bw=$bw bh=$bh " +
                "seed238/mask228/0.055~0.115W/fill0.50~0.85/ring150~235(抄detect)")
            if (circles.isEmpty()) {
                AndroidLog.e(TAG, "strip F23 pixel band: samples too few n=0 need=n>=5 cyTol=28px")
                return null
            }
            // 按纵列分组（cy容差28px，F7/F22同值；脚本0.012H≈28px@2400H同量级）。
            data class Cl(val items: List<Triple<Int, Int, BandCircleF23>>)
            val items = circles.map {
                val cy = yLo + (it.y0 + it.y1) / 2
                val cx = (it.x0 + it.x1) / 2
                val left = it.x0
                Triple(cx, cy, it)
            }
            val byY = items.sortedBy { it.second }
            val cyTolPx = 28
            val clusters = ArrayList<Cl>()
            var i = 0
            while (i < byY.size) {
                var j = i
                while (j < byY.size && byY[j].second - byY[i].second <= cyTolPx) j++
                val slice = byY.subList(i, j)
                if (slice.size >= 5) clusters.add(Cl(slice.toList()))
                i++
            }
            if (clusters.isEmpty()) {
                AndroidLog.e(TAG, "strip F23 pixel band: no cluster n=${items.size} need=n>=5 cyTol=28px")
                return null
            }
            val sortedCl = clusters.sortedWith(
                compareByDescending<Cl> { it.items.size }
                    .thenBy { cl -> cl.items.map { it.second }.average() }
            )
            val gapMin = (wPx * 0.07f).toInt()
            val gapMax = (wPx * 0.18f).toInt()
            val gapFirstMax = (wPx * 0.45f).toInt()
            val yTol = hPx * 0.012f
            for (cl in sortedCl) {
                val byX = cl.items.sortedBy { it.third.x0 }
                val dxs = ArrayList<Int>()
                for (k in 0 until byX.size - 1) dxs.add(byX[k + 1].third.x0 - byX[k].third.x0)
                // 首圆蓝校验（抄detect has_blue(g[0])，防候选散字成组）。
                val pixArr = IntArray(bw * bh)
                runCatching { bandBmp.getPixels(pixArr, 0, bw, 0, 0, bw, bh) }
                val blueOk = runCatching { hasBlueF23(pixArr, bw, bh, byX[0].third) }.getOrDefault(true)
                AndroidLog.i(TAG, "strip F23 pixel band: cluster n=${cl.items.size}/${items.size} " +
                    "xs=${byX.map { it.third.x0 }} dxs=$dxs gap=[$gapMin,$gapMax] firstMax=$gapFirstMax blueOk=$blueOk")
                if (!blueOk) continue
                val wideFirst = dxs.isNotEmpty() && dxs[0] in gapMin..gapFirstMax &&
                    (dxs.size == 1 || dxs.subList(1, dxs.size).all { it in gapMin..gapMax })
                val uniform = dxs.isNotEmpty() && dxs.all { it in gapMin..gapMax }
                // 间距仅诊断（F7放宽同源，不过滤，防bar maxKids=4误杀）。
                var useItems = byX
                if (logo != null && useItems.isNotEmpty()) {
                    val lloc = IntArray(2)
                    runCatching { logo.getLocationOnScreen(lloc) }
                    val firstCy = useItems[0].second.toFloat()
                    if (lloc[1] > 0 && kotlin.math.abs(firstCy - (lloc[1] + logo.height / 2f)) <= yTol &&
                        kotlin.math.abs(useItems[0].third.x0 - lloc[0]) <= wPx * 0.05f
                    ) {
                        useItems = useItems.subList(1, useItems.size)
                    }
                }
                if (useItems.isEmpty()) continue
                var sum = 0f
                var hSum = 0
                var hN = 0
                for ((_, cy, c) in useItems) {
                    sum += cy.toFloat()
                    val h = c.y1 - c.y0
                    if (h > 0) { hSum += h; hN++ }
                }
                if (hN == 0) continue
                val lineY = sum / useItems.size
                val avgH = hSum.toFloat() / hN
                val halfH = avgH / 2f
                val iconTop = lineY - halfH
                AndroidLog.i(TAG, "strip F23 pixel band hit: n=${useItems.size} y=${lineY.toInt()} " +
                    "top=${iconTop.toInt()} avgH=${avgH.toInt()} qTop=$qTop " +
                    "mode=${if (wideFirst) "wideFirst" else if (uniform) "uniform" else "relaxed-nogate"}")
                return IconLine(lineY, halfH, iconTop, useItems.size)
            }
            AndroidLog.e(TAG, "strip F23 pixel band: no group passed blue/spacing n=${items.size}")
            null
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F23 pixel band failed: $t")
            null
        } finally {
            runCatching {
                val b = bandBmp
                if (b != null && !b.isRecycled && b.width <= 1600) runCatching { b.recycle() }
            }
        }
    }

    /** F14封存：F13旧实现（停用，见上；互抵空转根因，不再调用）。 */
    private fun followSlotTopForStripShiftDisabled(row: View, totalDy: Float) {
        try {
            if (row.getTag() != TAG_SEARCH_BOX_CONTAINER) return
            val slot = row.parent as? ViewGroup ?: return
            // fail-closed：slot必须含我方条（防误收工具栏/键盘容器）。
            if (slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) !== row &&
                slot.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null
            ) {
                return
            }
            val lp = slot.layoutParams as? ViewGroup.MarginLayoutParams ?: run {
                AndroidLog.e(TAG, "strip follow F13: slot no MarginLP ${slot.javaClass.simpleName}")
                return
            }
            val targetTop = (-totalDy).roundToInt().coerceAtMost(0)
            synchronized(stripTopMarginOrig) {
                if (!stripTopMarginOrig.containsKey(slot)) {
                    stripTopMarginOrig[slot] = lp.topMargin
                }
            }
            if (lp.topMargin != targetTop) {
                val before = lp.topMargin
                lp.topMargin = targetTop
                slot.layoutParams = lp
                AndroidLog.i(TAG, "strip follow F13: slot=${slot.javaClass.simpleName} " +
                    "topMargin $before->$targetTop totalDy=${totalDy.toInt()} " +
                    "window->strip=1.5dp(mTop) strip->icon=20px")
            }
            runCatching { slot.requestLayout() }
            // cand WRAP自跟随：只requestLayout不改margin（防双倍收敛），高度链收掉dy由N三连+float重算窗高落实。
            val cand = slot.parent as? ViewGroup
            if (cand != null) runCatching { cand.requestLayout() }
            // 窗高跟随：N三连重算+float重排（高度同步，不碰工具栏/键盘/壳/s0/圆角B/DEL/commit/logo）。
            runCatching { refreshCandidateLayout(row, "postAlignF13-follow") }
            runCatching { refreshFloatWindow(row, "postAlignF13-follow") }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip follow F13 failed: $t")
        }
    }

    private fun stripDyOf(row: View): Float {
        return try {
            synchronized(stripDyApplied) { stripDyApplied.getOrDefault(row, 0f) }
        } catch (_: Throwable) {
            0f
        }
    }

    /** F10退条还条位移（单视图，幂等；只撤销我方增量，不碰宿主值）。F14同还rowMargin+pending。 */
    private fun restoreStripShift(row: View) {
        try {
            val applied = synchronized(stripDyApplied) { stripDyApplied.remove(row) } ?: 0f
            val orig = synchronized(stripTransOrig) { stripTransOrig.remove(row) }
            if (kotlin.math.abs(applied) > 0.5f) {
                if (orig != null) row.translationY = orig
                else row.translationY = row.translationY - applied
                runCatching { row.requestLayout() }
                AndroidLog.i(TAG, "strip shift F10 restored dy=$applied")
            }
            runCatching { restoreStripRowMargin(row) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip shift restore failed: $t")
        }
    }

    /** F10退壳还全部条位移（幂等；view已摘则只清账）。F14同还全部rowMargin+pending。 */
    private fun restoreAllStripShifts() {
        try {
            var n = 0
            val keys = synchronized(stripDyApplied) { stripDyApplied.keys.toList() }
            for (v in keys) {
                try {
                    val applied = synchronized(stripDyApplied) { stripDyApplied.remove(v) } ?: 0f
                    val orig = synchronized(stripTransOrig) { stripTransOrig.remove(v) }
                    if (kotlin.math.abs(applied) > 0.5f && v.parent != null) {
                        if (orig != null) v.translationY = orig
                        else v.translationY = v.translationY - applied
                        runCatching { v.requestLayout() }
                    }
                    runCatching { restoreStripRowMargin(v) }
                    n++
                } catch (_: Throwable) {
                }
            }
            synchronized(stripTransOrig) {
                if (stripTransOrig.isNotEmpty() && keys.isEmpty()) stripTransOrig.clear()
            }
            // F14：仅margin模式（trans为0但margin有值）的残键同还。
            runCatching {
                val mKeys = synchronized(stripRowMarginApplied) {
                    stripRowMarginApplied.keys.toList()
                }
                for (v in mKeys) {
                    runCatching { restoreStripRowMargin(v) }
                }
                synchronized(stripPendingVerify) {
                    if (stripPendingVerify.isNotEmpty() && keys.isEmpty() && mKeys.isEmpty()) {
                        stripPendingVerify.clear()
                    }
                }
            }
            if (n > 0) AndroidLog.i(TAG, "strip shift F10 restored all n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip shift restore all failed: $t")
        }
    }

    /**
     * F10全拆：F8工具栏行负位移已停用（死命令：工具栏钉死原位，什么都不准变）。
     * 记账map与restoreToolbarShift保留（退壳时旧位移全还），但不再施加任何新位移。
     */
    private fun applyToolbarShift(bar: View, dy: Float): Float {
        AndroidLog.i(TAG, "toolbar shift F10 disabled: pinned, drop dy=$dy " +
            "bar=${bar.javaClass.simpleName}")
        return 0f
    }

    /** F10封存：F8旧实现（停用，见上）。 */
    private fun applyToolbarShiftDisabled(bar: View, dy: Float): Float {
        return try {
            if (kotlin.math.abs(dy) < 0.5f) return 0f
            synchronized(toolbarTransOrig) {
                if (!toolbarTransOrig.containsKey(bar)) {
                    toolbarTransOrig[bar] = bar.translationY
                }
            }
            val applied = synchronized(toolbarDyApplied) {
                toolbarDyApplied.getOrDefault(bar, 0f)
            }
            val next = applied + dy
            bar.translationY = (synchronized(toolbarTransOrig) {
                toolbarTransOrig[bar] ?: (bar.translationY - applied)
            } + next)
            synchronized(toolbarDyApplied) { toolbarDyApplied[bar] = next }
            val bloc = IntArray(2)
            runCatching { bar.getLocationOnScreen(bloc) }
            AndroidLog.i(TAG, "toolbar shift F8: dy=$dy applied=$next barTop=${bloc[1]} " +
                "bar=${bar.javaClass.simpleName}")
            next
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar shift apply failed: $t")
            0f
        }
    }

    /**
     * F9新作用点解析（C12：旧作用点=bar容器非图标真父，dy=-145后barTop 1321→1176
     * 但视觉图标仍1371灰缝151不动；或父链clip吃位移，或该bar非图标行）。
     * 优先图标叶子共祖父LCA（scan簇叶子最近公共父），无LCA才回退bar行本身。
     * 只读几何，不碰视图。
     */
    private fun isAncestorOf(anc: ViewGroup, v: View): Boolean {
        return try {
            var p: android.view.ViewParent? = v.parent
            while (p != null) {
                if (p === anc) return true
                p = p.parent
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun findCommonAncestor(views: List<View>, stopAt: ViewGroup): ViewGroup? {
        return try {
            if (views.isEmpty()) return null
            if (views.size == 1) return views[0].parent as? ViewGroup
            var p: ViewGroup? = views[0].parent as? ViewGroup ?: return null
            while (p != null) {
                var all = true
                for (i in 1 until views.size) {
                    if (!isAncestorOf(p, views[i])) { all = false; break }
                }
                if (all) return p
                if (p === stopAt) break
                p = p.parent as? ViewGroup
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * F9图标簇叶子收集（与scanSquareIconLine同阈值同源：W相对0.055~0.115/容差0.02W，
     * cy聚类±28px n>=5，Q_top带内；仅返最大簇去logo后叶子Views，供LCA用）。
     * 只读不碰视图；失败返空（调用方回退bar）。
     */
    private fun collectIconClusterLeafViews(decor: ViewGroup, logo: View?): List<View> {
        return try {
            val dm = decor.resources.displayMetrics
            val wPx = dm.widthPixels
            val hPx = dm.heightPixels
            if (wPx <= 0 || hPx <= 0) return emptyList()
            val wMin = wPx * 0.055f
            val wMax = wPx * 0.115f
            val whTol = wPx * 0.02f
            val yTol = hPx * 0.012f
            val dloc = IntArray(2)
            runCatching { decor.getLocationOnScreen(dloc) }
            val decorH = decor.height
            val qTop = findQTopOnScreen(decor)
            // F22 Q_top带限行：cy<Q_top-100px（防Q键上半/第二工具栏/AI条残留误检；
            // 旧0.005H≈12px太贴Q排，C25疑1437即Q排顶1494附近误检）。qTop null则回退decor带。
            val yHi = if (qTop != null) (qTop - STRIP_ICON_Q_GUARD_PX).toInt()
                else dloc[1] + (decorH * 0.85f).toInt()
            val yLo = dloc[1] + (decorH * 0.30f).toInt()
            data class Sq(val x: Float, val y: Float, val left: Int, val h: Int, val v: View)
            val cands = ArrayList<Sq>()
            var hops = 0
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            while (q.isNotEmpty() && hops < 800) {
                val v = q.removeFirst()
                hops++
                if (v.getTag() == TAG_SEARCH_BUTTON ||
                    v.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                    v.getTag() == TAG_SEARCH_CLEAR
                ) continue
                if (v.visibility != View.VISIBLE || !v.isShown) {
                    if (v is ViewGroup) continue else continue
                }
                if (v is ViewGroup) {
                    val w = v.width
                    val h = v.height
                    val square = w > 0 && h > 0 && w >= wMin && w <= wMax &&
                        h >= wMin && h <= wMax &&
                        kotlin.math.abs(w - h) <= whTol
                    if (square && v !== decor && rowSlotHasImage(v)) {
                        val loc = IntArray(2)
                        runCatching { v.getLocationOnScreen(loc) }
                        val cy = loc[1] + h / 2f
                        val cx = loc[0] + w / 2f
                        val inBand = if (decorH > 0 && loc[1] > 0) {
                            cy > yLo && cy < yHi && loc[1] > dloc[1]
                        } else true
                        if (inBand && loc[1] > 0) cands.add(Sq(cx, cy, loc[0], h, v))
                        continue
                    }
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                } else {
                    val tv = v as? android.widget.TextView
                    if (tv != null && tv.text?.toString()?.trim()?.length == 1) continue
                    if (!isToolbarImageLeaf(v)) {
                        val w = v.width
                        val h = v.height
                        if (!(w > 0 && h > 0 && w >= wMin && w <= wMax &&
                                h >= wMin && h <= wMax &&
                                kotlin.math.abs(w - h) <= whTol)
                        ) continue
                    } else {
                        val w = v.width
                        val h = v.height
                        if (!(w > 0 && h > 0 && w >= wMin && w <= wMax &&
                                h >= wMin && h <= wMax &&
                                kotlin.math.abs(w - h) <= whTol)
                        ) continue
                    }
                    val w = v.width
                    val h = v.height
                    val loc = IntArray(2)
                    runCatching { v.getLocationOnScreen(loc) }
                    if (loc[1] <= 0) continue
                    val cy = loc[1] + h / 2f
                    val cx = loc[0] + w / 2f
                    if (decorH > 0) {
                        if (!(cy > yLo && cy < yHi)) continue
                    }
                    cands.add(Sq(cx, cy, loc[0], h, v))
                }
            }
            if (cands.size < 5) return emptyList()
            val cyTolPx = 28f
            val byY = cands.sortedBy { it.y }
            data class Cl(val items: List<Sq>)
            val clusters = ArrayList<Cl>()
            var i = 0
            while (i < byY.size) {
                var j = i
                while (j < byY.size && byY[j].y - byY[i].y <= cyTolPx) j++
                val slice = byY.subList(i, j)
                if (slice.size >= 5) clusters.add(Cl(slice.toList()))
                i++
            }
            if (clusters.isEmpty()) return emptyList()
            val sortedCl = clusters.sortedWith(
                compareByDescending<Cl> { it.items.size }
                    .thenBy { cl -> cl.items.map { it.y }.average() }
            )
            val best = sortedCl.firstOrNull() ?: return emptyList()
            var useItems = best.items.sortedBy { it.left }
            if (logo != null && useItems.isNotEmpty()) {
                val lloc = IntArray(2)
                runCatching { logo.getLocationOnScreen(lloc) }
                if (lloc[1] > 0 && kotlin.math.abs(useItems[0].y - (lloc[1] + logo.height / 2f)) <= yTol &&
                    kotlin.math.abs(useItems[0].left - lloc[0]) <= wPx * 0.05f
                ) {
                    useItems = useItems.subList(1, useItems.size)
                }
            }
            useItems.map { it.v }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** F9图标叶子最近公共父（LCA）；过高（decor本身）或含条子树则返null由调用方回退bar。 */
    private fun findIconClusterLca(decor: ViewGroup, logo: View?): ViewGroup? {
        return try {
            val leaves = collectIconClusterLeafViews(decor, logo)
            if (leaves.size < 5) return null
            val lca = findCommonAncestor(leaves, decor) ?: return null
            if (lca === decor) return null
            if (lca.getTag() == TAG_SEARCH_BOX_CONTAINER) return null
            if (lca.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) != null) {
                // LCA含条子树=过高共祖（含候选区），不是图标行真父，回退bar。
                return null
            }
            lca
        } catch (_: Throwable) {
            null
        }
    }

    /** F10全拆：F9解链clip已停用（工具栏钉死，记账/restore保留，不再施加）。 */
    private fun unclipToolbarChain(target: View, decor: ViewGroup) {
        AndroidLog.i(TAG, "toolbar unclip F10 disabled: pinned, no-op " +
            "target=${target.javaClass.simpleName}")
    }

    /** F10封存：F9旧实现（停用，见上）。 */
    private fun unclipToolbarChainDisabled(target: View, decor: ViewGroup) {
        try {
            var n = 0
            var p: ViewGroup? = if (target is ViewGroup) target else target.parent as? ViewGroup
            var guard = 0
            while (p != null && guard < 6) {
                synchronized(toolbarClipOrig) {
                    if (!toolbarClipOrig.containsKey(p)) {
                        toolbarClipOrig[p] = (p.clipChildren to p.clipToPadding)
                    }
                }
                p.clipChildren = false
                p.clipToPadding = false
                n++
                if (p === decor) break
                p = p.parent as? ViewGroup
                guard++
            }
            AndroidLog.i(TAG, "toolbar shift F9: unclipped $n levels target=${target.javaClass.simpleName}")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar unclip failed: $t")
        }
    }

    /** F9单视图translation撤销（margin回退前调用，二选一不双施加）。 */
    private fun revertToolbarShiftFor(target: View) {
        try {
            val applied = synchronized(toolbarDyApplied) { toolbarDyApplied.remove(target) } ?: 0f
            val orig = synchronized(toolbarTransOrig) { toolbarTransOrig.remove(target) }
            if (kotlin.math.abs(applied) > 0.5f) {
                if (orig != null) target.translationY = orig
                else target.translationY = target.translationY - applied
                AndroidLog.i(TAG, "toolbar shift F9: reverted translation dy=$applied " +
                    "target=${target.javaClass.simpleName}")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar revert single failed: $t")
        }
    }

    /**
     * F10全拆：F9负topMargin已停用（工具栏钉死，记账/restore保留，不再施加）。
     * 返0（无增量）。
     */
    private fun applyToolbarMarginFallback(target: View, dyNeed: Float): Int {
        AndroidLog.i(TAG, "toolbar margin F10 disabled: pinned, drop dy=$dyNeed " +
            "target=${target.javaClass.simpleName}")
        return 0
    }

    /** F10封存：F9旧实现（停用，见上）。 */
    private fun applyToolbarMarginFallbackDisabled(target: View, dyNeed: Float): Int {
        return try {
            val lp = target.layoutParams as? ViewGroup.MarginLayoutParams ?: run {
                AndroidLog.e(TAG, "toolbar shift F9: margin fallback no MarginLP " +
                    "target=${target.javaClass.simpleName}")
                return 0
            }
            synchronized(toolbarMarginOrig) {
                if (!toolbarMarginOrig.containsKey(target)) {
                    toolbarMarginOrig[target] = lp.topMargin
                }
            }
            val orig = synchronized(toolbarMarginOrig) { toolbarMarginOrig[target] ?: lp.topMargin }
            val delta = dyNeed.toInt()
            val wantTop = orig + delta
            if (lp.topMargin == wantTop) {
                synchronized(toolbarMarginApplied) { toolbarMarginApplied[target] = delta }
                AndroidLog.i(TAG, "toolbar shift F9: margin already top=$wantTop " +
                    "target=${target.javaClass.simpleName}")
                return delta
            }
            lp.topMargin = wantTop
            target.layoutParams = lp
            runCatching { target.requestLayout() }
            synchronized(toolbarMarginApplied) { toolbarMarginApplied[target] = delta }
            val tloc = IntArray(2)
            runCatching { target.getLocationOnScreen(tloc) }
            AndroidLog.i(TAG, "toolbar shift F9: margin fallback dy=$dyNeed top $orig->$wantTop " +
                "target=${target.javaClass.simpleName} tTop=${tloc[1]} (verify next round gapToIcon)")
            delta
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar margin apply failed: $t")
            0
        }
    }

    /**
     * F10全拆：F9图标真父位移已停用（工具栏钉死，记账/restore保留，不再施加）。
     * postAlign不再调用本函数；保留签名供封存体编译，恒返none（需重试语义=不再移工具栏）。
     */
    private fun applyToolbarShiftToIconRow(
        decor: ViewGroup,
        bar: ViewGroup?,
        logo: View?,
        dy: Float,
        iconTopBefore: Float
    ): Triple<Float, String, Int> {
        AndroidLog.i(TAG, "toolbar shift F10 disabled: pinned, drop dy=$dy")
        return Triple(0f, "none(F10-disabled)", 0)
    }

    /** F10封存：F9旧实现（停用，见上）。 */
    private fun applyToolbarShiftToIconRowDisabled(
        decor: ViewGroup,
        bar: ViewGroup?,
        logo: View?,
        dy: Float,
        iconTopBefore: Float
    ): Triple<Float, String, Int> {
        return try {
            if (kotlin.math.abs(dy) < 0.5f) return Triple(0f, "none", 0)
            val lca = runCatching { findIconClusterLca(decor, logo) }.getOrNull()
            val barDesc = if (bar != null) {
                val bloc = IntArray(2)
                runCatching { bar.getLocationOnScreen(bloc) }
                "${bar.javaClass.simpleName} top=${bloc[1]} h=${bar.height} kids=${bar.childCount}"
            } else "null"
            val lcaDesc = if (lca != null) {
                val lloc = IntArray(2)
                runCatching { lca.getLocationOnScreen(lloc) }
                "${lca.javaClass.simpleName} top=${lloc[1]} h=${lca.height} kids=${lca.childCount}"
            } else "null"
            AndroidLog.i(TAG, "toolbar shift F9: oldTarget bar=$barDesc newLCA=$lcaDesc " +
                "dy=$dy iconTopBefore=${iconTopBefore.toInt()}")
            val target: View? = if (lca != null && lca !== decor) lca else bar
            if (target == null) {
                AndroidLog.e(TAG, "toolbar shift F9: no target (lca null, bar null), keep seam")
                return Triple(0f, "none", 0)
            }
            val targetKind = if (target === lca) "lca" else "bar"
            val tlocBefore = IntArray(2)
            runCatching { target.getLocationOnScreen(tlocBefore) }
            runCatching { unclipToolbarChainDisabled(target, decor) }
            val applied = applyToolbarShiftDisabled(target, dy)
            val iconAfter = runCatching { scanSquareIconLine(decor, logo) }.getOrNull()
            val iconTopAfter = iconAfter?.top ?: -1f
            val tlocAfter = IntArray(2)
            runCatching { target.getLocationOnScreen(tlocAfter) }
            val targetDelta = if (tlocBefore[1] > 0 && tlocAfter[1] > 0) {
                tlocAfter[1] - tlocBefore[1]
            } else 999
            val iconDelta = if (iconTopBefore > 0 && iconTopAfter > 0) {
                (iconTopAfter - iconTopBefore).toInt()
            } else 999
            val moved = iconTopAfter > 0 && (iconTopBefore - iconTopAfter) >= 1f
            AndroidLog.i(TAG, "toolbar shift F9: target=$targetKind ${target.javaClass.simpleName} " +
                "tTop ${tlocBefore[1]}->${tlocAfter[1]} d=$targetDelta " +
                "iconTop ${iconTopBefore.toInt()}->${iconTopAfter.toInt()} d=$iconDelta " +
                "moved=$moved dy=$dy applied=$applied")
            if (moved) return Triple(applied, targetKind, 0)
            AndroidLog.e(TAG, "toolbar shift F9: translationY不动 " +
                "(iconTop ${iconTopBefore.toInt()}->${iconTopAfter.toInt()}), fallback margin")
            runCatching { revertToolbarShiftFor(target) }
            val marginDy = applyToolbarMarginFallbackDisabled(target, dy)
            if (marginDy != 0) return Triple(0f, targetKind + "-margin", marginDy)
            return Triple(0f, "none", 0)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar shift F9 failed: $t")
            Triple(0f, "none", 0)
        }
    }

    /** F9退壳还clip链（幂等，fail-closed）。 */
    private fun restoreToolbarClip() {
        try {
            var n = 0
            synchronized(toolbarClipOrig) {
                val it = toolbarClipOrig.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        e.key.clipChildren = e.value.first
                        e.key.clipToPadding = e.value.second
                    }
                    it.remove()
                    n++
                }
            }
            if (n > 0) AndroidLog.i(TAG, "toolbar clip restored n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar clip restore failed: $t")
        }
    }

    /** F9退壳还负topMargin（现取现还，幂等）。 */
    private fun restoreToolbarMargin() {
        try {
            var n = 0
            val keys = synchronized(toolbarMarginApplied) { toolbarMarginApplied.keys.toList() }
            for (v in keys) {
                try {
                    val orig = synchronized(toolbarMarginOrig) { toolbarMarginOrig.remove(v) }
                    synchronized(toolbarMarginApplied) { toolbarMarginApplied.remove(v) }
                    if (orig != null) {
                        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
                        if (lp != null && lp.topMargin != orig) {
                            lp.topMargin = orig
                            v.layoutParams = lp
                            runCatching { v.requestLayout() }
                            AndroidLog.i(TAG, "toolbar margin restored top=$orig " +
                                "target=${v.javaClass.simpleName}")
                        }
                        n++
                    }
                } catch (_: Throwable) {
                }
            }
            synchronized(toolbarMarginOrig) {
                if (toolbarMarginOrig.isNotEmpty()) toolbarMarginOrig.clear()
            }
            if (n > 0) AndroidLog.i(TAG, "toolbar margin restored n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar margin restore failed: $t")
        }
    }

    /** F8退壳还账：撤销我方工具栏行负位移（回到宿主当前值减增量；无记录不动，幂等）。 */
    private fun restoreToolbarShift() {
        try {
            var n = 0
            val keys = synchronized(toolbarDyApplied) { toolbarDyApplied.keys.toList() }
            for (bar in keys) {
                try {
                    val applied = synchronized(toolbarDyApplied) { toolbarDyApplied.remove(bar) } ?: 0f
                    val orig = synchronized(toolbarTransOrig) { toolbarTransOrig.remove(bar) }
                    if (kotlin.math.abs(applied) > 0.5f) {
                        if (orig != null) {
                            bar.translationY = orig
                        } else {
                            bar.translationY = bar.translationY - applied
                        }
                        AndroidLog.i(TAG, "toolbar shift restored dy=$applied")
                    }
                    n++
                } catch (_: Throwable) {
                }
            }
            if (n > 0) AndroidLog.i(TAG, "toolbar shift restored n=$n")
            // F9：退壳时位移/margin/clip全还（只撤销我方增量，不碰宿主值）。
            runCatching { restoreToolbarMargin() }
            runCatching { restoreToolbarClip() }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar shift restore failed: $t")
        }
    }

    /** 候选区高度基线（挂条前现读，退条时还原；WRAP 则不动）。slot+cand 同记。 */
    private val candHeightOrig: MutableMap<ViewGroup, Int> =
        Collections.synchronizedMap(WeakHashMap<ViewGroup, Int>())

    /**
     * F12窗顶多余垫基线（只收槽/窗多余垫：slot/cand paddingTop+topMargin，现量现记→0，
     * 退条时还原；不动条真高167/box143，不碰键盘容器及祖先/工具栏/条根自身）。
     */
    private val stripTopPadOrig: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val stripTopMarginOrig: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())

    /** 壳模式：候选链改 WRAP 包条内容，随后走高度流/N 三连长窗。 */
    private fun expandCandidateForStrip(cand: ViewGroup) {
        try {
            val lp = cand.layoutParams ?: return
            synchronized(candHeightOrig) {
                if (!candHeightOrig.containsKey(cand) &&
                    lp.height != ViewGroup.LayoutParams.WRAP_CONTENT
                ) {
                    candHeightOrig[cand] = lp.height
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    cand.layoutParams = lp
                    AndroidLog.i(TAG, "candidate wrap for strip: origH=${candHeightOrig[cand]}")
                }
            }
            cand.requestLayout()
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "candidate expand failed: $t")
        }
    }

    /**
     * 条链 WRAP 自扩自缩（压扁根因修）：C2 实证 slot 定高把条压成 card_h=84
     *（真高 UNSPECIFIED 重测 167，box 780x143 为内容真高），溢出绘制盖住
     * 工具栏/候选。s0 插槽父（candidate_top_view）+ cand（ImeCandidateView）
     * 定高一律改 WRAP 包内容（现量现记，禁写死 84/140/167），条根自身 LP
     * 已是 MATCH/WRAP 不动；键盘容器及祖先高度一律不动（不挤键、不把工具栏
     * 挤出可视区、不把候选区挤成 0）。退出时按基线逐个还原。
     * F12顶漏量修（用户报：条顶→输入法顶视觉还很长，根本不是5px——旧量法只量槽顶→条顶mTop=1.5dp，
     * 漏了槽顶→窗顶/灰底顶那段）：查后结论为槽/窗多余垫（slot/cand paddingTop/topMargin），
     * 不是 WRAP链撑出整段灰顶（slot/cand WRAP只包内容trueH167，键盘容器及祖先不动；
     * publish(trueH167)+N三连(A2/e0/N2)+float u0只同步一次高度流/重排，不加垫）。
     * 此处WRAP后接trimStripTopExtra把slot/cand多余paddingTop/topMargin现记→0，
     * 窗顶→条顶视觉缝压到1.5dp当量（=mTop，slotWRAP自收敛槽总高=条真高+1.5dp+20px）；
     * 只收槽/窗多余垫，不动条真高167/box143，不碰工具栏/键盘/壳/s0/圆角B/DEL/commit/logo。
     */
    private fun expandStripChainForWrap(row: View, cand: ViewGroup) {
        // F41丢弃：槽WRAP收敛（顶垫/槽高）不再执行，following翻译态原生k链。
        AndroidLog.i(TAG, "strip F41 wrap: SKIP expandStripChain (translation native wins, diag only)")
        if (true) return
        try {
            // 条根自身：确保 WRAP（s0 传的已是 WRAP，此处只验不动定高）。
            runCatching {
                val lp = row.layoutParams
                if (lp != null && lp.height > 0) {
                    synchronized(candHeightOrig) {
                        if (!candHeightOrig.containsKey(row as? ViewGroup) &&
                            lp.height != ViewGroup.LayoutParams.WRAP_CONTENT
                        ) {
                            // row 多为 FrameLayout.LP WRAP，此分支极少命中；命中亦只改 WRAP。
                            if (row is ViewGroup) {
                                candHeightOrig[row] = lp.height
                                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                row.layoutParams = lp
                                AndroidLog.i(TAG, "strip root wrap: origH=${candHeightOrig[row]}")
                            }
                        }
                    }
                }
            }
            // s0 插槽父（candidate_top_view）：定高改 WRAP，否则条被 AT_MOST 压扁。
            val slot = row.parent as? ViewGroup
            if (slot != null && slot !== cand) {
                runCatching {
                    val lp = slot.layoutParams
                    if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                        synchronized(candHeightOrig) {
                            if (!candHeightOrig.containsKey(slot)) {
                                candHeightOrig[slot] = lp.height
                                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                slot.layoutParams = lp
                                AndroidLog.i(TAG, "strip slot wrap: " +
                                    "${slot.javaClass.simpleName} origH=${candHeightOrig[slot]}")
                            }
                        }
                    }
                }
                runCatching { slot.requestLayout() }
            }
            expandCandidateForStrip(cand)
            // F12：WRAP后收槽/窗多余顶垫（只动slot/cand paddingTop/topMargin→0，条真高/工具栏/键盘不动）。
            runCatching { trimStripTopExtra(row, cand) }
            runCatching { row.requestLayout() }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip chain expand failed: $t")
        }
    }

    /**
     * F12窗顶→条顶收敛（只收槽/窗多余垫）：slot（candidate_top_view）+ cand（ImeCandidateView）
     * 的 paddingTop/topMargin现量现记→0（各记stripTopPadOrig/stripTopMarginOrig，退条时还原）。
     * 只读几何+只改这两层顶垫：条根自身LP/高不动（条真高167/box143不动），键盘容器及祖先不动，
     * 工具栏任何视图不动（F10钉死保持，不查bar不改bar/margin/clip），壳/s0/圆角B/DEL/commit/logo不动。
     * publish(trueH)/N三连/float此前已走高度同步一次，此处不二次publish，只压垫+requestLayout。
     * 窗顶→条顶压到1.5dp当量（=mTop=STRIP_M_TOP_DP，槽WRAP自收敛槽总高=条真高+1.5dp+20px）。
     */
    private fun trimStripTopExtra(row: View, cand: ViewGroup) {
        try {
            val slot = row.parent as? ViewGroup
            var cutSum = 0
            val detail = StringBuilder()
            if (slot != null && slot !== cand) {
                runCatching {
                    val pt = slot.paddingTop
                    if (pt > 0) {
                        synchronized(stripTopPadOrig) {
                            if (!stripTopPadOrig.containsKey(slot)) stripTopPadOrig[slot] = pt
                        }
                        cutSum += pt
                        detail.append("slotPadTop=$pt->0 ")
                        slot.setPadding(slot.paddingLeft, 0, slot.paddingRight, slot.paddingBottom)
                    } else {
                        detail.append("slotPadTop=0 ")
                    }
                }
                runCatching {
                    val lp = slot.layoutParams as? ViewGroup.MarginLayoutParams
                    if (lp != null && lp.topMargin > 0) {
                        synchronized(stripTopMarginOrig) {
                            if (!stripTopMarginOrig.containsKey(slot)) {
                                stripTopMarginOrig[slot] = lp.topMargin
                            }
                        }
                        cutSum += lp.topMargin
                        detail.append("slotTopMargin=${stripTopMarginOrig[slot]}->0 ")
                        lp.topMargin = 0
                        slot.layoutParams = lp
                    } else {
                        detail.append("slotTopMargin=0 ")
                    }
                }
                runCatching { slot.requestLayout() }
            } else {
                detail.append("slot=none/cand ")
            }
            runCatching {
                val pt = cand.paddingTop
                if (pt > 0) {
                    synchronized(stripTopPadOrig) {
                        if (!stripTopPadOrig.containsKey(cand)) stripTopPadOrig[cand] = pt
                    }
                    cutSum += pt
                    detail.append("candPadTop=$pt->0 ")
                    cand.setPadding(cand.paddingLeft, 0, cand.paddingRight, cand.paddingBottom)
                } else {
                    detail.append("candPadTop=0 ")
                }
            }
            runCatching {
                val lp = cand.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null && lp.topMargin > 0) {
                    synchronized(stripTopMarginOrig) {
                        if (!stripTopMarginOrig.containsKey(cand)) {
                            stripTopMarginOrig[cand] = lp.topMargin
                        }
                    }
                    cutSum += lp.topMargin
                    detail.append("candTopMargin=${stripTopMarginOrig[cand]}->0 ")
                    lp.topMargin = 0
                    cand.layoutParams = lp
                } else {
                    detail.append("candTopMargin=0 ")
                }
            }
            runCatching { cand.requestLayout() }
            AndroidLog.i(TAG, "strip top trim F12: cutSum=${cutSum}px $detail" +
                "window->strip=1.5dp(mTop) stripTrueH untouched box143 " +
                "wrap/publish/N/float no extra gray")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip top trim failed: $t")
        }
    }

    /**
     * F5条缝专用工具栏行查找（与跳回/对齐同判据：横向行+槽位5~9+几何+间距首缝放宽，
     * 只读不改；供verify/mount共用，保证缝基准同源）。
     * F7放宽（C10实锤：bar maxKids=4需5~9——层次行缺席，但vision明明看到7圆）：
     * 不再要求单行容器kids 5~9；严格行先试，miss则全候选区扫方形图标叶子聚类
     * （cy聚类±28px，n>=5即成行，不过滤maxKids，见scanSquareIconLine），
     * 取含图标行顶的行容器作bar回退（barTop取行顶）；行容器亦无则返null，
     * 调用方以iconTop作barTop（empty=0，mBottom=gap），确保挂后postAlign能命中。
     */
    private fun findStripToolbarBar(decor: ViewGroup): ViewGroup? {
        return try {
            val wPx = runCatching { decor.resources.displayMetrics.widthPixels }.getOrDefault(0)
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            var hops = 0
            var maxKids = 0
            while (q.isNotEmpty() && hops < 400) {
                val v = q.removeFirst()
                hops++
                val slots = runCatching {
                    if (v is ViewGroup && v.getTag() != TAG_SEARCH_BUTTON &&
                        v.getTag() != TAG_SEARCH_BOX_CONTAINER &&
                        v.visibility == View.VISIBLE && isToolbarRowShape(v)
                    ) {
                        rowIconSlotViews(v)
                    } else null
                }.getOrNull()
                val kids = slots?.size ?: -1
                if (kids > maxKids) maxKids = kids
                if (v is ViewGroup && kids in 5..9 &&
                    isToolbarRowGeometry(v, decor) &&
                    (wPx <= 0 || isToolbarRowSpacingOk(slots!!, wPx))
                ) {
                    return v
                }
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            // F7回退：严格行缺席（C10 maxKids=4）→ 用放宽图标簇行顶找含行容器。
            val logoFb = runCatching { resolveLogoView(decor) }.getOrNull()
            val line = runCatching { scanSquareIconLine(decor, logoFb) }.getOrNull()
            if (line == null || line.top <= 0) {
                AndroidLog.i(TAG, "strip bar: strict row miss maxKids=$maxKids, relaxed cluster miss")
                return null
            }
            val rowTop = line.top.toInt()
            val rowCy = line.centerY.toInt()
            var best: ViewGroup? = null
            var bestH = Int.MAX_VALUE
            val q2: ArrayDeque<View> = ArrayDeque()
            q2.add(decor)
            var hops2 = 0
            while (q2.isNotEmpty() && hops2 < 400) {
                val v = q2.removeFirst()
                hops2++
                if (v is ViewGroup && v !== decor &&
                    v.getTag() != TAG_SEARCH_BUTTON &&
                    v.getTag() != TAG_SEARCH_BOX_CONTAINER &&
                    v.visibility == View.VISIBLE && v.width > 0 && v.height > 0
                ) {
                    val loc = IntArray(2)
                    runCatching { v.getLocationOnScreen(loc) }
                    val top = loc[1]
                    val bottom = top + v.height
                    // 含图标行顶/中心即视为行容器（barTop取行顶）；取最小高度者最贴行。
                    if (top > 0 && top <= rowTop && rowCy in top..bottom) {
                        if (v.height < bestH) {
                            bestH = v.height
                            best = v
                        }
                    }
                }
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q2.add(it) }
                    }
                }
            }
            if (best != null) {
                val bloc = IntArray(2)
                runCatching { best.getLocationOnScreen(bloc) }
                AndroidLog.i(TAG, "strip bar: relaxed row fallback maxKids=$maxKids " +
                    "barTop=${bloc[1]} rowTop=$rowTop cy=$rowCy " +
                    "bar=${best.javaClass.simpleName} h=${best.height}")
                return best
            }
            AndroidLog.i(TAG, "strip bar: strict row miss maxKids=$maxKids, " +
                "relaxed cluster n=${line.n} rowTop=$rowTop (no container, caller uses iconTop as barTop)")
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * F12条/图标上下排布校验（只读日志，不碰视图）：缝基准=图标顶iconTop（IconLine.top=cy-现算半高）；
     * 条底贴图标顶19~21px为pass（目标STRIP_M_BOTTOM_PX=20px，现算px，容差±1px）；
     * 重叠（gap<0）即error盖图标，gap>21即warn中空。找不到图标cluster则fail-closed
     * diagnostically，不回退容器顶（barTop仅记empty诊断，不作基准）。
     * 工具栏可见钉死：不做隐藏分支，重叠/空隙一律按缝判定。 */
    private fun verifyStripStacking(decor: ViewGroup, row: View, tag: String) {
        // F41：验证门回到翻译态原生可见性判定，像素只diag。本函数（缝19~21门）不再gate，仅diag。
        AndroidLog.i(TAG, "strip F41 stack [$tag]: SKIP seam gate (translation native wins, diag only)")
        runCatching { verifyNativeTranslationStateDiagF41(decor, "stack-$tag") }
        if (true) return
        try {
            val rloc = IntArray(2)
            runCatching { row.getLocationOnScreen(rloc) }
            val stripTop = rloc[1]
            val stripBottom = rloc[1] + row.height
            // F5：缝基准=图标顶（不是容器顶）。barTop仅供empty=iconTop-barTop诊断。
            // F22：verify亦走统一出口（现扫视图+像素交叉，差>20px用像素diagnostically），只读判据。
            val bar = findStripToolbarBar(decor)
            val bloc = IntArray(2)
            if (bar != null) runCatching { bar.getLocationOnScreen(bloc) }
            val barTop = if (bar != null) bloc[1] else -1
            val logoForScan = runCatching { resolveLogoView(decor) }.getOrNull()
            val iconLine = resolveIconLineF22(decor, logoForScan)
            if (iconLine == null) {
                AndroidLog.e(TAG, "strip stack [$tag]: icon cluster missing, fail-closed " +
                    "strip=${row.width}x${row.height} y=[$stripTop,$stripBottom] " +
                    "barTop=$barTop kids=${bar?.childCount} baseline=iconTop(no fallback)")
                return
            }
            val iconTop = iconLine.top
            val empty = if (barTop > 0) (iconTop - barTop).toInt() else -1
            if (iconTop <= 0 || stripTop <= 0) {
                AndroidLog.i(TAG, "strip stack [$tag]: not laid out strip=[$stripTop,$stripBottom] " +
                    "iconTop=${iconTop.toInt()} barTop=$barTop empty=$empty baseline=iconTop")
                return
            }
            val gapToIcon = (iconTop - stripBottom).toInt()
            // F12：与postAlign同目标（STRIP_M_BOTTOM_PX=20px现算px，容差±1px），只读判据。
            val pass = gapToIcon in (STRIP_M_BOTTOM_PX - 1)..(STRIP_M_BOTTOM_PX + 1)
            AndroidLog.i(TAG, "strip stack [$tag]: strip=${row.width}x${row.height} " +
                "y=[$stripTop,$stripBottom] iconTop=${iconTop.toInt()} " +
                "cy=${iconLine.centerY.toInt()} halfH=${iconLine.halfH.toInt()} n=${iconLine.n} " +
                "barTop=$barTop empty=$empty gapToIcon=$gapToIcon pass=$pass baseline=iconTop")
            if (gapToIcon < 0) {
                AndroidLog.e(TAG, "strip stack [$tag]: OVERLAP strip covers icons gap=$gapToIcon")
            } else if (!pass) {
                AndroidLog.e(TAG, "strip stack [$tag]: GAP strip floats above icons gap=$gapToIcon need=19~21")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip stack check failed [$tag]: $t")
        }
    }

    /** S7+F12：退条时候选链高度+顶垫还账（slot+cand 高度/顶垫逐个回到挂条前基线，幂等）。 */
    private fun restoreCandidateHeight(card: View) {
        try {
            var p = card.parent
            while (p != null && p !is ViewGroup) p = p.parent
            var node = p as? ViewGroup
            var depth = 0
            var n = 0
            while (node != null && depth < 6) {
                val orig = synchronized(candHeightOrig) { candHeightOrig.remove(node) }
                if (orig != null) {
                    runCatching {
                        val lp = node.layoutParams
                        if (lp != null) {
                            lp.height = orig
                            node.layoutParams = lp
                        }
                    }
                    runCatching { node.requestLayout() }
                    n++
                    AndroidLog.i(TAG, "candidate height restored: $orig " +
                        "node=${node.javaClass.simpleName}")
                }
                // F12顶垫还账（只撤销我方slot/cand paddingTop/topMargin→0增量，不碰宿主其他值）。
                val padOrig = synchronized(stripTopPadOrig) { stripTopPadOrig.remove(node) }
                if (padOrig != null) {
                    runCatching {
                        node.setPadding(node.paddingLeft, padOrig, node.paddingRight, node.paddingBottom)
                    }
                    runCatching { node.requestLayout() }
                    n++
                    AndroidLog.i(TAG, "strip top pad restored: $padOrig " +
                        "node=${node.javaClass.simpleName}")
                }
                val marginOrig = synchronized(stripTopMarginOrig) { stripTopMarginOrig.remove(node) }
                if (marginOrig != null) {
                    runCatching {
                        val lp = node.layoutParams as? ViewGroup.MarginLayoutParams
                        if (lp != null) {
                            lp.topMargin = marginOrig
                            node.layoutParams = lp
                        }
                    }
                    runCatching { node.requestLayout() }
                    n++
                    AndroidLog.i(TAG, "strip top margin restored: $marginOrig " +
                        "node=${node.javaClass.simpleName}")
                }
                node = node.parent as? ViewGroup
                depth++
            }
            // 条根自身若记过账（极少命中），清键防泄漏（view 已摘无需还）。
            runCatching {
                if (card is ViewGroup) {
                    synchronized(candHeightOrig) { candHeightOrig.remove(card) }
                }
            }
            // F12顶垫残键清账（view已摘则只清账，条位移全还路径不变）。
            runCatching {
                synchronized(stripTopPadOrig) {
                    if (stripTopPadOrig.isNotEmpty()) {
                        val it = stripTopPadOrig.entries.iterator()
                        while (it.hasNext()) {
                            val e = it.next()
                            if (e.key === card || e.key.parent == null) it.remove()
                        }
                    }
                }
                synchronized(stripTopMarginOrig) {
                    if (stripTopMarginOrig.isNotEmpty()) {
                        val it = stripTopMarginOrig.entries.iterator()
                        while (it.hasNext()) {
                            val e = it.next()
                            if (e.key === card || e.key.parent == null) it.remove()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "candidate restore failed: $t")
        }
    }

    /** 拆条统一出口：先还条位移+候选区高度账+工具栏旧负位移+F17夹层账+F18 LCA账+F19相交账+F20垫账+F21稳态账+F23定高账+F24 kids/N对照账+F25过期块账+F26残留[4]全还+F27缝试探账+F28跳过对照账+F32栏位键盘位全还，再摘条（无任何手动垫高）。 */
    private fun removeStripCard(card: View) {
        try {
            runCatching { restoreStripShift(card) }
            runCatching { restoreToolbarShift() }
            runCatching { restoreInterlayerF17() }
            runCatching { restoreLcaF18() }
            runCatching { restoreInterSpanF19() }
            runCatching { restoreConstraintPadF20() }
            runCatching { restoreBarSteadyF21() }
            runCatching { restoreFixedParentF23() }
            runCatching { restoreKidsSteadyF24() }
            runCatching { restoreNABSteadyF24() }
            runCatching { restoreStaleWhoF25() }
            runCatching { restoreStale4F26() }
            runCatching { restoreSeamF27() }
            runCatching { restoreSkipF28() }
            runCatching { restoreBarKeyboardF32() }
            // F40收起还账：mount基线清账（次挂重记，不复用旧值；只清记账不碰视图）。
            runCatching { clearMountBaselineF40() }
            // F28拆条后无条稳态基线（只读）：摘条前捕获decor，摘后1000ms量windowBar0。
            val decorForBaseline = runCatching {
                (card.rootView as? ViewGroup)
            }.getOrNull()
            restoreCandidateHeight(card)
            (card.parent as? ViewGroup)?.removeView(card)
            runCatching {
                if (decorForBaseline != null) scheduleBaselineAfterRemoveF28(decorForBaseline)
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "remove strip failed: $t")
            runCatching { (card.parent as? ViewGroup)?.removeView(card) }
        }
    }

    /**
     * 底部面板撑开展示（翻译条同形）：条增删后调宿主浮窗
     * `f.u0(singleton, 0,0,0, true,false,false, 55, null)` 重排
     *（q 高度流收集器的同一调用，`V()` 不可见则跳过）。
     * 刷新只是布局同步，不涉及视觉绘制；缺失只记日志不拆条。
     */
    private fun refreshFloatWindow(anchor: View, reason: String) {
        try {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                mainHandler.post { refreshFloatWindow(anchor, reason) }
                return
            }
            // 浮窗管理类与视图可能分属不同 dex loader：沿挂载视图的多 loader 链
            // + 线程上下文 loader 逐个试，全宿主原生。
            val loaders = ArrayList<ClassLoader?>()
            hostClassLoader?.let { hcl ->
                runCatching { loaders.addAll(idClassLoaders(anchor, hcl)) }
            }
            runCatching { loaders.add(Thread.currentThread().contextClassLoader) }
            // 输入法浮窗的 Context 由浮窗管理侧创建，其 loader 链最可能直达
            // float 类所在 dex：沿 anchor/rootView 的 Context（及 baseContext
            // 链）逐个收 loader。
            runCatching {
                val ctxs = ArrayList<android.content.Context?>()
                ctxs.add(anchor.context)
                ctxs.add(anchor.rootView?.context)
                var c: android.content.Context? = anchor.context
                var guard = 0
                while (c is android.content.ContextWrapper && guard++ < 8) {
                    c = c.baseContext
                    ctxs.add(c)
                }
                c = anchor.rootView?.context
                guard = 0
                while (c is android.content.ContextWrapper && guard++ < 8) {
                    c = c.baseContext
                    ctxs.add(c)
                }
                for (ctx in ctxs) {
                    runCatching { loaders.add(ctx?.classLoader) }
                }
            }
            var fCls: Class<*>? = null
            for (cl in loaders) {
                if (cl == null) continue
                for (name in NATIVE_FLOAT_CLASSES) {
                    fCls = runCatching { Class.forName(name, false, cl) }.getOrNull()
                    if (fCls != null) break
                }
                if (fCls != null) break
            }
            // C50根因③（只补float缺分支诊断+直达fallback，其余N/高度/栏不动，fail-closed）：
            // C50同期float f missing(E)伴随k3 ok但恒落键盘页；补reason+loader数+anchor类，
            // 并经loadHostClass再试一次直达（多dex loader链），仍缺则fail-closed，N+requestLayout照走不拦面板切换。
            var fClassTmp: Class<*>? = fCls
            if (fClassTmp == null) {
                for (name in NATIVE_FLOAT_CLASSES) {
                    fClassTmp = runCatching { loadHostClass(anchor, name) }.getOrNull()
                    if (fClassTmp != null) break
                }
                if (fClassTmp != null) {
                    AndroidLog.i(TAG, "float refresh: f via loadHostClass fallback reason=$reason")
                }
            }
            val fClass = fClassTmp ?: run {
                AndroidLog.e(TAG, "float refresh: float f missing reason=$reason " +
                    "loaders=${loaders.size} anchor=${anchor.javaClass.name} " +
                    "(fail-closed, N+requestLayout照走不拦面板切换)")
                return
            }
            val inst = fClass.declaredFields
                .firstOrNull { it.type == fClass }
                ?.also { it.isAccessible = true }
                ?.get(null) ?: run {
                    AndroidLog.e(TAG, "float refresh: float singleton missing")
                    return
                }
            val vis = fClass.declaredMethods
                .firstOrNull { it.name == "V" && it.parameterTypes.isEmpty() } ?: run {
                    AndroidLog.e(TAG, "float refresh: V() missing")
                    return
                }
            vis.isAccessible = true
            val visible = runCatching { vis.invoke(inst) as? Boolean }.getOrNull() ?: false
            if (!visible) {
                AndroidLog.i(TAG, "float refresh skipped (not visible): $reason")
                return
            }
            val u0 = fClass.declaredMethods.firstOrNull {
                it.name == "u0" && it.parameterTypes.size == 9 &&
                    java.lang.reflect.Modifier.isStatic(it.modifiers)
            } ?: run {
                AndroidLog.e(TAG, "float refresh: u0 missing")
                return
            }
            u0.isAccessible = true
            u0.invoke(null, inst, 0, 0, 0, true, false, false, 55, null)
            AndroidLog.i(TAG, "float refreshed: $reason")
            AndroidLog.i(TAG, "strip F29 mount-once: float flush u0(9参 inst,0,0,0,true,false,false,55,null) " +
                "reason=$reason (单挂载只刷一次)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "float refresh failed ($reason): $t")
        }
    }

    // 翻译条高度流管理类（Y() 高度 StateFlow，q 初始化即常驻收集，
    // 条增删→setValue→原生 u0 重排，与 k.java:1432 同信号）。
    private const val NATIVE_HEIGHT_MGR_CLASS =
        "com.tencent.wetype.plugin.hld.translatingwhilewriting.q"

    /**
     * 发布条高到原生高度流（k 同信号）：`q.Y().setValue(h)`。
     * 收集器常驻→浮窗 `u0` 重排→底部面板向上撑。height<=0 表拆除。
     * 失败只记日志（直接浮窗刷新照走）。
     */
    private fun publishStripHeight(anchor: View, height: Int, reason: String) {
        try {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                mainHandler.post { publishStripHeight(anchor, height, reason) }
                return
            }
            val loaders = ArrayList<ClassLoader?>()
            hostClassLoader?.let { hcl ->
                runCatching { loaders.addAll(idClassLoaders(anchor, hcl)) }
            }
            runCatching { loaders.add(Thread.currentThread().contextClassLoader) }
            runCatching { loaders.add(anchor.context?.classLoader) }
            var qCls: Class<*>? = null
            for (cl in loaders) {
                if (cl == null) continue
                qCls = runCatching { Class.forName(NATIVE_HEIGHT_MGR_CLASS, false, cl) }.getOrNull()
                if (qCls != null) break
            }
            val qClass = qCls ?: run {
                AndroidLog.e(TAG, "height flow: translating q missing")
                return
            }
            val mgr = qClass.declaredFields
                .firstOrNull { it.type == qClass }
                ?.also { it.isAccessible = true }
                ?.get(null) ?: run {
                    AndroidLog.e(TAG, "height flow: q singleton missing")
                    return
                }
            val y = qClass.declaredMethods
                .firstOrNull { it.name == "Y" && it.parameterTypes.isEmpty() } ?: run {
                    AndroidLog.e(TAG, "height flow: Y() missing")
                    return
                }
            y.isAccessible = true
            val flow = y.invoke(mgr) ?: run {
                AndroidLog.e(TAG, "height flow: Y() null")
                return
            }
            val setValue = runCatching {
                flow.javaClass.getMethod("setValue", Any::class.java)
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "height flow: setValue missing")
                return
            }
            setValue.invoke(flow, height)
            val cur = runCatching {
                flow.javaClass.getMethod("getValue").invoke(flow)
            }.getOrNull()
            AndroidLog.i(TAG, "height flow published h=$height prev=$cur ($reason)")
            AndroidLog.i(TAG, "strip F29 mount-once: Y-flow publish h=$height cur=$cur reason=$reason " +
                "(q.Y().setValue(h)只发一次)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "height flow publish failed ($reason): $t")
        }
    }
    // 候选控制器（挂载后刷新三连的宿主，classes3.dex，与 k 同 loader 可见）。
    private const val NATIVE_CAND_CTL_CLASS = "com.tencent.wetype.plugin.hld.model.N"

    /**
     * 挂载后刷新三连（翻译 `q` 原生顺序）：`N.A2()`（X4+M3+S1+y3，
     * 含候选区 R1 同步）+ `N.e0()`（主线程 UI 刷新协程）+
     * `N.N2(n,false,null,3,null)`（→M2(true,Other)→Q2 带动画重算窗高）。
     * 底部面板随之上撑，键盘容器高度不动。任一步缺失只记日志。
     */
    private fun refreshCandidateLayout(anchor: View, reason: String) {
        try {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                mainHandler.post { refreshCandidateLayout(anchor, reason) }
                return
            }
            val loaders = ArrayList<ClassLoader?>()
            hostClassLoader?.let { hcl ->
                runCatching { loaders.addAll(idClassLoaders(anchor, hcl)) }
            }
            runCatching { loaders.add(Thread.currentThread().contextClassLoader) }
            runCatching { loaders.add(anchor.context?.classLoader) }
            var nCls: Class<*>? = null
            for (cl in loaders) {
                if (cl == null) continue
                nCls = runCatching { Class.forName(NATIVE_CAND_CTL_CLASS, false, cl) }.getOrNull()
                if (nCls != null) break
            }
            val nClass = nCls ?: run {
                AndroidLog.e(TAG, "cand refresh: N missing ($reason)")
                return
            }
            val inst = nClass.declaredFields
                .firstOrNull { it.type == nClass }
                ?.also { it.isAccessible = true }
                ?.get(null) ?: run {
                    AndroidLog.e(TAG, "cand refresh: N singleton missing ($reason)")
                    return
                }
            var ok = true
            runCatching {
                val a2 = nClass.declaredMethods.firstOrNull {
                    it.name == "A2" && it.parameterTypes.isEmpty()
                } ?: throw NoSuchMethodException("A2")
                a2.isAccessible = true
                a2.invoke(inst)
            }.onFailure {
                ok = false
                AndroidLog.e(TAG, "cand refresh: A2 failed ($reason): $it")
            }
            runCatching {
                val e0 = nClass.declaredMethods.firstOrNull {
                    it.name == "e0" && it.parameterTypes.isEmpty()
                } ?: throw NoSuchMethodException("e0")
                e0.isAccessible = true
                e0.invoke(inst)
            }.onFailure {
                ok = false
                AndroidLog.e(TAG, "cand refresh: e0 failed ($reason): $it")
            }
            runCatching {
                val n2 = nClass.declaredMethods.firstOrNull {
                    it.name == "N2" && it.parameterTypes.size == 5 &&
                        java.lang.reflect.Modifier.isStatic(it.modifiers)
                } ?: throw NoSuchMethodException("N2")
                n2.isAccessible = true
                n2.invoke(null, inst, false, null, 3, null)
            }.onFailure {
                ok = false
                AndroidLog.e(TAG, "cand refresh: N2 failed ($reason): $it")
            }
            AndroidLog.i(TAG, "cand refresh done ok=$ok ($reason)")
            AndroidLog.i(TAG, "strip F29 mount-once: N-refresh A2(0参)+e0(0参)+N2(5参 inst,false,null,3,null) " +
                "ok=$ok reason=$reason (单挂载只刷一次)")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "cand refresh failed ($reason): $t")
        }
    }

    /**
     * 运行时结构探针（只读日志）：条根→容器→候选视图逐层类名/可见性/高，
     * 判定槽位定高与兄弟视图状态。
     */
    private fun describeStripTree(row: View): String {
        return try {
            val sb = StringBuilder()
            var p = row.parent
            var level = 0
            while (p is ViewGroup && level < 4) {
                val lp = p.layoutParams
                sb.append("L$level ${p.javaClass.name} vis=${p.visibility} " +
                    "h=${p.height} lpH=${lp?.height}; kids[")
                for (i in 0 until minOf(p.childCount, 8)) {
                    val c = p.getChildAt(i) ?: continue
                    sb.append("${c.javaClass.simpleName}:v${c.visibility}:${c.width}x${c.height},")
                }
                sb.append("]; ")
                p = p.parent
                level++
            }
            sb.toString()
        } catch (t: Throwable) {
            "probe failed: $t"
        }
    }

    /** logo 平移基线/已施加量（退壳时只撤销我方增量，不碰宿主值）。 */
    private val logoTransOrig: MutableMap<View, Float> =
        Collections.synchronizedMap(WeakHashMap<View, Float>())
    private val logoDyApplied: MutableMap<View, Float> =
        Collections.synchronizedMap(WeakHashMap<View, Float>())
    /** logo 祖先裁剪基线（clipChildren/clipToPadding，退壳还原）。 */
    private val logoClipOrig: MutableMap<View, Pair<Boolean, Boolean>> =
        Collections.synchronizedMap(WeakHashMap<View, Pair<Boolean, Boolean>>())
    /** F8工具栏行负位移基线/已施加量（只动工具栏行translationY吃灰缝，不碰键盘容器；
     * 退壳时全还，只撤销我方增量，不碰宿主值）。 */
    private val toolbarTransOrig: MutableMap<View, Float> =
        Collections.synchronizedMap(WeakHashMap<View, Float>())
    private val toolbarDyApplied: MutableMap<View, Float> =
        Collections.synchronizedMap(WeakHashMap<View, Float>())
    /** F9图标行位移记账（C12：旧作用点打错bar容器非图标真父，位移被吃/图标不动）：
     * clip链基线（施加前解clipChildren记账，退壳还）+ 负topMargin基线/增量
     * （translationY复测不动时二选一改走margin吃灰缝，退壳全还）。 */
    private val toolbarClipOrig: MutableMap<ViewGroup, Pair<Boolean, Boolean>> =
        Collections.synchronizedMap(WeakHashMap<ViewGroup, Pair<Boolean, Boolean>>())
    private val toolbarMarginOrig: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val toolbarMarginApplied: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** F10条根下移记账（只挪条，工具栏钉死；退壳/拆条时全还，只撤销我方增量）。 */
    private val stripTransOrig: MutableMap<View, Float> =
        Collections.synchronizedMap(WeakHashMap<View, Float>())
    private val stripDyApplied: MutableMap<View, Float> =
        Collections.synchronizedMap(WeakHashMap<View, Float>())
    /** F15复测记账：row -> (上轮beforeBottom, 上轮dyPrev, 上轮beforeWindowTop)，下轮先验delta>=1px才算落实。
     * F14同款safeguards：stripBottom不动停轮 + 窗灰顶不动停轮（top未PASS时），封顶300px，不动不再累加防1981。 */
    private val stripPendingVerify: MutableMap<View, Triple<Int, Float, Int>> =
        Collections.synchronizedMap(WeakHashMap<View, Triple<Int, Float, Int>>())
    /** F14条根自身topMargin记账（二选一之二，trans不动时改走本路；退壳/拆条全还）。 */
    private val stripRowMarginOrig: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val stripRowMarginApplied: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())

    /** 宿主 logo_iv（s 类字段，全宿主原生；找不到返 null）。 */
    private fun resolveLogoView(decor: ViewGroup): View? {
        return try {
            val cl = hostClassLoader ?: return null
            val sCls = runCatching { Class.forName(WETYPE_ID_CLASS, false, cl) }.getOrNull()
                ?: return null
            val id = runCatching { sCls.getField("logo_iv").getInt(null) }.getOrNull()
                ?: return null
            decor.findViewById<View>(id)
        } catch (_: Throwable) {
            null
        }
    }

    /** 行内图标线中心Y（屏坐标；排除 logo 本体；无图标返 null）。 */
    private fun iconLineCenterY(row: ViewGroup, exclude: View?): Float? {
        return try {
            var sum = 0f
            var n = 0
            for (i in 0 until row.childCount) {
                val kid = row.getChildAt(i) ?: continue
                if (kid.getTag() == TAG_SEARCH_BUTTON ||
                    kid.getTag() == TAG_SEARCH_BOX_CONTAINER
                ) {
                    continue
                }
                if (kid.visibility != View.VISIBLE) continue
                if (exclude != null && (kid === exclude || containsView(kid, exclude))) continue
                val c = imageLeafCenterY(kid, 0) ?: continue
                sum += c
                n++
            }
            if (n == 0) null else sum / n
        } catch (_: Throwable) {
            null
        }
    }

    private fun containsView(root: View, target: View): Boolean {
        return try {
            if (root === target) return true
            if (root !is ViewGroup) return false
            for (i in 0 until minOf(root.childCount, 25)) {
                if (containsView(root.getChildAt(i) ?: continue, target)) return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun imageLeafCenterY(v: View, depth: Int): Float? {
        return try {
            if (v.getTag() == TAG_SEARCH_BUTTON ||
                v.getTag() == TAG_SEARCH_BOX_CONTAINER
            ) {
                return null
            }
            if (v.visibility != View.VISIBLE) return null
            if (v !is ViewGroup) {
                if (!isToolbarImageLeaf(v)) return null
                if (v.height <= 0) return null
                val loc = IntArray(2)
                runCatching { v.getLocationOnScreen(loc) }
                if (loc[1] == 0) return null
                loc[1] + v.height / 2f
            } else {
                if (depth >= 3) return null
                for (i in 0 until minOf(v.childCount, 25)) {
                    val c = imageLeafCenterY(v.getChildAt(i) ?: continue, depth + 1)
                    if (c != null) return c
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * logo 祖先解裁剪（平移不被短父容器吃掉；最多上溯 4 层，候选区止步）。
     * 基线记 map，退壳时还原。
     */
    private fun unclipLogoAncestors(logo: View, decor: ViewGroup) {
        try {
            var n = 0
            var p = logo.parent as? ViewGroup
            while (p != null && p !== decor && n < 4) {
                synchronized(logoClipOrig) {
                    if (!logoClipOrig.containsKey(p)) {
                        logoClipOrig[p] = (p.clipChildren to p.clipToPadding)
                    }
                }
                p.clipChildren = false
                p.clipToPadding = false
                n++
                // 候选视图本身可解（logo 平移后仍在其 439 高内），再往上止步。
                if (p.javaClass.name.contains("Candidate")) break
                p = p.parent as? ViewGroup
            }
            AndroidLog.i(TAG, "toolbar align: unclipped $n ancestor levels")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar align: unclip failed: $t")
        }
    }

    /** 退壳时撤销我方 logo 平移（回到宿主当前值减增量；无记录不动）。 */
    private fun restoreLogoTranslation(host: View) {
        try {
            val root = (host as? ViewGroup) ?: (host.parent as? ViewGroup) ?: run {
                restoreClippedChain()
                return
            }
            val logo = resolveLogoView(root) ?: run {
                // FAIL②：AI条态（认证/AI提问）logo暂隐，裁剪链仍必须还账。
                restoreClippedChain()
                return
            }
            val applied = synchronized(logoDyApplied) { logoDyApplied.remove(logo) } ?: 0f
            synchronized(logoTransOrig) { logoTransOrig.remove(logo) }
            if (kotlin.math.abs(applied) > 0.5f) {
                logo.translationY = logo.translationY - applied
                AndroidLog.i(TAG, "toolbar logo restored dy=$applied")
            } else {
                // FAIL②诊断：还账调用可观测（Keep验toolbar logo restored）。
                AndroidLog.i(TAG, "toolbar logo restored dy=0 (no-op)")
            }
            restoreClippedChain()
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar logo restore failed: $t")
        }
    }

    /**
     * FAIL②：logo祖先裁剪链还账（与restoreLogoTranslation分离，
     * collapse/jumpBack/拆键盘三路必须双调；logo暂隐（AI条）时仍还账，
     * 幂等，fail-closed）。
     */
    private fun restoreClippedChain() {
        try {
            var n = 0
            synchronized(logoClipOrig) {
                val it = logoClipOrig.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    runCatching {
                        (e.key as? ViewGroup)?.let { g ->
                            g.clipChildren = e.value.first
                            g.clipToPadding = e.value.second
                        }
                    }
                    it.remove()
                    n++
                }
            }
            if (n > 0) AndroidLog.i(TAG, "toolbar clip restored n=$n")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar clip restore failed: $t")
        }
    }

    /** FAIL②：AI条判定（收起后宿主切“认证/AI提问/X”非工具栏，logo暂隐）。 */
    private fun isAiBarShowing(decor: ViewGroup): Boolean {
        return try {
            var hops = 0
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            while (q.isNotEmpty() && hops < 300) {
                val v = q.removeFirst()
                hops++
                val txt = runCatching {
                    (v as? android.widget.TextView)?.text?.toString() ?: ""
                }.getOrDefault("")
                if (txt.contains("AI提问") || txt.contains("认证")) {
                    if (v.visibility == View.VISIBLE && v.isShown) return true
                }
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 20)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * C48工具栏恢复（本单三点之三）：jump/收起后工具栏在位。
     * custom/logo/logoC VISIBLE+dy0+isShown双真+requestLayout/N重刷（只改显隐/位移/重刷，
     * 不碰肤色/字号/图标/圆角B/J3/布局边距；取不到fail-closed不动；禁仿制禁GradientDrawable/硬编码色/系统图标/f0(32)）。
     * 搜索态（translatorShellByUs=true，翻译藏栏为原生行为）不强制显栏，由调用方经translatorShellByUs门控；
     * 本函数仅在还账态（!translatorShellByUs）执行，搜索态调用即SKIP diag。
     * 只撤销藏栏（VISIBLE/dy0/祖先显），不新施位移；N重刷由调用方统一出口执行。
     */
    private fun restoreToolbarVisibleForJumpF48(decor: ViewGroup, reason: String): Boolean {
        return try {
            if (translatorShellByUs) {
                AndroidLog.i(TAG, "strip F48 toolbar restore [$reason]: SKIP searchMode (translation native wins, diag only)")
                return false
            }
            val custom = runCatching { resolveCustomToolbarViewF39(decor) }.getOrNull()
            val logo = runCatching { resolveLogoView(decor) }.getOrNull()
            val logoC = runCatching { resolveLogoContainerViewF39(decor) }.getOrNull()
            val bar = runCatching { findStripToolbarBar(decor) }.getOrNull()
            if (custom == null && logo == null && logoC == null && bar == null) {
                AndroidLog.e(TAG, "strip F48 toolbar restore [$reason]: SKIP all-missing (fail-closed)")
                return false
            }
            var changed = false
            val parts = ArrayList<String>()
            fun one(nm: String, v: View?) {
                if (v == null) {
                    parts.add("$nm:null")
                    return
                }
                if (v.parent == null) {
                    parts.add("$nm:detached")
                    return
                }
                val vis = v.visibility
                val visStr = when (vis) { View.VISIBLE -> "V"; View.GONE -> "G"; View.INVISIBLE -> "I"; else -> "$vis" }
                val dy = runCatching { v.translationY }.getOrDefault(0f)
                if (vis != View.VISIBLE) {
                    runCatching { v.visibility = View.VISIBLE }
                    runCatching { v.requestLayout() }
                    (v.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
                    changed = true
                }
                if (kotlin.math.abs(runCatching { v.translationY }.getOrDefault(dy)) > 1f) {
                    runCatching { v.translationY = 0f }
                    runCatching { v.requestLayout() }
                    (v.parent as? ViewGroup)?.let { runCatching { it.requestLayout() } }
                    changed = true
                }
                if (v.visibility == View.VISIBLE && !v.isShown) {
                    val ancN = runCatching { ensureAncestorsVisibleF40(v, decor) }.getOrDefault(0)
                    if (ancN > 0) {
                        runCatching { v.requestLayout() }
                        changed = true
                    }
                    parts.add("$nm:ancestors+$ancN")
                }
                val loc = IntArray(2)
                runCatching { v.getLocationOnScreen(loc) }
                val afterVis = v.visibility
                val afterStr = when (afterVis) { View.VISIBLE -> "V"; View.GONE -> "G"; View.INVISIBLE -> "I"; else -> "$afterVis" }
                parts.add("$nm:$visStr->$afterStr(isShown=${v.isShown} dy=${dy.toInt()}->${v.translationY.toInt()} y=${loc[1]} h=${v.height})")
            }
            one("customToolbarRv", custom)
            one("logo_iv", logo)
            one("logoContainerRl", logoC)
            if (bar != null && bar !== custom && bar !== logo && bar !== logoC) one("bar", bar)
            val viewLine = runCatching { scanSquareIconLine(decor, logo) }.getOrNull()
            val iconN = viewLine?.n ?: -1
            val iconTop = viewLine?.top?.toInt() ?: -1
            val allVis = listOf(custom, logo, logoC).filterNotNull().all { it.visibility == View.VISIBLE && it.isShown } &&
                (bar == null || (bar.visibility == View.VISIBLE && bar.isShown))
            val allDy0 = listOf(custom, logo, logoC, bar).filterNotNull().all {
                kotlin.math.abs(runCatching { it.translationY }.getOrDefault(0f)) <= 1f
            }
            val pass = allVis && allDy0 && iconN >= 5
            if (pass) {
                AndroidLog.i(TAG, "strip F48 toolbar restore [$reason]: PASS visOk=true(VISIBLE+isShown双真) dy0=true " +
                    "icon n=$iconN(>=5) top=$iconTop changed=$changed " + parts.joinToString(" | ") +
                    " (jump后工具栏在位+N重刷)")
            } else {
                AndroidLog.e(TAG, "strip F48 toolbar restore [$reason]: FAIL visOk=$allVis(VISIBLE+isShown双真) " +
                    "dy0=$allDy0 icon n=$iconN(需>=5) top=$iconTop changed=$changed " + parts.joinToString(" | ") +
                    " (fail-closed，不仿制)")
            }
            pass
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F48 toolbar restore failed [$reason]: $t")
            false
        }
    }

    /**
     * FAIL②：工具栏还账统一出口（collapse/jumpBack/拆键盘三路必调）。
     * restoreLogoTranslation+restoreClippedChain+publish0+refresh四连，
     * 补一次post对线；AI条态等工具栏回归再还账（120/500/1200ms三档重试，
     * 只读几何不碰宿主态，fail-closed）。
     * C48：还账态（!translatorShellByUs）加custom/logo/logoC VISIBLE+dy0+isShown双真+requestLayout/N重刷
     * （restoreToolbarVisibleForJumpF48），jump后工具栏在位；搜索态不强制（翻译藏栏原生）。
     * 只改还账，不碰双行卡/下拉/hint/隔离/圆角B/J3。
     */
    private fun restoreToolbarState(decor: View, reason: String, attempt: Int = 0) {
        if (submitSession.id != 0L) return // Native Q0 owns restoration; no late layout/navigation replay.
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { restoreToolbarState(decor, reason, attempt) }
                return
            }
            publishStripHeight(decor, 0, reason)
            refreshCandidateLayout(decor, reason)
            refreshFloatWindow(decor, reason)
            restoreLogoTranslation(decor)
            restoreClippedChain()
            runCatching { restoreToolbarShift() }
            // C48：还账态工具栏在位（custom/logo/logoC VISIBLE+dy0+isShown双真+requestLayout，
            // 搜索态SKIP；成功后N重刷一次，jump后工具栏在位）。
            val dg = decor as? ViewGroup
            if (dg != null && !translatorShellByUs) {
                runCatching { restoreToolbarVisibleForJumpF48(dg, "$reason-attempt$attempt") }
                runCatching { refreshCandidateLayout(decor, "$reason-toolbarVis") }
                runCatching { refreshFloatWindow(decor, "$reason-toolbarVis") }
            }
            if (dg != null) {
                if (isAiBarShowing(dg)) {
                    AndroidLog.i(TAG, "toolbar restore deferred (AI bar) reason=$reason attempt=$attempt")
                } else {
                    // 补一次post对线（工具栏回归即logoCy可观测）。
                    runCatching { alignToolbarRowWithLogo(dg) }
                }
                if (attempt < 2) {
                    val delay = if (attempt == 0) 500L else 1200L
                    mainHandler.postDelayed({
                        try {
                            // 重试前重判AI条：回归即还账+对线，未归仅记日志。
                            // C48：重试亦含VISIBLE+dy0+isShown双真+requestLayout/N重刷（还账态才做，搜索态SKIP）。
                            if (!isAiBarShowing(dg)) {
                                restoreLogoTranslation(dg)
                                restoreClippedChain()
                                runCatching { restoreToolbarShift() }
                                var visPassRetry: Boolean? = null
                                if (!translatorShellByUs) {
                                    visPassRetry = runCatching {
                                        restoreToolbarVisibleForJumpF48(dg, "$reason-retry${attempt + 1}")
                                    }.getOrNull()
                                    runCatching { refreshCandidateLayout(dg, "$reason-retry-toolbarVis") }
                                    runCatching { refreshFloatWindow(dg, "$reason-retry-toolbarVis") }
                                }
                                runCatching { alignToolbarRowWithLogo(dg) }
                                val iconNRetry = runCatching {
                                    scanSquareIconLine(dg, runCatching { resolveLogoView(dg) }.getOrNull())?.n
                                }.getOrNull() ?: -1
                                AndroidLog.i(TAG, "toolbar restore retry reason=$reason attempt=${attempt + 1} " +
                                    "icon n=$iconNRetry visPass=${visPassRetry ?: "skip-searchMode"} " +
                                    "(VISIBLE+dy0+isShown双真+N重刷，jump后工具栏在位)")
                            } else {
                                AndroidLog.i(TAG, "toolbar restore retry deferred (AI bar) " +
                                    "reason=$reason attempt=${attempt + 1}")
                                if (attempt + 1 < 2) {
                                    mainHandler.postDelayed({
                                        restoreToolbarState(dg, reason, attempt + 1)
                                    }, 1200)
                                }
                            }
                        } catch (t: Throwable) {
                            AndroidLog.e(TAG, "toolbar restore retry failed: ${t.message}")
                        }
                    }, delay)
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar restore failed ($reason): ${t.message}")
        }
    }

    /**
     * 工具栏行对齐：logo_iv（模块重绘 96px）与各图标容器高度/重心不一致，
     * 条撑开候选区后下推重排即暴露偏上。找到横向工具栏行（与跳回同判据：
     * 横向 LinearLayout/Row + 图标槽位5~9 + 间距首缝放宽 + 键盘窗几何），
     * 把行 gravity 与直孩 layout_gravity 统一为 CENTER_VERTICAL，
     * 只动对齐不碰尺寸/点击。
     * FAIL①：槽位≥6改5~9兼容（logo+5=6圆），间距首缝0.07W~0.45W同步，
     * 找不到行打分步诊断（maxKids/间距/几何/Q_top），fail-closed。
     */
    private fun alignToolbarRowWithLogo(decor: ViewGroup) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { alignToolbarRowWithLogo(decor) }
                return
            }
            val wPx = runCatching { decor.resources.displayMetrics.widthPixels }.getOrDefault(0)
            var row: ViewGroup? = null
            var hops = 0
            var maxKids = 0
            var maxSpacing = ""
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            while (q.isNotEmpty() && hops < 400) {
                val v = q.removeFirst()
                hops++
                val slots = runCatching {
                    if (v is ViewGroup && v.getTag() != TAG_SEARCH_BUTTON &&
                        v.getTag() != TAG_SEARCH_BOX_CONTAINER &&
                        v.visibility == View.VISIBLE && isToolbarRowShape(v)
                    ) {
                        rowIconSlotViews(v)
                    } else null
                }.getOrNull()
                val kids = slots?.size ?: -1
                if (kids > maxKids) {
                    maxKids = kids
                    maxSpacing = runCatching {
                        if (slots != null && wPx > 0 && kids in 5..12) {
                            val lefts = slots.map {
                                val loc = IntArray(2)
                                runCatching { it.getLocationOnScreen(loc) }
                                loc[0]
                            }.sorted()
                            val dxs = (0 until lefts.size - 1).map { lefts[it + 1] - lefts[it] }
                            "dxs=$dxs"
                        } else ""
                    }.getOrDefault("")
                }
                if (row == null && v is ViewGroup && kids in 5..9 &&
                    isToolbarRowGeometry(v, decor) &&
                    (wPx <= 0 || isToolbarRowSpacingOk(slots!!, wPx))
                ) {
                    row = v
                    break
                }
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            val bar = row ?: run {
                val qTop = runCatching { findQTopOnScreen(decor) }.getOrNull()
                AndroidLog.e(TAG, "toolbar align: icon row not found " +
                    "maxKids=$maxKids hops=$hops W=$wPx qTop=$qTop $maxSpacing " +
                    "need=5~9 spacing=[0.07W,0.18W]/firstMax=0.45W")
                alignLogoToIconLine(decor)
                return
            }
            if (bar is LinearLayout) {
                bar.gravity = android.view.Gravity.CENTER_VERTICAL
            }
            for (i in 0 until bar.childCount) {
                val kid = bar.getChildAt(i) ?: continue
                if (kid.getTag() == TAG_SEARCH_BUTTON ||
                    kid.getTag() == TAG_SEARCH_BOX_CONTAINER
                ) {
                    continue
                }
                val lp = kid.layoutParams as? LinearLayout.LayoutParams
                if (lp != null && lp.gravity != android.view.Gravity.CENTER_VERTICAL) {
                    lp.gravity = android.view.Gravity.CENTER_VERTICAL
                    kid.layoutParams = lp
                }
                if (kid is ImageView) {
                    if (kid.scaleType != ImageView.ScaleType.CENTER_INSIDE) {
                        kid.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }
                } else if (kid is ViewGroup) {
                    // 容器内图标也居中（只调 gravity，不碰尺寸）。
                    if (kid is LinearLayout) {
                        kid.gravity = android.view.Gravity.CENTER
                    }
                    for (j in 0 until minOf(kid.childCount, 8)) {
                        val inner = kid.getChildAt(j) ?: continue
                        val ilp = inner.layoutParams as? LinearLayout.LayoutParams
                        if (ilp != null && ilp.gravity != android.view.Gravity.CENTER) {
                            ilp.gravity = android.view.Gravity.CENTER
                            inner.layoutParams = ilp
                        }
                    }
                }
            }
            // logo 回位（实测壳模式 logo 上移 76px、图标分毫不差）：
            // 现算图标线中心与 logo 中心差值，打到 translationY。
            alignLogoToIconLine(decor)
            bar.requestLayout()
            AndroidLog.i(TAG, "toolbar row aligned center_vertical kids=${bar.childCount}")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar align failed: $t")
        }
    }

    /**
     * 全树图标线中心Y（行结构无关）：扫 decor 内全部可见方形图标叶子
     *（自家条子树排除），按屏中心Y聚类取最大簇均值。
     * FAIL①修：方形叶子聚类改5~9兼容（logo+5=6圆，旧阈值判丢），
     * 白卡Q上方判定同步（Q_top上方才算），logo首缝放宽同步
     * （首缝0.07W~0.45W，其余0.07W~0.18W，与tools/open-strip.sh同判据），
     * 找不到行打分步诊断（scan数、各cy/x、阈值），仍fail-closed不硬编码。
     * 几何探针只读，不碰 Translating 态。
     */
    private fun iconLineCenterYDecor(decor: ViewGroup, logo: View?): Float? {
        // F5：logo仍对中心线（语义不动），仅取IconLine.centerY；图标顶走IconLine.top（条缝基准）。
        return scanSquareIconLine(decor, logo)?.centerY
    }

    /**
     * FAIL①：方形叶子行扫描（Keep open-strip.sh kt侧同源）。
     * 尺寸一律W相对（0.055W~0.115W，方差容差0.02W），聚类容差0.012H，
     * 簇5~9才收（logo+5=6兼容），Y上界Q_top-0.005H（Q键/候选排除），
     * X间距首缝0.07W~0.45W、其余0.07W~0.18W或全匀0.07W~0.18W
     * （logo首缝放宽）。失败返null（fail-closed，禁仿制兜底）。
     * F5：返IconLine（centerY+现算半高halfH+top图标顶；半高=簇图标高均值/2，
     * 现算不写死，89x89仅参考；logo排除后算均值防偏）。只读不碰视图。
     * F7放宽（C10实锤：postAlign 12/8/4轮询全是`icon row still missing, keep temp seam`，
     * 原因`bar maxKids=4需5~9`——层次行缺席，但vision明明看到7圆；
     * C9曾抓到icon cluster n=6/6 y=1416 top=1365，图标叶子在，只是行容器判据太严）：
     * 不再要求单行容器kids 5~9，改全候选区扫方形图标叶子聚类（cy聚类±28px，
     * n>=5即成行，不过滤maxKids/间距上限，间距仅记诊断不拒收），barTop取行顶
     * （调用方bar缺席时回退iconTop），iconTop=cy-半高现算。
     */
    private fun scanSquareIconLine(decor: ViewGroup, logo: View?): IconLine? {
        return try {
            val dm = decor.resources.displayMetrics
            val wPx = dm.widthPixels
            val hPx = dm.heightPixels
            if (wPx <= 0 || hPx <= 0) {
                AndroidLog.e(TAG, "toolbar align: scanSquareIconLine no metrics W=$wPx H=$hPx")
                return null
            }
            val wMin = wPx * 0.055f
            val wMax = wPx * 0.115f
            val whTol = wPx * 0.02f
            val yTol = hPx * 0.012f
            val dloc = IntArray(2)
            runCatching { decor.getLocationOnScreen(dloc) }
            val decorH = decor.height
            val qTop = findQTopOnScreen(decor)
            // F22 Q_top带限行：cy<Q_top-100px（防Q键上半/第二工具栏/AI条残留误检；
            // 旧0.005H≈12px太贴Q排，C25疑1437即Q排顶1494附近误检）。qTop null则回退decor带。
            val yHi = if (qTop != null) (qTop - STRIP_ICON_Q_GUARD_PX).toInt()
                else dloc[1] + (decorH * 0.85f).toInt()
            val yLo = dloc[1] + (decorH * 0.30f).toInt()
            data class Sq(val x: Float, val y: Float, val left: Int, val h: Int, val v: View)
            val cands = ArrayList<Sq>()
            var hops = 0
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            while (q.isNotEmpty() && hops < 800) {
                val v = q.removeFirst()
                hops++
                if (v.getTag() == TAG_SEARCH_BUTTON ||
                    v.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                    v.getTag() == TAG_SEARCH_CLEAR
                ) {
                    continue
                }
                if (v.visibility != View.VISIBLE || !v.isShown) {
                    if (v is ViewGroup) {
                        // GONE子树仍需略过，不展开。
                        continue
                    } else continue
                }
                if (v is ViewGroup) {
                    // 方形槽容器（每图标独立容器，内含Image叶子）：记槽中心并剪枝，
                    // 防容器+内叶双计（6圆变12落选）。非方形才展开。
                    val w = v.width
                    val h = v.height
                    val square = w > 0 && h > 0 && w >= wMin && w <= wMax &&
                        h >= wMin && h <= wMax &&
                        kotlin.math.abs(w - h) <= whTol
                    if (square && v !== decor && rowSlotHasImage(v)) {
                        val loc = IntArray(2)
                        runCatching { v.getLocationOnScreen(loc) }
                        val cy = loc[1] + h / 2f
                        val cx = loc[0] + w / 2f
                        val inBand = if (decorH > 0 && loc[1] > 0) {
                            cy > yLo && cy < yHi && loc[1] > dloc[1]
                        } else true
                        if (inBand && loc[1] > 0) {
                            cands.add(Sq(cx, cy, loc[0], h, v))
                        }
                        continue
                    }
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                } else {
                    // 方形叶子：Image系优先，方形非文本叶也收（宿主自定义图标类），
                    // 单字TextView（Q键）排除，Q_top双保险。
                    val tv = v as? android.widget.TextView
                    if (tv != null && tv.text?.toString()?.trim()?.length == 1) continue
                    if (!isToolbarImageLeaf(v)) {
                        // 非Image系：方形才收，否则跳过（候选字块/长条排除）。
                        val w = v.width
                        val h = v.height
                        if (!(w > 0 && h > 0 && w >= wMin && w <= wMax &&
                                h >= wMin && h <= wMax &&
                                kotlin.math.abs(w - h) <= whTol)
                        ) continue
                    } else {
                        val w = v.width
                        val h = v.height
                        if (!(w > 0 && h > 0 && w >= wMin && w <= wMax &&
                                h >= wMin && h <= wMax &&
                                kotlin.math.abs(w - h) <= whTol)
                        ) continue
                    }
                    val w = v.width
                    val h = v.height
                    val loc = IntArray(2)
                    runCatching { v.getLocationOnScreen(loc) }
                    if (loc[1] <= 0) continue
                    val cy = loc[1] + h / 2f
                    val cx = loc[0] + w / 2f
                    if (decorH > 0) {
                        if (!(cy > yLo && cy < yHi)) continue
                    }
                    cands.add(Sq(cx, cy, loc[0], h, v))
                }
            }
            val cysSorted = cands.map { it.y }.sorted()
            AndroidLog.i(TAG, "toolbar align: scanSquareIconLine squares=${cands.size} " +
                "W=$wPx H=$hPx wRange=[${wMin.toInt()},${wMax.toInt()}] " +
                "whTol=${whTol.toInt()} yTol=${yTol.toInt()} qTop=$qTop " +
                "yBand=[$yLo,$yHi] cys=$cysSorted")
            if (cands.size < 5) {
                AndroidLog.e(TAG, "toolbar align: icon samples too few n=${cands.size} " +
                    "need=5~9 wRange=[${wMin.toInt()},${wMax.toInt()}] " +
                    "yTol=${yTol.toInt()} qTop=$qTop cys=$cysSorted")
                return null
            }
            // 按Y聚类（F7放宽：cy聚类±28px固定容差，n>=5即成行，不过滤maxKids/上限9；
            // 旧0.012H≈28px@2400H同量级，现按任务显式±28px，间距仅诊断不拒收）。
            val cyTolPx = 28f
            val byY = cands.sortedBy { it.y }
            data class Cl(val items: List<Sq>)
            val clusters = ArrayList<Cl>()
            var i = 0
            while (i < byY.size) {
                var j = i
                while (j < byY.size && byY[j].y - byY[i].y <= cyTolPx) j++
                val slice = byY.subList(i, j)
                if (slice.size >= 5) clusters.add(Cl(slice.toList()))
                i++
            }
            if (clusters.isEmpty()) {
                AndroidLog.e(TAG, "toolbar align: no icon cluster n=${cands.size} " +
                    "need=n>=5 cyTol=28px cys=$cysSorted")
                return null
            }
            // F7放宽：取最大簇（n最大，tie则顶优先Y小在前），间距仅记诊断不拒收，
            // 确保vision 7圆 / C9 n=6/6在bar maxKids=4时仍能命中。
            val sortedCl = clusters.sortedWith(
                compareByDescending<Cl> { it.items.size }
                    .thenBy { cl -> cl.items.map { it.y }.average() }
            )
            val gapMin = (wPx * 0.07f).toInt()
            val gapMax = (wPx * 0.18f).toInt()
            val gapFirstMax = (wPx * 0.45f).toInt()
            for (cl in sortedCl) {
                val byX = cl.items.sortedBy { it.left }
                val dxs = ArrayList<Int>()
                for (k in 0 until byX.size - 1) dxs.add(byX[k + 1].left - byX[k].left)
                val wideFirst = dxs.isNotEmpty() && dxs[0] in gapMin..gapFirstMax &&
                    (dxs.size == 1 || dxs.subList(1, dxs.size).all { it in gapMin..gapMax })
                val uniform = dxs.isNotEmpty() && dxs.all { it in gapMin..gapMax }
                val xs = byX.map { it.left }
                // F7：间距仅诊断（不过滤），直接按本簇成行。
                AndroidLog.i(TAG, "toolbar align: icon cluster relaxed n=${cl.items.size}/${cands.size} " +
                    "xs=$xs dxs=$dxs gap=[$gapMin,$gapMax] firstMax=$gapFirstMax " +
                    "mode=${if (wideFirst) "wideFirst" else if (uniform) "uniform" else "relaxed-nogate"}")
                // logo偏置排除：最左若贴近logo_iv（同行容差内），均值去logo防偏。
                var useItems = byX
                if (logo != null) {
                    val lloc = IntArray(2)
                    runCatching { logo.getLocationOnScreen(lloc) }
                    if (lloc[1] > 0 && kotlin.math.abs(byX[0].y - (lloc[1] + logo.height / 2f)) <= yTol &&
                        kotlin.math.abs(byX[0].left - lloc[0]) <= wPx * 0.05f
                    ) {
                        useItems = byX.subList(1, byX.size)
                    }
                }
                var sum = 0f
                var hSum = 0
                var hN = 0
                for (s in useItems) {
                    sum += s.y
                    if (s.h > 0) { hSum += s.h; hN++ }
                }
                if (useItems.isEmpty() || hN == 0) {
                    AndroidLog.e(TAG, "toolbar align: icon cluster empty after logo exclude")
                    return null
                }
                val lineY = sum / useItems.size
                // F34显式Q行veto（接F33单字skip+Q_top带限+方形槽+Image叶+n>=5/cy±28，在此基础上加，不reset）：
                // 同kb容器内QWERTYUIOP>=5+极差<=0.06H+digitTop<QTop+>midY即Q行（findQTopOnScreen逻辑复用，
                // 见qRowDetailF34，10键11缝）；若本簇cy落入Q带或dxs≈Q键距即Q误检，本簇跳过试下一簇，
                // 全veto才返null skip不记iconTop（fail-closed，缝19~21+视图n>=5门不动）。
                val vetoF34 = runCatching { isQMisdetectVetoF34(decor, lineY, xs, wPx) }.getOrDefault(false)
                if (vetoF34) {
                    AndroidLog.e(TAG, "strip F34 scan veto: cluster n=${cl.items.size} y=${lineY.toInt()} xs=$xs " +
                        "判Q误检跳过试下一簇 (不记iconTop, fail-closed)")
                    continue
                }
                // F5：半高现算（簇图标高均值/2，不写死44；89x89仅参考），top即图标顶（条缝新基准）。
                // F7：iconTop=cy-半高现算，barTop取行顶（调用方回退见findStripToolbarBar/mount/postAlign）。
                val avgH = hSum.toFloat() / hN
                val halfH = avgH / 2f
                val iconTop = lineY - halfH
                    AndroidLog.i(TAG, "toolbar align: icon cluster n=${cl.items.size}/${cands.size} " +
                        "y=${lineY.toInt()} top=${iconTop.toInt()} avgH=${avgH.toInt()} " +
                        "halfH=${halfH.toInt()} xs=$xs dxs=$dxs " +
                        "gap=[$gapMin,$gapMax] firstMax=$gapFirstMax mode=${if (wideFirst) "wideFirst" else if (uniform) "uniform" else "relaxed-nogate"}")
                    return IconLine(lineY, halfH, iconTop, useItems.size)
            }
            AndroidLog.e(TAG, "toolbar align: icon line not found n=${cands.size} " +
                "clusters=${clusters.map { it.items.size }} need=n>=5 cyTol=28px (F34全veto亦落此，Q误检skip)")
            return null
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar align: cluster failed: $t")
            null
        }
    }

    /**
     * F22像素亮斑法（视觉交叉，diagnostically二选一以像素为准）：
     * 白圆在灰底上，亮斑min>225（R/G/B全>225）连通域独立验iconTop。
     * 只读不碰视图：对候选方形图标视图取位图（ImageView drawable直取/BitmapDrawable，
     * 否则drawable渲染或view.draw渲染，宽高 cap 128防大图卡顿），步长2采样扫亮斑，
     * 要求亮斑数>=40、包围盒近似方形/圆形（宽高比<=1.6）、密度>0.3、亮斑中心贴视图中心
     * （<视图短边*0.30），命中即该图标像素验证通过。
     * 失败返false（该候选不计入像素簇，调用方记diag）。
     */
    private fun hasBrightSpotF22(v: View): Boolean {
        return try {
            val bmp = bitmapForBrightCheckF22(v) ?: return false
            if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) {
                runCatching { if (!bmp.isRecycled) bmp.recycle() }
                return false
            }
            val w = bmp.width
            val h = bmp.height
            val step = 2
            var bright = 0
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            var sx = 0
            while (sx < w) {
                var sy = 0
                while (sy < h) {
                    val pix = runCatching { bmp.getPixel(sx, sy) }.getOrNull() ?: 0
                    val r = (pix shr 16) and 0xFF
                    val g = (pix shr 8) and 0xFF
                    val b = pix and 0xFF
                    if (r > STRIP_ICON_BRIGHT_MIN && g > STRIP_ICON_BRIGHT_MIN &&
                        b > STRIP_ICON_BRIGHT_MIN
                    ) {
                        bright++
                        if (sx < minX) minX = sx
                        if (sx > maxX) maxX = sx
                        if (sy < minY) minY = sy
                        if (sy > maxY) maxY = sy
                    }
                    sy += step
                }
                sx += step
            }
            val needRecycle = bmp.width <= 128 && bmp.height <= 128
            if (bright < 40) {
                if (needRecycle) runCatching { bmp.recycle() }
                return false
            }
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            if (boxW < 4 || boxH < 4) {
                if (needRecycle) runCatching { bmp.recycle() }
                return false
            }
            val longSide = maxOf(boxW, boxH).toFloat()
            val shortSide = minOf(boxW, boxH).toFloat()
            if (longSide / shortSide.coerceAtLeast(1f) > 1.6f) {
                if (needRecycle) runCatching { bmp.recycle() }
                return false
            }
            val boxArea = (boxW * boxH).toFloat().coerceAtLeast(1f)
            val density = bright.toFloat() / boxArea
            if (density < 0.30f) {
                if (needRecycle) runCatching { bmp.recycle() }
                return false
            }
            val brightCx = (minX + maxX) / 2f
            val brightCy = (minY + maxY) / 2f
            val viewCx = w / 2f
            val viewCy = h / 2f
            val dist = kotlin.math.hypot(brightCx - viewCx, brightCy - viewCy)
            val shortSidePx = minOf(w, h).toFloat().coerceAtLeast(1f)
            if (dist > shortSidePx * 0.30f) {
                if (needRecycle) runCatching { bmp.recycle() }
                return false
            }
            if (needRecycle) runCatching { bmp.recycle() }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** F22亮斑位图来源（只读）：ImageView走drawable，槽容器找内叶，余下走view.draw；cap 128。 */
    private fun bitmapForBrightCheckF22(v: View): Bitmap? {
        return try {
            if (v is ImageView) {
                bitmapFromImageViewF22(v)
            } else if (v is ViewGroup) {
                val leaf = findBrightImageLeafF22(v)
                if (leaf != null) {
                    bitmapFromImageViewF22(leaf)
                } else {
                    bitmapFromViewDrawF22(v)
                }
            } else {
                bitmapFromViewDrawF22(v)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** F22槽内Image叶查找（BFS深3/每层25上限，只读）。 */
    private fun findBrightImageLeafF22(slot: ViewGroup): ImageView? {
        return try {
            val q: ArrayDeque<Pair<View, Int>> = ArrayDeque()
            q.add(slot to 0)
            var hops = 0
            while (q.isNotEmpty() && hops < 100) {
                val (n, d) = q.removeFirst()
                hops++
                if (n.getTag() == TAG_SEARCH_BUTTON ||
                    n.getTag() == TAG_SEARCH_BOX_CONTAINER
                ) continue
                if (n.visibility != View.VISIBLE) continue
                if (n !== slot && n is ImageView) return n
                if (n is ViewGroup && d < 3) {
                    for (i in 0 until minOf(n.childCount, 25)) {
                        n.getChildAt(i)?.let { q.add(it to d + 1) }
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** F22 ImageView位图（BitmapDrawable直取，否则渲染到cap128画布；只读，保护宿主原Drawable bounds不被污染篡改）。 */
    private fun bitmapFromImageViewF22(img: ImageView): Bitmap? {
        return try {
            val d = img.drawable ?: return null
            if (d is BitmapDrawable) {
                val b = runCatching { d.bitmap }.getOrNull()
                if (b != null && !b.isRecycled && b.width > 0 && b.height > 0) return b
            }
            val vw = img.width.takeIf { it > 0 } ?: d.intrinsicWidth.takeIf { it > 0 } ?: return null
            val vh = img.height.takeIf { it > 0 } ?: d.intrinsicHeight.takeIf { it > 0 } ?: return null
            if (vw <= 0 || vh <= 0 || vw > 512 || vh > 512) return null
            val bw = vw.coerceAtMost(128)
            val bh = vh.coerceAtMost(128)
            if (bw <= 0 || bh <= 0) return null
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val savedBounds = android.graphics.Rect(d.bounds)
            try {
                if (savedBounds.width() > 0 && savedBounds.height() > 0) {
                    canvas.save()
                    canvas.scale(bw.toFloat() / savedBounds.width(), bh.toFloat() / savedBounds.height())
                    d.draw(canvas)
                    canvas.restore()
                } else {
                    d.setBounds(0, 0, bw, bh)
                    d.draw(canvas)
                }
            } finally {
                runCatching { d.bounds = savedBounds }
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /** F22普通视图位图（view.draw到cap128画布，主线程调用；只读）。 */
    private fun bitmapFromViewDrawF22(v: View): Bitmap? {
        return try {
            val vw = v.width
            val vh = v.height
            if (vw <= 0 || vh <= 0 || vw > 256 || vh > 256) return null
            val bmp = Bitmap.createBitmap(vw.coerceAtMost(128), vh.coerceAtMost(128), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            // 缩放绘制以适配cap（只为亮斑计数，不作几何基准）。
            runCatching {
                val sx = bmp.width.toFloat() / vw.toFloat()
                val sy = bmp.height.toFloat() / vh.toFloat()
                canvas.scale(sx, sy)
                v.draw(canvas)
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * F22像素簇扫描（与scanSquareIconLine同阈值同源：W相对0.055~0.115/容差0.02W，
     * cy聚类±28px n>=5，Q_top带限cy<Q_top-100px；多一道亮斑门min>225）。
     * 只读不碰视图；失败返null（调用方记diag不dropped）。
     */
    private fun scanPixelBrightIconLineF22(decor: ViewGroup, logo: View?): IconLine? {
        return try {
            val dm = decor.resources.displayMetrics
            val wPx = dm.widthPixels
            val hPx = dm.heightPixels
            if (wPx <= 0 || hPx <= 0) return null
            val wMin = wPx * 0.055f
            val wMax = wPx * 0.115f
            val whTol = wPx * 0.02f
            val yTol = hPx * 0.012f
            val dloc = IntArray(2)
            runCatching { decor.getLocationOnScreen(dloc) }
            val decorH = decor.height
            val qTop = findQTopOnScreen(decor)
            val yHi = if (qTop != null) (qTop - STRIP_ICON_Q_GUARD_PX).toInt()
                else dloc[1] + (decorH * 0.85f).toInt()
            val yLo = dloc[1] + (decorH * 0.30f).toInt()
            data class Sq(val x: Float, val y: Float, val left: Int, val h: Int, val v: View)
            val cands = ArrayList<Sq>()
            var hops = 0
            var brightPass = 0
            var brightFail = 0
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            while (q.isNotEmpty() && hops < 800) {
                val v = q.removeFirst()
                hops++
                if (v.getTag() == TAG_SEARCH_BUTTON ||
                    v.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                    v.getTag() == TAG_SEARCH_CLEAR
                ) continue
                if (v.visibility != View.VISIBLE || !v.isShown) continue
                if (v is ViewGroup) {
                    val w = v.width
                    val h = v.height
                    val square = w > 0 && h > 0 && w >= wMin && w <= wMax &&
                        h >= wMin && h <= wMax &&
                        kotlin.math.abs(w - h) <= whTol
                    if (square && v !== decor && rowSlotHasImage(v)) {
                        val loc = IntArray(2)
                        runCatching { v.getLocationOnScreen(loc) }
                        val cy = loc[1] + h / 2f
                        val cx = loc[0] + w / 2f
                        val inBand = if (decorH > 0 && loc[1] > 0) {
                            cy > yLo && cy < yHi && loc[1] > dloc[1]
                        } else true
                        if (inBand && loc[1] > 0) {
                            if (runCatching { hasBrightSpotF22(v) }.getOrDefault(false)) {
                                brightPass++
                                cands.add(Sq(cx, cy, loc[0], h, v))
                            } else {
                                brightFail++
                            }
                        }
                        continue
                    }
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                } else {
                    val tv = v as? android.widget.TextView
                    if (tv != null && tv.text?.toString()?.trim()?.length == 1) continue
                    val w = v.width
                    val h = v.height
                    if (!(w > 0 && h > 0 && w >= wMin && w <= wMax &&
                            h >= wMin && h <= wMax &&
                            kotlin.math.abs(w - h) <= whTol)
                    ) continue
                    val loc = IntArray(2)
                    runCatching { v.getLocationOnScreen(loc) }
                    if (loc[1] <= 0) continue
                    val cy = loc[1] + h / 2f
                    val cx = loc[0] + w / 2f
                    if (decorH > 0) {
                        if (!(cy > yLo && cy < yHi)) continue
                    }
                    if (runCatching { hasBrightSpotF22(v) }.getOrDefault(false)) {
                        brightPass++
                        cands.add(Sq(cx, cy, loc[0], h, v))
                    } else {
                        brightFail++
                    }
                }
            }
            val cysSorted = cands.map { it.y }.sorted()
            AndroidLog.i(TAG, "toolbar align F22 pixel: brightPass=$brightPass brightFail=$brightFail " +
                "squares=${cands.size} qTop=$qTop yBand=[$yLo,$yHi] cys=$cysSorted")
            if (cands.size < 5) {
                AndroidLog.e(TAG, "toolbar align F22 pixel: samples too few n=${cands.size} " +
                    "need=n>=5 cyTol=28px qGuard=${STRIP_ICON_Q_GUARD_PX}px")
                return null
            }
            val cyTolPx = 28f
            val byY = cands.sortedBy { it.y }
            data class Cl(val items: List<Sq>)
            val clusters = ArrayList<Cl>()
            var i = 0
            while (i < byY.size) {
                var j = i
                while (j < byY.size && byY[j].y - byY[i].y <= cyTolPx) j++
                val slice = byY.subList(i, j)
                if (slice.size >= 5) clusters.add(Cl(slice.toList()))
                i++
            }
            if (clusters.isEmpty()) {
                AndroidLog.e(TAG, "toolbar align F22 pixel: no cluster n=${cands.size} need=n>=5 cyTol=28px")
                return null
            }
            val sortedCl = clusters.sortedWith(
                compareByDescending<Cl> { it.items.size }
                    .thenBy { cl -> cl.items.map { it.y }.average() }
            )
            val best = sortedCl.firstOrNull() ?: return null
            var useItems = best.items.sortedBy { it.left }
            if (logo != null && useItems.isNotEmpty()) {
                val lloc = IntArray(2)
                runCatching { logo.getLocationOnScreen(lloc) }
                if (lloc[1] > 0 && kotlin.math.abs(useItems[0].y - (lloc[1] + logo.height / 2f)) <= yTol &&
                    kotlin.math.abs(useItems[0].left - lloc[0]) <= wPx * 0.05f
                ) {
                    useItems = useItems.subList(1, useItems.size)
                }
            }
            if (useItems.isEmpty()) {
                AndroidLog.e(TAG, "toolbar align F22 pixel: empty after logo exclude")
                return null
            }
            var sum = 0f
            var hSum = 0
            var hN = 0
            for (s in useItems) {
                sum += s.y
                if (s.h > 0) { hSum += s.h; hN++ }
            }
            if (hN == 0) return null
            val lineY = sum / useItems.size
            val avgH = hSum.toFloat() / hN
            val halfH = avgH / 2f
            val iconTop = lineY - halfH
            AndroidLog.i(TAG, "toolbar align F22 pixel: n=${best.items.size}/${cands.size} " +
                "y=${lineY.toInt()} top=${iconTop.toInt()} avgH=${avgH.toInt()} qTop=$qTop")
            IconLine(lineY, halfH, iconTop, useItems.size)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar align F22 pixel failed: $t")
            null
        }
    }

    /**
     * F22统一出口（禁止复用旧值 + 视觉交叉二选一以像素为准，diagnostically）：
     * 每次调用现扫视图簇（scanSquareIconLine现扫，cy±28px n>=5，Q_top带限cy<Q_top-100px）
     * + 现扫像素簇（亮斑min>225连通域），差>20px记mismatch用像素值。
     * 任一扫不到记diag不dropped（返另一方；双缺返null由调用方keep seam）。
     * 附stale证据：视图簇top/cy连轮 pinned（如1365/1416基线）而条底已动或像素簇分歧，
     * 即记STALE-SUSPECT（旧值疑为开条前键盘页旧簇缓存或Q排误检）。
     * 只读几何+位图采样，不碰视图/顶底槽栏键/壳/s0/圆角B/DEL/commit/logo。
     */
    private fun resolveIconLineF22(decor: ViewGroup, logo: View?): IconLine? {
        return try {
            // 禁止复用旧值：本函数无任何缓存，每次现扫（调用方每轮必调本函数，不持旧IconLine）。
            val viewLine = runCatching { scanSquareIconLine(decor, logo) }.getOrNull()
            // F23波段优先（直接抄tools/open-strip.sh detect：stripBottom→Q_top灰带白圆阈值，
            // 合成像素与外部脚本同源，修F22 drawable采样brightPass=0失败）；旧drawable法仅记diag fallback。
            val stripRowF23 = runCatching {
                decor.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER)
            }.getOrNull()
            val pixelBandF23 = runCatching { scanPixelBandF23(decor, logo, stripRowF23) }.getOrNull()
            val pixelOldF22 = runCatching { scanPixelBrightIconLineF22(decor, logo) }.getOrNull()
            if (pixelBandF23 == null && pixelOldF22 != null) {
                AndroidLog.i(TAG, "strip F23 pixel band miss, fallback old drawable " +
                    "oldTop=${pixelOldF22.top.toInt()} oldN=${pixelOldF22.n} (diag only)")
            }
            val pixelLine = pixelBandF23 ?: pixelOldF22
            val qTop = runCatching { findQTopOnScreen(decor) }.getOrNull()
            val viewTop = viewLine?.top?.toInt() ?: -1
            val viewCy = viewLine?.centerY?.toInt() ?: -1
            val viewN = viewLine?.n ?: -1
            val pixTop = pixelLine?.top?.toInt() ?: -1
            val pixCy = pixelLine?.centerY?.toInt() ?: -1
            val pixN = pixelLine?.n ?: -1
            if (viewLine == null && pixelLine == null) {
                AndroidLog.e(TAG, "strip F22 resolve: both missing diag only (not dropped) " +
                    "qTop=$qTop need=n>=5 cyTol=28px qGuard=${STRIP_ICON_Q_GUARD_PX}px brightMin=$STRIP_ICON_BRIGHT_MIN")
                return null
            }
            if (viewLine == null) {
                AndroidLog.e(TAG, "strip F22 resolve: view missing diag only, use pixel " +
                    "pixTop=$pixTop pixCy=$pixCy pixN=$pixN qTop=$qTop (not dropped)")
                noteIconStaleF22(pixelLine!!, null)
                return pixelLine
            }
            if (pixelLine == null) {
                // Q_top带限显式复核：视图簇若贴Q排（cy>=Q_top-100）即疑Q误检，记diag仍返视图（像素缺席不断链）。
                val qFail = if (qTop != null) viewLine.centerY >= (qTop - STRIP_ICON_Q_GUARD_PX) else false
                AndroidLog.e(TAG, "strip F22 resolve: pixel missing diag only, use view " +
                    "viewTop=$viewTop viewCy=$viewCy viewN=$viewN qTop=$qTop qGuardFail=$qFail " +
                    "(not dropped; pixel brightMin=$STRIP_ICON_BRIGHT_MIN)")
                noteIconStaleF22(viewLine, null)
                return viewLine
            }
            val diff = kotlin.math.abs(viewLine.top - pixelLine.top).toInt()
            val qFailView = if (qTop != null) viewLine.centerY >= (qTop - STRIP_ICON_Q_GUARD_PX) else false
            val qFailPix = if (qTop != null) pixelLine.centerY >= (qTop - STRIP_ICON_Q_GUARD_PX) else false
            if (diff > STRIP_ICON_PIXEL_MISMATCH_PX) {
                AndroidLog.e(TAG, "strip F22 MISMATCH: viewTop=$viewTop viewCy=$viewCy viewN=$viewN " +
                    "pixTop=$pixTop pixCy=$pixCy pixN=$pixN diff=$diff(>20) qTop=$qTop " +
                    "qFailView=$qFailView qFailPix=$qFailPix use=PIXEL(diagnostically) " +
                    "suspect=stale-cache-or-Q-misdetect")
                noteIconStaleF22(pixelLine, viewLine)
                return pixelLine
            }
            AndroidLog.i(TAG, "strip F22 match: viewTop=$viewTop viewCy=$viewCy viewN=$viewN " +
                "pixTop=$pixTop pixCy=$pixCy pixN=$pixN diff=$diff(<=20) qTop=$qTop use=VIEW")
            noteIconStaleF22(viewLine, null)
            viewLine
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip F22 resolve failed: $t")
            runCatching { scanSquareIconLine(decor, logo) }.getOrNull()
        }
    }

    /** F22 stale记账（只记诊断不复用：比对上轮视图簇是否pinned不动）。 */
    private fun noteIconStaleF22(effective: IconLine, viewAlt: IconLine?) {
        try {
            val prevTop = stripIconLastViewTopF22
            val prevCy = stripIconLastViewCyF22
            val curTop = (viewAlt ?: effective).top
            val curCy = (viewAlt ?: effective).centerY
            if (prevTop > 0 && prevCy > 0) {
                val dTop = kotlin.math.abs(curTop - prevTop)
                val dCy = kotlin.math.abs(curCy - prevCy)
                if (dTop < 1f && dCy < 1f) {
                    AndroidLog.i(TAG, "strip F22 stale-watch: view pinned top=${curTop.toInt()} " +
                        "cy=${curCy.toInt()} n=${(viewAlt ?: effective).n} " +
                        "(prev same; C9基线1365/1416? effectiveTop=${effective.top.toInt()})")
                }
            }
            stripIconLastViewTopF22 = curTop
            stripIconLastViewCyF22 = curCy
        } catch (_: Throwable) {
        }
    }

    /** F35 Q作用域扫描（只读）：给定scope内找单字1~0数字首排与QWERTYUIOP≥5，返tops明细；VISIBLE+isShown+>midY+条tag排除同F33口径。 */
    private data class QScanF35(
        val digitBest: Int?,
        val digitCount: Int,
        val digitTops: List<Int>,
        val qBest: Int?,
        val qCount: Int,
        val qTops: List<Int>,
        val azOther: Int
    )
    private fun scanQScopeF35(scope: ViewGroup, midY: Int): QScanF35 {
        val digitSet = setOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val qRowSet = setOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
        var digitBest: Int? = null
        var digitCount = 0
        val digitTops = ArrayList<Int>()
        var qBest: Int? = null
        var qCount = 0
        val qTops = ArrayList<Int>()
        var azOther = 0
        try {
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(scope)
            var hops = 0
            // F40：hop 600→800（decor回退树深，600截断致qTop=-1；800有界，Q>=5+极差+>midY口径不动）。
            while (q.isNotEmpty() && hops < 800) {
                val v = q.removeFirst()
                hops++
                if (v.getTag() == TAG_SEARCH_BUTTON ||
                    v.getTag() == TAG_SEARCH_BOX_CONTAINER ||
                    v.getTag() == TAG_SEARCH_CLEAR ||
                    v.getTag() == TAG_SEARCH_BOX
                ) continue
                if (v is android.widget.TextView && v.visibility == View.VISIBLE && v.isShown) {
                    val t = runCatching { v.text?.toString()?.trim() }.getOrNull() ?: ""
                    if (t.length == 1) {
                        val up = t.uppercase()
                        val loc = IntArray(2)
                        runCatching { v.getLocationOnScreen(loc) }
                        val laid = loc[1] > 0 && v.width > 0 && v.height > 0
                        val inKbHalf = loc[1] > midY
                        if (laid && inKbHalf) {
                            if (t in digitSet) {
                                digitCount++
                                digitTops.add(loc[1])
                                if (digitBest == null || loc[1] < digitBest!!) digitBest = loc[1]
                                continue
                            }
                            if (up in qRowSet) {
                                qCount++
                                qTops.add(loc[1])
                                if (qBest == null || loc[1] < qBest!!) qBest = loc[1]
                                continue
                            }
                            if (up.length == 1 && up[0] in 'A'..'Z') {
                                azOther++
                            }
                        }
                        continue
                    }
                }
                if (v is ViewGroup) {
                    for (idx in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(idx)?.let { q.add(it) }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        digitTops.sort()
        qTops.sort()
        return QScanF35(digitBest, digitCount, digitTops.toList(), qBest, qCount, qTops.toList(), azOther)
    }
    /**
     * F33 qTop修（接F32，在此基础上改，不reset）：C36铁证键盘qTop缺测dy=0（实Q1498≈基线1494键盘从没动过，
     * C35的Q1320是把图标当Q误标——旧找法全decor扫单字A-Z，工具栏/候选区单字误检）。
     * 修：键盘容器作用域内找字面“1”~“0”数字首排≥5记首排顶diag；同容器内找Q排字面QWERTYUIOP≥5
     * 取最小top为Q顶返补偿判据；任一缺测记diag返null上层skip不动（skip≠fail：缺测不断言/不REVERT，
     * 调用方tryCompensateKeyboardF32记skip、复测q-after记SKIP/PASS/DRIFT-diag三态）。
     * 可信门（防1320类误标，ij均运行时现算不写死px）：Q须下半区（>midY，kb作用域内亦然）；
     * Q排须成排（tops极差<=0.06H，否则跨排散点不可信）；Q须在数字首排之下（digitTop<QTop，
     * digit缺测则不判）；任一不可信即缺测返null skip。键盘补偿保留但Q从未动过。
     * 位移/退壳/壳/s0/J3钳/圆角B/DEL/commit/logo全不动。
     * FAIL①旧语义（白卡Q上方判定kt侧同源）保留：找不到返null上层回退decor带，不硬编码px。
     * F35回退（保留）：kb缺测/不可信时再试decor全树回退同判据（Q>=5+极差<=0.06H+>midY，
     * digitTop<QTop仅digit可见时要求，不可见即放宽），命中即返，仍缺测才skip不断言。
     * Q基线只留normal=1494±8。F32几何+F33缝19~21+viewN>=5唯一门像素只diag+toolbar dy=0+圆角B不动。 */
    private fun findQTopOnScreen(decor: ViewGroup): Int? {
        return try {
            // 键盘页作用域：优先键盘容器内找（防工具栏/候选区单字误检为Q）；容器缺失则回退全decor但仍按字面严格判据。
            val clF33 = hostClassLoader ?: decor.context?.classLoader
            val kbIdF33 = clF33?.let { runCatching { resolveKeyboardContainerId(it) }.getOrNull() }
            val kbScopeF33: ViewGroup? = kbIdF33?.let {
                runCatching { decor.findViewById<View>(it) as? ViewGroup }.getOrNull()
            }
            val scopeF33: ViewGroup = kbScopeF33 ?: decor
            val scopeTagF33 = if (kbScopeF33 != null) "kbContainer" else "decorFallback"
            val dloc = IntArray(2)
            runCatching { decor.getLocationOnScreen(dloc) }
            val decorH = decor.height.takeIf { it > 0 } ?: return null
            val midY = dloc[1] + (decorH * 0.5f).toInt()
            val rangeTol = (decorH * 0.06f).toInt().coerceAtLeast(28)
            fun isCredibleF35(s: QScanF35): Boolean {
                val qb = s.qBest ?: return false
                if (s.qCount < 5) return false
                if (qb <= midY) return false
                val qr = if (s.qTops.size >= 2) s.qTops.last() - s.qTops.first() else 0
                if (qr > rangeTol) return false
                // F35：digitTop<QTop仅digit可见时要求，不可见即放宽（digitBest==null即放宽）。
                if (s.digitBest != null && qb < s.digitBest!!) return false
                return true
            }
            val firstF35 = runCatching { scanQScopeF35(scopeF33, midY) }.getOrNull()
            if (firstF35 != null) {
                AndroidLog.i(TAG, "strip F33 qTop scan: scope=$scopeTagF33 digitN=${firstF35.digitCount} digitTop=${firstF35.digitBest ?: -1} " +
                    "digitTops=${firstF35.digitTops.take(12)} qRowN=${firstF35.qCount} qTop=${firstF35.qBest ?: -1} qTops=${firstF35.qTops.take(12)} " +
                    "azOther=${firstF35.azOther} midY=$midY (首排1~0记diag，返Q排顶)")
                if (isCredibleF35(firstF35)) return firstF35.qBest
                if (firstF35.qBest != null && firstF35.qCount >= 5) {
                    val qr0 = if (firstF35.qTops.size >= 2) firstF35.qTops.last() - firstF35.qTops.first() else 0
                    if (firstF35.qBest!! <= midY) {
                        AndroidLog.e(TAG, "strip F33 qTop untrusted upper-half skip: qTop=${firstF35.qBest} midY=$midY " +
                            "scope=$scopeTagF33 qRowN=${firstF35.qCount} (1320类误标防线，试decor回退)")
                    } else if (qr0 > rangeTol) {
                        AndroidLog.e(TAG, "strip F33 qTop untrusted scattered skip: range=$qr0(>$rangeTol≈0.06H) " +
                            "qTops=${firstF35.qTops.take(12)} scope=$scopeTagF33 (跨排散点不可信，试decor回退)")
                    } else if (firstF35.digitBest != null && firstF35.qBest!! < firstF35.digitBest!!) {
                        AndroidLog.e(TAG, "strip F33 qTop untrusted order skip: qTop=${firstF35.qBest} digitTop=${firstF35.digitBest} " +
                            "(Q须在数字首排之下，逆序即误标，试decor回退)")
                    }
                } else {
                    AndroidLog.e(TAG, "strip F33 qTop missing (diag only, 试decor回退): scope=$scopeTagF33 " +
                        "digitN=${firstF35.digitCount} digitTop=${firstF35.digitBest ?: -1} qRowN=${firstF35.qCount} qTop=${firstF35.qBest ?: -1} need=Q排≥5")
                }
            }
            // F35 decor回退：kb缺测/不可信且kb存在时再扫全decor同判据。
            if (kbScopeF33 != null && scopeF33 !== decor) {
                val secondF35 = runCatching { scanQScopeF35(decor, midY) }.getOrNull()
                if (secondF35 != null) {
                    AndroidLog.i(TAG, "strip F33 qTop scan: scope=decorFallback digitN=${secondF35.digitCount} digitTop=${secondF35.digitBest ?: -1} " +
                        "digitTops=${secondF35.digitTops.take(12)} qRowN=${secondF35.qCount} qTop=${secondF35.qBest ?: -1} qTops=${secondF35.qTops.take(12)} " +
                        "azOther=${secondF35.azOther} midY=$midY (回退，同判据Q>=5+极差<=0.06H+>midY)")
                    if (isCredibleF35(secondF35)) {
                        AndroidLog.i(TAG, "strip F33 qTop fallback-hit: qTop=${secondF35.qBest} scope=decorFallback " +
                            "qRowN=${secondF35.qCount} digitN=${secondF35.digitCount} (kb缺测回退命中)")
                        return secondF35.qBest
                    }
                    AndroidLog.e(TAG, "strip F33 qTop missing (diag only, skip不动): scope=decorFallback " +
                        "digitN=${secondF35.digitCount} digitTop=${secondF35.digitBest ?: -1} qRowN=${secondF35.qCount} qTop=${secondF35.qBest ?: -1} need=Q排≥5")
                    return null
                }
            }
            // kb缺席时scope已是decor，无二次回退；kb存在但回退未命中亦落此。
            if (firstF35 == null) {
                AndroidLog.e(TAG, "strip F33 qTop missing (diag only, skip不动): scope=$scopeTagF33 scanNull")
            } else if (kbScopeF33 == null) {
                AndroidLog.e(TAG, "strip F33 qTop missing (diag only, skip不动): scope=$scopeTagF33 " +
                    "digitN=${firstF35.digitCount} digitTop=${firstF35.digitBest ?: -1} qRowN=${firstF35.qCount} qTop=${firstF35.qBest ?: -1} need=Q排≥5")
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** logo 回位到图标线（宽松行查找；行实在没有则仅记日志不动）。 */
    private fun alignLogoToIconLine(decor: ViewGroup) {
        try {
            val logo = resolveLogoView(decor)
            if (logo == null || logo.visibility != View.VISIBLE || logo.height <= 0) {
                AndroidLog.e(TAG, "toolbar align: logo_iv not found or hidden")
                return
            }
            // S7：工具栏行零高（键盘页还没切回来、行没布局）时不对齐，
            // 否则会按错乱几何误算 dy（如 -140）把 logo 顶飞；等切页后重试。
            var plug = logo.parent
            var pd = 0
            while (plug is ViewGroup && pd < 3) {
                if ((plug as ViewGroup).height <= 0) {
                    AndroidLog.i(TAG, "toolbar align: row not laid out, deferred")
                    return
                }
                plug = (plug as ViewGroup).parent
                pd++
            }
            unclipLogoAncestors(logo, decor)
            val chain = StringBuilder()
            var pp = logo.parent as? View
            var dd = 0
            while (pp != null && dd < 8) {
                val ploc = IntArray(2)
                runCatching { pp.getLocationOnScreen(ploc) }
                chain.append("[${pp.javaClass.name} ${pp.width}x${pp.height} " +
                    "y=${ploc[1]} cc=${(pp as? ViewGroup)?.clipChildren} " +
                    "vis=${pp.visibility}]")
                if (pp === decor) break
                pp = pp.parent as? View
                dd++
            }
            AndroidLog.i(TAG, "toolbar align: logo chain h=${logo.height} " +
                "ty=${logo.translationY} $chain")
            val lineY = iconLineCenterYDecor(decor, logo) ?: run {
                AndroidLog.e(TAG, "toolbar align: icon line not found")
                return
            }
            val ll = IntArray(2)
            runCatching { logo.getLocationOnScreen(ll) }
            val logoCy = ll[1] + logo.height / 2f
            val dy = lineY - logoCy
            synchronized(logoTransOrig) {
                if (!logoTransOrig.containsKey(logo)) logoTransOrig[logo] = logo.translationY
            }
            val applied = synchronized(logoDyApplied) {
                logoDyApplied.getOrDefault(logo, 0f)
            }
            AndroidLog.i(TAG, "toolbar align: logoCy=$logoCy lineY=$lineY dy=$dy " +
                "applied=$applied")
            if (kotlin.math.abs(dy) > 1f) {
                logo.translationY = logo.translationY + dy
                synchronized(logoDyApplied) { logoDyApplied[logo] = applied + dy }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar align: logo line failed: $t")
        }
    }

    /** 宿主 drawable 按名取（多 loader 容忍，仍全宿主原生；无命中返 null）。 */
    private fun resolveHostDrawable(anchor: View, hostLoader: ClassLoader, name: String): Int? {
        for (cl in idClassLoaders(anchor, hostLoader)) {
            if (cl == null) continue
            val id = runCatching {
                Class.forName(WETYPE_DRAWABLE_CLASS, false, cl).getField(name).getInt(null)
            }.getOrNull()
            if (id != null && id != 0) return id
        }
        return null
    }

    /**
     * 键盘页条本体：根 = `ImeRadiusConstraintLayout`
     * + `setRadius(背景圆角设置，方案B)` + `setBorderWidth(1f)` + skin.t，圆角
     * 与输入法背景同值（`WeTypeWindowHooks` 同源）；内排一行
     * `[放大镜(宿主搜索图标) | 输入框(宿主ImeEditText+skin.w) | X(宿主关闭图标) | 收起(原生参数+skin.j)]`。
     * 根带 `TAG_SEARCH_BOX_CONTAINER`，经 s0 插槽挂载，无外边距、全幅宽。
     * 任一步原生失败返 null，上层整条 fail-closed。
     */
    private fun buildKeyboardStrip(ref: ViewGroup): View? {
        val context = ref.context
        val res = context.resources
        val root = newNativeStripRoot(context) ?: return null
        root.tag = TAG_SEARCH_BOX_CONTAINER
        root.visibility = View.VISIBLE
        root.isClickable = true
        root.isFocusable = false
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val padH = dpToPx(res, ROW_PADDING_H_DP)
            val padV = dpToPx(res, ROW_PADDING_V_DP)
            setPadding(padH, padV, padH, padV)
            isClickable = false
            isFocusable = false
        }
        root.addView(
            row,
            childLayoutParams(
                root,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val iconSize = dpToPx(res, ROW_ICON_DP)
        val searchIcon = resolveSearchIconRes(root, hostClassLoader ?: context.classLoader)
            ?: return null
        val icon = ImageView(context).apply {
            contentDescription = "搜索"
            setImageResource(searchIcon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = dpToPx(res, 4f)
            }
        }
        row.addView(icon)

        val boxH = nativeScaledPx("e0", NATIVE_INPUT_H_E0) ?: return null
        AndroidLog.i(TAG, "strip input geometry: boxH=$boxH")
        val cl = hostClassLoader ?: context.classLoader
        if (cl == null) return null
        val cursorRes = resolveHostDrawable(root, cl, NATIVE_CURSOR_DRAWABLE)
        val box = createSearchBox(context, boxH, cursorRes) ?: return null
        box.visibility = View.VISIBLE
        row.addView(box, LinearLayout.LayoutParams(0, boxH, 1f))

        val clear = createClearButton(context, box) ?: return null
        row.addView(clear)
        val exit = createNativeExitButton(context) ?: return null
        row.addView(exit)
        val exitColor = exit.currentTextColor
        if (Color.alpha(exitColor) > 0) {
            icon.setColorFilter(exitColor, PorterDuff.Mode.SRC_IN)
        } else {
            applyThemeIconTint(icon)
        }
        AndroidLog.i(TAG, "strip native map ok: root=${root.javaClass.name} " +
            "searchIcon=$searchIcon box=${box.javaClass.name}")
        return root
    }

    /**
     * 条根：反射 `new ImeRadiusConstraintLayout(context, null)`
     * + `setRadius(背景圆角)` + `setBorderWidth(1f)` + `d.h(root,false,skin.t)`。
     * 方案B（用户拍板）：条圆角跟输入法背景走（WeTypeSettings 圆角 dp→px），
     * 不再用原生 `r1.f0(32)`，保证白卡与背景上圆角一致。任一步失败返 null
     *（整条 fail-closed，禁 FrameLayout/灰底兜底）。
     */
    private fun newNativeStripRoot(context: android.content.Context): ViewGroup? {
        try {
            val cl = hostClassLoader ?: run {
                AndroidLog.e(TAG, "native root: host loader missing")
                return null
            }
            val rootCls = runCatching {
                Class.forName(NATIVE_RADIUS_CLASS, false, cl)
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "native root: $NATIVE_RADIUS_CLASS missing")
                return null
            }
            val ctor = runCatching {
                rootCls.getDeclaredConstructor(
                    android.content.Context::class.java,
                    android.util.AttributeSet::class.java
                )
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "native root: ctor missing")
                return null
            }
            ctor.isAccessible = true
            val root = runCatching {
                ctor.newInstance(context, null) as? ViewGroup
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "native root: newInstance failed")
                return null
            }
            val setRadius = rootCls.declaredMethods.firstOrNull {
                it.name == "setRadius" && it.parameterTypes.size == 1
            } ?: run {
                AndroidLog.e(TAG, "native root: setRadius missing")
                return null
            }
            setRadius.isAccessible = true
            // 方案B：条圆角 = 输入法背景圆角减 8dp（用户设置 dp→px，与
            // WeTypeWindowHooks.resolveCornerRadii 同源，半径少 8dp），保证卡片适度圆润。
            val radius = try {
                val baseRadiusDp = WeTypeSettings.getCornerRadiusXposed(context).toFloat()
                val cardRadiusDp = resolveCardCornerRadiusDp(baseRadiusDp)
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    cardRadiusDp,
                    context.resources.displayMetrics
                ).roundToInt()
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip radius: background corner failed: $t")
                return null
            }
            when (setRadius.parameterTypes[0]) {
                Int::class.javaPrimitiveType, Integer::class.java ->
                    setRadius.invoke(root, radius)
                Float::class.javaPrimitiveType, java.lang.Float::class.java ->
                    setRadius.invoke(root, radius.toFloat())
                else -> run {
                    AndroidLog.e(TAG, "native root: setRadius shape unknown")
                    return null
                }
            }
            val setBorder = rootCls.declaredMethods.firstOrNull {
                it.name == "setBorderWidth" && it.parameterTypes.size == 1
            } ?: run {
                AndroidLog.e(TAG, "native root: setBorderWidth missing")
                return null
            }
            setBorder.isAccessible = true
            when (setBorder.parameterTypes[0]) {
                Float::class.javaPrimitiveType, java.lang.Float::class.java ->
                    setBorder.invoke(root, 1f)
                Int::class.javaPrimitiveType, Integer::class.java ->
                    setBorder.invoke(root, 1)
                else -> run {
                    AndroidLog.e(TAG, "native root: setBorderWidth shape unknown")
                    return null
                }
            }
            if (!applyNativeSkin(root, NATIVE_SKIN_ROOT)) {
                AndroidLog.e(TAG, "native root: skin.t bind failed")
                return null
            }
            AndroidLog.i(TAG, "strip native root ok radius=$radius skin=true")
            return root
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "native strip root failed: ${t.message}")
            return null
        }
    }

    /** 子 LP：根若是宿主 ConstraintLayout 系则用其 LP，否则用普通 LP（addView 自转换）。 */
    private fun childLayoutParams(root: ViewGroup, w: Int, h: Int): ViewGroup.LayoutParams {
        try {
            val cl = hostClassLoader
            if (cl != null && root.javaClass.name == NATIVE_RADIUS_CLASS) {
                val lpCls = Class.forName(
                    "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams", false, cl
                )
                val ctor = runCatching { lpCls.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }.getOrNull()
                if (ctor != null) {
                    ctor.isAccessible = true
                    (ctor.newInstance(w, h) as? ViewGroup.LayoutParams)?.let { return it }
                }
            }
        } catch (_: Throwable) {
        }
        return ViewGroup.MarginLayoutParams(w, h)
    }

    /**
     * 原生皮肤绑定：`d.h(view, false, k$<inner>.singleton, 1, null)`。
     * inner 按名取翻译条 k 的内部皮肤 lambda（j=收起字色/ime_color_06，
     * t=条底/ime_color_09+16，w=输入字色），单例按型扫描不写死 f 号。
     * 失败返 false（上层整条 fail-closed）。
     */
    private fun applyNativeSkin(view: View, innerName: String): Boolean {
        return try {
            val cl = hostClassLoader ?: return false
            val kCls = runCatching { Class.forName(NATIVE_TOPVIEW_CLASS, false, cl) }.getOrNull()
                ?: run {
                    AndroidLog.e(TAG, "native skin: topview class missing")
                    return false
                }
            // R8 抹 InnerClasses 属性（运行时 getDeclaredClasses 为空），按二进制名
            // `k$t` 直取内部皮肤单例类，不依赖 inner 反射。
            val inner = runCatching {
                Class.forName(NATIVE_TOPVIEW_CLASS + "$" + innerName, false, cl)
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "native skin: inner $innerName binary missing")
                return false
            }
            val singleton = inner.declaredFields
                .firstOrNull { it.type == inner }
                ?.also { it.isAccessible = true }
                ?.get(null) ?: run {
                    AndroidLog.e(TAG, "native skin: inner $innerName singleton missing")
                    return false
                }
            val dCls = runCatching { Class.forName(NATIVE_SKIN_UTILS_CLASS, false, cl) }.getOrNull()
                ?: run {
                    AndroidLog.e(TAG, "native skin: d class missing")
                    return false
                }
            val bind = dCls.declaredMethods.firstOrNull {
                it.name == "h" && it.parameterTypes.size == 5
            } ?: run {
                AndroidLog.e(TAG, "native skin: d.h(5) missing")
                return false
            }
            bind.isAccessible = true
            try {
                bind.invoke(null, view, false, singleton, 1, null)
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "native skin: d.h invoke failed: $t")
                return false
            }
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "native skin: unexpected $t")
            false
        }
    }

    /** `q1/r1.f0/g0/e0(int)` 静态缩放；失败返 null（上层 fail-closed，禁 dp 兜底）。 */
    private fun nativeScaledPx(method: String, arg: Int): Int? {
        return try {
            val cl = hostClassLoader ?: return null
            var missing = true
            for (clsName in NATIVE_SCALE_CLASSES) {
                val r1 = runCatching { Class.forName(clsName, false, cl) }.getOrNull()
                    ?: continue
                val cands = r1.declaredMethods.filter {
                    it.name == method && it.parameterTypes.size == 1
                }
                if (cands.isEmpty()) continue
                missing = false
                val m = cands.firstOrNull { it.parameterTypes[0] == Int::class.javaPrimitiveType }
                    ?: cands[0]
                m.isAccessible = true
                if (!java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                    AndroidLog.e(TAG, "native scale: $clsName.$method not static")
                    return null
                }
                return (m.invoke(null, arg) as? Number)?.toInt()
            }
            AndroidLog.e(TAG, if (missing) "native scale: $method(1) missing"
            else "native scale: $method($arg) failed: non-numeric")
            null
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "native scale: $method($arg) failed: $t")
            null
        }
    }

    /** `m1/n1.A3(tv, size, fromCandidate)` 原生字号；失败返 false（上层 fail-closed）。 */
    private fun applyNativeFont(tv: android.widget.TextView, size: Int, fromCandidate: Boolean): Boolean {
        return try {
            val cl = hostClassLoader ?: return false
            for (clsName in NATIVE_FONT_CLASSES) {
                val n1 = runCatching { Class.forName(clsName, false, cl) }.getOrNull()
                    ?: continue
                val inst = n1.declaredFields
                    .firstOrNull { it.type == n1 }
                    ?.also { it.isAccessible = true }
                    ?.get(null) ?: continue
                val m = n1.declaredMethods.firstOrNull {
                    it.name == "A3" && it.parameterTypes.size == 3
                } ?: continue
                m.isAccessible = true
                m.invoke(inst, tv, size, fromCandidate)
                return true
            }
            AndroidLog.e(TAG, "native font: A3 missing")
            false
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "native font: A3 failed: $t")
            false
        }
    }

    /** 宿主 r 类按名取图标（首个命中），全无返 null（上层 fail-closed）。 */
    private fun resolveHostIconRes(vararg names: String): Int? {
        return try {
            val cl = hostClassLoader ?: return null
            val rCls = Class.forName(WETYPE_DRAWABLE_CLASS, false, cl)
            names.firstNotNullOfOrNull { runCatching { rCls.getField(it).getInt(null) }.getOrNull() }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 原生收起按钮：翻译条同参 TextView"收起"（gravity17/alignment4 +
     * skin.j + n1.A3(40,true) + padding g0(20)/g0(40)），点之 collapseStrip。
     * 任一步原生失败返 null（整条 fail-closed，禁 14sp/灰字兜底）。
     */
    private fun createNativeExitButton(context: android.content.Context): android.widget.TextView? {
        return try {
            val exit = android.widget.TextView(context).apply {
                text = "收起"
                gravity = android.view.Gravity.CENTER
                setTextAlignment(View.TEXT_ALIGNMENT_CENTER)
            }
            if (!applyNativeFont(exit, NATIVE_EXIT_FONT, true)) {
                AndroidLog.e(TAG, "exit button: n1.A3 failed")
                return null
            }
            if (!applyNativeSkin(exit, NATIVE_SKIN_EXIT)) {
                AndroidLog.e(TAG, "exit button: skin.j bind failed")
                return null
            }
            val padStart = nativeScaledPx("g0", NATIVE_EXIT_PAD_START_G0) ?: run {
                AndroidLog.e(TAG, "exit button: g0 start failed")
                return null
            }
            val padEnd = nativeScaledPx("g0", NATIVE_EXIT_PAD_END_G0) ?: run {
                AndroidLog.e(TAG, "exit button: g0 end failed")
                return null
            }
            exit.setPadding(padStart, 0, padEnd, 0)
            exit.isClickable = true
            exit.isFocusable = true
            exit.setOnClickListener { collapseStrip() }
            exit
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "exit button build failed: ${t.message}")
            null
        }
    }

    /**
     * 条收起：退壳 exit(false) + 拆条 + 清词恢复，不跳页（用户只想关行）。
     * FAIL②：collapse/jumpBack/拆键盘三路必调restoreLogoTranslation+
     * restoreClippedChain+publish0+refresh（经restoreToolbarState统一出口），
     * 收起若宿主切AI条则等工具栏回归再还账或补一次post对线。
     */
    private fun collapseStrip() {
        // F41：原生k在即走原生收起（S/J0/V0/U0/C0/s全还账）；否则走自绘条旧路（已丢弃，仅备查）。
        val nk = nativeKRefF41?.get()
        if (nk != null && nk.parent != null) {
            collapseStripF41()
            return
        }
        try {
            pendingKeyword = ""
            overlayPending = false
            val parent = overlayParentRef?.get()
            overlayParentRef = null
            parent?.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER)?.let { card ->
                removeStripCard(card)
                exitTranslatorShell(card)
                val decor: View = parent
                mainHandler.postDelayed({
                    restoreToolbarState(decor, "collapse", 0)
                }, 120)
            } ?: run {
                // 条已不在（AI条态二次收起）：仍走还账出口保logo复归可观测。
                parent?.let { p ->
                    mainHandler.postDelayed({ restoreToolbarState(p, "collapse", 0) }, 120)
                }
            }
            clearSearch()
            AndroidLog.i(TAG, "strip collapsed")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip collapse failed: ${t.message}")
        }
    }

    /**
     * S5c-A4：回车/✓带词跳回剪贴板（容器第 3 位直点）。
     * 顺序：decor 拆条前捕获 → 摘条还账（工具栏复归，第 3 位可见）→ 等一帧 →
     * 找行点第 3 位 performClick。行/目标找不到返 false（禁假 ok=true）。
     * pendingKeyword 点前已存框词，剪贴板页经 applyKeywordDirect 走 S5 过滤。
     * FAIL②：经restoreToolbarState统一出口（四连+post对线+AI条重试）。
     */
    private fun jumpBackToClipboard(container: View) {
        jumpBackToClipboardF41("enter")
    }


    /**
     * S5c 行形：横向 LinearLayout，或类名含 Row 的横向行容器。
     * 纵向 LinearLayout 一律排除（键列/列表非工具栏行）。
     */
    private fun isToolbarRowShape(row: ViewGroup): Boolean {
        return try {
            if (row is LinearLayout) {
                row.orientation == LinearLayout.HORIZONTAL
            } else {
                val simple = runCatching { row.javaClass.simpleName }.getOrDefault("")
                simple.contains("Row")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar row shape failed: ${t.message}")
            false
        }
    }

    /**
     * S5c 槽位计数：行直接孩子中图标槽位数（禁 desc 依赖）。
     * 直孩 Image 叶子计 1；直孩容器内有可见 Image 叶子计 1
     * （R2 根因：每图标包独立容器，叶子模型直数失效）。
     * 自家搜索条 tag 与 GONE 直孩跳过不计。
     * FAIL①：5~9兼容（logo+5=6圆，旧≥6/≥7判丢与误收12+）。
     */
    private fun countRowIconSlots(row: ViewGroup): Int {
        return try {
            rowIconSlotViews(row).size
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "count row icon slots failed: ${t.message}")
            0
        }
    }

    /** FAIL①：行图标槽直孩列表（计数与间距同源，禁desc依赖）。 */
    private fun rowIconSlotViews(row: ViewGroup): List<View> {
        val out = ArrayList<View>()
        try {
            for (i in 0 until row.childCount) {
                val kid = runCatching { row.getChildAt(i) }.getOrNull() ?: continue
                if (kid.getTag() == TAG_SEARCH_BUTTON ||
                    kid.getTag() == TAG_SEARCH_BOX_CONTAINER
                ) {
                    continue
                }
                if (kid.visibility != View.VISIBLE) continue
                if (kid is ViewGroup) {
                    if (rowSlotHasImage(kid)) out.add(kid)
                } else if (isToolbarImageLeaf(kid)) {
                    out.add(kid)
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "row slot list failed: ${t.message}")
        }
        return out
    }

    /**
     * FAIL①：logo首缝放宽（open-strip.sh同判据，W相对无写死px）。
     * slots按屏左排序，dxs首缝0.07W~0.45W、其余0.07W~0.18W（logo宽缝），
     * 或全匀0.07W~0.18W（无logo簇兼容）。失败fail-closed（返false）。
     */
    private fun isToolbarRowSpacingOk(slots: List<View>, wPx: Int): Boolean {
        return try {
            if (slots.size !in 5..9 || wPx <= 0) return false
            val lefts = slots.map {
                val loc = IntArray(2)
                runCatching { it.getLocationOnScreen(loc) }
                loc[0]
            }
            // 未布局（left全0）不卡，靠计数+几何定行。
            if (lefts.all { it == 0 }) return true
            val sorted = lefts.sorted()
            val dxs = ArrayList<Int>()
            for (k in 0 until sorted.size - 1) dxs.add(sorted[k + 1] - sorted[k])
            if (dxs.any { it <= 0 }) return false
            val gapMin = (wPx * 0.07f).toInt()
            val gapMax = (wPx * 0.18f).toInt()
            val gapFirstMax = (wPx * 0.45f).toInt()
            val wideFirst = dxs[0] in gapMin..gapFirstMax &&
                (dxs.size == 1 || dxs.subList(1, dxs.size).all { it in gapMin..gapMax })
            val uniform = dxs.all { it in gapMin..gapMax }
            wideFirst || uniform
        } catch (_: Throwable) {
            false
        }
    }

    /** S5c 槽内探针：容器内（3 层/每层 25 孩上限）是否有可见 Image 叶子。 */
    private fun rowSlotHasImage(slot: ViewGroup): Boolean {
        return try {
            val q: ArrayDeque<Pair<View, Int>> = ArrayDeque()
            q.add(slot to 0)
            var hops = 0
            while (q.isNotEmpty() && hops < 100) {
                val (v, depth) = q.removeFirst()
                hops++
                if (v.getTag() == TAG_SEARCH_BUTTON ||
                    v.getTag() == TAG_SEARCH_BOX_CONTAINER
                ) {
                    continue
                }
                if (v.visibility != View.VISIBLE) continue
                if (v !is ViewGroup) {
                    if (isToolbarImageLeaf(v)) return true
                } else if (depth < 3) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it to depth + 1) }
                    }
                }
            }
            false
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "row slot probe failed: ${t.message}")
            false
        }
    }

    /**
     * S5c 几何门：宽≈屏宽（运行时屏宽分数，无写死 px）+ y 在键盘窗
     * 上半区带内（decor 下半窗偏上，排除底部按键行与顶部应用区）。
     * 未量出（宽/顶为 0）不卡，靠槽位计数定行。
     */
    private fun isToolbarRowGeometry(row: ViewGroup, decor: ViewGroup): Boolean {
        return try {
            val dm = row.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val rowW = row.width
            if (rowW > 0 && screenW > 0 &&
                rowW < (screenW * 0.8f).roundToInt()
            ) {
                return false
            }
            val loc = IntArray(2)
            runCatching { row.getLocationOnScreen(loc) }
            val rowTop = loc[1]
            if (rowTop <= 0) return true
            val dloc = IntArray(2)
            runCatching { decor.getLocationOnScreen(dloc) }
            val decorH = if (decor.height > 0) decor.height else screenH
            val top = dloc[1]
            rowTop > top + (decorH * 0.3f).roundToInt() &&
                rowTop < top + (decorH * 0.8f).roundToInt()
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar row geometry failed: ${t.message}")
            true
        }
    }

    /**
     * S5c 第 3 位解析：直孩 Image 叶子直点；容器深入取首个可点 Image 叶子
     * （无可点则取首个可见 Image 叶子）；非 Image 直孩返 null（禁点非第 3 位，
     * 禁回退他位，找不到就 false）。
     */
    private fun resolveIdx2Target(slot: View): View? {
        return try {
            if (slot.getTag() == TAG_SEARCH_BUTTON ||
                slot.getTag() == TAG_SEARCH_BOX_CONTAINER
            ) {
                return null
            }
            if (slot.visibility != View.VISIBLE) return null
            if (slot !is ViewGroup) {
                return if (isToolbarImageLeaf(slot)) slot else null
            }
            var fallback: View? = null
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(slot)
            var hops = 0
            while (q.isNotEmpty() && hops < 100) {
                val v = q.removeFirst()
                hops++
                if (v !== slot) {
                    if (v.getTag() == TAG_SEARCH_BUTTON ||
                        v.getTag() == TAG_SEARCH_BOX_CONTAINER
                    ) {
                        continue
                    }
                    if (v.visibility != View.VISIBLE) continue
                    if (v !is ViewGroup && isToolbarImageLeaf(v)) {
                        if (v.isClickable) return v
                        if (fallback == null) fallback = v
                        continue
                    }
                }
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            fallback
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "resolve idx2 target failed: ${t.message}")
            null
        }
    }

    /** Image 系叶子判定：ImageView（含 ImageButton 子类）或类名含 Image 系。 */
    private fun isToolbarImageLeaf(v: View): Boolean {
        return try {
            if (v is ImageView) return true
            val simple = runCatching { v.javaClass.simpleName }.getOrDefault("")
            val full = runCatching { v.javaClass.name }.getOrDefault("")
            simple.contains("ImageView") || simple.contains("ImageButton") ||
                full.contains("ImageView") || full.contains("ImageButton")
        } catch (_: Throwable) {
            false
        }
    }

    private fun viewBoundsOf(v: View): String {
        return try {
            val loc = IntArray(2)
            runCatching { v.getLocationOnScreen(loc) }
            if (loc[0] != 0 || loc[1] != 0) {
                "${loc[0]},${loc[1]}-${v.width}x${v.height}"
            } else {
                "l=${v.left},t=${v.top},w=${v.width},h=${v.height}"
            }
        } catch (_: Throwable) {
            "?"
        }
    }

    /**
     * 落页校验：decor 树找剪贴板/常用语 tabs+列表 vs 手写页（字迹/手写）。
     * C48三缺口②③（本单）：只查文本不查可见致键盘页亦报clipboard ok=true（5s窗视觉与日志背离）。
     * 修为加VISIBLE/isShown/位置判定（宽高>0+屏位y>0+isShown双真），键盘页不再报ok（返keyboard FAIL，
     * fail-closed补图标行）；灰空页unknown留C49可辨识行（禁改sh，只留日志，C49加灰页CLIPBOARD兜底）。
     * G6落点（只修causas分清，其余隔离/双行卡/下拉/hint/栏/J3/圆角B/零仿制不动）：
     * 真落点须clipboard ok=true且VISIBLE/isShown+宽高屏位有效（isClipboardPageShowing已含）；
     * keyboard FAIL causa=tabs可见但列表隐/零尺寸/屏位无效（键盘页工具栏+QWERTY，非落点，须补图标行）；
     * unknown causa=tabs缺失（灰空页/翻译壳未退/键盘页无tabs三者，sh灰页兜底另判，此处fail-closed）。
     * 返回 `clipboard ok=true` / `keyboard FAIL` / `handwriting FAIL` / `unknown`。
     * 只改落点判定，不碰隔离/双行卡/下拉/hint/栏/J3/圆角B/零仿制。
     */


    /**
     * 拆键盘还账（Finish/切页防泄漏）。
     * FAIL②：经restoreToolbarState统一出口（四连+post对线+AI条重试）。
     */
    private fun teardownStrip() {
        if (nativeSearchRestore != null || submitSession.phase == ClipboardSearchSubmitSession.Phase.COMMITTING ||
            submitSession.phase == ClipboardSearchSubmitSession.Phase.EXITING ||
            submitSession.phase == ClipboardSearchSubmitSession.Phase.OPENING) {
            collapseStripF41()
            return
        }
        // F41：原生k在即走原生收起全还账；否则走旧路（自绘条已丢弃）。
        val nk = nativeKRefF41?.get()
        if (nk != null && nk.parent != null) {
            collapseStripF41()
            return
        }
        try {
            overlayPending = false
            val parent = overlayParentRef?.get()
            overlayParentRef = null
            if (parent == null) return
            parent.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER)?.let { card ->
                removeStripCard(card)
                exitTranslatorShell(card)
                mainHandler.postDelayed({
                    restoreToolbarState(parent, "teardown", 0)
                }, 120)
                AndroidLog.i(TAG, "strip torn down on input finish")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "teardown strip failed: ${t.message}")
        }
    }

    private fun clearSearchOnMain() {
        if (submitSession.phase == ClipboardSearchSubmitSession.Phase.COMMITTING ||
            submitSession.phase == ClipboardSearchSubmitSession.Phase.EXITING ||
            submitSession.phase == ClipboardSearchSubmitSession.Phase.OPENING) return
        try {
            synchronized(trackedBoxes) {
                val it = trackedBoxes.iterator()
                while (it.hasNext()) {
                    val box = it.next()
                    try {
                        if (box.parent == null) {
                            it.remove()
                            continue
                        }
                        if (box.text?.isNotEmpty() == true) box.setText("")
                        updateClearVisibility(box)
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "clear box failed: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "clearSearch failed: ${t.message}")
        }
    }

    private fun updateClearVisibility(box: EditText) {
        try {
            val parent = box.parent as? ViewGroup ?: return
            val clear = parent.findViewWithTag<View>(TAG_SEARCH_CLEAR) ?: return
            var v: Boolean? = null
            var node: View? = parent
            while (node != null) {
                if (node.getTag() == TAG_SEARCH_BOX_CONTAINER) { v = node.visibility == View.VISIBLE; break }
                node = node.parent as? View
            }
            val show = (v ?: (box.visibility == View.VISIBLE)) && box.text?.isNotEmpty() == true
            clear.visibility = if (show) View.VISIBLE else View.GONE
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "update clear visibility failed: ${t.message}")
        }
    }

    /**
     * 搜索输入框（全原生，零仿制）：宿主 `ImeEditText` + skin.w
     *（ime_key_text_color/ime_color_08/transparent，随深浅主题切换）
     * + 原生几何（m1.A3(45,true) + padding g0(40)/e0(40) + 高 e0(143)
     * + 行高 e0(63) + gravity 8388627 + 绿光标）。
     * 宿主类缺失 / skin.w / 字号 / 边距 / 高度任一步失败返 null
     *（整条 fail-closed，禁 EditText/硬编码字色兜底）。
     * `background=null` 去默认下划线（去样式非仿制）。
     */
    private fun createSearchBox(
        context: android.content.Context,
        boxH: Int,
        cursorRes: Int?
    ): EditText? {
        val box = createHostBox(context) ?: run {
            AndroidLog.e(TAG, "search box: host ImeEditText missing")
            return null
        }
        AndroidLog.i(TAG, "search box widget: ${box.javaClass.name}")
        box.apply {
            tag = TAG_SEARCH_BOX
            hint = "搜索剪贴板"
            setSingleLine(true)
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            // 防劫持：NO_SUGGESTIONS 阻宿主联想劫持条词；
            // ENTER 经 StripInputConnection/onKey/EditorAction 三路放行。
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            visibility = View.VISIBLE
            background = null
            gravity = NATIVE_INPUT_GRAVITY
            isFocusable = true
            isFocusableInTouchMode = true
        }
        if (!applyNativeSkin(box, NATIVE_SKIN_INPUT)) {
            AndroidLog.e(TAG, "search box: skin.w bind failed")
            return null
        }
        if (!applyNativeFont(box, NATIVE_INPUT_FONT, true)) {
            AndroidLog.e(TAG, "search box: m1.A3(45) failed")
            return null
        }
        val padSide = nativeScaledPx("g0", NATIVE_INPUT_PAD_SIDE_G0) ?: run {
            AndroidLog.e(TAG, "search box: g0(40) pad failed")
            return null
        }
        val padTb = nativeScaledPx("e0", NATIVE_INPUT_PAD_TB_E0) ?: run {
            AndroidLog.e(TAG, "search box: e0(40) pad failed")
            return null
        }
        box.setPadding(padSide, padTb, padSide, padTb)
        box.minHeight = boxH
        box.setHeight(boxH)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val lineH = nativeScaledPx("e0", NATIVE_INPUT_LINE_H_E0) ?: run {
                AndroidLog.e(TAG, "search box: e0(63) lineHeight failed")
                return null
            }
            box.setLineHeight(lineH)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            if (cursorRes != null && cursorRes != 0) {
                runCatching { box.setTextCursorDrawable(cursorRes) }
            } else {
                AndroidLog.e(TAG, "search box: green cursor missing, skipped")
            }
        }
        synchronized(trackedBoxes) { trackedBoxes.add(box) }
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                try {
                    val raw = s?.toString().orEmpty()
                    // 换行键兜底：该键盘回车送的是 commitText("\n") 而非 editor
                    // action（日志里 editor action 从未触发）。见换行就去掉并
                    // 当回车跳回剪贴板；框保持单行干净，退格逐字删除恢复正常。
                    if (raw.contains('\n') || raw.contains('\r')) {
                        val clean = raw.replace("\n", "").replace("\r", "")
                        box.post {
                            try {
                                box.setText(clean)
                                box.setSelection(clean.length.coerceAtMost(box.text?.length ?: 0))
                                var node: View? = box
                                while (node != null && node.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                                    node = node.parent as? View
                                }
                                if (node != null) {
                                    AndroidLog.i(TAG, "strip newline-as-enter: jumping back")
                                    jumpBackToClipboard(node)
                                }
                            } catch (t: Throwable) {
                                AndroidLog.e(TAG, "newline jump failed: ${t.message}")
                            }
                        }
                        pendingKeyword = clean
                        keywordListenerImpl?.invoke(clean)
                        updateClearVisibility(box)
                        return
                    }
                    pendingKeyword = raw
                    keywordListenerImpl?.invoke(pendingKeyword)
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "keyword listener failed: ${t.message}")
                }
                updateClearVisibility(box)
            }
        })
        // S5e-A5：点⌫触摸经 StripInputConnection.sendKeyEvent(DEL) 直删 1 字
        // （实证不走 deleteSurroundingText；wrapper 消费禁双发）。
        // FAIL③：回车路由三路放行（IC sendKeyEvent/performEditorAction +
        // OnEditorActionListener + OnKeyListener），聚焦+可见双门卫下 jump；
        // NO_SUGGESTIONS 防联想劫持，不吞 ENTER。
        box.setOnEditorActionListener { _, _, _ ->
            // 键盘页回车 = ✓跳回剪贴板看结果。
            try {
                AndroidLog.i(TAG, "strip editor action: jumping back")
                var node: View? = box
                while (node != null && node.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                    node = node.parent as? View
                }
                if (node != null) jumpBackToClipboard(node)
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip search action failed: ${t.message}")
            }
            true
        }
        box.setOnKeyListener { _, keyCode, event ->
            try {
                if ((keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                        keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                    event.action == android.view.KeyEvent.ACTION_DOWN
                ) {
                    // 聚焦+可见双门卫下放行（与searchInputConnection同判据）。
                    if (box.visibility == View.VISIBLE && box.hasFocus() && box.parent != null) {
                        var chainOk = true
                        var p = box.parent
                        while (p is View) {
                            if ((p as View).visibility != View.VISIBLE) { chainOk = false; break }
                            p = (p as View).parent
                        }
                        if (chainOk) {
                            AndroidLog.i(TAG, "strip enter key: jumping back via onKey")
                            var node: View? = box
                            while (node != null && node.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                                node = node.parent as? View
                            }
                            if (node != null) jumpBackToClipboard(node)
                            true
                        } else false
                    } else false
                } else false
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip onKey enter failed: ${t.message}")
                false
            }
        }
        return box
    }

    private fun createHostBox(context: android.content.Context): EditText? {
        return try {
            val cl = hostClassLoader ?: return null
            val cls = Class.forName(
                "com.tencent.wetype.plugin.hld.view.imeedittext.ImeEditText", false, cl
            )
            val ctor = cls.getDeclaredConstructor(android.content.Context::class.java)
            ctor.isAccessible = true
            ctor.newInstance(context) as? EditText
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "host ImeEditText missing: ${t.message}")
            null
        }
    }

    /**
     * X 清除按钮（全原生，零仿制）：翻译条本身无 X，此处取宿主关闭图标
     *（icon_close_black→tips→navigation→toolbar→clear 链），无命中返 null
     *（整条 fail-closed，禁系统图标兜底）。点击清框 + clearSearch 恢复（语义不动）。
     */
    private fun createClearButton(context: android.content.Context, box: EditText): ImageView? {
        val hostIcon = resolveHostIconRes(*NATIVE_CLEAR_ICON_NAMES) ?: run {
            AndroidLog.e(TAG, "clear button: host close icon missing")
            return null
        }
        return ImageView(context).apply {
            tag = TAG_SEARCH_CLEAR
            contentDescription = "清除搜索"
            setImageResource(hostIcon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val res = resources
            val pad = dpToPx(res, CLEAR_ICON_PADDING_DP)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener {
                try {
                    box.setText("")
                    clearSearch()
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "clear button failed: ${t.message}")
                }
            }
            val size = dpToPx(res, CLEAR_BOX_DP)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun alignSearchButton(btn: View, bar: ViewGroup, backBtn: View, backBtnId: Int) {
        try {
            bar.post {
                try {
                    val bw = backBtn.width
                    val bh = backBtn.height
                    if (bw <= 0 || bh <= 0) {
                        AndroidLog.e(TAG, "back button not laid out ($bw x $bh), align deferred")
                        return@post
                    }
                    val lp = btn.layoutParams ?: ViewGroup.MarginLayoutParams(bw, bh)
                    lp.width = bw
                    lp.height = bh
                    btn.layoutParams = lp
                    applyCircularShape(btn, minOf(bw, bh))
                    (btn as? ImageView)?.let { iv ->
                        val pad = (minOf(bw, bh) * 0.15f).roundToInt()
                        iv.setPadding(pad, pad, pad, pad)
                    }
                    val gap = dpToPx(bar.resources, BTN_GAP_DP)
                    val targetLeft = backBtn.left + bw + gap
                    val targetTop = backBtn.top
                    btn.translationX = (targetLeft - btn.left).toFloat()
                    btn.translationY = (targetTop - btn.top).toFloat()
                    val loc = IntArray(2)
                    runCatching { btn.getLocationOnScreen(loc) }
                    AndroidLog.i(TAG, "search button placed screen=${loc[0]},${loc[1]} tx=${btn.translationX} ty=${btn.translationY}")
                    resolveNativeRoundBackground(backBtn, btn.resources)?.let { adopted ->
                        btn.background = adopted
                        adopted.state = btn.drawableState
                        adopted.jumpToCurrentState()
                    }
                    adoptNativeRoundBackground(btn, backBtn)
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "align search button failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "align search button failed: ${t.message}")
        }
    }

    /**
     * 原生圆钮背景：复用宿主 back_btn 的皮肤圆钮背景（S15 V0 皮肤期下发）。
     * 只认 back_btn 一个来源，取不到返回 null（禁自绘/系统兜底）。
     */
    private fun resolveNativeRoundBackground(
        backBtn: View,
        resources: android.content.res.Resources
    ): android.graphics.drawable.Drawable? {
        val bg = backBtn.background ?: return null
        return runCatching { bg.constantState?.newDrawable(resources)?.mutate() }.getOrNull()
    }

    /** 宿主皮肤在挂载后才给 back_btn 下发圆钮背景：短时重试补齐，拿到即停。 */
    private fun adoptNativeRoundBackground(btn: View, backBtn: View, attempt: Int = 0) {
        if (btn.background != null || attempt >= 12 || btn.parent == null) return
        val target = btn
        val source = backBtn
        btn.postDelayed({
            if (target.background == null && target.parent != null) {
                resolveNativeRoundBackground(source, target.resources)?.let { adopted ->
                    target.background = adopted
                    AndroidLog.i(TAG, "search button background adopted (attempt=$attempt)")
                }
            }
            adoptNativeRoundBackground(target, source, attempt + 1)
        }, 120L)
    }

    private fun findHostView(keyboardObj: Any): View? {
        var clazz: Class<*>? = keyboardObj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                try {
                    if (!View::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    val view = field.get(keyboardObj) as? View
                    if (view != null) return view
                } catch (_: Throwable) {
                    continue
                }
            }
            clazz = clazz.superclass
        }
        var search: Class<*>? = keyboardObj.javaClass
        while (search != null && search != Any::class.java) {
            for (method in search.declaredMethods) {
                try {
                    if (method.parameterTypes.isNotEmpty()) continue
                    if (!View::class.java.isAssignableFrom(method.returnType)) continue
                    method.isAccessible = true
                    val view = method.invoke(keyboardObj) as? View
                    if (view != null) return view
                } catch (_: Throwable) {
                    continue
                }
            }
            search = search.superclass
        }
        return null
    }

    private data class ResolvedIds(
        val backBtnId: Int?,
        val backBtnIvId: Int?,
        val clipListId: Int?,
        val moreBtnId: Int? = null
    )

    private fun findClipboardListView(root: ViewGroup, clipListId: Int?): View? {
        if (clipListId == null) return null
        return try {
            root.findViewById(clipListId)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findClipboardPageContainer(listView: View, backBtnId: Int): ViewGroup? {
        var node = listView.parent
        var depth = 0
        while (node is ViewGroup && depth < 10) {
            if (node.findViewById<View>(backBtnId) != null) return node
            node = node.parent
            depth++
        }
        return null
    }

    private fun idClassLoaders(anchor: View, hostLoader: ClassLoader): List<ClassLoader?> {
        val ctx = anchor.context
        return listOf(hostLoader, ctx?.classLoader, ctx?.applicationContext?.classLoader)
    }

    private fun findIdClass(anchor: View, hostLoader: ClassLoader): Class<*>? {
        for (cl in idClassLoaders(anchor, hostLoader)) {
            if (cl == null) continue
            runCatching { Class.forName(WETYPE_ID_CLASS, false, cl) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun removeResidualViews(root: ViewGroup) {
        try {
            root.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER)?.let { container ->
                removeStripCard(container)
            }
            root.findViewWithTag<View>(TAG_SEARCH_BOX)?.let { box ->
                (box.parent as? ViewGroup)?.removeView(box)
            }
            root.findViewWithTag<View>(TAG_SEARCH_BUTTON)?.let { btn ->
                (btn.parent as? ViewGroup)?.removeView(btn)
            }
            clearSearchOnMain()
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "remove residual search UI failed: ${t.message}")
        }
    }

    private fun resolveIds(anchor: View, hostLoader: ClassLoader): ResolvedIds {
        val sClass = findIdClass(anchor, hostLoader)
        if (sClass == null) {
            AndroidLog.e(TAG, "resolve clipboard page IDs failed: $WETYPE_ID_CLASS")
            return ResolvedIds(null, null, null)
        }
        return ResolvedIds(
            backBtnId = runCatching { sClass.getField("back_btn").getInt(null) }.getOrNull(),
            backBtnIvId = runCatching { sClass.getField("back_btn_iv").getInt(null) }.getOrNull(),
            clipListId = runCatching { sClass.getField("t15_clipboard_list").getInt(null) }.getOrNull(),
            moreBtnId = runCatching { sClass.getField("clipboard_more_btn").getInt(null) }.getOrNull()
        ).also {
            if (it.backBtnId == null && it.clipListId == null) {
                AndroidLog.e(TAG, "resolve clipboard page IDs failed: fields missing in $WETYPE_ID_CLASS")
            }
        }
    }

    /** 宿主搜索图标（首个 icon/magnif/loupe 形 search 字段），无命中返 null（禁系统图标兜底）。 */
    private fun resolveSearchIconRes(anchor: View, hostLoader: ClassLoader): Int? {
        for (cl in idClassLoaders(anchor, hostLoader)) {
            if (cl == null) continue
            try {
                val rClass = Class.forName(WETYPE_DRAWABLE_CLASS, false, cl)
                val fields = rClass.declaredFields
                var firstHost: Int? = null
                for (field in fields) {
                    val name = field.name.lowercase()
                    if (!name.contains("search")) continue
                    val resId = runCatching { field.getInt(null) }.getOrNull() ?: continue
                    if (firstHost == null) firstHost = resId
                    if (name.contains("icon") || name.contains("magnif") || name.contains("loupe")) {
                        AndroidLog.i(TAG, "search icon=$name")
                        return resId
                    }
                }
                if (firstHost != null) {
                    AndroidLog.i(TAG, "search icon=first search match (host pick)")
                    return firstHost
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "resolve search icon failed: ${t.message}")
            }
        }
        AndroidLog.e(TAG, "search icon: no host search drawable, dropped")
        return null
    }

    /**
     * 宿主搜索图标是固定黑矢量（actionbar_icon_dark_search），暗色下必须自行上色：
     * 优先复制同栏返回图标的原生 tint/colorFilter，取不到再按系统深浅色回退黑白。
     */
    private fun applyHostIconTint(icon: ImageView, page: View, backBtnIvId: Int?) {
        icon.imageTintList = null
        icon.clearColorFilter()
        try {
            val reference = backBtnIvId?.let { page.findViewById<View>(it) } as? ImageView
            if (reference != null) {
                reference.imageTintList?.let { tint ->
                    icon.imageTintList = tint
                    AndroidLog.i(TAG, "search icon tint copied from back_btn_iv")
                    return
                }
                reference.colorFilter?.let { filter ->
                    @Suppress("DEPRECATION")
                    icon.setColorFilter(filter)
                    AndroidLog.i(TAG, "search icon colorFilter copied from back_btn_iv")
                    return
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "search icon tint copy failed: ${t.message}")
        }
        applyThemeIconTint(icon)
    }

    private fun applyThemeIconTint(icon: ImageView) {
        icon.setColorFilter(resolveThemeIconColor(icon), PorterDuff.Mode.SRC_IN)
    }

    private fun resolveThemeIconColor(view: View): Int {
        val night = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.WHITE else Color.BLACK
    }

    private fun dpToPx(resources: android.content.res.Resources, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).roundToInt()
    }
}
