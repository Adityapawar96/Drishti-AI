package com.example.drishtijava;

import java.util.Locale;

/** Languages supported by offline speech alerts. */
public enum SpeechLanguage {
    ENGLISH("en", Locale.ENGLISH),
    HINDI("hi", new Locale("hi", "IN")),
    MARATHI("mr", new Locale("mr", "IN"));

    private final String preferenceValue;
    private final Locale locale;

    SpeechLanguage(String preferenceValue, Locale locale) {
        this.preferenceValue = preferenceValue;
        this.locale = locale;
    }

    public String getPreferenceValue() {
        return preferenceValue;
    }

    public Locale getLocale() {
        return locale;
    }

    public static SpeechLanguage fromPreference(String value) {
        if (HINDI.preferenceValue.equalsIgnoreCase(value)) return HINDI;
        if (MARATHI.preferenceValue.equalsIgnoreCase(value)) return MARATHI;
        return ENGLISH;
    }
}
