package com.local.embylite

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 底部导航栏 —— 移植自 HyperLight（KiminonawaResa/HyperLight）的设置界面底栏。
 *
 * HyperLight 的 Compose 层经 R8 混淆无法直接提取调用代码，但其底栏组件本体是
 * MiuiX（Apache-2.0）的 `FloatingNavigationBar` + `FloatingNavigationBarItem`
 * （miuix 0.8.8 起就在 top.yukonga.miuix.kmp.basic 包内），模糊质感则是 Haze 同款思路。
 * 当前形态（按用户要求迭代）：
 *   - IconOnly 纯图标模式：无文字，图标 28dp 居中紧凑胶囊；
 *   - 选中态用「中粗描边」（2.2~2.4f，原 3.2~3.6f 太粗）；
 *   - 液态玻璃：Haze（dev.chrisbanes.haze:haze:1.7.3，Apache-2.0）真背景模糊——
 *     MainActivity 在页面区包了 hazeSource，这里通过 FloatingNavigationBar 的
 *     modifier 参数挂 hazeEffect（组件内部 clip 成胶囊形，模糊被裁进胶囊轮廓）。
 *     Android 12+ 真模糊（RenderEffect），更低版本自动退化为 fallbackTint 半透明底。
 *     item 着色读 MiuixTheme.colorScheme.onSurfaceContainer，因此外面包一层
 *     MiuixTheme，把 Embylite 的明暗 Palette 映射到 miuix 的浅色/深色方案上。
 *
 * 历史备注：此前自绘版本不用模糊，是因为当时试 miuix-blur 的 layerBackdrop 时把
 * 被模糊表面自己也包进了捕获范围（自引用导致 SIGSEGV）。Haze 的 hazeSource /
 * hazeEffect 是兄弟节点分工，不存在该自引用问题。
 */
@Composable
internal fun AppNavBar(
    tab: Int,
    p: Palette,
    haze: HazeState,
    onSelect: (Int) -> Unit
) {
    val labels = listOf("首页", "视频信息", "日志", "设置")
    val icons = listOf(IconHome, IconInfo, IconLog, IconSettings)
    val iconsOn = listOf(IconHomeFilled, IconInfoFilled, IconLogFilled, IconSettingsFilled)
    val dark = p.bg == Color.Black

    MiuixTheme(
        colors = if (dark) darkColorScheme() else lightColorScheme()
    ) {
        FloatingNavigationBar(
            // 本体透明，玻璃感全部交给 hazeEffect 的模糊底
            color = Color.Transparent,
            mode = FloatingNavigationBarDisplayMode.IconOnly,
            modifier = Modifier.hazeEffect(
                state = haze,
                style = HazeStyle(
                    backgroundColor = p.bg,
                    // 淡淡的底色 tint：模糊之上再叠一层玻璃材质色
                    tints = listOf(
                        HazeTint(p.cardAlt.copy(alpha = if (dark) 0.68f else 0.62f))
                    ),
                    blurRadius = 24.dp,
                    noiseFactor = 0.12f,
                    // Android 12 以下无 RenderEffect，退化为半透明玻璃底
                    fallbackTint = HazeTint(p.cardAlt.copy(alpha = 0.9f))
                )
            )
        ) {
            labels.forEachIndexed { i, label ->
                FloatingNavigationBarItem(
                    selected = tab == i,
                    onClick = { onSelect(i) },
                    // 选中中粗描边、未选中细描边
                    icon = if (tab == i) iconsOn[i] else icons[i],
                    label = label // 无障碍朗读用，IconOnly 模式不显示
                )
            }
        }
    }
}

/* ---------- 图标：手写矢量（沿用原版，未引入 miuix-icons 依赖） ---------- */

private fun lineIcon(
    name: String,
    strokeWidth: Float,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block
        )
    }.build()

/* 每个图标两个版本：细描边（未选中 1.7f）+ 中粗描边（选中 2.2~2.4f）。
 * 注：选中版曾用 3.2~3.6f 模拟实心，用户反馈太粗，已调细。 */

