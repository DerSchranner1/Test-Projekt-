package de.schranner.tastatur

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Bereich für Emojis (alle, mit Suche und Hauttönen), GIFs und Sticker. */
@SuppressLint("ViewConstructor")
class MediaPanel(
    context: Context,
    private val theme: KeyboardTheme,
    private val giphy: GiphyClient,
    private val stickers: StickerStore,
    private val host: Host,
) : LinearLayout(context) {

    enum class Tab { EMOJI, GIF, STICKER }
    private enum class StickerSource { ANIMATED, EMOJI, OWN, GIPHY }

    interface Host {
        fun commitEmoji(e: String)
        fun commitGif(item: GiphyClient.Item, sticker: Boolean)
        fun commitStickerFile(f: File)
        fun commitEmojiSticker(e: String)
        /** Animiertes Emoji als GIF senden (funktioniert ohne Schlüssel). */
        fun commitAnimatedEmoji(e: String)
        fun deleteChar()
        fun backToKeyboard()
        fun startSearch(tab: Tab)
        fun openSettings(page: String)
        fun haptic(v: View)
    }

    private val main = Handler(Looper.getMainLooper())
    private val store = context.getSharedPreferences("emoji", Context.MODE_PRIVATE)
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    var tab = Tab.EMOJI
        private set
    private var emojiCategory = 0
    private var emojiQuery: String? = null
    private var stickerSource = StickerSource.ANIMATED
    private var gifQuery: String? = null
    private var stickerQuery: String? = null
    private var loadToken = 0

    // Inhalte
    /** Emoji-Liste: Überschriften und Zeilen mit Emojis – alle Gruppen untereinander, wie bei Gboard. */
    private sealed class Row {
        class Header(val title: String) : Row()
        class Cells(val items: List<EmojiData.Emoji>) : Row()
    }
    private var emojiRows: List<Row> = emptyList()
    /** Zeile, bei der jede Gruppe der unteren Leiste beginnt (0 = zuletzt verwendet). */
    private var sectionStart = IntArray(0)
    private var columns = 8
    private var gifItems: List<GiphyClient.Item> = emptyList()
    private var ownItems: List<File> = emptyList()
    /** Große Emojis im Raster (Emoji-Sticker oder animierte Emojis). */
    private var glyphItems: List<String> = emptyList()
    private var glyphAnimated = false

    private val tabViews = HashMap<Tab, TextView>()
    private val emojiList = android.widget.ListView(context)
    private val variantRow = LinearLayout(context)
    private val variantScroll = HorizontalScrollView(context)
    private val mediaBox = LinearLayout(context)
    private val chipRow = LinearLayout(context)
    private val status = TextView(context)
    private val mediaGrid = GridView(context)
    private val attribution = TextView(context)
    private val emojiCats = LinearLayout(context)
    private val emojiCatViews = mutableListOf<TextView>()

    // ------------------------------------------------------------------ Steuerung

    fun select(t: Tab) {
        tab = t
        tabViews.forEach { (k, v) -> styleChip(v, k == t) }
        hideVariants()
        emojiList.visibility = if (t == Tab.EMOJI) VISIBLE else GONE
        mediaBox.visibility = if (t == Tab.EMOJI) GONE else VISIBLE
        emojiCats.visibility = if (t == Tab.EMOJI) VISIBLE else INVISIBLE
        when (t) {
            Tab.EMOJI -> if (emojiQuery != null) showEmojiSearch() else showAllEmojis(emojiCategory)
            Tab.GIF -> showGifs()
            Tab.STICKER -> { buildStickerChips(); showStickerSource() }
        }
    }

    /** Ergebnis der Suche aus der Tastatur. */
    fun search(t: Tab, query: String) {
        val q = query.trim().ifEmpty { null }
        when (t) {
            Tab.EMOJI -> emojiQuery = q
            Tab.GIF -> gifQuery = q
            Tab.STICKER -> { stickerQuery = q; stickerSource = StickerSource.GIPHY }
        }
        select(t)
    }

    /** Beim Öffnen: Trend-GIFs schon vorladen, wenn ein GIPHY-Schlüssel eingerichtet ist. */
    fun prefetch() {
        if (giphy.hasKey()) ImageLoader.run { giphy.prefetchTrending() }
    }

    fun refreshRecents() {
        // Beim Öffnen: zuletzt verwendete oben, darunter alle Emojis
        if (tab == Tab.EMOJI && emojiQuery == null) showAllEmojis(0)
    }

    private fun columnCount(): Int {
        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        return ((w - dp(8f)) / dp(42f)).coerceIn(5, 14)
    }

    /** Alle Emojis als eine durchgehende Liste; [jumpTo] springt zu einer Gruppe (0 = Anfang). */
    private fun showAllEmojis(jumpTo: Int) {
        EmojiData.ensureLoaded(context)
        emojiQuery = null
        columns = columnCount()
        val rows = ArrayList<Row>()
        val starts = IntArray(EmojiData.categories.size + 1)
        val recent = recentEmojis().take(columns * 2).map { EmojiData.Emoji(it, false, emptyList(), "") }
        if (recent.isNotEmpty()) {
            rows += Row.Header("Zuletzt verwendet")
            recent.chunked(columns).forEach { rows += Row.Cells(it) }
        }
        EmojiData.categories.forEachIndexed { i, c ->
            starts[i + 1] = rows.size
            rows += Row.Header(c.name)
            c.emojis.chunked(columns).forEach { rows += Row.Cells(it) }
        }
        emojiRows = rows
        sectionStart = starts
        emojiAdapter.notifyDataSetChanged()
        selectEmojiCategory(jumpTo)
    }

    private fun selectEmojiCategory(i: Int) {
        emojiCategory = i
        if (emojiQuery != null) { showAllEmojis(i); return }
        emojiList.setSelection(sectionStart.getOrElse(i) { 0 })
        // Ohne zuletzt verwendete Emojis beginnt die Liste direkt mit den Smileys
        highlightCategory(if (i == 0 && sectionStart.getOrElse(1) { 0 } == 0) 1 else i)
    }

    private fun highlightCategory(i: Int) {
        emojiCatViews.forEachIndexed { j, tv -> tv.alpha = if (j == i) 1f else 0.45f }
    }

    private fun showEmojiSearch() {
        EmojiData.ensureLoaded(context)
        val q = emojiQuery ?: return
        columns = columnCount()
        val found = EmojiData.search(q)
        val rows = ArrayList<Row>()
        rows += Row.Header(if (found.isEmpty()) "Kein Emoji für „$q“ gefunden" else "Suche: $q")
        found.chunked(columns).forEach { rows += Row.Cells(it) }
        emojiRows = rows
        emojiAdapter.notifyDataSetChanged()
        emojiList.setSelection(0)
        emojiCatViews.forEach { it.alpha = 0.45f }
    }

    // ---- Hauttöne und andere Varianten (langes Drücken)

    private fun preferred(e: EmojiData.Emoji): String =
        if (e.alternates.isEmpty()) e.text else store.getString("v_" + e.text, e.text) ?: e.text

    private fun showVariants(e: EmojiData.Emoji): Boolean {
        val options = (listOf(e.text) + e.alternates).filter { EmojiData.isSupported(it) }
        if (options.size < 2) return false
        variantRow.removeAllViews()
        for (o in options) {
            variantRow.addView(TextView(context).apply {
                text = o
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                setOnClickListener {
                    // Die Wahl merken: beim nächsten Mal steht diese Variante im Raster
                    store.edit().putString("v_" + e.text, o).apply()
                    host.commitEmoji(o); host.haptic(it)
                    hideVariants()
                    emojiAdapter.notifyDataSetChanged()
                }
            }, LayoutParams(dp(48f), dp(46f)))
        }
        variantScroll.scrollTo(0, 0)
        variantScroll.visibility = VISIBLE
        return true
    }

    private fun hideVariants() { variantScroll.visibility = GONE }

    // ---- GIFs

    private fun showGifs() {
        chipRow.removeAllViews()
        if (!giphy.hasKey()) {
            // Ohne GIPHY-Schlüssel: animierte Emojis funktionieren sofort
            addChip("✨ Animierte Emojis", true) {}
            addChip("＋ GIFs aus Filmen und Memes", false) { host.openSettings(SettingsActivity.PAGE_MEDIA) }
            showAnimated(null)
            return
        }
        addChip("🔍 " + (gifQuery ?: "GIFs suchen"), gifQuery != null) { host.startSearch(Tab.GIF) }
        addChip("🔥 Trends", gifQuery == null) { gifQuery = null; select(Tab.GIF) }
        for (q in GIF_TOPICS) addChip(q, gifQuery == q) { gifQuery = q; select(Tab.GIF) }
        loadGiphy("gifs", gifQuery)
    }

    private fun showAnimated(hint: String?) {
        EmojiData.ensureLoaded(context)
        ownItems = emptyList()
        gifItems = emptyList()
        // Eingebaute Animationen zuerst (ohne Internet sofort da), dann alle weiteren
        val built = EmojiData.bundledOrder
        glyphItems = built + EmojiData.animated().map { it.text }.filter { it !in EmojiData.bundled }
        glyphAnimated = true
        attribution.visibility = GONE
        mediaGrid.numColumns = 5
        status.setOnClickListener(null)
        showStatus(hint)
        mediaAdapter.notifyDataSetChanged()
        mediaGrid.setSelection(0)
    }

    // ---- Sticker

    private fun buildStickerChips() {
        chipRow.removeAllViews()
        addChip("✨ Animiert", stickerSource == StickerSource.ANIMATED) { stickerSource = StickerSource.ANIMATED; select(Tab.STICKER) }
        addChip("😀 Emoji", stickerSource == StickerSource.EMOJI) { stickerSource = StickerSource.EMOJI; select(Tab.STICKER) }
        addChip("⭐ Eigene", stickerSource == StickerSource.OWN) { stickerSource = StickerSource.OWN; select(Tab.STICKER) }
        if (giphy.hasKey()) {
            addChip("🔍 " + (stickerQuery ?: "GIPHY"), stickerSource == StickerSource.GIPHY) {
                if (stickerSource == StickerSource.GIPHY) host.startSearch(Tab.STICKER)
                else { stickerSource = StickerSource.GIPHY; select(Tab.STICKER) }
            }
        }
        addChip("＋ Hinzufügen", false) { host.openSettings(SettingsActivity.PAGE_MEDIA) }
    }

    private fun showStickerSource() {
        if (stickerSource == StickerSource.GIPHY && !giphy.hasKey()) stickerSource = StickerSource.ANIMATED
        when (stickerSource) {
            StickerSource.ANIMATED -> showAnimated(null)
            StickerSource.OWN -> {
                ownItems = stickers.list()
                gifItems = emptyList()
                glyphItems = emptyList()
                attribution.visibility = GONE
                mediaGrid.numColumns = 4
                showStatus(if (ownItems.isEmpty()) "Noch keine eigenen Sticker.\nTippe auf „＋ Hinzufügen“, um Bilder aus deiner Galerie zu übernehmen." else null)
                mediaAdapter.notifyDataSetChanged()
            }
            StickerSource.EMOJI -> {
                EmojiData.ensureLoaded(context)
                ownItems = emptyList()
                gifItems = emptyList()
                glyphItems = stickerEmojis()
                glyphAnimated = false
                attribution.visibility = GONE
                mediaGrid.numColumns = 5
                showStatus(null)
                mediaAdapter.notifyDataSetChanged()
            }
            StickerSource.GIPHY -> loadGiphy("stickers", stickerQuery)
        }
    }

    private fun loadGiphy(kind: String, query: String?) {
        ownItems = emptyList()
        gifItems = emptyList()
        glyphItems = emptyList()
        mediaGrid.numColumns = if (kind == "gifs") 3 else 4
        attribution.visibility = VISIBLE
        mediaAdapter.notifyDataSetChanged()
        status.setOnClickListener(null)
        showStatus("Lädt …")
        val token = ++loadToken
        giphy.cachedResult(kind, query)?.let { cached ->
            // Schon vorgeladen: sofort zeigen
            gifItems = cached
            showStatus(if (cached.isEmpty()) "Nichts gefunden." else null)
            mediaAdapter.notifyDataSetChanged()
            mediaGrid.setSelection(0)
            return
        }
        ImageLoader.run {
            val result = try { giphy.search(kind, query) } catch (e: Exception) { null }
            main.post {
                if (token != loadToken) return@post
                if (result == null) {
                    showStatus("Konnte nicht laden. Bitte Internet und GIPHY-Schlüssel prüfen (hier tippen).")
                    status.setOnClickListener { host.openSettings(SettingsActivity.PAGE_MEDIA) }
                } else {
                    gifItems = result
                    showStatus(if (result.isEmpty()) "Nichts gefunden." else null)
                    mediaAdapter.notifyDataSetChanged()
                    mediaGrid.setSelection(0)
                }
            }
        }
    }

    /** Zustimmung einholen, bevor etwas aus dem Netz geladen wird. */
    fun askConsent(text: String, onAllow: () -> Unit) {
        showStatus(text)
        status.setOnClickListener {
            status.setOnClickListener(null)
            showStatus(null)
            onAllow()
        }
    }

    private fun showStatus(text: String?) {
        status.text = text ?: ""
        status.visibility = if (text == null) GONE else VISIBLE
    }

    private fun onMediaClick(pos: Int) {
        when {
            glyphItems.isNotEmpty() -> glyphItems.getOrNull(pos)?.let {
                if (glyphAnimated) host.commitAnimatedEmoji(it) else host.commitEmojiSticker(it)
            }
            ownItems.isNotEmpty() -> ownItems.getOrNull(pos)?.let { host.commitStickerFile(it) }
            else -> gifItems.getOrNull(pos)?.let { host.commitGif(it, tab == Tab.STICKER) }
        }
    }

    private fun onMediaLongClick(pos: Int): Boolean {
        if (tab == Tab.STICKER && stickerSource == StickerSource.OWN) {
            ownItems.getOrNull(pos)?.let {
                it.delete()
                Toast.makeText(context, "Sticker gelöscht", Toast.LENGTH_SHORT).show()
                showStickerSource()
            }
            return true
        }
        return false
    }

    private fun stickerEmojis(): List<String> =
        (recentEmojis() + EmojiData.categories[0].emojis.take(80).map { it.text } +
            EmojiData.categories[7].emojis.take(30).map { it.text }).distinct()

    // ------------------------------------------------------------------ Adapter

    private val emojiAdapter = object : BaseAdapter() {
        override fun getCount() = emojiRows.size
        override fun getItem(position: Int) = emojiRows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (emojiRows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = false
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            when (val row = emojiRows[position]) {
                is Row.Header -> {
                    val tv = (convertView as? TextView) ?: TextView(context).apply {
                        setTextColor(theme.hint)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setPadding(dp(10f), dp(8f), dp(10f), dp(2f))
                    }
                    tv.text = row.title
                    return tv
                }
                is Row.Cells -> {
                    val line = (convertView as? LinearLayout)?.takeIf { it.childCount == columns }
                        ?: LinearLayout(context).apply {
                            orientation = HORIZONTAL
                            setPadding(dp(4f), 0, dp(4f), 0)
                            repeat(columns) {
                                addView(TextView(context).apply {
                                    gravity = Gravity.CENTER
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                                }, LayoutParams(0, dp(44f), 1f))
                            }
                        }
                    for (i in 0 until columns) {
                        val cell = line.getChildAt(i) as TextView
                        val e = row.items.getOrNull(i)
                        if (e == null) {
                            cell.text = ""
                            cell.setOnClickListener(null)
                            cell.setOnLongClickListener(null)
                            cell.isClickable = false
                            continue
                        }
                        cell.text = preferred(e)
                        cell.setOnClickListener {
                            hideVariants()
                            host.commitEmoji(preferred(e)); host.haptic(it)
                        }
                        cell.setOnLongClickListener {
                            val shown = showVariants(e)
                            if (shown) host.haptic(it)
                            shown
                        }
                    }
                    return line
                }
            }
        }
    }

    private val mediaAdapter = object : BaseAdapter() {
        private fun glyphMode() = glyphItems.isNotEmpty()
        override fun getCount() = when {
            glyphMode() -> glyphItems.size
            ownItems.isNotEmpty() -> ownItems.size
            else -> gifItems.size
        }
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) = when {
            glyphMode() && glyphAnimated && glyphItems[position] in EmojiData.bundled -> 2
            glyphMode() -> 0
            else -> 1
        }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            if (getItemViewType(position) == 2) {
                // Eingebaute Animation: bewegt sich schon in der Übersicht
                val iv = (convertView as? ImageView) ?: ImageView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64f))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
                }
                val e = glyphItems[position]
                iv.contentDescription = e
                EmojiData.bundledAsset(e)?.let { ImageLoader.loadAsset(iv, context, it) }
                return iv
            }
            if (glyphMode()) {
                val tv = (convertView as? TextView) ?: TextView(context).apply {
                    gravity = Gravity.CENTER
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56f))
                }
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (glyphAnimated) 32f else 38f)
                tv.text = glyphItems[position]
                return tv
            }
            val iv = (convertView as? ImageView) ?: ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92f))
                background = GradientDrawable().apply { setColor(theme.specialKey); cornerRadius = dp(6f).toFloat() }
                clipToOutline = true
            }
            val sticker = tab == Tab.STICKER
            iv.scaleType = if (sticker) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            iv.layoutParams.height = dp(if (sticker) 80f else 92f)
            if (ownItems.isNotEmpty()) ImageLoader.loadFile(iv, ownItems[position])
            else gifItems.getOrNull(position)?.let { ImageLoader.load(iv, it.previewUrl) }
            return iv
        }
    }

    // ------------------------------------------------------------------ Bausteine

    fun recentEmojis(): List<String> =
        store.getString("recent", "")!!.split('\u0001').filter { it.isNotEmpty() }

    private fun addChip(label: String, active: Boolean, onClick: () -> Unit) {
        val c = chip(label, onClick)
        styleChip(c, active)
        chipRow.addView(c, LayoutParams(LayoutParams.WRAP_CONTENT, dp(30f)).apply { setMargins(dp(3f), dp(4f), dp(3f), dp(4f)) })
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(12f), 0, dp(12f), 0)
        setOnClickListener { onClick() }
    }

    private fun styleChip(v: TextView, active: Boolean) {
        v.background = GradientDrawable().apply {
            setColor(if (active) theme.accent else theme.key)
            cornerRadius = dp(16f).toFloat()
        }
        v.setTextColor(if (active) theme.accentText else theme.text)
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(theme.text)
        setOnClickListener { onClick() }
    }

    // Muss nach den Adaptern stehen (Reihenfolge der Initialisierung)
    init {
        orientation = VERTICAL
        setBackgroundColor(theme.background)
        EmojiData.ensureLoaded(context)

        // --- Reiter oben: Suche, Emoji, GIF, Sticker
        val tabs = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        val searchChip = chip("🔍") {
            if (tab == Tab.GIF && !giphy.hasKey()) host.startSearch(Tab.EMOJI) else host.startSearch(tab)
        }
        styleChip(searchChip, false)
        tabs.addView(searchChip, LayoutParams(dp(48f), dp(32f)).apply { setMargins(dp(4f), dp(4f), dp(2f), dp(2f)) })
        for ((t, label) in listOf(Tab.EMOJI to "Emoji", Tab.GIF to "GIF", Tab.STICKER to "Sticker")) {
            val tv = chip(label) { emojiQuery = null; select(t) }
            tabViews[t] = tv
            tabs.addView(tv, LayoutParams(0, dp(32f), 1f).apply { setMargins(dp(3f), dp(4f), dp(3f), dp(2f)) })
        }
        addView(tabs)

        // --- Varianten (Hauttöne), erscheint bei langem Druck auf ein Emoji
        variantRow.orientation = HORIZONTAL
        variantScroll.isHorizontalScrollBarEnabled = false
        variantScroll.background = GradientDrawable().apply { setColor(theme.popup); cornerRadius = dp(10f).toFloat() }
        variantScroll.addView(variantRow)
        variantScroll.visibility = GONE
        addView(variantScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(46f)).apply { setMargins(dp(6f), dp(2f), dp(6f), dp(2f)) })

        // --- Inhalt
        val contentFrame = FrameLayout(context)
        emojiList.apply {
            divider = null
            dividerHeight = 0
            setPadding(0, 0, 0, dp(4f))
            clipToPadding = false
            adapter = emojiAdapter
            // Nur Wischen: kein Schnell-Bildlauf-Griff am Rand (der würde die rechten Emojis verdecken)
            isVerticalScrollBarEnabled = false
            isFastScrollEnabled = false
            // Beim Blättern die passende Gruppe in der unteren Leiste hervorheben
            setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: android.widget.AbsListView?, state: Int) {}
                override fun onScroll(view: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {
                    if (emojiQuery != null || sectionStart.isEmpty()) return
                    var current = 0
                    for (i in sectionStart.indices) if (i > 0 && sectionStart[i] <= first) current = i
                    if (first == 0) current = if (sectionStart.getOrElse(1) { 0 } == 0) 1 else 0
                    highlightCategory(current)
                }
            })
        }
        contentFrame.addView(emojiList)

        mediaBox.orientation = VERTICAL
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        chipRow.orientation = HORIZONTAL
        chipRow.setPadding(dp(4f), 0, dp(4f), 0)
        scroll.addView(chipRow)
        mediaBox.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(38f)))
        status.apply {
            gravity = Gravity.CENTER
            setTextColor(theme.hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16f), dp(4f), dp(16f), dp(6f))
        }
        mediaBox.addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        mediaGrid.apply {
            numColumns = 3
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(3f)
            verticalSpacing = dp(3f)
            setPadding(dp(4f), 0, dp(4f), dp(4f))
            clipToPadding = false
            adapter = mediaAdapter
            setOnItemClickListener { _, v, pos, _ -> onMediaClick(pos); host.haptic(v) }
            setOnItemLongClickListener { _, _, pos, _ -> onMediaLongClick(pos) }
        }
        mediaBox.addView(mediaGrid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        attribution.apply {
            text = "Powered by GIPHY"
            setTextColor(theme.hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.END
            setPadding(0, 0, dp(8f), 0)
        }
        mediaBox.addView(attribution)
        contentFrame.addView(mediaBox)
        addView(contentFrame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // --- Leiste unten: zurück, Emoji-Gruppen, löschen
        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(theme.specialKey)
        }
        bottom.addView(button("ABC") { host.backToKeyboard() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTypeface(null, Typeface.BOLD)
        }, LayoutParams(dp(52f), dp(42f)))
        emojiCats.orientation = HORIZONTAL
        val icons = listOf("🕘") + EmojiData.categories.map { it.icon }
        icons.forEachIndexed { i, icon ->
            val tv = button(icon) { selectEmojiCategory(i) }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f) }
            emojiCatViews += tv
            emojiCats.addView(tv, LayoutParams(0, dp(42f), 1f))
        }
        bottom.addView(emojiCats, LayoutParams(0, dp(42f), 1f))
        bottom.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_backspace)
            setColorFilter(theme.text)
            scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { host.deleteChar() }
        }, LayoutParams(dp(52f), dp(42f)))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, dp(42f)))

        select(Tab.EMOJI)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && columnCount() != columns && tab == Tab.EMOJI) {
            post { if (emojiQuery != null) showEmojiSearch() else showAllEmojis(emojiCategory) }
        }
    }

    /** Für Tests: das erste sichtbare Emoji antippen. */
    fun tapFirstEmoji(): String? {
        for (r in 0 until emojiList.childCount) {
            val line = emojiList.getChildAt(r) as? LinearLayout ?: continue
            val cell = line.getChildAt(0) as? TextView ?: continue
            if (cell.text.isNullOrEmpty()) continue
            cell.performClick()
            return cell.text.toString()
        }
        return null
    }

    companion object {
        val GIF_TOPICS = listOf(
            "Haha", "Danke", "Liebe", "Ja", "Nein", "Wow", "Traurig", "Party", "Alles Gute",
            "Gute Nacht", "Guten Morgen", "Hallo", "OK", "Sorry", "Facepalm", "Tanzen", "Katze", "Hund",
        )
    }
}
