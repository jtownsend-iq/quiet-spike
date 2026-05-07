// android/quiet-spike/app/src/main/java/app/quiet/spike/QuietTheme.kt
//
// Locked design tokens (CLAUDE.md § Design tokens):
//   --bg-canvas     #FAFAF7
//   --accent-ink    #1F3A5F  (single accent — focus underline only)
//   --hairline      #E5E3DD
//   --text-primary  #1A1A1A
// Type: 16 sp body, 26 sp line-height per the field contract.
//
// No second accent. No gradient. No shadow. No celebration. The
// composable apps that import this file MUST NOT introduce a Material
// colour scheme that paints buttons/sheets/tiles in another colour.

package app.quiet.spike

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object QuietTokens {
    val BgCanvas      = Color(0xFFFAFAF7)
    val BgSurface     = Color(0xFFFFFFFF)
    val BgInset       = Color(0xFFF2F1EC)
    val TextPrimary   = Color(0xFF1A1A1A)
    val TextSecondary = Color(0xFF4A4A4A)
    val TextTertiary  = Color(0xFF7A7A7A)
    val AccentInk     = Color(0xFF1F3A5F)
    val Hairline      = Color(0xFFE5E3DD)

    // Typography (DS § Type scale): 32 / 20 / 16 / 13 / 11; 2 weights only.
    val Body          = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, fontWeight = FontWeight.W400, color = TextPrimary)
    val BodyStrong    = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, fontWeight = FontWeight.W600, color = TextPrimary)
    val Meta          = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.W400, color = TextTertiary)
}

/**
 * QuietTheme intentionally collapses Material3's full ColorScheme to the
 * narrow palette in QuietTokens. Anything Material draws automatically
 * (focus rings, ripple) lands on AccentInk; nothing else gets a colour.
 */
@Composable
fun QuietTheme(content: @Composable () -> Unit) {
    // The app is silent and light only in the spike. Dark mode is a Phase-2
    // concern; reusing lightColorScheme here keeps the contract simple.
    @Suppress("UNUSED_PARAMETER")
    val darkUnused = isSystemInDarkTheme()
    val scheme = lightColorScheme(
        primary       = QuietTokens.AccentInk,
        onPrimary     = QuietTokens.BgSurface,
        background    = QuietTokens.BgCanvas,
        onBackground  = QuietTokens.TextPrimary,
        surface       = QuietTokens.BgSurface,
        onSurface     = QuietTokens.TextPrimary,
        outline       = QuietTokens.Hairline,
        outlineVariant= QuietTokens.Hairline,
    )
    val typography = Typography(
        bodyLarge   = QuietTokens.Body,
        bodyMedium  = QuietTokens.Body,
        labelSmall  = QuietTokens.Meta,
        titleMedium = QuietTokens.BodyStrong,
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
