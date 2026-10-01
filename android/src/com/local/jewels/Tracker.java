package com.local.jewels;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plays BeyondNetwork.it (the Mod Archive release) live through libopenmpt, the way the original game did:
 * every section is a range of the module's order list, and we jump around inside
 * the one module instead of playing separate audio files.
 *
 * All libopenmpt calls happen on the render thread; other threads only post requests.
 */
final class Tracker implements Runnable {
    static {
        System.loadLibrary("openmpt");
        System.loadLibrary("jewelsaudio");
    }

    private static native long nOpen(byte[] data);
    private static native int nRead(long mod, int rate, short[] buf, int frames);
    private static native void nSetPos(long mod, int order, int row);
    private static native int nOrder(long mod);
    private static native int nPattern(long mod);
    private static native int nRow(long mod);
    private static native int nSpeed(long mod);
    private static native void nSetFactor(long mod, String key, double value);
    private static native void nClose(long mod);

    /** Orders [start, end) of the module. When playback leaves the range it goes to {@code next}, or loops to {@code loopTo}. */
    static final class Section {
        final String name, next;
        final int start, end, loopTo;

        Section(String name, int start, int end, int loopTo, String next) {
            this.name = name; this.start = start; this.end = end; this.loopTo = loopTo; this.next = next;
        }

        boolean contains(int order) { return order >= start && order < end; }
    }

    private static final int CHUNK = 128;           // frames per render step (~3 ms): how far we can overshoot a section end
    private static final int FADE_FRAMES = 3072;    // ~65 ms fade when cutting from one section to another

    private final Map<String, Section> sections = new HashMap<>();
    private final long mod;
    private final int rate;
    private final AudioTrack track;
    private final Thread thread;
    private final Object lock = new Object();

    // requests from other threads (guarded by lock)
    private boolean running = true, paused, enabled = true, reqPending;
    private String reqName, reqThen;
    private float reqTempo = 1f;

    // render-thread state
    private Section cur;
    private String curThen;
    private float tempo = 1f;
    private int hangFrames, quietFrames;

    /** What is playing now, for the UI. */
    volatile String playing;
    volatile int order, pattern, row;

