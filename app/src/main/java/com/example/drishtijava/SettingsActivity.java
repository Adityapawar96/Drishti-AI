package com.example.drishtijava;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.net.Uri;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.drishtijava.face.KnownPeopleActivity;
import com.google.android.material.materialswitch.MaterialSwitch;

/** App preferences. MainActivity releases the camera when this screen opens. */
public final class SettingsActivity extends AppCompatActivity {
    private SharedPreferences preferences;
    private MaterialSwitch voiceAlertsSwitch;
    private MaterialSwitch autoTorchSwitch;
    private MaterialSwitch hapticsSwitch;
    private MaterialSwitch urgentHapticsSwitch;
    private RadioGroup hapticsIntensityGroup;
    private RadioGroup speechLanguageGroup;
    private SeekBar cameraThresholdSeekBar;
    private TextView cameraThresholdValue;
    private int cameraThreshold;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        preferences = getSharedPreferences(AppSettings.PREFERENCES, MODE_PRIVATE);
        voiceAlertsSwitch = findViewById(R.id.settingsVoiceAlertsSwitch);
        autoTorchSwitch = findViewById(R.id.settingsAutoTorchSwitch);
        hapticsSwitch = findViewById(R.id.settingsHapticsSwitch);
        urgentHapticsSwitch = findViewById(R.id.settingsUrgentHapticsSwitch);
        hapticsIntensityGroup = findViewById(R.id.hapticsIntensityGroup);
        speechLanguageGroup = findViewById(R.id.settingsSpeechLanguageGroup);
        cameraThresholdSeekBar = findViewById(R.id.cameraThresholdSeekBar);
        cameraThresholdValue = findViewById(R.id.cameraThresholdValue);
        cameraThreshold = preferences.getInt(AppSettings.CAMERA_DARKNESS_THRESHOLD,
                AppSettings.DEFAULT_CAMERA_DARKNESS_THRESHOLD);

