package com.mudb0y.appletizer;

import android.app.Application;
import android.os.Process;

/** Runs first in every process start, the speech engine's included. */
public class AppletizerApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            CrashReports.saveJava(this, t, e);
            if (next != null) {
                next.uncaughtException(t, e);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
        CrashReports.announce(this);
    }
}
