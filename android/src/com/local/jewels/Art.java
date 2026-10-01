package com.local.jewels;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

import static com.local.jewels.Palette.mix;

/** Procedural backdrops, one per planet: painted at the game's pixel resolution, then snapped to the palette with dithering. */
final class Art {
    static final int DUNES = 0, CRYSTAL = 1, SWAMP = 2, BAZAAR = 3, MESA = 4, CLOUDS = 5, FUNGAL = 6,
            VOLCANO = 7, ICE = 8, OCEAN = 9, STATION = 10, CORE = 11, DEEP = 12;

    private final Canvas c;
    private final float w, h;
    private final Random R;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private Art(Canvas c, float w, float h, long seed) {
        this.c = c; this.w = w; this.h = h; R = new Random(seed);
    }

    static Bitmap paint(int scene, long seed, int width, int height) {
        int sw = Math.max(2, width), sh = Math.max(2, height);
        Bitmap b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Art a = new Art(new Canvas(b), sw, sh, seed);
        a.scene(scene);
        a.vignette();
        Palette.quantize(b, 26, false);
        return b;
    }

    private static int A(int c, int a) { return (c & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24); }
    private float rf(float a, float b) { return a + R.nextFloat() * (b - a); }

    // ------------------------------------------------------------------ primitives

