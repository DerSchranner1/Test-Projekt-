package de.schranner.tastatur

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** GIFs und Sticker von GIPHY (kostenloser Schlüssel nötig). */
class GiphyClient(private val prefs: Prefs) {

    class Item(val id: String, val previewUrl: String, val fullUrl: String, val pageUrl: String, val ratio: Float)

    fun hasKey() = prefs.giphyKey.isNotBlank()

    /** @param kind "gifs" oder "stickers"; ohne Suchbegriff: Trends */
    fun search(kind: String, query: String?): List<Item> {
        val key = URLEncoder.encode(prefs.giphyKey, "UTF-8")
        val url = if (query.isNullOrBlank()) {
            "https://api.giphy.com/v1/$kind/trending?api_key=$key&limit=30&rating=pg-13"
        } else {
            "https://api.giphy.com/v1/$kind/search?api_key=$key&limit=30&rating=pg-13&lang=de&q=" +
                URLEncoder.encode(query, "UTF-8")
        }
        val body = String(Net.get(url))
        val data = JSONObject(body).getJSONArray("data")
        val out = ArrayList<Item>()
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val images = o.getJSONObject("images")
            val small = images.optJSONObject("fixed_width_small") ?: images.optJSONObject("fixed_width") ?: continue
            val full = images.optJSONObject("fixed_width") ?: small
            val w = small.optString("width").toFloatOrNull() ?: 1f
            val h = small.optString("height").toFloatOrNull() ?: 1f
            out += Item(o.optString("id"), small.optString("url"), full.optString("url"), o.optString("url"), h / w)
        }
        return out
    }
}

object Net {
    fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        val code = c.responseCode
        if (code != 200) throw RuntimeException("HTTP $code")
        return c.inputStream.use { it.readBytes() }
    }
}

/** Lädt (animierte) Bilder im Hintergrund in ImageViews. */
object ImageLoader {
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, ByteArray>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    fun load(view: ImageView, url: String) {
        view.tag = url
        view.setImageDrawable(null)
        pool.execute {
            try {
                val bytes = cache.get(url) ?: Net.get(url).also { cache.put(url, it) }
                val d = decode(bytes)
                main.post {
                    if (view.tag == url) {
                        view.setImageDrawable(d)
                        (d as? AnimatedImageDrawable)?.start()
                    }
                }
            } catch (_: Exception) { }
        }
    }

    fun loadFile(view: ImageView, file: File) {
        val key = file.absolutePath
        view.tag = key
        view.setImageDrawable(null)
        pool.execute {
            try {
                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { dec, info, _ ->
                    val max = 256
                    val w = info.size.width
                    val h = info.size.height
                    if (w > max || h > max) {
                        val s = max.toFloat() / maxOf(w, h)
                        dec.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
                    }
                }
                main.post {
                    if (view.tag == key) {
                        view.setImageDrawable(d)
                        (d as? AnimatedImageDrawable)?.start()
                    }
                }
            } catch (_: Exception) { }
        }
    }

    fun cached(url: String): ByteArray? = cache.get(url)

    fun decode(bytes: ByteArray): Drawable =
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))

    fun run(task: () -> Unit) = pool.execute(task)
}

/** Eigene Sticker (aus der Galerie) und Emoji-Sticker. */
class StickerStore(private val context: Context) {
    val dir = File(context.filesDir, "stickers").apply { mkdirs() }

    fun list(): List<File> = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Bild aus der Galerie übernehmen (verkleinert auf max. 512 px; animierte GIFs bleiben unverändert). */
    fun import(uri: Uri): Boolean {
        return try {
            val type = context.contentResolver.getType(uri) ?: ""
            val name = "s" + System.currentTimeMillis() + "_" + (0..999).random()
            if (type == "image/gif") {
                context.contentResolver.openInputStream(uri)!!.use { inp ->
                    File(dir, "$name.gif").outputStream().use { inp.copyTo(it) }
                }
            } else {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    if (w > 512 || h > 512) {
                        val s = 512f / maxOf(w, h)
                        dec.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
                    }
                }
                File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            true
        } catch (_: Exception) { false }
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    companion object {
        /** Emoji als großes Sticker-Bild (512 px, transparent). */
        fun renderEmoji(emoji: String, format: Bitmap.CompressFormat, out: File) {
            val size = 512
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size * 0.78f
                textAlign = Paint.Align.CENTER
            }
            val y = size / 2f - (p.descent() + p.ascent()) / 2f
            c.drawText(emoji, size / 2f, y, p)
            out.outputStream().use { bmp.compress(format, 100, it) }
        }
    }
}

/**
 * Stellt Bilder für andere Apps bereit (GIFs/Sticker werden per commitContent übergeben).
 * Nicht exportiert – Apps bekommen nur für das einzelne Bild eine Leseberechtigung.
 */
class MediaProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val seg = uri.pathSegments
        if (seg.size != 2) throw FileNotFoundException()
        val base = when (seg[0]) {
            "share" -> File(ctx.cacheDir, "share")
            "stickers" -> File(ctx.filesDir, "stickers")
            else -> throw FileNotFoundException()
        }
        val f = File(base, seg[1])
        if (!f.canonicalPath.startsWith(base.canonicalPath + File.separator) || !f.exists()) throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String = mimeOf(uri.lastPathSegment ?: "")

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        })
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "de.schranner.tastatur.media"

        fun uriFor(kind: String, name: String): Uri = Uri.parse("content://$AUTHORITY/$kind/$name")

        fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
            "gif" -> "image/gif"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }
}
