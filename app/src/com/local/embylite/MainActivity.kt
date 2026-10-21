package com.local.embylite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.anim.folmeSpring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ---------------- 配色 ---------------- */
internal val Blue = Color(0xFF4C9AFF)
internal val Red = Color(0xFFFF453A)
internal val Orange = Color(0xFFFF9F0A)
internal val Green = Color(0xFF30D158)

internal data class Palette(
    val bg: Color,
    val card: Color,
    val cardAlt: Color,
    val text: Color,
    val sub: Color,
    val divider: Color,
)

internal val DarkPalette = Palette(
    bg = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    cardAlt = Color(0xFF232326),
    text = Color(0xFFFFFFFF),
    sub = Color(0xFF8E8E93),
    divider = Color(0xFF2C2C2E),
)

internal val LightPalette = Palette(
    bg = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    cardAlt = Color(0xFFF7F7FA),
    text = Color(0xFF111114),
    sub = Color(0xFF8A8A8E),
    divider = Color(0xFFE5E5EA),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
            HomeScreen(p)
        }
    }
}

@Composable
private fun HomeScreen(p: Palette) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var running by remember { mutableStateOf(HttpService.isServiceRunning) }
    var port by remember { mutableStateOf(Settings.getPort(ctx)) }
    var uptimeMin by remember { mutableStateOf(0L) }
    var count by remember { mutableStateOf(-1) }
    var treeLabel by remember { mutableStateOf(treeDisplayPath(ctx)) }
    var lanIp by remember { mutableStateOf(lanIpAddress()) }
    var memMb by remember { mutableStateOf(0L) }
    var threads by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(0) }
    /* 0 = 首页，1 = 弹幕，2 = 日志，3 = 设置（底部导航切换） */
    var page by remember { mutableStateOf(0) }
    var allFiles by remember { mutableStateOf(VideoLibrary.hasAllFilesAccess()) }
    var prevAllFiles by remember { mutableStateOf(allFiles) }

    LaunchedEffect(Unit) {
        while (true) {
            running = HttpService.isServiceRunning
            port = Settings.getPort(ctx)
            uptimeMin = if (running && HttpService.startedAt > 0)
                (System.currentTimeMillis() - HttpService.startedAt) / 60000 else 0
            val rt = Runtime.getRuntime()
            memMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            threads = Thread.activeCount()
            lanIp = lanIpAddress()
            allFiles = VideoLibrary.hasAllFilesAccess()
            if (allFiles && !prevAllFiles) {
                prevAllFiles = true
                VideoLibrary.invalidate()
            }
            delay(2000)
        }
    }

    LaunchedEffect(treeLabel) {
        count = withContext(Dispatchers.IO) {
            runCatching { VideoLibrary.get(ctx, Settings.getFolderPath(ctx)).size }.getOrDefault(-1)
        }
    }

    fun rescan() {
        VideoLibrary.invalidate()
        scope.launch {
            count = withContext(Dispatchers.IO) {
                runCatching { VideoLibrary.get(ctx, Settings.getFolderPath(ctx)).size }
                    .getOrDefault(-1)
            }
            toast(ctx, if (count >= 0) "已刷新，共 $count 部" else "扫描失败，请查看日志")
        }
    }

    fun toggleServer() {
        if (running) {
            Settings.setServerEnabled(ctx, false)
            ctx.stopService(Intent(ctx, HttpService::class.java))
        } else {
            Settings.setServerEnabled(ctx, true)
            val i = Intent(ctx, HttpService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            // 开启过 Root 保活的话，每次启动服务器顺手执行一次
            if (Settings.isRootKeepAlive(ctx)) {
                scope.launch(Dispatchers.IO) { runCatching { RootKeepAlive.apply(ctx) } }
            }
        }
        running = HttpService.isServiceRunning
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            Settings.setTreeUri(ctx, uri.toString())
            treeLabel = treeDisplayPath(ctx)
            VideoLibrary.invalidate()
            scope.launch {
                count = withContext(Dispatchers.IO) {
                    runCatching { VideoLibrary.get(ctx, Settings.getFolderPath(ctx)).size }
                        .getOrDefault(-1)
                }
                toast(ctx, "已选择目录，共 $count 部")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {

        /* ---------- 页面区（带过渡动效） ----------
         * 用 Miuix 自带的 folmeSpring 做轻微缩放 + SinOutEasing 做水平位移，
         * 再叠一层淡入淡出，切页是"滑过来"而不是直接刷新。 */
        AnimatedContent(
            targetState = page,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val forward = targetState > initialState
                // 前进：新页从右边滑入、旧页往左滑出；后退则镜像
                val slideSpec = tween<IntOffset>(220, easing = SinOutEasing)
                // 轻微缩放用 Miuix 的 folmeSpring：response 0.9s / damping 0.38（与 Dialog 动效同一套参数）
                val scaleSpec = folmeSpring<Float>(response = 0.9f, damping = 0.38f)

                val enter: EnterTransition =
                    fadeIn(tween(160)) + slideInHorizontally(slideSpec) {
                        if (forward) it else -it
                    } + scaleIn(scaleSpec, 0.96f)
                val exit: ExitTransition =
                    slideOutHorizontally(slideSpec) {
                        if (forward) -it else it
                    } + fadeOut(tween(160)) + scaleOut(scaleSpec, 0.96f)

                enter togetherWith exit
            },
            label = "page"
        ) { current ->
            when (current) {
                1 -> DanmuScreen(p)
                2 -> SettingsScreen(
                    p = p,
                    port = port,
                    running = running,
                    count = count,
                    treeLabel = treeLabel,
                    allFiles = allFiles,
                    onPortSaved = { port = it },
                    onTreeLabelChanged = { treeLabel = it },
                    onRescan = { rescan() },
                    onPickFolder = { picker.launch(null) }
                )
                3 -> LogPage(p)
                else -> HomeContent(
                    p, scroll, running, uptimeMin, port, lanIp, count, memMb, threads,
                    onToggle = { toggleServer() },
                    onRescan = { rescan() },
                    onPickFolder = { picker.launch(null) },
                    onOpenLog = { tab = 2; page = 3 }
                )
            }
        }

        /* ---------- 底部导航：图标在上、小字在下 ---------- */
        Box(Modifier.align(Alignment.BottomCenter)) {
            AppNavBar(
                tab = tab,
                p = p,
                onSelect = { i ->
                    if (tab == i) return@AppNavBar
                    tab = i
                    page = when (i) {
                        1 -> 1
                        2 -> 3
                        3 -> 2
                        else -> 0
                    }
                }
            )
        }
    }
}

/* ---------------- 首页内容 ---------------- */

/** 首页：服务器状态、地址、快捷操作、运行指标。设置与弹幕已拆到各自页面。 */
@Composable
private fun HomeContent(
    p: Palette,
    scroll: androidx.compose.foundation.ScrollState,
    running: Boolean,
    uptimeMin: Long,
    port: Int,
    lanIp: String?,
    count: Int,
    memMb: Long,
    threads: Int,
    onToggle: () -> Unit,
    onRescan: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenLog: () -> Unit = {}
) {
    val ctx = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Text(
            "Embylite",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = p.text,
            letterSpacing = (-0.5).sp
        )
        Spacer(Modifier.height(2.dp))
        Text("本地 Emby 服务器", fontSize = 13.sp, color = p.sub)

        Spacer(Modifier.height(16.dp))

        HeroCard(p, running, uptimeMin, port, lanIp)

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .background(p.card, RoundedCornerShape(18.dp))
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionCell(if (running) "停止" else "启动", if (running) Red else Blue, Modifier.weight(1f)) {
                onToggle()
            }
            VDivider(p)
            ActionCell("复制", Blue, Modifier.weight(1f)) {
                val ip = lanIp
                if (ip != null) copy(ctx, "http://$ip:$port")
                else toast(ctx, "未获取到局域网地址，请连接 Wi-Fi")
            }
            VDivider(p)
            ActionCell("刷新", Orange, Modifier.weight(1f)) { onRescan() }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(p, "媒体库", if (count < 0) "统计中" else "$count 部视频", Modifier.weight(1f)) {
                onPickFolder()
            }
            InfoCard(p, "日志", "查看请求记录", Modifier.weight(1f)) {
                onOpenLog()
            }
        }

        Spacer(Modifier.height(12.dp))

        Column(
            Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(18.dp)).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("服务", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text)
                Spacer(Modifier.weight(1f))
                Text("点击数值可复制", fontSize = 11.sp, color = p.sub)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                StatCell(p, "端口", port.toString(), Modifier.weight(1f)) {
                    copy(ctx, port.toString())
                }
                StatCell(p, "本机", "127.0.0.1", Modifier.weight(1f)) {
                    copy(ctx, "http://127.0.0.1:$port")
                }
                StatCell(p, "局域网", lanIp ?: "无", Modifier.weight(1f)) {
                    val ip = lanIp
                    if (ip != null) copy(ctx, "http://$ip:$port")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProgressCard(p, "媒体库数量", count.coerceAtLeast(0), 200, Modifier.weight(1f))
            ProgressCard(
                p, "内存占用", memMb.toInt(), 512, Modifier.weight(1f),
                suffix = "MB", extraLabel = "线程", extraValue = threads.toString()
            )
        }

        Spacer(Modifier.height(132.dp))
    }
}

