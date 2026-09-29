package com.calorie.app.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.calorie.app.R
import com.calorie.app.ai.AiException
import com.calorie.app.ai.AiFailure
import com.calorie.app.ai.AiResponse
import com.calorie.app.ai.ClaudeFood
import com.calorie.app.ai.Photo
import com.calorie.app.data.AiCached
import com.calorie.app.data.ApiKeyStore
import com.calorie.app.data.Entry
import com.calorie.app.data.FoodDb
import com.calorie.app.data.Meal
import com.calorie.app.data.OffApi
import com.calorie.app.data.Prefs
import com.calorie.app.data.Source
import com.calorie.app.logic.AiParser
import com.calorie.app.logic.Body
import com.calorie.app.logic.Keys
import com.calorie.app.logic.Confidence
import com.calorie.app.logic.FoodDraft
import com.calorie.app.logic.Goals
import com.calorie.app.logic.Target
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

private const val STATE_DAY = "day"
private const val STATE_SETTINGS_PAGE = "settings_page"
/** How many recent Claude answers to keep in the cache. */
private const val AI_CACHE_KEEP = 300

/** Draft row on the review screen; confidence is set only for Claude results. */
data class ReviewItem(val draft: FoodDraft, val confidence: Confidence? = null)

/** Screen on top of the tabs: adding food step by step. */
sealed interface Overlay {
    data object AddMenu : Overlay
    data object TextInput : Overlay
    data class Manual(val name: String = "") : Overlay
    class PhotoHint(val jpeg: ByteArray, val hint: String = "") : Overlay
    data class Busy(val text: Int) : Overlay
    class Review(
        val items: List<ReviewItem>,
        val source: Source,
        val note: String? = null,
        val jpeg: ByteArray? = null,
        /** Editing an existing entry: save over it instead of adding. */
        val editing: Entry? = null,
        /** The answer came from the cache; askAgain - ask Claude again, bypassing the cache. */
        val fromCache: Boolean = false,
        val askAgain: (() -> Unit)? = null,
    ) : Overlay
    data class NotFound(val barcode: String) : Overlay
    data class Failed(val failure: AiFailure?, val detail: String?) : Overlay
}

/** Claude Console pages, opened in an in-app browser tab. */
object Console {
    const val KEYS = "https://platform.claude.com/settings/keys"
    const val BILLING = "https://platform.claude.com/settings/billing"
    const val USAGE = "https://platform.claude.com/usage"
}

class MainActivity : ComponentActivity() {
    lateinit var prefs: Prefs
        private set

    /** Selected diary day (LocalDate.toEpochDay). */
    var day by mutableLongStateOf(LocalDate.now().toEpochDay())
    var overlay by mutableStateOf<Overlay?>(null)
    var settingsPage by mutableStateOf<SettingsPage?>(null)
    var themeMode by mutableStateOf(Prefs.THEME_SYSTEM)
    var hasKey by mutableStateOf(false)
    /** Bumped on any profile or goal change so screens recalculate the target. */
    var profileVersion by mutableIntStateOf(0)
    /** Requested tab (from the diary - "go to day"); -1 - no request. */
    var tabRequest by mutableIntStateOf(-1)

