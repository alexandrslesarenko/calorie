package com.calorie.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calorie.app.R
import com.calorie.app.data.Entry
import com.calorie.app.data.FoodDb
import com.calorie.app.data.Meal
import com.calorie.app.data.Pending
import com.calorie.app.data.PulsarStatus
import com.calorie.app.data.Source
import com.calorie.app.logic.Burn
import java.time.Instant
import java.time.ZoneId
import com.calorie.app.data.WeightMark
import com.calorie.app.logic.Goals
import com.calorie.app.logic.Limit
import com.calorie.app.logic.Nutrients
import com.calorie.app.logic.Target
import com.calorie.app.logic.gramsText
import com.calorie.app.logic.shortText
import com.calorie.app.logic.kcalText
import com.calorie.app.logic.sum
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Composable
fun TodayScreen(a: MainActivity) {
    val ctx = LocalContext.current
    val dao = remember { FoodDb.get(ctx).dao() }
    val entries by remember(a.day) { dao.entries(a.day) }.collectAsState(emptyList())
    val lastWeight by remember { dao.lastWeight() }.collectAsState(null)
    val current = remember(lastWeight, a.profileVersion, a.pulsar, a.trendData) { a.target(lastWeight?.kg) }
    // A past day keeps the kcal target it had; the rest (protein, limits) is today's.
    val saved by remember { dao.dayTargets() }.collectAsState(emptyList())
    val target = current?.let { t -> t.copy(kcal = Goals.targetOn(a.day, LocalDate.now().toEpochDay(), t.kcal, saved) ?: t.kcal) }
    val eaten = entries.map { it.nutrients }.sum()
    var weightDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DayHeader(a)
        if (!a.prefs.disclaimerAccepted) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.disclaimer_short), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = {
                        a.prefs.disclaimerAccepted = true
                        a.profileVersion++
                    }) { Text(stringResource(R.string.disclaimer_ok)) }
                }
            }
        }
        if (target == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.profile_needed))
                    Button(onClick = { a.openSettings(SettingsPage.PROFILE) }) { Text(stringResource(R.string.profile_fill)) }
                }
            }
        }
        if (target != null && !a.prefs.goalSeen) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.goal_needed, a.prefs.pace.kgPerWeek.shortText()))
                    Button(onClick = { a.openSettings(SettingsPage.GOAL) }) { Text(stringResource(R.string.goal_set)) }
                }
            }
        }
        Summary(eaten, target)
        FilledTonalButton(onClick = { a.overlay = Overlay.AddMenu }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.add_food))
        }
        PendingCard(a)
        Meal.entries.forEach { meal ->
            val list = entries.filter { Meal.byKey(it.meal) == meal }
            if (list.isNotEmpty()) MealCard(a, meal, list)
        }
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.day_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ActivityCard(a, lastWeight?.kg)
        WeightCard(lastWeight, a.day) { weightDialog = true }
    }
    if (weightDialog) WeightDialog(a, lastWeight?.kg) { weightDialog = false }
}

/** Date with arrows: yesterday's dinner can be added later. No going past today. */
@Composable
private fun DayHeader(a: MainActivity) {
    val today = LocalDate.now().toEpochDay()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { a.day-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.day_prev)) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (a.day == today) {
                Text(dayTitle(a.day), style = MaterialTheme.typography.titleMedium)
            } else {
                // Not today: a bright badge, otherwise food quietly lands on the old day.
                // Tapping it goes back to today.
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(CalColors.Warn)
                        .clickable { a.day = today }.padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(dayTitle(a.day), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                    Spacer(Modifier.size(8.dp))
                    Icon(Icons.Filled.Refresh, stringResource(R.string.back_to_today), tint = Color.Black, modifier = Modifier.size(20.dp))
                }
            }
        }
        IconButton(onClick = { a.day++ }, enabled = a.day < today) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.day_next))
        }
    }
}

