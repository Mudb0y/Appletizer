package com.mudb0y.appletizer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads the build Updater found, checks it, and hands it to Android's installer. */
public class UpdateService extends Service {
    static final String ACTION_CANCEL = "com.mudb0y.appletizer.CANCEL_UPDATE";
    private static final String CHANNEL = "downloads";
    private static final int ONGOING_ID = 52;
    private static final String TAG = "Appletizer";

    enum Phase { DOWNLOADING, INSTALLING, FAILED }

    /** Null while no update is under way. */
    static volatile Phase phase;
    static volatile int percent;
    static volatile String target, error;
    private static volatile boolean cancelled;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private NotificationManager nm;

    @Override
    public void onCreate() {
        super.onCreate();
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
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            return START_NOT_STICKY;
        }
        // Android requires this promptly after startForegroundService, even when there is nothing to do.
        Notification n = progress(getString(R.string.notif_update_starting), -1);
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else
            startForeground(ONGOING_ID, n);
        if (phase == Phase.DOWNLOADING || phase == Phase.INSTALLING) return START_NOT_STICKY;
        Updater.Release r = Updater.latest(this);
        if (r == null || r.code <= Updater.installedCode(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        cancelled = false;
        target = r.name;
        percent = 0;
        error = null;
        phase = Phase.DOWNLOADING;
        Updater.changed();
        worker.submit(() -> run(r));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    static File dir(Context c) {
        return new File(c.getNoBackupFilesDir(), "update");
    }

    /** Leaves only keep, or nothing when keep is null. */
    static void clean(Context c, File keep) {
        File[] files = dir(c).listFiles();
        if (files == null) return;
        for (File f : files)
            if (!f.equals(keep)) //noinspection ResultOfMethodCallIgnored
                f.delete();
    }

    private void run(Updater.Release r) {
        File dir = dir(this);
        // Named by build, so a half download is never resumed with another build's bytes.
        File apk = new File(dir, r.code + "-" + r.sha256 + ".apk");
        try {
            clean(this, apk);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            download(r, apk);
            phase = Phase.INSTALLING;
            Updater.changed();
            nm.notify(ONGOING_ID, progress(getString(R.string.notif_update_installing, r.name), -1));
            if (!DownloadService.digest(apk, "SHA-256").equalsIgnoreCase(r.sha256)) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                throw new IOException("checksum mismatch, the download was damaged");
            }
            Updater.prefs(this).edit().putLong("installing", r.code).commit();
            install(apk);
            // Android now replaces this app, which ends this process; UpdateReceiver hears how it went.
        } catch (Exception e) {
            if (cancelled) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                phase = null;
                Updater.changed();
            } else {
                Log.w(TAG, "updating to " + r.name, e);
                failed(this, e.getMessage());
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void download(Updater.Release r, File apk) throws IOException {
        long have = apk.isFile() ? apk.length() : 0;
        if (have > r.size) {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            have = 0;
        }
        if (have == r.size) return;
        HttpURLConnection c = (HttpURLConnection) new URL(r.url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
        int code = c.getResponseCode();
        boolean append = code == HttpURLConnection.HTTP_PARTIAL;
        if (code != HttpURLConnection.HTTP_OK && !append) throw new IOException("GitHub answered " + code);
        if (!append) have = 0;
        int lastShown = -1;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk, append)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                if (cancelled) throw new IOException("cancelled");
                out.write(buf, 0, n);
                have += n;
                int pct = (int) (have * 100 / r.size);
                if (pct != percent) {
                    percent = pct;
                    Updater.changed();
                }
                if (pct / 5 != lastShown) {
                    lastShown = pct / 5;
                    nm.notify(ONGOING_ID, progress(getString(R.string.notif_update_downloading, r.name), pct));
                }
            }
        } finally {
            c.disconnect();
        }
        if (have != r.size) throw new IOException("download ended early");
    }

    /**
     * On Android 12 and later an app replacing itself may skip the system's
     * confirmation screen; earlier, or if Android insists, UpdateReceiver
     * gets the screen to show.
     */
    private void install(File apk) throws IOException {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent status = new Intent(this, UpdateReceiver.class).setAction(UpdateReceiver.ACTION_STATUS);
            // Mutable, since the installer fills in the outcome.
            s.commit(PendingIntent.getBroadcast(this, 0, status, PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0)).getIntentSender());
        } catch (IOException | RuntimeException e) {
            try {
                pi.abandonSession(id);
            } catch (RuntimeException ignored) {
                // Already committed or gone; the original failure is the one worth reporting.
            }
            throw e;
        }
    }

    static void failed(Context c, String message) {
        phase = Phase.FAILED;
        error = message == null || message.isEmpty() ? c.getString(R.string.update_no_reason) : message;
        Updater.prefs(c).edit().remove("installing").apply();
        Updater.changed();
        Updater.notifications(c).notify(Updater.NOTIFY_RESULT,
                Updater.notice(c, c.getString(R.string.notif_update_failed, error)).build());
        c.stopService(new Intent(c, UpdateService.class));
    }

    private Notification progress(String text, int pct) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(text)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, UpdatesActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (pct >= 0) b.setContentText(pct + "%").setProgress(100, pct, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }
}
