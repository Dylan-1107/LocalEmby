package com.local.embylite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 视频信息页筛选页签。 */
private enum class InfoTab(val label: String) {
    ALL("全部"),
    DONE("已完成"),
    TODO("待处理"),
    FAILED("失败")
}

/** 识别/刮削范围三模式（对应 Service 的 MODE_*）。选中后按「开始」才执行。 */
private val RANGE_LABELS = listOf("增量刷新", "重试失败与待确认", "全量重建…")
private const val RANGE_DANGER = 2

/** 一行视频信息（弹幕 + 刮削两个管线的状态合并）。 */
private data class InfoRow(
    val item: Item,
    val local: String,
    val danmuLabel: String,
    val danmuDetail: String,
    val danmuCands: List<Pair<Int, String>>,
    val danmuBound: Boolean,
    val danmuFailed: Boolean,
    val scrapeLabel: String,
    val scrapeDetail: String,
    val scrapeCands: List<Pair<Int, String>>,
    val scrapeDone: Boolean,
    val scrapeFailed: Boolean,
    val scrapeLocked: Boolean,
    val rank: Int
) {
    val done get() = rank == RANK_DONE
    val failed get() = rank == RANK_FAILED
}

private const val RANK_DONE = 0
private const val RANK_FAILED = 1
private const val RANK_PENDING = 2

/**
 * 视频信息页：HyperLight 同款 MiuiX 设置风格（分组卡片 + 开关 + 锚定下拉 + 开始按钮），
 * 整页随列表一起滚动；单条目支持重新搜索 / 清除结果纠错。
 */
