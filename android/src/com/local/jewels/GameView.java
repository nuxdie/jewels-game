package com.local.jewels;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.local.jewels.Palette.CREAM;
import static com.local.jewels.Palette.GOLD;
import static com.local.jewels.Palette.GOLD_D;
import static com.local.jewels.Palette.GOLD_L;
import static com.local.jewels.Palette.INK0;
import static com.local.jewels.Palette.INK1;
import static com.local.jewels.Palette.INK2;
import static com.local.jewels.Palette.INK3;
import static com.local.jewels.Palette.INK4;
import static com.local.jewels.Palette.MUTED;
import static com.local.jewels.Palette.PALE;
import static com.local.jewels.Palette.SNOW;

/**
 * SurfaceView with its own game thread. Everything is drawn into a small frame buffer
 * (about 270 pixels wide) on one pixel grid, then scaled up without smoothing.
 * Input is queued from the UI thread and handled on the game thread.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable, Game.Listener {
    enum Screen { MENU, MAP, GAME, PAUSE, OVER, JUKEBOX }

    private static final int EV_BACK = -1;
    // font sizes that land exactly on each font's pixel grid
    private static final int BODY = 8, UI = 10, UI2 = 20, TITLE = 24, TITLE2 = 48;

    private final Activity act;
    private final Audio audio;
    private final SharedPreferences prefs;
    private final Game game = new Game(this);
    private final Random rnd = new Random();
    private final ConcurrentLinkedQueue<float[]> input = new ConcurrentLinkedQueue<>();
    private final Typeface fBody, fUi, fTitle;

    private Screen screen = Screen.MENU;
    private Thread thread;
    private volatile boolean running;
    private boolean surfaceReady, resumed;
    private volatile int insetTop, insetBottom;
    private int realW, realH, laidW, laidH, laidTop, laidBottom;
    volatile int debugPlanet = -1;
    volatile boolean debugMap, debugWin, debugEndless;

    // virtual pixel canvas
    private int S = 4, W, H;
    private Bitmap frame;
    private Canvas fc;
    private final Rect dst = new Rect();
    private final Paint blit = new Paint();

    // layout, in virtual pixels
    private int bx, by, cs, bsize, hudTop, hudH, barY, barH, top, bottom;
    private Bitmap[] sprites, hyper;
    private Bitmap[][] auras;
    private final Bitmap[] backgrounds = new Bitmap[Planets.COUNT + 1]; // last = deep space
    private float time, shake, bannerT, bannerMax, shownScore, mapT;
    private String banner;
    private int best, bestEndless;
    private boolean musicOn, newBest, voyageDoneShown;

    // effects
    private static final class Part { float x, y, vx, vy, life, max; int color, size; }
    private static final class Pop { float x, y, t; String s; }
    private static final class Fx { float x1, y1, x2, y2, t, max; boolean ring; }
    private static final class Mote { float x, y, vx, vy, ph; }
    private final ArrayList<Part> parts = new ArrayList<>();
    private final ArrayList<Pop> pops = new ArrayList<>();
    private final ArrayList<Fx> fxs = new ArrayList<>();
    private final Mote[] motes = new Mote[40];
    private int moteScene = -1;
    private int twinkleR = -1, twinkleC;
    private float twinkleT;

    // buttons (rebuilt every frame for the current screen)
    private static final class Btn {
        final RectF r; final String label; final Runnable act;
        Btn(RectF r, String label, Runnable act) { this.r = r; this.label = label; this.act = act; }
    }
    private final ArrayList<Btn> btns = new ArrayList<>();
    private Btn pressed;
    private int downR = -1, downC;
    private float downX, downY;

    private final Paint paint = new Paint();
    private final Paint tp = new Paint();
    private final Path path = new Path();
    private final Rect bounds = new Rect();
    private final HashMap<String, List<String>> wraps = new HashMap<>();

    public GameView(Context ctx) {
        super(ctx);
        act = (Activity) ctx;
        audio = new Audio(ctx);
        prefs = ctx.getSharedPreferences("jewels", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        bestEndless = prefs.getInt("best_endless", 0);
        musicOn = prefs.getBoolean("music", true);
        audio.sfxOn = prefs.getBoolean("sfx", true);
        audio.setMusicOn(musicOn);
        fBody = Typeface.createFromAsset(ctx.getAssets(), "fonts/Tiny5.ttf");
        fUi = Typeface.createFromAsset(ctx.getAssets(), "fonts/Jersey10.ttf");
        fTitle = Typeface.createFromAsset(ctx.getAssets(), "fonts/Jacquard12.ttf");
        paint.setAntiAlias(false);
        tp.setAntiAlias(false);
        blit.setFilterBitmap(false);
        for (int i = 0; i < motes.length; i++) motes[i] = new Mote();
        getHolder().addCallback(this);
        setFocusable(true);
        audio.music("menu", null);
    }

    // ================================================================ lifecycle

    void onResumeActivity() {
        resumed = true;
        audio.resume();
        startThread();
    }

    void onPauseActivity() {
        resumed = false;
        stopThread();
        audio.pause();
        if (screen == Screen.GAME && canPause()) screen = Screen.PAUSE;
    }

    void release() {
        stopThread();
        audio.release();
    }

    void back() {
        input.add(new float[]{EV_BACK, 0, 0});
    }

    @Override public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; startThread(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
    @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; stopThread(); }

    private void startThread() {
        if (!surfaceReady || !resumed || running) return;
        running = true;
        thread = new Thread(this, "game");
        thread.start();
    }

    private void stopThread() {
        running = false;
        if (thread != null) {
            try { thread.join(); } catch (InterruptedException ignored) { }
            thread = null;
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets i = in.getInsets(WindowInsets.Type.displayCutout() | WindowInsets.Type.systemBars());
            insetTop = i.top;
            insetBottom = i.bottom;
        } else if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout dc = in.getDisplayCutout();
            insetTop = dc != null ? dc.getSafeInsetTop() : 0;
            insetBottom = dc != null ? dc.getSafeInsetBottom() : 0;
        }
        return super.onApplyWindowInsets(in);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)
            input.add(new float[]{a, e.getX(), e.getY()});
        return true;
    }

    // ================================================================ loop

    @Override
    public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = Math.min(.05f, (now - last) / 1e9f);
            last = now;
            realW = getWidth();
            realH = getHeight();
            if (realW == 0 || realH == 0) continue;
            if (realW != laidW || realH != laidH || insetTop != laidTop || insetBottom != laidBottom) layout();
            if (debugPlanet >= 0) {
                game.endless = debugEndless;
                game.newGame();
                game.level = debugPlanet + 1;
                debugPlanet = -1;
                if (debugMap) showMap(false); else land();
            }
            if (debugWin && screen == Screen.GAME && game.phase == Game.Phase.IDLE) {
                debugWin = false;
                game.debugCompleteDeal();
            }
            handleInput();
            update(dt);
            render(fc);
            Canvas c = null;
            try {
                c = getHolder().lockHardwareCanvas();
                if (c != null) c.drawBitmap(frame, null, dst, blit);
            } catch (RuntimeException ignored) {
            } finally {
                if (c != null) try { getHolder().unlockCanvasAndPost(c); } catch (RuntimeException ignored) { }
            }
            long spent = System.nanoTime() - now;
            if (spent < 8_000_000L) try { Thread.sleep((8_000_000L - spent) / 1_000_000L); } catch (InterruptedException ignored) { }
        }
    }

    private void layout() {
        laidW = realW; laidH = realH; laidTop = insetTop; laidBottom = insetBottom;
        S = Math.max(2, Math.round(realW / 270f));
        W = (realW + S - 1) / S;
        H = (realH + S - 1) / S;
        frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        fc = new Canvas(frame);
        dst.set(0, 0, W * S, H * S);

        top = (insetTop + S - 1) / S + 4;
        bottom = (insetBottom + S - 1) / S + 4;
        cs = Math.min(32, (W - 8) / Game.N);
        bsize = cs * Game.N;
        bx = (W - bsize) / 2;
        barH = 22;
        barY = H - bottom - barH / 2;
        by = barY - barH / 2 - 8 - bsize;
        hudH = 48;
        hudTop = by - 8 - hudH;

        sprites = Sprites.build(cs);
        auras = Sprites.auras(cs);
        hyper = Sprites.hyper(cs);
        for (int i = 0; i < backgrounds.length; i++) backgrounds[i] = null;
        wraps.clear();
        moteScene = -1;
    }

    private Bitmap background(int planet) {
        int i = planet < 0 ? Planets.COUNT : planet;
        if (backgrounds[i] == null)
            backgrounds[i] = planet < 0 ? Art.paint(Art.DEEP, 7, W, H) : Art.paint(Planets.get(planet).scene, Planets.get(planet).seed, W, H);
        return backgrounds[i];
    }

    private static int accent(int planet) {
        return Palette.nearest(Planets.get(planet).accent);
    }

    // ================================================================ input

    private void handleInput() {
        float[] e;
        while ((e = input.poll()) != null) {
            int a = (int) e[0];
            if (a == EV_BACK) { onBack(); continue; }
            float x = e[1] / S, y = e[2] / S;
            if (screen == Screen.GAME && boardInput(a, x, y)) continue;
            if (a == MotionEvent.ACTION_DOWN) {
                pressed = null;
                for (Btn b : btns) if (b.r.contains(x, y)) pressed = b;
            } else if (a == MotionEvent.ACTION_UP) {
                Btn b = pressed;
                pressed = null;
                if (b != null && b.r.contains(x, y)) {
                    audio.sfx("click", 1f);
                    b.act.run();
                }
            } else if (a == MotionEvent.ACTION_CANCEL) {
                pressed = null;
            }
        }
    }

    /** Returns true if the event was consumed by the board. */
    private boolean boardInput(int a, float x, float y) {
        if (a == MotionEvent.ACTION_DOWN) {
            if (x < bx || y < by || x >= bx + bsize || y >= by + bsize) return false;
            int c = (int) ((x - bx) / cs), r = (int) ((y - by) / cs);
            downR = -1;
            if (!game.tap(r, c) && game.phase == Game.Phase.IDLE) {
                downR = r; downC = c; downX = x; downY = y;
            }
            return true;
        }
        if (a == MotionEvent.ACTION_MOVE && downR >= 0) {
            float dx = x - downX, dy = y - downY;
            if (Math.max(Math.abs(dx), Math.abs(dy)) > cs * .35f) {
                int dr = 0, dc = 0;
                if (Math.abs(dx) > Math.abs(dy)) dc = dx > 0 ? 1 : -1; else dr = dy > 0 ? 1 : -1;
                int r = downR, c = downC;
                downR = -1;
                game.trySwap(r, c, r + dr, c + dc);
            }
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            boolean mine = downR >= 0;
            downR = -1;
            return mine;
        }
        return false;
    }

    private void onBack() {
        switch (screen) {
            case GAME: if (canPause()) screen = Screen.PAUSE; break;
            case PAUSE: screen = Screen.GAME; break;
            case MAP:
            case OVER:
            case JUKEBOX: toMenu(); break;
            case MENU: act.runOnUiThread(act::finish); break;
        }
    }

    private boolean canPause() {
        Game.Phase p = game.phase;
        return p == Game.Phase.IDLE || p == Game.Phase.SWAP || p == Game.Phase.SWAP_BACK || p == Game.Phase.CLEAR || p == Game.Phase.FALL;
    }

    // ================================================================ screens / actions

    private void toMenu() {
        screen = Screen.MENU;
        if (!"menu".equals(audio.want())) audio.music("menu", null);
    }

    private void clearFx() {
        parts.clear(); pops.clear(); fxs.clear();
        banner = null;
    }

    // voyage and endless games keep separate saves and records
    private static String saveKey(boolean endless) { return endless ? "save_endless" : "save"; }
    private int bestNow() { return game.endless ? bestEndless : best; }

    private void newVoyage() {
        startNew(false);
    }

    private void newEndless() {
        startNew(true);
    }

    private void startNew(boolean endless) {
        clearFx();
        newBest = false;
        game.endless = endless;
        game.newGame();
        showMap(false);
    }

    private void continueGame(boolean endless) {
        clearFx();
        newBest = false;
        String s = prefs.getString(saveKey(endless), null);
        if (s == null || !game.restore(s)) { startNew(endless); return; }
        shownScore = game.score;
        screen = Screen.GAME;
    }

    /** "Continue" label suffix: the planet a save is at. */
    private static String savedAt(String save) {
        try {
            String[] p = save.split(";");
            int lvl = Integer.parseInt(p[p[0].equals("v1") ? 2 : 3]);
            return ": " + Planets.get((lvl - 1) % Planets.COUNT).name;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void showMap(boolean voyageDone) {
        voyageDoneShown = voyageDone;
        mapT = 0;
        screen = Screen.MAP;
        // Departure only sets off a voyage; between ports the Loading theme plays
        // (after the Deal Struck jingle, or after Final Destination, which flows into it by itself).
        String now = audio.want();
        if (!voyageDone && game.planet() == 0 && (!game.endless || game.level == 1)) {
            if (!"depart".equals(now)) audio.music("depart", null);
        } else if (!"clear".equals(now) && !"finale".equals(now) && !"loading".equals(now)) {
            audio.music("loading", null);
        }
    }

    private void land() {
        clearFx();
        shownScore = game.score;
        screen = Screen.GAME;
        game.land();
    }

    private void toggleMusic() {
        musicOn = !musicOn;
        audio.setMusicOn(musicOn);
        prefs.edit().putBoolean("music", musicOn).apply();
    }

    private void toggleSfx() {
        audio.sfxOn = !audio.sfxOn;
        prefs.edit().putBoolean("sfx", audio.sfxOn).apply();
    }

    // ================================================================ Game.Listener (game thread)

    @Override public void sfx(String name, float vol) { audio.sfx(name, vol); }
    @Override public void music(String name, String then) { audio.music(name, then); }

    private float cx(float c) { return bx + (c + .5f) * cs; }
    private float cy(float r) { return by + (r + .5f) * cs; }

    @Override
    public void burst(float r, float c, int type) {
        int[] ramp = type >= 0 && type < Palette.GEM.length ? Palette.GEM[type] : Palette.GEM[1];
        for (int i = 0; i < 7; i++) spark(cx(c), cy(r), ramp[2 + rnd.nextInt(3)], cs * (2f + rnd.nextFloat() * 3.5f), .45f + rnd.nextFloat() * .35f);
    }

    private void spark(float x, float y, int color, float speed, float life) {
        if (parts.size() > 500) return;
        Part p = new Part();
        double a = rnd.nextDouble() * Math.PI * 2;
        p.x = x; p.y = y;
        p.vx = (float) Math.cos(a) * speed;
        p.vy = (float) Math.sin(a) * speed - cs * 2f;
        p.life = p.max = life;
        p.size = rnd.nextInt(3) == 0 ? 2 : 1;
        p.color = color;
        parts.add(p);
    }

    @Override
    public void explosion(int r, int c) {
        Fx f = new Fx();
        f.x1 = cx(c); f.y1 = cy(r); f.ring = true; f.t = f.max = .4f;
        fxs.add(f);
        shake = Math.max(shake, 3);
        for (int i = 0; i < 22; i++) spark(f.x1, f.y1, Palette.GEM[i % 2 == 0 ? 5 : 3][3], cs * (3f + rnd.nextFloat() * 5f), .5f + rnd.nextFloat() * .4f);
    }

    @Override
    public void zap(int r1, int c1, int r2, int c2) {
        Fx f = new Fx();
        f.x1 = cx(c1); f.y1 = cy(r1); f.x2 = cx(c2); f.y2 = cy(r2); f.t = f.max = .4f;
        fxs.add(f);
    }

    @Override
    public void popup(float r, float c, String s) {
        Pop p = new Pop();
        p.x = cx(c); p.y = cy(r); p.s = s; p.t = 1f;
        pops.add(p);
    }

    @Override
    public void banner(String s, float secs) {
        banner = s;
        bannerT = bannerMax = secs;
    }

    @Override
    public void save() {
        SharedPreferences.Editor e = prefs.edit().putString(saveKey(game.endless), game.serialize());
        if (game.endless && game.score > bestEndless) { bestEndless = game.score; e.putInt("best_endless", bestEndless); }
        if (!game.endless && game.score > best) { best = game.score; e.putInt("best", best); }
        e.apply();
    }

    @Override
    public void gameOver() {
        newBest = game.score > prefs.getInt("best", 0);
        if (game.score > best) best = game.score;
        prefs.edit().remove("save").putInt("best", best).apply();
        audio.music("finale", null);
        screen = Screen.OVER;
    }

    @Override
    public void stageComplete(boolean voyageDone) {
        showMap(voyageDone);
    }

    // ================================================================ update

    private void update(float dt) {
        time += dt;
        mapT += dt;
        if (screen == Screen.GAME) game.update(dt);
        shownScore += (game.score - shownScore) * Math.min(1f, dt * 8f);
        if (Math.abs(game.score - shownScore) < 1) shownScore = game.score;
        shake = Math.max(0, shake - dt * 12);
        if (bannerT > 0) bannerT -= dt;
        twinkleT -= dt;
        if (twinkleT <= 0) {
            twinkleT = .35f + rnd.nextFloat() * .5f;
            twinkleR = rnd.nextInt(Game.N);
            twinkleC = rnd.nextInt(Game.N);
        }

        float grav = cs * 14f;
        for (Iterator<Part> it = parts.iterator(); it.hasNext(); ) {
            Part p = it.next();
            p.life -= dt;
            if (p.life <= 0) { it.remove(); continue; }
            p.vy += grav * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }
        for (Iterator<Pop> it = pops.iterator(); it.hasNext(); ) {
            Pop p = it.next();
            p.t -= dt * .9f;
            p.y -= cs * .8f * dt;
            if (p.t <= 0) it.remove();
        }
        for (Iterator<Fx> it = fxs.iterator(); it.hasNext(); ) {
            Fx f = it.next();
            f.t -= dt;
            if (f.t <= 0) it.remove();
        }
        updateMotes(dt);
        // embers rising from flame gems
        if (screen == Screen.GAME)
            for (int r = 0; r < Game.N; r++)
                for (int c = 0; c < Game.N; c++) {
                    Gem m = game.g[r][c];
                    if (m != null && m.sp == Game.FLAME && !m.dying && rnd.nextFloat() < dt * 5) {
                        Part p = new Part();
                        p.x = cx(m.x) + (rnd.nextFloat() - .5f) * cs * .5f;
                        p.y = cy(m.y) - cs * .3f;
                        p.vx = (rnd.nextFloat() - .5f) * cs * .3f;
                        p.vy = -cs * (1.2f + rnd.nextFloat()) - grav * .6f;
                        p.life = p.max = .5f;
                        p.size = 1;
                        p.color = Palette.GEM[rnd.nextBoolean() ? 5 : 3][3];
                        parts.add(p);
                    }
                }
    }

    // ambient motes per planet: sand, sparkles, fireflies, lantern sparks, dust, wisps, spores, embers, snow, spray...
    private static final int[] MOTE_COL = {0xFFEEC39A, 0xFFB4DCCB, 0xFFE0EFC4, 0xFFEFD68A, 0xFFD8B48E, CREAM, 0xFFB4DCCB, 0xFFEFBD84, SNOW, 0xFFB4DCCB, GOLD_L, 0xFFC3B0DE, PALE};

    private int sceneNow() {
        if (screen == Screen.MENU || screen == Screen.JUKEBOX || (screen == Screen.MAP && voyageDoneShown)) return Art.DEEP;
        return Planets.get(game.planet()).scene;
    }

    private void resetMote(Mote m, int scene, boolean anywhere) {
        m.x = rnd.nextFloat() * W;
        m.y = anywhere ? rnd.nextFloat() * H : (scene == Art.ICE ? -2 : H + 2);
        m.ph = rnd.nextFloat() * 6.28f;
        float s = H * .03f;
        switch (scene) {
            case Art.DUNES: m.vx = W * (.25f + rnd.nextFloat() * .3f); m.vy = -s * .2f; break;
            case Art.ICE: m.vx = W * .02f; m.vy = s * (1 + rnd.nextFloat()); break;
            case Art.CLOUDS: m.vx = -W * .03f; m.vy = 0; break;
            case Art.SWAMP: case Art.CRYSTAL: case Art.STATION: case Art.CORE: case Art.DEEP:
                m.vx = (rnd.nextFloat() - .5f) * W * .02f; m.vy = (rnd.nextFloat() - .5f) * s * .5f; break;
            default: m.vx = (rnd.nextFloat() - .5f) * W * .02f; m.vy = -s * (.5f + rnd.nextFloat());
        }
    }

    private void updateMotes(float dt) {
        int scene = sceneNow();
        if (scene != moteScene) {
            moteScene = scene;
            for (Mote m : motes) resetMote(m, scene, true);
        }
        for (Mote m : motes) {
            m.x += m.vx * dt + (float) Math.sin(time * 1.3f + m.ph) * W * .01f * dt;
            m.y += m.vy * dt;
            if (m.x < -4 || m.x > W + 4 || m.y < -4 || m.y > H + 4) {
                resetMote(m, scene, false);
                if (scene == Art.DUNES) { m.x = -2; m.y = rnd.nextFloat() * H; }
                else if (scene == Art.CLOUDS) { m.x = W + 2; m.y = rnd.nextFloat() * H; }
                else if (Math.abs(m.vy) < H * .01f) m.y = rnd.nextFloat() * H;
            }
        }
    }

    // ================================================================ pixel drawing helpers

    private void rect(Canvas c, int x, int y, int w, int h, int col) {
        paint.setColor(col);
        c.drawRect(x, y, x + w, y + h, paint);
    }

    /** A box with cut corners, a 1px border and an optional highlight along the top inside edge. */
    private void box(Canvas c, int x, int y, int w, int h, int fill, int border, int hi) {
        rect(c, x + 1, y, w - 2, h, border);
        rect(c, x, y + 1, w, h - 2, border);
        rect(c, x + 1, y + 1, w - 2, h - 2, fill);
        if (hi != 0) rect(c, x + 2, y + 1, w - 4, 1, hi);
    }

    private void dim(Canvas c, int alpha) {
        paint.setColor(INK0);
        paint.setAlpha(alpha);
        c.drawRect(0, 0, W, H, paint);
        paint.setAlpha(255);
    }

    private void font(Typeface tf, int size) {
        tp.setTypeface(tf);
        tp.setTextSize(size);
    }

    private int textW(String s) {
        return Math.round(tp.measureText(s));
    }

    /** Draws text on whole pixels, with a one-pixel drop shadow. align: -1 left, 0 centre, 1 right. */
    private void text(Canvas c, String s, float x, float y, Typeface tf, int size, int col, int align, int shadow) {
        font(tf, size);
        int w = textW(s);
        int xi = Math.round(align == 0 ? x - w / 2f : align > 0 ? x - w : x), yi = Math.round(y);
        tp.setTextAlign(Paint.Align.LEFT);
        if (shadow != 0) {
            tp.setColor(shadow);
            c.drawText(s, xi, yi + 1, tp);
        }
        tp.setColor(col);
        c.drawText(s, xi, yi, tp);
    }

    /** The bigger of two font sizes if the text fits the width, else the smaller. */
    private int fit(String s, Typeface tf, int big, int small, int width) {
        font(tf, big);
        return textW(s) <= width ? big : small;
    }

    private int capHeight(Typeface tf, int size) {
        font(tf, size);
        tp.getTextBounds("H", 0, 1, bounds);
        return bounds.height();
    }

    private List<String> wrap(String s, Typeface tf, int size, int width) {
        String key = size + "|" + width + "|" + s;
        List<String> lines = wraps.get(key);
        if (lines != null) return lines;
        font(tf, size);
        lines = new ArrayList<>();
        for (String para : s.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String tryLine = line.length() == 0 ? word : line + " " + word;
                if (textW(tryLine) > width && line.length() > 0) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(tryLine);
                }
            }
            lines.add(line.toString());
        }
        wraps.put(key, lines);
        return lines;
    }

    private int paragraph(Canvas c, String s, int x, int y, int width, int col, int shadow) {
        for (String l : wrap(s, fBody, BODY, width)) {
            text(c, l, x, y + 7, fBody, BODY, col, -1, shadow);
            y += 10;
        }
        return y;
    }

    private void addBtn(Canvas c, float cx, float cy, int w, int h, String label, boolean primary, Runnable r) {
        int x = Math.round(cx - w / 2f), y = Math.round(cy - h / 2f);
        Btn b = new Btn(new RectF(x, y, x + w, y + h), label, r);
        btns.add(b);
        boolean down = pressed != null && pressed.label.equals(label);
        if (!down) rect(c, x + 1, y + h, w - 2, 1, INK0);
        if (down) y += 1;
        if (primary) box(c, x, y, w, h, GOLD, GOLD_D, GOLD_L);
        else box(c, x, y, w, h, INK3, INK0, INK4);
        // big buttons: Jersey 20; small ones: Tiny5, which is crisp at 8 px (Jersey 10 is not)
        Typeface tf = h >= 24 && fit(label, fUi, UI2, 0, w - 8) == UI2 ? fUi : fBody;
        int size = tf == fUi ? UI2 : BODY;
        int ch = capHeight(tf, size);
        text(c, label, x + w / 2f, y + (h + ch) / 2f, tf, size, primary ? INK1 : CREAM, 0, primary ? GOLD_L : INK0);
    }

    // ================================================================ drawing

    private void render(Canvas c) {
        btns.clear();
        switch (screen) {
            case MENU: drawMenu(c); break;
            case JUKEBOX: drawJukebox(c); break;
            case MAP: drawMap(c); break;
            default: drawGame(c);
        }
    }

    private void drawBg(Canvas c, int planet, int dimAlpha) {
        c.drawBitmap(background(planet), 0, 0, null);
        if (dimAlpha > 0) dim(c, dimAlpha);
        int col = MOTE_COL[Math.max(0, Math.min(MOTE_COL.length - 1, moteScene))];
        for (Mote m : motes) {
            if (Math.sin(time * 3 + m.ph) < -.3) continue;
            rect(c, (int) m.x, (int) m.y, 1, 1, col);
        }
    }

    private void drawGame(Canvas c) {
        int planet = game.planet();
        drawBg(c, planet, 0);
        drawHud(c, planet);

        c.save();
        if (shake > 0) c.translate(rnd.nextInt(3) - 1, rnd.nextInt(3) - 1);

        // board
        box(c, bx - 4, by - 4, bsize + 8, bsize + 8, INK1, INK0, 0);
        rect(c, bx - 3, by - 3, bsize + 6, 1, accent(planet));
        for (int r = 0; r < Game.N; r++)
            for (int col = 0; col < Game.N; col++)
                rect(c, bx + col * cs, by + r * cs, cs, cs, ((r + col) & 1) == 0 ? INK2 : INK1);

        c.save();
        c.clipRect(bx, by, bx + bsize, by + bsize);
        if (game.phase != Game.Phase.TRAVEL)
            for (int r = 0; r < Game.N; r++)
                for (int col = 0; col < Game.N; col++) {
                    Gem m = game.g[r][col];
                    if (m != null) drawGem(c, m, r == game.selR && col == game.selC);
                }
        if (game.hint != null && game.phase == Game.Phase.IDLE && (int) (time * 4) % 2 == 0)
            brackets(c, game.hint[0], game.hint[1], CREAM);
        if (game.selR >= 0) brackets(c, game.selR, game.selC, GOLD_L);
        c.restore();

        for (Fx f : fxs) {
            float k = f.t / f.max;
            paint.setStyle(Paint.Style.STROKE);
            if (f.ring) {
                paint.setStrokeWidth(2);
                paint.setColor(Palette.GEM[5][3]);
                c.drawCircle(Math.round(f.x1), Math.round(f.y1), Math.round(cs * (1.6f - 1.2f * k)), paint);
            } else {
                drawBolt(c, f.x1, f.y1, f.x2, f.y2);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        for (Part p : parts) {
            if (p.life < p.max * .3f && ((int) (p.life * 30) & 1) == 0) continue; // flicker out
            rect(c, (int) p.x, (int) p.y, p.size, p.size, p.color);
        }
        c.restore();

        for (Pop p : pops) {
            if (p.t < .3f && ((int) (p.t * 30) & 1) == 0) continue;
            text(c, p.s, p.x, p.y, fBody, BODY, GOLD_L, 0, INK0);
        }

        if (screen == Screen.GAME) {
            int bw = 76;
            addBtn(c, bx + bw / 2f, barY, bw, barH, "Menu", false, () -> { if (canPause()) screen = Screen.PAUSE; });
            addBtn(c, bx + bsize - bw / 2f, barY, bw, barH, "Hint", false, game::showHint);
        }

        if (banner != null && bannerT > 0) {
            float k = bannerT / bannerMax;
            if (k < .9f || ((int) (time * 20) & 1) == 0) {
                int y = by + bsize / 2;
                paint.setColor(INK0);
                paint.setAlpha(210);
                c.drawRect(0, y - 18, W, y + 16, paint);
                paint.setAlpha(255);
                rect(c, 0, y - 19, W, 1, GOLD_D);
                rect(c, 0, y + 16, W, 1, GOLD_D);
                int size = fit(banner, fTitle, TITLE, TITLE / 2, W - 16);
                text(c, banner, W / 2f, y + capHeight(fTitle, size) / 2f, fTitle, size, GOLD_L, 0, INK0);
            }
        }

        if (screen == Screen.PAUSE) drawPause(c);
        else if (screen == Screen.OVER) drawOver(c);
    }

    private void brackets(Canvas c, int r, int col, int color) {
        int x = bx + col * cs, y = by + r * cs, l = Math.max(3, cs / 5), e = cs - 1;
        rect(c, x, y, l, 1, color); rect(c, x, y, 1, l, color);
        rect(c, x + e - l + 1, y, l, 1, color); rect(c, x + e, y, 1, l, color);
        rect(c, x, y + e, l, 1, color); rect(c, x, y + e - l + 1, 1, l, color);
        rect(c, x + e - l + 1, y + e, l, 1, color); rect(c, x + e, y + e - l + 1, 1, l, color);
    }

    private void drawGem(Canvas c, Gem m, boolean selected) {
        int x = bx + Math.round(m.x * cs), y = by + Math.round(m.y * cs);
        if (selected && Math.sin(time * 9) > 0) y -= 1;
        if (m.dying && m.alpha < 1 && ((int) (time * 40) & 1) == 0) return; // pixel-style flicker as it pops
        if (m.t == Game.HYPER) {
            c.drawBitmap(hyper[(int) (time * 8) % hyper.length], x, y, null);
        } else {
            if (m.sp == Game.FLAME) c.drawBitmap(auras[m.t][(int) (time * 10) % 2], x, y, null);
            c.drawBitmap(sprites[m.t], x, y, null);
        }
        if (m.flash > 0) {
            int cxp = x + cs / 2, cyp = y + cs / 2, len = Math.round(cs * .2f + cs * .3f * (1 - m.flash));
            rect(c, cxp - len, cyp, len * 2 + 1, 1, SNOW);
            rect(c, cxp, cyp - len, 1, len * 2 + 1, SNOW);
        }
        if (!m.dying && Math.round(m.y) == twinkleR && Math.round(m.x) == twinkleC && twinkleT > .2f && m.t != Game.HYPER) {
            int sx = x + cs / 3, sy = y + cs / 3;
            rect(c, sx - 1, sy, 3, 1, SNOW);
            rect(c, sx, sy - 1, 1, 3, SNOW);
        }
    }

    private void drawBolt(Canvas c, float x1, float y1, float x2, float y2) {
        int seg = 6;
        float px = x1, py = y1;
        float nx = -(y2 - y1), ny = x2 - x1, len = (float) Math.hypot(nx, ny);
        if (len < 1) return;
        nx /= len; ny /= len;
        paint.setStrokeWidth(1);
        for (int i = 1; i <= seg; i++) {
            float t = i / (float) seg, j = i == seg ? 0 : (rnd.nextFloat() - .5f) * cs * .5f;
            float qx = x1 + (x2 - x1) * t + nx * j, qy = y1 + (y2 - y1) * t + ny * j;
            paint.setColor(Palette.GEM[6][3]);
            c.drawLine(Math.round(px) + 1, Math.round(py), Math.round(qx) + 1, Math.round(qy), paint);
            paint.setColor(SNOW);
            c.drawLine(Math.round(px), Math.round(py), Math.round(qx), Math.round(qy), paint);
            px = qx; py = qy;
        }
    }

    // ---------------------------------------------------------------- dial

    /** The voyage dial: twelve wedges, one per planet; {@code done} are full, the next is filled by {@code frac}. */
    private void drawDial(Canvas c, int x, int y, int R, int done, float frac, int voyage, boolean big) {
        paint.setColor(INK0);
        c.drawCircle(x, y, R + 1, paint);
        paint.setColor(GOLD_D);
        c.drawCircle(x, y, R, paint);
        paint.setColor(INK1);
        c.drawCircle(x, y, R - 2, paint);
        float ri = R * .36f, ro = R - 3;
        RectF outer = new RectF(x - ro, y - ro, x + ro, y + ro), inner = new RectF(x - ri, y - ri, x + ri, y + ri);
        for (int i = 0; i < Planets.COUNT; i++) {
            float a0 = -90 + i * 30 + (big ? 2.5f : 4f), sw = 30 - (big ? 5f : 8f);
            wedge(outer, inner, a0, sw);
            paint.setColor(i == done ? INK4 : INK3);
            c.drawPath(path, paint);
            float f = i < done ? 1 : i == done ? frac : 0;
            if (f > 0) {
                wedge(outer, inner, a0, sw * Math.min(1, f));
                paint.setColor(accent(i));
                c.drawPath(path, paint);
            }
            if (i == done && frac < 1 && (int) (time * 3) % 2 == 0) {
                wedge(outer, inner, a0, sw);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1);
                paint.setColor(CREAM);
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        paint.setColor(INK0);
        c.drawCircle(x, y, ri, paint);
        paint.setColor(INK1);
        c.drawCircle(x, y, ri - 1, paint);
        String v = roman(voyage);
        if (big) text(c, v, x, y + capHeight(fUi, UI2) / 2f, fUi, UI2, GOLD_L, 0, INK0);
        else text(c, v, x, y + 3, fBody, BODY, GOLD_L, 0, 0);
    }

    private void wedge(RectF outer, RectF inner, float a0, float sw) {
        path.reset();
        path.arcTo(outer, a0, sw, true);
        path.arcTo(inner, a0 + sw, -sw);
        path.close();
    }

    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 1 && n <= 10 ? r[n] : String.valueOf(n);
    }

    private void drawHud(Canvas c, int planet) {
        Planets.P pl = Planets.get(planet);
        box(c, bx - 4, hudTop, bsize + 8, hudH, INK1, INK0, INK2);
        int R = hudH / 2 - 3, dx = bx + R + 1, dy = hudTop + hudH / 2;
        float frac = Math.min(1f, game.levelScore / (float) game.target());
        drawDial(c, dx, dy, R, planet, frac, game.voyage(), false);

        int x0 = dx + R + 8, x1 = bx + bsize - 1;
        text(c, "BEST " + Math.max(bestNow(), game.score), x1, hudTop + 11, fBody, BODY, MUTED, 1, 0);
        text(c, (planet + 1) + "/12 " + pl.name.toUpperCase(), x0, hudTop + 11, fBody, BODY, accent(planet), -1, INK0);
        text(c, String.valueOf(Math.round(shownScore)), x0, hudTop + 29, fUi, UI2, CREAM, -1, INK0);

        // deal bar
        int y = hudTop + 32, h = 6, w = x1 - x0;
        rect(c, x0, y, w, h, INK0);
        rect(c, x0 + 1, y + 1, w - 2, h - 2, INK2);
        int fw = Math.round((w - 2) * frac);
        if (fw > 0) {
            rect(c, x0 + 1, y + 1, fw, h - 2, accent(planet));
            rect(c, x0 + 1, y + 1, fw, 1, Palette.nearest(Palette.mix(pl.accent, SNOW, .4f)));
        }
        for (int t = 1; t < 10; t++) rect(c, x0 + 1 + (w - 2) * t / 10, y + h - 2, 1, 1, INK1);
        text(c, "DEAL", x0, y + h + 7, fBody, BODY, MUTED, -1, 0);
        text(c, Math.min(game.levelScore, game.target()) + " / " + game.target(), x1, y + h + 7, fBody, BODY, PALE, 1, 0);
    }

    // ---------------------------------------------------------------- overlays

    private int panel(Canvas c, int h) {
        dim(c, 170);
        int w = Math.min(W - 24, 220), x = (W - w) / 2, y = (H - h) / 2;
        box(c, x, y, w, h, INK2, INK0, INK3);
        rect(c, x + 3, y + 3, w - 6, 1, GOLD_D);
        rect(c, x + 3, y + h - 4, w - 6, 1, GOLD_D);
        return w;
    }

    private void drawPause(Canvas c) {
        int bh = 24, gap = 30, h = 5 * gap + 50;
        int w = panel(c, h), y = (H - h) / 2, bw = w - 40;
        text(c, "Paused", W / 2f, y + 30, fTitle, TITLE, GOLD_L, 0, INK0);
        y += 54;
        addBtn(c, W / 2f, y, bw, bh, "Resume", true, () -> screen = Screen.GAME); y += gap;
        addBtn(c, W / 2f, y, bw, bh, "Music " + (musicOn ? "on" : "off"), false, this::toggleMusic); y += gap;
        addBtn(c, W / 2f, y, bw, bh, "Sound " + (audio.sfxOn ? "on" : "off"), false, this::toggleSfx); y += gap;
        if (game.endless) addBtn(c, W / 2f, y, bw, bh, "Restart endless", false, this::newEndless);
        else addBtn(c, W / 2f, y, bw, bh, "New voyage", false, this::newVoyage);
        y += gap;
        addBtn(c, W / 2f, y, bw, bh, "Main menu", false, this::toMenu);
    }

    private void drawOver(Canvas c) {
        int h = 170;
        int w = panel(c, h), y = (H - h) / 2, bw = w - 40;
        String title = "The deal collapses";
        int ts = fit(title, fTitle, TITLE, TITLE / 2, w - 12);
        text(c, title, W / 2f, y + 30, fTitle, ts, GOLD_L, 0, INK0);
        Planets.P pl = Planets.get(game.planet());
        text(c, "Stranded at " + pl.name + ", voyage " + roman(game.voyage()), W / 2f, y + 50, fBody, BODY, PALE, 0, INK0);
        text(c, "Fortune " + game.score, W / 2f, y + 72, fUi, UI2, CREAM, 0, INK0);
        text(c, newBest ? "Your richest voyage yet!" : "Best " + best, W / 2f, y + 88, fBody, BODY, newBest ? GOLD_L : MUTED, 0, INK0);
        addBtn(c, W / 2f, y + 112, bw, 24, "New voyage", true, this::newVoyage);
        addBtn(c, W / 2f, y + 142, bw, 24, "Main menu", false, this::toMenu);
    }

    // ---------------------------------------------------------------- voyage map

    private void drawMap(Canvas c) {
        int planet = game.planet();
        Planets.P pl = Planets.get(planet);
        drawBg(c, voyageDoneShown ? -1 : planet, 120);

        int y = top + 8;
        String head = voyageDoneShown ? "VOYAGE " + roman(game.voyage() - 1) + " COMPLETE"
                : (game.endless ? "ENDLESS   CIRCUIT " : "VOYAGE ") + roman(game.voyage()) + "   PORT " + (planet + 1) + " OF 12";
        text(c, head, W / 2f, y, fBody, BODY, PALE, 0, INK0);

        int R = Math.max(30, Math.min(W / 4, (H - 330) / 2)), dcx = W / 2, dcy = y + 12 + R;
        if (voyageDoneShown) {
            drawDial(c, dcx, dcy, R, Planets.COUNT, 0, game.voyage() - 1, true);
            if ((int) (time * 2) % 2 == 0)
                for (int i = 0; i < 12; i++) {
                    double a = Math.toRadians(i * 30 + time * 20);
                    rect(c, dcx + (int) (Math.cos(a) * (R + 6)), dcy + (int) (Math.sin(a) * (R + 6)), 1, 1, GOLD_L);
                }
        } else {
            drawDial(c, dcx, dcy, R, planet, 0, game.voyage(), true);
            // the ship travels around the rim from the last port to this one
            float k = Math.min(1f, mapT / 2.2f);
            k = k * k * (3 - 2 * k);
            float from = planet == 0 ? -90 - 30 : -90 + (planet - 1) * 30 + 15, to = -90 + planet * 30 + 15;
            double a = Math.toRadians(from + (to - from) * k);
            drawShip(c, dcx + (int) Math.round(Math.cos(a) * (R + 7)), dcy + (int) Math.round(Math.sin(a) * (R + 7)), a + Math.PI / 2);
        }

        int ty = dcy + R + 14;
        int bh = 28;
        if (voyageDoneShown) {
            paint.setColor(INK1);
            paint.setAlpha(215);
            c.drawRect(0, ty - 4, W, H - bottom - bh * 2, paint);
            paint.setAlpha(255);
            text(c, "Holds full", W / 2f, ty + 20, fTitle, TITLE, GOLD_L, 0, INK0);
            paragraph(c, "Twelve worlds, twelve deals. Your holds are full of water-glass and singing crystal, mire-pearls and starlight silk, and somewhere at the bottom, the last jewel.\n\nWord travels fast between the stars. On the next voyage the traders will know your name, and they will drive harder bargains.",
                    16, ty + 34, W - 32, CREAM, INK0);
            addBtn(c, W / 2f, H - bottom - bh, W - 60, bh, "Begin voyage " + roman(game.voyage()), true, () -> showMap(false));
            return;
        }
        int by2 = H - bottom - bh / 2 - 26;
        int introW = W - 32, introLines = wrap(pl.intro, fBody, BODY, introW).size();
        int panelEnd = Math.min(by2 - bh / 2 - 20, ty + 40 + introLines * 10 + 8);
        paint.setColor(INK1);
        paint.setAlpha(215);
        c.drawRect(0, ty - 4, W, panelEnd, paint);
        rect(c, 0, panelEnd, W, 1, GOLD_D);
        paint.setAlpha(255);
        rect(c, 0, ty - 5, W, 1, GOLD_D);
        int ns = fit(pl.name, fTitle, TITLE, TITLE / 2, W - 12);
        text(c, pl.name, W / 2f, ty + 18, fTitle, ns, GOLD_L, 0, INK0);
        ty += 30;
        text(c, "Trading in " + pl.goods, W / 2f, ty, fBody, BODY, accent(planet), 0, INK0);
        ty += 10;
        paragraph(c, pl.intro, 16, ty, introW, CREAM, INK0);

        text(c, "To close the deal: " + game.target(), W / 2f, by2 - bh / 2f - 8, fBody, BODY, PALE, 0, INK0);
        addBtn(c, W / 2f, by2, W - 60, bh, "Land on " + pl.name, true, this::land);
        addBtn(c, W / 2f, by2 + bh / 2f + 14, 90, 20, "Menu", false, this::toMenu);
    }

    private void drawShip(Canvas c, int x, int y, double heading) {
        // a tiny ship, turned in 45° steps so it stays crisp
        int dir = ((int) Math.round(heading / (Math.PI / 4)) % 8 + 8) % 8;
        int[][] shape = {{0, -3}, {0, -2}, {-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {0, 0}, {1, 0}, {-2, 1}, {-1, 1}, {1, 1}, {2, 1}, {-2, 2}, {2, 2}};
        double ang = dir * Math.PI / 4;
        for (int[] p : shape) {
            int rx = (int) Math.round(p[0] * Math.cos(ang) - p[1] * Math.sin(ang));
            int ry = (int) Math.round(p[0] * Math.sin(ang) + p[1] * Math.cos(ang));
            rect(c, x + rx, y + ry, 1, 1, p[1] < 0 ? GOLD_L : GOLD);
        }
        int ex = x - (int) Math.round(-3 * Math.sin(ang)), ey = y + (int) Math.round(3 * Math.cos(ang));
        if ((int) (time * 12) % 2 == 0) rect(c, ex, ey, 1, 1, Palette.GEM[5][3]);
    }

    // ---------------------------------------------------------------- menu & music room

    private void drawMenu(Canvas c) {
        drawBg(c, -1, 0);
        int ocy = top + Math.max(70, H / 6);
        // gems orbit the title: the far half is drawn behind it
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 7; i++) {
                double a = time * .4 + i * Math.PI * 2 / 7;
                if (pass == 1) continue;
                int x = W / 2 + (int) Math.round(Math.cos(a) * (W * .44f)) - cs / 2;
                int y = ocy + 8 + (int) Math.round(Math.sin(a) * 46) - cs / 2;
                c.drawBitmap(sprites[i], x, y, null);
            }
            if (pass == 0) {
                text(c, "Jewel", W / 2f, ocy + 4, fTitle, TITLE2, GOLD_L, 0, INK0);
                text(c, "Merchant", W / 2f, ocy + 30, fTitle, TITLE, CREAM, 0, INK0);
            }
        }
        text(c, "a game for the long roads between stars", W / 2f, ocy + 80, fBody, BODY, PALE, 0, INK0);

        int bh = 28, bw = Math.min(W - 60, 200), gap = 36;
        int y = ocy + 114;
        String save = prefs.getString(saveKey(false), null), saveE = prefs.getString(saveKey(true), null);
        if (save != null) {
            addBtn(c, W / 2f, y, bw, bh, "Continue" + savedAt(save), true, () -> continueGame(false));
            y += gap;
        }
        addBtn(c, W / 2f, y, bw, bh, "New voyage", save == null, this::newVoyage); y += gap;
        addBtn(c, W / 2f, y, bw, bh, saveE != null ? "Endless" + savedAt(saveE) : "Endless", false, () -> continueGame(true)); y += gap;
        addBtn(c, W / 2f, y, bw, bh, "Music room", false, () -> screen = Screen.JUKEBOX); y += gap;
        addBtn(c, W / 2f - bw / 4f - 2, y, bw / 2 - 4, bh, "Music " + (musicOn ? "on" : "off"), false, this::toggleMusic);
        addBtn(c, W / 2f + bw / 4f + 2, y, bw / 2 - 4, bh, "Sound " + (audio.sfxOn ? "on" : "off"), false, this::toggleSfx);
        y += gap;
        String records = (best > 0 ? "Richest voyage " + best : "") + (best > 0 && bestEndless > 0 ? "     " : "")
                + (bestEndless > 0 ? "Endless " + bestEndless : "");
        if (!records.isEmpty()) text(c, records, W / 2f, y + 2, fBody, BODY, PALE, 0, INK0);

        int b = H - bottom;
        text(c, "Music: Beyond the Network, by Peter Hajba (Skaven)", W / 2f, b - 12, fBody, BODY, MUTED, 0, INK0);
        text(c, "from Bejeweled 2. CC BY-NC-ND 4.0, via The Mod Archive.", W / 2f, b - 2, fBody, BODY, MUTED, 0, INK0);
    }

    private void drawJukebox(Canvas c) {
        drawBg(c, -1, 80);
        int y = top + 22;
        text(c, "Music Room", W / 2f, y, fTitle, TITLE, GOLD_L, 0, INK0);
        text(c, "Peter Hajba - Beyond the Network", W / 2f, y + 14, fBody, BODY, PALE, 0, INK0);
        int[] pos = audio.position();
        if (pos != null && audio.want() != null)
            text(c, String.format(java.util.Locale.ROOT, "ORDER %03d  PATTERN %03d  ROW %03d", pos[0], pos[1], pos[2]), W / 2f, y + 26, fBody, BODY, Palette.GEM[2][3], 0, INK0);
        y += 44;
        ArrayList<String[]> tracks = new ArrayList<>();
        tracks.add(new String[]{"menu", "Main theme"});
        tracks.add(new String[]{"depart", "Departure"});
        for (int i = 0; i < Planets.COUNT; i++)
            tracks.add(new String[]{String.format(java.util.Locale.ROOT, "planet%02d", i + 1), (i + 1) + ". " + Planets.get(i).name});
        tracks.add(new String[]{"ready", "Get ready"});
        tracks.add(new String[]{"clear", "Deal struck"});
        tracks.add(new String[]{"loading", "Loading"});
        tracks.add(new String[]{"finale", "Final destination"});
        String now = audio.want();
        int colW = (W - 24) / 2, bh = 20, gap = 24;
        for (int i = 0; i < tracks.size(); i++) {
            final String id = tracks.get(i)[0];
            float x = W / 2f + (i % 2 == 0 ? -1 : 1) * (colW / 2f + 2);
            addBtn(c, x, y + (i / 2) * gap, colW, bh, (id.equals(now) ? "> " : "") + tracks.get(i)[1], id.equals(now), () -> {
                if (!musicOn) toggleMusic();
                audio.music(id, null);
            });
        }
        y += (tracks.size() / 2) * gap + 4;
        addBtn(c, W / 2f - colW / 2f - 2, y, colW, bh, "Stop", false, () -> audio.music(null, null));
        addBtn(c, W / 2f + colW / 2f + 2, y, colW, bh, "Back", false, this::toMenu);
    }
}
