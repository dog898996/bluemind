package com.bluemind.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.widget.FrameLayout;

final class RainSceneView extends FrameLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BlueTheme style;

    RainSceneView(Context context, BlueTheme style) {
        super(context);
        this.style = style;
        setWillNotDraw(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, width, height,
                style.heroStart, style.heroEnd, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);

        float centerX = width - style.dp(77);
        float centerY = height * .55f;
        paint.setShader(new RadialGradient(centerX, centerY, style.dp(124),
                style.heroGlow, style.heroGlow & 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(style.dp(1));
        paint.setColor(style.light ? 0x344D88C6 : 0x2276BFFF);
        for (int radius = 32; radius <= 132; radius += 25) {
            float r = style.dp(radius);
            canvas.drawCircle(centerX, centerY, r, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x807EB9F0);
        canvas.drawCircle(centerX - style.dp(65), style.dp(23), style.dp(1.3f), paint);
        canvas.drawCircle(centerX + style.dp(39), height * .20f, style.dp(1.6f), paint);
        canvas.drawCircle(centerX + style.dp(49), height * .73f, style.dp(1.1f), paint);
        canvas.drawCircle(centerX - style.dp(43), height * .82f, style.dp(1.7f), paint);
    }
}