@Composable
internal fun VideoInfoScreen(p: Palette) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var danmuProgress by remember { mutableStateOf(EpisodeMatcher.progress) }
    var scrapeProgress by remember { mutableStateOf(VideoInfoService.progress) }
    var rows by remember { mutableStateOf(emptyList<InfoRow>()) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(InfoTab.ALL) }
    var danmuRange by remember { mutableIntStateOf(0) }
    var scrapeRange by remember { mutableIntStateOf(0) }
    var confirmTarget by remember { mutableStateOf<String?>(null) }
    var searchItem by remember { mutableStateOf<Item?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            danmuProgress = EpisodeMatcher.progress
            scrapeProgress = VideoInfoService.progress
            tick++
            delay(1000)
        }
    }

    fun reload() {
        scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching {
                    VideoLibrary.get(ctx, Settings.getFolderPath(ctx))
                        .map { toRow(ctx, it) }
                        .sortedWith(compareBy({ it.rank }, { it.item.name }))
                }.getOrDefault(emptyList())
            }
            rows = fresh
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    /* 批次运行中定期重载列表统计（节流 3s） */
    var lastReloadAt by remember { mutableStateOf(0L) }
    LaunchedEffect(danmuProgress, scrapeProgress, tick) {
        val now = System.currentTimeMillis()
        if (!loading && now - lastReloadAt > 3000) {
            lastReloadAt = now
            reload()
        }
    }

    val dBound = rows.count { it.danmuBound }
    val dTodo = rows.count { !it.danmuBound && !it.danmuFailed }
    val dFailed = rows.count { it.danmuFailed }
    val sDone = rows.count { it.scrapeDone }
    val sTodo = rows.count { !it.scrapeDone && !it.scrapeFailed }
    val sFailed = rows.count { it.scrapeFailed }
    val dActive = remember(tick) { EpisodeMatcher.isActive }
    val sActive = remember(tick) { VideoInfoService.isActive }
    val visible = remember(rows, tab) {
        when (tab) {
            InfoTab.ALL -> rows
            InfoTab.DONE -> rows.filter { it.done }
            InfoTab.TODO -> rows.filter { !it.done && !it.failed }
            InfoTab.FAILED -> rows.filter { it.failed }
        }
    }

    fun enqueueDanmu(mode: Int) {
        scope.launch {
            withContext(Dispatchers.IO) {
                EpisodeMatcher.enqueue(ctx, VideoLibrary.get(ctx, Settings.getFolderPath(ctx)), mode)
            }
            toast(ctx, if (mode == 2) "开始全量重新识别…" else "开始识别弹幕…")
        }
    }

    fun enqueueScrape(mode: Int) {
        scope.launch {
            withContext(Dispatchers.IO) {
                VideoInfoService.enqueue(ctx, VideoLibrary.get(ctx, Settings.getFolderPath(ctx)), mode)
            }
            toast(ctx, if (mode == 2) "开始全量重新刮削…" else "开始刮削元数据…")
        }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        /* ---- 整页滚动：头部与列表在同一个 LazyColumn 里 ---- */
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 130.dp)
        ) {
            item(key = "header") {
                Column(Modifier.statusBarsPadding()) {
                    Text(
                        "视频信息",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = p.text,
                        letterSpacing = (-0.5).sp,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (loading) "正在读取视频列表…"
                        else "弹幕 $danmuProgress · 刮削 $scrapeProgress",
                        fontSize = 12.sp,
                        color = p.sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(14.dp))
                    SectionLabel(p, "弹幕绑定")
                    SectionCard(p) {
                        StatRow(p, "绑定统计", dBound, dTodo, dFailed, "已绑定")
                        RowDivider(p)
                        DropdownRow(
                            p,
                            title = "识别范围",
                            summary = "选好范围后点下方开始",
                            items = RANGE_LABELS,
                            selectedIndex = danmuRange,
                            dangerIndex = RANGE_DANGER
                        ) { danmuRange = it }
                        if (dActive) {
                            RowDivider(p)
                            CompactProgress(p, EpisodeMatcher.doneCount, EpisodeMatcher.totalCount) {
                                EpisodeMatcher.cancel()
                                toast(ctx, "正在取消…")
                            }
                        }
                        StartRow(p, "开始识别") {
                            if (danmuRange == RANGE_DANGER) confirmTarget = "danmu"
                            else enqueueDanmu(danmuRange)
                        }
                    }

                    SectionLabel(p, "元数据刮削 · TMDB")
                    SectionCard(p) {
                        StatRow(p, "刮削统计", sDone, sTodo, sFailed, "已刮削")
                        RowDivider(p)
                        DropdownRow(
                            p,
                            title = "刮削范围",
                            summary = if (sTodo + sFailed > 0) "$sTodo 个待处理 · $sFailed 个失败" else "全部已处理",
                            items = RANGE_LABELS,
                            selectedIndex = scrapeRange,
                            dangerIndex = RANGE_DANGER
                        ) { scrapeRange = it }
                        if (sActive) {
                            RowDivider(p)
                            CompactProgress(p, VideoInfoService.doneCount, VideoInfoService.totalCount) {
                                VideoInfoService.cancel()
                                toast(ctx, "正在取消…")
                            }
                        }
                        StartRow(p, "开始刮削") {
                            if (scrapeRange == RANGE_DANGER) confirmTarget = "scrape"
                            else enqueueScrape(scrapeRange)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoTab.entries.forEach { entry ->
                            TabChip(entry.label, tab == entry, p) { tab = entry }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            if (rows.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (loading) "正在读取…"
                            else "还没有扫描到视频文件\n先在「设置 → 媒体目录」授权视频目录，或授予所有文件访问权限",
                            fontSize = 13.sp, color = p.sub, maxLines = 4
                        )
                    }
                }
            } else if (visible.isEmpty()) {
                item(key = "empty-filtered") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Text("当前筛选下没有条目", fontSize = 13.sp, color = p.sub)
                    }
                }
            } else {
                items(visible, key = { it.item.id }) { row ->
                    RowCard(
                        p, row,
                        onConfirmDanmu = { id ->
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    EpisodeMatcher.confirm(ctx, row.item, id)
                                }
                                toast(ctx, if (ok) "已确认弹幕绑定" else "绑定失败，请重新识别")
                                if (ok) reload()
                            }
                        },
                        onConfirmScrape = { id ->
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    VideoInfoService.pickCandidate(
                                        ctx, rows.map { it.item }, row.item, id
                                    )
                                }
                                toast(ctx, if (ok) "已选定，正在刮削详情…" else "确认失败，请重新刮削")
                                if (ok) reload()
                            }
                        },
                        onRescrape = { searchItem = row.item },
                        onClear = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    VideoInfoService.clearResult(ctx, row.item)
                                }
                                toast(ctx, if (ok) "已清除，恢复待刮削" else "清除失败")
                                if (ok) reload()
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        /* ---- 全量重建确认弹窗 ---- */
        confirmTarget?.let { target ->
            ConfirmDialog(
                p,
                title = "全量重建？",
                body = if (target == "danmu")
                    "将重新识别全部 ${rows.size} 个文件（已确认的绑定保留），预计耗时与数量成正比。"
                else
                    "将重新请求全部 ${rows.size} 个文件的 TMDB 元数据，预计 3~5 分钟；已确认的候选保留，其余结果覆盖。",
                confirmLabel = "重新全部",
                onDismiss = { confirmTarget = null }
            ) {
                confirmTarget = null
                if (target == "danmu") enqueueDanmu(2) else enqueueScrape(2)
            }
        }

        /* ---- 重新搜索弹窗（单条目纠错） ---- */
        searchItem?.let { item ->
            ManualSearchDialog(
                p, item,
                onDismiss = { searchItem = null },
                onPick = { id ->
                    val target = item
                    searchItem = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            VideoInfoService.pickCandidate(ctx, rows.map { it.item }, target, id)
                        }
                        toast(ctx, if (ok) "已选定，正在刮削详情…" else "确认失败，请重试")
                        if (ok) reload()
                    }
                }
            )
        }
    }
}

