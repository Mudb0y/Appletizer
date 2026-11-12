package com.mudb0y.appletizer;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** What voices exist, where they live on disk, and how Android locales map onto them. */
final class VoiceStore {

    /** One converted engine and the voices Apple made for it. */
    static final class Generation {
        final String id, library, tag;
        /** String resource naming the iOS versions it served. */
        final int era;

        Generation(String id, String library, String tag, int era) {
            this.id = id;
            this.library = library;
            this.tag = tag;
            this.era = era;
        }
    }

    static final Generation VE151 = new Generation("ve151", "libve151.so", "ios15", R.string.era_ve151);
    static final Generation VE131 = new Generation("ve131", "libve131.so", "ios7", R.string.era_ve131);
    /** The 32-bit iOS 3 to 6 engines, run inside an emulator by their bridge libraries. */
    static final Generation VAUTO = new Generation("vauto", "libvauto.so", "ios6", R.string.era_vauto);
    static final Generation VMOBILE = new Generation("vmobile", "libvmobile.so", "ios4", R.string.era_vmobile);
    /** Newest first, which is also the order voices are preferred in. */
    static final List<Generation> GENERATIONS = Arrays.asList(VE151, VE131, VAUTO, VMOBILE);

    static Generation generation(String id) {
        for (Generation g : GENERATIONS)
            if (g.id.equals(id)) return g;
        return null;
    }

    static final class Voice {
        final Generation gen;
        final String language;
        final String name;
        final boolean premium;
        final File dir;
        /** The download that brings this voice, or null when it is built in. */
        final Pack pack;

        Voice(Generation gen, String language, String name, boolean premium, File dir, Pack pack) {
            this.gen = gen;
            this.language = language;
            this.name = name;
            this.premium = premium;
            this.dir = dir;
            this.pack = pack;
        }

        String id() {
            return language + "-" + name.toLowerCase(Locale.ROOT)
                    + (premium ? "-premium-" : "-compact-") + gen.tag;
        }

        Locale locale() {
            return Locale.forLanguageTag(language);
        }

        String engine() {
            return gen.library;
        }

        boolean installed() {
            return pack == null || new File(dir, "broker.hdr").isFile();
        }

        /** The language's own name for itself, then the voice's name. */
        String sampleText() {
            Locale loc = locale();
            return loc.getDisplayName(loc) + ". " + name;
        }
    }

    /** One download from Apple: a single voice, or a compact and a premium one. */
    static final class Pack {
        final Generation gen;
        final String language, name, url, sha1;
        final long bytes, unpacked;
        final String[] folders, names;
        final boolean[] premium;

        Pack(JSONObject o) throws JSONException {
            gen = generation(o.getString("engine"));
            language = o.getString("language");
            name = o.getString("name");
            url = o.getString("url");
            sha1 = o.getString("sha1");
            bytes = o.getLong("bytes");
            unpacked = o.getLong("unpacked");
            JSONArray v = o.getJSONArray("voices");
            folders = new String[v.length()];
            names = new String[v.length()];
            premium = new boolean[v.length()];
            for (int i = 0; i < v.length(); i++) {
                JSONObject x = v.getJSONObject(i);
                folders[i] = x.getString("folder");
                names[i] = x.getString("name");
                premium[i] = x.getString("footprint").equals("premium");
            }
        }

