package com.bluemind.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class NavIconView extends View {
    private final int type;
    private final int tint;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    NavIconView(Context context, int type, int tint) {
        super(context);
        this.type = type;
        this.tint = tint;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth() / 24f, getHeight() / 24f);
        paint.setColor(tint);
        paint.setStrokeWidth(1.75f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        if (type == 0) {
            Path drop = new Path();
            drop.moveTo(12, 2);
            drop.cubicTo(10, 6, 6, 9, 6, 14);
            drop.cubicTo(6, 22, 18, 22, 18, 14);
            drop.cubicTo(18, 9, 14, 6, 12, 2);
            drop.close();
            canvas.drawPath(drop, paint);
        } else if (type == 1) {
            canvas.drawRoundRect(3.2f, 3.2f, 20.8f, 20.8f, 4, 4, paint);
            canvas.drawLine(12, 7, 12, 12, paint);
            canvas.drawLine(12, 12, 16, 14, paint);
        } else {
            canvas.drawLine(4, 20, 20, 20, paint);
            canvas.drawLine(7, 16, 7, 12, paint);
            canvas.drawLine(12, 16, 12, 6, paint);
            canvas.drawLine(17, 16, 17, 9, paint);
        }
        canvas.restore();
    }
}