/* ==================== 头部组件（HyperLight 设置页风格） ==================== */

@Composable
private fun SectionLabel(p: Palette, text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = p.sub,
        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 7.dp)
    )
}

@Composable
private fun SectionCard(p: Palette, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.card, RoundedCornerShape(18.dp))
            .padding(vertical = 4.dp)
    ) {
        content()
    }
}

@Composable
private fun RowDivider(p: Palette) {
    Box(
        Modifier
            .padding(start = 16.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(p.divider)
    )
}

/** 统计行：标题 + 右侧三色计数（已处理 · 待处理 · 失败）。 */
@Composable
private fun StatRow(p: Palette, title: String, ok: Int, todo: Int, failed: Int, okLabel: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$ok", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Green)
            Text(okLabel, fontSize = 11.sp, color = p.sub)
            Text("·", fontSize = 14.sp, color = p.sub)
            Text("$todo", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Orange)
            Text("待处理", fontSize = 11.sp, color = p.sub)
            Text("·", fontSize = 14.sp, color = p.sub)
            Text("$failed", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Red)
            Text("失败", fontSize = 11.sp, color = p.sub)
        }
    }
}

/** 开关行（HyperOS 设置风格）。 */
@Composable
private fun SwitchRow(
    p: Palette,
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(RoundedCornerShape(0.dp)) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = p.text)
            Text(summary, fontSize = 11.sp, color = p.sub, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(10.dp))
        Toggle(p, checked) { onChange(it) }
    }
}

@Composable
private fun Toggle(p: Palette, checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .width(48.dp)
            .height(28.dp)
            .background(if (checked) Blue else p.divider, RoundedCornerShape(50))
            .pressable(RoundedCornerShape(50)) { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(24.dp)
                .background(Color.White, CircleShape)
        )
    }
}

/**
 * 下拉行：行尾显示当前选中值，点击在行旁锚定弹出选项浮层（带缩放+淡入动画）。
 * 选择只更新值，不触发执行——执行由卡片底部的「开始」按钮负责。
 */
