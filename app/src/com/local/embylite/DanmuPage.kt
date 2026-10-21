package com.local.embylite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 弹幕页筛选页签。 */
private enum class DanmuTab(val label: String) {
    ALL("全部"),
    BOUND("已绑定"),
    TODO("待处理"),
    FAILED("失败")
}

/** 一行识别结果（在 IO 线程算好，避免组合期间读 SharedPreferences）。 */
private data class DanmuRow(
    val item: Item,
    val local: String,
    val statusLabel: String,
    val detail: String,
    val candidates: List<Pair<Int, String>>,
    val rank: Int
) {
    val bound get() = rank == RANK_MATCHED || rank == RANK_CONFIRMED
    val failed get() = rank == RANK_FAILED
}

private const val RANK_MATCHED = 0
private const val RANK_CONFIRMED = 1
private const val RANK_FAILED = 2
private const val RANK_AMBIGUOUS = 3
private const val RANK_PENDING = 4
private const val RANK_NONE = 5

/**
 * 弹幕页：固定头部（进度 + 统计 + 筛选页签），下方是独立滚动列表。
 * 不再是一整条长滚动——切筛选、看列表都不会把进度卡滚出屏幕。
 */
@Composable
internal fun DanmuScreen(p: Palette) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var progress by remember { mutableStateOf(EpisodeMatcher.progress) }
    var rows by remember { mutableStateOf(emptyList<DanmuRow>()) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(DanmuTab.ALL) }

    LaunchedEffect(Unit) {
        while (true) {
            progress = EpisodeMatcher.progress
            delay(2000)
        }
    }

    fun reload() {
        loading = true
        scope.launch {
            rows = withContext(Dispatchers.IO) {
                runCatching {
                    VideoLibrary.get(ctx, Settings.getFolderPath(ctx))
                        .filter { it.episodeNumber > 0 }
                        .map { toRow(ctx, it) }
                        .sortedWith(compareBy({ it.rank }, { it.item.name }))
                }.getOrDefault(emptyList())
            }
            loading = false
        }
    }

    LaunchedEffect(progress) { reload() }

    val bound = rows.count { it.bound }
    val todo = rows.count { !it.bound && !it.failed }
    val failed = rows.count { it.failed }
    val visible = remember(rows, tab) {
        when (tab) {
            DanmuTab.ALL -> rows
            DanmuTab.BOUND -> rows.filter { it.bound }
            DanmuTab.TODO -> rows.filter { !it.bound && !it.failed }
            DanmuTab.FAILED -> rows.filter { it.failed }
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
        /* ---- 固定头部 ---- */
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
            Text(
                "弹幕识别",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = p.text,
                letterSpacing = (-0.5).sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (loading) "正在读取识别结果…" else progress,
                fontSize = 12.sp,
                color = p.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(14.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(p.card, RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiniStat("已绑定", bound.toString(), Green, p.sub, Modifier.weight(1f))
                MiniStat("待处理", todo.toString(), Orange, p.sub, Modifier.weight(1f))
                MiniStat("失败", failed.toString(), Red, p.sub, Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                PillButton("重新识别", Blue, Modifier.width(90.dp)) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            EpisodeMatcher.enqueue(
                                ctx, VideoLibrary.get(ctx, Settings.getFolderPath(ctx)), true
                            )
                        }
                        toast(ctx, "开始识别弹幕…")
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DanmuTab.entries.forEach { entry ->
                    TabChip(entry.label, tab == entry, p) { tab = entry }
                }
            }

            Spacer(Modifier.height(10.dp))
        }

        /* ---- 独立滚动的结果列表 ---- */
        if (rows.isEmpty()) {
            EmptyBox(
                p,
                if (loading) "正在读取…"
                else "还没有可识别的剧集文件\n文件名带 S01E01 / 第N集 等集数信息才会参与匹配"
            )
        } else if (visible.isEmpty()) {
            EmptyBox(p, "当前筛选下没有条目")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visible, key = { it.item.id }) { row ->
                    RowCard(p, row, onConfirm = { id ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                EpisodeMatcher.confirm(ctx, row.item, id)
                            }
                            toast(ctx, if (ok) "已确认绑定" else "绑定失败，请重新识别")
                            if (ok) reload()
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
    color: Color,
    subColor: Color,
    modifier: Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = color)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = subColor)
    }
}

@Composable
private fun TabChip(label: String, active: Boolean, p: Palette, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (active) Blue.copy(alpha = 0.16f) else p.card,
                RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
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
private fun EmptyBox(p: Palette, text: String) {
    Box(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(p.card, RoundedCornerShape(18.dp))
                .padding(vertical = 28.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, fontSize = 12.sp, color = p.sub, maxLines = 4)
        }
    }
}

@Composable
private fun RowCard(p: Palette, row: DanmuRow, onConfirm: (Int) -> Unit) {
    var expanded by remember(row.item.id) { mutableStateOf(false) }
    val tint = when {
        row.bound -> Green
        row.failed -> Red
        else -> Orange
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.card, RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(7.dp).background(tint, RoundedCornerShape(50)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.item.name,
                    fontSize = 13.sp,
                    color = p.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text("${row.local} · ${row.statusLabel}", fontSize = 11.sp, color = tint)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "收起" else "详情", fontSize = 11.sp, color = p.sub)
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            if (row.detail.isNotBlank()) {
                Text(row.detail, fontSize = 11.sp, color = p.sub, maxLines = 3)
                Spacer(Modifier.height(6.dp))
            }
            row.candidates.take(4).forEach { (id, title) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onConfirm(id) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .background(Blue.copy(alpha = 0.12f), RoundedCornerShape(7.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text("确认", fontSize = 10.sp, color = Blue)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        title,
                        fontSize = 11.sp,
                        color = p.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 把 Item 的绑定状态转成一行展示数据。 */
private fun toRow(ctx: android.content.Context, item: Item): DanmuRow {
    val result: JSONObject = EpisodeMatcher.binding(ctx, item)
    val matched = result.optInt("episodeId") > 0
    val status = result.optString("status")
    val rank = when {
        matched && status == "confirmed" -> RANK_CONFIRMED
        matched -> RANK_MATCHED
        status == "network_error" -> RANK_FAILED
        item.identityAmbiguous -> RANK_AMBIGUOUS
        status == "needs_confirmation" -> RANK_PENDING
        status == "unmatched" -> RANK_NONE
        else -> RANK_PENDING
    }
    val label = when (rank) {
        RANK_MATCHED -> "已绑定"
        RANK_CONFIRMED -> "已确认"
        RANK_FAILED -> "网络失败"
        RANK_AMBIGUOUS -> "命名有歧义"
        RANK_PENDING -> if (status == "needs_confirmation") "待确认候选" else "等待识别"
        else -> "未找到匹配"
    }
    val detail = when (rank) {
        RANK_MATCHED, RANK_CONFIRMED -> result.optString("episodeTitle")
        RANK_FAILED -> result.optString("error", "连接未成功，请重新识别")
        RANK_AMBIGUOUS -> "多集或上下篇文件，请拆分后识别"
        else -> ""
    }
    val local = if (item.seasonExplicit) "第${item.seasonNumber}季 · 第${item.episodeNumber}集"
                else "季未知 · 第${item.episodeNumber}集"
    val candidates = ArrayList<Pair<Int, String>>()
    result.optJSONArray("candidates")?.let { array ->
        for (i in 0 until array.length()) {
            val c = array.optJSONObject(i) ?: continue
            val id = c.optInt("episodeId", -1)
            if (id > 0) {
                val title = c.optString("animeTitle") + " / " + c.optString("episodeTitle")
                candidates.add(id to title)
            }
        }
    }
    return DanmuRow(item, local, label, detail, candidates, rank)
}
