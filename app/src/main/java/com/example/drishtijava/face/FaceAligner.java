package com.example.drishtijava.face;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;

import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.Arrays;
import java.util.Comparator;

/** Five-landmark similarity alignment to the model's 112 by 112 ArcFace-style template. */
public final class FaceAligner {
    public static final int INPUT_SIZE = 112;
    private static final PointF[] TARGET = {
            new PointF(38.2946f, 51.6963f), new PointF(73.5318f, 51.5014f),
            new PointF(56.0252f, 71.7366f), new PointF(41.5493f, 92.3655f),
            new PointF(70.7299f, 92.2041f)
    };

    private FaceAligner() { }

    public static Bitmap align(Bitmap source, Face face) {
        PointF leftEye = point(face, FaceLandmark.LEFT_EYE);
        PointF rightEye = point(face, FaceLandmark.RIGHT_EYE);
        PointF nose = point(face, FaceLandmark.NOSE_BASE);
        PointF leftMouth = point(face, FaceLandmark.MOUTH_LEFT);
        PointF rightMouth = point(face, FaceLandmark.MOUTH_RIGHT);
        if (leftEye == null || rightEye == null || nose == null || leftMouth == null || rightMouth == null) return null;
        PointF[] eyes = ordered(leftEye, rightEye);
        PointF[] mouth = ordered(leftMouth, rightMouth);
        PointF[] sourcePoints = {eyes[0], eyes[1], nose, mouth[0], mouth[1]};
        float[] transform = fitSimilarity(sourcePoints, TARGET);
        if (transform == null) return null;
        Bitmap aligned = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(aligned);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        Matrix matrix = new Matrix();
        matrix.setValues(transform);
        canvas.drawBitmap(source, matrix, paint);
        return aligned;
    }

    private static PointF point(Face face, int landmarkType) {
        FaceLandmark landmark = face.getLandmark(landmarkType);
        return landmark == null ? null : new PointF(landmark.getPosition().x, landmark.getPosition().y);
    }

    private static PointF[] ordered(PointF first, PointF second) {
        PointF[] pair = {first, second};
        Arrays.sort(pair, Comparator.comparingDouble(value -> value.x));
        return pair;
    }

    private static float[] fitSimilarity(PointF[] source, PointF[] target) {
        float sourceX = 0, sourceY = 0, targetX = 0, targetY = 0;
        for (int i = 0; i < source.length; i++) {
            sourceX += source[i].x;
            sourceY += source[i].y;
            targetX += target[i].x;
            targetY += target[i].y;
        }
        sourceX /= source.length;
        sourceY /= source.length;
        targetX /= source.length;
        targetY /= source.length;
        double dot = 0, cross = 0, energy = 0;
        for (int i = 0; i < source.length; i++) {
            double sx = source[i].x - sourceX;
            double sy = source[i].y - sourceY;
            double tx = target[i].x - targetX;
            double ty = target[i].y - targetY;
            dot += sx * tx + sy * ty;
            cross += sx * ty - sy * tx;
            energy += sx * sx + sy * sy;
        }
        if (energy < 1e-5) return null;
        float a = (float) (dot / energy);
        float b = (float) (cross / energy);
        float translateX = targetX - a * sourceX + b * sourceY;
        float translateY = targetY - b * sourceX - a * sourceY;
        return new float[]{a, -b, translateX, b, a, translateY, 0f, 0f, 1f};
    }
}
