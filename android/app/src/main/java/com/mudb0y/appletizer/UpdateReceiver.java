package com.mudb0y.appletizer;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** Hears how an install went, and greets the new version once Android has replaced this app. */
public class UpdateReceiver extends BroadcastReceiver {
    static final String ACTION_STATUS = "com.mudb0y.appletizer.UPDATE_STATUS";

    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
            replaced(c);
            return;
        }
        if (!ACTION_STATUS.equals(i.getAction())) return;
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            confirm(c, Build.VERSION.SDK_INT >= 33
                    ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : i.getParcelableExtra(Intent.EXTRA_INTENT));
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            UpdateService.failed(c, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }

    /**
     * Android wants the user to approve. Straight onto the screen if they are
     * looking at the updates screen; a background app may not open windows,
     * so otherwise a notification carries it.
     */
    private static void confirm(Context c, Intent screen) {
        if (screen == null) {
            UpdateService.failed(c, null);
            return;
        }
        screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (UpdatesActivity.visible) {
            c.startActivity(screen);
            return;
        }
        Updater.notifications(c).notify(Updater.NOTIFY_RESULT,
                Updater.notice(c, c.getString(R.string.notif_update_confirm))
                        .setContentIntent(PendingIntent.getActivity(c, 2, screen,
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                        .build());
    }

    private static void replaced(Context c) {
        SharedPreferences p = Updater.prefs(c);
        long code = p.getLong("installing", 0);
        p.edit().remove("installing").apply();
        UpdateService.clean(c, null);
        Updater.schedule(c);
        Updater.notifications(c).cancel(Updater.NOTIFY_AVAILABLE);
        // Only for updates this app made; adb and browser installs need no announcement.
        if (code != 0 && code == Updater.installedCode(c))
            Updater.notifications(c).notify(Updater.NOTIFY_RESULT,
                    Updater.notice(c, c.getString(R.string.notif_updated, Updater.installedName(c))).build());
    }
}
