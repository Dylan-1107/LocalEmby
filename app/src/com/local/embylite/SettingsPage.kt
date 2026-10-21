package com.local.embylite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页：媒体目录、服务端口、开机自启、Root 保活、弹幕源。
 * 全部沿用首页同一套卡片 / 分割线 / 胶囊按钮样式。
 */
@Composable
internal fun SettingsScreen(
    p: Palette,
    port: Int,
    running: Boolean,
    count: Int,
    treeLabel: String,
    allFiles: Boolean,
    themeMode: Int,
    onThemeChanged: (Int) -> Unit,
    onPortSaved: (Int) -> Unit,
    onTreeLabelChanged: (String) -> Unit,
    onRescan: () -> Unit,
    onPickFolder: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var autoStart by remember { mutableStateOf(Settings.isServerEnabled(ctx)) }
    var editingPort by remember { mutableStateOf(false) }
    var portDraft by remember { mutableStateOf(port.toString()) }
    var editingDanmu by remember { mutableStateOf(false) }
    var danmuDraft by remember { mutableStateOf(Settings.getDanmuApi(ctx)) }

    var editingTmdb by remember { mutableStateOf(false) }
    var tmdbDraft by remember { mutableStateOf(Settings.getTmdbKey(ctx)) }

    var rootEnabled by remember { mutableStateOf(Settings.isRootKeepAlive(ctx)) }
    var rootState by remember { mutableStateOf(RootKeepAlive.Status.UNKNOWN) }
    var rootBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            rootState = RootKeepAlive.check()
            delay(3000)
        }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
        Spacer(Modifier.height(10.dp))
        Text(
            "设置",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = p.text,
            letterSpacing = (-0.5).sp
        )
        Spacer(Modifier.height(2.dp))
        Text("媒体目录、服务与保活", fontSize = 13.sp, color = p.sub)

        Spacer(Modifier.height(16.dp))

        /* ---------- 媒体目录 ---------- */
        SettingCard(p) {
            Text("媒体目录", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text)
            Spacer(Modifier.height(10.dp))
            SettingRow(
                p,
                title = "视频目录",
                subtitle = when {
                    treeLabel.isNotEmpty() -> treeLabel
                    allFiles -> "全盘（已授予所有文件访问权限）"
                    else -> "未授权"
                },
                onClick = onPickFolder
            )
            Divider(p)
            SettingRow(p, "媒体库数量", "${count.coerceAtLeast(0)} 部", null, null)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("选择文件夹", Blue, Modifier.weight(1f)) { onPickFolder() }
                PillButton("刷新媒体库", p.cardAlt, Modifier.weight(1f), p.text) { onRescan() }
            }
            if (treeLabel.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Divider(p)
                SettingRow(
                    p, "改用全盘扫描",
                    if (allFiles) "清除当前文件夹选择，扫描整台手机"
                    else "需要先授予「所有文件访问权限」",
                    null, "›"
                ) {
                    Settings.setTreeUri(ctx, "")
                    Settings.setFolderPath(ctx, "")
                    onTreeLabelChanged("")
                    VideoLibrary.invalidate()
                    onRescan()
                }
            }
        }

        if (!allFiles) {
            Spacer(Modifier.height(12.dp))
            SettingCard(p) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("免选文件夹", fontSize = 15.sp, color = p.text)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "授予「所有文件访问权限」后直接扫全盘",
                            fontSize = 11.sp, color = p.sub
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    PillButton("去授权", Blue, Modifier.width(88.dp)) {
                        try {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:" + ctx.packageName)
                                )
                            )
                        } catch (e: Throwable) {
                            try {
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                                    )
                                )
                            } catch (e2: Throwable) {
                                toast(ctx, "无法打开权限设置")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        /* ---------- 外观 ---------- */
        SettingCard(p) {
            Text("外观", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (mode, label) ->
                    val active = themeMode == mode
                    Box(
                        Modifier
                            .weight(1f)
                            .background(
                                if (active) Blue.copy(alpha = 0.16f) else p.cardAlt,
                                RoundedCornerShape(12.dp)
                            )
                            .pressable(RoundedCornerShape(12.dp)) { onThemeChanged(mode) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            color = if (active) Blue else p.sub,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        /* ---------- 服务 ---------- */
        SettingCard(p) {
            Text("服务", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text)
            Spacer(Modifier.height(6.dp))
            Divider(p)
            SettingRow(
                p, "服务端口",
                if (running) "修改后需重启服务器生效" else "默认 8096",
                port.toString(), "›"
            ) {
                portDraft = port.toString()
                editingPort = !editingPort
            }
            if (editingPort) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicField(p, portDraft) { portDraft = it.filter { c -> c.isDigit() }.take(5) }
                    Spacer(Modifier.width(10.dp))
                    PillButton("保存", Blue, Modifier.width(72.dp)) {
                        val v = portDraft.toIntOrNull()
                        if (v == null || v < 1024 || v > 65535) {
                            toast(ctx, "请输入 1024-65535")
                        } else {
                            Settings.setPort(ctx, v)
                            onPortSaved(v)
                            editingPort = false
                            toast(ctx, if (running) "已保存，重启服务器后生效" else "已保存")
                        }
                    }
                }
            }
            Divider(p)
            SettingRow(
                p, "开机自动启动", "手动开启过服务器后生效",
                if (autoStart) "已开启" else "已关闭",
                null,
                valueColor = if (autoStart) Green else null
            ) {
                autoStart = !autoStart
                Settings.setServerEnabled(ctx, autoStart)
            }
        }

        Spacer(Modifier.height(12.dp))

        /* ---------- Root 保活 ---------- */
        SettingCard(p) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Root 保活", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "root 下把本应用加入电池优化白名单并常驻，降低被系统杀后台的概率",
                        fontSize = 11.sp, color = p.sub
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .background(
                            if (rootState == RootKeepAlive.Status.GRANTED) Green.copy(alpha = 0.16f)
                            else p.cardAlt,
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        when (rootState) {
                            RootKeepAlive.Status.GRANTED -> "已授权"
                            RootKeepAlive.Status.DENIED -> "未授权"
                            RootKeepAlive.Status.NO_ROOT -> "无 root"
                            else -> "检测中"
                        },
                        fontSize = 11.sp,
                        color = if (rootState == RootKeepAlive.Status.GRANTED) Green else p.sub
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Divider(p)
            SettingRow(
                p, "启用保活",
                when (rootState) {
                    RootKeepAlive.Status.NO_ROOT -> "当前设备未检测到 root，无法使用"
                    RootKeepAlive.Status.DENIED -> "点右侧「授权」在 root 管理器中允许本应用"
                    else -> "已开启后每次启动服务器都会自动执行"
                },
                if (rootEnabled) "已开启" else "已关闭",
                null,
                valueColor = if (rootEnabled) Green else null
            ) {
                if (rootState == RootKeepAlive.Status.NO_ROOT) {
                    toast(ctx, "未检测到 root")
                } else {
                    rootEnabled = !rootEnabled
                    Settings.setRootKeepAlive(ctx, rootEnabled)
                    if (rootEnabled) {
                        rootBusy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { RootKeepAlive.apply(ctx) }
                            rootBusy = false
                            rootState = RootKeepAlive.check()
                            toast(ctx, if (ok) "保活已生效" else "保活执行失败，请检查 root 授权")
                        }
                    }
                }
            }
            if (rootBusy) {
                Spacer(Modifier.height(6.dp))
                Text("正在申请 root 权限…", fontSize = 11.sp, color = p.sub)
            }
            Spacer(Modifier.height(8.dp))
            Divider(p)
            SettingRow(p, "立即执行保活", "手动触发一次白名单 + 常驻设置", null, "›") {
                rootBusy = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { RootKeepAlive.apply(ctx) }
                    rootBusy = false
                    rootState = RootKeepAlive.check()
                    toast(ctx, if (ok) "保活已生效" else "执行失败，请确认已授予 root")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        /* ---------- 弹幕源 ---------- */
        SettingCard(p) {
            Text("弹幕", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text)
            Spacer(Modifier.height(6.dp))
            Divider(p)
            SettingRow(
                p, "弹幕 API 地址",
                if (danmuDraft.isBlank()) "内置默认（弹弹play 兼容）" else danmuDraft,
                if (danmuDraft.isBlank()) "默认" else "自定义",
                "›"
            ) { editingDanmu = !editingDanmu }
            if (editingDanmu) {
                Spacer(Modifier.height(6.dp))
                BasicField(p, danmuDraft) { danmuDraft = it }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("保存", Blue, Modifier.weight(1f)) {
                        Settings.setDanmuApi(ctx, danmuDraft)
                        editingDanmu = false
                        toast(ctx, "已保存")
                    }
                    PillButton("恢复默认", p.cardAlt, Modifier.weight(1f), p.text) {
                        danmuDraft = ""
                        Settings.setDanmuApi(ctx, "")
                        toast(ctx, "已恢复内置弹幕源")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        /* ---------- 视频信息（元数据刮削） ---------- */
        SettingCard(p) {
            Text("视频信息", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text)
            Spacer(Modifier.height(6.dp))
            Divider(p)
            SettingRow(
                p, "TMDB API Key",
                "默认已内置公共 Key，可替换为你自己的（themoviedb.org 免费申请）",
                "已配置",
                "›"
            ) { editingTmdb = !editingTmdb }
            if (editingTmdb) {
                Spacer(Modifier.height(6.dp))
                BasicField(p, tmdbDraft) { tmdbDraft = it.trim() }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("保存", Blue, Modifier.weight(1f)) {
                        Settings.setTmdbKey(ctx, tmdbDraft)
                        editingTmdb = false
                        toast(ctx, if (tmdbDraft.isBlank()) "已清除 TMDB Key" else "已保存，指纹已更新将重新刮削")
                    }
                    PillButton("清除", p.cardAlt, Modifier.weight(1f), p.text) {
                        tmdbDraft = ""
                        Settings.setTmdbKey(ctx, "")
                        toast(ctx, "已清除 TMDB Key")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        /* ---------- 关于 ---------- */
        SettingCard(p) {
            SettingRow(p, "应用名称", "Embylite", null, null)
            Divider(p)
            SettingRow(p, "版本", "2.7.0 (12)", null, null)
            Divider(p)
            SettingRow(
                p, "说明",
                "本机 Emby 服务器模拟器，只管理你自己的本地视频文件",
                null, null
            )
        }

        Spacer(Modifier.height(132.dp))
        }

        // 下滑时大标题收拢到顶部中间（HYPERLIGHT 风格）
        CollapsingTitle(title = "设置", scroll = scroll, p = p)
    }
}

/* ---------------- 设置页小部件（与首页同一套视觉） ---------------- */

@Composable
private fun SettingCard(p: Palette, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.card, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun Divider(p: Palette) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
}

@Composable
private fun SettingRow(
    p: Palette,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    arrow: String? = null,
    valueColor: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.pressable(RoundedCornerShape(12.dp), onClick) else it }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = p.text)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = p.sub,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                value,
                fontSize = 13.sp,
                color = valueColor ?: p.sub,
                maxLines = 1
            )
        }
        if (arrow != null) {
            Spacer(Modifier.width(6.dp))
            Text(arrow, fontSize = 16.sp, color = p.sub)
        }
    }
}
