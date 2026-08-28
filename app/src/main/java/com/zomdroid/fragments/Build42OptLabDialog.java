package com.zomdroid.fragments;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.zomdroid.OptLabPreferences;
import com.zomdroid.R;

/** Restart-scoped panel containing only bytecode features owned by the Build 42 pack. */
public final class Build42OptLabDialog {
    private Build42OptLabDialog() {}

    public static void show(Context context, Runnable onClosed) {
        OptLabPreferences prefs = OptLabPreferences.from(context);
        int pad = dp(context, 16);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, dp(context, 4), pad, pad);

        TextView intro = text(context, context.getString(R.string.opt_lab_build42_intro), false);
        intro.setPadding(0, 0, 0, dp(context, 10));
        content.addView(intro);

        SwitchCompat onlyBuild42 = toggle(context,
                context.getString(R.string.opt_lab_only_build42), prefs.isOnlyBuild42());
        content.addView(onlyBuild42);

        TextView masterOff = text(context,
                context.getString(R.string.opt_lab_build42_master_off), true);
        masterOff.setPadding(0, dp(context, 4), 0, dp(context, 4));
        content.addView(masterOff);

        addSection(content, context, context.getString(R.string.opt_lab_build42_presets));
        Button streamAll = preset(context, OptLabPreferences.Profile.STREAM_ALL);
        Button fboAll = preset(context, OptLabPreferences.Profile.FBO_ALL);
        Button fullCandidate = preset(context, OptLabPreferences.Profile.FULL_CANDIDATE);
        content.addView(streamAll);
        content.addView(fboAll);
        content.addView(fullCandidate);

        addSection(content, context, context.getString(R.string.opt_lab_runtime_b42_section));
        SwitchCompat pacing = toggle(context, context.getString(R.string.opt_lab_pacing),
                prefs.isMainloopPacing());
        content.addView(pacing);

        addSection(content, context, context.getString(R.string.opt_lab_stream_section));
        SwitchCompat streamWake = toggle(context, context.getString(R.string.opt_lab_stream_wake),
                prefs.isStreamWake());
        SwitchCompat streamQueue = toggle(context,
                context.getString(R.string.opt_lab_stream_queue), prefs.isStreamQueueFast());
        SwitchCompat streamEta = toggle(context, context.getString(R.string.opt_lab_stream_eta),
                prefs.isStreamVelocityEta());
        content.addView(streamWake);
        content.addView(streamQueue);
        content.addView(streamEta);

        addSection(content, context, context.getString(R.string.opt_lab_fbo_section));
        SwitchCompat fboDedup = toggle(context, context.getString(R.string.opt_lab_fbo_dedup),
                prefs.isFboDirtyDedup());
        SwitchCompat fboBudget = toggle(context, context.getString(R.string.opt_lab_fbo_budget),
                prefs.isFboFrameBudget());
        SwitchCompat coordinator = toggle(context,
                context.getString(R.string.opt_lab_stream_fbo_coordinator),
                prefs.isStreamFboCoordinator());
        content.addView(fboDedup);
        content.addView(fboBudget);
        content.addView(coordinator);

        TextView warning = text(context,
                context.getString(R.string.opt_lab_build42_restart_warning), false);
        warning.setPadding(0, dp(context, 12), 0, 0);
        content.addView(warning);

        final boolean[] updating = {false};
        Runnable sync = () -> {
            OptLabPreferences now = OptLabPreferences.from(context);
            updating[0] = true;
            onlyBuild42.setChecked(now.isOnlyBuild42());
            pacing.setChecked(now.isMainloopPacing());
            streamWake.setChecked(now.isStreamWake());
            streamQueue.setChecked(now.isStreamQueueFast());
            streamEta.setChecked(now.isStreamVelocityEta());
            fboDedup.setChecked(now.isFboDirtyDedup());
            fboBudget.setChecked(now.isFboFrameBudget());
            coordinator.setChecked(now.isStreamFboCoordinator());
            boolean enabled = now.isMasterEnabled();
            masterOff.setVisibility(enabled ? TextView.GONE : TextView.VISIBLE);
            pacing.setEnabled(enabled);
            streamWake.setEnabled(enabled);
            streamQueue.setEnabled(enabled);
            streamEta.setEnabled(enabled);
            fboDedup.setEnabled(enabled);
            fboBudget.setEnabled(enabled);
            coordinator.setEnabled(enabled && now.isFboFrameBudget());
            updating[0] = false;
        };

        onlyBuild42.setOnCheckedChangeListener((view, checked) -> {
            if (!updating[0]) prefs.setOnlyBuild42(checked);
        });
        pacing.setOnCheckedChangeListener((view, checked) -> {
            if (!updating[0]) prefs.setMainloopPacing(checked);
        });
        streamWake.setOnCheckedChangeListener((view, checked) -> {
            if (!updating[0]) prefs.setStreamWake(checked);
        });
        streamQueue.setOnCheckedChangeListener((view, checked) -> {
            if (!updating[0]) prefs.setStreamQueueFast(checked);
        });
        streamEta.setOnCheckedChangeListener((view, checked) -> {
            if (!updating[0]) prefs.setStreamVelocityEta(checked);
        });
        fboDedup.setOnCheckedChangeListener((view, checked) -> {
            if (!updating[0]) prefs.setFboDirtyDedup(checked);
        });
        fboBudget.setOnCheckedChangeListener((view, checked) -> {
            if (updating[0]) return;
            prefs.setFboFrameBudget(checked);
            if (!checked && prefs.isStreamFboCoordinator()) {
                prefs.setStreamFboCoordinator(false);
            }
            sync.run();
        });
        coordinator.setOnCheckedChangeListener((view, checked) -> {
            if (!updating[0]) prefs.setStreamFboCoordinator(checked);
        });
        streamAll.setOnClickListener(view -> {
            prefs.setProfile(OptLabPreferences.Profile.STREAM_ALL);
            sync.run();
        });
        fboAll.setOnClickListener(view -> {
            prefs.setProfile(OptLabPreferences.Profile.FBO_ALL);
            sync.run();
        });
        fullCandidate.setOnClickListener(view -> {
            prefs.setProfile(OptLabPreferences.Profile.FULL_CANDIDATE);
            sync.run();
        });

        sync.run();
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        new AlertDialog.Builder(context)
                .setTitle(R.string.opt_lab_build42_title)
                .setView(scroll)
                .setPositiveButton(R.string.dialog_button_ok, null)
                .setOnDismissListener(dialog -> onClosed.run())
                .show();
    }

    private static SwitchCompat toggle(Context context, String label, boolean checked) {
        SwitchCompat toggle = new SwitchCompat(context);
        toggle.setText(label);
        toggle.setChecked(checked);
        toggle.setPadding(0, dp(context, 5), 0, dp(context, 5));
        return toggle;
    }

    private static Button preset(Context context, OptLabPreferences.Profile profile) {
        Button button = new Button(context);
        button.setText(profile.toString());
        return button;
    }

    private static TextView text(Context context, String value, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(13);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private static void addSection(LinearLayout parent, Context context, String value) {
        TextView label = text(context, value, true);
        label.setPadding(0, dp(context, 14), 0, dp(context, 4));
        parent.addView(label);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
