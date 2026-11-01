package cz.radek.dekontaminace.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import cz.radek.dekontaminace.R
import androidx.compose.ui.unit.sp

/** Paleta konzole hvězdné lodi. */
object Console {
    val Amber = Color(0xFFFF9F3B)
    val Peach = Color(0xFFFFC98B)
    val Lilac = Color(0xFFA9A4FF)
    val Periwinkle = Color(0xFF9FA8FF)
    val Sky = Color(0xFF7FC4E8)
    val Azure = Color(0xFF5FA8FF)
    val Violet = Color(0xFFA98BFF)
    val Teal = Color(0xFF4FD1C5)
    val Indigo = Color(0xFF6C7BFF)
    val Gold = Color(0xFFFFD166)
    val Lime = Color(0xFFB8E06A)
    val Rose = Color(0xFFFF6B4A)
    val Red = Color(0xFFFF4F4F)
    val Gray = Color(0xFF55566A)
    val Dim = Color(0xFF8C8DA3)
    val Black = Color(0xFF000000)
}

// Audiowide jen na nadpisy (je široký a „vesmírný“), Chakra Petch na všechno ostatní, protože
// zůstává čitelný i v drobném písmu. Oba fonty mají celou českou diakritiku (OFL licence).
val Display = FontFamily(Font(R.font.audiowide_regular, FontWeight.Normal))
val Body = FontFamily(
    Font(R.font.chakra_petch_medium, FontWeight.Medium),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

private val ConsoleTypography = Typography(
    displaySmall = TextStyle(fontFamily = Display, fontSize = 30.sp, letterSpacing = 1.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontSize = 21.sp, letterSpacing = 0.5.sp),
    titleLarge = TextStyle(fontFamily = Display, fontSize = 19.sp, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontFamily = Display, fontSize = 15.sp, letterSpacing = 0.3.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.sp),
    labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.8.sp),
    labelSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

private val ConsoleColors = darkColorScheme(
    primary = Console.Amber,
    onPrimary = Console.Black,
    secondary = Console.Lilac,
    onSecondary = Console.Black,
    tertiary = Console.Sky,
    background = Console.Black,
    onBackground = Console.Peach,
    surface = Console.Black,
    onSurface = Console.Peach,
    surfaceVariant = Color(0xFF14141C),
    onSurfaceVariant = Console.Peach,
    error = Console.Red,
)

@Composable
fun DekontaminaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ConsoleColors,
        typography = ConsoleTypography,
        content = content,
    )
}
