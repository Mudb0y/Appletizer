package com.mudb0y.appletizer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Shows the report before anything leaves the phone, and shares or copies
 * it. Kept apart from the voice list so it still opens when that crashes.
 */
public class CrashReportActivity extends Activity {
    /** Large reports would overflow the share intent; the start holds what matters. */
    private static final int MAX_SHARE = 200 * 1024;
    private String report;
    private TextView body;
    private Button share, copy;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int margin = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(margin, margin, margin, margin);

        int n = CrashReports.count(this);
        TextView intro = new TextView(this);
        intro.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium);
        intro.setText(n == 0 ? getString(R.string.crash_none)
                : getResources().getQuantityString(R.plurals.crash_intro, n, n));
        l.addView(intro);

        share = button(l, R.string.crash_share, v -> share());
        copy = button(l, R.string.crash_copy, v -> copy());
        button(l, R.string.crash_dismiss, v -> {
            CrashReports.markSeen(this);
            finish();
        });

        TextView heading = new TextView(this);
        heading.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Large);
        heading.setText(R.string.crash_report_heading);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        l.addView(heading);
        body = new TextView(this);
        body.setTextIsSelectable(true);
        body.setText(R.string.crash_preparing);
        l.addView(body);

        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        setContentView(sv);
        setTitle(R.string.crash_title);
        share.setEnabled(false);
        copy.setEnabled(false);
        new Thread(() -> {
            String r = CrashReports.report(getApplicationContext());
            runOnUiThread(() -> {
                report = r.length() > MAX_SHARE ? r.substring(0, MAX_SHARE) : r;
                body.setText(report);
                share.setEnabled(true);
                copy.setEnabled(true);
            });
        }).start();
    }

    private Button button(LinearLayout l, int label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(click);
        l.addView(b);
        return b;
    }

    private void share() {
        CrashReports.markSeen(this);
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_subject))
                .putExtra(Intent.EXTRA_TEXT, report), getString(R.string.crash_share)));
    }

    private void copy() {
        CrashReports.markSeen(this);
        getSystemService(ClipboardManager.class).setPrimaryClip(
                ClipData.newPlainText(getString(R.string.crash_subject), report));
        // Android 13 and later announce clipboard copies themselves.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show();
    }
}
