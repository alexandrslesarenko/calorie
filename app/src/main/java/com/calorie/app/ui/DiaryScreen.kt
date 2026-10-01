package com.calorie.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
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
import kotlin.math.roundToInt

/** How many days the diary list shows. */
private const val DIARY_DAYS = 120L
private val WEIGHT_PERIODS = listOf(30, 90, 0)

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
        item { WeightSection(a, weights) }
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
private fun WeightSection(a: MainActivity, all: List<WeightMark>) {
    var period by rememberSaveable { mutableIntStateOf(30) }
    val today = LocalDate.now().toEpochDay()
    val shown = if (period == 0) all else all.filter { it.day > today - period }
    val goal = a.prefs.goalKg.takeIf { it > 0 }?.toDouble()
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
            WeightChart(shown, goal)
        }
    }
}

/** Weight line by day; the dashed line is the target weight when it fits a sensible scale. */
@Composable
private fun WeightChart(points: List<WeightMark>, goal: Double?) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 11.sp, color = colors.onSurfaceVariant)
    val fmt = remember { DateTimeFormatter.ofPattern(dayMonthPattern()) }
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val lo0 = points.minOf { it.kg }
        val hi0 = points.maxOf { it.kg }
        // Draw the goal only if it does not flatten the chart: within 5 kg of the data.
        val showGoal = goal != null && goal > lo0 - 5
        val lo = (if (showGoal) minOf(lo0, goal!!) else lo0) - 0.5
        val hi = hi0 + 0.5
        val d0 = points.first().day
        val d1 = points.last().day.coerceAtLeast(d0 + 1)
        val left = 40.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        fun x(day: Long) = left + (day - d0).toFloat() / (d1 - d0) * (size.width - left)
        fun y(kg: Double) = ((hi - kg) / (hi - lo) * bottom).toFloat()
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
    }
}
