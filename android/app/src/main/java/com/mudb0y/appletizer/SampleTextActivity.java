package com.mudb0y.appletizer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

/** Gives Settings the sentence its Play button speaks. */
public class SampleTextActivity extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        VoiceStore store = VoiceStore.get(this);
        Intent in = getIntent();
        Object[] m = store.match(in.getStringExtra("language"), in.getStringExtra("country"));
        VoiceStore.Voice v = m == null ? null : store.best((String) m[0]);
        if (v == null) {
            setResult(TextToSpeech.LANG_NOT_SUPPORTED);
        } else {
            setResult(TextToSpeech.LANG_AVAILABLE,
                    new Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, v.sampleText()));
        }
        finish();
    }
}
