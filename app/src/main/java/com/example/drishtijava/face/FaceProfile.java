package com.example.drishtijava.face;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Private face identity data; reference embeddings are never exposed as mutable arrays. */
public final class FaceProfile {
    public final String id;
    public final String name;
    private final List<float[]> references;

    public FaceProfile(String id, String name, List<float[]> references) {
        this.id = id;
        this.name = name;
        List<float[]> copy = new ArrayList<>();
        for (float[] reference : references) copy.add(reference.clone());
        this.references = Collections.unmodifiableList(copy);
    }

    public List<float[]> getReferences() {
        List<float[]> copy = new ArrayList<>();
        for (float[] reference : references) copy.add(reference.clone());
        return copy;
    }
}