@Composable
private fun DropdownRow(
    p: Palette,
    title: String,
    summary: String,
    items: List<String>,
    selectedIndex: Int,
    dangerIndex: Int,
    onSelect: (Int) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(RoundedCornerShape(0.dp)) { open = !open }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, color = p.text)
                Text(summary, fontSize = 11.sp, color = p.sub, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                items[selectedIndex],
                fontSize = 14.sp,
                color = if (open) Blue else p.sub
            )
            Spacer(Modifier.width(5.dp))
            Text("⌄", fontSize = 13.sp, color = p.sub)
        }
        if (open) {
            Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(0, 12),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true)
            ) {
                val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = appear,
                    enter = fadeIn(tween(130)) + scaleIn(
                        initialScale = 0.85f,
                        animationSpec = tween(190),
                        transformOrigin = TransformOrigin(1f, 0f)
                    ),
                    exit = fadeOut(tween(110)) + scaleOut(targetScale = 0.94f, animationSpec = tween(140))
                ) {
                    Column(
                        Modifier
                            .width(210.dp)
                            .background(p.card, RoundedCornerShape(16.dp))
                            .border(0.5.dp, p.divider, RoundedCornerShape(16.dp))
                            .padding(vertical = 6.dp)
                    ) {
                        items.forEachIndexed { i, label ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .pressable(RoundedCornerShape(0.dp)) {
                                        open = false
                                        if (i != selectedIndex) onSelect(i)
                                    }
                                    .padding(horizontal = 15.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    label,
                                    fontSize = 14.sp,
                                    fontWeight = if (i == selectedIndex) FontWeight.Medium else FontWeight.Normal,
                                    color = when {
                                        i == selectedIndex -> Blue
                                        i == dangerIndex -> Red
                                        else -> p.text
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                if (i == selectedIndex) Text("✓", fontSize = 14.sp, color = Blue)
                            }
                            if (i < items.lastIndex) {
                                Box(
                                    Modifier
                                        .padding(start = 15.dp)
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(p.divider)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 紧凑进度：标题 + 计数 + 取消 + 4dp 细进度条（不再占一整块）。 */
@Composable
private fun CompactProgress(p: Palette, done: Int, total: Int, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("进行中", fontSize = 12.sp, color = p.sub, modifier = Modifier.weight(1f))
            Text("$done/$total", fontSize = 12.sp, color = p.sub)
            Spacer(Modifier.width(10.dp))
            Text(
                "取消", fontSize = 12.sp, color = Blue,
                modifier = Modifier.pressable(RoundedCornerShape(6.dp), onCancel)
            )
        }
        Spacer(Modifier.height(7.dp))
        val frac = if (total <= 0) 0.02f else (done.toFloat() / total.toFloat()).coerceIn(0.02f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(p.cardAlt, RoundedCornerShape(50))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(4.dp)
                    .background(Blue, RoundedCornerShape(50))
            )
        }
    }
}

/** 开始按钮行：右对齐的蓝色小按钮，选中范围后按它才执行。 */
@Composable
private fun StartRow(p: Palette, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .background(Blue, RoundedCornerShape(12.dp))
                .pressable(RoundedCornerShape(12.dp), onClick)
                .padding(horizontal = 24.dp, vertical = 9.dp)
        ) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
    }
}

/* ==================== 弹窗（确认 / 重新搜索） ==================== */

@Composable
private fun ConfirmDialog(
    p: Palette,
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .pressable(RoundedCornerShape(0.dp), onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(272.dp)
                .background(p.card, RoundedCornerShape(20.dp))
                .padding(18.dp)
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.text)
            Spacer(Modifier.height(8.dp))
            Text(body, fontSize = 13.sp, color = p.sub)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .background(p.cardAlt, RoundedCornerShape(12.dp))
                        .pressable(RoundedCornerShape(12.dp), onDismiss)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("取消", fontSize = 13.sp, color = p.text)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .background(Red.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                        .pressable(RoundedCornerShape(12.dp), onConfirm)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(confirmLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Red)
                }
            }
        }
    }
}

