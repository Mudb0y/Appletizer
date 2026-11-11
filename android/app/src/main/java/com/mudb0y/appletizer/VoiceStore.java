package com.mudb0y.appletizer;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** What voices exist, where they live on disk, and how Android locales map onto them. */
final class VoiceStore {

    static final class Voice {
        final String language;
        final String name;
        final boolean premium;
        final File dir;

        Voice(String language, String name, boolean premium, File dir) {
            this.language = language;
            this.name = name;
            this.premium = premium;
            this.dir = dir;
        }

        String id() {
            return language + "-" + name.toLowerCase(Locale.ROOT) + (premium ? "-premium" : "-compact");
        }

        Locale locale() {
            return Locale.forLanguageTag(language);
        }
    }

    static final class Pack {
        final String language, name, url, sha1;
        final long bytes, unpacked;

        Pack(JSONObject o) throws JSONException {
            language = o.getString("language");
            name = o.getString("name");
            url = o.getString("url");
            sha1 = o.getString("sha1");
            bytes = o.getLong("bytes");
            unpacked = o.getLong("unpacked");
        }

        int megabytes() {
            return (int) Math.max(1, Math.round(bytes / 1e6));
        }
    }

    /** When only a language is asked for, the variant iOS itself would pick. */
    private static final Map<String, String> PREFERRED = new HashMap<>();

    static {
        PREFERRED.put("eng", "en-US");
        PREFERRED.put("spa", "es-ES");
        PREFERRED.put("fra", "fr-FR");
        PREFERRED.put("por", "pt-BR");
        PREFERRED.put("zho", "zh-CN");
        PREFERRED.put("nld", "nl-NL");
    }

    private static VoiceStore instance;

