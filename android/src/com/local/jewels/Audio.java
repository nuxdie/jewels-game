package com.local.jewels;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Music and sound effects, both from the one module: {@link Tracker} plays it, {@link Sfx} plays its instruments. */
final class Audio {
    private static final String TAG = "JewelsAudio";
    private Tracker tracker;
    private Sfx sfx;

    volatile boolean sfxOn = true;

    Audio(Context c) {
        byte[] module;
        try (InputStream in = c.getAssets().open("music/BeyondNetwork.it")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            module = out.toByteArray();
        } catch (IOException e) {
            Log.w(TAG, "no module: audio disabled", e);
            return;
        }
        try {
            tracker = new Tracker(module);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            Log.w(TAG, "music disabled", e);
        }
        try (InputStream defs = c.getAssets().open("sfx.txt")) {
            sfx = new Sfx(module, defs);
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            Log.w(TAG, "sound effects disabled", e);
        }
    }

    void sfx(String name, float vol) {
        if (sfxOn && sfx != null) sfx.play(name, vol);
    }

    /** Play a section of the module; for jingles, {@code then} starts when it ends. null = silence. */
    void music(String name, String then) {
        if (tracker != null) tracker.play(name, then);
    }

    /** The section playing right now (it changes by itself when a jingle hands over). */
    String want() {
        return tracker != null ? tracker.playing : null;
    }

    /** Live position in the module: order, pattern, row. */
    int[] position() {
        return tracker != null ? new int[]{tracker.order, tracker.pattern, tracker.row} : null;
    }

    void setMusicOn(boolean on) {
        if (tracker != null) tracker.setEnabled(on);
    }

    void pause() {
        if (tracker != null) tracker.setPaused(true);
        if (sfx != null) sfx.setPaused(true);
    }

    void resume() {
        if (tracker != null) tracker.setPaused(false);
        if (sfx != null) sfx.setPaused(false);
    }

    void release() {
        if (tracker != null) tracker.release();
        if (sfx != null) sfx.release();
    }
}
