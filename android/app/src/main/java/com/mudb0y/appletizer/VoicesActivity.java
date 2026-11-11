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

/** Lists the 36 languages; each row offers its samples and the premium voice download. */
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
                actions(store.languages().get(position)));
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

    private void fill() {
        rows.clear();
        for (String lang : store.languages()) rows.add(describe(lang));
        adapter.notifyDataSetChanged();
    }

    private String describe(String lang) {
        String row = getString(R.string.row_compact, Locale.forLanguageTag(lang).getDisplayName(),
                store.compactName(lang));
        VoiceStore.Pack p = store.pack(lang);
        if (p == null) return row;
        String name = store.premiumName(lang);
        DownloadService.State s = DownloadService.state(lang);
        String premium;
        if (s != null && s.phase == DownloadService.Phase.FAILED)
            premium = getString(R.string.row_premium_failed, name, s.error);
        else if (s != null && s.phase == DownloadService.Phase.INSTALLING)
            premium = getString(R.string.row_premium_installing, name);
        else if (s != null)
            premium = getString(R.string.row_premium_downloading, name, s.percent);
        else if (store.premiumInstalled(lang))
            premium = getString(R.string.row_premium_installed, name);
        else
            premium = getString(R.string.row_premium_available, name, p.megabytes());
        return row + " " + premium;
    }

    private void actions(String lang) {
        List<String> labels = new ArrayList<>();
        List<Runnable> runs = new ArrayList<>();
        labels.add(getString(R.string.action_sample_compact));
        runs.add(() -> sample(lang, false));
        VoiceStore.Pack p = store.pack(lang);
        DownloadService.State s = DownloadService.state(lang);
        boolean busy = s != null && s.phase != DownloadService.Phase.FAILED;
        if (store.premiumInstalled(lang) && !busy) {
            labels.add(getString(R.string.action_sample_premium));
            runs.add(() -> sample(lang, true));
            labels.add(getString(R.string.action_remove));
            runs.add(() -> {
                VoiceStore.deleteTree(store.premiumDir(lang));
                fill();
            });
        } else if (busy) {
            labels.add(getString(R.string.action_cancel_download));
            runs.add(() -> startService(new Intent(this, DownloadService.class)
                    .setAction(DownloadService.ACTION_CANCEL)
                    .putExtra(DownloadService.EXTRA_LANGUAGE, lang)));
        } else if (p != null) {
            labels.add(getString(R.string.action_download, p.megabytes()));
            runs.add(() -> startForegroundService(new Intent(this, DownloadService.class)
                    .putExtra(DownloadService.EXTRA_LANGUAGE, lang)));
        }
        new AlertDialog.Builder(this)
                .setTitle(Locale.forLanguageTag(lang).getDisplayName())
                .setItems(labels.toArray(new String[0]), (d, which) -> runs.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Each voice says its language's own name, then its name. */
    private void sample(String lang, boolean premium) {
        VoiceStore.Voice v = premium
                ? new VoiceStore.Voice(lang, store.premiumName(lang), true, store.premiumDir(lang))
                : new VoiceStore.Voice(lang, store.compactName(lang), false, store.compactDir(lang));
        for (Voice av : tts.getVoices() == null ? new java.util.HashSet<Voice>() : tts.getVoices()) {
            if (av.getName().equals(v.id())) {
                tts.setVoice(av);
                Locale loc = v.locale();
                tts.speak(loc.getDisplayName(loc) + ". " + v.name, TextToSpeech.QUEUE_FLUSH, null, "sample");
                return;
            }
        }
    }
}
