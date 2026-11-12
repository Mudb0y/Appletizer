package com.mudb0y.appletizer;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.io.File;

/**
 * Debug builds only. Renders text through the system TTS path into a WAV for
 * checking off-device:
 *   adb shell am start -n com.mudb0y.appletizer/.SelfTestActivity \
 *       --es voice en-US-samantha-compact --es text "Hello" --es out hello.wav
 * The file lands in the app's external files directory; results go to logcat
 * under AppletizerTest. With --es download PACK_KEY instead, it downloads and
 * installs that voice pack and reports when it is in place. With --es
 * update-base URL, the updater reads URL/stable/update.json and
 * URL/dev/update.json instead of GitHub; an empty URL undoes it. With
 * --es crash java or --es crash native, the process dies that way, for
 * testing crash reports, and --es report FILE writes the crash report that
 * the app would share into FILE.
 */
public class SelfTestActivity extends Activity {
    private static final String TAG = "AppletizerTest";
    private TextToSpeech tts;
    private long started, firstAudio;
    private int words;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String crash = getIntent().getStringExtra("crash");
        if ("java".equals(crash)) throw new IllegalStateException("self-test crash");
        if ("native".equals(crash)) {
            // A real fault inside Appletizer's own library: stopping an engine that does not exist.
            try {
                System.loadLibrary("appletizer");
                java.lang.reflect.Method stop = Engine.class.getDeclaredMethod("stop", long.class);
                stop.setAccessible(true);
                stop.invoke(null, 0xdead0000L);
            } catch (ReflectiveOperationException e) {
                Log.e(TAG, "FAIL " + e);
            }
        }
        String report = getIntent().getStringExtra("report");
        if (report != null) {
            new Thread(() -> {
                try (java.io.FileWriter w = new java.io.FileWriter(new File(getExternalFilesDir(null), report))) {
                    w.write(CrashReports.report(getApplicationContext()));
                    Log.i(TAG, "DONE report " + report);
                } catch (java.io.IOException e) {
                    Log.e(TAG, "FAIL report: " + e);
                }
                runOnUiThread(this::finish);
            }).start();
            return;
        }
        String base = getIntent().getStringExtra("update-base");
        if (base != null) {
            Updater.prefs(this).edit().putString("testBase", base.isEmpty() ? null : base)
                    .remove("outcome").remove("json").apply();
            Log.i(TAG, "DONE update-base " + base);
            finish();
            return;
        }
        String download = getIntent().getStringExtra("download");
        if (download != null) {
            download(download);
            return;
        }
        String voice = getIntent().getStringExtra("voice");
        String text = getIntent().getStringExtra("text");
        String out = getIntent().getStringExtra("out");
        if (text == null || out == null) {
            Log.e(TAG, "FAIL need text and out extras");
            finish();
            return;
        }
        File file = new File(getExternalFilesDir(null), out);
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "FAIL engine did not bind: " + status);
                finish();
                return;
            }
            if (voice != null) {
                Voice found = null;
                for (Voice v : tts.getVoices()) if (v.getName().equals(voice)) found = v;
                if (found == null) {
                    Log.e(TAG, "FAIL no voice " + voice);
                    finish();
                    return;
                }
                tts.setVoice(found);
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) { }

                @Override
                public void onAudioAvailable(String id, byte[] audio) {
                    if (firstAudio == 0) firstAudio = SystemClock.elapsedRealtime();
                }

                @Override
                public void onRangeStart(String id, int start, int end, int frame) {
                    words++;
                }

                @Override
                public void onDone(String id) {
                    long now = SystemClock.elapsedRealtime();
                    Log.i(TAG, String.format("DONE %s first-audio %dms total %dms words %d",
                            file, firstAudio - started, now - started, words));
                    finish();
                }

                @Override
                public void onError(String id) {
                    Log.e(TAG, "FAIL synthesis error");
                    finish();
                }
            });
            started = SystemClock.elapsedRealtime();
            tts.synthesizeToFile(text, null, file, "selftest");
        }, getPackageName());
    }

    private void download(String key) {
        VoiceStore store = VoiceStore.get(this);
        VoiceStore.Pack p = store.pack(key);
        if (p == null) {
            Log.e(TAG, "FAIL no pack " + key);
            finish();
            return;
        }
        startForegroundService(new android.content.Intent(this, DownloadService.class)
                .putExtra(DownloadService.EXTRA_PACK, key));
        started = SystemClock.elapsedRealtime();
        new Thread(() -> {
            while (true) {
                SystemClock.sleep(500);
                DownloadService.State s = DownloadService.state(p);
                if (s == null && store.installed(p)) {
                    Log.i(TAG, "DONE download " + key + " in " + (SystemClock.elapsedRealtime() - started) + "ms");
                    break;
                }
                if (s != null && s.phase == DownloadService.Phase.FAILED) {
                    Log.e(TAG, "FAIL download " + key + ": " + s.error);
                    break;
                }
            }
            runOnUiThread(this::finish);
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
