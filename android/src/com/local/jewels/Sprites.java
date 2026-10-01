package com.local.jewels;

import android.graphics.Bitmap;

/**
 * Pixel-art gems generated pixel by pixel: a distinct silhouette per type, faceted
 * shading from a top-left light, a dark outline and a little sparkle.
 */
final class Sprites {
    private static final int OUT = 0, DARK = 1, MID = 2, LIGHT = 3, HI = 4;

    static int base(int t) {
        return t >= 0 && t < Palette.GEM.length ? Palette.GEM[t][LIGHT] : Palette.CREAM;
    }

    /** Is (u, v) in [-1, 1]² inside gem shape t? */
    private static boolean inside(int t, float u, float v) {
        float au = Math.abs(u), av = Math.abs(v);
        switch (t) {
            case 0: return au <= .76f && av <= .76f && au + av <= 1.36f;                 // square
            case 1: return u * u + v * v <= .8f * .8f;                                    // round
            case 2: return au <= .8f && av <= .8f && au + av <= 1.12f;                    // octagon
            case 3: return au + av <= .92f;                                               // diamond
            case 4: return v <= .72f && v >= -.86f && au <= (v + .86f) / 1.58f * .92f;   // triangle
            case 5: return av <= .74f && au + av * .58f <= .92f;                          // hexagon
            default: return v < -.2f ? au <= .7f * (v + .96f) / .76f : au <= .7f * (.96f - v) / 1.16f; // kite
        }
    }

    /** Which shade a pixel inside the gem gets. */
    private static int shade(int t, float u, float v) {
        switch (t) {
            case 1: { // sphere
                float r2 = (u * u + v * v) / (.8f * .8f);
                float z = (float) Math.sqrt(Math.max(0, 1 - r2));
                float i = (-.5f * u / .8f - .6f * v / .8f + .62f * z) / 0.95f;
                return i > .86f ? HI : i > .55f ? LIGHT : i > .2f ? MID : DARK;
            }
            case 3:
            case 6: { // faceted: table on top, four facets
                if (inside(t, u / .45f, v / .45f)) return LIGHT;
                if (u < 0 && v < 0) return LIGHT;
                if (u >= 0 && v >= 0) return DARK;
                return MID;
            }
            case 4: { // triangle: left facet lit, right and bottom in shade
                if (inside(4, u / .5f, (v - .2f) / .5f + .2f)) return MID;
                if (v > .45f) return DARK;
                return u < 0 ? LIGHT : DARK;
            }
            default: { // bevelled stones: lit top-left rim, dark bottom-right rim, flat face
                if (inside(t, u / .68f, v / .68f)) return MID;
                float d = -(u + v);
                return d > .25f ? LIGHT : d < -.25f ? DARK : MID;
            }
        }
    }

    /** Gem sprites at size x size, one per colour. */
    static Bitmap[] build(int size) {
        size = Math.max(8, size);
        Bitmap[] out = new Bitmap[Palette.GEM.length];
        for (int t = 0; t < out.length; t++) out[t] = gem(t, size, Palette.GEM[t]);
        return out;
    }

