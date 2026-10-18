package com.example.drishtijava;

/** Vibration shapes and arbitration metadata; contains no Android dependencies. */
public enum HapticPattern {
    URGENT_OBSTACLE(new long[]{0, 110, 80, 110, 80, 140},
            new int[]{0, 255, 0, 255, 0, 255}, 100, 1_500),
    OBSTACLE_NEAR(new long[]{0, 240}, new int[]{0, 190}, 80, 1_500),
    POTHOLE(new long[]{0, 65, 75, 65}, new int[]{0, 245, 0, 245}, 90, 2_000),
    KNOWN_PERSON(new long[]{0, 85, 100, 85, 100, 280},
            new int[]{0, 80, 0, 80, 0, 80}, 55, 3_500),
    GEMINI_STARTED(new long[]{0, 45}, new int[]{0, 150}, 20, 400),
    GEMINI_DONE(new long[]{0, 45, 70, 45}, new int[]{0, 145, 0, 145}, 20, 500),
    GEMINI_ERROR(new long[]{0, 500}, new int[]{0, 210}, 60, 1_500),
    MODE_OBJECT(new long[]{0, 65}, new int[]{0, 125}, 10, 300),
    MODE_ROAD(new long[]{0, 65, 70, 65}, new int[]{0, 125, 0, 125}, 10, 300);

    private final long[] timingsMs;
    private final int[] amplitudes;
    private final int priority;
    private final long cooldownMs;
    private final long durationMs;

    HapticPattern(long[] timingsMs, int[] amplitudes, int priority, long cooldownMs) {
        if (timingsMs.length != amplitudes.length) {
            throw new IllegalArgumentException("Waveform timing and amplitude arrays must match");
        }
        this.timingsMs = timingsMs.clone();
        this.amplitudes = amplitudes.clone();
        this.priority = priority;
        this.cooldownMs = cooldownMs;
        long duration = 0;
        for (long timing : timingsMs) duration += timing;
        durationMs = duration;
    }

    public long[] timingsMs() { return timingsMs.clone(); }
    public int[] amplitudes() { return amplitudes.clone(); }
    public int priority() { return priority; }
    public long cooldownMs() { return cooldownMs; }
    public long durationMs() { return durationMs; }
}
