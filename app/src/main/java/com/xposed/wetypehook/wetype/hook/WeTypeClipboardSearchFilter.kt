package com.xposed.wetypehook.wetype.hook

import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.Log as AndroidLog
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.xposed.wetypehook.wetype.clipboard.ClipboardSearchEngine
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.hookAfter
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Slice 5：剪贴板搜索过滤渲染（防抖 + 后台搜 + 前50分页 + 高亮）。
 *
 * 挂点（S1 证据）：
 * - 核心过滤点 `ImeClipboardScrollView.setList(List<C>)`（证据 19 行）：只做全量快照，
 *   实际过滤在后台线程跑完后经原生链路 `setList(过滤后)` + `getListAdapter().y()` 回放
 *   （`y()` 刷新点见证据 23 行，`S15.a1()` 统一刷新见 13 行）。禁绕过 Adapter 自建列表。
 * - 高亮次选点 `Adapter v.onBindViewHolder`（证据 22 行）：后处理加 [ForegroundColorSpan]。
 * - 文本判定 `C.getType()==0` + `getContent()` 为搜索唯一范围（证据 24/25 行）；
 *   图片/文件/远程条目（type != 0）原样保留不参搜。
 * - S4 扩展点：只消费 `WeTypeClipboardSearchUi.setKeywordListener`（`clearSearch`
 *   由 Ui 侧清空搜索框文本并透传空关键词，本片经监听空关键词执行恢复）。
 * - S2 引擎冻结 API：只引用 `search(contents, keyword, limit=50)`。
 */
internal object WeTypeClipboardSearchFilter {

    private const val TAG = "WeTypeClipboardSearch"
    private const val SCROLLVIEW_CLASS =
        "com.tencent.wetype.plugin.hld.clipboard.ImeClipboardScrollView"
    private const val ADAPTER_CLASS = "com.tencent.wetype.plugin.hld.clipboard.v"

    private const val DEBOUNCE_MS = 300L
    private const val PAGE_LIMIT = 50

    // 高亮色来源：README 主题色 #FB7299（模块品牌强调色）。
    // 用前景色（文字色）而非底色，避免与深浅主题底色冲突；清空时移除同色 span 恢复原样。
    private const val HIGHLIGHT_COLOR = 0xFFFB7299.toInt()