    static synchronized VoiceStore get(Context context) {
        if (instance == null) instance = new VoiceStore(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final File root;
    private final List<String> languages;
    private final Map<String, Pack> packs = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final String build;

    private VoiceStore(Context app) {
        this.app = app;
        root = new File(app.getFilesDir(), "voices");
        String b;
        try {
            b = Long.toString(app.getPackageManager().getPackageInfo(app.getPackageName(), 0).lastUpdateTime);
        } catch (PackageManager.NameNotFoundException e) {
            b = "0";
        }
        build = b;
        List<String> langs = new ArrayList<>();
        String[] listed;
        try {
            listed = readAsset("voices/index.txt").split("\n");
            JSONArray a = new JSONObject(readAsset("premium-voices.json")).getJSONArray("voices");
            for (int i = 0; i < a.length(); i++) {
                Pack p = new Pack(a.getJSONObject(i));
                packs.put(p.language, p);
            }
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("APK assets are incomplete", e);
        }
        // The TTS service builds this store, so one broken language must not
        // take every other voice down with it.
        for (String line : listed) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            try {
                names.put(l, brokerTag(readAsset("voices/" + l + "/broker.hdr"), "voice"));
                langs.add(l);
            } catch (IOException e) {
                android.util.Log.w("Appletizer", "skipping language " + l, e);
            }
        }
        Collections.sort(langs);
        languages = Collections.unmodifiableList(langs);
    }

    List<String> languages() {
        return languages;
    }

    Pack pack(String language) {
        return packs.get(language);
    }

    File commonDir() {
        return new File(root, "compact/common");
    }

    File compactDir(String language) {
        return new File(root, "compact/" + language);
    }

    File premiumDir(String language) {
        return new File(root, "premium/" + language);
    }

    boolean premiumInstalled(String language) {
        return new File(premiumDir(language), "broker.hdr").isFile();
    }

    String compactName(String language) {
        return names.get(language);
    }

    String premiumName(String language) {
        String n = null;
        if (premiumInstalled(language)) {
            try {
                n = brokerTag(readFile(new File(premiumDir(language), "broker.hdr")), "voice");
            } catch (IOException ignored) {
            }
        }
        if (n == null || n.isEmpty()) {
            Pack p = packs.get(language);
            n = p != null && !p.name.isEmpty() ? p.name : compactName(language);
        }
        return n;
    }

    List<Voice> installed() {
        List<Voice> out = new ArrayList<>();
        for (String l : languages) {
            out.add(new Voice(l, compactName(l), false, compactDir(l)));
            if (premiumInstalled(l)) out.add(new Voice(l, premiumName(l), true, premiumDir(l)));
        }
        return out;
    }

    Voice find(String id) {
        if (id == null) return null;
        for (Voice v : installed())
            if (v.id().equals(id)) return v;
        return null;
    }

    Voice best(String language) {
        if (premiumInstalled(language)) return new Voice(language, premiumName(language), true, premiumDir(language));
        return new Voice(language, compactName(language), false, compactDir(language));
    }

    /**
     * The language code serving an ISO 639 / 3166 pair (either letter count),
     * with the TextToSpeech LANG_* level it reaches; null when unsupported.
     */
    Object[] match(String lang, String country) {
        String l = iso3Language(lang);
        String c = iso3Country(country);
        if (l.isEmpty()) return null;
        if (l.equals("yue")) {
            l = "zho";
            if (c.isEmpty()) c = "HKG";
        }
        String langOnly = null;
        for (String code : languages) {
            Locale loc = Locale.forLanguageTag(code);
            if (!iso3Language(loc.getLanguage()).equals(l)) continue;
            if (!c.isEmpty() && loc.getISO3Country().equals(c))
                return new Object[] {code, TextToSpeech.LANG_COUNTRY_AVAILABLE};
            if (langOnly == null) langOnly = code;
        }
        if (langOnly == null) return null;
        String preferred = PREFERRED.get(l);
        return new Object[] {preferred != null ? preferred : langOnly, TextToSpeech.LANG_AVAILABLE};
    }

    static String iso3Language(String lang) {
        if (lang == null || lang.isEmpty()) return "";
        String l = lang.toLowerCase(Locale.ROOT);
        if (l.equals("nob") || l.equals("nno") || l.equals("nb") || l.equals("nn")) return "nor";
        try {
            return new Locale(l).getISO3Language();
        } catch (java.util.MissingResourceException e) {
            return l;
        }
    }

    static String iso3Country(String country) {
        if (country == null || country.isEmpty()) return "";
        try {
            return new Locale("", country).getISO3Country();
        } catch (java.util.MissingResourceException e) {
            return country.toUpperCase(Locale.ROOT);
        }
    }

    /** Copies one compact voice and the shared files out of the APK, once per install. */
    synchronized void ensureCompact(String language) throws IOException {
        ensureCommon();
        copyAssetDir("voices/" + language, compactDir(language));
    }

    synchronized void ensureCommon() throws IOException {
        copyAssetDir("voices/common", commonDir());
    }

    void ensureAllCompact() {
        for (String l : languages) {
            try {
                ensureCompact(l);
            } catch (IOException e) {
                android.util.Log.w("Appletizer", "copying " + l, e);
            }
        }
    }

    private void copyAssetDir(String asset, File dest) throws IOException {
        File stamp = new File(dest, ".build");
        if (stamp.isFile() && build.equals(readFile(stamp))) return;
        File tmp = new File(dest.getPath() + ".tmp");
        deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
        AssetManager am = app.getAssets();
        byte[] buf = new byte[1 << 16];
        for (String f : am.list(asset)) {
            try (InputStream in = am.open(asset + "/" + f);
                 OutputStream out = new FileOutputStream(new File(tmp, f))) {
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
        }
        try (OutputStream out = new FileOutputStream(new File(tmp, ".build"))) {
            out.write(build.getBytes(StandardCharsets.UTF_8));
        }
        deleteTree(dest);
        if (!tmp.renameTo(dest)) throw new IOException("cannot move " + tmp + " to " + dest);
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static String brokerTag(String xml, String tag) {
        int a = xml.indexOf("<" + tag + ">");
        int b = xml.indexOf("</" + tag + ">");
        if (a < 0 || b < a) return "";
        return xml.substring(a + tag.length() + 2, b).trim();
    }

    private String readAsset(String name) throws IOException {
        try (InputStream in = app.getAssets().open(name)) {
            return readAll(in);
        }
    }

    static String readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
