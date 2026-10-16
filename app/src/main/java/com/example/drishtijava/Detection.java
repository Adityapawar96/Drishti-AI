package com.example.drishtijava;

import android.graphics.RectF;

public final class Detection {
    public final RectF box;
    public final int classId;
    public final String label;
    public final float confidence;

    public Detection(RectF box, int classId, String label, float confidence) {
        this.box = new RectF(box);
        this.classId = classId;
        this.label = label;
        this.confidence = confidence;
    }
}