    @Volatile
    private var hooked = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WeTypeClipboardSearch").apply { isDaemon = true }
    }

    // 版本号 guards：新关键词/新数据到达即 +1，防后台乱序回写。
    private val version = AtomicLong(0L)

    // 原生 setList 回放时的重入 guard：我们自己调 setList 时跳过快照与重排，避免循环。
    private val applyingFilter = AtomicBoolean(false)

    @Volatile
    private var currentKeyword = ""

    /** 供图片注入器判断是否正在回放过滤结果（避免把图片塞回搜索结果）。 */
    internal fun isReplaying(): Boolean = applyingFilter.get()

    /** 供图片注入器判断当前搜索态。 */
    internal fun currentKeywordValue(): String = currentKeyword

    private val lock = Any()
    private val fullItems: MutableList<Any> = ArrayList()
    private val scrollViews: MutableSet<Any> =
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val searchFoldStates = WeakHashMap<Any, Int>()
    private val pendingResultAlphas = WeakHashMap<View, Float>()

    /** Keep layout/visibility intact for native navigation while suppressing unfiltered frames. */
    private fun hidePendingResults(targets: List<Any>) {
        targets.filterIsInstance<View>().forEach { view ->
            if (!pendingResultAlphas.containsKey(view)) pendingResultAlphas[view] = view.alpha
            view.alpha = 0f
        }
    }

    private fun revealPendingResults(targets: List<Any>) {
        targets.filterIsInstance<View>().forEach { view ->
            pendingResultAlphas.remove(view)?.let { alpha -> view.alpha = alpha }
        }
    }

    private fun releaseFailedPresentation(v: Long) {
        mainHandler.post {
            if (v == version.get()) revealPendingResults(pendingResultAlphas.keys.toList())
        }
    }

    // C.getType()/getContent() 反射缓存（同类加载器下同类，main/后台线程只读调用）。
    @Volatile
    private var getTypeMethod: Method? = null
    @Volatile
    private var getContentMethod: Method? = null

    private val debounceTask = Runnable {
        try {
            val v = version.get()
            val kw = currentKeyword
            val snapshot: List<Any>
            val targets: List<Any>
            synchronized(lock) {
                snapshot = ArrayList(fullItems)
                targets = ArrayList(scrollViews)
            }
            if (targets.isEmpty()) return@Runnable
            // S6：快照为空时仍走后台/回放，使空态文案能覆盖原生空标题；
            // 后台对空快照直接产出空列表，开销可忽略。
            executor.submit { runFilterInBackground(v, kw, snapshot, targets) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "debounce dispatch failed: ${t.message}")
            releaseFailedPresentation(version.get())
        }
    }

    fun install(classLoader: ClassLoader) {
        if (hooked) return
        try {
            hookSetList(classLoader)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "hook setList failed: ${t.message}")
        }
        try {
            hookBind(classLoader)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "hook onBindViewHolder failed: ${t.message}")
        }
        try {
            // S4 扩展点消费：关键词变化只做防抖调度，实际搜索在后台线程。
            WeTypeClipboardSearchUi.setKeywordListener { kw -> onKeyword(kw) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "register keyword listener failed: ${t.message}")
        }
        hooked = true
        AndroidLog.i(TAG, "search filter installed (debounce=${DEBOUNCE_MS}ms, limit=$PAGE_LIMIT)")
    }

    // ---- 关键词入口（主线程防抖） ----

    private fun onKeyword(raw: String?) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { onKeyword(raw) }
                return
            }
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) {
                // 开关关闭：取消 pending 并抬升版本作废在途后台任务，不碰列表；
                // S6：空态文案一并恢复原生。
                currentKeyword = ""
                version.incrementAndGet()
                mainHandler.removeCallbacks(debounceTask)
                try {
                    val targets: List<Any> = synchronized(lock) { ArrayList(scrollViews) }
                    revealPendingResults(pendingResultAlphas.keys.toList())
                    for (target in targets) {
                        updateSearchFoldState(target, false)
                        invokeAdapterFullRebind(target)
                    }
                    WeTypeClipboardSearchEmpty.onSwitchOff(targets)
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "empty restore on switch-off failed: ${t.message}")
                }
                return
            }
            val keyword = raw.orEmpty()
            val unchanged = keyword == currentKeyword
            currentKeyword = keyword
            if (currentKeyword.isNotBlank()) {
                hidePendingResults(synchronized(lock) { ArrayList(scrollViews) })
            }
            scheduleLocked(if (unchanged || keyword.isEmpty()) 0L else DEBOUNCE_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "onKeyword failed: ${t.message}")
        }
    }

    private fun onNativeSetList(scrollView: Any, listArg: Any?) {
        try {
            if (applyingFilter.get()) return
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) return
            val list = listArg as? List<*> ?: return
            val runSchedule: Boolean
            synchronized(lock) {
                scrollViews.add(scrollView)
                // 空关键词时仍刷新全量快照（恢复/首挂载路径），不额外调度。
                fullItems.clear()
                for (item in list) {
                    if (item != null) fullItems.add(item)
                }
                // 宿主列表默认排除 type==1 图片；合入模块注入缓存，保证搜索恢复时可见。
                WeTypeClipboardImageList.appendCachedUnique(fullItems)
                runSchedule = currentKeyword.isNotEmpty()
            }
            WeTypeClipboardImageList.onNativeSetList(scrollView, list.filterNotNull())
            // 主线程调度；hookAfter 大概率已在主线程，非主则抛回主线程。
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (runSchedule) {
                    hidePendingResults(listOf(scrollView))
                    scheduleLocked(0L)
                }
            } else {
                mainHandler.post {
                    try {
                        if (runSchedule &&
                            WeTypeSettings.isClipboardSearchEnabledXposed() &&
                            !applyingFilter.get()
                        ) {
                            hidePendingResults(listOf(scrollView))
                            scheduleLocked(0L)
                        }
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "native setList reschedule failed: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "onNativeSetList failed: ${t.message}")
        }
    }

    private fun scheduleLocked(delayMillis: Long = DEBOUNCE_MS) {
        try {
            version.incrementAndGet()
            mainHandler.removeCallbacks(debounceTask)
            mainHandler.postDelayed(debounceTask, delayMillis)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "schedule filter failed: ${t.message}")
            releaseFailedPresentation(version.get())
        }
    }

    // ---- 后台搜索（禁碰 View） ----

    private fun runFilterInBackground(
        v: Long,
        keyword: String,
        snapshot: List<Any>,
        targets: List<Any>
    ) {
        try {
            val filtered: List<Any> = if (keyword.isEmpty()) {
                ArrayList(snapshot)
            } else {
                filterSnapshot(snapshot, keyword)
            }
            mainHandler.post { applyResultOnMain(v, keyword, targets, filtered) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "background filter failed: ${t.message}")
            releaseFailedPresentation(v)
        }
    }

    private fun filterSnapshot(snapshot: List<Any>, keyword: String): List<Any> {
        // 文本条目逐条抽 content 走引擎；非文本原样保留不参搜（S1 证据 B:988 语义）。
        // content 读不到的条目判不准→保留（宁可多留不断数据）。
        val textContents = ArrayList<String>()
        val textToOrig = ArrayList<Int>()
        val unknownContent = HashSet<Int>()
        for (i in snapshot.indices) {
            val item = snapshot[i]
            try {
                if (getItemType(item) != 0L) continue
                val content = getItemContent(item)
                if (content == null) {
                    unknownContent.add(i)
                    continue
                }
                textContents.add(content)
                textToOrig.add(i)
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "snapshot item read failed: ${t.message}")
            }
        }
        val matchedOrig = HashSet<Int>()
        try {
            val hits = ClipboardSearchEngine.search(textContents, keyword, PAGE_LIMIT)
            for (hit in hits) {
                val pos = hit.index
                if (pos in textToOrig.indices) matchedOrig.add(textToOrig[pos])
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "engine search failed: ${t.message}")
            return ArrayList(snapshot)
        }
        val out = ArrayList<Any>(snapshot.size.coerceAtMost(PAGE_LIMIT + 16))
        for (i in snapshot.indices) {
            val item = snapshot[i]
            try {
                val type = getItemType(item)
                if (type != 0L) {
                    // ADR-0002：搜索关键词非空时图片条目全部隐藏；其余非文本保持原样。
                    if (type != 1L) out.add(item)
                } else if (matchedOrig.contains(i) || unknownContent.contains(i)) {
                    out.add(item)
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "filter item failed: ${t.message}")
                out.add(item)
            }
        }
        return out
    }

    // ---- 主线程回放（原生链路刷新） ----

    private fun applyResultOnMain(v: Long, keyword: String, targets: List<Any>, filtered: List<Any>) {
        try {
            if (v != version.get()) return
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) return
            if (targets.isEmpty()) return
            for (scrollView in targets) {
                try {
                    applyingFilter.set(true)
                    invokeSetList(scrollView, filtered)
                    invokeAdapterRefresh(scrollView)
                    updateSearchFoldState(scrollView, keyword.isNotBlank())
                    // S8：宿主 y() 只重算折叠分区并 notifyItemChanged(0)，可视区其余行
                    // 保持旧绑定（滑出屏幕回收后才重绑，表现为第 2/3 行短暂显示旧内容）。
                    // 这里补一次全量 notifyDataSetChanged 强制重绑所有行。
                    invokeAdapterFullRebind(scrollView)
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "apply filtered list failed: ${t.message}")
                } finally {
                    applyingFilter.set(false)
                    revealPendingResults(listOf(scrollView))
                }
            }
            // S6：空态（无结果文案/恢复原生）复用原生空视图链路，主线程 UI。
            try {
                WeTypeClipboardSearchEmpty.onFilterResult(targets, keyword, filtered.isEmpty())
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "empty state dispatch failed: ${t.message}")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "applyResult failed: ${t.message}")
        }
    }

    /** Verified host v: h=1 selects the collapsed list k; z(0) selects full list b. */
    private fun updateSearchFoldState(scrollView: Any, searching: Boolean) {
        val adapter = resolveAdapter(scrollView) ?: return
        if (adapter.javaClass.name != ADAPTER_CLASS) return
        runCatching {
            val field = adapter.javaClass.getDeclaredField("h").apply { isAccessible = true }
            if (field.type != Int::class.javaPrimitiveType) return
            val setter = adapter.javaClass.getDeclaredMethod("z", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            if (setter.returnType != Void.TYPE) return
            if (searching) {
                if (!searchFoldStates.containsKey(adapter)) searchFoldStates[adapter] = field.getInt(adapter)
                setter.invoke(adapter, 0)
            } else {
                searchFoldStates.remove(adapter)?.let { setter.invoke(adapter, it) }
            }
        }.onFailure { AndroidLog.e(TAG, "search fold state update failed: ${it.message}") }
    }

    private fun invokeSetList(scrollView: Any, filtered: List<Any>) {
        try {
            val method = scrollView.javaClass.declaredMethods.firstOrNull { m ->
                m.name == "setList" &&
                    m.parameterTypes.size == 1 &&
                    List::class.java.isAssignableFrom(m.parameterTypes[0])
            } ?: run {
                AndroidLog.e(TAG, "setList(List) not found on ${scrollView.javaClass.name}")
                return
            }
            method.isAccessible = true
            // 原生 setList 期望 ArrayList<C>；传 ArrayList 保持与原生一致。
            method.invoke(scrollView, ArrayList(filtered))
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "invoke setList failed: ${t.message}")
        }
    }

    private fun invokeAdapterRefresh(scrollView: Any) {
        try {
            val adapter = resolveAdapter(scrollView) ?: return
            val refresh = adapter.javaClass.declaredMethods.firstOrNull { m ->
                m.name == "y" && m.parameterTypes.isEmpty()
            } ?: run {
                AndroidLog.e(TAG, "adapter y() not found on ${adapter.javaClass.name}")
                return
            }
            refresh.isAccessible = true
            refresh.invoke(adapter)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "adapter refresh y() failed: ${t.message}")
        }
    }

    /** 全量重绑：宿主 `y()` 不覆盖可视区旧行，需显式 `notifyDataSetChanged`。 */
    private fun invokeAdapterFullRebind(scrollView: Any) {
        try {
            val adapter = resolveAdapter(scrollView) ?: return
            var clazz: Class<*>? = adapter.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.name == "notifyDataSetChanged" && method.parameterTypes.isEmpty()) {
                        method.isAccessible = true
                        method.invoke(adapter)
                        return
                    }
                }
                clazz = clazz.superclass
            }
            AndroidLog.e(TAG, "notifyDataSetChanged not found on ${adapter.javaClass.name}")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "adapter full rebind failed: ${t.message}")
        }
    }

    private fun resolveAdapter(scrollView: Any): Any? = try {
        val getter = scrollView.javaClass.declaredMethods.firstOrNull { m ->
            m.name == "getListAdapter" && m.parameterTypes.isEmpty()
        }
        if (getter == null) {
            AndroidLog.e(TAG, "getListAdapter() not found")
            null
        } else {
            getter.isAccessible = true
            getter.invoke(scrollView) ?: run {
                AndroidLog.e(TAG, "getListAdapter() returned null")
                null
            }
        }
    } catch (t: Throwable) {
        AndroidLog.e(TAG, "resolve adapter failed: ${t.message}")
        null
    }

    // ---- 高亮（onBindViewHolder 后处理，主线程碰 View） ----

    private fun hookSetList(classLoader: ClassLoader) {
        val scrollClass = Class.forName(SCROLLVIEW_CLASS, false, classLoader)
        var count = 0
        for (method in scrollClass.declaredMethods) {
            if (method.name != "setList") continue
            if (method.parameterTypes.size != 1) continue
            if (!List::class.java.isAssignableFrom(method.parameterTypes[0])) continue
            try {
                method.isAccessible = true
                method.hookAfter { param ->
                    try {
                        val arg = param.args.getOrNull(0)
                        onNativeSetList(param.thisObject, arg)
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "setList hook dispatch failed: ${t.message}")
                    }
                }
                count++
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "hook setList failed: ${t.message}")
            }
        }
        AndroidLog.i(TAG, "hooked setList overloads: $count")
    }

    private fun hookBind(classLoader: ClassLoader) {
        val adapterClass = runCatching {
            Class.forName(ADAPTER_CLASS, false, classLoader)
        }.getOrNull() ?: run {
            AndroidLog.e(TAG, "clipboard adapter class not found, skip highlight hook")
            return
        }
        var count = 0
        for (method in adapterClass.declaredMethods) {
            if (method.name != "onBindViewHolder") continue
            if (method.parameterTypes.size != 2) continue
            try {
                method.isAccessible = true
                method.hookAfter { param ->
                    try {
                        val holder = param.args.getOrNull(0) ?: return@hookAfter
                        // 图片条目渲染与搜索开关无关；搜索高亮仍受开关控制。
                        WeTypeClipboardImageEntries.onBind(holder)
                        if (WeTypeSettings.isClipboardSearchEnabledXposed()) {
                            applyHighlightToHolder(holder, currentKeyword)
                        }
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "bind highlight dispatch failed: ${t.message}")
                    }
                }
                count++
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "hook onBindViewHolder failed: ${t.message}")
            }
        }
        AndroidLog.i(TAG, "hooked onBindViewHolder overloads: $count")
    }

    private fun applyHighlightToHolder(holder: Any, keyword: String) {
        try {
            val itemView = getHolderItemView(holder) ?: return
            forEachTextView(itemView, keyword)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "applyHighlight failed: ${t.message}")
        }
    }

    private fun getHolderItemView(holder: Any): View? {
        try {
            var clazz: Class<*>? = holder.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (field in clazz.declaredFields) {
                    try {
                        if (!View::class.java.isAssignableFrom(field.type)) continue
                        field.isAccessible = true
                        val view = field.get(holder) as? View
                        if (view != null) return view
                    } catch (_: Throwable) {
                        continue
                    }
                }
                clazz = clazz.superclass
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "get holder itemView failed: ${t.message}")
        }
        return null
    }

    private fun forEachTextView(view: View, keyword: String) {
        try {
            if (view is TextView) {
                highlightTextView(view, keyword)
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    try {
                        forEachTextView(view.getChildAt(i), keyword)
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "traverse child failed: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "forEachTextView failed: ${t.message}")
        }
    }

    private fun highlightTextView(tv: TextView, keyword: String) {
        try {
            // 先清除上一轮同色高亮，恢复原样后再按当前关键词重打。
            clearOurSpans(tv)
            if (keyword.isEmpty()) return
            val text = tv.text?.toString().orEmpty()
            if (text.isEmpty()) return
            val ranges = try {
                val hits = ClipboardSearchEngine.search(listOf(text), keyword, 1)
                if (hits.isEmpty()) emptyList() else hits[0].ranges
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "highlight search failed: ${t.message}")
                return
            }
            if (ranges.isEmpty()) return
            val spannable = SpannableString(tv.text)
            var firstStart = -1
            for (range in ranges) {
                try {
                    val start = range.first.coerceIn(0, spannable.length)
                    val endExclusive = (range.last + 1).coerceIn(0, spannable.length)
                    if (start >= endExclusive) continue
                    spannable.setSpan(
                        ForegroundColorSpan(HIGHLIGHT_COLOR),
                        start,
                        endExclusive,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    if (firstStart < 0) firstStart = start
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "apply span range failed: ${t.message}")
                }
            }
            tv.setText(spannable, TextView.BufferType.SPANNABLE)
            if (firstStart >= 0) scrollContentToHighlight(tv, firstStart)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "highlightTextView failed: ${t.message}")
        }
    }

    /**
     * 命中片段在可视区之外时，把内容行所在的横向滚动容器滚到首个高亮处，
     * 让长文本的匹配结果直接落在这一行里（原生只会在绑定时复位到 0）。
     */
    private fun scrollContentToHighlight(tv: TextView, offset: Int) {
        try {
            val scroll = tv.parent as? HorizontalScrollView ?: return
            // 布局用的是 transformation 后的文本（SingleLineTransformationMethod 会把
            // \r/\n 换成 \uFEFF，且保持 1:1 长度），必须按变换后的内容与 layout.text 比较。
            val expectedText = runCatching {
                tv.transformationMethod?.getTransformation(tv.text, tv)
            }.getOrNull() ?: tv.text
            scrollToHighlightAttempt(tv, scroll, offset, expectedText, 0)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "scroll content to highlight failed: ${t.message}")
        }
    }

    private fun scrollToHighlightAttempt(
        tv: TextView,
        scroll: HorizontalScrollView,
        offset: Int,
        expectedText: CharSequence?,
        attempt: Int
    ) {
        if (attempt > 10) return
        try {
            tv.postDelayed({
                try {
                    val layout = tv.layout
                    if (layout != null && TextUtils.equals(layout.text, expectedText) &&
                        tv.width > 0 && scroll.width > 0
                    ) {
                        scrollViewToOffset(tv, scroll, layout, offset)
                    } else {
                        scrollToHighlightAttempt(tv, scroll, offset, expectedText, attempt + 1)
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "scroll content to highlight failed: ${t.message}")
                }
            }, 16L)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "schedule scroll to highlight failed: ${t.message}")
        }
    }

    private fun scrollViewToOffset(
        tv: TextView,
        scroll: HorizontalScrollView,
        layout: android.text.Layout,
        offset: Int
    ) {
        if (offset < 0 || offset >= layout.text.length) return
        val viewport = scroll.width - scroll.paddingLeft - scroll.paddingRight
        if (viewport <= 0) return
        val maxScroll = (tv.width + scroll.paddingLeft + scroll.paddingRight - scroll.width)
            .coerceAtLeast(0)
        if (maxScroll <= 0) return
        val x = tv.left + tv.compoundPaddingLeft + layout.getPrimaryHorizontal(offset)
        val lead = (tv.resources.displayMetrics.density * 12f).toInt().coerceAtMost(viewport / 3)
        val target = (x - lead).toInt().coerceIn(0, maxScroll)
        if (scroll.scrollX != target) {
            scroll.scrollTo(target, 0)
            AndroidLog.i(TAG, "scroll to highlight: offset=$offset x=${x.toInt()} target=$target max=$maxScroll")
        }
    }

    private fun clearOurSpans(tv: TextView) {
        try {
            val spanned = tv.text as? Spanned ?: return
            val spans = spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java)
            if (spans.isEmpty()) return
            // tv.text 可能是不可变 Spanned：转可变后再清，避免直接 remove 抛异常。
            val editable = SpannableString(spanned)
            var changed = false
            for (span in spans) {
                try {
                    if (span.foregroundColor != HIGHLIGHT_COLOR) continue
                    editable.removeSpan(span)
                    changed = true
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "remove span failed: ${t.message}")
                }
            }
            if (changed) tv.setText(editable, TextView.BufferType.SPANNABLE)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "clear spans failed: ${t.message}")
        }
    }

    // ---- C 条目反射读写（数据面，后台/主线程均可调，不碰 View） ----

    private fun getItemType(item: Any): Long {
        try {
            var m = getTypeMethod
            if (m == null || m.declaringClass != item.javaClass) {
                // S7o：装机版 C.getType 改名（NoSuchMethod 导致全保留、过滤无事
                // 发生）。jadx 354 批注 `renamed from: q` + 真机普查 q():long=0
                // 在文本条目上吻合 type==0，三重印证，直接绑 q。
                m = item.javaClass.declaredMethods.firstOrNull { c ->
                    c.name == "q" && c.parameterTypes.isEmpty() &&
                        (c.returnType == Long::class.javaPrimitiveType ||
                            c.returnType == Long::class.java ||
                            c.returnType == Int::class.javaPrimitiveType ||
                            c.returnType == Integer::class.java)
                }
                if (m == null) {
                    AndroidLog.e(TAG, "C.q() not found on ${item.javaClass.name}, keep item")
                    return 1L
                }
                m.isAccessible = true
                getTypeMethod = m
                AndroidLog.i(TAG, "C type accessor bound: q() on ${item.javaClass.name}")
            }
            val v = m.invoke(item)
            return when (v) {
                is Long -> v
                is Int -> v.toLong()
                is Number -> v.toLong()
                else -> 1L
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "getType failed: ${t.message}")
            // 读不到类型时按非文本保留，避免误删数据。
            return 1L
        }
    }

    private fun getItemContent(item: Any): String? {
        try {
            var m = getContentMethod
            if (m == null || m.declaringClass != item.javaClass) {
                // S7o：同上，content 绑 a()（普查 a() 即条目正文，三重印证）。
                // 绑不上返回 null，调用方保留条目。
                m = item.javaClass.declaredMethods.firstOrNull { c ->
                    c.name == "a" && c.parameterTypes.isEmpty() &&
                        c.returnType == String::class.java
                }
                if (m == null) {
                    AndroidLog.e(TAG, "C.a() not found on ${item.javaClass.name}, keep item")
                    return null
                }
                m.isAccessible = true
                getContentMethod = m
                AndroidLog.i(TAG, "C content accessor bound: a() on ${item.javaClass.name}")
            }
            return m.invoke(item) as? String
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "getContent failed: ${t.message}")
            return null
        }
    }
}
