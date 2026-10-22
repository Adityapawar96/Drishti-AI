package com.example.drishtijava;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/** Lightweight orbit animation for the brief model and camera startup period. */
public final class StartupLoadingAnimationView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private final int[] dotColors;
    private final float density;
    private ValueAnimator animator;
    private float rotationDegrees;

    public StartupLoadingAnimationView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        dotColors = new int[] {
                ContextCompat.getColor(context, R.color.color_primary),
                ContextCompat.getColor(context, R.color.color_accent),
                ContextCompat.getColor(context, R.color.color_highlight)
        };
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(3f * density);
        trackPaint.setColor(ContextCompat.getColor(context, R.color.color_surface_highlight));
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(7f * density);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(ContextCompat.getColor(context, R.color.color_primary));
        dotPaint.setStyle(Paint.Style.FILL);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) * 0.40f;
        canvas.drawCircle(cx, cy, radius, trackPaint);
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(arcBounds, rotationDegrees - 90f, 78f, false, arcPaint);

        float dotOrbit = radius;
        float dotRadius = 6f * density;
        for (int index = 0; index < dotColors.length; index++) {
            double angle = Math.toRadians(rotationDegrees - 90f + index * 120f);
            float x = cx + (float) Math.cos(angle) * dotOrbit;
            float y = cy + (float) Math.sin(angle) * dotOrbit;
            dotPaint.setColor(dotColors[index]);
            canvas.drawCircle(x, y, dotRadius, dotPaint);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (isAttachedToWindow() && isShown()) startAnimation();
        else stopAnimation();
    }

    private void startAnimation() {
        if (animator != null || !isShown()) return;
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(1_500L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            rotationDegrees = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopAnimation() {
        if (animator == null) return;
        animator.cancel();
        animator = null;
    }
}
