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
import com.calorie.app.ai.AiQueue
import com.calorie.app.ai.AiRequest
import com.calorie.app.ai.Photo
import com.calorie.app.data.ApiKeyStore
import com.calorie.app.data.Entry
import com.calorie.app.data.FoodDb
import com.calorie.app.data.Meal
import com.calorie.app.data.OffApi
import com.calorie.app.data.Pending
import com.calorie.app.data.PulsarData
import com.calorie.app.data.PulsarSource
import com.calorie.app.data.PulsarStatus
import com.calorie.app.logic.Burn
import com.calorie.app.logic.Calibration
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
/** Background time after which the diary returns to today. */
private const val RETURN_TO_TODAY_MS = 10 * 60_000L

/** Draft row on the review screen; confidence is set only for Claude results. */
data class ReviewItem(val draft: FoodDraft, val confidence: Confidence? = null)

/** Screen on top of the tabs: adding food step by step. */
sealed interface Overlay {
    data object AddMenu : Overlay
    data class TextInput(val text: String = "") : Overlay
    data class Manual(val name: String = "") : Overlay
    class PhotoHint(val jpeg: ByteArray, val hint: String = "") : Overlay
    /** back - where Cancel leads: the input screen with what was typed, not an empty menu. */
    data class Busy(val text: Int, val back: Overlay? = null) : Overlay
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
        /** Answer from the retry queue: its row goes away once saved; day and meal are the request's. */
        val pendingId: Long? = null,
        val day: Long? = null,
        val meal: Meal? = null,
    ) : Overlay
    data class NotFound(val barcode: String) : Overlay
    /** back - the input screen to return to; retry - send the same request again. */
    class Failed(val failure: AiFailure?, val detail: String?, val back: Overlay? = null, val retry: (() -> Unit)? = null) : Overlay
    /** A request in the retry queue: waiting for Claude or failed for good. */
    data class Queued(val id: Long) : Overlay
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
    /** Walks and workouts from Pulsar for the last two weeks; refreshed on every resume. */
    var pulsar by mutableStateOf(PulsarData(PulsarStatus.NOT_INSTALLED))

    /** Requested tab (from the diary - "go to day"); -1 - no request. */
    var tabRequest by mutableIntStateOf(-1)

    /** Which day was today when last shown: if it was being viewed, move to the new today. */
    private var shownToday = LocalDate.now().toEpochDay()
    /** When the app went to the background; 0 - it is in the foreground. */
    private var stoppedAt = 0L
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

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        // Back after a while with no food being added: show today, not the day looked at
        // earlier - otherwise the next meal quietly lands on that old day. The camera and
        // the photo picker also stop the activity, but then an overlay is open.
        val away = System.currentTimeMillis() - stoppedAt
        if (stoppedAt > 0 && away > RETURN_TO_TODAY_MS && overlay == null) day = LocalDate.now().toEpochDay()
        stoppedAt = 0
    }

    override fun onResume() {
        super.onResume()
        // Opened on the next day: if "today" was shown, show the new today.
        val today = LocalDate.now().toEpochDay()
        if (today != shownToday) {
            if (day == shownToday) day = today
            shownToday = today
        }
        lifecycleScope.launch { pulsar = withContext(Dispatchers.IO) { PulsarSource.load(this@MainActivity) } }
        // A delayed retry may be held back by Doze: the app is open, so try now if due.
        lifecycleScope.launch { AiQueue.schedule(this@MainActivity) }
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

    /** Profile as a Body for the formulas; null - the profile is incomplete. */
    fun body(lastWeightKg: Double?): Body? {
        val sex = prefs.sex ?: return null
        val w = lastWeightKg ?: return null
        val birth = prefs.birthDate ?: return null
        if (prefs.heightCm == 0) return null
        return Body(sex, Goals.age(birth), prefs.heightCm, w)
    }

    /** Activity level measured by Pulsar, if it is switched on and there are enough days. */
    fun calibration(lastWeightKg: Double?): Calibration? {
        if (!prefs.activityFromPulsar || pulsar.status != PulsarStatus.OK) return null
        val b = body(lastWeightKg) ?: return null
        return Burn.calibrate(pulsar.minutes, b, LocalDate.now())
    }

    /** Target from the profile and the latest weight; null - the profile is incomplete. */
    fun target(lastWeightKg: Double?): Target? {
        val body = body(lastWeightKg) ?: return null
        val factor = calibration(lastWeightKg)?.factor ?: prefs.activity.factor
        val t = Goals.target(body, factor, prefs.pace, prefs.goalKg.takeIf { it > 0 }?.toDouble())
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
        overlay = when (val o = overlay) {
            null, Overlay.AddMenu, is Overlay.Queued -> null
            // A queued answer stays on Today until it is saved or deleted.
            is Overlay.Review -> if (o.editing != null || o.pendingId != null) null else Overlay.AddMenu
            is Overlay.Busy -> o.back ?: Overlay.AddMenu
            is Overlay.Failed -> o.back ?: Overlay.AddMenu
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
        val h = hint.trim()
        val lang = aiLanguage()
        val req = AiRequest(Source.PHOTO, h, lang, jpeg, Keys.photo(jpeg, h.ifEmpty { null }, lang), useCache)
        runAi(req, Overlay.PhotoHint(jpeg, hint)) { recognizePhoto(jpeg, hint, useCache = false) }
    }

    fun recognizeText(text: String, useCache: Boolean = true) {
        val lang = aiLanguage()
        val req = AiRequest(Source.TEXT, text.trim(), lang, null, Keys.description(text, lang), useCache)
        runAi(req, Overlay.TextInput(text)) { recognizeText(text, useCache = false) }
    }

    /**
     * Claude request with a cache: the same photo or the same description with the same hint
     * is answered from the database for free. A temporary failure puts the request into the
     * retry queue instead of an error screen. back - the input screen with what was entered;
     * askAgain - the same request bypassing the cache.
     */
    private fun runAi(req: AiRequest, back: Overlay, askAgain: () -> Unit) {
        val key = ApiKeyStore.load(this)
        overlay = Overlay.Busy(R.string.busy_ai, back)
        work = lifecycleScope.launch {
            try {
                if (key == null && (!req.useCache || FoodDb.get(this@MainActivity).dao().aiCached(req.cacheKey) == null)) {
                    overlay = Overlay.Failed(AiFailure.BAD_KEY, null, back)
                    return@launch
                }
                // No key but a cached answer: ask() returns it without a request.
                val a = AiQueue.ask(this@MainActivity, key ?: "", req)
                overlay = if (a.result.items.isEmpty()) {
                    Overlay.Failed(null, a.result.note ?: getString(R.string.ai_nothing), back)
                } else {
                    Overlay.Review(
                        a.result.items.map { ReviewItem(it.draft, it.confidence) }, req.source, a.result.note, req.jpeg,
                        fromCache = a.fromCache, askAgain = askAgain,
                    )
                }
            } catch (e: AiException) {
                if (e.failure == AiFailure.BAD_KEY) prefs.keyStatus = Prefs.KEY_BAD
                overlay = if (e.failure.retryable) {
                    Overlay.Queued(AiQueue.enqueue(this@MainActivity, req, day, defaultMeal().key, e.failure, e.message))
                } else {
                    val retry = if (e.failure == AiFailure.BAD_ANSWER) ({ runAi(req, back, askAgain) }) else null
                    Overlay.Failed(e.failure, e.message, back, retry)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                overlay = Overlay.Failed(null, e.message ?: e.javaClass.simpleName, back)
            }
        }
    }

    /** Opens the answer of a queued request on the review screen. */
    fun openPendingReview(p: Pending) {
        val json = p.json ?: return
        work = lifecycleScope.launch {
            val result = runCatching { AiParser.parse(json) }.getOrNull()
            if (result == null || result.items.isEmpty()) {
                FoodDb.get(this@MainActivity).dao().pendingFailed(p.id, AiFailure.BAD_ANSWER.name, null)
                overlay = Overlay.Queued(p.id)
                return@launch
            }
            overlay = Overlay.Review(
                result.items.map { ReviewItem(it.draft, it.confidence) }, Source.byKey(p.source), result.note,
                AiQueue.jpeg(this@MainActivity, p), pendingId = p.id, day = p.day, meal = Meal.byKey(p.meal),
            )
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

    fun save(items: List<FoodDraft>, meal: Meal, source: Source, editing: Entry?, day: Long = this.day, pendingId: Long? = null) {
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
            // After the insert: if the app dies in between, a duplicate is better than a lost meal.
            if (pendingId != null) AiQueue.delete(this@MainActivity, pendingId)
        }
        overlay = null
    }

    fun delete(e: Entry) {
        lifecycleScope.launch { FoodDb.get(this@MainActivity).dao().delete(e) }
        overlay = null
    }
}
