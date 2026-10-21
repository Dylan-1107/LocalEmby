package com.local.embylite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 日志页：既可作为底部导航的一个页签（应用内切换，动效与其它页签一致），
 * 也可被 LogActivity 以独立模式使用（此时显示左上角 ‹ 返回）。
 *
 * 配色与文字全部复用首页共享的 [Palette] 与 Text，保证整套 UI 一致。
 */
@Composable
internal fun LogPage(p: Palette, onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var raw by remember { mutableStateOf(EmbyLog.text()) }
    var onlyError by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            raw = EmbyLog.text()
            delay(1500)
        }
    }

    val allLines = remember(raw) {
        raw.lines().filter { it.isNotBlank() }.reversed() // 最新在上
    }
    val lines = remember(allLines, onlyError) {
        if (onlyError) allLines.filter { isErrorLine(it) } else allLines
    }

    // 有新日志时回到顶部（因为最新在上）
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) runCatching { listState.animateScrollToItem(0) }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = if (onBack == null) 96.dp else 0.dp)
        ) {

            /* 顶栏 */
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    Text(
                        "‹",
                        fontSize = 26.sp,
                        color = p.text,
                        modifier = Modifier
                            .pressable(RoundedCornerShape(50)) { onBack() }
                            .padding(end = 14.dp, top = 2.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("请求日志", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.text)
                    Text("共 ${allLines.size} 条 · 自动刷新", fontSize = 11.sp, color = p.sub)
                }
                Box(
                    Modifier
                        .background(p.card, RoundedCornerShape(50))
                        .pressable(RoundedCornerShape(50)) {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("log", raw))
                            Toast.makeText(ctx, "日志已复制", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text("复制", fontSize = 13.sp, color = Blue, fontWeight = FontWeight.Medium)
                }
            }

            /* 过滤 */
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(p, "全部", !onlyError) { onlyError = false }
                FilterChip(p, "仅异常", onlyError) { onlyError = true }
            }

            Spacer(Modifier.height(12.dp))

            /* 日志列表 */
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(p.card, RoundedCornerShape(18.dp))
            ) {
                if (lines.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (onlyError) "暂无异常请求" else "暂无请求记录，用客户端连接后会出现",
                            fontSize = 13.sp,
                            color = p.sub
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(lines) { line ->
                            Row(verticalAlignment = Alignment.Top) {
                                Spacer(
                                    Modifier
                                        .padding(top = 5.dp)
                                        .width(3.dp)
                                        .height(11.dp)
                                        .background(levelColor(line), RoundedCornerShape(50))
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    line,
                                    fontSize = 11.sp,
                                    color = levelColor(line),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "绿色 200 · 橙色 4xx · 红色 5xx · 灰色 其他",
                fontSize = 10.sp,
                color = p.sub,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun FilterChip(p: Palette, text: String, active: Boolean, onClick: () -> Unit) {
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
            text,
            fontSize = 12.sp,
            color = if (active) Blue else p.sub,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
        )
    }
}

internal fun isErrorLine(line: String): Boolean =
    line.contains("-> 4") || line.contains("-> 5") ||
            line.contains("error", true) || line.contains("失败") ||
            line.contains("aborted")

internal fun levelColor(line: String): Color = when {
    line.contains("-> 2") -> Color(0xFF6FCF97)
    line.contains("-> 4") -> Orange
    line.contains("-> 5") -> Red
    line.contains("aborted") -> Color(0xFF8E8E93)
    line.contains("失败") || line.contains("error", true) -> Red
    else -> Color(0xFFB0B0B8)
}
