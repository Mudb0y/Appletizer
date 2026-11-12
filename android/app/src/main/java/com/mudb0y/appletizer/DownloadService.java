package com.mudb0y.appletizer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Fetches voice packs from Apple, checks them, and unpacks them. */
public class DownloadService extends Service {
    static final String EXTRA_PACK = "pack";
    static final String ACTION_CANCEL = "com.mudb0y.appletizer.CANCEL";
    private static final String CHANNEL = "downloads";
    private static final int ONGOING_ID = 1;
    private static final String TAG = "Appletizer";

    enum Phase { QUEUED, DOWNLOADING, INSTALLING, FAILED }

    static final class State {
        volatile Phase phase = Phase.QUEUED;
        volatile int percent;
        volatile String error;
    }

    private static final Map<String, State> states = new ConcurrentHashMap<>();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    static State state(VoiceStore.Pack p) {
        return states.get(p.key());
    }

    static void addListener(Runnable r) {
        listeners.add(r);
    }

    static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private static void changed() {
        for (Runnable r : listeners) r.run();
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger doneIds = new AtomicInteger(100);
    private NotificationManager nm;
    private VoiceStore store;

    @Override
    public void onCreate() {
        super.onCreate();
        store = VoiceStore.get(this);
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String key = intent == null ? null : intent.getStringExtra(EXTRA_PACK);
        VoiceStore.Pack p = key == null ? null : store.pack(key);
        if (p == null) {
            stopIfIdle();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelled.add(key);
            stopIfIdle();
            return START_NOT_STICKY;
        }
        State s = states.get(key);
        if (s != null && s.phase != Phase.FAILED) return START_NOT_STICKY;
        cancelled.remove(key);
        states.put(key, new State());
        changed();
        pending.incrementAndGet();
        Notification n = progress(label(p), getString(R.string.notif_downloading, label(p)), 0);
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else
            startForeground(ONGOING_ID, n);
        worker.submit(() -> run(p));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private String label(VoiceStore.Pack p) {
        return getString(R.string.pack_label, Locale.forLanguageTag(p.language).getDisplayName(),
                p.name, getString(p.gen.era));
    }

    private void run(VoiceStore.Pack p) {
        String key = p.key();
        State s = states.get(key);
        File zip = new File(getCacheDir(), key + ".zip");
        try {
            s.phase = Phase.DOWNLOADING;
            changed();
            download(key, p, zip, s);
            s.phase = Phase.INSTALLING;
            changed();
            nm.notify(ONGOING_ID, progress(label(p), getString(R.string.notif_installing, label(p)), -1));
            String sum = sha1(zip);
            if (!sum.equalsIgnoreCase(p.sha1))
                throw new IOException("checksum mismatch, the download was damaged");
            install(p, zip);
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
            states.remove(key);
            finished(getString(R.string.notif_installed, label(p)));
        } catch (Exception e) {
            if (cancelled.remove(key)) {
                //noinspection ResultOfMethodCallIgnored
                zip.delete();
                states.remove(key);
            } else {
                Log.w(TAG, "downloading " + key, e);
                s.phase = Phase.FAILED;
                s.error = e.getMessage();
                finished(getString(R.string.notif_failed, label(p), e.getMessage()));
            }
        } finally {
            changed();
            if (pending.decrementAndGet() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }

    private void download(String key, VoiceStore.Pack p, File zip, State s) throws IOException {
        long have = zip.isFile() ? zip.length() : 0;
        if (have > p.bytes) {
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
            have = 0;
        }
        if (have == p.bytes) return;
        HttpURLConnection c = (HttpURLConnection) new URL(p.url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
        int code = c.getResponseCode();
        boolean append = code == HttpURLConnection.HTTP_PARTIAL;
        if (code != HttpURLConnection.HTTP_OK && !append) throw new IOException("Apple's server answered " + code);
        if (!append) have = 0;
        int lastShown = -1;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(zip, append)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                if (cancelled.contains(key)) throw new IOException("cancelled");
                out.write(buf, 0, n);
                have += n;
                int pct = (int) (have * 100 / p.bytes);
                if (pct != s.percent) {
                    s.percent = pct;
                    changed();
                }
                if (pct / 5 != lastShown) {
                    lastShown = pct / 5;
                    nm.notify(ONGOING_ID, progress(label(p), getString(R.string.notif_downloading, label(p)), pct));
                }
            }
        } finally {
            c.disconnect();
        }
        if (have != p.bytes) throw new IOException("download ended early");
    }

    /**
     * Like iOS: everything below AssetData/<pack>/ keeps its place, so a pack
     * holding a compact and a premium voice keeps them in their own folders,
     * and broker.hdr.asset loses its suffix.
     */
    private void install(VoiceStore.Pack p, File zip) throws IOException {
        File dest = store.packDir(p);
        File tmp = new File(dest.getPath() + ".tmp");
        VoiceStore.deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(zip))) {
            byte[] buf = new byte[1 << 16];
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String[] parts = e.getName().split("/");
                if (e.isDirectory() || parts.length < 3 || !parts[0].equals("AssetData")) continue;
                String name = parts[parts.length - 1];
                if (name.endsWith(".plist") || name.contains("..")) continue;
                if (name.equals("broker.hdr.asset")) name = "broker.hdr";
                File dir = tmp;
                for (int i = 2; i < parts.length - 1; i++) {
                    if (parts[i].equals("..")) throw new IOException("the pack has a bad path");
                    dir = new File(dir, parts[i]);
                }
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                try (OutputStream out = new FileOutputStream(new File(dir, name))) {
                    for (int n; (n = z.read(buf)) > 0; ) out.write(buf, 0, n);
                }
            }
        }
        for (String f : p.folders)
            if (!new File(new File(tmp, f), "broker.hdr").isFile())
                throw new IOException("the pack has no broker header");
        VoiceStore.deleteTree(dest);
        if (!tmp.renameTo(dest)) throw new IOException("cannot move " + tmp);
    }

    private static String sha1(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private Notification progress(String title, String text, int percent) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, VoicesActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(percent >= 0 ? text + ", " + percent + "%" : text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (percent >= 0) b.setProgress(100, percent, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void finished(String text) {
        nm.notify(doneIds.incrementAndGet(), new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(text)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, VoicesActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .build());
    }

    private void stopIfIdle() {
        if (pending.get() == 0) stopSelf();
    }
}
