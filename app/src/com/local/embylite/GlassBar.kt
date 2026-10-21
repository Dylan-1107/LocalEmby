package com.local.embylite

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.basic.Icon

/**
 * 底部导航栏：图标在上（选中为实心）、小字在下，排布与 LSPosed 一致。
 *
 * 关于柔光玻璃：miuix-blur 的 `layerBackdrop` 只能捕获**自身 composable** 所绘制
 * 的内容，一旦把被模糊的表面自己也包进捕获范围，采样源与被采样者就形成自引用，
 * RenderThread 会无限递归并 SIGSEGV（实测崩溃）。因此这里不用全屏捕获，
 * 改用「半透明底 + 玻璃高光描边」的等效观感：不依赖 RuntimeShader，任何设备都稳。
 */
@Composable
internal fun AppNavBar(
    tab: Int,
    p: Palette,
    onSelect: (Int) -> Unit
) {
    val labels = listOf("首页", "弹幕", "日志", "设置")
    val icons = listOf(IconHome, IconDanmu, IconLog, IconSettings)
    val iconsOn = listOf(IconHomeFilled, IconDanmuFilled, IconLogFilled, IconSettingsFilled)
    val barShape = RoundedCornerShape(50)
    val dark = p.bg == Color.Black

    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 16.dp)
    ) {
        // 底板：半透明让页面内容透出来，配上下不对称的玻璃高光描边
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(p.cardAlt.copy(alpha = if (dark) 0.88f else 0.82f), barShape)
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) 0.20f else 0.85f),
                            Color.White.copy(alpha = if (dark) 0.05f else 0.35f)
                        )
                    ),
                    shape = barShape
                )
        )

        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            labels.forEachIndexed { i, label ->
                NavItem(
                    label = label,
                    icon = icons[i],
                    iconOn = iconsOn[i],
                    selected = tab == i,
                    p = p,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(i) }
                )
            }
        }
    }
}

/** 单项：图标在上（选中为实心）、小字在下。 */
@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    iconOn: ImageVector,
    selected: Boolean,
    p: Palette,
    modifier: Modifier,
    onClick: () -> Unit
) {
    // 图标用 Crossfade 做"空心 ↔ 实心"的平滑替换，颜色同步渐变
    val iconColor by animateColorAsState(
        targetValue = if (selected) Blue else p.sub,
        animationSpec = tween(240, easing = SinOutEasing),
        label = "navIconColor"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) Blue else p.sub,
        animationSpec = tween(240, easing = SinOutEasing),
        label = "navTextColor"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.10f else 1f,
        animationSpec = tween(260, easing = SinOutEasing),
        label = "navIconScale"
    )

    Column(
        modifier
            .fillMaxSize()
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (selected) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(Blue.copy(alpha = 0.14f), RoundedCornerShape(50))
                )
            }
            androidx.compose.animation.Crossfade(
                targetState = selected,
                animationSpec = tween(180),
                label = "navIcon"
            ) { on ->
                Icon(
                    imageVector = if (on) iconOn else icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(23.dp * iconScale)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            color = textColor,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

/* ---------- 图标：手写矢量（未引入 miuix-icons 依赖） ---------- */

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

/* 每个图标两个版本：细描边（未选中）+ 粗描边（选中，视觉上接近实心）。 */

/** 首页：房子。 */
val IconHome: ImageVector by lazy { lineIcon("Home", 1.7f) {
    moveTo(3.4f, 10.6f); lineTo(12f, 3.4f); lineTo(20.6f, 10.6f)
    lineTo(20.6f, 20.4f); lineTo(14.6f, 20.4f); lineTo(14.6f, 14.4f)
    lineTo(9.4f, 14.4f); lineTo(9.4f, 20.4f); lineTo(3.4f, 20.4f); close()
} }

val IconHomeFilled: ImageVector by lazy { lineIcon("HomeFilled", 3.6f) {
    moveTo(3.4f, 10.6f); lineTo(12f, 3.4f); lineTo(20.6f, 10.6f)
    lineTo(20.6f, 20.4f); lineTo(14.6f, 20.4f); lineTo(14.6f, 14.4f)
    lineTo(9.4f, 14.4f); lineTo(9.4f, 20.4f); lineTo(3.4f, 20.4f); close()
} }

/** 弹幕：带省略号的气泡。 */
val IconDanmu: ImageVector by lazy { lineIcon("Danmu", 1.7f) {
    moveTo(4.2f, 4.4f); lineTo(19.8f, 4.4f); lineTo(19.8f, 16.0f)
    lineTo(9.8f, 16.0f); lineTo(5.6f, 19.8f); lineTo(5.6f, 16.0f); lineTo(4.2f, 16.0f); close()
    moveTo(8.6f, 10.2f); lineTo(8.7f, 10.2f)
    moveTo(11.9f, 10.2f); lineTo(12.1f, 10.2f)
    moveTo(15.3f, 10.2f); lineTo(15.5f, 10.2f)
} }

val IconDanmuFilled: ImageVector by lazy { lineIcon("DanmuFilled", 3.6f) {
    moveTo(4.2f, 4.4f); lineTo(19.8f, 4.4f); lineTo(19.8f, 16.0f)
    lineTo(9.8f, 16.0f); lineTo(5.6f, 19.8f); lineTo(5.6f, 16.0f); lineTo(4.2f, 16.0f); close()
} }

/** 日志：三条横线。 */
val IconLog: ImageVector by lazy { lineIcon("Log", 1.7f) {
    moveTo(4.2f, 6.4f); lineTo(19.8f, 6.4f)
    moveTo(4.2f, 12.0f); lineTo(19.8f, 12.0f)
    moveTo(4.2f, 17.6f); lineTo(13.2f, 17.6f)
} }

val IconLogFilled: ImageVector by lazy { lineIcon("LogFilled", 3.6f) {
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

val IconSettingsFilled: ImageVector by lazy { lineIcon("SettingsFilled", 3.6f) {
    moveTo(3.8f, 7.6f); lineTo(20.2f, 7.6f)
    moveTo(3.8f, 16.4f); lineTo(20.2f, 16.4f)
    moveTo(15.6f, 5.0f); lineTo(18.2f, 5.0f); lineTo(18.2f, 10.2f); lineTo(15.6f, 10.2f); close()
    moveTo(5.8f, 13.8f); lineTo(8.4f, 13.8f); lineTo(8.4f, 19.0f); lineTo(5.8f, 19.0f); close()
} }
