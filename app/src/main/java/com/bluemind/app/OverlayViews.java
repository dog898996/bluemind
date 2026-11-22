package com.bluemind.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns trusted, window-local masks and a non-interactive counter. */
final class OverlayViews {
    interface Actions {
        void choose(String key, float x, float y);
        void scroll(String key, boolean down);
    }

    private final Context context;
    private final WindowManager windows;
    private final Actions actions;
    private final Map<String, Mask> masks = new HashMap<>();
    private TextView counter;
    private Rect counterBounds;
    private String counterStyle = "";
    private boolean bringCounterToFront;

    OverlayViews(Context context, Actions actions) {
        this.context = context;
        this.actions = actions;
        windows = context.getSystemService(WindowManager.class);
    }

    void showMasks(List<ViewingSession.Cover> covers, Rect viewport) {
        Set<String> visible = new HashSet<>();
        for (ViewingSession.Cover cover : covers) {
            Rect rectangle = ScreenSnapshot.bounds(cover.target.bounds);
            if (!rectangle.intersect(viewport) || rectangle.width() < 2 || rectangle.height() < 2) {
                continue;
            }
            visible.add(cover.key);
            Mask mask = masks.get(cover.key);
            if (mask == null) {
                mask = new Mask(cover.key, rectangle, cover.locked);
                if (add(mask, parameters(rectangle, true))) {
                    masks.put(cover.key, mask);
                    bringCounterToFront |= counterBounds != null && Rect.intersects(rectangle, counterBounds);
                }
            } else {
                mask.setLocked(cover.locked);
                if (!mask.bounds.equals(rectangle)) {
                    windows.updateViewLayout(mask, parameters(rectangle, true));
                    mask.bounds.set(rectangle);
                    mask.invalidate();
                }
            }
        }
        Iterator<Map.Entry<String, Mask>> iterator = masks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Mask> previous = iterator.next();
            if (!visible.contains(previous.getKey())) {
                remove(previous.getValue());
                iterator.remove();
            }
        }
    }

    void showCounter(Rect viewport, int count, Integer dailyLimit, MindStore.CounterSize size,
                     MindStore.ColorTheme theme) {
        String text = dailyLimit == null
                ? context.getString(R.string.floating_count_unlimited, count)
                : context.getString(R.string.floating_count, count, dailyLimit);
        String style = size.name() + theme.name();
        if (counter == null) {
            counter = new TextView(context);
            counter.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            counter.setGravity(Gravity.CENTER);
            counter.setSingleLine(true);
            counter.setPadding(dp(11), 0, dp(11), 0);
        }
        if (!text.contentEquals(counter.getText())) {
            counter.setText(text);
            counter.setContentDescription(context.getString(R.string.floating_count_description, count));
        }
        if (!style.equals(counterStyle)) {
            counter.setTextSize(size == MindStore.CounterSize.SMALL ? 12
                    : size == MindStore.CounterSize.LARGE ? 20 : 15);
            counter.setTextColor(switch (theme) {
                case RAIN -> 0xFF91D3FF;
                case DEEP_OCEAN -> 0xFF75E1E0;
                case MOONLIGHT -> 0xFFB7CBFF;
            });
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xEE0B141E);
            background.setCornerRadius(dp(60));
            background.setStroke(dp(1), counter.getCurrentTextColor());
            counter.setBackground(background);
            counterStyle = style;
        }
        int height = dp(size == MindStore.CounterSize.SMALL ? 24
                : size == MindStore.CounterSize.LARGE ? 40 : 32);
        int width = (int) Math.ceil(counter.getPaint().measureText(text))
                + counter.getPaddingLeft() + counter.getPaddingRight();
        int right = viewport.right - dp(12);
        int top = viewport.top + dp(8);
        Rect rectangle = new Rect(Math.max(viewport.left, right - width), top, right, top + height);
        if (counterBounds == null || bringCounterToFront) {
            if (counterBounds != null) {
                remove(counter);
            }
            if (!add(counter, parameters(rectangle, false))) {
                removeCounter();
                return;
            }
        } else if (!counterBounds.equals(rectangle)) {
            windows.updateViewLayout(counter, parameters(rectangle, false));
        }
        counterBounds = rectangle;
        bringCounterToFront = false;
    }

    void removeCounter() {
        if (counter != null) {
            remove(counter);
        }
        counter = null;
        counterBounds = null;
        counterStyle = "";
        bringCounterToFront = false;
    }

    void releaseAt(int x, int y) {
        Iterator<Map.Entry<String, Mask>> iterator = masks.entrySet().iterator();
        while (iterator.hasNext()) {
            Mask mask = iterator.next().getValue();
            if (mask.bounds.contains(x, y)) {
                remove(mask);
                iterator.remove();
            }
        }
    }

    void clear() {
        for (Mask mask : masks.values()) {
            remove(mask);
        }
        masks.clear();
        removeCounter();
    }

    private boolean add(View view, WindowManager.LayoutParams params) {
        try {
            windows.addView(view, params);
            return true;
        } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException exception) {
            Log.w("Bluemind", "The system rejected an accessibility overlay.", exception);
            return false;
        }
    }

    private void remove(View view) {
        if (view.isAttachedToWindow() || view.getParent() != null) {
            try {
                windows.removeViewImmediate(view);
            } catch (IllegalArgumentException detached) {
                // Android may remove the window when revoking the service token.
            }
        }
    }

    @SuppressWarnings("RtlHardcoded") // These are absolute screen coordinates from the Android tree.
    private WindowManager.LayoutParams parameters(Rect rect, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (!touchable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        WindowManager.LayoutParams parameters = new WindowManager.LayoutParams(
                rect.width(), rect.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags, PixelFormat.TRANSLUCENT);
        parameters.gravity = Gravity.TOP | Gravity.LEFT;
        parameters.x = rect.left;
        parameters.y = rect.top;
        if (Build.VERSION.SDK_INT >= 30) {
            parameters.setFitInsetsTypes(0);
            parameters.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        return parameters;
    }

    private int dp(float size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    private final class Mask extends View {
        final String key;
        final Rect bounds;
        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private StaticLayout textLayout;
        private final Drawable drop;
        private final int touchSlop;
        private boolean locked;
        private boolean moved;
        private float downX;
        private float downY;
        private float clickX;
        private float clickY;
        private String label;

        Mask(String key, Rect bounds, boolean locked) {
            super(context);
            this.key = key;
            this.bounds = new Rect(bounds);
            clickX = bounds.exactCenterX();
            clickY = bounds.exactCenterY();
            drop = context.getDrawable(R.drawable.ic_raindrop);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setColor(0xFFF3F8FF);
            setBackgroundColor(Color.BLACK);
            setClickable(true);
            setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setLocked(locked);
        }

        void setLocked(boolean value) {
            if (label != null && locked == value) {
                return;
            }
            locked = value;
            label = context.getString(locked ? R.string.cover_limit : R.string.cover_prompt);
            setContentDescription(label);
            layoutLabel(getWidth(), getHeight());
            invalidate();
        }

        @Override
        public CharSequence getAccessibilityClassName() {
            return android.widget.Button.class.getName();
        }

        @Override
        protected void onSizeChanged(int width, int height, int previousWidth, int previousHeight) {
            layoutLabel(width, height);
        }

        private void layoutLabel(int width, int height) {
            textLayout = null;
            if (label == null || width < dp(58) || height < dp(46)) {
                return;
            }
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                    width < dp(170) ? 11 : 15, getResources().getDisplayMetrics()));
            int layoutWidth = width - dp(10);
            StaticLayout layout = StaticLayout.Builder.obtain(label, 0, label.length(), paint, layoutWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build();
            if (layout.getHeight() + dp(14) <= height) {
                textLayout = layout;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int labelHeight = textLayout == null ? 0 : textLayout.getHeight() + dp(5);
            int size = Math.min(dp(49), Math.min(getWidth() - dp(8), getHeight() - labelHeight - dp(6)));
            if (size < 1) {
                return;
            }
            int imageTop = (getHeight() - size - labelHeight) / 2;
            int imageLeft = (getWidth() - size) / 2;
            if (drop != null) {
                drop.setBounds(imageLeft, imageTop, imageLeft + size, imageTop + size);
                drop.draw(canvas);
            }
            if (textLayout != null) {
                canvas.save();
                canvas.translate((getWidth() - textLayout.getWidth()) / 2f, imageTop + size + dp(5));
                textLayout.draw(canvas);
                canvas.restore();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getRawX();
            float y = event.getRawY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = x;
                    downY = y;
                    moved = false;
                    setPressed(true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    moved |= Math.hypot(x - downX, y - downY) > touchSlop;
                    break;
                case MotionEvent.ACTION_UP:
                    setPressed(false);
                    float dx = x - downX;
                    float dy = y - downY;
                    if (!moved && Math.hypot(dx, dy) <= touchSlop) {
                        clickX = x;
                        clickY = y;
                        performClick();
                    } else if (Math.abs(dy) > Math.max(Math.abs(dx), touchSlop)) {
                        actions.scroll(key, dy < 0);
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    setPressed(false);
                    break;
                default:
                    break;
            }
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            actions.choose(key, clickX, clickY);
            clickX = bounds.exactCenterX();
            clickY = bounds.exactCenterY();
            return true;
        }
    }
}
