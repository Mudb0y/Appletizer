package com.mudb0y.appletizer;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AppletizerTtsService extends TextToSpeechService {
    private static final String TAG = "Appletizer";
    /** Engines kept open, so switching between a few languages costs nothing. */
    private static final int CACHED_ENGINES = 3;

    private VoiceStore store;
    private final LinkedHashMap<String, Engine> engines = new LinkedHashMap<>(4, 0.75f, true);
    private volatile Engine speaking;
    private String[] currentLanguage = {"eng", "USA", ""};

    @Override
    public void onCreate() {
        store = VoiceStore.get(this);
        super.onCreate();
        new Thread(store::ensureAllCompact, "appletizer-unpack").start();
    }

    @Override
    public void onDestroy() {
        synchronized (engines) {
            for (Engine e : engines.values()) e.close();
            engines.clear();
        }
        super.onDestroy();
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        Object[] m = store.match(lang, country);
        return m == null ? TextToSpeech.LANG_NOT_SUPPORTED : (Integer) m[1];
    }

    @Override
    protected String[] onGetLanguage() {
        return currentLanguage;
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        Object[] m = store.match(lang, country);
        if (m == null) return TextToSpeech.LANG_NOT_SUPPORTED;
        currentLanguage = iso3Triple((String) m[0]);
        return (Integer) m[1];
    }

    @Override
    public List<Voice> onGetVoices() {
        List<Voice> out = new ArrayList<>();
        for (VoiceStore.Voice v : store.installed()) out.add(toAndroid(v));
        return out;
    }

    @Override
    public int onIsValidVoiceName(String name) {
        return store.find(name) != null ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    @Override
    public int onLoadVoice(String name) {
        VoiceStore.Voice v = store.find(name);
        if (v == null) return TextToSpeech.ERROR;
        try {
            engine(v);
            return TextToSpeech.SUCCESS;
        } catch (IOException e) {
            Log.w(TAG, "loading " + name, e);
            return TextToSpeech.ERROR;
        }
    }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        Object[] m = store.match(lang, country);
        return m == null ? null : store.best((String) m[0]).id();
    }

    @Override
    protected void onStop() {
        Engine e = speaking;
        if (e != null) e.stop();
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback cb) {
        VoiceStore.Voice v = store.find(request.getVoiceName());
        if (v == null) {
            Object[] m = store.match(request.getLanguage(), request.getCountry());
            if (m == null) {
                cb.error(TextToSpeech.ERROR_INVALID_REQUEST);
                return;
            }
            v = store.best((String) m[0]);
        }
        Engine e;
        try {
            e = engine(v);
        } catch (IOException ex) {
            Log.w(TAG, "opening " + v.id(), ex);
            cb.error(TextToSpeech.ERROR_SYNTHESIS);
            return;
        }

        String text = request.getCharSequenceText().toString();
        cb.start(e.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1);
        speaking = e;
        int err;
        try {
            err = e.speak(text, request.getSpeechRate(), request.getPitch(), 100, new Engine.Sink() {
                @Override
                public boolean audio(byte[] pcm, int length) {
                    int max = cb.getMaxBufferSize();
                    for (int off = 0; off < length; off += max)
                        if (cb.audioAvailable(pcm, off, Math.min(max, length - off)) != TextToSpeech.SUCCESS)
                            return false;
                    return true;
                }

                @Override
                public void word(int start, int end, int sample) {
                    cb.rangeStart(sample, start, end);
                }
            });
        } finally {
            speaking = null;
        }
        if (err != 0) {
            Log.w(TAG, String.format("synthesis failed: %#x", err));
            cb.error(TextToSpeech.ERROR_SYNTHESIS);
        } else {
            cb.done();
        }
    }

    private Engine engine(VoiceStore.Voice v) throws IOException {
        synchronized (engines) {
            Engine e = engines.get(v.id());
            if (e != null) return e;
            if (v.premium) store.ensureCommon();
            else store.ensureCompact(v.language);
            e = new Engine(v.dir, store.commonDir());
            engines.put(v.id(), e);
            Iterator<Map.Entry<String, Engine>> it = engines.entrySet().iterator();
            while (engines.size() > CACHED_ENGINES && it.hasNext()) {
                Map.Entry<String, Engine> old = it.next();
                if (old.getValue() == speaking) continue;
                old.getValue().close();
                it.remove();
            }
            return e;
        }
    }

    private static String[] iso3Triple(String code) {
        java.util.Locale l = java.util.Locale.forLanguageTag(code);
        return new String[] {l.getISO3Language(), l.getISO3Country(), ""};
    }

    private static Voice toAndroid(VoiceStore.Voice v) {
        Set<String> features = new HashSet<>();
        return new Voice(v.id(), v.locale(),
                v.premium ? Voice.QUALITY_VERY_HIGH : Voice.QUALITY_NORMAL,
                v.premium ? Voice.LATENCY_NORMAL : Voice.LATENCY_LOW,
                false, features);
    }
}
