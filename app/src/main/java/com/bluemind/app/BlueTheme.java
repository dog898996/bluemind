package com.bluemind.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class BlueTheme {
    final int background;
    final int panel;
    final int field;
    final int outline;
    final int accent;
    final int paleBlue;
    final int ink;
    final int muted;
    final int reminder;
    final int heroStart;
    final int heroEnd;
    final int heroGlow;
    final boolean light;

    private final Context context;

    BlueTheme(Context context, MindStore.ColorTheme theme) {
        this.context = context;
        light = theme == MindStore.ColorTheme.MOONLIGHT;
        switch (theme) {
            case MOONLIGHT -> {
                background = 0xFFEEF3FC;
                panel = 0xFFFAFCFF;
                field = 0xFFE6EEFB;
                outline = 0xFFD1DDF0;
                accent = 0xFF244D98;
                paleBlue = 0xFF285399;
                ink = 0xFF132641;
                muted = 0xFF475D7D;
                reminder = 0xFF874614;
                heroStart = 0xFFF9FCFF;
                heroEnd = 0xFFD9E6FA;
                heroGlow = 0x765FAEFA;
            }
            case DEEP_OCEAN -> {
                background = 0xFF071C25;
                panel = 0xFF102835;
                field = 0xFF183845;
                outline = 0xFF234752;
                accent = 0xFF75E1E0;
                paleBlue = 0xFFC2F5EF;
                ink = 0xFFEAFBFA;
                muted = 0xFFA4CCC9;
                reminder = 0xFFF2C889;
                heroStart = 0xFF153845;
                heroEnd = 0xFF0C2B36;
                heroGlow = 0x7850CABC;
            }
            default -> {
                background = 0xFF0B1420;
                panel = 0xFF121F2E;
                field = 0xFF1A2C3E;
                outline = 0xFF263B50;
                accent = 0xFF71C7FF;
                paleBlue = 0xFFBAE2FF;
                ink = 0xFFEDF5FD;
                muted = 0xFFA7BED4;
                reminder = 0xFFF3BB76;
                heroStart = 0xFF142B42;
                heroEnd = 0xFF132131;
                heroGlow = 0x684895E0;
            }
        }
    }

    int dp(float size) {
        return Math.round(context.getResources().getDisplayMetrics().density * size);
    }

    static void updateText(TextView view, String text) {
        if (!text.contentEquals(view.getText())) {
            view.setText(text);
        }
    }

    TextView text(CharSequence label, float size, int color) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return view;
    }

    TextView title(CharSequence label, float size, int color) {
        TextView view = text(label, size, color);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    Drawable rounded(int color, float radius, int outline) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (outline != Color.TRANSPARENT) {
            shape.setStroke(dp(1), outline);
        }
        return shape;
    }

    Drawable ripple(int color, float radius, int outline) {
        return new RippleDrawable(
                ColorStateList.valueOf(0x3083C5FF),
                rounded(color, radius, outline),
                rounded(Color.WHITE, radius, Color.TRANSPARENT));
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    LinearLayout panel() {
        LinearLayout layout = column();
        layout.setPadding(dp(17), dp(15), dp(17), dp(15));
        layout.setBackground(rounded(panel, 19, outline));
        return layout;
    }

    void pad(View view, int horizontal, int vertical) {
        view.setPadding(dp(horizontal), dp(vertical), dp(horizontal), dp(vertical));
    }

    void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        parent.addView(child, params);
    }

    void weight(LinearLayout parent, View child) {
        parent.addView(child, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    TextView action(String label, boolean primary, Runnable click) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(primary ? background : paleBlue);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setStateListAnimator(null);
        pad(button, 12, 5);
        button.setBackground(ripple(primary ? accent : field, 12,
                primary ? Color.TRANSPARENT : outline));
        button.setOnClickListener(view -> click.run());
        return button;
    }
}
