package dev.pi.mobile.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Two families with two jobs.
 *
 * Sans is the chrome: titles, rows, buttons, sheet labels. Mono is the machine's voice: transcript
 * prose, composer text, code, model ids, counts. System families only — no downloaded fonts, so the
 * APK and the offline story stay intact.
 */
val Sans = FontFamily.SansSerif
val Mono = FontFamily.Monospace

private fun sans(
  size: Int,
  weight: FontWeight = FontWeight.Normal,
  line: Int = (size * 1.4f).toInt(),
  tracking: Float = 0f,
) = TextStyle(
  fontFamily = Sans,
  fontWeight = weight,
  fontSize = size.sp,
  lineHeight = line.sp,
  letterSpacing = tracking.sp,
)

private fun mono(
  size: Int,
  weight: FontWeight = FontWeight.Normal,
  line: Int = (size * 1.45f).toInt(),
  tracking: Float = 0f,
) = TextStyle(
  fontFamily = Mono,
  fontWeight = weight,
  fontSize = size.sp,
  lineHeight = line.sp,
  letterSpacing = tracking.sp,
)

/** Material widgets inherit the UI voice; content composables use [PiType] explicitly. */
val Typography = Typography(
  displayLarge = sans(28, FontWeight.SemiBold, 36, -0.6f),
  displayMedium = sans(24, FontWeight.SemiBold, 32, -0.4f),
  displaySmall = sans(20, FontWeight.SemiBold, 28, -0.3f),
  headlineLarge = sans(20, FontWeight.SemiBold, 28, -0.3f),
  headlineMedium = sans(18, FontWeight.SemiBold, 26, -0.2f),
  headlineSmall = sans(16, FontWeight.Medium, 24),
  titleLarge = sans(20, FontWeight.SemiBold, 28, -0.3f),
  titleMedium = sans(15, FontWeight.Medium, 20),
  titleSmall = sans(13, FontWeight.Medium, 18),
  bodyLarge = sans(15, FontWeight.Normal, 23),
  bodyMedium = sans(13, FontWeight.Normal, 19),
  bodySmall = sans(12, FontWeight.Normal, 17),
  labelLarge = sans(13, FontWeight.Medium, 18),
  labelMedium = sans(12, FontWeight.Medium, 16),
  labelSmall = sans(10, FontWeight.Medium, 14, 1.2f),
)

/**
 * The roles the screens actually use. Sizes are a small fixed set; a new size is a design change,
 * not a local decision. `Label` is the only role that may be 10 sp, and only in uppercase.
 */
object PiType {
  val Display = sans(24, FontWeight.SemiBold, 30, -0.5f)
  val Title = sans(20, FontWeight.SemiBold, 26, -0.3f)
  val Heading = sans(16, FontWeight.Medium, 23)
  val Header = sans(15, FontWeight.Medium, 19, -0.1f)
  val Ui = sans(13, FontWeight.Medium, 18)
  val UiSmall = sans(12, FontWeight.Normal, 17)
  val Body = mono(15, FontWeight.Normal, 24)
  val BodySmall = mono(13, FontWeight.Normal, 21)
  val Meta = mono(11, FontWeight.Normal, 16)
  val Micro = mono(10, FontWeight.Normal, 14)
  val Label = mono(10, FontWeight.Medium, 14, 1.6f)
  val Code = mono(12, FontWeight.Normal, 17)
  /** The 8 sp line under a 48 dp glyph; never used for a sentence. */
  val Nano = mono(8, FontWeight.Medium, 10, 0.4f)
}
