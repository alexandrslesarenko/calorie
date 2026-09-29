package com.calorie.app.ui

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.calorie.app.R
import com.calorie.app.ai.AiException
import com.calorie.app.ai.AiFailure
import com.calorie.app.ai.ClaudeFood
import com.calorie.app.data.ApiKeyStore
import com.calorie.app.data.FoodDb
import com.calorie.app.data.Prefs
import com.calorie.app.data.WeightMark
import com.calorie.app.logic.Activity
import com.calorie.app.logic.Goals
import com.calorie.app.logic.Pace
import com.calorie.app.logic.Pricing
import com.calorie.app.logic.Sex
import com.calorie.app.logic.gramsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private const val LANGUAGE_EN = "Language"

/**
 * App languages: tag and native name. Native names are not translated - one's own language
 * must be easy to find whatever language the app is open in.
 */
private val APP_LANGUAGES = listOf(
    "en" to "English", "ru" to "Русский", "de" to "Deutsch", "fr" to "Français",
    "es" to "Español", "it" to "Italiano", "ja" to "日本語", "ko" to "한국어", "zh-CN" to "简体中文",
)

/** Settings pages in tile order: who I am -> what I want -> how to recognize -> how it looks. */
enum class SettingsPage(@StringRes val title: Int, @DrawableRes val icon: Int, val color: Color) {
    PROFILE(R.string.profile_title, R.drawable.ic_set_person, Color(0xFF26A69A)),
    GOAL(R.string.goal_title, R.drawable.ic_set_flag, Color(0xFF3BA55C)),
    CLAUDE(R.string.claude_title, R.drawable.ic_set_key, Color(0xFFD97757)),
    THEME(R.string.theme_title, R.drawable.ic_set_palette, Color(0xFF00A5B8)),
    LANGUAGE(R.string.lang_title, R.drawable.ic_set_language, Color(0xFF5C6BC0)),
}

/** Not null - the card is open as a separate page with a back button. */
private val LocalSettingsBack = compositionLocalOf<(() -> Unit)?> { null }

@Composable
fun SettingsScreen(a: MainActivity) {
    val page = a.settingsPage
    if (page == null) {
        SettingsGrid(a)
        return
    }
    BackHandler { a.settingsPage = null }
    CompositionLocalProvider(LocalSettingsBack provides { a.settingsPage = null }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
                SettingsPage.PROFILE -> ProfileSettings(a)
                SettingsPage.GOAL -> GoalSettings(a)
                SettingsPage.CLAUDE -> ClaudeSettings(a)
                SettingsPage.THEME -> ThemeSettings(a)
                SettingsPage.LANGUAGE -> LanguageSettings()
            }
        }
    }
}

