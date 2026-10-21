package com.local.embylite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * 日志的独立入口（通知栏、调试跳转等外部场景仍可用）。
 * 界面逻辑全部在 [LogPage] 中，这里只负责取深/浅色配色并提供 ‹ 返回。
 */
class LogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = when (Settings.getThemeMode(this)) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            LogPage(
                p = if (dark) DarkPalette else LightPalette,
                onBack = { finish() }
            )
        }
    }
}