    private void sky(int... cols) {
        p.setShader(new LinearGradient(0, 0, 0, h, cols, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    /** Single-pixel stars; a few bright ones get a little cross. */
    private void stars(int n, float maxY, float bright) {
        n = n / 2;
        for (int i = 0; i < n; i++) {
            int x = (int) (R.nextFloat() * w), y = (int) (R.nextFloat() * maxY);
            p.setColor(A(mix(0xFFFFFFFF, R.nextBoolean() ? 0xFFFFD0A0 : 0xFFA0C8FF, R.nextFloat() * .5f), (int) (rf(120, 255) * bright)));
            c.drawRect(x, y, x + 1, y + 1, p);
            if (R.nextInt(12) == 0) {
                c.drawRect(x - 1, y, x + 2, y + 1, p);
                c.drawRect(x, y - 1, x + 1, y + 2, p);
            }
        }
    }

    private void nebula(float cx, float cy, float rad, int col, int n, int alpha) {
        for (int i = 0; i < n; i++) {
            float x = cx + (float) R.nextGaussian() * rad * .6f, y = cy + (float) R.nextGaussian() * rad * .3f;
            float r = rad * rf(.2f, .6f);
            p.setShader(new RadialGradient(x, y, r, A(col, (int) (alpha * rf(.5f, 1.2f))), A(col, 0), Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, p);
        }
        p.setShader(null);
    }

    private void glow(float x, float y, float r, int col, int alpha) {
        p.setShader(new RadialGradient(x, y, r, A(col, alpha), A(col, 0), Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
        p.setShader(null);
    }

    /** A planet or moon in the sky. */
    private void body(float x, float y, float r, int col, int bands, int ringCol) {
        glow(x, y, r * 1.35f, col, 70);
        if (ringCol != 0) ring(x, y, r, ringCol, true);
        p.setShader(new RadialGradient(x - r * .35f, y - r * .35f, r * 1.4f, new int[]{mix(col, 0xFFFFFFFF, .35f), col, mix(col, 0xFF000000, .75f)}, new float[]{0, .45f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
        p.setShader(null);
        if (bands > 0) {
            c.save();
            path.reset();
            path.addCircle(x, y, r, Path.Direction.CW);
            c.clipPath(path);
            for (int i = 0; i < bands; i++) {
                float by = y - r + R.nextFloat() * r * 2, bh = r * rf(.04f, .16f);
                p.setColor(A(R.nextBoolean() ? 0xFFFFFFFF : 0xFF000000, (int) rf(14, 40)));
                c.drawRect(x - r, by, x + r, by + bh, p);
            }
            // terminator shadow
            p.setShader(new RadialGradient(x + r * .6f, y + r * .5f, r * 1.5f, new int[]{A(0xFF000000, 200), A(0xFF000000, 60), A(0xFF000000, 0)}, new float[]{0, .5f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, p);
            p.setShader(null);
            c.restore();
        }
        if (ringCol != 0) ring(x, y, r, ringCol, false);
    }

    private void ring(float x, float y, float r, int col, boolean back) {
        c.save();
        c.rotate(-16, x, y);
        c.clipRect(x - r * 3, back ? y - r * 3 : y, x + r * 3, back ? y : y + r * 3);
        p.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 3; i++) {
            p.setStrokeWidth(r * (.12f - i * .03f));
            p.setColor(A(col, 170 - i * 40));
            float rr = r * (1.6f + i * .22f);
            c.drawOval(x - rr, y - rr * .28f, x + rr, y + rr * .28f, p);
        }
        p.setStyle(Paint.Style.FILL);
        c.restore();
    }

    /** Fills below a ridge line. kind: 0 smooth, 1 jagged, 2 mesa */
    private float[] ridge(float baseY, float amp, int kind, int top, int bottom) {
        int n = (int) (w / 2) + 2;
        float[] ys = new float[n];
        if (kind == 0) {
            float f1 = rf(1.5f, 3f), f2 = rf(4f, 7f), f3 = rf(9f, 14f), p1 = rf(0, 6), p2 = rf(0, 6), p3 = rf(0, 6);
            for (int i = 0; i < n; i++) {
                float t = i / (float) (n - 1) * 6.283f;
                ys[i] = baseY - amp * (.55f * (float) Math.sin(t * f1 / 6.283f * 3 + p1) + .3f * (float) Math.sin(t * f2 / 6.283f * 3 + p2) + .15f * (float) Math.sin(t * f3 / 6.283f * 3 + p3));
            }
        } else {
            // midpoint displacement
            int size = 1;
            while (size < n) size <<= 1;
            float[] m = new float[size + 1];
            m[0] = rf(-1, 1);
            m[size] = rf(-1, 1);
            float rough = 1f;
            for (int step = size; step > 1; step >>= 1, rough *= .55f)
                for (int i = 0; i < size; i += step)
                    m[i + step / 2] = (m[i] + m[i + step]) / 2 + rf(-1, 1) * rough;
            for (int i = 0; i < n; i++) ys[i] = baseY - amp * m[i] * .6f;
            if (kind == 2) {
                // mesas: flat tops, steep sides
                int i = 0;
                while (i < n) {
                    int len = (int) rf(n * .06f, n * .2f);
                    boolean high = R.nextInt(3) > 0;
                    float lvl = baseY - (high ? amp * rf(.6f, 1.1f) : amp * rf(-.1f, .15f));
                    for (int j = i; j < Math.min(n, i + len); j++) {
                        float edge = Math.min(j - i, i + len - j) / (n * .012f);
                        float k = Math.min(1, edge);
                        ys[j] = baseY + (lvl - baseY) * k + rf(-1, 1) * amp * .02f;
                    }
                    i += len;
                }
            }
        }
        path.reset();
        path.moveTo(0, h);
        for (int i = 0; i < n; i++) path.lineTo(i * 2, ys[i]);
        path.lineTo(w, h);
        path.close();
        p.setShader(new LinearGradient(0, baseY - amp, 0, h, top, bottom, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
        return ys;
    }

    private void crest(float[] ys, int col, float width) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setColor(col);
        path.reset();
        path.moveTo(0, ys[0]);
        for (int i = 1; i < ys.length; i++) path.lineTo(i * 2, ys[i]);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void haze(float y, float band, int col, int alpha) {
        p.setShader(new LinearGradient(0, y - band, 0, y + band, new int[]{A(col, 0), A(col, alpha), A(col, 0)}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, y - band, w, y + band, p);
        p.setShader(null);
    }

    private void shard(float x, float base, float sw, float sh, float tilt, int col) {
        path.reset();
        path.moveTo(x - sw / 2, base);
        path.lineTo(x + tilt - sw * .1f, base - sh * .92f);
        path.lineTo(x + tilt, base - sh);
        path.lineTo(x + sw / 2, base);
        path.close();
        p.setShader(new LinearGradient(x - sw / 2, 0, x + sw / 2, 0, mix(col, 0xFFFFFFFF, .45f), mix(col, 0xFF000000, .45f), Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
        p.setColor(A(0xFFFFFFFF, 90));
        p.setStrokeWidth(Math.max(1, sw * .06f));
        c.drawLine(x + tilt, base - sh, x - sw * .1f, base, p);
    }

    private void tree(float x, float y, float len, float ang, float width, int depth, int col) {
        if (depth == 0 || len < 2) return;
        float x2 = x + (float) Math.cos(ang) * len, y2 = y + (float) Math.sin(ang) * len;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(width);
        p.setColor(col);
        path.reset();
        path.moveTo(x, y);
        path.quadTo((x + x2) / 2 + rf(-len, len) * .2f, (y + y2) / 2, x2, y2);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        int kids = depth > 3 ? 2 : 2 + R.nextInt(2);
        for (int i = 0; i < kids; i++)
            tree(x2, y2, len * rf(.6f, .8f), ang + rf(-.8f, .8f), width * .65f, depth - 1, col);
        if (depth <= 2 && R.nextInt(2) == 0) { // hanging moss
            p.setStrokeWidth(Math.max(1, width * .4f));
            p.setColor(A(mix(col, 0xFF6A8A3A, .5f), 150));
            c.drawLine(x2, y2, x2 + rf(-2, 2), y2 + len * rf(.5f, 1.4f), p);
        }
    }

    private void cloudBank(float y, int n, float size, int col, int alpha) {
        for (int i = 0; i < n; i++) {
            float x = R.nextFloat() * w, yy = y + rf(-size, size) * .4f, r = size * rf(.5f, 1.2f);
            p.setShader(new RadialGradient(x, yy - r * .3f, r, new int[]{A(mix(col, 0xFFFFFFFF, .4f), alpha), A(col, alpha), A(col, 0)}, new float[]{0, .6f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(x, yy, r, p);
        }
        p.setShader(null);
    }

    private void dots(int n, float y0, float y1, int col, float rMin, float rMax, boolean glowy) {
        for (int i = 0; i < n; i++) {
            float x = R.nextFloat() * w, y = rf(y0, y1), r = rf(rMin, rMax);
            if (glowy) glow(x, y, r * 4, col, 90);
            p.setColor(A(mix(col, 0xFFFFFFFF, .5f), (int) rf(150, 255)));
            c.drawCircle(x, y, r, p);
        }
    }

    private void vignette() {
        p.setShader(new RadialGradient(w / 2, h * .45f, Math.max(w, h) * .75f, new int[]{0x00000000, 0x00000000, 0xA0000000}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    // ------------------------------------------------------------------ scenes

    private void scene(int scene) {
        float hz = h * .3f; // horizon: mostly visible above the board
        switch (scene) {
            case DUNES: {
                sky(0xFF22142E, 0xFF6E2E5A, 0xFFD8643A, 0xFFF7B66A);
                stars(120, hz * .6f, .6f);
                nebula(w * .3f, h * .08f, w * .5f, 0xFFFF7AB0, 10, 30);
                body(w * .78f, h * .12f, w * .2f, 0xFFE8C8A0, 8, 0);
                body(w * .2f, h * .07f, w * .045f, 0xFFB0C8E0, 0, 0);
                body(w * .3f, h * .14f, w * .025f, 0xFFF0E0C0, 0, 0);
                haze(hz, h * .05f, 0xFFFFC080, 160);
                int[][] L = {{0xFFE89A6A, 0xFFB0603A}, {0xFFD27A44, 0xFF8A4022}, {0xFFB05A2A, 0xFF5A2810}, {0xFF7A3A18, 0xFF2A1006}};
                for (int i = 0; i < 4; i++) {
                    float[] ys = ridge(hz + h * (.02f + i * .09f) + i * i * h * .02f, h * (.03f + i * .02f), 0, L[i][0], L[i][1]);
                    crest(ys, A(0xFFFFE0B0, 120 - i * 20), 1.5f + i);
                }
                // blowing sand
                p.setStrokeWidth(1.2f);
                for (int i = 0; i < 70; i++) {
                    float x = R.nextFloat() * w, y = rf(hz, h);
                    p.setColor(A(0xFFFFD8A0, (int) rf(20, 60)));
                    c.drawLine(x, y, x + rf(20, 60), y - rf(2, 8), p);
                }
                break;
            }
            case CRYSTAL: {
                sky(0xFF07041A, 0xFF1E0E46, 0xFF55207A, 0xFF9A4A9A);
                stars(260, hz, 1f);
                nebula(w * .6f, h * .1f, w * .6f, 0xFFFF60C0, 14, 34);
                nebula(w * .2f, h * .16f, w * .4f, 0xFF40E0FF, 10, 30);
                body(w * .25f, h * .1f, w * .13f, 0xFF8AA0FF, 6, 0xFFE0D0FF);
                ridge(hz + h * .02f, h * .07f, 1, 0xFF5A3A8A, 0xFF2A1450);
                haze(hz + h * .03f, h * .04f, 0xFFB080FF, 120);
                ridge(hz + h * .09f, h * .05f, 1, 0xFF3A2468, 0xFF140A30);
                for (int layer = 0; layer < 3; layer++) {
                    float base = hz + h * (.14f + layer * .2f);
                    int n = 10 + layer * 4;
                    for (int i = 0; i < n; i++) {
                        float x = R.nextFloat() * w, sh = h * rf(.04f, .12f) * (1 + layer * .5f);
                        int col = R.nextBoolean() ? 0xFF7AF0FF : 0xFFFF80E0;
                        glow(x, base - sh * .5f, sh * .9f, col, 40);
                        shard(x, base, sh * rf(.2f, .35f), sh, rf(-sh, sh) * .2f, mix(col, 0xFF2A1450, .3f + layer * .1f));
                    }
                    if (layer < 2) ridge(base + h * .02f, h * .02f, 0, 0xFF241248, 0xFF0C0620);
                }
                break;
            }
            case SWAMP: {
                sky(0xFF0A1A18, 0xFF1C4238, 0xFF5E8A6E, 0xFFA8C89A);
                body(w * .65f, h * .14f, w * .1f, 0xFFF0F0C0, 0, 0);
                glow(w * .65f, h * .14f, w * .5f, 0xFFE0FFC0, 60);
                for (int i = 0; i < 3; i++) {
                    float base = hz + h * (.03f + i * .07f);
                    int col = mix(0xFF2A3A2A, 0xFF0A120C, i / 2f);
                    for (int t = 0; t < 4 + i * 2; t++)
                        tree(R.nextFloat() * w, base, h * rf(.05f, .09f) * (1 + i * .4f), -1.57f + rf(-.3f, .3f), w * .012f * (1 + i), 6, col);
                    ridge(base, h * .01f, 0, mix(col, 0xFF5E8A6E, .4f), col);
                    haze(base - h * .02f, h * .04f, 0xFFA8C89A, 110 - i * 30);
                }
                // black water
                float wy = hz + h * .26f;
                p.setShader(new LinearGradient(0, wy, 0, h, 0xFF2A3A30, 0xFF050A08, Shader.TileMode.CLAMP));
                c.drawRect(0, wy, w, h, p);
                p.setShader(null);
                for (int i = 0; i < 60; i++) {
                    float y = rf(wy, h), x = R.nextFloat() * w;
                    p.setColor(A(0xFFC0E0A0, (int) rf(15, 45)));
                    p.setStrokeWidth(1);
                    c.drawLine(x, y, x + rf(10, 50), y, p);
                }
                dots(60, hz - h * .05f, h, 0xFFE0FF60, .8f, 1.8f, true);
                break;
            }
            case BAZAAR: {
                sky(0xFF150828, 0xFF4E1660, 0xFFC0407A, 0xFFFFA860);
                stars(80, hz * .5f, .7f);
                body(w * .5f, hz, w * .22f, 0xFFFF9050, 0, 0);
                // city of domes and spires
                for (int layer = 0; layer < 2; layer++) {
                    float base = hz + h * (.03f + layer * .06f);
                    int col = layer == 0 ? 0xFF5A2050 : 0xFF2E0E2E;
                    p.setColor(col);
                    for (int i = 0; i < 9 + layer * 3; i++) {
                        float x = R.nextFloat() * w, bw = w * rf(.04f, .1f);
                        float bh = h * rf(.03f, .09f) * (layer == 0 ? 1 : 1.3f);
                        c.drawRect(x - bw / 2, base - bh, x + bw / 2, base + h * .1f, p);
                        if (R.nextBoolean()) c.drawOval(x - bw * .6f, base - bh - bw * .55f, x + bw * .6f, base - bh + bw * .55f, p);
                        else {
                            path.reset();
                            path.moveTo(x - bw * .3f, base - bh);
                            path.lineTo(x, base - bh - bw * 1.6f);
                            path.lineTo(x + bw * .3f, base - bh);
                            path.close();
                            c.drawPath(path, p);
                        }
                        for (int k = 0; k < 4; k++) {
                            p.setColor(A(0xFFFFD070, (int) rf(120, 255)));
                            c.drawCircle(x + rf(-bw, bw) * .35f, base - bh * rf(.1f, .9f), Math.max(1, w * .003f), p);
                            p.setColor(col);
                        }
                    }
                    haze(base, h * .03f, 0xFFFF80A0, 70);
                }
                // tents
                float tb = hz + h * .3f;
                for (int i = 0; i < 7; i++) {
                    float x = (i + .5f) * w / 7 + rf(-10, 10), tw = w * rf(.12f, .18f), th = h * rf(.06f, .09f);
                    int col = new int[]{0xFF8A1E4A, 0xFF1E5A7A, 0xFFB0702A, 0xFF5A2A8A}[i % 4];
                    path.reset();
                    path.moveTo(x - tw / 2, tb);
                    path.lineTo(x, tb - th);
                    path.lineTo(x + tw / 2, tb);
                    path.close();
                    p.setShader(new LinearGradient(x - tw / 2, 0, x + tw / 2, 0, mix(col, 0xFFFFFFFF, .25f), mix(col, 0xFF000000, .5f), Shader.TileMode.CLAMP));
                    c.drawPath(path, p);
                    p.setShader(null);
                    glow(x, tb - th * .2f, tw * .4f, 0xFFFFB060, 70);
                }
                p.setShader(new LinearGradient(0, tb, 0, h, 0xFF2A0E26, 0xFF0A0408, Shader.TileMode.CLAMP));
                c.drawRect(0, tb, w, h, p);
                p.setShader(null);
                // strings of lanterns
                for (int s = 0; s < 3; s++) {
                    float y0 = hz + h * (.12f + s * .05f), sag = h * .04f;
                    for (int i = 0; i <= 30; i++) {
                        float t = i / 30f, x = t * w, y = y0 + sag * 4 * t * (1 - t);
                        int col = new int[]{0xFFFFD060, 0xFFFF5A9A, 0xFF60E0FF}[i % 3];
                        glow(x, y, w * .02f, col, 120);
                        p.setColor(mix(col, 0xFFFFFFFF, .5f));
                        c.drawCircle(x, y, w * .004f, p);
                    }
                }
                break;
            }
            case MESA: {
                sky(0xFF2A0A0A, 0xFF8A2A16, 0xFFE07A3A, 0xFFF8C070);
                body(w * .3f, h * .1f, w * .07f, 0xFFFFF0C0, 0, 0);
                glow(w * .3f, h * .1f, w * .45f, 0xFFFFD080, 90);
                body(w * .72f, h * .16f, w * .045f, 0xFFFFC080, 0, 0);
                glow(w * .72f, h * .16f, w * .25f, 0xFFFF9050, 70);
                ridge(hz + h * .01f, h * .1f, 2, 0xFFB0482A, 0xFF6A2412);
                haze(hz + h * .02f, h * .04f, 0xFFFFB070, 110);
                ridge(hz + h * .1f, h * .12f, 2, 0xFF8A2E18, 0xFF3A1008);
                ridge(hz + h * .26f, h * .05f, 1, 0xFF5A1A0C, 0xFF1A0604);
                break;
            }
            case CLOUDS: {
                sky(0xFF081634, 0xFF22508E, 0xFF6EA8D8, 0xFFCDE6F6);
                stars(60, h * .12f, .5f);
                body(w * .5f, hz + h * .32f, w * .9f, 0xFFD8A070, 18, 0);
                cloudBank(hz, 20, w * .1f, 0xFFE8D0F0, 140);
                cloudBank(hz + h * .08f, 20, w * .13f, 0xFFFFC8D8, 170);
                // floating islands
                for (int i = 0; i < 4; i++) {
                    float x = rf(.1f, .9f) * w, y = hz + h * rf(-.05f, .15f), iw = w * rf(.08f, .16f);
                    path.reset();
                    path.moveTo(x - iw, y);
                    path.lineTo(x + iw, y);
                    path.lineTo(x + iw * .2f, y + iw * 1.1f);
                    path.close();
                    p.setShader(new LinearGradient(0, y, 0, y + iw, 0xFF8A6A7A, 0xFF2A1E3A, Shader.TileMode.CLAMP));
                    c.drawPath(path, p);
                    p.setShader(null);
                    p.setColor(0xFFE8E0F0);
                    c.drawOval(x - iw * .5f, y - iw * .45f, x + iw * .3f, y + iw * .3f, p);
                    p.setColor(0xFFFFD080);
                    c.drawCircle(x - iw * .1f, y - iw * .1f, Math.max(1, w * .004f), p);
                }
                cloudBank(hz + h * .2f, 24, w * .16f, 0xFFF0D8F0, 200);
                cloudBank(h * .8f, 20, w * .2f, 0xFFB0A0D0, 160);
                break;
            }
            case FUNGAL: {
                sky(0xFF040310, 0xFF120A2C, 0xFF261642, 0xFF1A1030);
                stars(200, hz, .8f);
                nebula(w * .5f, h * .06f, w * .7f, 0xFF40FFC0, 10, 22);
                for (int layer = 0; layer < 3; layer++) {
                    float base = hz + h * (.06f + layer * .17f);
                    for (int i = 0; i < 6 + layer * 2; i++) {
                        float x = R.nextFloat() * w, sh = h * rf(.06f, .14f) * (1 + layer * .5f), cw = sh * rf(.4f, .7f);
                        int col = new int[]{0xFF40FFD0, 0xFFFF50C0, 0xFF8A70FF, 0xFFFFC040}[R.nextInt(4)];
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(cw * .18f);
                        p.setColor(mix(0xFFD8D0C0, 0xFF1A1030, .5f + layer * .1f));
                        path.reset();
                        path.moveTo(x, base);
                        path.quadTo(x + rf(-cw, cw) * .5f, base - sh * .5f, x + rf(-cw, cw) * .2f, base - sh);
                        c.drawPath(path, p);
                        p.setStyle(Paint.Style.FILL);
                        glow(x, base - sh, cw * 1.6f, col, 70);
                        p.setShader(new RadialGradient(x, base - sh - cw * .2f, cw, mix(col, 0xFFFFFFFF, .3f), mix(col, 0xFF100820, .6f), Shader.TileMode.CLAMP));
                        c.drawArc(new RectF(x - cw, base - sh - cw * .7f, x + cw, base - sh + cw * .3f), 180, 180, true, p);
                        p.setShader(null);
                        p.setColor(A(0xFFFFFFFF, 160));
                        for (int k = 0; k < 4; k++) c.drawCircle(x + rf(-cw, cw) * .6f, base - sh - rf(.05f, .5f) * cw * .6f, cw * .07f, p);
                    }
                    ridge(base, h * .01f, 0, 0xFF1A1030, 0xFF060410);
                }
                dots(90, hz - h * .1f, h, 0xFF80FFD0, .6f, 1.5f, true);
                break;
            }
            case VOLCANO: {
                sky(0xFF0E0303, 0xFF2E0808, 0xFF6A1808, 0xFFB0400E);
                cloudBank(h * .06f, 18, w * .14f, 0xFF3A1A14, 170);
                // volcano cone
                float vx = w * .55f, vtop = hz - h * .09f;
                path.reset();
                path.moveTo(vx - w * .6f, hz + h * .08f);
                path.lineTo(vx - w * .07f, vtop);
                path.lineTo(vx + w * .07f, vtop);
                path.lineTo(vx + w * .6f, hz + h * .08f);
                path.close();
                p.setShader(new LinearGradient(0, vtop, 0, hz + h * .08f, 0xFF3A1410, 0xFF120404, Shader.TileMode.CLAMP));
                c.drawPath(path, p);
                p.setShader(null);
                glow(vx, vtop, w * .3f, 0xFFFF6010, 160);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i < 5; i++) {
                    path.reset();
                    float x = vx + rf(-w * .05f, w * .05f);
                    path.moveTo(x, vtop);
                    path.cubicTo(x + rf(-w * .1f, w * .1f), vtop + h * .05f, x + rf(-w * .2f, w * .2f), hz, x + rf(-w * .3f, w * .3f), hz + h * .08f);
                    p.setStrokeWidth(rf(1.5f, 3.5f));
                    p.setColor(A(0xFFFF8020, 220));
                    c.drawPath(path, p);
                }
                p.setStyle(Paint.Style.FILL);
                ridge(hz + h * .1f, h * .06f, 1, 0xFF2A0C08, 0xFF0A0202);
                // lava river glow
                haze(hz + h * .2f, h * .02f, 0xFFFF5010, 160);
                ridge(hz + h * .25f, h * .07f, 1, 0xFF1A0604, 0xFF050101);
                dots(120, 0, h, 0xFFFF9030, .6f, 1.6f, true);
                break;
            }
            case ICE: {
                sky(0xFF050C1E, 0xFF14305A, 0xFF4E80B0, 0xFFC0E0F8);
                stars(150, hz, .8f);
                // aurora ribbons
                for (int b = 0; b < 3; b++) {
                    int col = b == 1 ? 0xFF80FFC0 : 0xFF60E0FF;
                    float y0 = h * (.04f + b * .05f);
                    for (int i = 0; i < 90; i++) {
                        float x = (float) Math.floor(i * w / 90f), x2 = (float) Math.floor((i + 1) * w / 90f);
                        float y = y0 + (float) Math.sin(i * .12f + b) * h * .03f;
                        p.setShader(new LinearGradient(x, y, x, y + h * .1f, A(col, 70), A(col, 0), Shader.TileMode.CLAMP));
                        c.drawRect(x, y, x2, y + h * .1f, p);
                    }
                }
                p.setShader(null);
                ridge(hz + h * .02f, h * .1f, 1, 0xFFE0F0FF, 0xFF7A9AC0);
                haze(hz + h * .04f, h * .04f, 0xFFE0F0FF, 140);
                ridge(hz + h * .12f, h * .08f, 1, 0xFFB0D0F0, 0xFF3A5A8A);
                for (int i = 0; i < 16; i++) {
                    float x = R.nextFloat() * w, sh = h * rf(.04f, .1f);
                    shard(x, hz + h * .32f, sh * .4f, sh, rf(-sh, sh) * .15f, 0xFFA8D8FF);
                }
                ridge(hz + h * .32f, h * .02f, 0, 0xFFD8ECFF, 0xFF6A8AB0);
                dots(80, hz, h, 0xFFFFFFFF, .5f, 1.3f, false);
                break;
            }
            case OCEAN: {
                sky(0xFF041A2C, 0xFF14587A, 0xFF5AB0C0, 0xFFC8F0E8);
                stars(60, h * .1f, .5f);
                body(w * .3f, h * .13f, w * .17f, 0xFFF0B8A0, 10, 0xFFFFE0D0);
                haze(hz, h * .03f, 0xFFE0FFFF, 150);
                // ocean
                p.setShader(new LinearGradient(0, hz, 0, h, 0xFF2A8A9A, 0xFF02141E, Shader.TileMode.CLAMP));
                c.drawRect(0, hz, w, h, p);
                p.setShader(null);
                // rock arches
                for (int i = 0; i < 4; i++) {
                    float x = R.nextFloat() * w, aw = w * rf(.08f, .18f), ah = h * rf(.05f, .12f);
                    float base = hz + h * rf(.01f, .1f);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(aw * .35f);
                    p.setColor(mix(0xFF0A1A20, 0xFF5AB0C0, .3f - i * .05f));
                    c.drawArc(new RectF(x - aw, base - ah, x + aw, base + ah), 180, 180, false, p);
                    p.setStyle(Paint.Style.FILL);
                }
                for (int i = 0; i < 160; i++) {
                    float y = rf(hz, h), x = R.nextFloat() * w, k = (y - hz) / (h - hz);
                    p.setColor(A(0xFFE0FFFF, (int) rf(20, 70)));
                    p.setStrokeWidth(1 + k * 2);
                    c.drawLine(x, y, x + rf(8, 30) * (1 + k * 3), y, p);
                }
                break;
            }
            case STATION: {
                sky(0xFF02030A, 0xFF080C26, 0xFF141432);
                stars(320, h, 1f);
                nebula(w * .4f, h * .5f, w * .8f, 0xFF4060FF, 12, 26);
                nebula(w * .7f, h * .2f, w * .5f, 0xFFFFA040, 8, 22);
                float cx = w * .5f, cy = h * .16f;
                glow(cx, cy, w * .12f, 0xFFFFE0A0, 200);
                p.setStyle(Paint.Style.STROKE);
                for (int i = 0; i < 5; i++) {
                    float rx = w * (.16f + i * .09f), ry = rx * (.28f + i * .02f);
                    c.save();
                    c.rotate(-12 + i * 5, cx, cy);
                    p.setStrokeWidth(w * .006f);
                    p.setColor(A(0xFFE0C070, 200 - i * 25));
                    c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, p);
                    p.setStyle(Paint.Style.FILL);
                    double a = R.nextDouble() * 6.28;
                    float px = cx + (float) Math.cos(a) * rx, py = cy + (float) Math.sin(a) * ry;
                    p.setShader(new RadialGradient(px - 2, py - 2, w * .03f, 0xFFFFF0C0, 0xFF8A5A20, Shader.TileMode.CLAMP));
                    c.drawCircle(px, py, w * (.012f + i * .004f), p);
                    p.setShader(null);
                    p.setStyle(Paint.Style.STROKE);
                    c.restore();
                }
                p.setStyle(Paint.Style.FILL);
                body(w * .85f, h * .55f, w * .3f, 0xFF4A6A9A, 14, 0xFFA0B0D0);
                break;
            }
            case CORE: {
                sky(0xFF000000, 0xFF080214, 0xFF16052A);
                stars(500, h, 1f);
                nebula(w * .5f, h * .2f, w * .9f, 0xFF9A30FF, 16, 30);
                nebula(w * .5f, h * .2f, w * .5f, 0xFFFFB040, 10, 26);
                float cx = w * .5f, cy = h * .17f, r = w * .1f;
                glow(cx, cy, w * .6f, 0xFFFFA040, 70);
                c.save();
                c.rotate(-10, cx, cy);
                for (int i = 0; i < 26; i++) {
                    float rx = r * (1.4f + i * .1f), ry = rx * .22f;
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .08f);
                    p.setColor(A(mix(0xFFFFF0C0, 0xFFFF3080, i / 26f), 200 - i * 7));
                    c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.restore();
                p.setColor(0xFF000000);
                c.drawCircle(cx, cy, r, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * .06f);
                p.setColor(0xD0FFE0A0);
                c.drawCircle(cx, cy, r * 1.05f, p);
                p.setStyle(Paint.Style.FILL);
                break;
            }
            default: { // DEEP: menu / voyage map
                sky(0xFF03030C, 0xFF0A0E2A, 0xFF1A1440);
                stars(420, h, 1f);
                nebula(w * .3f, h * .25f, w * .8f, 0xFFFF6A3A, 14, 26);
                nebula(w * .7f, h * .6f, w * .8f, 0xFF4A6AFF, 14, 26);
                nebula(w * .5f, h * .9f, w * .7f, 0xFFB040FF, 10, 22);
                body(w * .85f, h * .12f, w * .12f, 0xFFE09060, 8, 0xFFF0D0B0);
                body(w * .12f, h * .82f, w * .2f, 0xFF4A7AA0, 10, 0);
            }
        }
    }
}
