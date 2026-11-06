package com.zerohidz.whatsapppause;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared look of every screen and of the blocker. A screen shows at most one primary button. */
final class Ui {
    static final int BACKGROUND = Color.rgb(10, 20, 27);
    static final int TEXT = Color.rgb(235, 242, 238);
    static final int MUTED = Color.argb(170, 235, 242, 238);
    static final int PRIMARY = Color.rgb(80, 181, 130);
    static final int SECONDARY = Color.rgb(28, 38, 44);
    static final int STROKE = Color.argb(50, 235, 242, 238);
    static final int DESTRUCTIVE = Color.rgb(240, 138, 122);
    private static final int RADIUS_DP = 14;
    private static final int BUTTON_HEIGHT_DP = 52;
    private static final int PAGE_PADDING_DP = 24;

    private Ui() {}
    static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    static GradientDrawable shape(Context context, int fill, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(context, RADIUS_DP));
        shape.setColor(fill);
        if (stroke != Color.TRANSPARENT) shape.setStroke(dp(context, 1), stroke);
        return shape;
    }
    private static Button button(Context context, String label, int fill, int stroke, int textColor, boolean bold) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(textColor);
        button.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        button.setStateListAnimator(null);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40, 235, 242, 238)),
            shape(context, fill, stroke), shape(context, Color.WHITE, Color.TRANSPARENT)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, BUTTON_HEIGHT_DP));
        params.topMargin = dp(context, 12);
        button.setLayoutParams(params);
        return button;
    }
    static Button primary(Context context, String label) { return button(context, label, PRIMARY, Color.TRANSPARENT, BACKGROUND, true); }
    static Button secondary(Context context, String label) { return button(context, label, SECONDARY, STROKE, TEXT, false); }
    static Button destructive(Context context, String label) { return button(context, label, Color.TRANSPARENT, Color.TRANSPARENT, DESTRUCTIVE, false); }

    static TextView text(Context context, String value, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(TEXT);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    static TextView muted(Context context, String value, int size) {
        TextView view = text(context, value, size, false);
        view.setTextColor(MUTED);
        return view;
    }
    static LinearLayout.LayoutParams topMargin(Context context, int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, value);
        return params;
    }
    /** The theme capitalizes dialog buttons, and without a Turkish locale it gets İ wrong. */
    static AlertDialog show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.show();
        for (int which : new int[] {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE})
            if (dialog.getButton(which) != null) dialog.getButton(which).setAllCaps(false);
        return dialog;
    }
    /** Keeps content clear of the bars and, on screens with a text field, of the keyboard. */
    static void applyInsets(View view, int padding) {
        int base = dp(view.getContext(), padding);
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            Insets bars = Build.VERSION.SDK_INT >= 30
                ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime())
                : insets.getSystemWindowInsets();
            target.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom);
            return insets;
        });
    }
    /** A scrolling page as the activity's content; returns the column to fill. */
    static LinearLayout page(Activity activity) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, PAGE_PADDING_DP);
        column.setPadding(padding, padding, padding, padding);
        applyInsets(column, PAGE_PADDING_DP);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.addView(column);
        activity.getWindow().setBackgroundDrawable(new ColorDrawable(BACKGROUND));
        activity.setContentView(scroll);
        return column;
    }
    /** Screen title with a back control, for every screen below the main one. */
    static LinearLayout header(Activity activity, String title) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(activity, "←", 24, false);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Geri");
        back.setOnClickListener(v -> activity.finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        row.addView(text(activity, title, 26, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }
}
