package com.example.drishtijava;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Collections;
import java.util.List;

public final class OverlayView extends View {
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Detection> detections = Collections.emptyList();
    private int frameWidth;
    private int frameHeight;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3.5f * getResources().getDisplayMetrics().density);
        textPaint.setColor(0xffffffff);
        textPaint.setTextSize(14f * getResources().getDisplayMetrics().density);
        textPaint.setFakeBoldText(true);
        backgroundPaint.setStyle(Paint.Style.FILL);
    }

    public void update(List<Detection> next, int width, int height) {
        detections = next;
        frameWidth = width;
        frameHeight = height;
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frameWidth <= 0 || frameHeight <= 0) return;
        float scale = Math.max((float) getWidth() / frameWidth, (float) getHeight() / frameHeight);
        float offsetX = (getWidth() - frameWidth * scale) * 0.5f;
        float offsetY = (getHeight() - frameHeight * scale) * 0.5f;
        float density = getResources().getDisplayMetrics().density;
        for (Detection detection : detections) {
            int color = colorForClass(detection.classId);
            boxPaint.setColor(color);
            backgroundPaint.setColor(color);
            RectF box = new RectF(offsetX + detection.box.left * scale,
                    offsetY + detection.box.top * scale,
                    offsetX + detection.box.right * scale,
                    offsetY + detection.box.bottom * scale);
            canvas.drawRoundRect(box, 5f * density, 5f * density, boxPaint);
            String label = detection.label + " " + Math.round(detection.confidence * 100) + "%";
            float labelWidth = textPaint.measureText(label) + 12f * density;
            float labelTop = Math.max(0, box.top - 25f * density);
            canvas.drawRect(box.left, labelTop, box.left + labelWidth, labelTop + 23f * density, backgroundPaint);
            canvas.drawText(label, box.left + 6f * density, labelTop + 17f * density, textPaint);
        }
    }

    private static int colorForClass(int classId) {
        float hue = (classId * 47f) % 360f;
        return android.graphics.Color.HSVToColor(new float[]{hue, 0.85f, 1f});
    }
}
