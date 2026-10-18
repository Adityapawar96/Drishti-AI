package com.example.drishtijava.face;

import java.util.List;

public final class FaceMath {
    private static final float MIN_PROFILE_SCORE_MARGIN = 0.04f;

    private FaceMath() { }

    public static float[] normalized(float[] embedding) {
        if (embedding == null || embedding.length == 0) return new float[0];
        double squared = 0;
        for (float value : embedding) {
            if (Float.isNaN(value) || Float.isInfinite(value)) return new float[0];
            squared += value * value;
        }
        if (squared < 1e-12) return new float[0];
        float norm = (float) Math.sqrt(squared);
        float[] result = embedding.clone();
        for (int i = 0; i < result.length; i++) result[i] /= norm;
        return result;
    }

    public static float[] averageNormalized(List<float[]> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) return new float[0];
        int length = embeddings.get(0).length;
        float[] average = new float[length];
        for (float[] embedding : embeddings) {
            if (embedding.length != length) return new float[0];
            for (int i = 0; i < length; i++) average[i] += embedding[i];
        }
        return normalized(average);
    }

    public static float cosineSimilarity(float[] first, float[] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) return -1f;
        float[] a = normalized(first);
        float[] b = normalized(second);
        if (a.length == 0 || b.length == 0) return -1f;
        float score = 0;
        for (int i = 0; i < a.length; i++) score += a[i] * b[i];
        return score;
    }

    public static Match bestMatch(float[] embedding, List<FaceProfile> profiles, float threshold) {
        String bestName = null;
        float bestScore = -1f;
        float secondBestScore = -1f;
        for (FaceProfile profile : profiles) {
            float profileScore = -1f;
            for (float[] reference : profile.getReferences()) {
                float score = cosineSimilarity(embedding, reference);
                if (score > profileScore) profileScore = score;
            }
            if (profileScore > bestScore) {
                secondBestScore = bestScore;
                bestScore = profileScore;
                bestName = profile.name;
            } else if (profileScore > secondBestScore) {
                secondBestScore = profileScore;
            }
        }
        boolean clearlyBest = secondBestScore < 0
                || bestScore - secondBestScore >= MIN_PROFILE_SCORE_MARGIN;
        return new Match(bestScore >= threshold && clearlyBest ? bestName : null, bestScore);
    }

    public static final class Match {
        public final String name;
        public final float score;

        Match(String name, float score) {
            this.name = name;
            this.score = score;
        }
    }
}
