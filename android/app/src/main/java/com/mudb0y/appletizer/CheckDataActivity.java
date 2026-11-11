package com.mudb0y.appletizer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Locale;

/** Answers the old CHECK_TTS_DATA query: every language has a built-in voice. */
public class CheckDataActivity extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ArrayList<String> available = new ArrayList<>();
        for (String lang : VoiceStore.get(this).languages()) {
            Locale l = Locale.forLanguageTag(lang);
            available.add(l.getISO3Language() + "-" + l.getISO3Country());
        }
        Intent result = new Intent()
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
                .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, new ArrayList<>());
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result);
        finish();
    }
}
