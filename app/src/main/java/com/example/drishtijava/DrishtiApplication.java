package com.example.drishtijava;

import android.app.Application;

/** Sets the saved theme before any activity is created. */
public final class DrishtiApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppTheme.applySavedMode(this);
    }
}