        String key() {
            return gen.id + "-" + language + "-" + name.toLowerCase(Locale.ROOT);
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
    /** generation id, then language, to the built-in voice's name. */
    private final Map<String, Map<String, String>> builtin = new HashMap<>();
    private final List<Pack> packs = new ArrayList<>();
    private final SharedPreferences defaults;
    private final String build;

    private VoiceStore(Context app) {
        this.app = app;
        root = new File(app.getFilesDir(), "voices");
        defaults = app.getSharedPreferences("defaults", Context.MODE_PRIVATE);
        String b;
        try {
            b = Long.toString(app.getPackageManager().getPackageInfo(app.getPackageName(), 0).lastUpdateTime);
        } catch (PackageManager.NameNotFoundException e) {
            b = "0";
        }
        build = b;
        TreeSet<String> langs = new TreeSet<>();
        try {
            JSONArray a = new JSONObject(readAsset("catalog.json")).getJSONArray("packs");
            for (int i = 0; i < a.length(); i++) {
                Pack p = new Pack(a.getJSONObject(i));
                if (p.gen != null) packs.add(p);
            }
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("APK assets are incomplete", e);
        }
        for (Generation g : GENERATIONS) {
            Map<String, String> names = new LinkedHashMap<>();
            builtin.put(g.id, names);
            String[] listed;
            try {
                listed = readAsset("voices/" + g.id + "/index.txt").split("\n");
            } catch (IOException e) {
                android.util.Log.w("Appletizer", "no built-in voices for " + g.id, e);
                continue;
            }
            // The TTS service builds this store, so one broken language must not
            // take every other voice down with it.
            for (String line : listed) {
                String l = line.trim();
                if (l.isEmpty()) continue;
                try {
                    names.put(l, brokerTag(readAsset("voices/" + g.id + "/" + l + "/broker.hdr"), "voice"));
                    langs.add(l);
                } catch (IOException e) {
                    android.util.Log.w("Appletizer", "skipping " + g.id + " " + l, e);
                }
            }
        }
        for (Pack p : packs) langs.add(p.language);
        languages = Collections.unmodifiableList(new ArrayList<>(langs));
        migrate();
    }

    /** 0.1 kept one compact and one iOS 7 premium voice per language elsewhere. */
    private void migrate() {
        File[] old = new File(root, "premium").listFiles();
        if (old != null) {
            for (File d : old) {
                Pack p = pack(VE131, d.getName());
                if (p != null && new File(d, "broker.hdr").isFile() && !packDir(p).exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    packDir(p).getParentFile().mkdirs();
                    //noinspection ResultOfMethodCallIgnored
                    d.renameTo(packDir(p));
                }
            }
        }
        deleteTree(new File(root, "premium"));
        deleteTree(new File(root, "compact"));
    }

    List<String> languages() {
        return languages;
    }

    private Pack pack(Generation g, String language) {
        for (Pack p : packs)
            if (p.gen == g && p.language.equals(language)) return p;
        return null;
    }

    Pack pack(String key) {
        for (Pack p : packs)
            if (p.key().equals(key)) return p;
        return null;
    }

    File commonDir(Generation g) {
        return new File(root, g.id + "/common");
    }

    File packDir(Pack p) {
        return new File(root, p.gen.id + "/packs/" + p.language + "-" + p.name.toLowerCase(Locale.ROOT));
    }

    boolean installed(Pack p) {
        for (String f : p.folders)
            if (!new File(new File(packDir(p), f), "broker.hdr").isFile()) return false;
        return true;
    }

    /** Every voice of a language, installed or not, newest generation first. */
    List<Voice> voices(String language) {
        List<Voice> out = new ArrayList<>();
        for (Generation g : GENERATIONS) {
            String name = builtin.get(g.id).get(language);
            if (name != null)
                out.add(new Voice(g, language, name, false, new File(root, g.id + "/builtin/" + language), null));
            for (Pack p : packs) {
                if (p.gen != g || !p.language.equals(language)) continue;
                for (int i = 0; i < p.folders.length; i++)
                    out.add(new Voice(g, language, p.names[i], p.premium[i],
                            new File(packDir(p), p.folders[i]), p));
            }
        }
        return out;
    }

    List<Voice> installed() {
        List<Voice> out = new ArrayList<>();
        for (String l : languages)
            for (Voice v : voices(l))
                if (v.installed()) out.add(v);
        return out;
    }

    Voice find(String id) {
        if (id == null) return null;
        for (Voice v : installed())
            if (v.id().equals(id)) return v;
        return null;
    }

    void setDefault(Voice v) {
        defaults.edit().putString(v.language, v.id()).apply();
    }

    /**
     * The voice a language speaks with when no voice is named: the one chosen
     * in the app, else the newest installed premium voice, else the newest
     * built-in one.
     */
    Voice best(String language) {
        Voice chosen = find(defaults.getString(language, null));
        if (chosen != null && chosen.language.equals(language)) return chosen;
        List<Voice> all = voices(language);
        for (Voice v : all)
            if (v.premium && v.installed()) return v;
        for (Voice v : all)
            if (v.installed()) return v;
        return null;
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

    /** Puts a voice's files where the engine reads them; built-in ones come out of the APK once per install. */
    synchronized void prepare(Voice v) throws IOException {
        copyAssetDir("voices/" + v.gen.id + "/common", commonDir(v.gen));
        if (v.pack == null) copyAssetDir("voices/" + v.gen.id + "/" + v.language, v.dir);
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
