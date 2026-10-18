package com.example.drishtijava;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.Collections;
import java.util.List;

public final class OverlayView extends View {
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outerBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int objectColor;
    private final int potholeColor;
    private final int labelBackgroundColor;
    private final int labelTextColor;
    private final int potholeLabelBackgroundColor;
    private final int potholeLabelTextColor;
    private List<Detection> detections = Collections.emptyList();
    private int frameWidth;
    private int frameHeight;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        objectColor = ContextCompat.getColor(context, R.color.detection_box_object);
        potholeColor = ContextCompat.getColor(context, R.color.detection_box_pothole);
        labelBackgroundColor = ContextCompat.getColor(context, R.color.detection_label_background_object);
        labelTextColor = ContextCompat.getColor(context, R.color.detection_label_text_object);
        potholeLabelBackgroundColor = ContextCompat.getColor(context, R.color.detection_label_background_pothole);
        potholeLabelTextColor = ContextCompat.getColor(context, R.color.detection_label_text_pothole);

        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3.5f * getResources().getDisplayMetrics().density);
        outerBoxPaint.setColor(ContextCompat.getColor(context, R.color.detection_box_outer_stroke));
        outerBoxPaint.setStyle(Paint.Style.STROKE);
        outerBoxPaint.setStrokeWidth(7.5f * getResources().getDisplayMetrics().density);
        textPaint.setColor(labelTextColor);
        textPaint.setTextSize(18f * getResources().getDisplayMetrics().scaledDensity);
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
            boolean pothole = "pothole".equalsIgnoreCase(detection.label);
            int color = pothole ? potholeColor : objectColor;
            int chipBg = pothole ? potholeLabelBackgroundColor : labelBackgroundColor;
            int chipText = pothole ? potholeLabelTextColor : labelTextColor;

            boxPaint.setColor(color);
            boxPaint.setStrokeWidth((pothole ? 5.0f : 3.5f) * density);
            outerBoxPaint.setStrokeWidth((pothole ? 9.0f : 7.5f) * density);
            backgroundPaint.setColor(chipBg);
            textPaint.setColor(chipText);

            RectF box = new RectF(offsetX + detection.box.left * scale,
                    offsetY + detection.box.top * scale,
                    offsetX + detection.box.right * scale,
                    offsetY + detection.box.bottom * scale);
            canvas.drawRoundRect(box, 8f * density, 8f * density, outerBoxPaint);
            canvas.drawRoundRect(box, 8f * density, 8f * density, boxPaint);

            String label = detection.label + " " + Math.round(detection.confidence * 100) + "%";
            float horizontalPadding = 8f * density;
            float labelWidth = textPaint.measureText(label) + 2f * horizontalPadding;
            Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
            float labelHeight = Math.max(34f * density,
                    fontMetrics.bottom - fontMetrics.top + 2f * 6f * density);
            float labelTop = Math.max(0, box.top - labelHeight);
            float labelRight = Math.min(getWidth(), box.left + labelWidth);
            canvas.drawRoundRect(box.left, labelTop, labelRight, labelTop + labelHeight,
                    8f * density, 8f * density, backgroundPaint);
            float baseline = labelTop + (labelHeight - (fontMetrics.bottom - fontMetrics.top)) / 2f
                    - fontMetrics.top;
            canvas.drawText(label, box.left + horizontalPadding, baseline, textPaint);
        }
    }
}
