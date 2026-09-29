package com.calorie.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * A row of buttons that tries to fit on one line: first labels of normal size,
 * then smaller, then smaller still. If it still does not fit (a very large system font),
 * buttons wrap to the next line instead of shrinking to unreadable.
 * content receives the label style to apply to the Text inside the buttons.
 */
@Composable
fun FitRow(spacing: Dp = 8.dp, content: @Composable (TextStyle) -> Unit) {
    val styles = listOf(MaterialTheme.typography.labelLarge, MaterialTheme.typography.labelMedium, MaterialTheme.typography.labelSmall)
    SubcomposeLayout { constraints ->
        val gap = spacing.roundToPx()
        val limit = Constraints(maxWidth = constraints.maxWidth)
        var items: List<Placeable> = emptyList()
        for ((i, style) in styles.withIndex()) {
            items = subcompose(i) { content(style) }.map { it.measure(limit) }
            val total = items.sumOf { it.width } + gap * (items.size - 1).coerceAtLeast(0)
            if (total <= constraints.maxWidth) break
        }
        var x = 0
        var y = 0
        var lineHeight = 0
        val positions = items.map { p ->
            if (x > 0 && x + p.width > constraints.maxWidth) {
                x = 0
                y += lineHeight + gap
                lineHeight = 0
            }
            val at = IntOffset(x, y)
            x += p.width + gap
            lineHeight = maxOf(lineHeight, p.height)
            at
        }
        val width = if (y == 0) (x - gap).coerceAtLeast(0) else constraints.maxWidth
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), y + lineHeight) {
            items.forEachIndexed { i, p -> p.placeRelative(positions[i]) }
        }
    }
}

/**
 * Single-line text that shrinks when space is short (down to 10 sp) instead of
 * wrapping. Style and color come from the environment, like a regular Text.
 */
@Composable
fun OneLineText(text: String, modifier: Modifier = Modifier) {
    val style = LocalTextStyle.current
    val max = if (style.fontSize.isSp) style.fontSize else 14.sp
    BasicText(
        text,
        modifier,
        style = style.copy(color = LocalContentColor.current),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = max),
    )
}

/**
 * Tile title: one line with shrinking while the font is at least 12 sp, otherwise two
 * lines at normal size. Long titles (Japanese ones among them) do not fit on one line even
 * at 10 sp, and a truncated title is worse than a wrapped one.
 */
@Composable
fun TileTitle(text: String, modifier: Modifier = Modifier) {
    val style = LocalTextStyle.current
    val measurer = rememberTextMeasurer()
    // Layout, not BoxWithConstraints: tiles are measured via IntrinsicSize, which SubcomposeLayout does not support.
    Layout(
        content = {
            OneLineText(text)
            Text(text, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val fits = !constraints.hasBoundedWidth ||
            measurer.measure(text, style.copy(fontSize = 12.sp), maxLines = 1, softWrap = false).size.width <= constraints.maxWidth
        val p = measurables[if (fits) 0 else 1].measure(constraints.copy(minHeight = 0))
        layout(p.width.coerceAtLeast(constraints.minWidth), p.height) { p.place(0, 0) }
    }
}

/** Day and month in the locale's usual order: 24.09 in Russian, 09/24 in English. */
fun dayMonthPattern(): String =
    android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "ddMM")
