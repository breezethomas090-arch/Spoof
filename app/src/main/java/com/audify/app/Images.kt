package com.audify.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// ============================================================== saving picked images

/** Copies a picked image into app storage, downscaled. Returns the saved file path. */
fun saveImage(ctx: Context, uri: Uri, dir: String, name: String, maxSize: Int = 800): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null

    // Centre-crop to a square
    val side = minOf(bmp.width, bmp.height)
    val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
    val scaled = if (side > maxSize) Bitmap.createScaledBitmap(square, maxSize, maxSize, true) else square

    val folder = File(ctx.filesDir, dir).apply { mkdirs() }
    val out = File(folder, "${name}_${System.currentTimeMillis()}.jpg")
    FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    out.absolutePath
}.getOrNull()

// ============================================================== artwork colour

/** A dark colour pulled from the artwork, for Spotify-style tinted backgrounds. */
@Composable
fun rememberArtColor(model: Any?, fallback: Color = Color(0xFF2A1D52)): State<Color> {
    val ctx = LocalContext.current
    return produceState(fallback, model) {
        if (model == null) {
            value = fallback
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(ctx).data(model).size(96).allowHardware(false).build()
                val bmp = (ctx.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap() ?: return@runCatching fallback
                val p = Palette.from(bmp).generate()
                val rgb = p.darkVibrantSwatch?.rgb ?: p.vibrantSwatch?.rgb ?: p.darkMutedSwatch?.rgb ?: p.dominantSwatch?.rgb
                    ?: return@runCatching fallback
                // Darken so white text stays readable
                val c = Color(rgb)
                Color(c.red * 0.62f, c.green * 0.62f, c.blue * 0.62f)
            }.getOrDefault(fallback)
        }
    }
}

// ============================================================== styled QR codes

data class QrTheme(val name: String, val start: Int, val end: Int)

val QR_THEMES = listOf(
    QrTheme("Audify", 0xFF8B5CF6.toInt(), 0xFFEC4899.toInt()),
    QrTheme("Ocean", 0xFF0EA5E9.toInt(), 0xFF4F46E5.toInt()),
    QrTheme("Sunset", 0xFFF97316.toInt(), 0xFFE11D48.toInt()),
    QrTheme("Forest", 0xFF10B981.toInt(), 0xFF0F766E.toInt()),
    QrTheme("Gold", 0xFFF59E0B.toInt(), 0xFFB45309.toInt()),
    QrTheme("Mono", 0xFF111111.toInt(), 0xFF111111.toInt()),
)

/**
 * Audify-branded QR: rounded dots in a gradient, rounded "eyes", and the Audify
 * logo in the middle. Uses high error correction so it still scans with the logo.
 */
fun styledQr(content: String, theme: QrTheme, px: Int = 960): Bitmap {
    val qr = Encoder.encode(content, ErrorCorrectionLevel.H, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
    val m = qr.matrix
    val n = m.width
    val margin = 3
    val cell = px.toFloat() / (n + margin * 2)
    val off = margin * cell

    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(android.graphics.Color.WHITE)
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, px.toFloat(), px.toFloat(), theme.start, theme.end, Shader.TileMode.CLAMP)
    }

    // Clear zone in the middle for the logo (same parity as n so it's centred)
    var logo = (n * 0.22f).toInt()
    if ((n - logo) % 2 != 0) logo++
    val lo = (n - logo) / 2
    val hi = lo + logo

    fun inEye(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)

    for (y in 0 until n) for (x in 0 until n) {
        if (m.get(x, y).toInt() != 1 || inEye(x, y)) continue
        if (x in lo until hi && y in lo until hi) continue
        val l = off + x * cell
        val t = off + y * cell
        val inset = cell * 0.08f
        c.drawRoundRect(RectF(l + inset, t + inset, l + cell - inset, t + cell - inset), cell * 0.38f, cell * 0.38f, ink)
    }

    // Rounded finder "eyes"
    val ring = Paint(ink).apply {
        style = Paint.Style.STROKE
        strokeWidth = cell
    }
    for ((ex, ey) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
        val l = off + ex * cell
        val t = off + ey * cell
        c.drawRoundRect(RectF(l + cell / 2, t + cell / 2, l + 6.5f * cell, t + 6.5f * cell), cell * 2f, cell * 2f, ring)
        c.drawRoundRect(RectF(l + 2 * cell, t + 2 * cell, l + 5 * cell, t + 5 * cell), cell, cell, ink)
    }

    // Logo: white rounded square with the five sound-wave bars
    val bl = off + lo * cell
    val bt = off + lo * cell
    val bs = logo * cell
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    c.drawRoundRect(RectF(bl, bt, bl + bs, bt + bs), bs * 0.22f, bs * 0.22f, white)
    val heights = floatArrayOf(0.38f, 0.72f, 1f, 0.6f, 0.3f)
    val area = bs * 0.62f
    val ax = bl + (bs - area) / 2
    val ay = bt + (bs - area) / 2
    val gap = area / 5f
    val barW = gap * 0.56f
    heights.forEachIndexed { i, h ->
        val barH = area * h
        val x = ax + gap * i + (gap - barW) / 2
        val y = ay + (area - barH) / 2
        c.drawRoundRect(RectF(x, y, x + barW, y + barH), barW / 2, barW / 2, ink)
    }
    return bmp
}

/** Shares a QR image (plus text) through the Android share sheet. */
fun shareImage(ctx: Context, bmp: Bitmap, text: String) {
    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
    val f = File(dir, "audify-qr.png")
    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, text)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
