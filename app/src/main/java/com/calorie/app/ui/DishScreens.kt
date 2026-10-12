package com.calorie.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calorie.app.R
import com.calorie.app.ai.Photo
import com.calorie.app.data.Dish
import com.calorie.app.data.FoodDb
import com.calorie.app.data.Source
import com.calorie.app.logic.Keys
import com.calorie.app.logic.gramsText
import com.calorie.app.logic.kcalText
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the dish editor holds as typed: survives a trip to the camera for a new thumbnail. */
data class DishForm(
    val name: String,
    val kcal: String,
    val protein: String,
    val fat: String,
    val carbs: String,
    val grams: String,
    val barcode: String,
) {
    /** The edited dish; null - something required is missing or not a number. */
    fun toDish(old: Dish): Dish? {
        val n = name.trim().takeIf { it.isNotEmpty() } ?: return null
        val k = parseNumber(kcal)?.takeIf { it >= 0 } ?: return null
        val g = parseNumber(grams)?.takeIf { it > 0 } ?: return null
        fun macro(s: String): Double? = if (s.isBlank()) 0.0 else parseNumber(s)?.takeIf { it >= 0 }
        val p = macro(protein) ?: return null
        val f = macro(fat) ?: return null
        val c = macro(carbs) ?: return null
        val b = barcode.trim()
        if (b.isNotEmpty() && !barcodeValid(b)) return null
        return old.copy(id = Keys.dish(n), name = n, kcal = k, protein = p, fat = f, carbs = c, lastGrams = g, barcode = b.ifEmpty { null })
    }

    companion object {
        fun of(d: Dish) = DishForm(
            d.name, d.kcal.gramsText(), d.protein.gramsText(), d.fat.gramsText(), d.carbs.gramsText(), d.lastGrams.gramsText(), d.barcode ?: "",
        )

        /** EAN-8, UPC-A, EAN-13 and GTIN-14: digits only. */
        fun barcodeValid(b: String) = b.length in 8..14 && b.all { it.isDigit() }
    }
}

/** Decoded thumbnails of the library by dish id. */
@Composable
private fun rememberThumbs(): Map<String, ImageBitmap> {
    val ctx = LocalContext.current
    val photos by remember { FoodDb.get(ctx).dao().dishPhotos() }.collectAsState(emptyList())
    return remember(photos) { photos.mapNotNull { p -> Photo.thumbnail(p.jpeg)?.let { p.id to it.asImageBitmap() } }.toMap() }
}

/**
 * "My dishes": prepared products and everything eaten, with thumbnails. Adding from here
 * goes through the usual add screens and ends in the library instead of the diary.
 */
