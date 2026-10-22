package com.example.drishtijava;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/** Applies the saved app appearance using AppCompat's DayNight mode support. */
final class AppTheme {
    private AppTheme() { }

    static void applySavedMode(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                AppSettings.PREFERENCES, Context.MODE_PRIVATE);
        String selected = preferences.getString(AppSettings.APP_THEME,
                AppSettings.DEFAULT_APP_THEME);
        int mode = AppSettings.APP_THEME_LIGHT.equals(selected)
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppSettings.APP_THEME_DARK.equals(selected)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}