    /** Which day was today when last shown: if it was being viewed, move to the new today. */
    private var shownToday = LocalDate.now().toEpochDay()
    private var work: Job? = null
    private var shotUri: Uri? = null
    /** Hint for the next photo (prefilled for a nutrition label). */
    private var pendingHint = ""

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = shotUri
        if (ok && uri != null) loadPhoto(uri) else overlay = Overlay.AddMenu
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadPhoto(uri) else overlay = Overlay.AddMenu
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        themeMode = prefs.theme
        // A language change recreates the activity: keep the same day and settings page.
        savedInstanceState?.let { st ->
            day = st.getLong(STATE_DAY, day)
            settingsPage = st.getString(STATE_SETTINGS_PAGE)?.let { n -> SettingsPage.entries.firstOrNull { it.name == n } }
        }
        hasKey = ApiKeyStore.has(this)
        setContent {
            val dark = when (themeMode) {
                Prefs.THEME_LIGHT -> false
                Prefs.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            // Status bar icons follow the app theme, not the system one.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            AppTheme(dark) { Surface(Modifier.fillMaxSize()) { Root() } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_DAY, day)
        settingsPage?.let { outState.putString(STATE_SETTINGS_PAGE, it.name) }
    }

    override fun onResume() {
        super.onResume()
        // Opened on the next day: if "today" was shown, show the new today.
        val today = LocalDate.now().toEpochDay()
        if (today != shownToday) {
            if (day == shownToday) day = today
            shownToday = today
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        val pager = rememberPagerState { 3 }
        val scope = rememberCoroutineScope()
        LaunchedEffect(tabRequest) {
            if (tabRequest >= 0) {
                pager.animateScrollToPage(tabRequest)
                tabRequest = -1
            }
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            val o = overlay
            if (o != null) {
                BackHandler { closeOverlay() }
                Column(Modifier.fillMaxSize().padding(16.dp)) { OverlayScreen(this@MainActivity, o) }
                return@Column
            }
            PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                listOf(R.string.tab_today, R.string.tab_diary, R.string.tab_settings).forEachIndexed { i, title ->
                    Tab(
                        selected = pager.currentPage == i,
                        onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        text = { OneLineText(stringResource(title)) },
                    )
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    when (page) {
                        0 -> TodayScreen(this@MainActivity)
                        1 -> DiaryScreen(this@MainActivity)
                        else -> SettingsScreen(this@MainActivity)
                    }
                }
            }
        }
    }

    /** Target from the profile and the latest weight; null - the profile is incomplete. */
    fun target(lastWeightKg: Double?): Target? {
        val sex = prefs.sex ?: return null
        val w = lastWeightKg ?: return null
        if (prefs.birthYear == 0 || prefs.heightCm == 0) return null
        val body = Body(sex, Goals.age(prefs.birthYear), prefs.heightCm, w)
        val t = Goals.target(body, prefs.activity, prefs.pace, prefs.goalKg.takeIf { it > 0 }?.toDouble())
        return if (prefs.customKcal > 0) t.copy(kcal = prefs.customKcal) else t
    }

    fun openSettings(page: SettingsPage) {
        overlay = null
        settingsPage = page
        tabRequest = 2
    }

    fun openConsole(url: String) {
        runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, Uri.parse(url)) }
    }

    fun closeOverlay() {
        work?.cancel()
        overlay = when (overlay) {
            null, Overlay.AddMenu -> null
            is Overlay.Review -> if ((overlay as Overlay.Review).editing != null) null else Overlay.AddMenu
            else -> Overlay.AddMenu
        }
    }

    fun takePhoto(hint: String = "") {
        pendingHint = hint
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", File(dir, "shot.jpg"))
        shotUri = uri
        takePicture.launch(uri)
    }

    fun pickFromGallery() {
        pendingHint = ""
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun loadPhoto(uri: Uri) {
        overlay = Overlay.Busy(R.string.busy_photo)
        work = lifecycleScope.launch {
            val jpeg = runCatching { withContext(Dispatchers.IO) { Photo.prepare(this@MainActivity, uri) } }.getOrNull()
            // The camera shot is no longer needed: only what went into the diary stays on the phone.
            withContext(Dispatchers.IO) { File(cacheDir, "photos/shot.jpg").delete() }
            overlay = if (jpeg != null) Overlay.PhotoHint(jpeg, pendingHint) else Overlay.Failed(null, getString(R.string.photo_unreadable))
        }
    }

    /** The model answers in the app language, named in English (unambiguous for the model). */
    private fun aiLanguage(): String = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).ifEmpty { "English" }

    fun recognizePhoto(jpeg: ByteArray, hint: String, useCache: Boolean = true) {
        val h = hint.trim().ifEmpty { null }
        val lang = aiLanguage()
        runAi(Source.PHOTO, jpeg, Keys.photo(jpeg, h, lang), useCache, { recognizePhoto(jpeg, hint, useCache = false) }) {
            it.recognizePhoto(jpeg, h, lang)
        }
    }

    fun recognizeText(text: String, useCache: Boolean = true) {
        val lang = aiLanguage()
        runAi(Source.TEXT, null, Keys.description(text, lang), useCache, { recognizeText(text, useCache = false) }) {
            it.recognizeText(text.trim(), lang)
        }
    }

    /**
     * Claude request with a cache: the same photo or the same description with the same hint
     * is answered from the database for free. askAgain - the same request bypassing the cache.
     */
    private fun runAi(
        source: Source,
        jpeg: ByteArray?,
        cacheKey: String,
        useCache: Boolean,
        askAgain: () -> Unit,
        request: (ClaudeFood) -> AiResponse,
    ) {
        val dao = FoodDb.get(this).dao()
        val key = ApiKeyStore.load(this)
        overlay = Overlay.Busy(R.string.busy_ai)
        work = lifecycleScope.launch {
            try {
                val cached = if (useCache) dao.aiCached(cacheKey) else null
                val result = if (cached != null) {
                    AiParser.parse(cached.json)
                } else {
                    if (key == null) {
                        overlay = Overlay.Failed(AiFailure.BAD_KEY, null)
                        return@launch
                    }
                    val r = withContext(Dispatchers.IO) { ClaudeFood(key).use(request) }
                    prefs.addUsage(r.usage.inputTokens, r.usage.outputTokens)
                    // An empty answer ("no food") is not cached: the photo will most likely be retaken.
                    if (r.result.items.isNotEmpty()) {
                        dao.putAiCached(AiCached(cacheKey, r.json, System.currentTimeMillis()))
                        dao.trimAiCache(AI_CACHE_KEEP)
                    }
                    r.result
                }
                overlay = if (result.items.isEmpty()) {
                    Overlay.Failed(null, result.note ?: getString(R.string.ai_nothing))
                } else {
                    Overlay.Review(
                        result.items.map { ReviewItem(it.draft, it.confidence) }, source, result.note, jpeg,
                        fromCache = cached != null, askAgain = askAgain,
                    )
                }
            } catch (e: AiException) {
                if (e.failure == AiFailure.BAD_KEY) prefs.keyStatus = Prefs.KEY_BAD
                overlay = Overlay.Failed(e.failure, e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                overlay = Overlay.Failed(null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun scanBarcode() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { b -> b.rawValue?.let(::lookupBarcode) }
            .addOnFailureListener { e -> overlay = Overlay.Failed(null, getString(R.string.scan_failed, e.message ?: "")) }
    }

    private fun lookupBarcode(code: String) {
        overlay = Overlay.Busy(R.string.busy_barcode)
        work = lifecycleScope.launch {
            try {
                val p = OffApi.find(this@MainActivity, code)
                overlay = if (p == null) {
                    Overlay.NotFound(code)
                } else {
                    val name = listOfNotNull(p.name, p.brand?.takeIf { !p.name.contains(it, ignoreCase = true) }).joinToString(", ")
                    Overlay.Review(listOf(ReviewItem(FoodDraft(name, p.servingG ?: 100.0, p.per100))), Source.BARCODE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                overlay = Overlay.Failed(AiFailure.NETWORK, e.message)
            }
        }
    }

    fun defaultMeal(): Meal = Meal.byHour(LocalTime.now().hour)

    fun save(items: List<FoodDraft>, meal: Meal, source: Source, editing: Entry?) {
        val dao = FoodDb.get(this).dao()
        val now = System.currentTimeMillis()
        lifecycleScope.launch {
            dao.rememberDishes(items, now)
            if (editing != null) {
                val d = items.first()
                val t = d.total
                dao.update(editing.copy(meal = meal.key, name = d.name, grams = d.grams, kcal = t.kcal, protein = t.protein, fat = t.fat, carbs = t.carbs))
            } else {
                dao.insert(
                    items.mapIndexed { i, d ->
                        val t = d.total
                        Entry(0, now + i, day, meal.key, d.name, d.grams, t.kcal, t.protein, t.fat, t.carbs, source.key)
                    }
                )
            }
        }
        overlay = null
    }

    fun delete(e: Entry) {
        lifecycleScope.launch { FoodDb.get(this@MainActivity).dao().delete(e) }
        overlay = null
    }
}
