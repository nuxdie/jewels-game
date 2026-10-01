package com.local.jewels;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

final class Gem {
    private static int ids;
    final int id = ids++;
    int t, sp, hyperColor = -1;
    float x, y, vy, scale = 1f, alpha = 1f, flash;
    boolean dying;

    Gem(int t, int col, float y) {
        this.t = t;
        this.x = col;
        this.y = y;
    }
}

/** Board rules and a time-driven state machine. Runs entirely on the game thread. */
final class Game {
    static final int N = 8, COLORS = 7, HYPER = 7, FLAME = 1;
    private static final float SWAP_T = .18f, CLEAR_T = .25f, GRAVITY = 55f;

    enum Phase { IDLE, SWAP, SWAP_BACK, CLEAR, FALL, LEVEL_CLEAR, DROP_OUT, TRAVEL, NO_MOVES, OVER }

    interface Listener {
        void sfx(String name, float vol);
        void music(String name, String then);
        void burst(float r, float c, int type);
        void explosion(int r, int c);
        void zap(int r1, int c1, int r2, int c2);
        void popup(float r, float c, String text);
        void banner(String text, float secs);
        void save();
        void gameOver();
        /** A planet's deal is done; the next board is ready and waits for {@link #land()}. */
        void stageComplete(boolean voyageDone);
    }

    final Gem[][] g = new Gem[N][N];
    Phase phase = Phase.OVER;
    int score, level = 1, levelScore, cascade;
    int selR = -1, selC = -1;
    int[] hint;
    private float timer, idle, landCooldown;
    private int saR, saC, sbR, sbC;
    private boolean shuffling;
    /** Endless mode: no losing (a dead board is reshuffled), planets go round in circuits. */
    boolean endless;
    private final Random rnd = new Random();
    private final Listener L;
    private final List<Gem> dying = new ArrayList<>();
    private final Map<Integer, int[]> creates = new HashMap<>();

    Game(Listener l) {
        L = l;
    }

    /** 0..11: which planet of the voyage. */
    int planet() {
        return (level - 1) % Planets.COUNT;
    }

    int voyage() {
        return (level - 1) / Planets.COUNT + 1;
    }

    String theme() {
        return String.format(java.util.Locale.ROOT, "planet%02d", planet() + 1);
    }

    int target() {
        int base = 750 + 500 * planet();
        float growth = endless ? .2f : .6f; // per circuit / per voyage
        return Math.round(base * (1f + growth * (voyage() - 1)) / 50f) * 50;
    }

    void land() {
        if (phase == Phase.TRAVEL) startDrop();
    }

    // ---------------------------------------------------------------- setup

    /** Fresh voyage; the first board waits in TRAVEL until {@link #land()}. */
    void newGame() {
        score = 0;
        level = 1;
        levelScore = 0;
        fillNew();
        phase = Phase.TRAVEL;
    }

    private void startDrop() {
        selR = -1;
        hint = null;
        cascade = 0;
        phase = Phase.FALL;
        L.sfx("start", 1f);
        L.music("ready", theme());
    }

    private void fillNew() {
        int[][] t = new int[N][N];
        do {
            for (int r = 0; r < N; r++)
                for (int c = 0; c < N; c++) {
                    int v;
                    do v = rnd.nextInt(COLORS);
                    while ((c >= 2 && t[r][c - 1] == v && t[r][c - 2] == v) || (r >= 2 && t[r - 1][c] == v && t[r - 2][c] == v));
                    t[r][c] = v;
                }
            for (int r = 0; r < N; r++)
                for (int c = 0; c < N; c++) g[r][c] = new Gem(t[r][c], c, 0);
        } while (findMove() == null);
        placeAbove();
    }

    /** Put every gem above the board so the board drops in. */
    private void placeAbove() {
        for (int r = 0; r < N; r++)
            for (int c = 0; c < N; c++) {
                Gem m = g[r][c];
                m.x = c;
                m.y = r - N - 1 - c * .35f;
                m.vy = 0;
                m.scale = m.alpha = 1f;
            }
    }

    String serialize() {
        StringBuilder sb = new StringBuilder("v2;").append(endless ? 1 : 0).append(';').append(score).append(';').append(level).append(';').append(levelScore).append(';');
        for (int r = 0; r < N; r++)
            for (int c = 0; c < N; c++) sb.append(g[r][c].t * 10 + g[r][c].sp).append(',');
        return sb.toString();
    }

