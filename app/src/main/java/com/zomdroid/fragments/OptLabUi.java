package com.zomdroid.fragments;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.zomdroid.R;

/** Theme-derived building blocks for the full-screen OPT-LAB surface. */
final class OptLabUi {
    static final class Page {
        final ScrollView root;
        final LinearLayout content;

        Page(ScrollView root, LinearLayout content) {
            this.root = root;
            this.content = content;
        }
    }

    static final class NavCard {
        final MaterialCardView root;
        final TextView subtitle;

        NavCard(MaterialCardView root, TextView subtitle) {
            this.root = root;
            this.subtitle = subtitle;
        }
    }

    static final class ToggleCard {
        final MaterialCardView root;
        final TextView subtitle;
        final SwitchCompat toggle;

        ToggleCard(MaterialCardView root, TextView subtitle, SwitchCompat toggle) {
            this.root = root;
            this.subtitle = subtitle;
            this.toggle = toggle;
        }

        void setEnabled(boolean enabled) {
            root.setEnabled(enabled);
            toggle.setEnabled(enabled);
            root.setAlpha(enabled ? 1f : 0.55f);
        }
    }

    static final class SpinnerCard<T> {
        final MaterialCardView root;
        final Spinner spinner;
        final ArrayAdapter<T> adapter;

        SpinnerCard(MaterialCardView root, Spinner spinner, ArrayAdapter<T> adapter) {
            this.root = root;
            this.spinner = spinner;
            this.adapter = adapter;
        }

        void setEnabled(boolean enabled) {
            root.setEnabled(enabled);
            spinner.setEnabled(enabled);
            root.setAlpha(enabled ? 1f : 0.55f);
        }
    }

    private OptLabUi() {}

    static Page page(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 16);
        content.setPadding(pad, dp(context, 16), pad, dp(context, 28));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        return new Page(scroll, content);
    }

    static NavCard navCard(Context context, @DrawableRes int icon, String title,
                           String subtitle, View.OnClickListener listener) {
        MaterialCardView card = card(context);
        boolean navigates = listener != null;
        card.setClickable(navigates);
        card.setFocusable(navigates);
        if (navigates) card.setOnClickListener(listener);

        LinearLayout row = horizontalRow(context, 16, 14);
        row.setMinimumHeight(dp(context, 92));
        row.addView(icon(context, icon), new LinearLayout.LayoutParams(
                dp(context, 54), dp(context, 54)));

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        copyParams.setMargins(dp(context, 16), 0, dp(context, 10), 0);
        row.addView(copy, copyParams);
        copy.addView(title(context, title));
        TextView secondary = body(context, subtitle);
        secondary.setPadding(0, dp(context, 3), 0, 0);
        copy.addView(secondary);

        if (navigates) {
            ImageView arrow = new ImageView(context);
            arrow.setImageResource(R.drawable.optlab_ic_chevron);
            arrow.setColorFilter(color(context,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            row.addView(arrow, new LinearLayout.LayoutParams(
                    dp(context, 24), dp(context, 24)));
        }
        card.addView(row);
        return new NavCard(card, secondary);
    }

    static ToggleCard toggleCard(Context context, String title, String subtitle) {
        return toggleCard(context, 0, title, subtitle, false);
    }

    static ToggleCard toggleCard(Context context, @DrawableRes int icon, String title,
                                 String subtitle, boolean emphasized) {
        MaterialCardView card = card(context);
        if (emphasized) {
            card.setStrokeColor(color(context, com.google.android.material.R.attr.colorPrimary));
            card.setStrokeWidth(dp(context, 2));
        }
        LinearLayout row = horizontalRow(context, 16, 14);
        row.setMinimumHeight(dp(context, icon == 0 ? 72 : 92));
        if (icon != 0) {
            row.addView(icon(context, icon), new LinearLayout.LayoutParams(
                    dp(context, 54), dp(context, 54)));
        }
        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        copyParams.setMargins(icon == 0 ? 0 : dp(context, 16), 0, dp(context, 8), 0);
        row.addView(copy, copyParams);
        copy.addView(title(context, title));
        TextView secondary = body(context, subtitle);
        if (!subtitle.isEmpty()) {
            secondary.setPadding(0, dp(context, 3), 0, 0);
            copy.addView(secondary);
        }

        SwitchCompat toggle = new SwitchCompat(context);
        toggle.setShowText(false);
        row.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(row);
        card.setOnClickListener(view -> {
            if (toggle.isEnabled()) toggle.toggle();
        });
        return new ToggleCard(card, secondary, toggle);
    }

    static <T> SpinnerCard<T> spinnerCard(Context context, @DrawableRes int icon,
                                           String label, T[] values) {
        MaterialCardView card = card(context);
        LinearLayout row = horizontalRow(context, 16, 14);
        row.setMinimumHeight(dp(context, 104));
        row.addView(icon(context, icon), new LinearLayout.LayoutParams(
                dp(context, 54), dp(context, 54)));

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        copyParams.setMargins(dp(context, 16), 0, 0, 0);
        row.addView(copy, copyParams);
        TextView caption = body(context, label);
        copy.addView(caption);

        Spinner spinner = new Spinner(context);
        ArrayAdapter<T> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        copy.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(row);
        return new SpinnerCard<>(card, spinner, adapter);
    }

    static MaterialButton actionButton(Context context, String text) {
        MaterialButton button = new MaterialButton(context);
        button.setText(text);
        button.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(context, 8));
        button.setLayoutParams(params);
        return button;
    }

    static void addCard(LinearLayout parent, View card) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(parent.getContext(), 12));
        parent.addView(card, params);
    }

    static TextView section(Context context, String value) {
        TextView view = body(context, value);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(color(context, com.google.android.material.R.attr.colorPrimary));
        view.setPadding(dp(context, 2), dp(context, 12), 0, dp(context, 8));
        return view;
    }

    static TextView note(Context context, String value) {
        TextView view = body(context, value);
        view.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 14));
        return view;
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static MaterialCardView card(Context context) {
        MaterialCardView card = new MaterialCardView(context);
        card.setRadius(dp(context, 16));
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(context, 1));
        card.setStrokeColor(color(context,
                com.google.android.material.R.attr.colorOutlineVariant));
        card.setCardBackgroundColor(color(context,
                com.google.android.material.R.attr.colorSurfaceContainerLow));
        return card;
    }

    private static LinearLayout horizontalRow(Context context, int horizontal, int vertical) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, horizontal), dp(context, vertical),
                dp(context, horizontal), dp(context, vertical));
        return row;
    }

    private static FrameLayout icon(Context context, @DrawableRes int drawable) {
        FrameLayout frame = new FrameLayout(context);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color(context,
                com.google.android.material.R.attr.colorSurfaceContainerLowest));
        circle.setStroke(dp(context, 1),
                color(context, com.google.android.material.R.attr.colorPrimary));
        frame.setBackground(circle);

        ImageView image = new ImageView(context);
        image.setImageDrawable(ContextCompat.getDrawable(context, drawable));
        image.setColorFilter(color(context, com.google.android.material.R.attr.colorPrimary));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(context, 26), dp(context, 26), Gravity.CENTER);
        frame.addView(image, params);
        return frame;
    }

    private static TextView title(Context context, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(color(context, com.google.android.material.R.attr.colorOnSurface));
        return view;
    }

    private static TextView body(Context context, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(color(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        return view;
    }

    private static int color(Context context, int attribute) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attribute, value, true)) {
            throw new IllegalStateException("Missing theme color attribute " + attribute);
        }
        return value.resourceId != 0
                ? ContextCompat.getColor(context, value.resourceId) : value.data;
    }
}
