package com.mudb0y.appletizer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds out whether the channel the user follows has a newer build. CI
 * publishes a small update.json beside every APK at a fixed address per
 * channel, so a check costs a few hundred bytes and no GitHub API quota.
 */
final class Updater {
    static final String STABLE = "stable", DEV = "dev";
    private static final String RELEASES = "https://github.com/Mudb0y/Appletizer/releases/";
    private static final String TAG = "Appletizer";
    private static final String CHANNEL_ID = "updates";
    static final int NOTIFY_AVAILABLE = 50, NOTIFY_RESULT = 51;
    private static final int JOB = 1;

    enum Status { UNKNOWN, CHECKING, CURRENT, AVAILABLE, AHEAD, NONE, FAILED }

    /** One channel's newest build, as its update.json describes it. */
    static final class Release {
        final long code, size;
        final String name, url, sha256;
        final String[] notes;

        Release(String json) throws JSONException {
            JSONObject o = new JSONObject(json);
            code = o.getLong("code");
            size = o.getLong("size");
            name = o.getString("name");
            url = o.getString("url");
            sha256 = o.getString("sha256");
            JSONArray n = o.optJSONArray("notes");
            notes = new String[n == null ? 0 : n.length()];
            for (int i = 0; i < notes.length; i++) notes[i] = n.getString(i);
        }

        int megabytes() {
            return (int) Math.max(1, Math.round(size / 1e6));
        }
    }

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static volatile boolean checking;

    private Updater() { }

    static void addListener(Runnable r) {
        listeners.add(r);
    }