/** 重新搜索弹窗：可改关键词 → 候选列表 → 用这个（单条目纠错）。 */
@Composable
private fun ManualSearchDialog(
    p: Palette,
    item: Item,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember(item.id) { mutableStateOf(defaultQuery(item)) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<JSONObject>()) }
    var selected by remember { mutableIntStateOf(-1) }

    fun runSearch() {
        searching = true
        error = ""
        scope.launch {
            val resp = withContext(Dispatchers.IO) {
                VideoInfoService.manualSearch(ctx, item, query)
            }
            searching = false
            if (resp == null) {
                error = "搜索失败，请检查网络与 TMDB Key"
                results = emptyList()
            } else {
                val arr = resp.optJSONArray("results")
                results = if (arr == null) emptyList()
                else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                if (results.isEmpty()) error = "没有候选，换个关键词试试"
                selected = if (results.size == 1) 0 else -1
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .pressable(RoundedCornerShape(0.dp), onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(304.dp)
                .background(p.card, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Text("重新搜索", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.text)
            Text(
                "${item.name} · 换别名 / 外文名 / 加年份",
                fontSize = 11.sp,
                color = p.sub,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            BasicField(p, query) { query = it }
            Spacer(Modifier.height(8.dp))
            Text(
                if (searching) "搜索中…" else "搜索",
                fontSize = 13.sp,
                color = Blue,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .pressable(RoundedCornerShape(8.dp)) { if (!searching && query.isNotBlank()) runSearch() }
                    .padding(vertical = 4.dp)
            )
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(error, fontSize = 12.sp, color = Orange)
            }
            if (results.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .height(190.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    results.forEachIndexed { i, c ->
                        val title = c.optString("title")
                        val y = c.optInt("year")
                        val ov = c.optString("overview")
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (i == selected) Blue.copy(alpha = 0.10f) else Color.Transparent,
                                    RoundedCornerShape(10.dp)
                                )
                                .pressable(RoundedCornerShape(10.dp)) { selected = i }
                                .padding(horizontal = 10.dp, vertical = 9.dp)
                        ) {
                            Row {
                                Text(
                                    title,
                                    fontSize = 14.sp,
                                    fontWeight = if (i == selected) FontWeight.Medium else FontWeight.Normal,
                                    color = if (i == selected) Blue else p.text,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (y > 0) Text(" $y", fontSize = 12.sp, color = p.sub)
                            }
                            if (ov.isNotEmpty()) {
                                Text(
                                    ov, fontSize = 11.sp, color = p.sub,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .background(p.cardAlt, RoundedCornerShape(12.dp))
                        .pressable(RoundedCornerShape(12.dp), onDismiss)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("取消", fontSize = 13.sp, color = p.text)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .background(if (selected >= 0) Blue else p.cardAlt, RoundedCornerShape(12.dp))
                        .pressable(RoundedCornerShape(12.dp)) { if (selected >= 0) onPick(results[selected].optInt("tmdbId")) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "用这个",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (selected >= 0) Color.White else p.sub
                    )
                }
            }
        }
    }
}

private fun defaultQuery(item: Item): String {
    return if (item.isEpisode()) (item.seriesName ?: "").trim() else ScrapeTitle.parse(item.name)?.title ?: item.name
}

/* ==================== 列表行 ==================== */

@Composable
private fun TabChip(label: String, active: Boolean, p: Palette, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (active) Blue.copy(alpha = 0.16f) else p.card,
                RoundedCornerShape(50)
            )
            .pressable(RoundedCornerShape(50), onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = if (active) Blue else p.sub
        )
    }
}

@Composable
private fun RowCard(
    p: Palette,
    row: InfoRow,
    onConfirmDanmu: (Int) -> Unit,
    onConfirmScrape: (Int) -> Unit,
    onRescrape: () -> Unit,
    onClear: () -> Unit
) {
    var expanded by remember(row.item.id) { mutableStateOf(false) }
    val tint = when {
        row.done -> Green
        row.failed -> Red
        else -> Orange
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.card, RoundedCornerShape(16.dp))
            .pressable(RoundedCornerShape(16.dp)) { expanded = !expanded }
            .padding(horizontal = 15.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(7.dp).background(tint, RoundedCornerShape(50)))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.item.name,
                    fontSize = 14.sp,
                    color = p.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${row.local} · ${row.danmuLabel} · ${row.scrapeLabel}",
                    fontSize = 12.sp,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "收起" else "详情", fontSize = 12.sp, color = p.sub)
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            if (row.danmuDetail.isNotBlank()) {
                Text("弹幕：${row.danmuDetail}", fontSize = 12.sp, color = p.sub, maxLines = 3)
                Spacer(Modifier.height(4.dp))
            }
            if (row.scrapeDetail.isNotBlank()) {
                Text(
                    "刮削：${row.scrapeDetail}${if (row.scrapeLocked) " · 已锁定" else ""}",
                    fontSize = 12.sp, color = p.sub, maxLines = 3
                )
                Spacer(Modifier.height(4.dp))
            }
            row.danmuCands.take(4).forEach { (id, title) ->
                CandidateRow(p, "弹幕确认", title) { onConfirmDanmu(id) }
            }
            // 候选 = "换一个"：已刮削条目也保留候选，直接挑
            row.scrapeCands.take(4).forEach { (id, title) ->
                CandidateRow(p, if (row.scrapeDone) "换一个" else "刮削确认", title) { onConfirmScrape(id) }
            }
            if (!row.scrapeLocked) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("重新搜索", Blue.copy(alpha = 0.14f), Modifier.weight(1f), textColor = Blue) {
                        onRescrape()
                    }
                    PillButton("清除结果", Red.copy(alpha = 0.12f), Modifier.weight(1f), textColor = Red) {
                        onClear()
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(p: Palette, chip: String, title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(RoundedCornerShape(8.dp), onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .background(Blue.copy(alpha = 0.12f), RoundedCornerShape(7.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Text(chip, fontSize = 11.sp, color = Blue)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            fontSize = 12.sp,
            color = p.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 把 Item 的弹幕 + 刮削状态转成一行展示数据。 */
private fun toRow(ctx: android.content.Context, item: Item): InfoRow {
    /* ---- 弹幕绑定状态 ---- */
    val d: JSONObject = EpisodeMatcher.binding(ctx, item)
    val dMatched = d.optInt("episodeId") > 0
    val dStatus = d.optString("status")
    val dLabel = when {
        dMatched && dStatus == "confirmed" -> "弹幕已确认"
        dMatched -> "弹幕已绑定"
        dStatus == "network_error" -> "弹幕网络失败"
        item.identityAmbiguous -> "命名有歧义"
        dStatus == "needs_confirmation" -> "弹幕待确认"
        dStatus == "unmatched" -> "弹幕未匹配"
        else -> "弹幕待识别"
    }
    val dDetail = when {
        dMatched -> d.optString("episodeTitle")
        dStatus == "network_error" -> d.optString("error", "连接未成功，请重新识别")
        item.identityAmbiguous -> "多集或上下篇文件，请拆分后识别"
        else -> ""
    }
    val danmuCands = ArrayList<Pair<Int, String>>()
    d.optJSONArray("candidates")?.let { array ->
        for (i in 0 until array.length()) {
            val c = array.optJSONObject(i) ?: continue
            val id = c.optInt("episodeId", -1)
            if (id > 0) {
                danmuCands.add(id to (c.optString("animeTitle") + " / " + c.optString("episodeTitle")))
            }
        }
    }

    /* ---- 元数据刮削状态 ---- */
    val s: JSONObject = VideoInfoService.state(ctx, item)
    val sStatus = s.optString("status")
    val sLocked = s.optBoolean("locked")
    val sDone = sStatus == "scraped" || sStatus == "local_nfo"
    val sFailed = sStatus == "network_error" || sStatus == "error"
    val sLabel = when {
        sStatus == "scraped" -> if (sLocked) "已刮削·已锁定" else "已刮削"
        sStatus == "local_nfo" -> "本地NFO"
        sStatus == "network_error" -> "刮削失败"
        sStatus == "error" -> "刮削失败"
        sStatus == "needs_confirmation" -> "刮削待确认"
        sStatus == "scraping" -> "刮削中"
        sStatus == "unmatched" -> "未命中"
        sStatus == "skipped" -> "无法识别"
        else -> "待刮削"
    }
    val sDetail = when {
        sStatus == "scraped" -> {
            val t = s.optString("title")
            val y = s.optInt("year")
            if (t.isNotEmpty()) t + (if (y > 0) " ($y)" else "") else ""
        }
        sStatus == "local_nfo" -> {
            val t = s.optString("title")
            val src = s.optString("source")
            (if (t.isNotEmpty()) t else "") + (if (src.isNotEmpty()) if (t.isEmpty()) src else " · $src" else "")
        }
        sFailed -> s.optString("error", "网络异常，请重新刮削")
        sStatus == "needs_confirmation" -> "有多个候选，展开后选择"
        else -> ""
    }
    val scrapeCands = ArrayList<Pair<Int, String>>()
    s.optJSONArray("candidates")?.let { array ->
        for (i in 0 until array.length()) {
            val c = array.optJSONObject(i) ?: continue
            val id = c.optInt("tmdbId", -1)
            if (id > 0) {
                val y = c.optInt("year")
                val title = c.optString("title") + (if (y > 0) " ($y)" else "")
                val ov = c.optString("overview")
                scrapeCands.add(id to (title + (if (ov.isNotEmpty()) " — $ov" else "")))
            }
        }
    }

    val rank = when {
        dMatched && sDone -> RANK_DONE
        (dStatus == "network_error" && !dMatched) || sFailed -> RANK_FAILED
        else -> RANK_PENDING
    }
    val local = if (item.isEpisode()) {
        if (item.seasonExplicit) "第${item.seasonNumber}季 · 第${item.episodeNumber}集"
        else "季未知 · 第${item.episodeNumber}集"
    } else "电影"
    return InfoRow(
        item, local, dLabel, dDetail, danmuCands, dMatched,
        dStatus == "network_error" && !dMatched,
        sLabel, sDetail, scrapeCands, sDone, sFailed, sLocked, rank
    )
}
