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
import java.io.File

/** Bereich für Emojis, GIFs und Sticker. */
@SuppressLint("ViewConstructor")
class MediaPanel(
    context: Context,
    private val theme: KeyboardTheme,
    private val giphy: GiphyClient,
    private val stickers: StickerStore,
    private val host: Host,
) : LinearLayout(context) {

    enum class Tab { EMOJI, GIF, STICKER }
    private enum class StickerSource { OWN, EMOJI, GIPHY }

    interface Host {
        fun commitEmoji(e: String)
        fun commitGif(item: GiphyClient.Item, sticker: Boolean)
        fun commitStickerFile(f: File)
        fun commitEmojiSticker(e: String)
        fun deleteChar()
        fun backToKeyboard()
        fun startSearch(tab: Tab)
        fun openSettings(page: String)
        fun haptic(v: View)
    }

    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    var tab = Tab.EMOJI
        private set
    private var emojiCategory = 0
    private var stickerSource = StickerSource.EMOJI
    private var gifQuery: String? = null
    private var stickerQuery: String? = null
    private var loadToken = 0

    // Inhalte
    private var emojiItems: List<String> = emptyList()
    private var gifItems: List<GiphyClient.Item> = emptyList()
    private var ownItems: List<File> = emptyList()

    private val tabViews = HashMap<Tab, TextView>()
    private val emojiGrid = GridView(context)
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
        emojiGrid.visibility = if (t == Tab.EMOJI) VISIBLE else GONE
        mediaBox.visibility = if (t == Tab.EMOJI) GONE else VISIBLE
        emojiCats.visibility = if (t == Tab.EMOJI) VISIBLE else INVISIBLE
        when (t) {
            Tab.EMOJI -> selectEmojiCategory(emojiCategory)
            Tab.GIF -> { buildGifChips(); loadGiphy("gifs", gifQuery) }
            Tab.STICKER -> { buildStickerChips(); showStickerSource() }
        }
    }

    /** Ergebnis der Suche aus der Tastatur. */
    fun search(t: Tab, query: String) {
        if (t == Tab.GIF) gifQuery = query.ifBlank { null }
        else { stickerQuery = query.ifBlank { null }; stickerSource = StickerSource.GIPHY }
        select(t)
    }

    fun refreshRecents() {
        if (tab == Tab.EMOJI && emojiCategory == 0) selectEmojiCategory(0)
    }

    private fun selectEmojiCategory(i: Int) {
        emojiCategory = i
        emojiItems = if (i == 0) recentEmojis().ifEmpty { EmojiData.categories[0].emojis }
        else EmojiData.categories[i - 1].emojis
        emojiAdapter.notifyDataSetChanged()
        emojiGrid.setSelection(0)
        emojiCatViews.forEachIndexed { j, tv -> tv.alpha = if (j == i) 1f else 0.45f }
    }

    private fun buildGifChips() {
        chipRow.removeAllViews()
        addChip("🔍 " + (gifQuery ?: "GIFs suchen"), gifQuery != null) { host.startSearch(Tab.GIF) }
        addChip("🔥 Trends", gifQuery == null) { gifQuery = null; select(Tab.GIF) }
        for (q in GIF_TOPICS) addChip(q, gifQuery == q) { gifQuery = q; select(Tab.GIF) }
    }

    private fun buildStickerChips() {
        chipRow.removeAllViews()
        addChip("⭐ Eigene", stickerSource == StickerSource.OWN) { stickerSource = StickerSource.OWN; select(Tab.STICKER) }
        addChip("😀 Emoji", stickerSource == StickerSource.EMOJI) { stickerSource = StickerSource.EMOJI; select(Tab.STICKER) }
        addChip("🔍 " + (stickerQuery ?: "GIPHY-Sticker"), stickerSource == StickerSource.GIPHY) {
            if (stickerSource == StickerSource.GIPHY) host.startSearch(Tab.STICKER)
            else { stickerSource = StickerSource.GIPHY; select(Tab.STICKER) }
        }
        addChip("＋ Hinzufügen", false) { host.openSettings(SettingsActivity.PAGE_MEDIA) }
    }

    private fun showStickerSource() {
        when (stickerSource) {
            StickerSource.OWN -> {
                ownItems = stickers.list()
                gifItems = emptyList()
                attribution.visibility = GONE
                mediaGrid.numColumns = 4
                showStatus(if (ownItems.isEmpty()) "Noch keine eigenen Sticker.\nTippe auf „＋ Hinzufügen“, um Bilder aus deiner Galerie zu übernehmen." else null)
                mediaAdapter.notifyDataSetChanged()
            }
            StickerSource.EMOJI -> {
                ownItems = emptyList()
                gifItems = emptyList()
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
        mediaGrid.numColumns = if (kind == "gifs") 3 else 4
        attribution.visibility = VISIBLE
        mediaAdapter.notifyDataSetChanged()
        if (!giphy.hasKey()) {
            showStatus("Für GIFs und GIPHY-Sticker brauchst du einen kostenlosen GIPHY-Schlüssel.\nTippe hier, um ihn in den Einstellungen einzutragen.")
            status.setOnClickListener { host.openSettings(SettingsActivity.PAGE_MEDIA) }
            return
        }
        status.setOnClickListener(null)
        showStatus("Lädt …")
        val token = ++loadToken
        ImageLoader.run {
            val result = try { giphy.search(kind, query) } catch (e: Exception) { null }
            main.post {
                if (token != loadToken) return@post
                if (result == null) {
                    showStatus("Konnte nicht laden – Internet oder GIPHY-Schlüssel prüfen.")
                } else {
                    gifItems = result
                    showStatus(if (result.isEmpty()) "Nichts gefunden." else null)
                    mediaAdapter.notifyDataSetChanged()
                    mediaGrid.setSelection(0)
                }
            }
        }
    }

    private fun showStatus(text: String?) {
        status.text = text ?: ""
        status.visibility = if (text == null) GONE else VISIBLE
    }

    private fun onMediaClick(pos: Int) {
        when {
            tab == Tab.GIF -> gifItems.getOrNull(pos)?.let { host.commitGif(it, false) }
            stickerSource == StickerSource.GIPHY -> gifItems.getOrNull(pos)?.let { host.commitGif(it, true) }
            stickerSource == StickerSource.OWN -> ownItems.getOrNull(pos)?.let { host.commitStickerFile(it) }
            stickerSource == StickerSource.EMOJI -> stickerEmojis().getOrNull(pos)?.let { host.commitEmojiSticker(it) }
        }
    }

    private fun onMediaLongClick(pos: Int): Boolean {
        if (tab == Tab.STICKER && stickerSource == StickerSource.OWN) {
            ownItems.getOrNull(pos)?.let {
                it.delete()
                android.widget.Toast.makeText(context, "Sticker gelöscht", android.widget.Toast.LENGTH_SHORT).show()
                showStickerSource()
            }
            return true
        }
        return false
    }

    private fun stickerEmojis(): List<String> =
        (recentEmojis() + EmojiData.categories[0].emojis.take(60) + EmojiData.categories[2].emojis.take(30)).distinct()

    // ------------------------------------------------------------------ Adapter

    private val emojiAdapter = object : BaseAdapter() {
        override fun getCount() = emojiItems.size
        override fun getItem(position: Int) = emojiItems[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46f))
            }
            tv.text = emojiItems[position]
            return tv
        }
    }

    private val mediaAdapter = object : BaseAdapter() {
        private fun emojiMode() = tab == Tab.STICKER && stickerSource == StickerSource.EMOJI
        override fun getCount() = when {
            emojiMode() -> stickerEmojis().size
            ownItems.isNotEmpty() -> ownItems.size
            else -> gifItems.size
        }
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (emojiMode()) 0 else 1
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            if (emojiMode()) {
                val tv = (convertView as? TextView) ?: TextView(context).apply {
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64f))
                }
                tv.text = stickerEmojis()[position]
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
        context.getSharedPreferences("emoji", Context.MODE_PRIVATE).getString("recent", "")!!
            .split('\u0001').filter { it.isNotEmpty() }

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

        // --- Reiter oben
        val tabs = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        for ((t, label) in listOf(Tab.EMOJI to "😀 Emoji", Tab.GIF to "GIF", Tab.STICKER to "Sticker")) {
            val tv = chip(label) { select(t) }
            tabViews[t] = tv
            tabs.addView(tv, LayoutParams(0, dp(32f), 1f).apply { setMargins(dp(4f), dp(4f), dp(4f), dp(2f)) })
        }
        addView(tabs)

        // --- Inhalt
        val contentFrame = FrameLayout(context)
        emojiGrid.apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(44f)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(4f), 0, dp(4f), dp(4f))
            clipToPadding = false
            adapter = emojiAdapter
            setOnItemClickListener { _, v, pos, _ ->
                host.commitEmoji(emojiItems[pos]); host.haptic(v)
            }
        }
        contentFrame.addView(emojiGrid)

        mediaBox.orientation = VERTICAL
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        chipRow.orientation = HORIZONTAL
        chipRow.setPadding(dp(4f), 0, dp(4f), 0)
        scroll.addView(chipRow)
        mediaBox.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(38f)))
        status.apply {
            gravity = Gravity.CENTER
            setTextColor(theme.hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
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

        // --- Leiste unten
        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(theme.specialKey)
        }
        bottom.addView(button("ABC") { host.backToKeyboard() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTypeface(null, Typeface.BOLD)
        }, LayoutParams(dp(56f), dp(44f)))
        emojiCats.orientation = HORIZONTAL
        val icons = listOf("🕘") + EmojiData.categories.map { it.icon }
        icons.forEachIndexed { i, icon ->
            val tv = button(icon) { selectEmojiCategory(i) }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }
            emojiCatViews += tv
            emojiCats.addView(tv, LayoutParams(0, dp(44f), 1f))
        }
        bottom.addView(emojiCats, LayoutParams(0, dp(44f), 1f))
        bottom.addView(button("⌫") { host.deleteChar() }, LayoutParams(dp(56f), dp(44f)))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))

        selectEmojiCategory(if (recentEmojis().isEmpty()) 1 else 0)
        select(Tab.EMOJI)
    }

    companion object {
        val GIF_TOPICS = listOf(
            "Haha", "Danke", "Liebe", "Ja", "Nein", "Wow", "Traurig", "Party", "Alles Gute",
            "Gute Nacht", "Guten Morgen", "Hallo", "OK", "Sorry", "Facepalm", "Tanzen", "Katze", "Hund",
        )
    }
}
