package com.example.drishtijava;

/** Shared preference keys used by the camera and settings screens. */
final class AppSettings {
    static final String PREFERENCES = "camera_preferences";
    static final String AUTO_TORCH_ENABLED = "auto_torch_enabled";
    static final String VOICE_ALERTS_ENABLED = "voice_alerts_enabled";
    static final String SPEECH_LANGUAGE = "speech_language";
    static final String DEFAULT_SPEECH_LANGUAGE = "en";
    static final String BACKGROUND_PROMPT_SHOWN = "background_detection_prompt_shown";
    static final String CAMERA_DARKNESS_THRESHOLD = "rear_camera_darkness_luminance";
    static final int DEFAULT_CAMERA_DARKNESS_THRESHOLD = 15;
    static final String HAPTICS_ENABLED = "haptics_enabled";
    static final boolean DEFAULT_HAPTICS_ENABLED = true;
    static final String HAPTICS_INTENSITY = "haptics_intensity";
    static final String HAPTICS_INTENSITY_LOW = "low";
    static final String HAPTICS_INTENSITY_MEDIUM = "medium";
    static final String HAPTICS_INTENSITY_HIGH = "high";
    static final String DEFAULT_HAPTICS_INTENSITY = HAPTICS_INTENSITY_HIGH;
    static final String HAPTICS_URGENT_ONLY = "haptics_urgent_only";
    static final boolean DEFAULT_HAPTICS_URGENT_ONLY = false;

    private AppSettings() { }
}
