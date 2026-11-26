package com.audify.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Violet = Color(0xFF8B5CF6)
val Pink = Color(0xFFEC4899)
val BrandBrush = Brush.linearGradient(listOf(Violet, Pink))
val YtMusicBrush = Brush.linearGradient(listOf(Color(0xFFFF3B30), Color(0xFFB3001B)))
val SpotifyBrush = Brush.linearGradient(listOf(Color(0xFF1ED760), Color(0xFF0F7A37)))

private val Scheme = darkColorScheme(
    primary = Color(0xFFB79CFF),
    onPrimary = Color(0xFF1A0B3D),
    primaryContainer = Color(0xFF2A1D52),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFF48FC4),
    secondaryContainer = Color(0xFF1C1C1C),
    onSecondaryContainer = Color(0xFFEDEDED),
    background = Color.Black,
    onBackground = Color(0xFFF2F2F2),
    surface = Color.Black,
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF9B9B9B),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF222222),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF242424),
    error = Color(0xFFFF8A80),
)

private val Type = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun AudifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}

/** The Audify mark: five rounded sound-wave bars in the brand gradient. */
@Composable
fun LogoMark(size: Dp, brush: Brush = BrandBrush) {
    Canvas(Modifier.size(size)) {
        val heights = floatArrayOf(0.38f, 0.72f, 1f, 0.6f, 0.3f)
        val gap = this.size.width / 5f
        val barW = gap * 0.56f
        heights.forEachIndexed { i, h ->
            val barH = this.size.height * h
            val x = gap * i + (gap - barW) / 2f
            val y = (this.size.height - barH) / 2f
            drawRoundRect(
                brush = brush,
                topLeft = Offset(x, y),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LogoMark(26.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "audify",
            style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** Material icons not in the core set, built from their SVG paths. */
object AppIcons {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    val Pause = icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val SkipNext = icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val SkipPrevious = icon("SkipPrevious", "M6,6h2v12H6zM9.5,12l8.5,6V6z")
    val Download = icon("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    val DownloadDone = icon("DownloadDone", "M5,18h14v2H5v-2zM9.6,15.6L4,10l2.1,-2.1 3.5,3.5 8.3,-8.3L20,5.2 9.6,15.6z")
    val MusicNote = icon(
        "MusicNote",
        "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z",
    )
    val Library = icon(
        "LibraryMusic",
        "M20,2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM18,7h-3v5.5c0,1.38 -1.12,2.5 -2.5,2.5S10,13.88 10,12.5s1.12,-2.5 2.5,-2.5c0.57,0 1.08,0.19 1.5,0.51V5h4v2zM4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6z",
    )
    val Playlist = icon(
        "QueueMusic",
        "M15,6H3v2h12V6zM15,10H3v2h12v-2zM3,16h8v-2H3v2zM17,6v8.18c-0.31,-0.11 -0.65,-0.18 -1,-0.18 -1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3V8h3V6h-5z",
    )
    val PlaylistAdd = icon("PlaylistAdd", "M14,10H2v2h12v-2zM14,6H2v2h12V6zM18,14v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zM2,16h8v-2H2v2z")
    val PlayNext = icon("PlayNext", "M3,10h11v2H3zM3,6h11v2H3zM3,14h7v2H3zM16,13v8l6,-4z")
    val Shuffle = icon(
        "Shuffle",
        "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5zM14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z",
    )
    val Repeat = icon("Repeat", "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z")
    val RepeatOne = icon(
        "RepeatOne",
        "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z",
        "M13,15V9h-1l-2,1v1h1.5v4H13z",
    )
    val QrCode = icon(
        "QrCode",
        "M3,11h8V3H3V11zM5,5h4v4H5V5z",
        "M3,21h8v-8H3V21zM5,15h4v4H5V15z",
        "M13,3v8h8V3H13zM19,9h-4V5h4V9z",
        "M19,19h2v2h-2zM13,13h2v2h-2zM15,15h2v2h-2zM13,17h2v2h-2zM15,19h2v2h-2zM17,17h2v2h-2zM17,13h2v2h-2zM19,15h2v2h-2z",
    )
    val Equalizer = icon("Equalizer", "M10,20h4V4h-4v16zM4,20h4v-8H4v8zM16,9v11h4V9h-4z")
    val Copy = icon(
        "ContentCopy",
        "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z",
    )
}