    Tracker(byte[] data) {
        mod = nOpen(data);
        if (mod == 0) throw new IllegalStateException("libopenmpt could not open the module");
        buildSections(data);

        int r = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        rate = r > 0 ? r : 48000;
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(Math.max(min, rate * 4 * 70 / 1000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        thread = new Thread(this, "tracker");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    /**
     * Section layout. The suite (orders before 181) is split by the composer's "+++"
     * separators into movements: the first is the departure theme, the next twelve are
     * the planets (short interludes are skipped). The rest comes from his notes in the
     * game's music.xml. Works for both the in-game module and the Mod Archive release,
     * whose suite starts at order 0 instead of after a separator.
     */
    private void buildSections(byte[] d) {
        int ordNum = (d[0x20] & 0xFF) | (d[0x21] & 0xFF) << 8;
        List<Integer> seps = new ArrayList<>();
        seps.add(-1);
        for (int i = 0; i < ordNum; i++) if ((d[0xC0 + i] & 0xFF) == 254) seps.add(i);
        List<int[]> movements = new ArrayList<>();
        for (int i = 0; i + 1 < seps.size(); i++) {
            int a = seps.get(i), b = seps.get(i + 1);
            if (a + 1 < 181 && b - a > 4) movements.add(new int[]{a + 1, b});
        }
        if (movements.size() != 13) throw new IllegalStateException("unexpected module layout: " + movements.size());
        int[] m = movements.get(0);
        add(new Section("depart", m[0], m[1], m[0], null));
        for (int i = 1; i < movements.size(); i++) {
            m = movements.get(i);
            add(new Section(String.format(java.util.Locale.ROOT, "planet%02d", i), m[0], m[1], m[0], null));
        }
        add(new Section("menu", 0xB6, 0xC5, 0xB9, null));        // loops itself back to 0xB9
        add(new Section("ready", 0xC5, 0xC6, -1, null));         // jingle, then drops to the silent break pattern
        add(new Section("clear", 0xC7, 0xC8, -1, null));         // jingle
        add(new Section("loading", 0xC9, 0xD6, 0xCA, null));     // loops itself back to 0xCA
        add(new Section("finale", 0xD7, 0xE4, -1, "loading"));   // the module itself flows on into the loading theme
    }

    private void add(Section s) {
        sections.put(s.name, s);
    }

    // ------------------------------------------------------------------ requests (any thread)

    /** Play a section; {@code then} replaces what normally follows when it ends. null = silence. */
    void play(String name, String then) {
        synchronized (lock) {
            reqName = name;
            reqThen = then;
            reqPending = true;
            lock.notifyAll();
        }
    }

    void setEnabled(boolean on) {
        synchronized (lock) { enabled = on; lock.notifyAll(); }
    }

    void setPaused(boolean p) {
        synchronized (lock) { paused = p; lock.notifyAll(); }
    }

    /** 1.0 = as written. */
    void setTempo(float factor) {
        synchronized (lock) { reqTempo = factor; }
    }

    void release() {
        synchronized (lock) { running = false; lock.notifyAll(); }
        try { thread.join(); } catch (InterruptedException ignored) { }
        track.release();
        nClose(mod);
    }

    // ------------------------------------------------------------------ render thread

    private final short[] buf = new short[CHUNK * 2];

    @Override
    public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        while (true) {
            String name = null, then = null;
            boolean pending, audible;
            float t;
            synchronized (lock) {
                if (!running) break;
                pending = reqPending;
                if (pending) { name = reqName; then = reqThen; reqPending = false; }
                audible = enabled && !paused;
                t = reqTempo;
            }
            if (t != tempo) { tempo = t; nSetFactor(mod, "play.tempo_factor", t); }
            if (pending) switchTo(name, then, audible);
            if (!audible || cur == null) {
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause();
                synchronized (lock) {
                    // sleep until something changes
                    if (running && !reqPending && (!(enabled && !paused) || cur == null)) {
                        try { lock.wait(); } catch (InterruptedException ignored) { }
                    }
                }
                continue;
            }
            if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
            step();
        }
    }

    /** Render one chunk and keep playback inside the current section. */
    private void step() {
        int n = nRead(mod, rate, buf, CHUNK);
        int o = nOrder(mod);
        order = o;
        pattern = nPattern(mod);
        row = nRow(mod);
        if (n > 0 && cur.contains(o) && !rungOut(n)) {
            track.write(buf, 0, n * 2);
            return;
        }
        hangFrames = quietFrames = 0;
        // left the section: work out where to go
        String nextName = curThen != null ? curThen : cur.next;
        Section next = nextName != null ? sections.get(nextName) : null;
        if (next != null && next.contains(o) && n > 0) {
            // the module flows there on its own (finale -> loading): just follow it
            track.write(buf, 0, n * 2);
            adopt(next, null);
            return;
        }
        // cut: fade the overshoot chunk out, then jump
        fade(buf, n, 1f, 0f);
        if (n > 0) track.write(buf, 0, n * 2);
        if (next != null) {
            adopt(next, null);
            nSetPos(mod, next.start, 0);
        } else if (cur.loopTo >= 0) {
            android.util.Log.d("Tracker", "loop " + cur.name + " at order " + o + " -> " + cur.loopTo);
            nSetPos(mod, cur.loopTo, 0);
        } else {
            adopt(null, null);
        }
    }

    /**
     * The composer ends some jingles (Deal Struck) by setting speed AFF: the module then crawls
     * at ~5 s per row and only jumps on minutes later. Treat that as the end: let the last chord
     * ring until it's quiet (at most 5 s), then move on.
     */
    private boolean rungOut(int n) {
        if (nSpeed(mod) < 0xF0) {
            hangFrames = quietFrames = 0;
            return false;
        }
        hangFrames += n;
        int peak = 0;
        for (int i = 0; i < n * 2; i++) peak = Math.max(peak, Math.abs(buf[i]));
        quietFrames = peak < 60 ? quietFrames + n : 0;
        return quietFrames > rate * 3 / 10 || hangFrames > rate * 5;
    }

    private void switchTo(String name, String then, boolean audible) {
        Section s = name != null ? sections.get(name) : null;
        if (audible && cur != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            // quick fade-out of whatever is playing before the jump
            for (int done = 0; done < FADE_FRAMES; ) {
                int n = nRead(mod, rate, buf, CHUNK);
                if (n == 0) break;
                fade(buf, n, 1f - done / (float) FADE_FRAMES, 1f - (done + n) / (float) FADE_FRAMES);
                track.write(buf, 0, n * 2);
                done += n;
            }
        }
        adopt(s, then);
        if (s != null) nSetPos(mod, s.start, 0);
    }

    private void adopt(Section s, String then) {
        hangFrames = quietFrames = 0;
        android.util.Log.d("Tracker", "section " + (s != null ? s.name + " [" + s.start + "," + s.end + ")" : "silence") + " at order " + order);
        cur = s;
        curThen = then;
        playing = s != null ? s.name : null;
    }

    private static void fade(short[] b, int frames, float from, float to) {
        for (int i = 0; i < frames; i++) {
            float g = from + (to - from) * i / Math.max(1, frames);
            b[i * 2] = (short) (b[i * 2] * g);
            b[i * 2 + 1] = (short) (b[i * 2 + 1] * g);
        }
    }
}
