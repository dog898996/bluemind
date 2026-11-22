package com.bluemind.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import java.util.Arrays;

final class HistoryChartView extends View {
    private final BlueTheme style;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] counts = new int[24];
    private int selected = -1;
    private boolean week;
    private float downX;
    private float downY;

    HistoryChartView(Context context, BlueTheme style) {
        super(context);
        this.style = style;
        setFocusable(true);
    }

    void bind(int[] newCounts, boolean week) {
        if (this.week != week || counts.length != newCounts.length) {
            selected = -1;
        }
        this.week = week;
        counts = newCounts;
        setContentDescription("기간별 열람 그래프, 합계 " + Arrays.stream(counts).sum() + "회");
        invalidate();
    }

    void clearSelection() {
        selected = -1;
        invalidate();
    }

    private float chartLeft() {
        return style.dp(33);
    }

    private float chartRight() {
        return getWidth() - style.dp(8);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = chartLeft();
        float right = chartRight();
        float top = style.dp(29);
        float bottom = getHeight() - style.dp(31);
        if (right <= left || bottom <= top) {
            return;
        }

        int max = Arrays.stream(counts).max().orElse(0);
        int unit = max >= 20 ? 5 : 1;
        int middle = Math.max(1, (max + 2 * unit - 1) / (2 * unit)) * unit;
        int ceiling = middle * 2;
        paint.setTextSize(style.dp(11));
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setStrokeWidth(style.dp(1));
        for (int i = 0; i <= 2; i++) {
            float y = bottom - (bottom - top) * i / 2;
            paint.setColor(style.outline);
            canvas.drawLine(left, y, right, y, paint);
            paint.setColor(style.muted);
            canvas.drawText(String.valueOf(i * middle), left - style.dp(6), y + style.dp(4), paint);
        }

        float column = (right - left) / counts.length;
        float barWidth = Math.min(style.dp(23), Math.max(style.dp(3), column * .67f));
        paint.setShader(new LinearGradient(0, top, 0, bottom,
                style.accent, style.paleBlue, Shader.TileMode.CLAMP));
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) {
                continue;
            }
            float x = left + (i + .5f) * column;
            float start = bottom - Math.max(
                    style.dp(4), (bottom - top) * counts[i] / ceiling);
            canvas.drawRoundRect(x - barWidth / 2, start, x + barWidth / 2, bottom,
                    style.dp(4), style.dp(4), paint);
        }
        paint.setShader(null);

        paint.setColor(style.muted);
        paint.setTextAlign(Paint.Align.CENTER);
        if (week) {
            String[] days = {"월", "화", "수", "목", "금", "토", "일"};
            for (int i = 0; i < days.length; i++) {
                canvas.drawText(days[i], left + (i + .5f) * column, bottom + style.dp(21), paint);
            }
        } else {
            for (int hour = 0; hour < 24; hour += 6) {
                canvas.drawText(hour + "시", left + (hour + .5f) * column,
                        bottom + style.dp(21), paint);
            }
            canvas.drawText("24시", right - style.dp(10), bottom + style.dp(21), paint);
        }

        if (selected >= 0 && selected < counts.length) {
            int value = counts[selected];
            float x = left + (selected + .5f) * column;
            float y = bottom - (bottom - top) * value / ceiling;
            paint.setColor(style.ink);
            canvas.drawCircle(x, y, style.dp(4), paint);
            paint.setTextSize(style.dp(13));
            canvas.drawText(value + "회", x, y - style.dp(9), paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (Math.abs(event.getY() - downY) < style.dp(10)
                        && Math.abs(event.getX() - downX) < style.dp(10)
                        && chartRight() > chartLeft()) {
                    float position = (event.getX() - chartLeft()) / (chartRight() - chartLeft());
                    selected = Math.max(0, Math.min(counts.length - 1, (int) (position * counts.length)));
                    announceForAccessibility(selectedLabel());
                    performClick();
                    invalidate();
                }
                return true;
            default:
                return true;
        }
    }

    private String selectedLabel() {
        String time = week
                ? new String[]{"월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"}[selected]
                : selected + "시";
        return time + " " + counts[selected] + "회";
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
