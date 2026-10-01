package com.local.jewels;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.Arrays;

/**
 * One soft, low-contrast palette for everything: dusky inks, cream instead of white,
 * muted gem ramps. Painted art is snapped to it with ordered dithering.
 */
final class Palette {
    // UI inks, darkest to lightest
    static final int INK0 = 0xFF16151F, INK1 = 0xFF1F1D2E, INK2 = 0xFF29273D, INK3 = 0xFF35324E, INK4 = 0xFF45416A,
            INK5 = 0xFF5C5786, MUTED = 0xFF8A84AD, PALE = 0xFFB9B3D1, CREAM = 0xFFEFE6D2, SNOW = 0xFFFBF7EE;
    static final int GOLD_D = 0xFF7A5A32, GOLD = 0xFFC9A05A, GOLD_L = 0xFFE8CC8A;

    /** Linear blend from a to b, alpha included. */
    static int mix(int a, int b, float t) {
        return Color.argb(
                (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Gem ramps: outline, dark, mid, light, highlight. */
    static final int[][] GEM = {
            {0xFF5A2A36, 0xFF9A4651, 0xFFC96A6A, 0xFFE59A8C, 0xFFF6D0BF}, // rose
            {0xFF5E5A6E, 0xFF9D98A8, 0xFFCFC8C4, 0xFFECE4D6, 0xFFFFFAF0}, // cream
            {0xFF2F4A3A, 0xFF4F7A55, 0xFF7FA86C, 0xFFB0CF8E, 0xFFE0EFC4}, // sage
            {0xFF6A4A2A, 0xFFB08540, 0xFFDCB35C, 0xFFEFD68A, 0xFFFBF0C6}, // ochre
            {0xFF3E3158, 0xFF6C5891, 0xFF9A83C0, 0xFFC3B0DE, 0xFFEBE0F5}, // lavender
            {0xFF6A3A28, 0xFFB0663E, 0xFFDC9158, 0xFFEFBD84, 0xFFFBE2C2}, // apricot
            {0xFF263A5A, 0xFF3F6590, 0xFF6595C0, 0xFF98C0DE, 0xFFD6EBF6}, // steel blue
    };

    private static final int[] SCENE = {
            // dusk skies
            0xFF3A2F5B, 0xFF5D4778, 0xFF8A5C86, 0xFFB8727F, 0xFFD99A84, 0xFFEEC39A,
            // sea and teal
            0xFF1F3A44, 0xFF2F5A62, 0xFF4A8584, 0xFF79B3A6, 0xFFB4DCCB,
            // forest
            0xFF22302A, 0xFF344A38,
            // earth and sand
            0xFF2B1F1E, 0xFF4A3029, 0xFF6E463A, 0xFF946250, 0xFFB98A6C, 0xFFD8B48E,
            // ice
            0xFFC8DCE6, 0xFFE4EEF0,
            // embers
            0xFF3A1E22, 0xFF6E2F30,
            // night blues
            0xFF1A2238, 0xFF26345A, 0xFF384C7A,
    };

    static final int[] ALL;

    static {
        int n = 13 + GEM.length * 5 + SCENE.length;
        ALL = new int[n];
        int i = 0;
        for (int c : new int[]{INK0, INK1, INK2, INK3, INK4, INK5, MUTED, PALE, CREAM, SNOW, GOLD_D, GOLD, GOLD_L}) ALL[i++] = c;
        for (int[] r : GEM) for (int c : r) ALL[i++] = c;
        for (int c : SCENE) ALL[i++] = c;
    }

    private static final int[] cache = new int[1 << 18];

    static {
        Arrays.fill(cache, -1);
    }

    /** Nearest palette colour (perceptually weighted). */
    static synchronized int nearest(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int key = (r >> 2) << 12 | (g >> 2) << 6 | (b >> 2);
        int hit = cache[key];
        if (hit != -1) return hit;
        int best = ALL[0], bestD = Integer.MAX_VALUE;
        for (int c : ALL) {
            int dr = r - ((c >> 16) & 0xFF), dg = g - ((c >> 8) & 0xFF), db = b - (c & 0xFF);
            int rm = (r + ((c >> 16) & 0xFF)) / 2;
            int d = (512 + rm) * dr * dr / 256 + 4 * dg * dg + (767 - rm) * db * db / 256;
            if (d < bestD) { bestD = d; best = c; }
        }
        cache[key] = best;
        return best;
    }

    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    /**
     * Snap a bitmap to the palette with 4x4 ordered dithering. Pixels under half alpha become
     * transparent; with {@code outline}, transparent pixels touching opaque ones become INK0.
     */
    static void quantize(Bitmap b, int spread, boolean outline) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = y * w + x, c = px[i];
                if ((c >>> 24) < 128) { px[i] = 0; continue; }
                int d = (BAYER[(y & 3) * 4 + (x & 3)] - 8) * spread / 16;
                int r = clamp(((c >> 16) & 0xFF) + d), g = clamp(((c >> 8) & 0xFF) + d), bl = clamp((c & 0xFF) + d);
                px[i] = nearest(0xFF000000 | r << 16 | g << 8 | bl);
            }
        if (outline) {
            int[] out = px.clone();
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if (px[i] != 0) continue;
                    if ((x > 0 && px[i - 1] != 0) || (x < w - 1 && px[i + 1] != 0) || (y > 0 && px[i - w] != 0) || (y < h - 1 && px[i + w] != 0))
                        out[i] = INK0;
                }
            px = out;
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }
}
