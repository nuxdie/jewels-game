package com.local.jewels;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Sound effects played on the music module's own instruments. A second copy of
 * BeyondNetwork.it sits on an empty row and we trigger notes on it, as listed in
 * assets/sfx.txt: effect, start ms, instrument, note, volume, pan, hold ms.
 *
 * All libopenmpt calls happen on the render thread; other threads only queue requests.
 */
final class Sfx implements Runnable {
    private static native long nOpen(byte[] data, int gainMb);
    private static native void nPark(long h);
    private static native boolean nParked(long h);
    private static native int nNumInstruments(long h);
    private static native String nInstrumentName(long h, int i);
    private static native int nPlay(long h, int ins, int note, double vol, double pan);
    private static native void nOff(long h, int ch);
    private static native int nRead(long h, int rate, short[] buf, int frames);
    private static native void nClose(long h);

    private static final String TAG = "JewelsSfx";
    private static final int GAIN_MB = 1600;      // the module is mixed for 14 channels; one effect needs more level
    private static final int CHUNK = 128;          // frames per render step
    private static final float TAIL_SECS = 3.5f;   // keep rendering this long after the last note-off (bell releases)

    private static final class Note {
        final int ms, ins, note, hold;
        final float vol, pan;

        Note(int ms, int ins, int note, float vol, float pan, int hold) {
            this.ms = ms; this.ins = ins; this.note = note; this.vol = vol; this.pan = pan; this.hold = hold;
        }
    }

    /** A note waiting to start or to be released, in output frames. */
    private static final class Voice {
        final Note n;
        final float vol;
        final long on, off;
        int ch = -1;

        Voice(Note n, float vol, long on, long off) { this.n = n; this.vol = vol; this.on = on; this.off = off; }
    }

    private final Map<String, Note[]> effects = new HashMap<>();
    private final ConcurrentLinkedQueue<Object[]> requests = new ConcurrentLinkedQueue<>();
    private final ArrayList<Voice> voices = new ArrayList<>();
    private final long mod;
    private final int rate;
    private final AudioTrack track;
    private final Thread thread;
    private final Object lock = new Object();
    private final short[] buf = new short[CHUNK * 2];
    private boolean running = true, paused;
    private long frame, quietFrom;  // quietFrom: when the last note was released

    Sfx(byte[] module, InputStream defs) throws IOException {
        System.loadLibrary("openmpt");
        System.loadLibrary("jewelsaudio");
        mod = nOpen(module, GAIN_MB);
        if (mod == 0) throw new IllegalStateException("libopenmpt could not open the module for effects");
        parse(defs);
        nPark(mod);

        int r = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        rate = r > 0 ? r : 48000;
        quietFrom = -(long) rate * 10;
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(Math.max(min, rate * 4 * 30 / 1000))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        thread = new Thread(this, "sfx");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    private void parse(InputStream in) throws IOException {
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0, n = nNumInstruments(mod); i < n; i++)
            byName.put(nInstrumentName(mod, i).replace(' ', '_').toLowerCase(Locale.ROOT), i);
        Map<String, List<Note>> fx = new HashMap<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        for (String line; (line = r.readLine()) != null; ) {
            String[] p = line.trim().split("\\s+");
            if (p.length != 7 || p[0].startsWith("#")) continue;
            Integer ins = p[2].startsWith("#") ? Integer.valueOf(Integer.parseInt(p[2].substring(1)) - 1)
                    : byName.get(p[2].toLowerCase(Locale.ROOT));
            int note = note(p[3]);
            if (ins == null || note < 0) { Log.w(TAG, "bad line: " + line); continue; }
            List<Note> l = fx.get(p[0]);
            if (l == null) fx.put(p[0], l = new ArrayList<>());
            l.add(new Note(Integer.parseInt(p[1]), ins, note, Float.parseFloat(p[4]), Float.parseFloat(p[5]), Integer.parseInt(p[6])));
        }
        for (Map.Entry<String, List<Note>> e : fx.entrySet()) effects.put(e.getKey(), e.getValue().toArray(new Note[0]));
    }

    /** Tracker notation: "C-5" is middle C (60), "F#6" is 78. */
    private static int note(String s) {
        int i = "C-C#D-D#E-F-F#G-G#A-A#B-".indexOf(s.substring(0, 2).toUpperCase(Locale.ROOT));
        return i < 0 || i % 2 != 0 ? -1 : i / 2 + 12 * Integer.parseInt(s.substring(2));
    }

    // ------------------------------------------------------------------ requests (any thread)

    void play(String name, float vol) {
        if (!effects.containsKey(name)) { Log.w(TAG, "no effect " + name); return; }
        requests.add(new Object[]{name, vol});
        synchronized (lock) { lock.notifyAll(); }
    }

    void setPaused(boolean p) {
        synchronized (lock) { paused = p; lock.notifyAll(); }
    }

    void release() {
        synchronized (lock) { running = false; lock.notifyAll(); }
        try { thread.join(); } catch (InterruptedException ignored) { }
        track.release();
        nClose(mod);
    }

    // ------------------------------------------------------------------ render thread

    @Override
    public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        while (true) {
            synchronized (lock) {
                if (!running) break;
                boolean idle = voices.isEmpty() && requests.isEmpty() && frame - quietFrom > rate * TAIL_SECS;
                if (paused || idle) {
                    if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) { track.pause(); track.flush(); }
                    if (idle && !nParked(mod)) nPark(mod);   // nothing is sounding, so the seek can't cut anything off
                    try { lock.wait(); } catch (InterruptedException ignored) { }
                    continue;
                }
            }
            for (Object[] q; (q = requests.poll()) != null; ) {
                float v = (Float) q[1];
                for (Note n : effects.get((String) q[0]))
                    voices.add(new Voice(n, v, frame + (long) n.ms * rate / 1000, frame + (long) (n.ms + n.hold) * rate / 1000));
            }
            for (Iterator<Voice> it = voices.iterator(); it.hasNext(); ) {
                Voice v = it.next();
                if (v.ch < 0 && frame >= v.on) v.ch = nPlay(mod, v.n.ins, v.n.note, Math.min(1f, v.n.vol * v.vol), v.n.pan);
                if (frame >= v.off) {
                    nOff(mod, v.ch);
                    it.remove();
                    quietFrom = frame;
                }
            }
            if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
            int n = nRead(mod, rate, buf, CHUNK);
            if (n > 0) track.write(buf, 0, n * 2);
            frame += CHUNK;
        }
    }
}
