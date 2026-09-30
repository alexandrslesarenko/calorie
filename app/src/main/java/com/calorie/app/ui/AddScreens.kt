package com.calorie.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import com.calorie.app.data.Dish
import com.calorie.app.logic.Keys
import kotlinx.coroutines.launch
import java.time.LocalDate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calorie.app.R
import com.calorie.app.ai.AiFailure
import com.calorie.app.ai.Photo
import com.calorie.app.data.FoodDb
import com.calorie.app.data.Meal
import com.calorie.app.data.Source
import com.calorie.app.logic.Confidence
import com.calorie.app.logic.FoodDraft
import com.calorie.app.logic.Nutrients
import com.calorie.app.logic.gramsText
import com.calorie.app.logic.kcalText
import com.calorie.app.logic.sum
import kotlin.math.roundToInt

/** Grams step of the buttons on the review screen. */
private const val GRAMS_STEP = 10.0
/** How many library dishes to show without a search. */
private const val DISHES_SHOWN = 30
private const val DISABLED_ALPHA = 0.45f

@Composable
fun OverlayScreen(a: MainActivity, o: Overlay) {
    when (o) {
        Overlay.AddMenu -> AddMenu(a)
        Overlay.TextInput -> TextInput(a)
        is Overlay.Manual -> ManualInput(a, o)
        is Overlay.PhotoHint -> PhotoHint(a, o)
        is Overlay.Busy -> Busy(a, o)
        is Overlay.Review -> Review(a, o)
        is Overlay.NotFound -> NotFound(a, o)
        is Overlay.Failed -> Failed(a, o)
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }
}

/** A way to add food - a tile with an icon, like in settings. */
private data class AddWay(val title: Int, val hint: Int, val color: androidx.compose.ui.graphics.Color, val needsKey: Boolean, val icon: @Composable () -> Painter, val go: () -> Unit)

