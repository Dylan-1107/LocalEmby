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
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 次要文字颜色（避免依赖具体主题字段）。 */
private val Secondary = Color(0xFF8A8A93)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixTheme(
                colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(HttpService.isServiceRunning) }
    var folderPath by remember { mutableStateOf(Settings.getFolderPath(ctx)) }
    var treeLabel by remember { mutableStateOf(treeDisplayPath(ctx)) }
    var portText by remember { mutableStateOf(Settings.getPort(ctx).toString()) }
    var danmuApi by remember { mutableStateOf(Settings.getDanmuApi(ctx)) }
    var autoStart by remember { mutableStateOf(Settings.isServerEnabled(ctx)) }
    var count by remember { mutableStateOf(-1) }
    var lanIp by remember { mutableStateOf(lanIpAddress()) }

    LaunchedEffect(Unit) {
        while (true) {
            running = HttpService.isServiceRunning
            delay(1000)
        }
    }

    LaunchedEffect(folderPath, treeLabel) {
        count = withContext(Dispatchers.IO) {
            runCatching { VideoLibrary.get(ctx, Settings.getFolderPath(ctx)).size }.getOrDefault(-1)
        }
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
                    runCatching { VideoLibrary.scanAuto(ctx, Settings.getFolderPath(ctx)).size }
                        .getOrDefault(-1)
                }
                toast(ctx, "已选择：$treeLabel，共 $count 部视频")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SmallTopAppBar(title = "本地影院")

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ---------- 服务器状态 ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(
                        Modifier
                            .size(10.dp)
                            .background(
                                if (running) Color(0xFF34C759) else Color(0xFF9A9AA5),
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (running) "运行中" else "已停止",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.weight(1f))
                    Text(text = "端口 ${Settings.getPort(ctx)}", fontSize = 13.sp, color = Secondary)
                }

                Spacer(Modifier.height(12.dp))
                AddressRow("本机", "http://127.0.0.1:${Settings.getPort(ctx)}")
                Spacer(Modifier.height(8.dp))
                AddressRow(
                    "局域网",
                    if (lanIp == null) "未检测到" else "http://$lanIp:${Settings.getPort(ctx)}"
                )

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (running) {
                                Settings.setServerEnabled(ctx, false)
                                ctx.stopService(Intent(ctx, HttpService::class.java))
                                toast(ctx, "服务器已停止")
                            } else {
                                Settings.setServerEnabled(ctx, true)
                                val i = Intent(ctx, HttpService::class.java)
                                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                                else ctx.startService(i)
                                toast(ctx, "服务器启动中…")
                            }
                            autoStart = Settings.isServerEnabled(ctx)
                            running = HttpService.isServiceRunning
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (running) "停止服务器" else "启动服务器")
                    }
                    Button(
                        onClick = {
                            lanIp = lanIpAddress()
                            toast(ctx, "地址已刷新")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("刷新地址")
                    }
                }
            }

            // ---------- 媒体库 ----------
            SmallTitle(text = "媒体库")
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(text = "视频目录", fontSize = 13.sp, color = Secondary)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = treeLabel.ifEmpty { "未选择（扫描全机视频）" },
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { picker.launch(null) }, modifier = Modifier.weight(1f)) {
                        Text("选择文件夹")
                    }
                    Button(
                        onClick = {
                            VideoLibrary.invalidate()
                            scope.launch {
                                count = withContext(Dispatchers.IO) {
                                    runCatching {
                                        VideoLibrary.scanAuto(ctx, Settings.getFolderPath(ctx)).size
                                    }.getOrDefault(-1)
                                }
                                toast(
                                    ctx,
                                    if (count >= 0) "共 $count 部视频" else "扫描失败，请查看日志"
                                )
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("刷新媒体库")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (count < 0) "统计中…" else "共 $count 部视频",
                    fontSize = 13.sp,
                    color = Secondary
                )
            }

            // ---------- 设置 ----------
            SmallTitle(text = "设置")
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("开机自动启动", fontSize = 16.sp)
                        Text("仅在你手动开启过服务器后生效", fontSize = 12.sp, color = Secondary)
                    }
                    Switch(checked = autoStart, onCheckedChange = {
                        autoStart = it
                        Settings.setServerEnabled(ctx, it)
                    })
                }

                HorizontalDivider()

                Text(text = "服务端口", fontSize = 13.sp, color = Secondary)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = portText,
                        onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = {
                        val v = portText.toIntOrNull()
                        if (v == null || v < 1024 || v > 65535) {
                            toast(ctx, "请输入 1024-65535 的端口")
                        } else {
                            Settings.setPort(ctx, v)
                            toast(ctx, if (running) "已保存，重启服务器后生效" else "端口已保存")
                        }
                    }) { Text("保存") }
                }

                Spacer(Modifier.height(14.dp))
                Text(text = "弹幕源（弹弹play 兼容，留空用内置）", fontSize = 13.sp, color = Secondary)
                Spacer(Modifier.height(6.dp))
                TextField(
                    value = danmuApi,
                    onValueChange = { danmuApi = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    Settings.setDanmuApi(ctx, danmuApi)
                    toast(ctx, "弹幕源已保存")
                }) { Text("保存弹幕源") }
            }

            // ---------- 调试 ----------
            SmallTitle(text = "调试")
            Card(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { ctx.startActivity(Intent(ctx, LogActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("查看请求日志") }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "在 Emby 客户端添加服务器：地址 http://127.0.0.1:${Settings.getPort(ctx)}，" +
                        "用户名密码任意填写。",
                fontSize = 12.sp,
                color = Secondary
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun AddressRow(label: String, value: String) {
    val ctx = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = Secondary,
            modifier = Modifier.width(56.dp)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Button(onClick = { copy(ctx, value) }) {
            Text("复制", fontSize = 13.sp)
        }
    }
}

private fun copy(ctx: Context, text: String) {
    if (text.isEmpty()) return
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("url", text))
    toast(ctx, "已复制：$text")
}

private fun toast(ctx: Context, msg: String) {
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

private fun lanIpAddress(): String? {
    return runCatching {
        val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
        var fallback: String? = null
        while (ifaces.hasMoreElements()) {
            val ni = ifaces.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a.isLoopbackAddress || a !is java.net.Inet4Address) continue
                val h = a.hostAddress ?: continue
                if (h.startsWith("169.254")) continue
                if (h.startsWith("192.168.") || h.startsWith("10.") || h.startsWith("172.")) return h
                if (fallback == null) fallback = h
            }
        }
        fallback
    }.getOrNull()
}
