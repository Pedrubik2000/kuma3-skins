package io.github.pedrubik2000.kuma3skins

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Base64
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/** kuma3 Skins: reps-only reviewers of kuma3's cards, each skin one page (assets/<skin>/index.html: anki = kuma3 Anki's own look, xp = Anki XP,
 *  hunger = Anki Hunger, joker = Anki Joker); assets/index.html picks the skin. [Bridge] gives the pages kuma3's decks
 *  and cards; scheduling (FSRS-7/RWKV), media and sync stay kuma3's job. */
class MainActivity : Activity() {
    private lateinit var web: WebView

    /** The first Anki installed here, kuma3 Anki preferred. */
    private val pkg get() = PACKAGES.firstOrNull { packageManager.resolveContentProvider("$it.flashcards", 0) != null }

    private val prefs get() = getSharedPreferences("kuma3-skins", MODE_PRIVATE)

    /** The chosen skin, or null for the picker. */
    private val skin get() = prefs.getString("skin", null)?.takeIf { it in SKINS }

    /** Another skin (null = the picker): a fresh activity, so the new page gets its own bridge token. */
    private fun useSkin(name: String?) {
        prefs.edit().putString("skin", name).apply()
        recreate()
    }

    /** This page is kuma3 Skins' settings (assets/settings), opened from a skin; Back returns to the skin. */
    private var inSettings = false

    /** The settings page (true) or back to the skin: a fresh activity, like a skin switch. */
    private fun showSettings(open: Boolean) {
        prefs.edit().putBoolean("settings", open).apply()
        recreate()
    }

