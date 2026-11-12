package com.mudb0y.appletizer;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Android records why each of this app's processes ended. Those that ended
 * badly (a crash, a native crash, a hang, Android 17's memory limiter) are
 * offered as a report the user can share, since most people who hit one
 * have no adb to fetch a log with. Android 11 and later only.
 */
final class CrashReports {
    private static final String TAG = "Appletizer";
    private static final String CHANNEL = "problems";
    private static final int NOTIFY = 53;
    private static final int MAX_EXITS = 5, MAX_TRACE = 32 * 1024, MAX_LOG_LINES = 300;

    private CrashReports() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("crashes", Context.MODE_PRIVATE);
    }

    private static File javaDir(Context c) {
        return new File(c.getFilesDir(), "crashes");
    }

    /** Android leaves Java stacks out of its records, so the uncaught-exception handler keeps them. */
    static void saveJava(Context c, Thread t, Throwable e) {
        try {
            File dir = javaDir(c);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            try (PrintWriter w = new PrintWriter(new FileOutputStream(
                    new File(dir, System.currentTimeMillis() + ".txt")))) {
                w.println("Thread " + t.getName());
                e.printStackTrace(w);
            }
            File[] old = dir.listFiles();
            if (old != null && old.length > MAX_EXITS) {
                Arrays.sort(old);
                for (int i = 0; i < old.length - MAX_EXITS; i++) //noinspection ResultOfMethodCallIgnored
                    old[i].delete();
            }
        } catch (Throwable ignored) {
            // Already dying; the crash itself is what Android will record.
        }
    }

    /** Exits after the last report the user shared or dismissed, newest first. */
    private static List<ApplicationExitInfo> pending(Context c) {
        List<ApplicationExitInfo> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 30) return out;
        long seen = prefs(c).getLong("seen", 0);
        try {
            for (ApplicationExitInfo e : c.getSystemService(ActivityManager.class)
                    .getHistoricalProcessExitReasons(c.getPackageName(), 0, 0))
                if (e.getTimestamp() > seen && abnormal(e)) out.add(e);
        } catch (RuntimeException ex) {
            Log.w(TAG, "reading exit reasons", ex);
        }
        return out;
    }

    private static boolean abnormal(ApplicationExitInfo e) {
        switch (e.getReason()) {
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
            case ApplicationExitInfo.REASON_ANR:
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return true;
            case ApplicationExitInfo.REASON_OTHER:
                // How Android 17's memory limiter reports its kills.
                String d = e.getDescription();
                return d != null && d.contains("MemoryLimiter");
            default:
                return false;
        }
    }

    static int count(Context c) {
        return pending(c).size();
    }

    static void markSeen(Context c) {
        List<ApplicationExitInfo> p = pending(c);
        if (!p.isEmpty()) prefs(c).edit().putLong("seen", p.get(0).getTimestamp()).apply();
        c.getSystemService(NotificationManager.class).cancel(NOTIFY);
    }

    /**
     * Called whenever the process starts, including when TalkBack restarts
     * the engine after a crash, so the user hears about it without opening
     * the app. Never throws, for the engine's sake.
     */
    static void announce(Context c) {
        new Thread(() -> {
            try {
                List<ApplicationExitInfo> p = pending(c);
                if (p.isEmpty()) return;
                long newest = p.get(0).getTimestamp();
                if (newest <= prefs(c).getLong("announced", 0)) return;
                prefs(c).edit().putLong("announced", newest).apply();
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                        c.getString(R.string.channel_problems), NotificationManager.IMPORTANCE_DEFAULT));
                nm.notify(NOTIFY, new Notification.Builder(c, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle(c.getString(R.string.crash_title))
                        .setContentText(c.getString(R.string.notif_crash_text))
                        .setOnlyAlertOnce(true)
                        .setAutoCancel(true)
                        .setContentIntent(PendingIntent.getActivity(c, 0,
                                new Intent(c, CrashReportActivity.class), PendingIntent.FLAG_IMMUTABLE))
                        .build());
            } catch (RuntimeException e) {
                Log.w(TAG, "announcing a crash", e);
            }
        }).start();
    }

    /** Slow (it reads the log), so not on the main thread. */
    static String report(Context c) {
        StringBuilder b = new StringBuilder();
        b.append(c.getString(R.string.crash_subject)).append('\n');
        b.append("Appletizer ").append(Updater.installedName(c)).append(" (")
                .append(Updater.installedCode(c)).append(")\n");
        b.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(", ").append(Build.HARDWARE).append(")\n");
        b.append("Android ").append(Build.VERSION.RELEASE).append(", API ").append(Build.VERSION.SDK_INT)
                .append(", ").append(Build.DISPLAY).append('\n');
        b.append("Build: ").append(Build.FINGERPRINT).append('\n');
        b.append("Page size: ").append(Os.sysconf(OsConstants._SC_PAGESIZE)).append('\n');
        b.append("Default speech engine: ").append(setting(c, Settings.Secure.TTS_DEFAULT_SYNTH)).append('\n');
        b.append("Accessibility services: ").append(setting(c, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)).append('\n');
        List<ApplicationExitInfo> exits = pending(c);
        for (int i = 0; i < Math.min(MAX_EXITS, exits.size()); i++) exit(c, exits.get(i), b);
        log(b);
        return b.toString();
    }

    private static String setting(Context c, String name) {
        try {
            String v = Settings.Secure.getString(c.getContentResolver(), name);
            return v == null ? "none" : v;
        } catch (RuntimeException e) {
            return "unreadable";
        }
    }

    private static void exit(Context c, ApplicationExitInfo e, StringBuilder b) {
        b.append("\n").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
                .format(new Date(e.getTimestamp()))).append(": ").append(reason(e.getReason())).append('\n');
        b.append("Process ").append(e.getProcessName()).append(", status ").append(e.getStatus())
                .append(", importance ").append(e.getImportance())
                .append(", PSS ").append(e.getPss()).append(" KB, RSS ").append(e.getRss()).append(" KB\n");
        if (e.getDescription() != null) b.append("Description: ").append(e.getDescription()).append('\n');
        if (e.getReason() == ApplicationExitInfo.REASON_CRASH) javaStack(c, e.getTimestamp(), b);
        try (InputStream in = e.getTraceInputStream()) {
            if (in == null) {
                if (e.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE)
                    b.append("Android kept no tombstone for this crash.\n");
                return;
            }
            byte[] data = readAll(in, 8 << 20);
            if (e.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                Tombstone.describe(data, b);
            } else {
                String text = new String(data, StandardCharsets.UTF_8);
                b.append(text, 0, Math.min(text.length(), MAX_TRACE)).append('\n');
            }
        } catch (IOException | RuntimeException ex) {
            b.append("Trace unreadable: ").append(ex).append('\n');
        }
    }

    /** The stack saved within a minute before Android recorded the crash. */
    private static void javaStack(Context c, long at, StringBuilder b) {
        File[] files = javaDir(c).listFiles();
        if (files == null) return;
        for (File f : files) {
            long t;
            try {
                t = Long.parseLong(f.getName().replace(".txt", ""));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (t <= at + 5000 && t >= at - 60000) {
                try {
                    b.append(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // Gone or unreadable; the reason line still stands.
                }
                return;
            }
        }
    }

    /** Apps may read their own log, which outlives the process that wrote it. */
    private static void log(StringBuilder b) {
        b.append("\nRecent log:\n");
        try {
            Process p = Runtime.getRuntime().exec(new String[] {
                    "logcat", "-d", "-v", "threadtime", "-t", String.valueOf(MAX_LOG_LINES)});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String line; (line = r.readLine()) != null; ) b.append(line).append('\n');
            }
            p.destroy();
        } catch (IOException e) {
            b.append("unreadable: ").append(e).append('\n');
        }
    }

    private static byte[] readAll(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0 && out.size() < cap; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String reason(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_CRASH: return "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
            case ApplicationExitInfo.REASON_ANR: return "not responding";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "failed to start";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "excessive resource use";
            case ApplicationExitInfo.REASON_OTHER: return "ended by the system";
            default: return "reason " + r;
        }
    }

    /**
     * Reads the parts of Android's tombstone (a protobuf, from debuggerd's
     * tombstone.proto) that say what went wrong and where: the signal, the
     * abort message, and the crashing thread's backtrace.
     */
    static final class Tombstone {
        private final byte[] b;
        private int p;
        private final int end;
        private int field, wire;

        private Tombstone(byte[] b, int start, int end) {
            this.b = b;
            this.p = start;
            this.end = end;
        }

        private boolean next() {
            if (p >= end) return false;
            long tag = varint();
            field = (int) (tag >>> 3);
            wire = (int) (tag & 7);
            return true;
        }

        private long varint() {
            long v = 0;
            for (int s = 0; p < end && s < 64; s += 7) {
                int x = b[p++] & 0xff;
                v |= (long) (x & 0x7f) << s;
                if (x < 0x80) break;
            }
            return v;
        }

        private Tombstone sub() {
            int n = (int) varint();
            Tombstone t = new Tombstone(b, p, Math.min(end, p + n));
            p += n;
            return t;
        }

        private String string() {
            int n = (int) varint();
            String s = new String(b, p, Math.min(n, end - p), StandardCharsets.UTF_8);
            p += n;
            return s;
        }

        private void skip() {
            switch (wire) {
                case 0: varint(); break;
                case 1: p += 8; break;
                case 2: {
                    // Not "p += varint()": Java would read p before varint() moves it.
                    int n = (int) varint();
                    p += n;
                    break;
                }
                case 5: p += 4; break;
                default: p = end;
            }
        }

        static void describe(byte[] data, StringBuilder out) {
            Tombstone t = new Tombstone(data, 0, data.length);
            long tid = -1;
            String signal = null, abort = null;
            List<String> causes = new ArrayList<>();
            List<Tombstone> threads = new ArrayList<>();
            while (t.next()) {
                if (t.field == 6 && t.wire == 0) tid = t.varint();
                else if (t.field == 10 && t.wire == 2) signal = signal(t.sub());
                else if (t.field == 14 && t.wire == 2) abort = t.string();
                else if (t.field == 15 && t.wire == 2) causes.add(cause(t.sub()));
                else if (t.field == 16 && t.wire == 2) threads.add(t.sub());
                else t.skip();
            }
            if (signal != null) out.append(signal).append('\n');
            if (abort != null && !abort.isEmpty()) out.append("Abort message: ").append(abort).append('\n');
            for (String c : causes) if (!c.isEmpty()) out.append("Cause: ").append(c).append('\n');
            for (Tombstone entry : threads) thread(entry, tid, out);
        }

        private static String signal(Tombstone s) {
            long number = 0, code = 0, fault = 0;
            String name = "", codeName = "";
            boolean hasFault = false;
            while (s.next()) {
                if (s.field == 1 && s.wire == 0) number = s.varint();
                else if (s.field == 2 && s.wire == 2) name = s.string();
                else if (s.field == 3 && s.wire == 0) code = s.varint();
                else if (s.field == 4 && s.wire == 2) codeName = s.string();
                else if (s.field == 8 && s.wire == 0) hasFault = s.varint() != 0;
                else if (s.field == 9 && s.wire == 0) fault = s.varint();
                else s.skip();
            }
            return "Signal " + number + " (" + name + "), code " + code + " (" + codeName + ")"
                    + (hasFault ? ", fault address 0x" + Long.toHexString(fault) : "");
        }

        private static String cause(Tombstone c) {
            while (c.next()) {
                if (c.field == 1 && c.wire == 2) return c.string();
                c.skip();
            }
            return "";
        }

        /** A map entry: key 1 is the thread id, value 2 the thread. Only the crashing one is shown. */
        private static void thread(Tombstone entry, long tid, StringBuilder out) {
            long key = -1;
            Tombstone th = null;
            while (entry.next()) {
                if (entry.field == 1 && entry.wire == 0) key = entry.varint();
                else if (entry.field == 2 && entry.wire == 2) th = entry.sub();
                else entry.skip();
            }
            if (th == null || key != tid) return;
            String name = "";
            List<String> frames = new ArrayList<>();
            while (th.next()) {
                if (th.field == 2 && th.wire == 2) name = th.string();
                else if (th.field == 4 && th.wire == 2) frames.add(frame(th.sub(), frames.size()));
                else th.skip();
            }
            out.append("Thread ").append(key).append(" \"").append(name).append("\":\n");
            for (String f : frames) out.append(f).append('\n');
        }

        private static String frame(Tombstone f, int n) {
            long relPc = 0, offset = 0;
            String function = "", file = "", buildId = "";
            while (f.next()) {
                if (f.field == 1 && f.wire == 0) relPc = f.varint();
                else if (f.field == 4 && f.wire == 2) function = f.string();
                else if (f.field == 5 && f.wire == 0) offset = f.varint();
                else if (f.field == 6 && f.wire == 2) file = f.string();
                else if (f.field == 8 && f.wire == 2) buildId = f.string();
                else f.skip();
            }
            StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "  #%02d pc %016x  %s", n, relPc, file));
            if (!function.isEmpty()) s.append(" (").append(function).append('+').append(offset).append(')');
            if (!buildId.isEmpty()) s.append(" (BuildId: ").append(buildId).append(')');
            return s.toString();
        }
    }
}