@Composable
private fun SettingsGrid(a: MainActivity) {
    val ctx = LocalContext.current
    val lastWeight by remember { FoodDb.get(ctx).dao().lastWeight() }.collectAsState(null)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The per-app language setting exists only since Android 13.
        val pages = SettingsPage.entries.filter { it != SettingsPage.LANGUAGE || Build.VERSION.SDK_INT >= 33 }
        pages.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { p -> SettingsTile(a, p, tileStatus(a, p, lastWeight?.kg), Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SettingsTile(a: MainActivity, p: SettingsPage, status: String, modifier: Modifier) {
    Card(onClick = { a.settingsPage = p }, modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(32.dp).background(p.color.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(p.icon), null, tint = p.color, modifier = Modifier.size(20.dp))
                }
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.titleSmall) {
                    TileTitle(if (p == SettingsPage.LANGUAGE) LANGUAGE_EN else stringResource(p.title), Modifier.weight(1f))
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** Short tile status: the key value, so the page need not be opened just to see it. */
@Composable
private fun tileStatus(a: MainActivity, p: SettingsPage, weight: Double?): String = when (p) {
    SettingsPage.PROFILE -> {
        val prefs = a.prefs
        if (prefs.sex == null || prefs.birthYear == 0 || prefs.heightCm == 0 || weight == null) stringResource(R.string.tile_profile_empty)
        else stringResource(R.string.tile_profile, Goals.age(prefs.birthYear), prefs.heightCm, weight.gramsText())
    }
    SettingsPage.GOAL -> a.target(weight)?.let { stringResource(R.string.tile_goal, it.kcal) } ?: stringResource(R.string.tile_goal_empty)
    SettingsPage.CLAUDE -> stringResource(
        when {
            !a.hasKey -> R.string.tile_claude_none
            a.prefs.keyStatus == Prefs.KEY_BAD -> R.string.tile_claude_bad
            else -> R.string.tile_claude_ok
        }
    )
    SettingsPage.THEME -> stringResource(themeLabel(a.themeMode))
    SettingsPage.LANGUAGE -> APP_LANGUAGES.firstOrNull { it.first == currentAppLanguage() }?.second ?: systemLanguageLabel()
}

private fun themeLabel(mode: String) = when (mode) {
    Prefs.THEME_LIGHT -> R.string.theme_light
    Prefs.THEME_DARK -> R.string.theme_dark
    else -> R.string.theme_system
}

/**
 * Settings card. text - short status, always visible; help - explanation, hidden
 * and expanded by the icon next to the title, so it takes no space.
 */
@Composable
fun SettingsCard(title: String, text: String? = null, help: String? = null, actions: @Composable () -> Unit) {
    var showHelp by rememberSaveable(title) { mutableStateOf(false) }
    val back = LocalSettingsBack.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (help != null) {
                IconButton(onClick = { showHelp = !showHelp }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (showHelp) Icons.Filled.Info else Icons.Outlined.Info,
                        stringResource(R.string.help_toggle),
                        tint = if (showHelp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium)
                if (help != null && showHelp) {
                    Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                actions()
            }
        }
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, decimal: Boolean, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value, onChange, modifier = modifier, singleLine = true,
        label = { Text(label, maxLines = 1) },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

@Composable
private fun activityLabel(x: Activity) = stringResource(
    when (x) {
        Activity.SEDENTARY -> R.string.activity_sedentary
        Activity.LIGHT -> R.string.activity_light
        Activity.MODERATE -> R.string.activity_moderate
        Activity.HIGH -> R.string.activity_high
    }
)

@Composable
private fun activityDesc(x: Activity) = stringResource(
    when (x) {
        Activity.SEDENTARY -> R.string.activity_sedentary_desc
        Activity.LIGHT -> R.string.activity_light_desc
        Activity.MODERATE -> R.string.activity_moderate_desc
        Activity.HIGH -> R.string.activity_high_desc
    }
)

@Composable
private fun ProfileSettings(a: MainActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = a.prefs
    val lastWeight by remember { FoodDb.get(ctx).dao().lastWeight() }.collectAsState(null)
    // Fields are easy to touch while scrolling, so by default the data is only shown.
    var editing by remember { mutableStateOf(prefs.sex == null) }
    var sex by remember { mutableStateOf(prefs.sex) }
    var year by remember { mutableStateOf(prefs.birthYear.takeIf { it > 0 }?.toString() ?: "") }
    var height by remember { mutableStateOf(prefs.heightCm.takeIf { it > 0 }?.toString() ?: "") }
    var weight by remember(lastWeight) { mutableStateOf(lastWeight?.kg?.gramsText() ?: "") }
    var activity by remember { mutableStateOf(prefs.activity) }
    val thisYear = LocalDate.now().year
    val y = year.toIntOrNull()?.takeIf { it in 1920..thisYear - 14 }
    val h = height.toIntOrNull()?.takeIf { it in 120..230 }
    val w = parseNumber(weight)?.takeIf { it in 30.0..300.0 }

    SettingsCard(stringResource(R.string.profile_title), help = stringResource(R.string.profile_help)) {
        if (!editing) {
            AboutLine(stringResource(R.string.sex), sex?.let { stringResource(if (it == Sex.MALE) R.string.sex_male else R.string.sex_female) } ?: "--")
            AboutLine(stringResource(R.string.birth_year), prefs.birthYear.takeIf { it > 0 }?.let { "$it (${Goals.age(it)})" } ?: "--")
            AboutLine(stringResource(R.string.height), prefs.heightCm.takeIf { it > 0 }?.let { stringResource(R.string.height_value, it) } ?: "--")
            AboutLine(stringResource(R.string.weight_title), lastWeight?.let { stringResource(R.string.kg_value, it.kg.gramsText()) } ?: "--")
            AboutLine(stringResource(R.string.activity), activityLabel(prefs.activity))
            OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.edit)) }
            return@SettingsCard
        }
        Text(stringResource(R.string.sex), style = MaterialTheme.typography.titleSmall)
        FitRow { style ->
            listOf(Sex.FEMALE to R.string.sex_female, Sex.MALE to R.string.sex_male).forEach { (s, label) ->
                FilterChip(selected = sex == s, onClick = { sex = s }, label = { Text(stringResource(label), maxLines = 1, style = style) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.birth_year), year, { year = it }, false, Modifier.weight(1f))
            NumberField(stringResource(R.string.height_cm), height, { height = it }, false, Modifier.weight(1f))
            NumberField(stringResource(R.string.weight_kg), weight, { weight = it }, true, Modifier.weight(1f))
        }
        Text(stringResource(R.string.activity), style = MaterialTheme.typography.titleSmall)
        FitRow { style ->
            Activity.entries.forEach { x ->
                FilterChip(selected = activity == x, onClick = { activity = x }, label = { Text(activityLabel(x), maxLines = 1, style = style) })
            }
        }
        Text(activityDesc(activity), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(enabled = sex != null && y != null && h != null && w != null, onClick = {
            prefs.sex = sex
            prefs.birthYear = y!!
            prefs.heightCm = h!!
            prefs.activity = activity
            // Weight from the profile is today's weight log entry: the target uses the latest weight.
            if (lastWeight?.kg != w) {
                val today = LocalDate.now().toEpochDay()
                scope.launch { FoodDb.get(ctx).dao().putWeight(WeightMark(today, w!!)) }
            }
            a.profileVersion++
            editing = false
        }) { Text(stringResource(R.string.done)) }
    }
}

@Composable
private fun GoalSettings(a: MainActivity) {
    val ctx = LocalContext.current
    val prefs = a.prefs
    val lastWeight by remember { FoodDb.get(ctx).dao().lastWeight() }.collectAsState(null)
    var goal by remember { mutableStateOf(prefs.goalKg.takeIf { it > 0 }?.toDouble()?.gramsText() ?: "") }
    var pace by remember { mutableStateOf(prefs.pace) }
    var custom by remember { mutableStateOf(prefs.customKcal > 0) }
    var customText by remember { mutableStateOf(prefs.customKcal.takeIf { it > 0 }?.toString() ?: "") }
    val t = remember(lastWeight, a.profileVersion) { a.target(lastWeight?.kg) }
    fun changed() { a.profileVersion++ }

    SettingsCard(
        stringResource(R.string.goal_title),
        help = stringResource(R.string.goal_help) + "\n\n" + stringResource(R.string.disclaimer_full),
    ) {
        if (t == null) {
            Text(stringResource(R.string.profile_needed))
            OutlinedButton(onClick = { a.settingsPage = SettingsPage.PROFILE }) { Text(stringResource(R.string.profile_fill)) }
            return@SettingsCard
        }
        NumberField(stringResource(R.string.goal_weight), goal, { s ->
            goal = s
            val v = parseNumber(s)
            if (s.isBlank()) { prefs.goalKg = 0f; changed() }
            else if (v != null && v in 30.0..300.0) { prefs.goalKg = v.toFloat(); changed() }
        }, true, Modifier.fillMaxWidth())
        Text(stringResource(R.string.pace), style = MaterialTheme.typography.titleSmall)
        FitRow { style ->
            Pace.entries.forEach { p ->
                FilterChip(
                    selected = pace == p,
                    onClick = { pace = p; prefs.pace = p; changed() },
                    label = { Text(stringResource(R.string.pace_value, p.kgPerWeek.gramsText()), maxLines = 1, style = style) },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AboutLine(stringResource(R.string.norm), stringResource(R.string.kcal_n, t.kcal.toString()))
        limitText(t)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CalColors.Warn) }
        Text(
            stringResource(R.string.norm_details, t.bmr, t.tdee, t.proteinG),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (t.kgPerWeek > 0) {
            Text(
                if (t.weeksToGoal != null) stringResource(R.string.forecast_goal, t.kgPerWeek.gramsText(), t.weeksToGoal)
                else stringResource(R.string.forecast, t.kgPerWeek.gramsText()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (custom) R.string.custom_on else R.string.custom_off), Modifier.weight(1f))
            Switch(checked = custom, onCheckedChange = {
                custom = it
                if (!it) { prefs.customKcal = 0; changed() }
            })
        }
        if (custom) {
            NumberField(stringResource(R.string.custom_kcal), customText, { s ->
                customText = s
                s.toIntOrNull()?.takeIf { it in 800..6000 }?.let { prefs.customKcal = it; changed() }
            }, false, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ClaudeSettings(a: MainActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = a.prefs
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var saved by remember { mutableStateOf(ApiKeyStore.load(ctx)) }
    var editing by remember { mutableStateOf(saved == null) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(prefs.keyStatus) }
    var checking by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var usageTick by remember { mutableStateOf(0) }

    fun check(key: String) {
        checking = true
        checkError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { ClaudeFood(key).use { it.checkKey() } }
                status = Prefs.KEY_OK
            } catch (e: AiException) {
                // No network - nothing learned about the key, keep the previous status.
                if (e.failure == AiFailure.BAD_KEY) status = Prefs.KEY_BAD
                checkError = ctx.getString(if (e.failure == AiFailure.BAD_KEY) R.string.err_bad_key else R.string.err_network)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                checkError = e.message
            } finally {
                prefs.keyStatus = status
                checking = false
            }
        }
    }

    val statusText = when {
        saved == null -> stringResource(R.string.key_none)
        checking -> stringResource(R.string.key_checking)
        status == Prefs.KEY_OK -> stringResource(R.string.key_ok, ApiKeyStore.masked(saved!!))
        status == Prefs.KEY_BAD -> stringResource(R.string.key_bad, ApiKeyStore.masked(saved!!))
        else -> stringResource(R.string.key_unchecked, ApiKeyStore.masked(saved!!))
    }
    SettingsCard(stringResource(R.string.claude_title), statusText, help = stringResource(R.string.claude_help)) {
        checkError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CalColors.Over) }
        if (editing) {
            OutlinedTextField(
                input, { input = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.key_field)) },
                placeholder = { Text("sk-ant-...") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            FitRow { style ->
                OutlinedButton(onClick = { clipboard.getText()?.text?.trim()?.let { input = it } }) {
                    Text(stringResource(R.string.paste), maxLines = 1, style = style)
                }
                if (saved != null) {
                    OutlinedButton(onClick = { editing = false; input = "" }) { Text(stringResource(R.string.cancel), maxLines = 1, style = style) }
                }
                OutlinedButton(enabled = input.startsWith("sk-ant-") && input.length > 20, onClick = {
                    ApiKeyStore.save(ctx, input)
                    saved = input
                    a.hasKey = true
                    status = Prefs.KEY_UNKNOWN
                    input = ""
                    editing = false
                    check(saved!!)
                }) { Text(stringResource(R.string.key_save), maxLines = 1, style = style) }
            }
            if (input.isNotEmpty() && !input.startsWith("sk-ant-")) {
                Text(stringResource(R.string.key_format), style = MaterialTheme.typography.bodySmall, color = CalColors.Warn)
            }
        } else {
            FitRow { style ->
                OutlinedButton(enabled = !checking, onClick = { check(saved!!) }) { Text(stringResource(R.string.key_check), maxLines = 1, style = style) }
                OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.key_replace), maxLines = 1, style = style) }
                OutlinedButton(onClick = {
                    ApiKeyStore.clear(ctx)
                    saved = null
                    a.hasKey = false
                    status = Prefs.KEY_UNKNOWN
                    prefs.keyStatus = status
                    editing = true
                }) { Text(stringResource(R.string.delete), maxLines = 1, style = style) }
            }
        }
    }

    SettingsCard(stringResource(R.string.console_title), help = stringResource(R.string.console_help)) {
        Text(stringResource(R.string.console_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FitRow { style ->
            OutlinedButton(onClick = { a.openConsole(Console.KEYS) }) { Text(stringResource(R.string.console_keys), maxLines = 1, style = style) }
            OutlinedButton(onClick = { a.openConsole(Console.BILLING) }) { Text(stringResource(R.string.console_billing), maxLines = 1, style = style) }
            OutlinedButton(onClick = { a.openConsole(Console.USAGE) }) { Text(stringResource(R.string.console_usage), maxLines = 1, style = style) }
        }
    }

    // A regular key cannot read the balance, so count it ourselves from the usage in answers.
    val usd = remember(usageTick) { Pricing.usd(prefs.aiInputTokens, prefs.aiOutputTokens) }
    val since = remember(usageTick) {
        prefs.usageSince.takeIf { it > 0 }?.let { SimpleDateFormat(dayMonthPattern(), Locale.getDefault()).format(Date(it)) }
    }
    SettingsCard(stringResource(R.string.spend_title), help = stringResource(R.string.spend_help)) {
        if (prefs.aiRequests == 0) {
            Text(stringResource(R.string.spend_none), style = MaterialTheme.typography.bodyMedium)
        } else {
            AboutLine(stringResource(R.string.spend_requests, since ?: "--"), prefs.aiRequests.toString())
            AboutLine(stringResource(R.string.spend_tokens), stringResource(R.string.spend_tokens_value, prefs.aiInputTokens, prefs.aiOutputTokens))
            AboutLine(stringResource(R.string.spend_cost), "$" + String.format(Locale.US, "%.3f", usd))
            AboutLine(stringResource(R.string.spend_avg), "$" + String.format(Locale.US, "%.4f", usd / prefs.aiRequests))
            OutlinedButton(onClick = { prefs.resetUsage(); usageTick++ }) { Text(stringResource(R.string.spend_reset)) }
        }
    }
}

@Composable
private fun ThemeSettings(a: MainActivity) {
    SettingsCard(stringResource(R.string.theme_title)) {
        FitRow { style ->
            listOf(Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK).forEach { mode ->
                FilterChip(
                    selected = a.themeMode == mode,
                    onClick = { a.themeMode = mode; a.prefs.theme = mode },
                    label = { Text(stringResource(themeLabel(mode)), maxLines = 1, style = style) },
                )
            }
        }
    }
}

/** The system stores the language (LocaleManager): the same choice shows in Android settings. */
@Composable
private fun currentAppLanguage(): String {
    if (Build.VERSION.SDK_INT < 33) return ""
    val list = LocalContext.current.getSystemService(LocaleManager::class.java).applicationLocales
    return if (list.isEmpty) "" else list[0].toLanguageTag()
}

/** "System (Русский)": the system language by its native name, so anyone recognizes it. */
@Composable
private fun systemLanguageLabel(): String {
    val word = stringResource(R.string.lang_system)
    if (Build.VERSION.SDK_INT < 33) return word
    val sys = LocalContext.current.getSystemService(LocaleManager::class.java).systemLocales
    if (sys.isEmpty) return word
    val own = sys[0].getDisplayLanguage(sys[0]).replaceFirstChar { it.titlecase(sys[0]) }
    return "$word ($own)"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageSettings() {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf("") }
    current = currentAppLanguage()
    SettingsCard(stringResource(R.string.lang_title)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("" to systemLanguageLabel()) + APP_LANGUAGES).forEach { (tag, name) ->
                FilterChip(
                    selected = current == tag,
                    onClick = {
                        if (current == tag || Build.VERSION.SDK_INT < 33) return@FilterChip
                        current = tag
                        // The system recreates the activity in the new language by itself.
                        ctx.getSystemService(LocaleManager::class.java).applicationLocales =
                            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
                    },
                    label = { Text(name, maxLines = 1) },
                )
            }
        }
    }
}
