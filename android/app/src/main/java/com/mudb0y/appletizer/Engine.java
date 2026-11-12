package com.mudb0y.appletizer;

import java.io.File;
import java.io.IOException;

/** One Vocalizer engine instance bound to one voice. Not thread-safe, except stop(). */
final class Engine implements AutoCloseable {
    static {
        System.loadLibrary("appletizer");
    }

    interface Sink {
        /** PCM 16-bit mono; return false to stop. */
        boolean audio(byte[] pcm, int length);

        /** A word [start, end) of the text begins at this sample. */
        void word(int start, int end, int sample);
    }

    final String voice;
    final String language;
    final String model;
    final int sampleRate;
    private long handle;

    /** library is the converted engine to run the voice on, by file name. */
    Engine(String library, File voiceDir, File commonDir) throws IOException {
        String[] info = new String[4];
        handle = open(library, voiceDir.getPath(), commonDir.getPath(), info);
        voice = info[0];
        language = info[1];
        model = info[2];
        sampleRate = Integer.parseInt(info[3]);
    }

    /** Blocks until spoken or stopped; returns 0 or the engine's error code. */
    int speak(String text, int rate, int pitch, Sink sink) {
        return speak(handle, text, rate, pitch, sink);
    }

    void stop() {
        long h = handle;
        if (h != 0) stop(h);
    }

    @Override
    public void close() {
        if (handle != 0) {
            close(handle);
            handle = 0;
        }
    }

    private static native long open(String library, String voiceDir, String commonDir, String[] info)
            throws IOException;

    private static native int speak(long handle, String text, int rate, int pitch, Sink sink);

    private static native void stop(long handle);

    private static native void close(long handle);
}
