package com.example.drishtijava;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

/** Manual, accessible preview for each built-in vibration pattern. */
public final class HapticTestActivity extends AppCompatActivity {
    private HapticAlertManager hapticManager;
    private TextView statusText;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_haptic_test);
        hapticManager = new HapticAlertManager(getApplicationContext());
        statusText = findViewById(R.id.hapticTestStatus);
        LinearLayout patternList = findViewById(R.id.hapticPatternButtons);
        for (HapticPattern pattern : HapticPattern.values()) {
            String label = getString(patternLabel(pattern));
            MaterialButton button = new MaterialButton(this);
            button.setText(label);
            button.setAllCaps(false);
            button.setTextSize(18f);
            button.setMinHeight(dp(58));
            button.setContentDescription(getString(R.string.test_pattern_description,
                    label.toLowerCase(java.util.Locale.ROOT)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(8);
            patternList.addView(button, params);
            button.setOnClickListener(view -> {
                if (hapticManager.playForTest(pattern)) {
                    statusText.setText(getString(R.string.pattern_vibration_played, label));
                } else {
                    statusText.setText(R.string.haptics_unavailable);
                    Toast.makeText(this, R.string.turn_on_haptics_to_test,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }
        findViewById(R.id.hapticTestBackButton).setOnClickListener(view -> finish());
        findViewById(R.id.hapticTestHeaderBackButton).setOnClickListener(view -> finish());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int patternLabel(HapticPattern pattern) {
        switch (pattern) {
            case URGENT_OBSTACLE: return R.string.pattern_urgent_obstacle;
            case OBSTACLE_NEAR: return R.string.pattern_obstacle_near;
            case POTHOLE: return R.string.pattern_pothole;
            case KNOWN_PERSON: return R.string.pattern_known_person;
            case GEMINI_STARTED: return R.string.pattern_gemini_started;
            case GEMINI_DONE: return R.string.pattern_gemini_done;
            case GEMINI_ERROR: return R.string.pattern_gemini_error;
            case MODE_OBJECT: return R.string.pattern_mode_object;
            case MODE_ROAD: return R.string.pattern_mode_road;
            default: throw new IllegalArgumentException("Unknown haptic pattern: " + pattern);
        }
    }

    @Override protected void onPause() {
        if (hapticManager != null) hapticManager.cancel();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (hapticManager != null) hapticManager.close();
        super.onDestroy();
    }
}