/** 首页：房子。 */
val IconHome: ImageVector by lazy { lineIcon("Home", 1.7f) {
    moveTo(3.4f, 10.6f); lineTo(12f, 3.4f); lineTo(20.6f, 10.6f)
    lineTo(20.6f, 20.4f); lineTo(14.6f, 20.4f); lineTo(14.6f, 14.4f)
    lineTo(9.4f, 14.4f); lineTo(9.4f, 20.4f); lineTo(3.4f, 20.4f); close()
} }

val IconHomeFilled: ImageVector by lazy { lineIcon("HomeFilled", 2.4f) {
    moveTo(3.4f, 10.6f); lineTo(12f, 3.4f); lineTo(20.6f, 10.6f)
    lineTo(20.6f, 20.4f); lineTo(14.6f, 20.4f); lineTo(14.6f, 14.4f)
    lineTo(9.4f, 14.4f); lineTo(9.4f, 20.4f); lineTo(3.4f, 20.4f); close()
} }

/** 视频信息：圆圈 i。 */
val IconInfo: ImageVector by lazy { lineIcon("Info", 1.7f) {
    moveTo(12.0f, 4.0f)
    arcTo(8.0f, 8.0f, 0.0f, false, true, 12.0f, 20.0f)
    arcTo(8.0f, 8.0f, 0.0f, false, true, 12.0f, 4.0f)
    close()
    moveTo(11.9f, 8.3f); lineTo(12.1f, 8.3f)
    moveTo(12.0f, 11.4f); lineTo(12.0f, 16.2f)
} }

val IconInfoFilled: ImageVector by lazy { lineIcon("InfoFilled", 2.2f) {
    moveTo(12.0f, 4.0f)
    arcTo(8.0f, 8.0f, 0.0f, false, true, 12.0f, 20.0f)
    arcTo(8.0f, 8.0f, 0.0f, false, true, 12.0f, 4.0f)
    close()
    moveTo(11.9f, 8.3f); lineTo(12.1f, 8.3f)
    moveTo(12.0f, 11.4f); lineTo(12.0f, 16.2f)
} }

/** 日志：三条横线。 */
val IconLog: ImageVector by lazy { lineIcon("Log", 1.7f) {
    moveTo(4.2f, 6.4f); lineTo(19.8f, 6.4f)
    moveTo(4.2f, 12.0f); lineTo(19.8f, 12.0f)
    moveTo(4.2f, 17.6f); lineTo(13.2f, 17.6f)
} }

val IconLogFilled: ImageVector by lazy { lineIcon("LogFilled", 2.4f) {
    moveTo(4.2f, 6.4f); lineTo(19.8f, 6.4f)
    moveTo(4.2f, 12.0f); lineTo(19.8f, 12.0f)
    moveTo(4.2f, 17.6f); lineTo(13.2f, 17.6f)
} }

/** 设置：两条滑杆。 */
val IconSettings: ImageVector by lazy { lineIcon("Settings", 1.7f) {
    moveTo(3.8f, 7.6f); lineTo(20.2f, 7.6f)
    moveTo(3.8f, 16.4f); lineTo(20.2f, 16.4f)
    moveTo(15.6f, 5.0f); lineTo(18.2f, 5.0f); lineTo(18.2f, 10.2f); lineTo(15.6f, 10.2f); close()
    moveTo(5.8f, 13.8f); lineTo(8.4f, 13.8f); lineTo(8.4f, 19.0f); lineTo(5.8f, 19.0f); close()
} }

val IconSettingsFilled: ImageVector by lazy { lineIcon("SettingsFilled", 2.4f) {
    moveTo(3.8f, 7.6f); lineTo(20.2f, 7.6f)
    moveTo(3.8f, 16.4f); lineTo(20.2f, 16.4f)
    moveTo(15.6f, 5.0f); lineTo(18.2f, 5.0f); lineTo(18.2f, 10.2f); lineTo(15.6f, 10.2f); close()
    moveTo(5.8f, 13.8f); lineTo(8.4f, 13.8f); lineTo(8.4f, 19.0f); lineTo(5.8f, 19.0f); close()
} }
