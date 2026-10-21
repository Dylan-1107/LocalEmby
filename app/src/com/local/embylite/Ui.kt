package com.local.embylite

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 带形状的按压反馈：先 clip 到按钮的圆角轮廓，再挂 clickable。
 * 这样水波纹/按压高亮会跟随按钮的圆角形状裁剪，
 * 而不是 Compose 默认铺满整个组件的矩形高亮。
 */
fun Modifier.pressable(shape: Shape, onClick: () -> Unit): Modifier =
    this.clip(shape).clickable(onClick = onClick)

/**
 * 下滑收拢标题（HYPERLIGHT / iOS 大标题风格）：
 * 页面顶部悬浮一层，随滚动偏移淡入一个居中的小标题与底色，
 * 原大标题留在滚动内容里自然上滑出屏，视觉上像"移到了中间"。
 * 叠层本身不消费点击，不影响下方内容的滚动手势。
 */
@Composable
internal fun CollapsingTitle(
    title: String,
    scroll: ScrollState,
    p: Palette,
    modifier: Modifier = Modifier
) {
    val fraction = ((scroll.value - 40f) / 100f).coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .background(p.bg.copy(alpha = fraction * 0.96f))
            .statusBarsPadding()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.text.BasicText(
            text = title,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = p.text
            ),
            modifier = Modifier.graphicsLayer {
                alpha = fraction
                translationY = (1f - fraction) * 16.dp.toPx()
            }
        )
    }
}
