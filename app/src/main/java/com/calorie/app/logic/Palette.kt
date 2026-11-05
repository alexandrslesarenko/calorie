package com.calorie.app.logic

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Material 3 color roles built from one seed color, the way Material You builds them
 * (the "tonal spot" style), but here instead of in the system.
 *
 * Why: from One UI 9 (Android 17) the system scheme went pale - lighter gray backgrounds
 * and cards, no tint in the grays (measured on the user's S23 Ultra, 2026-11-05: background
 * #1D1D20 and cards #404044 instead of tones 6 and 22). Only the seed hue is taken from the
 * system, so the wallpaper color stays, while the lightness of every role is ours.
 *
 * Tone is CIE L* (as in Material's HCT); hue and chroma are CIE LCh(ab) instead of CAM16 -
 * close enough for a scheme and a few lines instead of a library. Colors are ARGB ints.
 */
class Palette(
    val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int,
    val inversePrimary: Int,
    val secondary: Int, val onSecondary: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
    val tertiary: Int, val onTertiary: Int, val tertiaryContainer: Int, val onTertiaryContainer: Int,
    val background: Int, val onBackground: Int,
    val surface: Int, val onSurface: Int, val surfaceVariant: Int, val onSurfaceVariant: Int,
    val surfaceTint: Int, val inverseSurface: Int, val inverseOnSurface: Int,
    val outline: Int, val outlineVariant: Int, val scrim: Int,
    val surfaceBright: Int, val surfaceDim: Int,
    val surfaceContainerLowest: Int, val surfaceContainerLow: Int, val surfaceContainer: Int,
    val surfaceContainerHigh: Int, val surfaceContainerHighest: Int,
) {
    companion object {
        // Chroma of each tonal palette (LCh), fitted to the Material 3 baseline scheme.
        // Material's tonal spot: 36 / 16 / 24 / 6 / 8 in CAM16.
        const val PRIMARY_C = 37.0
        const val SECONDARY_C = 14.0
        const val TERTIARY_C = 21.0
        const val NEUTRAL_C = 5.0
        const val NEUTRAL_VARIANT_C = 6.5
        /**
         * Constant CAM16 chroma is larger LCh chroma at dark tones (baseline primary: 36 at
         * tone 80, 53 at tone 30); this matters for the colorful palettes only.
         */
        private fun darkBoost(tone: Double) = 1 + (80 - tone) * 0.009
        /** A seed grayer than this gives a proportionally grayer scheme (a gray wallpaper). */
        const val GRAY_SEED_C = 12.0
        const val TERTIARY_SHIFT = 60.0

        fun of(seed: Int, dark: Boolean): Palette {
            val (_, seedC, hue) = lch(seed)
            val k = (seedC / GRAY_SEED_C).coerceAtMost(1.0)
            val p = { t: Double -> color(t, PRIMARY_C * k * darkBoost(t), hue) }
            val s = { t: Double -> color(t, SECONDARY_C * k, hue) }
            val t3 = { t: Double -> color(t, TERTIARY_C * k * darkBoost(t), hue + TERTIARY_SHIFT) }
            val n = { t: Double -> color(t, NEUTRAL_C * k, hue) }
            val nv = { t: Double -> color(t, NEUTRAL_VARIANT_C * k, hue) }
            return if (dark) Palette(
                primary = p(80.0), onPrimary = p(20.0), primaryContainer = p(30.0), onPrimaryContainer = p(90.0),
                inversePrimary = p(40.0),
                secondary = s(80.0), onSecondary = s(20.0), secondaryContainer = s(30.0), onSecondaryContainer = s(90.0),
                tertiary = t3(80.0), onTertiary = t3(20.0), tertiaryContainer = t3(30.0), onTertiaryContainer = t3(90.0),
                background = n(6.0), onBackground = n(90.0),
                surface = n(6.0), onSurface = n(90.0), surfaceVariant = nv(30.0), onSurfaceVariant = nv(80.0),
                surfaceTint = p(80.0), inverseSurface = n(90.0), inverseOnSurface = n(20.0),
                outline = nv(60.0), outlineVariant = nv(30.0), scrim = n(0.0),
                surfaceBright = n(24.0), surfaceDim = n(6.0),
                surfaceContainerLowest = n(4.0), surfaceContainerLow = n(10.0), surfaceContainer = n(12.0),
                surfaceContainerHigh = n(17.0), surfaceContainerHighest = n(22.0),
            ) else Palette(
                primary = p(40.0), onPrimary = p(100.0), primaryContainer = p(90.0), onPrimaryContainer = p(10.0),
                inversePrimary = p(80.0),
                secondary = s(40.0), onSecondary = s(100.0), secondaryContainer = s(90.0), onSecondaryContainer = s(10.0),
                tertiary = t3(40.0), onTertiary = t3(100.0), tertiaryContainer = t3(90.0), onTertiaryContainer = t3(10.0),
                background = n(98.0), onBackground = n(10.0),
                surface = n(98.0), onSurface = n(10.0), surfaceVariant = nv(90.0), onSurfaceVariant = nv(30.0),
                surfaceTint = p(40.0), inverseSurface = n(20.0), inverseOnSurface = n(95.0),
                outline = nv(50.0), outlineVariant = nv(80.0), scrim = n(0.0),
                surfaceBright = n(98.0), surfaceDim = n(87.0),
                surfaceContainerLowest = n(100.0), surfaceContainerLow = n(96.0), surfaceContainer = n(94.0),
                surfaceContainerHigh = n(92.0), surfaceContainerHighest = n(90.0),
            )
        }

        /**
         * The color of tone [tone] (L*) with hue [hue] (degrees) and chroma up to [chroma]:
         * if that chroma does not fit in sRGB at this tone, the most that fits.
         */
        fun color(tone: Double, chroma: Double, hue: Double): Int {
            if (tone <= 0.0) return 0xFF000000.toInt()
            if (tone >= 100.0) return 0xFFFFFFFF.toInt()
            var c = chroma
            if (inGamut(tone, c, hue) == null) {
                var lo = 0.0
                var hi = chroma
                repeat(20) {
                    val mid = (lo + hi) / 2
                    if (inGamut(tone, mid, hue) != null) lo = mid else hi = mid
                }
                c = lo
            }
            val rgb = inGamut(tone, c, hue) ?: doubleArrayOf(tone / 100, tone / 100, tone / 100)
            return argb(rgb)
        }

        /** L*, C, h of an ARGB color. */
        fun lch(argb: Int): Triple<Double, Double, Double> {
            val r = linear((argb shr 16 and 0xFF) / 255.0)
            val g = linear((argb shr 8 and 0xFF) / 255.0)
            val b = linear((argb and 0xFF) / 255.0)
            val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN
            val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
            val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN
            val fx = f(x); val fy = f(y); val fz = f(z)
            val l = 116 * fy - 16
            val a = 500 * (fx - fy)
            val bb = 200 * (fy - fz)
            val h = Math.toDegrees(atan2(bb, a)).let { if (it < 0) it + 360 else it }
            return Triple(l, hypot(a, bb), h)
        }

        /** Linear sRGB of the LCh color, or null if it is outside sRGB. */
        private fun inGamut(l: Double, c: Double, hue: Double): DoubleArray? {
            val h = Math.toRadians(hue)
            val a = c * cos(h)
            val b = c * sin(h)
            val fy = (l + 16) / 116
            val x = XN * fInv(fy + a / 500)
            val y = fInv(fy)
            val z = ZN * fInv(fy - b / 200)
            val rgb = doubleArrayOf(
                3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
                -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
                0.0556434 * x - 0.2040259 * y + 1.0572252 * z,
            )
            return if (rgb.all { it in -1e-9..1.0 + 1e-9 }) rgb else null
        }

        private fun argb(linear: DoubleArray): Int {
            val (r, g, b) = linear.map { (gamma(it.coerceIn(0.0, 1.0)) * 255).roundToInt() }
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        private const val XN = 0.95047
        private const val ZN = 1.08883
        private const val E = 216.0 / 24389.0
        private const val K = 24389.0 / 27.0
        private fun f(t: Double) = if (t > E) cbrt(t) else (K * t + 16) / 116
        private fun fInv(t: Double) = if (t * t * t > E) t * t * t else (116 * t - 16) / K
        private fun linear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        private fun gamma(c: Double) = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
    }
}
