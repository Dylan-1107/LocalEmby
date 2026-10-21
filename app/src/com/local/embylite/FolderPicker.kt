package com.local.embylite

import java.io.File

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** 内置目录浏览的起始位置：主存储根目录。 */
private fun pickStart(): File {
    val primary = android.os.Environment.getExternalStorageDirectory()
    return if (primary != null && primary.isDirectory) primary else File("/storage/emulated/0")
}

/**
 * 应用内目录选择器（需要「所有文件访问权限」）。
 * 直接用 File API 浏览，不走系统 SAF 选择器——从而不受系统对
 * 存储根目录 / Download / Android 等目录的授权限制，任意目录可选。
 */
@Composable
internal fun FolderPickerDialog(
    p: Palette,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var dir by remember { mutableStateOf(pickStart()) }

    // 只列可见子目录；读取失败按空目录处理
    val subdirs = remember(dir) {
        runCatching {
            dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                ?.toList()
                ?.sortedWith(compareBy({ it.name }, { it.name }))
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    // 常用目录快捷入口
    val shortcuts = remember {
        listOf("Download", "Download/Telegram", "Movies", "DCIM", "Pictures")
            .map { File(pickStart(), it) }
            .filter { it.isDirectory }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(p.card, RoundedCornerShape(18.dp))
                .padding(18.dp)
        ) {
            Text("选择视频目录", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = p.text)
            Spacer(Modifier.height(4.dp))
            Text(
                dir.absolutePath,
                fontSize = 11.sp,
                color = p.sub,
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis
            )

            Spacer(Modifier.height(10.dp))

            // 快捷入口
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                shortcuts.take(3).forEach { sc ->
                    Box(
                        Modifier
                            .background(p.cardAlt, RoundedCornerShape(9.dp))
                            .clickable { dir = sc }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(sc.name, fontSize = 12.sp, color = Blue)
                    }
                }
            }
            if (shortcuts.size > 3) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    shortcuts.drop(3).forEach { sc ->
                        Box(
                            Modifier
                                .background(p.cardAlt, RoundedCornerShape(9.dp))
                                .clickable { dir = sc }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(sc.name, fontSize = 12.sp, color = Blue)
                        }
                    }
                }
            }

            // 目录列表
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(p.bg, RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                val parent = dir.parentFile
                if (parent != null && parent.canRead()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { dir = parent }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("↩ 上一级", fontSize = 14.sp, color = p.sub)
                    }
                }
                if (subdirs.isEmpty()) {
                    Text(
                        "无子目录",
                        fontSize = 13.sp,
                        color = p.sub,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                    )
                } else {
                    subdirs.forEach { sub ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { dir = sub }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📁", fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                sub.name,
                                fontSize = 14.sp,
                                color = p.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .background(p.cardAlt, RoundedCornerShape(12.dp))
                        .clickable { onDismiss() }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("取消", fontSize = 14.sp, color = p.sub)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .background(Blue, RoundedCornerShape(12.dp))
                        .clickable { onPick(dir.absolutePath) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("选择此目录", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
