package com.mudb0y.appletizer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** The installed version, what the chosen channel offers, and the choices around updating. */
public class UpdatesActivity extends Activity {
    static final String EXTRA_INSTALL = "install";
    /** Lets UpdateReceiver put Android's confirmation screen straight up. */
    static volatile boolean visible;

    private TextView status, changesTitle, changes;
    private Button action, check;
    private RadioButton dev;
    private boolean installAfterPermission;
    private int margin;
    private final Runnable refresh = () -> runOnUiThread(this::fill);

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        installAfterPermission = saved != null && saved.getBoolean("installAfterPermission");
        margin = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(margin, margin, margin, margin);

        status = text(l, android.R.style.TextAppearance_DeviceDefault_Medium);
        // Read out as the check or the download moves along; the percentage only moves in tens.
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        action = new Button(this);
        action.setOnClickListener(v -> {
            if (UpdateService.phase == UpdateService.Phase.DOWNLOADING) cancel();
            else install();
        });
        add(l, action);
        check = new Button(this);
        check.setText(R.string.update_check);
        check.setOnClickListener(v -> Updater.check(this, false, null));
        add(l, check);

        heading(l).setText(R.string.update_channel);
        RadioGroup group = new RadioGroup(this);
        RadioButton stable = radio(group, R.string.channel_stable);
        dev = radio(group, R.string.channel_dev);
        (Updater.DEV.equals(Updater.channel(this)) ? dev : stable).setChecked(true);
        group.setOnCheckedChangeListener((g, id) ->
                Updater.setChannel(this, id == dev.getId() ? Updater.DEV : Updater.STABLE));
        add(l, group);
        text(l, 0).setText(R.string.channel_note);

        Switch auto = new Switch(this);
        auto.setText(R.string.update_auto);
        auto.setChecked(Updater.automatic(this));
        auto.setOnCheckedChangeListener((b, on) -> Updater.setAutomatic(this, on));
        add(l, auto);

        changesTitle = heading(l);
        changes = text(l, 0);

        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        setContentView(sv);
        if (saved == null && getIntent().getBooleanExtra(EXTRA_INSTALL, false)) install();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        Updater.addListener(refresh);
        fill();
        if (installAfterPermission) {
            installAfterPermission = false;
            if (getPackageManager().canRequestPackageInstalls()) install();
        }
    }

    @Override
    protected void onPause() {
        visible = false;
        Updater.removeListener(refresh);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("installAfterPermission", installAfterPermission);
    }

    private void add(LinearLayout l, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = margin / 2;
        l.addView(v, lp);
    }

    private TextView text(LinearLayout l, int appearance) {
        TextView t = new TextView(this);
        if (appearance != 0) t.setTextAppearance(appearance);
        add(l, t);
        return t;
    }

    private TextView heading(LinearLayout l) {
        TextView t = text(l, android.R.style.TextAppearance_DeviceDefault_Large);
        if (Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        return t;
    }

    private RadioButton radio(RadioGroup g, int label) {
        RadioButton b = new RadioButton(this);
        b.setId(View.generateViewId());
        b.setText(label);
        g.addView(b);
        return b;
    }

    private void fill() {
        String channel = getString(dev.isChecked() ? R.string.channel_dev_name : R.string.channel_stable_name);
        Updater.Release r = Updater.latest(this);
        Updater.Status st = Updater.status(this);
        UpdateService.Phase phase = UpdateService.phase;
        StringBuilder b = new StringBuilder(getString(R.string.update_version, Updater.installedName(this))).append(' ');
        if (phase == UpdateService.Phase.DOWNLOADING) {
            b.append(getString(R.string.update_downloading, UpdateService.target, UpdateService.percent / 10 * 10));
        } else if (phase == UpdateService.Phase.INSTALLING) {
            b.append(getString(R.string.update_installing, UpdateService.target));
        } else {
            if (phase == UpdateService.Phase.FAILED)
                b.append(getString(R.string.update_failed, UpdateService.error)).append(' ');
            switch (st) {
                case CHECKING: b.append(getString(R.string.update_checking, channel)); break;
                case CURRENT: b.append(getString(R.string.update_current, channel)); break;
                case AVAILABLE: b.append(getString(R.string.update_available, r.name, channel, r.megabytes())); break;
                case AHEAD: b.append(getString(R.string.update_ahead, channel, r.name)); break;
                case NONE: b.append(getString(R.string.update_none, channel)); break;
                case FAILED: b.append(getString(R.string.update_check_failed, channel, Updater.error(this))); break;
                default: b.append(getString(R.string.update_unknown, channel));
            }
            long at = Updater.checkedAt(this), now = System.currentTimeMillis();
            if (at > 0 && st != Updater.Status.CHECKING && st != Updater.Status.UNKNOWN)
                b.append(' ').append(now - at < DateUtils.MINUTE_IN_MILLIS ? getString(R.string.update_checked_now)
                        : getString(R.string.update_checked,
                                DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS)));
        }
        // Setting the same text again would make TalkBack read it again.
        if (!TextUtils.equals(status.getText(), b)) status.setText(b);

        if (phase == UpdateService.Phase.DOWNLOADING) {
            action.setVisibility(View.VISIBLE);
            action.setText(R.string.update_cancel);
        } else if (phase != UpdateService.Phase.INSTALLING && st == Updater.Status.AVAILABLE) {
            action.setVisibility(View.VISIBLE);
            action.setText(getString(R.string.update_install, r.name, r.megabytes()));
        } else {
            action.setVisibility(View.GONE);
        }
        check.setEnabled(st != Updater.Status.CHECKING && phase != UpdateService.Phase.DOWNLOADING
                && phase != UpdateService.Phase.INSTALLING);

        boolean notes = r != null && r.notes.length > 0;
        changesTitle.setVisibility(notes ? View.VISIBLE : View.GONE);
        changes.setVisibility(notes ? View.VISIBLE : View.GONE);
        if (notes) {
            changesTitle.setText(getString(R.string.update_changes, r.name));
            changes.setText(TextUtils.join("\n", r.notes));
        }
    }

    private void install() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.permission_title)
                    .setMessage(R.string.permission_message)
                    .setPositiveButton(R.string.permission_continue, (d, w) -> {
                        installAfterPermission = true;
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        if (Updater.status(this) != Updater.Status.AVAILABLE) return;
        Updater.notifications(this).cancel(Updater.NOTIFY_AVAILABLE);
        Updater.notifications(this).cancel(Updater.NOTIFY_RESULT);
        startForegroundService(new Intent(this, UpdateService.class));
    }

    private void cancel() {
        startService(new Intent(this, UpdateService.class).setAction(UpdateService.ACTION_CANCEL));
    }
}