@Composable
fun LibraryScreen(a: MainActivity) {
    val ctx = LocalContext.current
    val dao = remember { FoodDb.get(ctx).dao() }
    val dishes by remember { dao.dishes() }.collectAsState(null)
    val thumbs = rememberThumbs()
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { a.scanCode = null }
    val q = Keys.dish(query)
    val shown = dishes?.let { list -> if (q.isEmpty()) list else list.filter { q in it.id || it.barcode == query.trim() } }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { a.closeOverlay() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Text(stringResource(R.string.my_dishes), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
        }
        item { FilledTonalButton(onClick = { a.overlay = Overlay.AddMenu }) { Text(stringResource(R.string.library_add)) } }
        if (dishes?.isEmpty() == true) {
            item { Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.bodyMedium) }
            return@LazyColumn
        }
        item {
            OutlinedTextField(
                query, { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.dishes_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) } }) else null,
            )
        }
        if (shown?.isEmpty() == true) {
            item { Text(stringResource(R.string.dishes_not_found), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(shown.orEmpty(), key = { it.id }) { d ->
            LibraryRow(
                d, thumbs[d.id],
                onOpen = { a.overlay = Overlay.DishEdit(d.id) },
                onStar = { scope.launch { dao.setFavorite(d.id, !d.favorite) } },
            )
        }
        item { Text(stringResource(R.string.library_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Thumb(bmp: ImageBitmap?, size: Int) {
    val shape = RoundedCornerShape(8.dp)
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size.dp).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_set_dish), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size((size / 2).dp))
        }
    }
}

@Composable
private fun LibraryRow(d: Dish, thumb: ImageBitmap?, onOpen: () -> Unit, onStar: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Thumb(thumb, 56)
            Column(Modifier.weight(1f)) {
                Text(d.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.dish_line, d.kcal.kcalText(), d.lastGrams.gramsText()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (d.barcode != null) {
                    Text(d.barcode, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onStar) {
                Icon(
                    painterResource(if (d.favorite) R.drawable.ic_star else R.drawable.ic_star_border),
                    stringResource(R.string.dish_favorite),
                    tint = if (d.favorite) CalColors.Star else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A library dish: name, values per 100 g, usual portion, barcode and thumbnail. */
@Composable
fun DishEditScreen(a: MainActivity, o: Overlay.DishEdit) {
    val ctx = LocalContext.current
    val dao = remember { FoodDb.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    // Initial Unit: "not loaded yet" differs from "dish is gone" (null).
    val loaded by remember(o.id) { dao.dishFlow(o.id) }.collectAsState(Unit)
    if (loaded == Unit) return
    val dish = loaded as Dish?
    if (dish == null) {
        LaunchedEffect(Unit) { a.overlay = Overlay.Library }
        return
    }
    val photo by remember(o.id) { dao.dishPhotoFlow(o.id) }.collectAsState(null)
    val bmp = remember(photo) { photo?.let { Photo.thumbnail(it.jpeg)?.asImageBitmap() } }
    var form by remember(o) { mutableStateOf(o.form ?: DishForm.of(dish)) }
    var nameTaken by remember(o) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val edited = form.toDish(dish)

    @Composable
    fun field(label: Int, v: String, set: (String) -> Unit, modifier: Modifier) = OutlinedTextField(
        v, set, modifier = modifier, singleLine = true,
        label = { Text(stringResource(label), maxLines = 1) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )

    fun saveThen(next: (Dish) -> Unit) {
        val d = edited ?: return
        scope.launch {
            if (dao.editDish(dish.id, d, null)) next(d) else nameTaken = true
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { a.closeOverlay() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.dish_edit_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        }
        FitRow { style ->
            OutlinedButton(onClick = { a.takeDishPhoto(Overlay.DishEdit(dish.id, form), camera = true) }) {
                Text(stringResource(R.string.dish_photo_camera), maxLines = 1, style = style)
            }
            OutlinedButton(onClick = { a.takeDishPhoto(Overlay.DishEdit(dish.id, form), camera = false) }) {
                Text(stringResource(R.string.dish_photo_gallery), maxLines = 1, style = style)
            }
            if (photo != null) {
                OutlinedButton(onClick = { scope.launch { dao.deleteDishPhoto(dish.id) } }) {
                    Text(stringResource(R.string.dish_photo_remove), maxLines = 1, style = style)
                }
            }
        }
        OutlinedTextField(
            form.name, { form = form.copy(name = it); nameTaken = false },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.food_name)) },
            isError = nameTaken,
            supportingText = if (nameTaken) ({ Text(stringResource(R.string.dish_name_taken)) }) else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            field(R.string.kcal_per100, form.kcal, { form = form.copy(kcal = it) }, Modifier.weight(1f))
            field(R.string.dish_portion, form.grams, { form = form.copy(grams = it) }, Modifier.weight(1f))
        }
        Text(stringResource(R.string.macros_per100), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            field(R.string.protein, form.protein, { form = form.copy(protein = it) }, Modifier.weight(1f))
            field(R.string.fat, form.fat, { form = form.copy(fat = it) }, Modifier.weight(1f))
            field(R.string.carbs, form.carbs, { form = form.copy(carbs = it) }, Modifier.weight(1f))
        }
        val badBarcode = form.barcode.isNotEmpty() && !DishForm.barcodeValid(form.barcode)
        OutlinedTextField(
            form.barcode, { form = form.copy(barcode = it.filter(Char::isDigit)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.dish_barcode), maxLines = 1) },
            isError = badBarcode,
            supportingText = if (badBarcode) ({ Text(stringResource(R.string.dish_barcode_bad)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        edited?.let { d ->
            val t = d.toDraft().total
            Text(
                stringResource(R.string.item_line, t.kcal.kcalText(), t.protein.roundToInt(), t.fat.roundToInt(), t.carbs.roundToInt()),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        FitRow { style ->
            OutlinedButton(onClick = { deleting = true }) { Text(stringResource(R.string.delete), maxLines = 1, style = style) }
            OutlinedButton(enabled = edited != null, onClick = {
                saveThen { d -> a.overlay = Overlay.Review(listOf(ReviewItem(d.toDraft())), Source.REPEAT) }
            }) { Text(stringResource(R.string.add_to_diary), maxLines = 1, style = style) }
            FilledTonalButton(enabled = edited != null, onClick = { saveThen { a.overlay = Overlay.Library } }) {
                Text(stringResource(R.string.save), maxLines = 1, style = style)
            }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.dish_delete_title)) },
            text = { Text(stringResource(R.string.dish_delete_text, dish.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    // Leave only after the delete: leaving first would cancel this screen's scope mid-way.
                    scope.launch {
                        dao.deleteDish(dish)
                        a.overlay = Overlay.Library
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
