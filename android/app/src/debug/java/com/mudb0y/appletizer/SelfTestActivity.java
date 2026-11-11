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
 * under AppletizerTest.
 */
public class SelfTestActivity extends Activity {
    private static final String TAG = "AppletizerTest";
    private TextToSpeech tts;
    private long started, firstAudio;
    private int words;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
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

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
