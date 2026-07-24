package com.scamshield.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Verdict colours are fixed by `design.md` §10.2 and are *not* derived from Material
 * dynamic colour: a wallpaper-tinted "danger" red is not a danger signal. Dynamic colour is
 * deliberately not enabled anywhere in this app.
 *
 * Colour never carries a verdict on its own — every verdict is also an icon and a sentence
 * (§10.2). These values exist to reinforce a message that is already legible without them.
 */
internal object VerdictColors {
    val Scam = Color(0xFFB3261E)
    val ScamContainer = Color(0xFFF9DEDC)
    val OnScamContainer = Color(0xFF410E0B)

    val Suspicious = Color(0xFF7A5900)
    val SuspiciousContainer = Color(0xFFFFEFC4)
    val OnSuspiciousContainer = Color(0xFF261A00)

    val Safe = Color(0xFF1B6B3A)
    val SafeContainer = Color(0xFFCFF0DA)
    val OnSafeContainer = Color(0xFF00210E)
}

// --- Light scheme -----------------------------------------------------------
internal val LightPrimary = Color(0xFF1B4B8F)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFD8E2FF)
internal val LightOnPrimaryContainer = Color(0xFF001A41)
internal val LightSecondary = Color(0xFF565E71)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSurface = Color(0xFFFDFBFF)
internal val LightOnSurface = Color(0xFF1A1B1F)
internal val LightSurfaceVariant = Color(0xFFE1E2EC)
internal val LightOnSurfaceVariant = Color(0xFF44474F)
internal val LightOutline = Color(0xFF74777F)
internal val LightError = Color(0xFFB3261E)
internal val LightOnError = Color(0xFFFFFFFF)

// --- Dark scheme ------------------------------------------------------------
internal val DarkPrimary = Color(0xFFAEC6FF)
internal val DarkOnPrimary = Color(0xFF002E69)
internal val DarkPrimaryContainer = Color(0xFF004494)
internal val DarkOnPrimaryContainer = Color(0xFFD8E2FF)
internal val DarkSecondary = Color(0xFFBEC6DC)
internal val DarkOnSecondary = Color(0xFF283041)
internal val DarkSurface = Color(0xFF1A1B1F)
internal val DarkOnSurface = Color(0xFFE3E2E6)
internal val DarkSurfaceVariant = Color(0xFF44474F)
internal val DarkOnSurfaceVariant = Color(0xFFC4C6D0)
internal val DarkOutline = Color(0xFF8E9099)
internal val DarkError = Color(0xFFFFB4AB)
internal val DarkOnError = Color(0xFF690005)