        SpeechLanguage selectedLanguage = SpeechLanguage.fromPreference(preferences.getString(
                AppSettings.SPEECH_LANGUAGE, AppSettings.DEFAULT_SPEECH_LANGUAGE));
        speechLanguageGroup.check(selectedLanguage == SpeechLanguage.HINDI
                ? R.id.speechLanguageHindi
                : selectedLanguage == SpeechLanguage.MARATHI
                ? R.id.speechLanguageMarathi : R.id.speechLanguageEnglish);
        speechLanguageGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String value = checkedId == R.id.speechLanguageHindi ? "hi"
                    : checkedId == R.id.speechLanguageMarathi ? "mr" : "en";
            preferences.edit().putString(AppSettings.SPEECH_LANGUAGE, value).apply();
        });

        findViewById(R.id.settingsKnownPeopleButton).setOnClickListener(view ->
                startActivity(new Intent(this, KnownPeopleActivity.class)));

        findViewById(R.id.settingsVoiceAlertsRow).setOnClickListener(view -> toggleVoiceAlerts());
        voiceAlertsSwitch.setOnClickListener(view -> toggleVoiceAlerts());

        findViewById(R.id.settingsAutoTorchRow).setOnClickListener(view -> toggleAutoTorch());
        autoTorchSwitch.setOnClickListener(view -> toggleAutoTorch());

        findViewById(R.id.settingsHapticsRow).setOnClickListener(view -> toggleHaptics());
        hapticsSwitch.setOnClickListener(view -> toggleHaptics());

        findViewById(R.id.settingsUrgentHapticsRow).setOnClickListener(view -> toggleUrgentOnly());
        urgentHapticsSwitch.setOnClickListener(view -> toggleUrgentOnly());

        findViewById(R.id.settingsTestHapticsButton).setOnClickListener(view ->
                startActivity(new Intent(this, HapticTestActivity.class)));
        findViewById(R.id.settingsHelpButton).setOnClickListener(view -> showHelp());
        findViewById(R.id.settingsBackgroundButton).setOnClickListener(view -> showBackgroundGuidance());
        findViewById(R.id.settingsHomeButton).setOnClickListener(view -> finish());

        cameraThresholdSeekBar.setProgress(cameraThreshold);
        String intensity = preferences.getString(AppSettings.HAPTICS_INTENSITY,
                AppSettings.DEFAULT_HAPTICS_INTENSITY);
        hapticsIntensityGroup.check(AppSettings.HAPTICS_INTENSITY_LOW.equals(intensity)
                ? R.id.hapticsIntensityLow
                : AppSettings.HAPTICS_INTENSITY_MEDIUM.equals(intensity)
                ? R.id.hapticsIntensityMedium : R.id.hapticsIntensityHigh);
        hapticsIntensityGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String selected = checkedId == R.id.hapticsIntensityLow
                    ? AppSettings.HAPTICS_INTENSITY_LOW
                    : checkedId == R.id.hapticsIntensityMedium
                    ? AppSettings.HAPTICS_INTENSITY_MEDIUM : AppSettings.HAPTICS_INTENSITY_HIGH;
            preferences.edit().putString(AppSettings.HAPTICS_INTENSITY, selected).apply();
        });
        updateCameraThresholdLabel();
        cameraThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                cameraThreshold = progress;
                updateCameraThresholdLabel();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                saveCameraThreshold();
            }
        });
        updateToggleLabels();
    }

    private void toggleVoiceAlerts() {
        boolean enabled = !preferences.getBoolean(AppSettings.VOICE_ALERTS_ENABLED, true);
        preferences.edit().putBoolean(AppSettings.VOICE_ALERTS_ENABLED, enabled).apply();
        updateToggleLabels();
    }

    private void toggleAutoTorch() {
        boolean enabled = !preferences.getBoolean(AppSettings.AUTO_TORCH_ENABLED, true);
        preferences.edit().putBoolean(AppSettings.AUTO_TORCH_ENABLED, enabled).apply();
        updateToggleLabels();
    }

    private void toggleHaptics() {
        boolean enabled = !preferences.getBoolean(AppSettings.HAPTICS_ENABLED,
                AppSettings.DEFAULT_HAPTICS_ENABLED);
        preferences.edit().putBoolean(AppSettings.HAPTICS_ENABLED, enabled).apply();
        updateToggleLabels();
    }

    private void toggleUrgentOnly() {
        boolean enabled = !preferences.getBoolean(AppSettings.HAPTICS_URGENT_ONLY,
                AppSettings.DEFAULT_HAPTICS_URGENT_ONLY);
        preferences.edit().putBoolean(AppSettings.HAPTICS_URGENT_ONLY, enabled).apply();
        updateToggleLabels();
    }

    private void updateToggleLabels() {
        boolean voiceEnabled = preferences.getBoolean(AppSettings.VOICE_ALERTS_ENABLED, true);
        voiceAlertsSwitch.setChecked(voiceEnabled);
        voiceAlertsSwitch.setContentDescription(getString(voiceEnabled
                ? R.string.voice_alert_state_on : R.string.voice_alert_state_off));

        boolean torchEnabled = preferences.getBoolean(AppSettings.AUTO_TORCH_ENABLED, true);
        autoTorchSwitch.setChecked(torchEnabled);
        autoTorchSwitch.setContentDescription(getString(torchEnabled
                ? R.string.torch_state_on : R.string.torch_state_off));

        boolean hapticsEnabled = preferences.getBoolean(AppSettings.HAPTICS_ENABLED,
                AppSettings.DEFAULT_HAPTICS_ENABLED);
        hapticsSwitch.setChecked(hapticsEnabled);
        hapticsSwitch.setContentDescription(getString(hapticsEnabled
                ? R.string.haptic_state_on : R.string.haptic_state_off));

        boolean urgentOnly = preferences.getBoolean(AppSettings.HAPTICS_URGENT_ONLY,
                AppSettings.DEFAULT_HAPTICS_URGENT_ONLY);
        urgentHapticsSwitch.setChecked(urgentOnly);
        urgentHapticsSwitch.setContentDescription(getString(urgentOnly
                ? R.string.urgent_state_on : R.string.urgent_state_off));
    }

    private void updateCameraThresholdLabel() {
        cameraThresholdValue.setText(getString(R.string.brightness_threshold_value, cameraThreshold));
        cameraThresholdSeekBar.setContentDescription(getString(
                R.string.brightness_threshold_adjustable_description, cameraThreshold));
    }

    private void saveCameraThreshold() {
        preferences.edit().putInt(AppSettings.CAMERA_DARKNESS_THRESHOLD, cameraThreshold).apply();
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.using_drishti_title)
                .setMessage(R.string.using_drishti_message)
                .setPositiveButton(R.string.got_it, null)
                .show();
    }

    private void showBackgroundGuidance() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.keep_detection_background_title)
                .setMessage(R.string.keep_detection_background_message)
                .setPositiveButton(R.string.battery_settings, (dialog, which) -> openBatterySettings())
                .setNeutralButton(R.string.phone_background_settings, (dialog, which) -> openVendorBackgroundSettings())
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            openAppDetails();
        }
    }

    private void openVendorBackgroundSettings() {
        String manufacturer = android.os.Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        try {
            Intent vendor = new Intent();
            if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")
                    || manufacturer.contains("poco")) {
                vendor.setAction("miui.intent.action.OP_AUTO_START");
                vendor.setPackage("com.miui.securitycenter");
            } else if (manufacturer.contains("oppo") || manufacturer.contains("realme")) {
                vendor.setComponent(new android.content.ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity"));
            } else if (manufacturer.contains("vivo")) {
                vendor.setComponent(new android.content.ComponentName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
            } else if (manufacturer.contains("samsung")) {
                vendor.setAction("android.settings.APPLICATION_DETAILS_SETTINGS");
                vendor.setData(Uri.parse("package:" + getPackageName()));
            } else {
                openBatterySettings();
                return;
            }
            startActivity(vendor);
        } catch (Exception e) {
            openAppDetails();
        }
    }

    private void openAppDetails() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        try { startActivity(intent); }
        catch (Exception ignored) { }
    }

    @Override protected void onPause() {
        saveCameraThreshold();
        super.onPause();
    }
}
