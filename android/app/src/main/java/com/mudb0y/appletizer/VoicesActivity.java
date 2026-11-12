package com.mudb0y.appletizer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One row per language; a language opens its voices, a voice opens what can be done with it. */
public class VoicesActivity extends Activity {
    private VoiceStore store;
    private ArrayAdapter<String> adapter;
    private final List<String> rows = new ArrayList<>();
    private TextToSpeech tts;
    private final Runnable refresh = () -> runOnUiThread(this::fill);

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = VoiceStore.get(this);
        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                language(store.languages().get(position)));
        setContentView(list);
        fill();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        tts = new TextToSpeech(this, status -> { }, getPackageName());
    }

    @Override
    protected void onResume() {
        super.onResume();
        DownloadService.addListener(refresh);
        fill();
    }

    @Override
    protected void onPause() {
        DownloadService.removeListener(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }

    private static String languageName(String lang) {
        return Locale.forLanguageTag(lang).getDisplayName();
    }

    private void fill() {
        rows.clear();
        for (String lang : store.languages()) {
            List<VoiceStore.Voice> voices = store.voices(lang);
            int ready = 0;
            for (VoiceStore.Voice v : voices) if (v.installed()) ready++;
            VoiceStore.Voice def = store.best(lang);
            rows.add(getString(R.string.row_language, languageName(lang), ready, voices.size(),
                    def == null ? "" : title(def)));
        }
        adapter.notifyDataSetChanged();
    }

    private String title(VoiceStore.Voice v) {
        return getString(R.string.voice_title, v.name, getString(v.gen.era),
                getString(v.premium ? R.string.footprint_premium : R.string.footprint_compact));
    }

    private boolean busy(VoiceStore.Pack p) {
        DownloadService.State s = p == null ? null : DownloadService.state(p);
        return s != null && s.phase != DownloadService.Phase.FAILED;
    }

    private String line(VoiceStore.Voice v, VoiceStore.Voice def) {
        StringBuilder b = new StringBuilder(title(v)).append(". ");
        DownloadService.State s = v.pack == null ? null : DownloadService.state(v.pack);
        if (v.pack == null) {
            b.append(getString(R.string.state_builtin));
        } else if (s != null && s.phase == DownloadService.Phase.FAILED) {
            b.append(getString(R.string.state_failed, s.error));
        } else if (s != null && s.phase == DownloadService.Phase.INSTALLING) {
            b.append(getString(R.string.state_installing));
        } else if (s != null) {
            b.append(getString(R.string.state_downloading, s.percent));
        } else if (v.installed()) {
            b.append(getString(R.string.state_downloaded));
        } else {
            b.append(getString(R.string.state_available, v.pack.megabytes()));
            if (v.pack.folders.length > 1)
                b.append(", ").append(getString(R.string.state_paired, getString(v.premium
                        ? R.string.footprint_compact : R.string.footprint_premium)));
        }
        if (def != null && def.id().equals(v.id())) b.append(", ").append(getString(R.string.state_default));
        return b.append('.').toString();
    }

    private void language(String lang) {
        List<VoiceStore.Voice> voices = store.voices(lang);
        VoiceStore.Voice def = store.best(lang);
        String[] lines = new String[voices.size()];
        for (int i = 0; i < lines.length; i++) lines[i] = line(voices.get(i), def);
        new AlertDialog.Builder(this)
                .setTitle(languageName(lang))
                .setItems(lines, (d, which) -> voice(voices.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void voice(VoiceStore.Voice v) {
        List<String> labels = new ArrayList<>();
        List<Runnable> runs = new ArrayList<>();
        VoiceStore.Pack p = v.pack;
        VoiceStore.Voice def = store.best(v.language);
        if (v.installed() && !busy(p)) {
            labels.add(getString(R.string.action_hear));
            runs.add(() -> hear(v));
            if (def == null || !def.id().equals(v.id())) {
                labels.add(getString(R.string.action_default, languageName(v.language)));
                runs.add(() -> {
                    store.setDefault(v);
                    fill();
                });
            }
            if (p != null) {
                labels.add(getString(R.string.action_remove));
                runs.add(() -> {
                    VoiceStore.deleteTree(store.packDir(p));
                    fill();
                });
            }
        } else if (busy(p)) {
            labels.add(getString(R.string.action_cancel_download));
            runs.add(() -> startService(new Intent(this, DownloadService.class)
                    .setAction(DownloadService.ACTION_CANCEL)
                    .putExtra(DownloadService.EXTRA_PACK, p.key())));
        } else if (p != null) {
            labels.add(getString(R.string.action_download, p.megabytes()));
            runs.add(() -> startForegroundService(new Intent(this, DownloadService.class)
                    .putExtra(DownloadService.EXTRA_PACK, p.key())));
        }
        new AlertDialog.Builder(this)
                .setTitle(title(v))
                .setItems(labels.toArray(new String[0]), (d, which) -> runs.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void hear(VoiceStore.Voice v) {
        Set<Voice> all = tts.getVoices();
        if (all == null) return;
        for (Voice av : all) {
            if (av.getName().equals(v.id())) {
                tts.setVoice(av);
                tts.speak(v.sampleText(), TextToSpeech.QUEUE_FLUSH, null, "sample");
                return;
            }
        }
    }
}