    /** Deck ids hidden in every skin (Settings > Decks), each on its own: a subdeck can stay when its parent is hidden. */
    private var hiddenDecks: Set<String>
        get() = prefs.getStringSet("hidden_decks", emptySet())!!
        set(ids) = prefs.edit().putStringSet("hidden_decks", ids).apply()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Draw behind the camera cutout too, or full screen leaves a black band on phones.
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.setDecorFitsSystemWindows(false)
        web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true // card media from /sdcard/<collection>/collection.media
            settings.mediaPlaybackRequiresUserGesture = false
            // Frames (the card) load here; the main frame stays this app's page, so a card can't swap it out.
            // A link that tries to leave opens in the browser.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    if (!req.isForMainFrame) return false
                    if (req.url.scheme == "http" || req.url.scheme == "https") runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }
                    return true
                }
            }
            addJavascriptInterface(Gate(), "Anki")
            inSettings = prefs.getBoolean("settings", false)
            prefs.edit().remove("settings").apply()
            loadUrl("file:///android_asset/${if (inSettings) "settings/" else skin?.let { "$it/" } ?: ""}index.html")
        }
        setContentView(web)
        // targetSdk 36 (predictive back) no longer calls onBackPressed on Android 13+.
        if (Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
    }

    override fun onResume() {
        super.onResume()
        // Full screen: the page draws its own taskbar and clock.
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        web.evaluateJavascript("window.refresh && refresh()", null)
    }

    override fun onPause() {
        super.onPause()
        web.evaluateJavascript("window.away && away()", null) // music stops when you leave the app
    }

    /** A skin switch recreates the activity: the old page must go (its music, its memory). */
    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) =
        web.evaluateJavascript("window.refresh && refresh()", null)

    @Deprecated("Android 11-12 only; 13+ uses the OnBackInvokedCallback above")
    override fun onBackPressed() = back()

    /** kuma3 Skins is the device's home app (Anki XP's desktop): Back never closes it. */
    private val isHome get() = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName

    /** Back the page doesn't use: from the settings to the skin, from a skin to the picker, from the picker out. */
    private fun back() = web.evaluateJavascript("window.back ? back() : false") {
        if (it == "true") return@evaluateJavascript
        if (inSettings) showSettings(false) else if (skin != null) useSkin(null) else if (!isHome) finish()
    }

    /** A gamepad (8BitDo Micro): D-pad, A B X Y, L1 R1 go to the page's pad(); nothing else of
     *  it reaches Android (its B would be Back). The D-pad repeats while held, the buttons don't. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = PAD[e.keyCode] ?: return super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_DOWN && (e.repeatCount == 0 || k in DPAD))
            web.evaluateJavascript("window.pad && pad('$k')", null)
        return true
    }

    /** The 8BitDo Micro's D-pad comes as the stick axes X/Y (Android gamepad mode; a hat on other pads), not D-pad keys: one pad() per press. */
    private var hat = ""
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return super.dispatchGenericMotionEvent(e)
        val x = e.getAxisValue(MotionEvent.AXIS_HAT_X) + e.getAxisValue(MotionEvent.AXIS_X)
        val y = e.getAxisValue(MotionEvent.AXIS_HAT_Y) + e.getAxisValue(MotionEvent.AXIS_Y)
        val dir = when { y < -.5f -> "up"; y > .5f -> "down"; x < -.5f -> "left"; x > .5f -> "right"; else -> "" }
        if (dir != hat && dir.isNotEmpty()) web.evaluateJavascript("window.pad && pad('$dir')", null)
        hat = dir
        return true
    }

    /** The Home button while the app is already in front: show the desktop, like XP's Win+D. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME) && hasWindowFocus()) web.evaluateJavascript("window.home && home()", null)
    }

    /** The page's window.Anki. WebView puts it in every frame, the card's too (shared decks run their own scripts),
     *  so each call needs a token only the app's page gets: it asks first, before any card is shown. */
    inner class Gate {
        private val secret = UUID.randomUUID().toString()
        private var given = false
        private val bridge = Bridge()

        @JavascriptInterface
        @Synchronized
        fun token(): String = if (given) "" else secret.also { given = true }

        /** [Bridge]'s method [name] with the JSON array [args] (strings and ints). */
        @JavascriptInterface
        fun call(t: String, name: String, args: String): String? {
            require(t == secret) { "no token" }
            val m = Bridge::class.java.methods.first { it.name == name && it.isAnnotationPresent(JavascriptInterface::class.java) }
            val a = JSONArray(args)
            val params = Array<Any>(m.parameterCount) { if (m.parameterTypes[it] == Int::class.javaPrimitiveType) a.getInt(it) else a.getString(it) }
            return m.invoke(bridge, *params)?.toString()
        }
    }

    /** The card is shown from its own file, so its page is cross-origin to the app's (file:// pages are). */
    private val cardFile get() = File(cacheDir, "card.html")

    /** Called by the page on the WebView's bridge thread, so provider queries don't block the UI thread. */
    inner class Bridge {
        private fun uri(path: String) = Uri.parse("content://$pkg.flashcards/$path")

        /** A cold kuma3 starts its provider before the app itself and refuses queries until then: retry for ~5 s. */
        private inline fun <T> ready(call: () -> T): T {
            repeat(20) {
                try {
                    return call()
                } catch (e: IllegalStateException) {
                    if (e.message?.contains("initialized") != true) throw e
                    Thread.sleep(250)
                }
            }
            return call()
        }

        @JavascriptInterface
        fun status(): String {
            val p = pkg
            val folder = if (p == KUMA3) "kuma3" else "AnkiDroid"
            val sprites = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "anki-hunger")
            return JSONObject()
                .put("pkg", p ?: JSONObject.NULL)
                .put("anki", p != null && checkSelfPermission("$p.permission.READ_WRITE_DATABASE") == PackageManager.PERMISSION_GRANTED)
                .put("media", Environment.isExternalStorageManager())
                .put("cardUrl", Uri.fromFile(cardFile).toString())
                .put("mediaBase", "file://${Environment.getExternalStorageDirectory()}/$folder/collection.media/")
                // Anki Hunger's Fear & Hunger battle sprites (never bundled): Pictures/anki-hunger/<name>.png
                .put("sprites", Uri.fromFile(sprites).toString() + "/")
                .put("have", JSONArray(sprites.list().orEmpty().map { it.substringBeforeLast('.') }))
                .toString()
        }

        /** Switch to skin [name] ("" = the skin picker). */
        @JavascriptInterface
        fun useSkin(name: String) = runOnUiThread { this@MainActivity.useSkin(name.takeIf { it in SKINS }) }

        /** kuma3 Skins' settings page (1) or back from it to the skin (0). */
        @JavascriptInterface
        fun settings(open: Int) = runOnUiThread { showSettings(open == 1) }

        /** Every deck with its "hidden" flag, for the settings page. */
        @JavascriptInterface
        fun allDecks(): String {
            val hidden = hiddenDecks
            val all = deckList()
            for (i in 0 until all.length()) all.getJSONObject(i).let { it.put("hidden", it.getString("id") in hidden) }
            return all.toString()
        }

        /** The decks to hide in every skin: a JSON array of deck ids. */
        @JavascriptInterface
        fun setHiddenDecks(ids: String) {
            val a = JSONArray(ids)
            hiddenDecks = (0 until a.length()).map { a.getString(it) }.toSet()
        }

        /** The card page to show next (the page then loads cardUrl in the card frame). */
        @JavascriptInterface
        fun setCard(html: String) = cardFile.writeText(html)

        @JavascriptInterface
        fun grantAnki() = runOnUiThread { pkg?.let { requestPermissions(arrayOf("$it.permission.READ_WRITE_DATABASE"), 1) } }

        @JavascriptInterface
        fun grantMedia() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }

        /** [{id, name, learn, due, new}] in deck-tree order ("A::B" names), without the decks hidden in the settings. */
        @JavascriptInterface
        fun decks(): String {
            val hidden = hiddenDecks
            val all = deckList().let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            val gone = all.filter { it.getString("id") in hidden }.map { it.getString("name") }.toSet()
            val out = JSONArray()
            for (d in all.filter { it.getString("id") !in hidden }) {
                // under a hidden parent it moves up a level: "Deutsch::Core 1000" -> "Deutsch › Core 1000"
                val parts = d.getString("name").split("::")
                val name = StringBuilder(parts[0])
                for (i in 1 until parts.size) name.append(if (parts.take(i).joinToString("::") in gone) " › " else "::").append(parts[i])
                out.put(d.put("name", name.toString()))
            }
            return out.toString()
        }

        private fun deckList(): JSONArray {
            val out = JSONArray()
            // kuma3_collapsed last: a kuma3 without kuma3 Skins' provider additions leaves it empty
            ready { contentResolver.query(uri("decks"), arrayOf("deck_id", "deck_name", "deck_count", "kuma3_collapsed"), null, null, null) }?.use { c ->
                while (c.moveToNext()) {
                    val n = JSONArray(c.getString(2)) // [learn, review, new]
                    out.put(JSONObject().put("id", c.getLong(0).toString()).put("name", c.getString(1))
                        .put("learn", n.getInt(0)).put("due", n.getInt(1)).put("new", n.getInt(2))
                        .put("collapsed", if (c.isNull(3)) JSONObject.NULL else c.getInt(3) == 1))
                }
            }
            return out
        }

        /** The deck's next card: {nid, ord, q, a, css, times[4]}, or null when the deck is done. */
        @JavascriptInterface
        fun next(deckId: String): String {
            // kuma3_queue (new/learn/review: the count to underline); an older kuma3 refuses the column
            val columns = arrayOf("note_id", "ord", "next_review_times", "kuma3_queue")
            val schedule = { cols: Array<String> -> contentResolver.query(uri("schedule"), cols, "limit=?,deckID=?", arrayOf("1", deckId), null) }
            val card = ready { runCatching { schedule(columns) }.getOrElse { if (it is IllegalStateException) throw it; schedule(columns.copyOf(3).requireNoNulls()) } }?.use { c ->
                if (!c.moveToFirst()) return "null"
                JSONObject().put("nid", c.getLong(0).toString()).put("ord", c.getInt(1)).put("times", JSONArray(c.getString(2)))
                    .apply { if (c.columnCount > 3) put("queue", c.getString(3)) }
            } ?: return "null"
            val path = "notes/${card.getString("nid")}"
            contentResolver.query(uri("$path/cards/${card.getInt("ord")}"), arrayOf("question", "answer"), null, null, null)?.use { c ->
                if (c.moveToFirst()) card.put("q", c.getString(0)).put("a", c.getString(1))
            }
            var flds = ""
            val mid = contentResolver.query(uri(path), arrayOf("mid", "flds"), null, null, null)?.use { c ->
                if (c.moveToFirst()) { flds = c.getString(1); c.getLong(0) } else null }
            contentResolver.query(uri("models/$mid"), arrayOf("field_names", "css", "name"), null, null, null)?.use { c ->
                if (c.moveToFirst()) card.put("fields", JSONObject(c.getString(0).split(SEP).zip(flds.split(SEP)).toMap()))
                    .put("css", c.getString(1)).put("model", c.getString(2))
            }
            // kuma3's cards query (an Anki search): lapses and flag for "improve this card first".
            contentResolver.query(uri("cards"), arrayOf("ord", "lapses", "flags"), "nid:${card.getString("nid")}", null, null)?.use { c ->
                while (c.moveToNext()) if (c.getInt(0) == card.getInt("ord")) card.put("lapses", c.getInt(1)).put("flag", c.getInt(2))
            }
            contentResolver.query(uri(path), arrayOf("tags"), null, null, null)?.use { c ->
                if (c.moveToFirst()) card.put("tags", c.getString(0))
            }
            return card.toString()
        }

        /** An improved card: the note's new tags (failure count baseline) and its flag cleared. */
        @JavascriptInterface
        fun improved(nid: String, ord: Int, tags: String) {
            ready { contentResolver.update(uri("notes/$nid"), ContentValues().apply { put("tags", tags) }, null, null) }
            contentResolver.update(uri("notes/$nid/cards/$ord"), ContentValues().apply { put("flags", 0) }, null, null)
        }

        /** The deck's boss of the day: its due card with the most lapses (1+), as {nid, ord, lapses}, or null. */
        @JavascriptInterface
        fun boss(deckId: String): String {
            var best: JSONObject? = null
            ready { contentResolver.query(uri("cards"), arrayOf("note_id", "ord", "lapses"), "did:$deckId is:due -is:suspended -is:buried", null, null) }?.use { c ->
                while (c.moveToNext()) if (c.getInt(2) > (best?.getInt("lapses") ?: 0))
                    best = JSONObject().put("nid", c.getLong(0).toString()).put("ord", c.getInt(1)).put("lapses", c.getInt(2))
            }
            return best?.toString() ?: "null"
        }

        /** Skip a card until tomorrow (kuma3 buries it). */
        @JavascriptInterface
        fun bury(nid: String, ord: Int) {
            val values = ContentValues().apply {
                put("note_id", nid.toLong())
                put("ord", ord)
                put("buried", 1)
            }
            ready { contentResolver.update(uri("schedule"), values, null, null) }
        }

        /** kumapie opened on its "Cards that don't stick" screen with this note picked (fresh task, so the extra is read). */
        @JavascriptInterface
        fun openKumapie(nid: String) = runOnUiThread {
            packageManager.getLaunchIntentForPackage(KUMAPIE)?.let {
                startActivity(it.putExtra("improve_note", nid.toLong()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }

        /** A vibration: "on,off,on,…" milliseconds (starts with a pause of 0). */
        @JavascriptInterface
        fun vibrate(pattern: String) {
            val timings = pattern.split(",").mapNotNull { it.trim().toLongOrNull() }.toLongArray()
            if (timings.isEmpty()) return
            val v = getSystemService(Vibrator::class.java) ?: return // minSdk 30: VibratorManager is 31+
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0) + timings, -1))
        }

        @JavascriptInterface
        fun model(): String = Build.MODEL

        // ---- game progress, synced through kuma3 like kumapie's: one note per device (note type PROGRESS, its card
        // suspended in the kumapie deck), written only by that device; every device reads all of them and merges.

        /** {device: data} of every device's progress note. */
        @JavascriptInterface
        fun progressRead(): String {
            val out = JSONObject()
            ready { contentResolver.query(uri("notes"), arrayOf("flds"), "\"note:$PROGRESS\"", null, null) }?.use { c ->
                while (c.moveToNext()) {
                    val f = c.getString(0).split(SEP)
                    if (f.size >= 2 && f[0].isNotEmpty()) runCatching { out.put(f[0], JSONObject(f[1])) }
                }
            }
            return out.toString()
        }

        /** This device's progress note, made (with its note type) the first time. */
        @JavascriptInterface
        fun progressWrite(device: String, data: String) {
            val flds = device + SEP + data
            val nid = ready { contentResolver.query(uri("notes"), arrayOf("_id", "flds"), "\"note:$PROGRESS\"", null, null) }?.use { c ->
                var found: Long? = null
                while (c.moveToNext()) if (c.getString(1).substringBefore(SEP) == device) found = c.getLong(0)
                found
            }
            if (nid != null) {
                contentResolver.update(uri("notes/$nid"), ContentValues().apply { put("flds", flds) }, null, null)
                return
            }
            val mid = contentResolver.query(uri("models"), arrayOf("_id", "name"), null, null, null)?.use { c ->
                var found: Long? = null
                while (c.moveToNext()) if (c.getString(1) == PROGRESS) found = c.getLong(0)
                found
            } ?: contentResolver.insert(uri("models"), ContentValues().apply {
                put("name", PROGRESS); put("field_names", "Device${SEP}Data"); put("num_cards", 1)
            })!!.lastPathSegment!!.toLong()
            val deck = contentResolver.query(uri("decks"), arrayOf("deck_id", "deck_name"), null, null, null)?.use { c ->
                var found: Long? = null
                while (c.moveToNext()) if (c.getString(1) == DECK) found = c.getLong(0)
                found
            } ?: contentResolver.insert(uri("decks"), ContentValues().apply { put("deck_name", DECK) })!!.lastPathSegment!!.toLong()
            val note = contentResolver.insert(uri("notes"), ContentValues().apply {
                put("mid", mid); put("flds", flds); put("tags", "anki-xp")
            })!!.lastPathSegment!!.toLong()
            // Its card goes to the deck and is suspended (kuma3's provider), so it never comes up for review.
            contentResolver.update(uri("notes/$note/cards/0"), ContentValues().apply { put("deck_id", deck) }, null, null)
            contentResolver.update(uri("notes/$note/cards/0"), ContentValues().apply { put("kuma3_suspend", true) }, null, null)
        }

        /** ease 1-4 = Again/Hard/Good/Easy, answered by kuma3's own scheduler. */
        @JavascriptInterface
        fun answer(nid: String, ord: Int, ease: Int, ms: Int) {
            val values = ContentValues().apply {
                put("note_id", nid.toLong())
                put("ord", ord)
                put("answer_ease", ease)
                put("time_taken", ms.toLong())
            }
            ready { contentResolver.update(uri("schedule"), values, null, null) }
        }

        /** kuma3 syncs (its Tasker intent; it opens kuma3 and allows one sync per 2 minutes). */
        @JavascriptInterface
        fun sync() = runOnUiThread {
            pkg?.let { startActivity(Intent("com.ichi2.anki.DO_SYNC").setPackage(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        /** Background music: the audio files in Music/anki-<skin> (made when missing; never bundled), as file:// URLs. */
        @JavascriptInterface
        fun music(): String {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "anki-$skin").apply { mkdirs() }
            val songs = dir.listFiles().orEmpty().filter { it.extension.lowercase() in AUDIO }.sortedBy { it.name }
            return JSONArray(songs.map { Uri.fromFile(it).toString() }).toString()
        }

        /** All Programs: the page's gotApps([{pkg, cls, label, icon}]) is called when the list is ready (~3 s of icons). */
        @JavascriptInterface
        fun loadApps() {
            thread {
                val list = apps()
                runOnUiThread { web.evaluateJavascript("gotApps($list)", null) }
            }
        }

        /** The launchable apps, A-Z (icon = a PNG data URL). */
        private fun apps(): String {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val out = JSONArray()
            packageManager.queryIntentActivities(main, 0).filter { it.activityInfo.packageName != packageName }
                .map { it to it.loadLabel(packageManager).toString() }.sortedBy { it.second.lowercase() }
                .forEach { (app, label) ->
                    out.put(JSONObject().put("pkg", app.activityInfo.packageName).put("cls", app.activityInfo.name)
                        .put("label", label).put("icon", png(app.loadIcon(packageManager))))
                }
            return out.toString()
        }

        private fun png(icon: Drawable): String {
            val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, 96, 96)
            icon.draw(Canvas(bmp))
            val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        @JavascriptInterface
        fun launch(pkg: String, cls: String) = runOnUiThread {
            runCatching { // an uninstalled desktop icon does nothing
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, cls)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            }
        }

        /** Desktop wallpaper: the first image in Pictures/anki-xp (made when missing) as a file:// URL, or "". */
        @JavascriptInterface
        fun wallpaper(): String {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "anki-xp").apply { mkdirs() }
            val pic = dir.listFiles().orEmpty().filter { it.extension.lowercase() in IMAGES }.minByOrNull { it.name }
            return pic?.let { Uri.fromFile(it).toString() } ?: ""
        }

        /** One of kuma3's own screens (kuma3-anki-app kuma3/Kuma3Screens.kt) with its [extras]. A kuma3 without them
         *  (older, or plain AnkiDroid): its deck list. */
        private fun kuma3Screen(screen: String, extras: Intent.() -> Unit = {}) = runOnUiThread {
            val p = pkg ?: return@runOnUiThread
            val open = Intent("com.ichi2.anki.kuma3.OPEN_SCREEN").setPackage(p).putExtra("screen", screen).apply(extras)
            if (open.resolveActivity(packageManager) != null) startActivity(open)
            else packageManager.getLaunchIntentForPackage(p)?.let(::startActivity)
        }

        /** stats, browser, add, deck_options, settings, kuma3_settings, note_types; [deckId] "" = none. */
        @JavascriptInterface
        fun openScreen(screen: String, deckId: String) = kuma3Screen(screen) { deckId.toLongOrNull()?.let { putExtra("deck_id", it) } }

        /** kuma3's note editor on the card being reviewed (its "edit" screen); Back returns to the skin. */
        @JavascriptInterface
        fun editNote(nid: String, ord: Int) = kuma3Screen("edit") { putExtra("note_id", nid.toLong()).putExtra("ord", ord) }

        /** Delete the note being reviewed (all its cards) after Android's own confirmation, then the page's
         *  cardDeleted() (or refresh()) shows the next card. Deleted through kuma3's provider, so it syncs like any delete. */
        @JavascriptInterface
        fun deleteNote(nid: String) {
            val id = nid.toLong()
            val cards = runCatching { contentResolver.query(uri("notes/$id/cards"), arrayOf("ord"), null, null, null)?.use { it.count } }.getOrNull() ?: 1
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Delete this note?")
                    .setMessage((if (cards > 1) "Its $cards cards are deleted too. " else "") + "This can't be undone here.")
                    .setPositiveButton("Delete") { _, _ ->
                        thread {
                            runCatching { contentResolver.delete(uri("notes/$id"), null, null) }
                            runOnUiThread { web.evaluateJavascript("window.cardDeleted ? cardDeleted() : refresh()", null) }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        /** The card's flag: 0 none, 1 red, 2 orange, 3 green, 4 blue, 5 pink, 6 turquoise, 7 purple. */
        @JavascriptInterface
        fun flag(nid: String, ord: Int, flag: Int) {
            ready { contentResolver.update(uri("notes/$nid/cards/$ord"), ContentValues().apply { put("flags", flag) }, null, null) }
        }

        /** Suspend the card (kuma3's provider), like Suspend card in kuma3's reviewer. */
        @JavascriptInterface
        fun suspend(nid: String, ord: Int) {
            ready { contentResolver.update(uri("notes/$nid/cards/$ord"), ContentValues().apply { put("kuma3_suspend", true) }, null, null) }
        }

        /** Notes matching an Anki search ("deck:Deutsch vorbei"), newest first, at most 200: [{nid, sfld, tags}]. */
        @JavascriptInterface
        fun searchNotes(query: String): String {
            val out = JSONArray()
            ready { contentResolver.query(uri("notes"), arrayOf("_id", "sfld", "tags"), query, null, "id desc") }?.use { c ->
                while (c.moveToNext() && out.length() < 200)
                    out.put(JSONObject().put("nid", c.getLong(0).toString()).put("sfld", c.getString(1)).put("tags", c.getString(2)))
            }
            return out.toString()
        }

        /** kuma3's full card browser on an Anki search. */
        @JavascriptInterface
        fun browse(search: String) = kuma3Screen("browser") { putExtra("search", search) }

        // ---- kuma3 Skins' provider additions in kuma3 (kuma3/today, kuma3/undo, decks/# kuma3_collapsed); an older
        // kuma3 doesn't have them: today() and undoLabel() then give null and "".

        /** kuma3's "Studied N cards in M minutes today" as {cards, seconds}, or null. */
        @JavascriptInterface
        fun today(): String = runCatching {
            contentResolver.query(uri("kuma3/today"), null, null, null, null)?.use { c ->
                if (c.moveToFirst()) JSONObject().put("cards", c.getInt(0)).put("seconds", c.getDouble(1)).toString() else null
            }
        }.getOrNull() ?: "null"

        /** What kuma3's Undo would undo now ("Undo Answer Card"…), "" = nothing. */
        @JavascriptInterface
        fun undoLabel(): String = runCatching {
            contentResolver.query(uri("kuma3/undo"), null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "" }
        }.getOrNull() ?: ""

        /** kuma3's Undo (its last change, whichever app made it): 1 when something was undone. */
        @JavascriptInterface
        fun undo(): Int = runCatching { contentResolver.update(uri("kuma3/undo"), ContentValues(), null, null) }.getOrDefault(0)

        /** A deck collapsed (1) or expanded (0) in kuma3's own deck list too. */
        @JavascriptInterface
        fun setCollapsed(deckId: String, collapsed: Int) {
            runCatching { contentResolver.update(uri("decks/$deckId"), ContentValues().apply { put("kuma3_collapsed", collapsed == 1) }, null, null) }
        }

        @JavascriptInterface
        fun openAnki() = runOnUiThread { pkg?.let { packageManager.getLaunchIntentForPackage(it)?.let(::startActivity) } }
    }

    companion object {
        const val KUMA3 = "io.github.pedrubik2000.kuma3"
        const val KUMAPIE = "io.github.pedrubik2000.kumapie.mobile"
        const val PROGRESS = "🐻 Anki XP progress" // every skin's: they share XP, reps and the streak
        val SKINS = setOf("anki", "xp", "hunger", "joker")
        const val DECK = "kumapie"
        const val SEP = "\u001f"
        val PACKAGES = listOf(KUMA3, "com.ichi2.anki.debug", "com.ichi2.anki")
        val IMAGES = setOf("jpg", "jpeg", "png", "webp")
        val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
        val DPAD = setOf("up", "down", "left", "right")
        val PAD = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to "up", KeyEvent.KEYCODE_DPAD_DOWN to "down",
            KeyEvent.KEYCODE_DPAD_LEFT to "left", KeyEvent.KEYCODE_DPAD_RIGHT to "right",
            KeyEvent.KEYCODE_BUTTON_A to "a", KeyEvent.KEYCODE_BUTTON_B to "b",
            KeyEvent.KEYCODE_BUTTON_X to "x", KeyEvent.KEYCODE_BUTTON_Y to "y",
            KeyEvent.KEYCODE_BUTTON_L1 to "l1", KeyEvent.KEYCODE_BUTTON_R1 to "r1")
    }
}
