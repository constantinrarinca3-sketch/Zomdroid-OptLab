package com.zomdroid.fragments;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.zomdroid.OptLabPreferences;
import com.zomdroid.R;

/** Compact runtime-built UI so every lab switch lives in one restart-scoped panel. */
public final class OptLabDialog {
    private OptLabDialog() {}

    public static void show(Context context, Runnable onClosed) {
        OptLabPreferences prefs = OptLabPreferences.from(context);
        int pad = dp(context, 16);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, dp(context, 4), pad, pad);

        TextView intro = text(context,
                context.getString(R.string.opt_lab_intro), false);
        intro.setPadding(0, 0, 0, dp(context, 12));
        content.addView(intro);

        addLabel(content, context, context.getString(R.string.opt_lab_profile));
        Spinner profile = spinner(context, OptLabPreferences.Profile.values(), prefs.getProfile());
        content.addView(profile);

        SwitchCompat master = toggle(context, context.getString(R.string.opt_lab_master),
                prefs.isMasterEnabled());
        content.addView(master);

        addSection(content, context, context.getString(R.string.opt_lab_runtime_section));
        SwitchCompat quiet = toggle(context, context.getString(R.string.opt_lab_quiet),
                prefs.isQuietRuntime());
        SwitchCompat buffered = toggle(context, context.getString(R.string.opt_lab_buffered_stdio),
                prefs.getStdioMode() == OptLabPreferences.StdioMode.BUFFERED);
        SwitchCompat pacing = toggle(context, context.getString(R.string.opt_lab_pacing),
                prefs.isMainloopPacing());
        SwitchCompat sqlite = toggle(context, context.getString(R.string.opt_lab_sqlite),
                prefs.isSqliteAndroidNative());
        content.addView(quiet);
        content.addView(buffered);
        content.addView(pacing);
        content.addView(sqlite);

        addSection(content, context, context.getString(R.string.opt_lab_stream_section));
        SwitchCompat streamWake = toggle(context, context.getString(R.string.opt_lab_stream_wake),
                prefs.isStreamWake());
        SwitchCompat streamQueue = toggle(context, context.getString(R.string.opt_lab_stream_queue),
                prefs.isStreamQueueFast());
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

        addLabel(content, context, context.getString(R.string.opt_lab_box64));
        Spinner box64 = spinner(context, OptLabPreferences.Box64Policy.values(),
                prefs.getBox64Policy());
        content.addView(box64);

        addSection(content, context, context.getString(R.string.opt_lab_android_section));
        SwitchCompat surface = toggle(context, context.getString(R.string.opt_lab_surface),
                prefs.getSurfaceMode() == OptLabPreferences.SurfaceMode.GEN_ACK);
        SwitchCompat refresh = toggle(context, context.getString(R.string.opt_lab_refresh),
                prefs.getDisplayFpsHint() == OptLabPreferences.DisplayFpsHint.NATIVE_REFRESH);
        SwitchCompat input = toggle(context, context.getString(R.string.opt_lab_input_queue),
                prefs.getInputQueueMode() == OptLabPreferences.InputQueueMode.MUTEX_SAFE);
        SwitchCompat analog = toggle(context, context.getString(R.string.opt_lab_analog),
                prefs.isAnalogFilter());
        SwitchCompat coalesce = toggle(context, context.getString(R.string.opt_lab_coalesce),
                prefs.isInputCoalesce());
        SwitchCompat mglLog = toggle(context, context.getString(R.string.opt_lab_mgl_log),
                prefs.isMobileGlFileLogEnabled());
        content.addView(surface);
        content.addView(refresh);
        content.addView(input);
        content.addView(analog);
        content.addView(coalesce);
        content.addView(mglLog);

        TextView warning = text(context, context.getString(R.string.opt_lab_restart_warning), false);
        warning.setPadding(0, dp(context, 12), 0, 0);
        content.addView(warning);

        final boolean[] updating = {false};
        final boolean[] suppressProfileCallback = {false};
        Runnable sync = () -> {
            OptLabPreferences now = OptLabPreferences.from(context);
            int effectiveProfile = now.getProfile().ordinal();
            if (profile.getSelectedItemPosition() != effectiveProfile) {
                suppressProfileCallback[0] = true;
                profile.setSelection(effectiveProfile, false);
            }
            master.setChecked(now.isMasterEnabled());
            quiet.setChecked(now.isQuietRuntime());
            buffered.setChecked(now.getStdioMode() == OptLabPreferences.StdioMode.BUFFERED);
            pacing.setChecked(now.isMainloopPacing());
            sqlite.setChecked(now.isSqliteAndroidNative());
            streamWake.setChecked(now.isStreamWake());
            streamQueue.setChecked(now.isStreamQueueFast());
            streamEta.setChecked(now.isStreamVelocityEta());
            fboDedup.setChecked(now.isFboDirtyDedup());
            fboBudget.setChecked(now.isFboFrameBudget());
            coordinator.setChecked(now.isStreamFboCoordinator());
            box64.setSelection(now.getBox64Policy().ordinal());
            surface.setChecked(now.getSurfaceMode() == OptLabPreferences.SurfaceMode.GEN_ACK);
            refresh.setChecked(now.getDisplayFpsHint() == OptLabPreferences.DisplayFpsHint.NATIVE_REFRESH);
            input.setChecked(now.getInputQueueMode() == OptLabPreferences.InputQueueMode.MUTEX_SAFE);
            analog.setChecked(now.isAnalogFilter());
            coalesce.setChecked(now.isInputCoalesce());
            mglLog.setChecked(now.isMobileGlFileLogEnabled());
            boolean enabled = now.isMasterEnabled();
            quiet.setEnabled(enabled);
            buffered.setEnabled(enabled);
            pacing.setEnabled(enabled);
            sqlite.setEnabled(enabled);
            streamWake.setEnabled(enabled);
            streamQueue.setEnabled(enabled);
            streamEta.setEnabled(enabled);
            fboDedup.setEnabled(enabled);
            fboBudget.setEnabled(enabled);
            coordinator.setEnabled(enabled);
            box64.setEnabled(enabled);
            surface.setEnabled(enabled);
            refresh.setEnabled(enabled);
            input.setEnabled(enabled);
            analog.setEnabled(enabled);
            coalesce.setEnabled(enabled);
            mglLog.setEnabled(enabled);
        };