    private static Bitmap gem(int t, int size, int[] ramp) {
        int[] px = new int[size * size];
        boolean[] in = new boolean[size * size];
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                float u = (x + .5f) / size * 2 - 1, v = (y + .5f) / size * 2 - 1;
                in[y * size + x] = inside(t, u, v);
            }
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                int i = y * size + x;
                if (!in[i]) continue;
                boolean edge = x == 0 || y == 0 || x == size - 1 || y == size - 1
                        || !in[i - 1] || !in[i + 1] || !in[i - size] || !in[i + size];
                float u = (x + .5f) / size * 2 - 1, v = (y + .5f) / size * 2 - 1;
                px[i] = ramp[edge ? OUT : shade(t, u, v)];
            }
        // soft drop shadow one pixel down-right
        for (int y = size - 2; y >= 0; y--)
            for (int x = size - 2; x >= 0; x--) {
                int i = y * size + x, j = (y + 1) * size + x + 1;
                if (in[i] && !in[j] && px[j] == 0) px[j] = 0x66000000;
            }
        // sparkle: a small cross on the upper-left of the face
        int sx = Math.round(size * .36f), sy = Math.round(size * (t == 4 ? .5f : .34f));
        int s = Math.max(1, size / 16);
        for (int k = -s; k <= s; k++) {
            set(px, size, in, sx + k, sy, ramp[HI]);
            set(px, size, in, sx, sy + k, ramp[HI]);
        }
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, size, 0, 0, size, size);
        return b;
    }

    private static void set(int[] px, int size, boolean[] in, int x, int y, int c) {
        if (x < 0 || y < 0 || x >= size || y >= size || !in[y * size + x]) return;
        px[y * size + x] = c;
    }

    /** A two-pixel aura around each gem shape, for flame gems (two flicker phases). */
    static Bitmap[][] auras(int size) {
        Bitmap[][] out = new Bitmap[Palette.GEM.length][2];
        int[] cols = {Palette.GEM[5][3], Palette.GEM[3][3], Palette.GEM[5][2]};
        for (int t = 0; t < out.length; t++) {
            boolean[] in = new boolean[size * size];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    in[y * size + x] = inside(t, (x + .5f) / size * 2 - 1, (y + .5f) / size * 2 - 1);
            for (int phase = 0; phase < 2; phase++) {
                int[] px = new int[size * size];
                for (int y = 0; y < size; y++)
                    for (int x = 0; x < size; x++) {
                        int i = y * size + x;
                        if (in[i]) continue;
                        int dist = 99;
                        for (int dy = -2; dy <= 2; dy++)
                            for (int dx = -2; dx <= 2; dx++) {
                                int xx = x + dx, yy = y + dy;
                                if (xx < 0 || yy < 0 || xx >= size || yy >= size || !in[yy * size + xx]) continue;
                                dist = Math.min(dist, Math.max(Math.abs(dx), Math.abs(dy)));
                            }
                        if (dist == 1) px[i] = cols[(x + y + phase) % 2 == 0 ? 0 : 1];
                        else if (dist == 2 && ((x * 7 + y * 3 + phase * 5) % 4 == 0)) px[i] = cols[2];
                    }
                Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                b.setPixels(px, 0, size, 0, 0, size, size);
                out[t][phase] = b;
            }
        }
        return out;
    }

    /** The hypercube: an isometric pixel cube whose faces cycle through the gem colours. */
    static Bitmap[] hyper(int size) {
        int n = Palette.GEM.length;
        Bitmap[] frames = new Bitmap[n];
        for (int f = 0; f < n; f++) {
            int[] px = new int[size * size];
            boolean[] in = new boolean[size * size];
            int[] face = new int[size * size];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++) {
                    float u = (x + .5f) / size * 2 - 1, v = (y + .5f) / size * 2 - 1;
                    float au = Math.abs(u);
                    // a cube seen corner-on: hexagon silhouette, top rhombus above the
                    // lines from the side corners to the centre, left and right faces below
                    if (au > .76f || Math.abs(v) > .86f - .566f * au) continue;
                    in[y * size + x] = true;
                    face[y * size + x] = v < -.566f * au ? 0 : (u < 0 ? 1 : 2);
                }
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++) {
                    int i = y * size + x;
                    if (!in[i]) continue;
                    boolean edge = x == 0 || y == 0 || x == size - 1 || y == size - 1 || !in[i - 1] || !in[i + 1] || !in[i - size] || !in[i + size];
                    boolean seam = !edge && ((x + 1 < size && face[i + 1] != face[i]) || (y + 1 < size && face[i + size] != face[i]));
                    int[] ramp = Palette.GEM[(f + face[i] * 2) % n];
                    px[i] = edge ? Palette.INK0 : seam ? ramp[OUT] : ramp[face[i] == 0 ? LIGHT : face[i] == 1 ? MID : DARK];
                }
            int c = size / 2;
            for (int k = -1; k <= 1; k++) {
                if (c + k >= 0 && c + k < size) px[(size / 3) * size + c + k] = Palette.SNOW;
                px[(size / 3 + k) * size + c] = Palette.SNOW;
            }
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            b.setPixels(px, 0, size, 0, 0, size, size);
            frames[f] = b;
        }
        return frames;
    }
}
