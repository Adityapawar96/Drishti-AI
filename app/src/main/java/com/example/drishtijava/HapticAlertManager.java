package com.example.drishtijava;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.os.VibrationAttributes;
import android.util.Log;

/** Lightweight accessibility haptics. All vibrator operations are dispatched on the main thread. */
@SuppressWarnings("deprecation") // Keep API 29–32 compatibility for vibrator attributes.
public final class HapticAlertManager implements AutoCloseable {
    private static final String TAG = "DrishtiHaptics";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private final Vibrator vibrator;
    private final HapticAlertPolicy policy = new HapticAlertPolicy();
    private volatile boolean enabled;
    private volatile boolean urgentOnly;
    private volatile String intensity;
    private boolean closed;

    public HapticAlertManager(Context context) {
        Context appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences(AppSettings.PREFERENCES, Context.MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = manager == null ? null : manager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) appContext.getSystemService(Context.VIBRATOR_SERVICE);
        }
        reloadPreferences();
    }

    public boolean isEnabled() { return enabled; }

    public void reloadPreferences() {
        boolean wasEnabled = enabled;
        enabled = preferences.getBoolean(AppSettings.HAPTICS_ENABLED, AppSettings.DEFAULT_HAPTICS_ENABLED);
        urgentOnly = preferences.getBoolean(AppSettings.HAPTICS_URGENT_ONLY,
                AppSettings.DEFAULT_HAPTICS_URGENT_ONLY);
        intensity = preferences.getString(AppSettings.HAPTICS_INTENSITY,
                AppSettings.DEFAULT_HAPTICS_INTENSITY);
        if (wasEnabled && !enabled) cancel();
    }

    /** Play an event haptic without waiting for the camera or inference thread. */
    public boolean play(HapticPattern pattern) {
        return play(pattern, 1f);
    }

    /** The proximity multiplier is used only by OBSTACLE_NEAR and comes from the speaker's box proxy. */
    public boolean play(HapticPattern pattern, float proximityScale) {
        if (pattern == null || !enabled || (urgentOnly && isDetectionCue(pattern)
                && pattern.priority() < HapticPattern.POTHOLE.priority())) return false;
        return dispatch(pattern, proximityScale, false);
    }

    /** Explicit test action: honors the main on/off switch but bypasses urgent-only and cooldowns. */
    public boolean playForTest(HapticPattern pattern) {
        if (pattern == null || !enabled) return false;
        return dispatch(pattern, 1f, true);
    }

    private boolean dispatch(HapticPattern pattern, float proximityScale, boolean test) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return playOnMain(pattern, proximityScale, test);
        }
        mainHandler.post(() -> playOnMain(pattern, proximityScale, test));
        return true;
    }

    private boolean playOnMain(HapticPattern pattern, float proximityScale, boolean test) {
        if (closed || !enabled || vibrator == null || !vibrator.hasVibrator()) return false;
        if (!test) {
            HapticAlertPolicy.Decision decision = policy.request(pattern,
                    android.os.SystemClock.elapsedRealtime());
            if (!decision.accepted) return false;
            if (decision.cancelExisting) vibrator.cancel();
        } else {
            // Test buttons always demonstrate the selected shape and do not enter alert arbitration.
            vibrator.cancel();
            policy.cancelActive();
        }

        long[] timings = pattern.timingsMs();
        VibrationEffect effect;
        if (vibrator.hasAmplitudeControl()) {
            int[] amplitudes = scaledAmplitudes(pattern, proximityScale);
            effect = VibrationEffect.createWaveform(timings, amplitudes, -1);
        } else {
            // Devices without amplitude control still get the exact pulse and pause timings.
            effect = VibrationEffect.createWaveform(timings, -1);
        }

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                VibrationAttributes attributes = new VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ACCESSIBILITY)
                        .build();
                vibrator.vibrate(effect, attributes);
            } else {
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                vibrator.vibrate(effect, attributes);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not play vibration pattern " + pattern, error);
            policy.cancelActive();
            return false;
        }
        return true;
    }

    private int[] scaledAmplitudes(HapticPattern pattern, float proximityScale) {
        float intensityScale;
        if (AppSettings.HAPTICS_INTENSITY_LOW.equals(intensity)) intensityScale = 0.58f;
        else if (AppSettings.HAPTICS_INTENSITY_MEDIUM.equals(intensity)) intensityScale = 0.78f;
        else intensityScale = 1f;

        float proximity = Math.max(0.65f, Math.min(1f, proximityScale));
        if (pattern != HapticPattern.OBSTACLE_NEAR) proximity = 1f;
        int[] amplitudes = pattern.amplitudes();
        for (int i = 0; i < amplitudes.length; i++) {
            if (amplitudes[i] > 0) {
                amplitudes[i] = Math.max(1, Math.min(255,
                        Math.round(amplitudes[i] * intensityScale * proximity)));
            }
        }
        return amplitudes;
    }

    private static boolean isDetectionCue(HapticPattern pattern) {
        return pattern == HapticPattern.URGENT_OBSTACLE
                || pattern == HapticPattern.OBSTACLE_NEAR
                || pattern == HapticPattern.POTHOLE
                || pattern == HapticPattern.KNOWN_PERSON;
    }

    public void cancel() {
        Runnable action = () -> {
            try {
                if (vibrator != null && vibrator.hasVibrator()) vibrator.cancel();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not cancel vibration", error);
            }
            policy.cancelActive();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }

    @Override public void close() {
        closed = true;
        cancel();
    }
}
