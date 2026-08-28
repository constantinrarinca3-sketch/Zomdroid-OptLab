package com.zomdroid.fragments;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.zomdroid.NativeModulesPreferences;
import com.zomdroid.R;

/** Separate restart-scoped panel for ARM64 game modules. */
public final class NativeModulesDialog {
    private NativeModulesDialog() {}

    public static void show(Context context, Runnable onClosed) {
        NativeModulesPreferences prefs = NativeModulesPreferences.from(context);
        int pad = dp(context, 16);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, dp(context, 4), pad, pad);

        TextView intro = text(context, context.getString(R.string.native_modules_intro), false);
        intro.setPadding(0, 0, 0, dp(context, 10));
        content.addView(intro);

        SwitchCompat lighting = toggle(context,
                context.getString(R.string.native_modules_lighting),
                prefs.isLighting64Enabled());
        SwitchCompat clipper = toggle(context,
                context.getString(R.string.native_modules_clipper),
                prefs.isPzClipperEnabled());
        SwitchCompat pathfinding = toggle(context,
                context.getString(R.string.native_modules_pathfinding),
                prefs.isPathfindingEnabled());
        SwitchCompat popMan = toggle(context,
                context.getString(R.string.native_modules_popman), false);
        popMan.setEnabled(false);
        content.addView(lighting);
        content.addView(clipper);
        content.addView(pathfinding);
        content.addView(popMan);

        TextView unavailable = text(context,
                context.getString(R.string.native_modules_popman_unavailable), true);
        unavailable.setPadding(0, dp(context, 2), 0, dp(context, 8));
        content.addView(unavailable);

        TextView restart = text(context,
                context.getString(R.string.native_modules_restart), false);
        restart.setPadding(0, dp(context, 10), 0, 0);
        content.addView(restart);

        lighting.setOnCheckedChangeListener((view, checked) ->
                prefs.setLighting64Enabled(checked));
        clipper.setOnCheckedChangeListener((view, checked) ->
                prefs.setPzClipperEnabled(checked));
        pathfinding.setOnCheckedChangeListener((view, checked) ->
                prefs.setPathfindingEnabled(checked));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        new AlertDialog.Builder(context)
                .setTitle(R.string.native_modules_title)
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

    private static TextView text(Context context, String value, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(13);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