/** "Today", "Yesterday" or a date. inline - inside a phrase, so the word goes lowercase. */
@Composable
fun dayTitle(day: Long, inline: Boolean = false): String {
    val today = LocalDate.now().toEpochDay()
    val date = LocalDate.ofEpochDay(day)
    val word = when (day) {
        today -> stringResource(R.string.today)
        today - 1 -> stringResource(R.string.yesterday)
        else -> return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
    return if (inline) word.replaceFirstChar { it.lowercase() } else word
}

/** Calorie ring: what is left of the target, with macro bars below. */
@Composable
private fun Summary(eaten: Nutrients, target: Target?) {
    val colors = MaterialTheme.colorScheme
    val over = target != null && eaten.kcal > target.kcal
    val ring = if (over) CalColors.Over else colors.primary
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            val track = colors.surfaceVariant
            val share = if (target == null) 0f else (eaten.kcal / target.kcal).toFloat().coerceIn(0f, 1f)
            Canvas(Modifier.size(180.dp)) {
                val w = 16.dp.toPx()
                val tl = Offset(w / 2, w / 2)
                val sz = Size(size.width - w, size.height - w)
                drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
                if (share > 0f) drawArc(ring, -90f, 360f * share, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (target != null) {
                    val left = target.kcal - eaten.kcal
                    Text(
                        kotlin.math.abs(left).kcalText(),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (over) CalColors.Over else colors.onSurface,
                    )
                    Text(
                        stringResource(if (over) R.string.kcal_over else R.string.kcal_left),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    Text(eaten.kcal.kcalText(), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.kcal_unit), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
        if (target != null) {
            Text(
                stringResource(R.string.eaten_of, eaten.kcal.kcalText(), target.kcal),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MacroBar(stringResource(R.string.protein), eaten.protein, target?.proteinG, CalColors.Protein, Modifier.weight(1f))
            MacroBar(stringResource(R.string.fat), eaten.fat, null, CalColors.Fat, Modifier.weight(1f))
            MacroBar(stringResource(R.string.carbs), eaten.carbs, null, CalColors.Carbs, Modifier.weight(1f))
        }
    }
}

/**
 * Protein has a target and a bar (it matters during a deficit); fat and carbs show only
 * what was eaten: separate targets for them add little for weight loss.
 */
@Composable
private fun MacroBar(label: String, grams: Double, goal: Int?, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (goal != null) stringResource(R.string.grams_of, grams.roundToInt(), goal) else stringResource(R.string.grams_n, grams.roundToInt()),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
        )
        LinearProgressIndicator(
            progress = { if (goal != null && goal > 0) (grams / goal).toFloat().coerceIn(0f, 1f) else if (grams > 0) 1f else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun MealCard(a: MainActivity, meal: Meal, list: List<Entry>) {
    val sum = list.map { it.nutrients }.sum()
    val hue = CalColors.meal(meal)
    // A light tint of the meal hue: the cards stop merging into one block.
    val tint = hue.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHighest)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = tint)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(hue))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(meal.label), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.kcal_n, sum.kcal.kcalText()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            list.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().clickable {
                        a.overlay = Overlay.Review(listOf(ReviewItem(e.toDraft())), Source.byKey(e.source), editing = e)
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.entry_line, e.grams.gramsText(), e.protein.roundToInt(), e.fat.roundToInt(), e.carbs.roundToInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(e.kcal.kcalText(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * Claude requests from the retry queue, for any day: waiting ones with the next try time,
 * answered ones to check and add, failed ones to retry or delete.
 */
@Composable
private fun PendingCard(a: MainActivity) {
    val ctx = LocalContext.current
    val list by remember { FoodDb.get(ctx).dao().pendingAll() }.collectAsState(emptyList())
    if (list.isEmpty()) return
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val zone = remember { ZoneId.systemDefault() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.pending_title), style = MaterialTheme.typography.titleSmall)
            list.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val title = p.text.ifBlank { stringResource(R.string.way_photo) }
                val (status, color) = when (p.state) {
                    Pending.READY -> stringResource(R.string.pending_ready) to MaterialTheme.colorScheme.primary
                    Pending.FAILED -> stringResource(R.string.failed_title) to CalColors.Warn
                    // A past time means the try is held back (Doze, no network), not that it is late.
                    else -> (if (p.nextTry <= System.currentTimeMillis()) stringResource(R.string.pending_soon)
                        else stringResource(R.string.pending_at, Instant.ofEpochMilli(p.nextTry).atZone(zone).format(fmt))) to
                        MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(
                    Modifier.fillMaxWidth().clickable {
                        if (p.state == Pending.READY) a.openPendingReview(p) else a.overlay = Overlay.Queued(p.id)
                    }.padding(vertical = 8.dp)
                ) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = color)
                }
            }
        }
    }
}

/** Mode name for a Pulsar session. */
@Composable
fun modeLabel(mode: String) = stringResource(if (mode == Burn.TRAINING) R.string.mode_training else R.string.mode_walk)

/**
 * Walks and workouts of the selected day from Pulsar. Shown for information: the burned
 * kcal reach the target through the two-week average, not by adding to today.
 */
@Composable
private fun ActivityCard(a: MainActivity, weightKg: Double?) {
    if (a.pulsar.status != PulsarStatus.OK) return
    val body = remember(weightKg, a.profileVersion) { a.body(weightKg) } ?: return
    val zone = remember { ZoneId.systemDefault() }
    val sessions = remember(a.pulsar, a.day, body) {
        Burn.sessions(a.pulsar.minutes.filter { Burn.day(it.minute, zone) == a.day }, body)
    }
    if (sessions.isEmpty()) return
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(fmt)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.activity), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.kcal_n, sessions.sumOf { it.kcal }.kcalText()), style = MaterialTheme.typography.titleSmall)
            }
            sessions.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(modeLabel(s.mode))
                        Text(
                            stringResource(R.string.session_line, hm(s.start), hm(s.end), s.minutes, s.avgBpm),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(s.kcal.kcalText(), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(
                stringResource(R.string.activity_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeightCard(last: WeightMark?, day: Long, onRecord: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.weight_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (last == null) stringResource(R.string.weight_none)
                    else stringResource(R.string.weight_last, last.kg.gramsText(), dayTitle(last.day, inline = true)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Weight goes to the selected day, so a missed morning can be filled in.
            OutlinedButton(onClick = onRecord) {
                Text(stringResource(if (last?.day == day) R.string.weight_fix else R.string.weight_record))
            }
        }
    }
}

@Composable
private fun WeightDialog(a: MainActivity, last: Double?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(last?.gramsText() ?: "") }
    val kg = parseNumber(text)?.takeIf { it in 30.0..300.0 }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.weight_on, dayTitle(a.day))) },
        text = {
            OutlinedTextField(
                text, { text = it },
                label = { Text(stringResource(R.string.weight_kg)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(enabled = kg != null, onClick = {
                val day = a.day
                scope.launch {
                    FoodDb.get(ctx).dao().putWeight(WeightMark(day, kg!!))
                    // The target depends on weight.
                    a.profileVersion++
                }
                onClose()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Number from a text field: comma and dot both work. */
fun parseNumber(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

/** Caption under the target: what limits it. */
@Composable
fun limitText(t: Target): String? = when (t.limit) {
    Limit.NONE -> null
    Limit.FLOOR -> stringResource(R.string.limit_floor)
    Limit.RATE -> stringResource(R.string.limit_rate)
    Limit.UNDERWEIGHT -> stringResource(R.string.limit_underweight)
    Limit.GOAL_REACHED -> stringResource(R.string.limit_goal)
}