/* ---------------- 轻量 Text ---------------- */

@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing
        ),
        maxLines = maxLines,
        overflow = overflow
    )
}

/* ---------------- 组件 ---------------- */

@Composable
private fun HeroCard(p: Palette, running: Boolean, uptimeMin: Long, port: Int, lanIp: String?) {
    val tint = if (running) Green else p.sub
    val brush = if (running) {
        Brush.linearGradient(listOf(Color(0xFF10402A), Color(0xFF123024), Color(0xFF0A1A12)))
    } else {
        Brush.linearGradient(listOf(p.card, p.cardAlt))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(brush, RoundedCornerShape(22.dp))
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(8.dp).background(tint, RoundedCornerShape(50)))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (running) "运行中" else "已停止",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = tint
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (running) "已运行 $uptimeMin 分钟" else "服务未启动",
                    fontSize = 13.sp,
                    color = if (running) Color(0xFFA9BFB2) else p.sub
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Emby · 直连播放",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = p.text
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "http://${lanIp ?: "127.0.0.1"}:$port",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (running) Color(0xFF8FA79A) else p.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            Canvas(Modifier.size(64.dp)) {
                val stroke = 3.dp.toPx()
                drawCircle(
                    color = tint.copy(alpha = 0.9f),
                    radius = size.minDimension / 2 - stroke / 2,
                    style = Stroke(width = stroke)
                )
                val w = size.width
                val h = size.height
                if (running) {
                    drawLine(tint, Offset(w * 0.32f, h * 0.52f), Offset(w * 0.45f, h * 0.66f), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(w * 0.45f, h * 0.66f), Offset(w * 0.70f, h * 0.36f), stroke, StrokeCap.Round)
                } else {
                    drawLine(tint, Offset(w * 0.38f, h * 0.38f), Offset(w * 0.62f, h * 0.62f), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(w * 0.62f, h * 0.38f), Offset(w * 0.38f, h * 0.62f), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun ActionCell(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.fillMaxSize().clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun VDivider(p: Palette) {
    Box(Modifier.width(1.dp).height(22.dp).background(p.divider))
}

@Composable
private fun InfoCard(
    p: Palette,
    title: String,
    subtitle: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .background(p.card, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            fontSize = 12.sp,
            color = p.sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatCell(
    p: Palette,
    label: String,
    value: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = p.sub)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Blue,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ProgressCard(
    p: Palette,
    label: String,
    value: Int,
    max: Int,
    modifier: Modifier,
    suffix: String = "部",
    extraLabel: String? = null,
    extraValue: String? = null
) {
    Column(modifier.background(p.card, RoundedCornerShape(18.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = p.sub, modifier = Modifier.weight(1f))
            if (extraLabel != null) {
                Text("$extraLabel $extraValue", fontSize = 11.sp, color = p.sub)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$value", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            Spacer(Modifier.width(3.dp))
            Text(suffix, fontSize = 11.sp, color = p.sub, modifier = Modifier.padding(bottom = 2.dp))
        }
        Spacer(Modifier.height(10.dp))
        val frac = (value.toFloat() / max.toFloat()).coerceIn(0.02f, 1f)
        Box(Modifier.fillMaxWidth().height(4.dp).background(p.divider, RoundedCornerShape(50))) {
            Box(Modifier.fillMaxWidth(frac).height(4.dp).background(Blue, RoundedCornerShape(50)))
        }
    }
}

@Composable
internal fun BasicField(p: Palette, value: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(p.cardAlt, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = androidx.compose.ui.text.TextStyle(color = p.text, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Blue),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
internal fun PillButton(
    text: String,
    bg: Color,
    modifier: Modifier,
    textColor: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(bg, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = textColor)
    }
}

/* ---------------- 工具 ---------------- */

internal fun copy(ctx: Context, text: String) {
    if (text.isEmpty()) return
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("url", text))
    toast(ctx, "已复制：$text")
}

internal fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

private fun treeDisplayPath(ctx: Context): String {
    val tree = Settings.getTreeUri(ctx)
    if (tree.isNullOrEmpty()) return ""
    return runCatching {
        val docId = DocumentsContract.getTreeDocumentId(Uri.parse(tree))
        val path = java.net.URLDecoder.decode(docId, "UTF-8")
        val idx = path.indexOf(':')
        val vol = if (idx >= 0) path.substring(0, idx) else "primary"
        val sub = if (idx >= 0) path.substring(idx + 1) else ""
        if (vol == "primary") {
            "/storage/emulated/0" + if (sub.isEmpty()) "" else "/$sub"
        } else {
            "/storage/$vol" + if (sub.isEmpty()) "" else "/$sub"
        }
    }.getOrDefault("")
}

private fun lanIpAddress(): String? = NetworkUtils.getLanIp()