        final boolean[] firstProfileCallback = {true};
        Runnable showCustomProfile = () -> {
            int custom = OptLabPreferences.Profile.CUSTOM.ordinal();
            if (profile.getSelectedItemPosition() != custom) {
                suppressProfileCallback[0] = true;
                profile.setSelection(custom, false);
            }
        };
        profile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (firstProfileCallback[0]) { firstProfileCallback[0] = false; return; }
                if (suppressProfileCallback[0]) { suppressProfileCallback[0] = false; return; }
                if (updating[0]) return;
                updating[0] = true;
                prefs.setProfile((OptLabPreferences.Profile) parent.getItemAtPosition(position));
                sync.run();
                updating[0] = false;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        master.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) {
                prefs.setMasterEnabled(checked);
                updating[0] = true;
                sync.run();
                updating[0] = false;
                if (checked) showCustomProfile.run();
            }
        });
        quiet.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) { prefs.setQuietRuntime(checked); showCustomProfile.run(); }
        });
        buffered.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) {
                prefs.setStdioMode(checked ? OptLabPreferences.StdioMode.BUFFERED
                        : OptLabPreferences.StdioMode.LEGACY);
                showCustomProfile.run();
            }
        });
        pacing.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) { prefs.setMainloopPacing(checked); showCustomProfile.run(); }
        });
        sqlite.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) { prefs.setSqliteAndroidNative(checked); showCustomProfile.run(); }
        });
        streamWake.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) { prefs.setStreamWake(checked); showCustomProfile.run(); }
        });
        streamQueue.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) { prefs.setStreamQueueFast(checked); showCustomProfile.run(); }
        });
        streamEta.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) { prefs.setStreamVelocityEta(checked); showCustomProfile.run(); }
        });
        fboDedup.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) { prefs.setFboDirtyDedup(checked); showCustomProfile.run(); }
        });
        fboBudget.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) { prefs.setFboFrameBudget(checked); showCustomProfile.run(); }
        });
        coordinator.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) {
                prefs.setStreamFboCoordinator(checked);
                showCustomProfile.run();
            }
        });
        box64.setOnItemSelectedListener(enumListener(updating,
                value -> prefs.setBox64Policy((OptLabPreferences.Box64Policy) value),
                showCustomProfile));
        surface.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) {
                prefs.setSurfaceMode(checked ? OptLabPreferences.SurfaceMode.GEN_ACK
                        : OptLabPreferences.SurfaceMode.LEGACY);
                showCustomProfile.run();
            }
        });
        refresh.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) {
                prefs.setDisplayFpsHint(checked ? OptLabPreferences.DisplayFpsHint.NATIVE_REFRESH
                        : OptLabPreferences.DisplayFpsHint.OFF);
                showCustomProfile.run();
            }
        });
        input.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) {
                prefs.setInputQueueMode(checked ? OptLabPreferences.InputQueueMode.MUTEX_SAFE
                        : OptLabPreferences.InputQueueMode.LEGACY);
                showCustomProfile.run();
            }
        });
        analog.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) { prefs.setAnalogFilter(checked); showCustomProfile.run(); }
        });
        coalesce.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) { prefs.setInputCoalesce(checked); showCustomProfile.run(); }
        });
        mglLog.setOnCheckedChangeListener((v, checked) -> {
            if (!updating[0]) { prefs.setMobileGlFileLogEnabled(checked); showCustomProfile.run(); }
        });

        updating[0] = true;
        sync.run();
        updating[0] = false;

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        new AlertDialog.Builder(context)
                .setTitle(R.string.opt_lab_title)
                .setView(scroll)
                .setPositiveButton(R.string.dialog_button_ok, null)
                .setOnDismissListener(dialog -> onClosed.run())
                .show();
    }

    private interface ValueConsumer { void accept(Object value); }

    private static AdapterView.OnItemSelectedListener enumListener(boolean[] updating,
                                                                    ValueConsumer consumer,
                                                                    Runnable afterChange) {
        final boolean[] first = {true};
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (first[0]) { first[0] = false; return; }
                if (!updating[0]) {
                    consumer.accept(parent.getItemAtPosition(position));
                    afterChange.run();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
    }

    private static <T> Spinner spinner(Context context, T[] values, T selected) {
        Spinner spinner = new Spinner(context);
        ArrayAdapter<T> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(adapter.getPosition(selected));
        spinner.setPadding(0, 0, 0, dp(context, 8));
        return spinner;
    }

    private static SwitchCompat toggle(Context context, String label, boolean checked) {
        SwitchCompat toggle = new SwitchCompat(context);
        toggle.setText(label);
        toggle.setChecked(checked);
        toggle.setPadding(0, dp(context, 5), 0, dp(context, 5));
        return toggle;
    }

    private static TextView text(Context context, String value, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(13);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private static void addLabel(LinearLayout parent, Context context, String value) {
        TextView label = text(context, value, true);
        label.setPadding(0, dp(context, 8), 0, dp(context, 2));
        parent.addView(label);
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
