package com.calorie.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calorie.app.R
import com.calorie.app.data.DayTotal
import com.calorie.app.data.FoodDb
import com.calorie.app.data.PulsarStatus
import com.calorie.app.data.WeightMark
import com.calorie.app.logic.Burn
import com.calorie.app.logic.Goals
import com.calorie.app.logic.shortText
import java.time.ZoneId
import com.calorie.app.logic.gramsText
import com.calorie.app.logic.kcalText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** How many days the diary list shows. */
private const val DIARY_DAYS = 120L
private val WEIGHT_PERIODS = listOf(30, 90, 0)
/** Eaten / target ratio at the top of the intake chart. */
private const val RATIO_TOP = 1.5
/** Left gutter for the axis labels, the same on both charts so their days line up. */
private val CHART_LEFT = 40.dp

@Composable
fun DiaryScreen(a: MainActivity) {
    val ctx = LocalContext.current
    val dao = remember { FoodDb.get(ctx).dao() }
    val today = LocalDate.now().toEpochDay()
    val totals by remember(today) { dao.totals(today - DIARY_DAYS, today) }.collectAsState(emptyList())
    val weights by remember { dao.weights() }.collectAsState(emptyList())
    val target = remember(weights.lastOrNull(), a.profileVersion, a.pulsar, a.trendData) { a.target(weights.lastOrNull()?.kg) }
    // Each day against its own target: today's would rewrite the balance of the past.
    val saved by remember { dao.dayTargets() }.collectAsState(emptyList())
    fun targetOn(day: Long) = Goals.targetOn(day, today, target?.kcal, saved)
    // Burned kcal per day from Pulsar, for information next to what was eaten.
    val burned = remember(weights.lastOrNull(), a.profileVersion, a.pulsar) {
        val b = a.body(weights.lastOrNull()?.kg)
        if (b == null || a.pulsar.status != PulsarStatus.OK) emptyMap()
        else Burn.perDay(a.pulsar.minutes, b, ZoneId.systemDefault())
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { WeightSection(a, weights, ::targetOn, burned) }
        item {
            // A 7-day average is fairer than one day: weight follows the week, not yesterday's dinner.
            val week = totals.filter { it.day > today - 7 && it.day < today }
            Column(Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.diary_days), style = MaterialTheme.typography.titleSmall)
                if (week.isNotEmpty()) {
                    Text(
                        stringResource(R.string.week_average, (week.sumOf { it.kcal } / week.size).kcalText(), week.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (target != null) {
                        // What the days add up to: the sum is what moves the scale.
                        val balance = week.sumOf { (targetOn(it.day) ?: target.kcal) - it.kcal }
                        val kg = kotlin.math.abs(balance) / Goals.KCAL_PER_KG
                        Text(
                            stringResource(
                                if (balance >= 0) R.string.week_deficit else R.string.week_surplus,
                                kotlin.math.abs(balance).kcalText(), kg.shortText(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (balance >= 0) CalColors.Carbs else CalColors.Over,
                        )
                    }
                }
            }
        }
        if (totals.isEmpty()) {
            item {
                Text(stringResource(R.string.diary_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(totals, key = { it.day }) { t -> DayRow(t, targetOn(t.day), burned[t.day], t.day == today) { a.day = t.day; a.tabRequest = 0 } }
    }
}

@Composable
private fun DayRow(t: DayTotal, target: Int?, burned: Double?, isToday: Boolean, onClick: () -> Unit) {
    val over = target != null && t.kcal > target
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dayTitle(t.day), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (target != null) stringResource(R.string.kcal_of, t.kcal.kcalText(), target) else stringResource(R.string.kcal_n, t.kcal.kcalText()),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (over) CalColors.Over else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (target != null) {
                LinearProgressIndicator(
                    progress = { (t.kcal / target).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (over) CalColors.Over else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
            }
            if (target != null) {
                // Balance against the target: the day's deficit or excess in plain numbers.
                // Today is not over yet, so it shows what is left instead of a verdict.
                val diff = target - t.kcal
                val (text, color) = when {
                    diff < 0 -> R.string.diary_over to CalColors.Over
                    isToday -> R.string.diary_left to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> R.string.diary_under to CalColors.Carbs
                }
                Text(stringResource(text, kotlin.math.abs(diff).kcalText()), style = MaterialTheme.typography.bodyMedium, color = color)
            }
            Text(
                stringResource(R.string.macros_line, t.protein.roundToInt(), t.fat.roundToInt(), t.carbs.roundToInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (burned != null && burned >= 1) {
                Text(
                    stringResource(R.string.diary_burned, burned.kcalText()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WeightSection(a: MainActivity, all: List<WeightMark>, targetOn: (Long) -> Int?, burned: Map<Long, Double>) {
    var period by rememberSaveable { mutableIntStateOf(30) }
    val today = LocalDate.now().toEpochDay()
    val shown = if (period == 0) all else all.filter { it.day > today - period }
    val goal = a.prefs.goalKg.takeIf { it > 0 }?.toDouble()
    // Own query: "all" can reach further back than the diary list below.
    val dao = remember { FoodDb.get(a).dao() }
    val from = shown.firstOrNull()?.day ?: today
    val totals by remember(from, today) { dao.totals(from - 1, today - 1) }.collectAsState(emptyList())
    // Eaten against the day's target, drawn on the next day: the morning weigh-in shows
    // yesterday's food. Today is not over yet and would always look low.
    var touchX by remember { mutableStateOf<Float?>(null) }
    val eaten = totals.mapNotNull { t -> targetOn(t.day)?.takeIf { it > 0 }?.let { t.day + 1 to (t.kcal to it) } }.toMap()
    // Pulsar activity of the same day, shifted the same way; only where there is a bar to put it in.
    val walked = burned.filterValues { it >= 1 }.mapKeys { it.key + 1 }.filterKeys { it in eaten }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.weight_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (all.size >= 2) {
                val first = shown.firstOrNull()
                val last = shown.lastOrNull()
                if (first != null && last != null && first !== last) {
                    val diff = last.kg - first.kg
                    Text(
                        stringResource(R.string.weight_change, (if (diff > 0) "+" else "") + diff.gramsText()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (diff <= 0) CalColors.Carbs else CalColors.Over,
                    )
                }
            }
        }
        FitRow { style ->
            WEIGHT_PERIODS.forEach { p ->
                FilterChip(
                    selected = period == p,
                    onClick = { period = p },
                    label = { Text(if (p == 0) stringResource(R.string.period_all) else stringResource(R.string.period_days, p), maxLines = 1, style = style) },
                )
            }
        }
        if (shown.size < 2) {
            Text(stringResource(R.string.weight_chart_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            WeightChart(shown, goal, eaten, walked, touchX) { touchX = it }
            if (eaten.isNotEmpty()) {
                IntakeChart(shown, eaten, walked, touchX) { touchX = it }
                Text(stringResource(R.string.weight_chart_bars), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (shown.any { it.day in walked }) {
                    Text(stringResource(R.string.weight_chart_burned), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Touch on a chart: the x of the finger while it is down, null after. Only read, not consumed:
 * a swipe over the chart still scrolls the list and the tabs. After a long press it follows
 * the finger.
 */
private fun Modifier.chartTouch(key: Any, onTouch: (Float?) -> Unit) = this
    .pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            onTouch(down.position.x)
            while (true) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed || c.isConsumed) break
                if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
            onTouch(null)
        }
    }
    .pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { onTouch(it.x) },
            onDragEnd = { onTouch(null) },
            onDragCancel = { onTouch(null) },
            onDrag = { change, _ -> onTouch(change.position.x) },
        )
    }

/** Day range of the charts: both map days to x the same way. */
private fun dayRange(points: List<WeightMark>) = points.first().day to points.last().day.coerceAtLeast(points.first().day + 1)

/**
 * Weight line by day; the dashed line is the target weight when it fits a sensible scale.
 * A touch here or on the intake chart below shows the date, weight and the previous day's kcal
 * (`eaten`) of the nearest point, as in Pulsar.
 */
@Composable
private fun WeightChart(
    points: List<WeightMark>, goal: Double?, eaten: Map<Long, Pair<Double, Int>>, walked: Map<Long, Double>, touchX: Float?, onTouch: (Float?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 11.sp, color = colors.onSurfaceVariant)
    val tipStyle = TextStyle(fontSize = 12.sp, color = colors.inverseOnSurface)
    val fmt = remember { DateTimeFormatter.ofPattern(dayMonthPattern()) }
    // Formatted outside the Canvas: stringResource is not available in a DrawScope.
    val tips = points.associate { p ->
        val w = stringResource(R.string.weight_last, p.kg.gramsText(), LocalDate.ofEpochDay(p.day).format(fmt))
        val e = eaten[p.day]
        val k = walked[p.day]
        p.day to buildString {
            append(w)
            if (e != null) append("\n").append(stringResource(R.string.kcal_of, e.first.kcalText(), e.second))
            if (k != null) append("\n").append(stringResource(R.string.diary_burned, k.kcalText()))
        }
    }
    // The latest weight, large and faint in the middle: the number the chart is about.
    val latest = points.last().kg.gramsText()
    val bigStyle = TextStyle(fontSize = 100.sp, fontWeight = FontWeight.Bold, color = colors.onSurface.copy(alpha = 0.2f))
    Canvas(Modifier.fillMaxWidth().height(180.dp).chartTouch(points, onTouch)) {
        val lo0 = points.minOf { it.kg }
        val hi0 = points.maxOf { it.kg }
        // Draw the goal only if it does not flatten the chart: within 5 kg of the data.
        val showGoal = goal != null && goal > lo0 - 5
        val lo = (if (showGoal) minOf(lo0, goal!!) else lo0) - 0.5
        val hi = hi0 + 0.5
        val (d0, d1) = dayRange(points)
        val left = CHART_LEFT.toPx()
        val bottom = size.height - 18.dp.toPx()
        fun x(day: Long) = left + (day - d0).toFloat() / (d1 - d0) * (size.width - left)
        fun y(kg: Double) = ((hi - kg) / (hi - lo) * bottom).toFloat()
        // As tall as the plot, unless the width runs out first: measured once, then scaled.
        val probe = measurer.measure(latest, bigStyle)
        val fit = minOf(bottom / probe.size.height, (size.width - left) / probe.size.width)
        val big = measurer.measure(latest, bigStyle.copy(fontSize = bigStyle.fontSize * fit))
        drawText(big, topLeft = Offset(left + (size.width - left - big.size.width) / 2, (bottom - big.size.height) / 2))
        for (v in listOf(hi - 0.5, lo + 0.5)) {
            val tl = measurer.measure(v.gramsText(), label)
            drawText(tl, topLeft = Offset(0f, (y(v) - tl.size.height / 2).coerceIn(0f, bottom - tl.size.height)))
            drawLine(colors.outlineVariant, Offset(left, y(v)), Offset(size.width, y(v)))
        }
        if (showGoal) {
            drawLine(CalColors.Carbs, Offset(left, y(goal!!)), Offset(size.width, y(goal)), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
        }
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.day), y(p.kg)) else path.lineTo(x(p.day), y(p.kg)) }
        drawPath(path, colors.primary, style = Stroke(3.dp.toPx()))
        points.forEach { drawCircle(colors.primary, 3.5.dp.toPx(), Offset(x(it.day), y(it.kg))) }
        for ((day, alignEnd) in listOf(d0 to false, points.last().day to true)) {
            val tl = measurer.measure(LocalDate.ofEpochDay(day).format(fmt), label)
            val tx = if (alignEnd) size.width - tl.size.width else left
            drawText(tl, topLeft = Offset(tx, size.height - tl.size.height))
        }

        val tx = touchX
        if (tx != null) {
            val p = points.minBy { abs(x(it.day) - tx) }
            val px = x(p.day)
            val py = y(p.kg)
            drawLine(colors.onSurfaceVariant.copy(alpha = 0.6f), Offset(px, 0f), Offset(px, bottom), 1.dp.toPx())
            drawCircle(colors.surface, 6.dp.toPx(), Offset(px, py))
            drawCircle(colors.primary, 4.dp.toPx(), Offset(px, py))
            val tl = measurer.measure(tips.getValue(p.day), tipStyle)
            val pad = 6.dp.toPx()
            val w = tl.size.width + pad * 2
            val h = tl.size.height + pad * 2
            // At the top, in the half away from the finger: next to the point it ends up under it.
            val bx = if (tx > left + (size.width - left) / 2) left else (size.width - w).coerceAtLeast(left)
            val by = 0f
            drawRoundRect(colors.inverseSurface, Offset(bx, by), Size(w, h), CornerRadius(6.dp.toPx()))
            drawText(tl, topLeft = Offset(bx + pad, by + pad))
        }
    }
}

/**
 * Eaten against the target, a bar per weigh-in day for the day before it (`eaten`), under the
 * weight chart and on the same days, so a flat week can be checked against the plates. The top
 * is 150% of the target; the dotted line is the target. Pulsar activity of that day (`walked`)
 * is the top part of the bar: what is left below it is the food it did not cover.
 */
@Composable
private fun IntakeChart(points: List<WeightMark>, eaten: Map<Long, Pair<Double, Int>>, walked: Map<Long, Double>, touchX: Float?, onTouch: (Float?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 11.sp, color = colors.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(72.dp).chartTouch(points, onTouch)) {
        val (d0, d1) = dayRange(points)
        val left = CHART_LEFT.toPx()
        val bottom = size.height
        fun x(day: Long) = left + (day - d0).toFloat() / (d1 - d0) * (size.width - left)
        fun y(ratio: Double) = (bottom - ratio.coerceIn(0.0, RATIO_TOP) / RATIO_TOP * bottom).toFloat()
        val step = (size.width - left) / (d1 - d0 + 1)
        for ((day, e) in eaten) {
            if (day < d0 || day > d1) continue
            val top = y(e.first / e.second)
            val bx0 = (x(day) - step / 2).coerceAtLeast(left)
            val bx1 = (x(day) + step / 2).coerceAtMost(size.width)
            val c = if (e.first > e.second) CalColors.Over else CalColors.Carbs
            val w = (bx1 - bx0 - 1f).coerceAtLeast(1f)
            drawRect(c.copy(alpha = 0.6f), Offset(bx0, top), Size(w, bottom - top))
            // Inside the bar: more burned than eaten still fills only the bar.
            walked[day]?.let { k ->
                val low = y((e.first - minOf(k, e.first)) / e.second)
                if (low > top) drawRect(CalColors.Protein.copy(alpha = 0.85f), Offset(bx0, top), Size(w, low - top))
            }
        }
        drawLine(colors.outlineVariant, Offset(left, bottom), Offset(size.width, bottom))
        val ty = y(1.0)
        drawLine(colors.onSurfaceVariant, Offset(left, ty), Offset(size.width, ty), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 6f)))
        val tl = measurer.measure("100%", label)
        drawText(tl, topLeft = Offset(0f, ty - tl.size.height / 2))

        val tx = touchX
        if (tx != null) {
            val px = x(points.minBy { abs(x(it.day) - tx) }.day)
            drawLine(colors.onSurfaceVariant.copy(alpha = 0.6f), Offset(px, 0f), Offset(px, bottom), 1.dp.toPx())
        }
    }
}