    boolean restore(String s) {
        try {
            String[] p = s.split(";");
            int o; // v1 saves predate endless mode
            if (p[0].equals("v1")) o = 0;
            else if (p[0].equals("v2")) o = 1;
            else return false;
            String[] cells = p[4 + o].split(",");
            if (cells.length != N * N) return false;
            endless = o == 1 && p[1].equals("1");
            score = Integer.parseInt(p[1 + o]);
            level = Integer.parseInt(p[2 + o]);
            levelScore = Integer.parseInt(p[3 + o]);
            for (int i = 0; i < N * N; i++) {
                int v = Integer.parseInt(cells[i]);
                Gem m = new Gem(v / 10, i % N, 0);
                m.sp = v % 10;
                g[i / N][i % N] = m;
            }
            placeAbove();
            startDrop();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- queries

    private int typeAt(int r, int c) {
        if (r < 0 || c < 0 || r >= N || c >= N) return -1;
        Gem m = g[r][c];
        return m == null || m.dying ? -1 : m.t;
    }

    private boolean hasMatchAt(int r, int c) {
        int t = typeAt(r, c);
        if (t < 0 || t == HYPER) return false;
        int h = 1, v = 1;
        for (int i = c - 1; typeAt(r, i) == t; i--) h++;
        for (int i = c + 1; typeAt(r, i) == t; i++) h++;
        for (int i = r - 1; typeAt(i, c) == t; i--) v++;
        for (int i = r + 1; typeAt(i, c) == t; i++) v++;
        return h >= 3 || v >= 3;
    }

    int[] findMove() {
        for (int r = 0; r < N; r++)
            for (int c = 0; c < N; c++)
                for (int d = 0; d < 2; d++) {
                    int r2 = r + d, c2 = c + 1 - d;
                    if (r2 >= N || c2 >= N) continue;
                    Gem a = g[r][c], b = g[r2][c2];
                    if (a == null || b == null) continue;
                    if (a.t == HYPER || b.t == HYPER) return new int[]{r, c, r2, c2};
                    g[r][c] = b;
                    g[r2][c2] = a;
                    boolean m = hasMatchAt(r, c) || hasMatchAt(r2, c2);
                    g[r][c] = a;
                    g[r2][c2] = b;
                    if (m) return new int[]{r, c, r2, c2};
                }
        return null;
    }

    private static final class Group {
        final Set<Integer> cells = new LinkedHashSet<>();
        int t, maxRun, runs;
    }

    private List<Group> findGroups() {
        List<int[]> runs = new ArrayList<>(); // r, c, dr, dc, len
        for (int r = 0; r < N; r++)
            for (int c = 0; c < N; ) {
                int t = typeAt(r, c), e = c + 1;
                while (e < N && typeAt(r, e) == t) e++;
                if (t >= 0 && t != HYPER && e - c >= 3) runs.add(new int[]{r, c, 0, 1, e - c});
                c = e;
            }
        for (int c = 0; c < N; c++)
            for (int r = 0; r < N; ) {
                int t = typeAt(r, c), e = r + 1;
                while (e < N && typeAt(e, c) == t) e++;
                if (t >= 0 && t != HYPER && e - r >= 3) runs.add(new int[]{r, c, 1, 0, e - r});
                r = e;
            }
        List<Group> groups = new ArrayList<>();
        Group[] owner = new Group[N * N];
        for (int[] run : runs) {
            Group into = null;
            for (int i = 0; i < run[4]; i++) {
                Group o = owner[(run[0] + run[2] * i) * N + run[1] + run[3] * i];
                if (o == null || o == into) continue;
                if (into == null) { into = o; continue; }
                // merge o into 'into'
                for (int k : o.cells) owner[k] = into;
                into.cells.addAll(o.cells);
                into.runs += o.runs;
                into.maxRun = Math.max(into.maxRun, o.maxRun);
                groups.remove(o);
            }
            if (into == null) {
                into = new Group();
                into.t = typeAt(run[0], run[1]);
                groups.add(into);
            }
            for (int i = 0; i < run[4]; i++) {
                int k = (run[0] + run[2] * i) * N + run[1] + run[3] * i;
                into.cells.add(k);
                owner[k] = into;
            }
            into.runs++;
            into.maxRun = Math.max(into.maxRun, run[4]);
        }
        return groups;
    }

    private boolean plain(int k) {
        Gem m = g[k / N][k % N];
        return m != null && m.sp == 0 && m.t != HYPER;
    }

    private int mostCommonColor() {
        int[] n = new int[COLORS];
        for (Gem[] row : g) for (Gem m : row) if (m != null && !m.dying && m.t < COLORS) n[m.t]++;
        int best = 0;
        for (int i = 1; i < COLORS; i++) if (n[i] > n[best]) best = i;
        return best;
    }

    // ---------------------------------------------------------------- input

    private static boolean adjacent(int r1, int c1, int r2, int c2) {
        return Math.abs(r1 - r2) + Math.abs(c1 - c2) == 1;
    }

    /** Tap on a cell: select it, or swap with the current selection if adjacent. Returns true if a swap started. */
    boolean tap(int r, int c) {
        if (phase != Phase.IDLE) return false;
        idle = 0;
        hint = null;
        if (selR >= 0 && adjacent(selR, selC, r, c)) {
            trySwap(selR, selC, r, c);
            return true;
        }
        if (selR != r || selC != c) L.sfx("select", 1f);
        selR = r;
        selC = c;
        return false;
    }

    void trySwap(int r1, int c1, int r2, int c2) {
        if (phase != Phase.IDLE || r2 < 0 || c2 < 0 || r2 >= N || c2 >= N || !adjacent(r1, c1, r2, c2)) return;
        selR = -1;
        hint = null;
        saR = r1; saC = c1; sbR = r2; sbC = c2;
        swapCells();
        phase = Phase.SWAP;
        timer = 0;
    }

    /** Debug: fill the deal bar and settle, as if the last move closed the deal. */
    void debugCompleteDeal() {
        if (phase != Phase.IDLE) return;
        levelScore = target();
        onSettled();
    }

    void showHint() {
        if (phase == Phase.IDLE) hint = findMove();
    }

    private void swapCells() {
        Gem t = g[saR][saC];
        g[saR][saC] = g[sbR][sbC];
        g[sbR][sbC] = t;
    }

    // ---------------------------------------------------------------- update

    private static float ease(float k) {
        return k < .5f ? 2 * k * k : 1 - (float) Math.pow(-2 * k + 2, 2) / 2;
    }

    void update(float dt) {
        landCooldown -= dt;
        for (Gem[] row : g) for (Gem m : row) if (m != null && m.flash > 0) m.flash = Math.max(0, m.flash - dt * 2.5f);
        switch (phase) {
            case IDLE:
                idle += dt;
                if (idle > 9 && hint == null) hint = findMove();
                break;
            case SWAP:
            case SWAP_BACK: {
                timer += dt;
                float k = ease(Math.min(1, timer / SWAP_T));
                Gem a = g[saR][saC], b = g[sbR][sbC]; // a came from b's cell, b from a's cell
                a.x = sbC + (saC - sbC) * k; a.y = sbR + (saR - sbR) * k;
                b.x = saC + (sbC - saC) * k; b.y = saR + (sbR - saR) * k;
                if (timer >= SWAP_T) {
                    if (phase == Phase.SWAP) afterSwap(); else { phase = Phase.IDLE; idle = 0; }
                }
                break;
            }
            case CLEAR: {
                timer += dt;
                float k = Math.min(1, timer / CLEAR_T);
                for (Gem m : dying) { m.scale = 1 + .35f * k; m.alpha = 1 - k; }
                if (timer >= CLEAR_T) finishClear();
                break;
            }
            case FALL:
                if (stepFall(dt)) startClear(null);
                break;
            case LEVEL_CLEAR:
                timer += dt;
                if (timer > 2.4f) { phase = Phase.DROP_OUT; timer = 0; }
                break;
            case DROP_OUT:
                timer += dt;
                for (Gem[] row : g)
                    for (Gem m : row) {
                        if (m == null || timer < (m.x * .05f + (N - m.y) * .02f)) continue;
                        m.vy += GRAVITY * dt;
                        m.y += m.vy * dt;
                    }
                if (timer > 1.3f && shuffling) {
                    // endless: a fresh board for the same deal
                    shuffling = false;
                    fillNew();
                    phase = Phase.FALL;
                    L.sfx("shuffle_in", 1f);
                } else if (timer > 1.3f) {
                    boolean voyageDone = !endless && planet() == Planets.COUNT - 1;
                    level++;
                    levelScore = 0;
                    fillNew();
                    phase = Phase.TRAVEL;
                    L.save();
                    L.stageComplete(voyageDone);
                }
                break;
            case TRAVEL:
                break;
            case NO_MOVES:
                timer += dt;
                if (timer > 2.6f) {
                    phase = Phase.OVER;
                    L.gameOver();
                }
                break;
            case OVER:
                break;
        }
    }

    private void afterSwap() {
        Gem a = g[saR][saC], b = g[sbR][sbC];
        if (a.t == HYPER || b.t == HYPER) {
            cascade = 1;
            Set<Integer> set = new LinkedHashSet<>();
            Gem h = a.t == HYPER ? a : b, o = h == a ? b : a;
            if (o.t == HYPER) {
                for (int k = 0; k < N * N; k++) set.add(k);
            } else {
                h.hyperColor = o.t;
                set.add(h == a ? saR * N + saC : sbR * N + sbC);
            }
            L.sfx("zap", 1f);
            beginClear(set, new HashMap<>());
        } else if (hasMatchAt(saR, saC) || hasMatchAt(sbR, sbC)) {
            cascade = 0;
            startClear(new int[][]{{saR, saC}, {sbR, sbC}});
        } else {
            L.sfx("bad", 1f);
            swapCells();
            phase = Phase.SWAP_BACK;
            timer = 0;
        }
    }

    private void startClear(int[][] pref) {
        List<Group> groups = findGroups();
        if (groups.isEmpty()) {
            onSettled();
            return;
        }
        cascade++;
        Map<Integer, int[]> protect = new HashMap<>();
        Set<Integer> set = new LinkedHashSet<>();
        for (Group gr : groups) {
            set.addAll(gr.cells);
            int kind = gr.maxRun >= 5 ? 2 : (gr.maxRun == 4 || gr.runs > 1) ? 1 : 0;
            if (kind == 0) continue;
            Integer cell = null;
            if (pref != null)
                for (int[] p : pref) {
                    int k = p[0] * N + p[1];
                    if (gr.cells.contains(k) && plain(k)) { cell = k; break; }
                }
            if (cell == null) {
                List<Integer> cand = new ArrayList<>();
                for (int k : gr.cells) if (plain(k)) cand.add(k);
                if (!cand.isEmpty()) cell = cand.get(cand.size() / 2);
            }
            if (cell != null) protect.put(cell, kind == 2 ? new int[]{HYPER, 0} : new int[]{gr.t, FLAME});
        }
        set.removeAll(protect.keySet());
        beginClear(set, protect);
    }

    /** Expand the set through special gems, then score it and animate it away. */
    private void beginClear(Set<Integer> set, Map<Integer, int[]> protect) {
        ArrayDeque<Integer> q = new ArrayDeque<>(set);
        Set<Integer> fired = new HashSet<>();
        int flames = 0, hypers = 0;
        while (!q.isEmpty()) {
            int k = q.poll();
            Gem m = g[k / N][k % N];
            if (m == null || fired.contains(k)) continue;
            if (m.sp == FLAME) {
                fired.add(k);
                flames++;
                L.explosion(k / N, k % N);
                for (int dr = -1; dr <= 1; dr++)
                    for (int dc = -1; dc <= 1; dc++) {
                        int r = k / N + dr, c = k % N + dc;
                        if (r < 0 || c < 0 || r >= N || c >= N) continue;
                        int kk = r * N + c;
                        if (g[r][c] != null && !protect.containsKey(kk) && set.add(kk)) q.add(kk);
                    }
            } else if (m.t == HYPER) {
                fired.add(k);
                hypers++;
                int col = m.hyperColor >= 0 ? m.hyperColor : mostCommonColor();
                for (int kk = 0; kk < N * N; kk++) {
                    Gem o = g[kk / N][kk % N];
                    if (o == null || o.t != col || protect.containsKey(kk)) continue;
                    L.zap(k / N, k % N, kk / N, kk % N);
                    if (set.add(kk)) q.add(kk);
                }
            }
        }

        int pts = set.size() * 10 * Math.max(1, cascade) + fired.size() * 50;
        score += pts;
        levelScore += pts;
        float cr = 0, cc = 0;
        for (int k : set) {
            Gem m = g[k / N][k % N];
            m.dying = true;
            dying.add(m);
            cr += k / N;
            cc += k % N;
            L.burst(k / N, k % N, m.t);
        }
        if (!set.isEmpty()) L.popup(cr / set.size(), cc / set.size(), "+" + pts);

        creates.clear();
        creates.putAll(protect);
        if (flames > 0) L.sfx("flame", 1f);
        if (hypers > 0) L.sfx("hyper_blast", 1f);
        boolean makesHyper = false;
        for (int[] v : protect.values()) if (v[0] == HYPER) makesHyper = true;
        if (makesHyper) L.sfx("hyper_make", 1f);
        if (cascade <= 1) L.sfx(protect.isEmpty() ? "match" : "match_big", 1f);
        else L.sfx("cascade" + Math.min(7, cascade), 1f);

        phase = Phase.CLEAR;
        timer = 0;
    }

    private void finishClear() {
        for (int r = 0; r < N; r++)
            for (int c = 0; c < N; c++)
                if (g[r][c] != null && g[r][c].dying) g[r][c] = null;
        dying.clear();
        for (Map.Entry<Integer, int[]> e : creates.entrySet()) {
            Gem m = g[e.getKey() / N][e.getKey() % N];
            if (m == null) continue;
            m.t = e.getValue()[0];
            m.sp = e.getValue()[1];
            m.flash = 1f;
        }
        creates.clear();
        startFall();
    }

    private void startFall() {
        List<Gem> fresh = new ArrayList<>();
        for (int c = 0; c < N; c++) {
            int w = N - 1;
            for (int r = N - 1; r >= 0; r--) {
                Gem m = g[r][c];
                if (m == null) continue;
                g[r][c] = null;
                g[w--][c] = m;
            }
            int missing = w + 1;
            for (int r = w; r >= 0; r--) {
                Gem n = new Gem(rnd.nextInt(COLORS), c, r - missing - .2f);
                g[r][c] = n;
                fresh.add(n);
            }
        }
        if (endless) keepMovesAlive(fresh);
        phase = Phase.FALL;
    }

    /** How often endless mode had to step in (for tests). */
    int rescues;

    /**
     * Endless mode, like the original: if this refill would settle into a dead board,
     * recolour the gems that are about to drop so there is always a next move.
     * Board positions are already final here, so the board can be checked before anything lands.
     */
    private void keepMovesAlive(List<Gem> fresh) {
        if (fresh.isEmpty() || !findGroups().isEmpty() || findMove() != null) return;
        rescues++;
        // usually one recoloured gem is enough
        for (Gem m : fresh) {
            int orig = m.t;
            for (int k = 1; k < COLORS; k++) {
                m.t = (orig + k) % COLORS;
                if (findMove() != null || !findGroups().isEmpty()) return;
            }
            m.t = orig;
        }
        for (int tries = 0; tries < 300; tries++) {
            for (Gem m : fresh) m.t = rnd.nextInt(COLORS);
            if (findMove() != null || !findGroups().isEmpty()) return;
        }
        // still dead: onSettled() falls back to sweeping the table
    }

    /** Returns true when everything has landed. */
    private boolean stepFall(float dt) {
        boolean moving = false;
        int landed = 0;
        for (int r = 0; r < N; r++)
            for (int c = 0; c < N; c++) {
                Gem m = g[r][c];
                if (m == null) continue;
                m.x = c;
                if (m.y < r) {
                    m.vy += GRAVITY * dt;
                    m.y += m.vy * dt;
                    if (m.y >= r) { m.y = r; m.vy = 0; landed++; } else moving = true;
                } else {
                    m.y = r;
                    m.vy = 0;
                }
            }
        if (landed > 0 && landCooldown <= 0) {
            L.sfx("land", 1f);
            landCooldown = .07f;
        }
        return !moving;
    }

    private void onSettled() {
        cascade = 0;
        if (levelScore >= target()) {
            phase = Phase.LEVEL_CLEAR;
            timer = 0;
            for (Gem[] row : g) for (Gem m : row) if (m != null) m.vy = 0;
            boolean last = planet() == Planets.COUNT - 1, voyageDone = !endless && last;
            L.music(voyageDone ? "finale" : "clear", voyageDone ? null : "loading");
            L.banner(voyageDone ? "Voyage complete!" : endless && last ? "Circuit complete!" : "Deal struck!", 2.4f);
            return;
        }
        if (findMove() == null && endless) {
            // nobody loses in endless: the table is swept and dealt again
            phase = Phase.DROP_OUT;
            timer = 0;
            shuffling = true;
            for (Gem[] row : g) for (Gem m : row) if (m != null) m.vy = 0;
            L.sfx("shuffle_out", 1f);
            L.banner("Fresh stones", 2f);
            L.save();
            return;
        }
        if (findMove() == null) {
            phase = Phase.NO_MOVES;
            timer = 0;
            L.sfx("no_moves", 1f);
            L.banner("No more moves", 2.6f);
            return;
        }
        phase = Phase.IDLE;
        idle = 0;
        hint = null;
        L.save();
    }
}