    static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    static void changed() {
        for (Runnable r : listeners) r.run();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    private static PackageInfo info(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    static long installedCode(Context c) {
        PackageInfo i = info(c);
        return Build.VERSION.SDK_INT >= 28 ? i.getLongVersionCode() : i.versionCode;
    }

    static String installedName(Context c) {
        return info(c).versionName;
    }

    /** Until the user picks one, a build follows the channel it came from. */
    static String channel(Context c) {
        return prefs(c).getString("channel", installedName(c).contains("-dev") ? DEV : STABLE);
    }

    static void setChannel(Context c, String channel) {
        prefs(c).edit().putString("channel", channel).remove("outcome").remove("json").remove("error").apply();
        changed();
        check(c, false, null);
    }

    static boolean automatic(Context c) {
        return prefs(c).getBoolean("auto", true);
    }

    static void setAutomatic(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto", on).apply();
        schedule(c);
    }

    /**
     * Cheap enough to call whenever the app or the engine starts. Never
     * throws: the speech engine calls it, and no update problem may stop
     * the phone talking.
     */
    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (!automatic(c)) {
                js.cancel(JOB);
                return;
            }
            if (js.getPendingJob(JOB) != null) return;
            js.schedule(new JobInfo.Builder(JOB, new ComponentName(c, UpdateJob.class))
                    .setPeriodic(24 * 60 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build());
        } catch (RuntimeException e) {
            Log.w(TAG, "scheduling the daily update check", e);
        }
    }

    static Release latest(Context c) {
        String json = prefs(c).getString("json", null);
        if (json == null) return null;
        try {
            return new Release(json);
        } catch (JSONException e) {
            return null;
        }
    }

    static String error(Context c) {
        return prefs(c).getString("error", "");
    }

    static long checkedAt(Context c) {
        return prefs(c).getLong("checked", 0);
    }

    /** Judged against the installed version each time, so it stays right across an update. */
    static Status status(Context c) {
        if (checking) return Status.CHECKING;
        String outcome = prefs(c).getString("outcome", "");
        switch (outcome) {
            case "none": return Status.NONE;
            case "failed": return Status.FAILED;
            case "found": break;
            default: return Status.UNKNOWN;
        }
        Release r = latest(c);
        if (r == null) return Status.UNKNOWN;
        long have = installedCode(c);
        return r.code > have ? Status.AVAILABLE : r.code < have ? Status.AHEAD : Status.CURRENT;
    }

    static void check(Context c, boolean notify, Runnable done) {
        Context app = c.getApplicationContext();
        worker.submit(() -> {
            try {
                run(app, notify);
            } finally {
                if (done != null) done.run();
            }
        });
    }

    private static void run(Context c, boolean notify) {
        String channel = channel(c);
        checking = true;
        changed();
        SharedPreferences.Editor e = prefs(c).edit().putLong("checked", System.currentTimeMillis());
        try {
            String json = fetch(url(c, channel));
            if (json == null) {
                e.putString("outcome", "none").remove("json");
            } else {
                new Release(json);
                e.putString("outcome", "found").putString("json", json);
            }
            e.remove("error");
        } catch (IOException | JSONException ex) {
            Log.w(TAG, "checking for updates", ex);
            e.putString("outcome", "failed").putString("error", ex.getMessage()).remove("json");
        }
        // A channel switch during the check makes this answer stale; the switch queued its own.
        if (channel.equals(channel(c))) e.commit();
        checking = false;
        changed();
        if (notify && channel.equals(channel(c))) announce(c);
    }

    private static String url(Context c, String channel) {
        String base = RELEASES + (channel.equals(DEV) ? "download/dev/" : "latest/download/");
        // Debug builds can be pointed at a local copy, set by SelfTestActivity.
        String test = prefs(c).getString("testBase", null);
        if (test != null && (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            base = test + channel + "/";
        return base + "update.json";
    }

    /** The body, or null when the channel has published nothing. */
    private static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        try {
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null;
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("GitHub answered " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > 1 << 20) throw new IOException("the update file is too large");
                }
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static NotificationManager notifications(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                c.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT));
        return nm;
    }

    static Notification.Builder notice(Context c, String title) {
        return new Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 0,
                        new Intent(c, UpdatesActivity.class), PendingIntent.FLAG_IMMUTABLE));
    }

    /** Once per version, so the daily check does not repeat itself. */
    private static void announce(Context c) {
        if (status(c) != Status.AVAILABLE) return;
        Release r = latest(c);
        SharedPreferences p = prefs(c);
        if (p.getLong("notified", 0) == r.code) return;
        p.edit().putLong("notified", r.code).apply();
        String size = c.getString(R.string.notif_update_size, r.megabytes());
        StringBuilder big = new StringBuilder(size);
        for (String n : r.notes) big.append('\n').append(n);
        PendingIntent install = PendingIntent.getActivity(c, 1, new Intent(c, UpdatesActivity.class)
                .putExtra(UpdatesActivity.EXTRA_INSTALL, true), PendingIntent.FLAG_IMMUTABLE);
        notifications(c).notify(NOTIFY_AVAILABLE,
                notice(c, c.getString(R.string.notif_update_available, r.name))
                        .setContentText(size)
                        .setStyle(new Notification.BigTextStyle().bigText(big))
                        .addAction(new Notification.Action.Builder((Icon) null,
                                c.getString(R.string.action_install_update), install).build())
                        .build());
    }

    /** The short form the main list shows. */
    static String summary(Context c) {
        UpdateService.Phase phase = UpdateService.phase;
        if (phase == UpdateService.Phase.DOWNLOADING)
            return c.getString(R.string.row_updates_downloading, UpdateService.target, UpdateService.percent);
        if (phase == UpdateService.Phase.INSTALLING)
            return c.getString(R.string.row_updates_installing, UpdateService.target);
        switch (status(c)) {
            case AVAILABLE: return c.getString(R.string.row_updates_available, latest(c).name);
            case CURRENT:
            case AHEAD: return c.getString(R.string.row_updates_current);
            case CHECKING: return c.getString(R.string.row_updates_checking);
            case FAILED: return c.getString(R.string.row_updates_failed);
            default: return c.getString(R.string.row_updates);
        }
    }
}