@Composable
private fun AddMenu(a: MainActivity) {
    val ctx = LocalContext.current
    val dao = remember { FoodDb.get(ctx).dao() }
    val dishes by remember { dao.dishes() }.collectAsState(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Dish?>(null) }
    val scope = rememberCoroutineScope()
    val ways = listOf(
        AddWay(R.string.way_photo, R.string.way_photo_hint, CalColors.Photo, true, { painterResource(R.drawable.ic_photo_camera) }) { a.takePhoto() },
        AddWay(R.string.way_gallery, R.string.way_gallery_hint, CalColors.Gallery, true, { painterResource(R.drawable.ic_image) }, a::pickFromGallery),
        AddWay(R.string.way_barcode, R.string.way_barcode_hint, CalColors.Barcode, false, { painterResource(R.drawable.ic_barcode) }, a::scanBarcode),
        AddWay(R.string.way_text, R.string.way_text_hint, CalColors.Text, true, { painterResource(R.drawable.ic_chat) }) { a.overlay = Overlay.TextInput },
        AddWay(R.string.way_manual, R.string.way_manual_hint, CalColors.Manual, false, { rememberVectorPainter(Icons.Filled.Create) }) { a.overlay = Overlay.Manual() },
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(stringResource(R.string.add_title, dayTitle(a.day, inline = true))) { a.closeOverlay() }
        if (!a.hasKey) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.need_key))
                    Button(onClick = { a.openSettings(SettingsPage.CLAUDE) }) { Text(stringResource(R.string.need_key_btn)) }
                }
            }
        }
        ways.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { w -> WayTile(w, enabled = !w.needsKey || a.hasKey, Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (dishes.isNotEmpty()) {
            Text(stringResource(R.string.my_dishes), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                query, { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.dishes_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) } }) else null,
            )
            val q = Keys.dish(query)
            // Without a search - the first DISHES_SHOWN (favorites and recent), with a search - all matches.
            val shown = if (q.isEmpty()) dishes.take(DISHES_SHOWN) else dishes.filter { q in it.id }
            if (shown.isEmpty()) {
                Text(stringResource(R.string.dishes_not_found), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 12.dp)) {
                        shown.forEachIndexed { i, d ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DishRow(
                                d,
                                onPick = { a.overlay = Overlay.Review(listOf(ReviewItem(d.toDraft())), Source.REPEAT) },
                                onStar = { scope.launch { dao.setFavorite(d.id, !d.favorite) } },
                                onLongPress = { deleting = d },
                            )
                        }
                    }
                }
                Text(stringResource(R.string.dishes_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.dish_delete_title)) },
            text = { Text(stringResource(R.string.dish_delete_text, d.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.deleteDish(d) }
                    deleting = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Library row: tap - repeat, star - favorite, long press - delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DishRow(d: Dish, onPick: () -> Unit, onStar: () -> Unit, onLongPress: () -> Unit) {
    val draft = d.toDraft()
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onPick, onLongClick = onLongPress).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.recent_line, d.lastGrams.gramsText(), draft.total.kcal.kcalText()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onStar) {
            // The core icon set has no outlined star, so both come from Material Symbols.
            Icon(
                painterResource(if (d.favorite) R.drawable.ic_star else R.drawable.ic_star_border),
                stringResource(R.string.dish_favorite),
                tint = if (d.favorite) CalColors.Star else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WayTile(w: AddWay, enabled: Boolean, modifier: Modifier) {
    Card(onClick = w.go, enabled = enabled, modifier = modifier) {
        // A disabled card only dims its title; dim everything so the tile reads as off.
        Column(Modifier.padding(12.dp).alpha(if (enabled) 1f else DISABLED_ALPHA), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(32.dp).background(w.color.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(w.icon(), null, tint = w.color, modifier = Modifier.size(20.dp))
                }
                TileTitle(stringResource(w.title), Modifier.weight(1f))
            }
            Text(
                stringResource(w.hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun TextInput(a: MainActivity) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(stringResource(R.string.way_text)) { a.closeOverlay() }
        OutlinedTextField(
            text, { text = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            placeholder = { Text(stringResource(R.string.text_placeholder)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        FilledTonalButton(enabled = text.isNotBlank(), onClick = { a.recognizeText(text) }) { Text(stringResource(R.string.recognize)) }
        PrivacyNote()
    }
}

@Composable
private fun PrivacyNote() {
    Text(
        stringResource(R.string.privacy_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PhotoHint(a: MainActivity, o: Overlay.PhotoHint) {
    val bmp = remember(o) { Photo.thumbnail(o.jpeg) }
    var hint by remember(o) { mutableStateOf(o.hint) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(stringResource(R.string.way_photo)) { a.closeOverlay() }
        bmp?.let {
            Image(
                it.asImageBitmap(), null,
                Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        OutlinedTextField(
            hint, { hint = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.hint_label)) },
            placeholder = { Text(stringResource(R.string.hint_placeholder)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        FitRow { style ->
            OutlinedButton(onClick = { a.takePhoto(hint) }) { Text(stringResource(R.string.retake), maxLines = 1, style = style) }
            FilledTonalButton(onClick = { a.recognizePhoto(o.jpeg, hint) }) { Text(stringResource(R.string.recognize), maxLines = 1, style = style) }
        }
        PrivacyNote()
    }
}

@Composable
private fun Busy(a: MainActivity, o: Overlay.Busy) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(o.text))
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { a.closeOverlay() }) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun Review(a: MainActivity, o: Overlay.Review) {
    // Each row has a stable id, so Compose does not mix up text fields after a removal.
    val rows = remember(o) { mutableStateListOf(*o.items.mapIndexed { i, it -> i to it }.toTypedArray()) }
    val items = rows.map { it.second }
    var meal by remember(o) { mutableStateOf(o.editing?.let { Meal.byKey(it.meal) } ?: a.defaultMeal()) }
    val bmp = remember(o) { o.jpeg?.let { Photo.thumbnail(it) } }
    val total = items.map { it.draft.total }.sum()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(stringResource(if (o.editing != null) R.string.edit_title else R.string.review_title)) { a.closeOverlay() }
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(), null,
                Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        // The day is easy to miss in the header; a new entry for another day gets a warning.
        if (o.editing == null && a.day != LocalDate.now().toEpochDay()) {
            Text(stringResource(R.string.review_other_day, dayTitle(a.day, inline = true)), style = MaterialTheme.typography.bodyMedium, color = CalColors.Warn)
        }
        if (o.source == Source.PHOTO || o.source == Source.TEXT) {
            Text(
                stringResource(R.string.review_check),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        o.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (o.fromCache && o.askAgain != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.from_cache),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(enabled = a.hasKey, onClick = o.askAgain) { Text(stringResource(R.string.ask_again)) }
            }
        }
        rows.forEachIndexed { i, (id, item) ->
            androidx.compose.runtime.key(id) {
                ItemEditor(
                    item,
                    removable = rows.size > 1,
                    onChange = { rows[i] = id to it },
                    onRemove = { rows.removeAt(i) },
                )
            }
        }
        Text(stringResource(R.string.meal), style = MaterialTheme.typography.titleSmall)
        FitRow { style ->
            Meal.entries.forEach { m ->
                // Same hue as the meal card on "Today".
                val selected = CalColors.meal(m).copy(alpha = 0.35f).compositeOver(MaterialTheme.colorScheme.surface)
                FilterChip(
                    selected = meal == m, onClick = { meal = m },
                    label = { Text(stringResource(m.label), maxLines = 1, style = style) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = selected),
                )
            }
        }
        Text(
            stringResource(R.string.review_total, total.kcal.kcalText(), total.protein.roundToInt(), total.fat.roundToInt(), total.carbs.roundToInt()),
            style = MaterialTheme.typography.titleMedium,
        )
        val valid = items.isNotEmpty() && items.all { it.draft.name.isNotBlank() && it.draft.grams > 0 }
        FitRow { style ->
            if (o.editing != null) {
                OutlinedButton(onClick = { a.delete(o.editing) }) { Text(stringResource(R.string.delete), maxLines = 1, style = style) }
            }
            FilledTonalButton(enabled = valid, onClick = { a.save(items.map { it.draft }, meal, o.source, o.editing) }) {
                Text(stringResource(if (o.editing != null) R.string.save else R.string.add_to_diary), maxLines = 1, style = style)
            }
        }
    }
}

@Composable
private fun ItemEditor(item: ReviewItem, removable: Boolean, onChange: (ReviewItem) -> Unit, onRemove: () -> Unit) {
    var gramsText by remember { mutableStateOf(item.draft.grams.gramsText()) }
    fun setGrams(g: Double) {
        val v = g.coerceIn(0.0, 5000.0)
        gramsText = v.gramsText()
        onChange(item.copy(draft = item.draft.copy(grams = v)))
    }
    val t = item.draft.total
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    item.draft.name, { onChange(item.copy(draft = item.draft.copy(name = it))) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.food_name)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                if (removable) {
                    IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.remove_item)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { setGrams(item.draft.grams - GRAMS_STEP) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.less))
                }
                OutlinedTextField(
                    gramsText,
                    { s ->
                        gramsText = s
                        parseNumber(s)?.takeIf { it >= 0 }?.let { onChange(item.copy(draft = item.draft.copy(grams = it))) }
                    },
                    modifier = Modifier.width(96.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.grams_short)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedButton(onClick = { setGrams(item.draft.grams + GRAMS_STEP) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.more))
                }
            }
            Text(
                stringResource(R.string.item_line, t.kcal.kcalText(), t.protein.roundToInt(), t.fat.roundToInt(), t.carbs.roundToInt()),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.per100_line, item.draft.per100.kcal.kcalText()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.confidence == Confidence.LOW) {
                Text(stringResource(R.string.low_confidence), style = MaterialTheme.typography.bodySmall, color = CalColors.Warn)
            }
        }
    }
}

@Composable
private fun ManualInput(a: MainActivity, o: Overlay.Manual) {
    var name by remember { mutableStateOf(o.name) }
    var kcal by remember { mutableStateOf("") }
    var grams by remember { mutableStateOf("100") }
    var protein by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    val k = parseNumber(kcal)?.takeIf { it >= 0 }
    val g = parseNumber(grams)?.takeIf { it > 0 }
    @Composable
    fun num(label: Int, v: String, set: (String) -> Unit, modifier: Modifier = Modifier) = OutlinedTextField(
        v, set, modifier = modifier, singleLine = true,
        label = { Text(stringResource(label), maxLines = 1) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(stringResource(R.string.way_manual)) { a.closeOverlay() }
        OutlinedTextField(
            name, { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.food_name)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            num(R.string.kcal_per100, kcal, { kcal = it }, Modifier.weight(1f))
            num(R.string.grams_eaten, grams, { grams = it }, Modifier.weight(1f))
        }
        Text(stringResource(R.string.macros_optional), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            num(R.string.protein, protein, { protein = it }, Modifier.weight(1f))
            num(R.string.fat, fat, { fat = it }, Modifier.weight(1f))
            num(R.string.carbs, carbs, { carbs = it }, Modifier.weight(1f))
        }
        FilledTonalButton(enabled = name.isNotBlank() && k != null && g != null, onClick = {
            val per100 = Nutrients(k!!, parseNumber(protein) ?: 0.0, parseNumber(fat) ?: 0.0, parseNumber(carbs) ?: 0.0)
            a.overlay = Overlay.Review(listOf(ReviewItem(FoodDraft(name.trim(), g!!, per100))), Source.MANUAL)
        }) { Text(stringResource(R.string.next)) }
    }
}

@Composable
private fun NotFound(a: MainActivity, o: Overlay.NotFound) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(stringResource(R.string.way_barcode)) { a.closeOverlay() }
        Text(stringResource(R.string.barcode_not_found, o.barcode))
        FitRow { style ->
            val labelHint = stringResource(R.string.label_hint)
            if (a.hasKey) OutlinedButton(onClick = { a.takePhoto(labelHint) }) { Text(stringResource(R.string.photo_label), maxLines = 1, style = style) }
            OutlinedButton(onClick = { a.overlay = Overlay.Manual() }) { Text(stringResource(R.string.way_manual), maxLines = 1, style = style) }
        }
    }
}

@Composable
private fun Failed(a: MainActivity, o: Overlay.Failed) {
    val text = when (o.failure) {
        AiFailure.BAD_KEY -> stringResource(if (a.hasKey) R.string.err_bad_key else R.string.need_key)
        AiFailure.NO_CREDIT -> stringResource(R.string.err_no_credit)
        AiFailure.RATE_LIMIT -> stringResource(R.string.err_rate_limit)
        AiFailure.OVERLOADED -> stringResource(R.string.err_overloaded)
        AiFailure.NETWORK -> stringResource(R.string.err_network)
        AiFailure.BAD_ANSWER -> stringResource(R.string.err_bad_answer)
        null -> o.detail ?: ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(stringResource(R.string.failed_title)) { a.closeOverlay() }
        Text(text)
        if (o.failure != null && o.detail != null) {
            Text(o.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
        }
        when (o.failure) {
            AiFailure.BAD_KEY -> Button(onClick = { a.openSettings(SettingsPage.CLAUDE) }) { Text(stringResource(R.string.need_key_btn)) }
            AiFailure.NO_CREDIT -> Button(onClick = { a.openConsole(Console.BILLING) }) { Text(stringResource(R.string.console_billing)) }
            else -> OutlinedButton(onClick = { a.closeOverlay() }) { Text(stringResource(R.string.back)) }
        }
    }
}
