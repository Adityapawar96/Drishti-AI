package com.example.drishtijava;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Preview;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.drishtijava.face.KnownPeopleActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Collections;

/** Camera screen UI; camera, inference and alert resources are owned by DrishtiService. */
public class MainActivity extends AppCompatActivity implements DrishtiService.Listener {
    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView statusText;
    private TextView sceneAnalysisText;
    private TextView performanceText;
    private TextView lightStatusText;
    private MaterialButton objectsModeButton;
    private MaterialButton roadModeButton;
    private Button pauseDetectionButton;
    private ImageButton settingsButton;
    private Button setupInstructionsBubble;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private DrishtiService service;
    private boolean serviceBound;
    private boolean settingsTransition;
    private boolean setupInstructionsSeenThisLaunch;
    private boolean modelReady;
    private boolean roadMode;
    private boolean detectionPaused;
    private boolean roadAvailable;
    private String pendingVoiceLanguage;
    private int previewTapCount;
    private long previousPreviewTapUpMs;
    private float previousPreviewTapX;
    private float previousPreviewTapY;
    private float previewTapDownX;
    private float previewTapDownY;
    private long previewTapDownMs;
    private boolean previewTapCandidate;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            DrishtiService.LocalBinder localBinder = (DrishtiService.LocalBinder) binder;
            service = localBinder.getService();
            service.setListener(MainActivity.this);
            service.attachPreview(previewView.getSurfaceProvider(), currentDisplayRotation());
            if (settingsTransition) {
                settingsTransition = false;
            }
            requestNotificationPermissionIfNeeded();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            modelReady = false;
            updateDetectionModeButton();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            View top = findViewById(R.id.topControls);
            View bottom = findViewById(R.id.bottomControls);
            float density = getResources().getDisplayMetrics().density;
            if (top != null) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) top.getLayoutParams();
                lp.topMargin = bars.top + Math.round(16 * density);
                top.setLayoutParams(lp);
            }
            if (bottom != null) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) bottom.getLayoutParams();
                lp.bottomMargin = bars.bottom + Math.round(16 * density);
                bottom.setLayoutParams(lp);
            }
            return insets;
        });
        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        statusText = findViewById(R.id.statusText);
        sceneAnalysisText = findViewById(R.id.sceneAnalysisText);
        performanceText = findViewById(R.id.performanceText);
        lightStatusText = findViewById(R.id.lightStatusText);
        objectsModeButton = findViewById(R.id.objectsModeButton);
        roadModeButton = findViewById(R.id.roadModeButton);
        pauseDetectionButton = findViewById(R.id.pauseDetectionButton);
        settingsButton = findViewById(R.id.settingsButton);
        setupInstructionsBubble = findViewById(R.id.setupInstructionsBubble);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        configureSceneAnalysisGesture();
        updateSetupInstructionsBubble();
        setupInstructionsBubble.setOnClickListener(view -> showCameraSetupInstructions());
        settingsButton.setOnClickListener(view -> {
            settingsTransition = true;
            if (service != null) service.suspendForSettings();
            else ContextCompat.startForegroundService(this,
                    new Intent(this, DrishtiService.class).setAction("suspend"));
            startActivity(new Intent(this, SettingsActivity.class));
        });
        objectsModeButton.setOnClickListener(view -> {
            if (service != null && roadMode) service.switchDetectionMode();
        });
        roadModeButton.setOnClickListener(view -> {
            if (service != null && !roadMode) service.switchDetectionMode();
        });
        pauseDetectionButton.setOnClickListener(view -> {
            if (service != null) service.pauseDetection();
        });
        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) startAndBindService("start");
                    else statusText.setText(R.string.camera_permission_denied_settings);
                });
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    SharedPreferences prefs = getSharedPreferences(AppSettings.PREFERENCES, MODE_PRIVATE);
                    prefs.edit().putBoolean("notification_permission_prompted", true).apply();
                    if (!granted && service != null) service.speakNotificationPermissionInfo();
                });
        objectsModeButton.setEnabled(false);
        roadModeButton.setEnabled(false);
        pauseDetectionButton.setEnabled(false);
        statusText.setText(R.string.camera_permission_needed);
    }

    @Override protected void onResume() {
        super.onResume();
        updateSetupInstructionsBubble();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startAndBindService(settingsTransition ? "resume" : "start");
        } else if (!isFinishing()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startAndBindService(String action) {
        Intent intent = new Intent(this, DrishtiService.class).setAction(action);
        try {
            ContextCompat.startForegroundService(this, intent);
            if (!serviceBound) {
                serviceBound = bindService(new Intent(this, DrishtiService.class), serviceConnection,
                        BIND_AUTO_CREATE);
            }
        } catch (SecurityException | IllegalStateException e) {
            statusText.setText(R.string.camera_detection_start_failed);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences prefs = getSharedPreferences(AppSettings.PREFERENCES, MODE_PRIVATE);
        if (prefs.getBoolean("notification_permission_prompted", false)) return;
        if (service != null) service.speakNotificationPermissionInfo();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.allow_detection_notification_title)
                .setMessage(R.string.allow_detection_notification_message)
                .setPositiveButton(R.string.continue_button, (dialog, which) ->
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS))
                .setNegativeButton(R.string.not_now, (dialog, which) ->
                        prefs.edit().putBoolean("notification_permission_prompted", true).apply())
                .show();
    }

    @Override protected void onStop() {
        if (service != null) {
            service.detachPreview();
            service.setListener(null);
        }
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        service = null;
        super.onStop();
    }

    @Override public void onServiceState(String message, boolean ready, boolean roadMode,
                                         boolean paused, boolean roadAvailable) {
        modelReady = ready;
        this.roadMode = roadMode;
        detectionPaused = paused;
        this.roadAvailable = roadAvailable;
        statusText.setText(message);
        updateDetectionModeButton();
        updateDetectionButton();
        if (!ready) performanceText.setText(R.string.performance_initial);
    }

    @Override public void onPreviewAvailable(Preview preview) {
        if (service != null && previewView != null) {
            service.attachPreview(previewView.getSurfaceProvider(), currentDisplayRotation());
        }
    }

    @Override public void onDetections(java.util.List<Detection> detections, int width, int height) {
        if (overlayView != null) overlayView.update(detections, width, height);
    }

    @Override public void onPerformance(String value) {
        if (performanceText != null) performanceText.setText(value);
    }

    @Override public void onLightStatus(String value) {
        if (lightStatusText != null) lightStatusText.setText(value);
    }

    @Override public void onSceneResult(String value, boolean error) {
        if (sceneAnalysisText == null) return;
        sceneAnalysisText.setVisibility(View.VISIBLE);
        sceneAnalysisText.setText(value);
    }

    @Override public void onOfflineVoiceUnavailable(SpeechLanguage language) {
        showMissingOfflineVoiceGuidance(language);
    }

    private int currentDisplayRotation() {
        return previewView.getDisplay() == null ? android.view.Surface.ROTATION_0
                : previewView.getDisplay().getRotation();
    }

    private void updateDetectionModeButton() {
        if (objectsModeButton == null || roadModeButton == null) return;
        objectsModeButton.setBackgroundResource(roadMode
                ? R.drawable.button_secondary_background : R.drawable.button_primary_background);
        objectsModeButton.setIconResource(roadMode
                ? R.drawable.ic_objects : R.drawable.ic_objects_selected);
        objectsModeButton.setTextColor(ContextCompat.getColorStateList(this,
                roadMode ? R.color.button_secondary_text : R.color.button_primary_text));
        objectsModeButton.setText(modelReady ? R.string.mode_objects_short : R.string.mode_objects_loading);
        objectsModeButton.setContentDescription(getString(!modelReady
                ? R.string.object_mode_loading_description
                : roadMode ? R.string.select_object_mode : R.string.object_mode_selected));
        objectsModeButton.setEnabled(modelReady);

        roadModeButton.setBackgroundResource(roadMode
                ? R.drawable.button_warning_background : R.drawable.button_secondary_background);
        roadModeButton.setIconResource(roadMode
                ? R.drawable.ic_road_selected : R.drawable.ic_road);
        roadModeButton.setTextColor(ContextCompat.getColorStateList(this,
                roadMode ? R.color.button_warning_text : R.color.button_secondary_text));
        roadModeButton.setText(!modelReady ? R.string.mode_road_loading
                : !roadAvailable && !roadMode ? R.string.mode_road_unavailable
                : R.string.mode_road_short);
        roadModeButton.setContentDescription(getString(!modelReady
                ? R.string.road_mode_loading_description
                : !roadAvailable && !roadMode ? R.string.road_mode_unavailable_description
                : roadMode ? R.string.road_mode_selected : R.string.select_road_mode));
        roadModeButton.setEnabled(modelReady && (roadMode || roadAvailable));
    }

    private void updateDetectionButton() {
        if (pauseDetectionButton == null) return;
        pauseDetectionButton.setText(detectionPaused ? R.string.resume : R.string.pause);
        pauseDetectionButton.setContentDescription(getString(detectionPaused
                ? R.string.detection_paused_description : R.string.detection_running_description));
        pauseDetectionButton.setEnabled(modelReady);
    }

    private void configureSceneAnalysisGesture() {
        previewView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        previewView.setContentDescription(getString(R.string.live_preview_description));
        // Keep an accessibility click action for TalkBack; regular screen taps use
        // the triple-tap recognizer below.
        previewView.setOnClickListener(view -> requestSceneAnalysis());
        final float moveSlop = ViewConfiguration.get(this).getScaledTouchSlop() * 2f;
        final float tapSpacing = 72f * getResources().getDisplayMetrics().density;
        previewView.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                previewTapDownX = event.getX();
                previewTapDownY = event.getY();
                previewTapDownMs = event.getEventTime();
                previewTapCandidate = true;
                if (previewTapCount > 0 && !continuesPreviewTapSequence(
                        previewTapDownX, previewTapDownY, previewTapDownMs, tapSpacing)) {
                    resetPreviewTapSequence();
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - previewTapDownX;
                float dy = event.getY() - previewTapDownY;
                if (dx * dx + dy * dy > moveSlop * moveSlop) {
                    previewTapCandidate = false;
                    resetPreviewTapSequence();
                }
            } else if (action == MotionEvent.ACTION_UP) {
                long tapDuration = event.getEventTime() - previewTapDownMs;
                float dx = event.getX() - previewTapDownX;
                float dy = event.getY() - previewTapDownY;
                boolean isTap = previewTapCandidate && tapDuration <= 400L
                        && dx * dx + dy * dy <= moveSlop * moveSlop;
                if (isTap) {
                    previewTapCount++;
                    previousPreviewTapUpMs = event.getEventTime();
                    previousPreviewTapX = event.getX();
                    previousPreviewTapY = event.getY();
                    if (previewTapCount == 3) {
                        resetPreviewTapSequence();
                        requestSceneAnalysis();
                    }
                } else {
                    resetPreviewTapSequence();
                }
                previewTapCandidate = false;
            } else if (action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_POINTER_UP) {
                previewTapCandidate = false;
                resetPreviewTapSequence();
            }
            return true;
        });
    }

    private boolean continuesPreviewTapSequence(float x, float y, long timeMs, float spacing) {
        if (timeMs - previousPreviewTapUpMs > 500L) return false;
        float dx = x - previousPreviewTapX;
        float dy = y - previousPreviewTapY;
        return dx * dx + dy * dy <= spacing * spacing;
    }

    private void resetPreviewTapSequence() {
        previewTapCount = 0;
        previousPreviewTapUpMs = 0L;
    }

    private void requestSceneAnalysis() {
        if (service != null) service.requestSceneAnalysis();
        else {
            sceneAnalysisText.setVisibility(View.VISIBLE);
            sceneAnalysisText.setText(R.string.camera_service_starting);
        }
    }

    private void showMissingOfflineVoiceGuidance(SpeechLanguage language) {
        if (isFinishing() || isDestroyed() || !hasWindowFocus()) {
            pendingVoiceLanguage = language.getPreferenceValue();
            return;
        }
        if (language.getPreferenceValue().equals(pendingVoiceLanguage)) return;
        pendingVoiceLanguage = language.getPreferenceValue();
        String displayName = language == SpeechLanguage.HINDI ? "Hindi"
                : language == SpeechLanguage.MARATHI ? "Marathi" : "English";
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.offline_voice_unavailable_title)
                .setMessage(getString(R.string.offline_voice_unavailable_message, displayName))
                .setPositiveButton(R.string.open_voice_data_settings, (dialog, which) -> {
                    pendingVoiceLanguage = null;
                    try { startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)); }
                    catch (Exception e) {
                        new MaterialAlertDialogBuilder(this)
                                .setMessage(getString(R.string.install_voice_data_message, displayName))
                                .setPositiveButton(R.string.got_it, null).show();
                    }
                })
                .setNegativeButton(R.string.later, null)
                .show();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pendingVoiceLanguage != null) {
            SpeechLanguage language = SpeechLanguage.fromPreference(pendingVoiceLanguage);
            pendingVoiceLanguage = null;
            showMissingOfflineVoiceGuidance(language);
        }
    }

    private void updateSetupInstructionsBubble() {
        if (setupInstructionsBubble != null) {
            setupInstructionsBubble.setVisibility(setupInstructionsSeenThisLaunch ? View.GONE : View.VISIBLE);
        }
    }

    private void showCameraSetupInstructions() {
        setupInstructionsSeenThisLaunch = true;
        if (setupInstructionsBubble != null) setupInstructionsBubble.setVisibility(View.GONE);
        View content = getLayoutInflater().inflate(R.layout.dialog_camera_setup_instructions, null, false);
        ImageView image = content.findViewById(R.id.cameraSetupImage);
        TextView stepIndicator = content.findViewById(R.id.cameraSetupStepIndicator);
        TextView instruction = content.findViewById(R.id.cameraSetupInstruction);
        android.widget.FrameLayout step1Dot = content.findViewById(R.id.cameraSetupDotStep1);
        android.widget.FrameLayout step2Dot = content.findViewById(R.id.cameraSetupDotStep2);
        android.widget.FrameLayout step3Dot = content.findViewById(R.id.cameraSetupDotStep3);
        View step1DotIndicator = content.findViewById(R.id.cameraSetupDotStep1Indicator);
        View step2DotIndicator = content.findViewById(R.id.cameraSetupDotStep2Indicator);
        View step3DotIndicator = content.findViewById(R.id.cameraSetupDotStep3Indicator);
        Button gotItButton = content.findViewById(R.id.cameraSetupGotItButton);
        int[] currentStep = {0};
        android.graphics.Bitmap[] bitmap = {null};
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this).setView(content).create();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);
        Runnable showStep = () -> {
            int step = currentStep[0];
            int[] instructions = {R.string.camera_setup_step_1, R.string.camera_setup_step_2,
                    R.string.camera_setup_step_3};
            int[] images = {R.drawable.instruction_step_1, R.drawable.instruction_step_2,
                    R.drawable.instruction_step_3};
            int[] imageDescriptions = {R.string.camera_setup_image_1_description,
                    R.string.camera_setup_image_2_description, R.string.camera_setup_image_3_description};
            int[] dotDescriptions = {R.string.camera_setup_step_1_dot_current,
                    R.string.camera_setup_step_2_dot_current, R.string.camera_setup_step_3_dot_current};
            android.widget.FrameLayout[] dots = {step1Dot, step2Dot, step3Dot};
            View[] indicators = {step1DotIndicator, step2DotIndicator, step3DotIndicator};
            instruction.setText(instructions[step]);
            stepIndicator.setText(getString(R.string.camera_setup_step_indicator, step + 1));
            setSetupInstructionImage(image, images[step], bitmap);
            image.setContentDescription(getString(imageDescriptions[step]));
            for (int index = 0; index < dots.length; index++) {
                boolean current = index == step;
                indicators[index].setBackgroundResource(current
                        ? R.drawable.camera_setup_dot_active : R.drawable.camera_setup_dot_inactive);
                dots[index].setContentDescription(getString(current
                        ? dotDescriptions[index] : getDotActionDescription(index)));
            }
            gotItButton.setVisibility(step == 2 ? View.VISIBLE : View.INVISIBLE);
            stepIndicator.announceForAccessibility(stepIndicator.getText());
        };
        step1Dot.setOnClickListener(view -> { currentStep[0] = 0; showStep.run(); });
        step2Dot.setOnClickListener(view -> { currentStep[0] = 1; showStep.run(); });
        step3Dot.setOnClickListener(view -> { currentStep[0] = 2; showStep.run(); });
        gotItButton.setOnClickListener(view -> dialog.dismiss());
        GestureDetector gesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onFling(MotionEvent start, MotionEvent end, float vx, float vy) {
                if (start == null || end == null || Math.abs(start.getX() - end.getX()) < 72
                        || Math.abs(vx) < 100 || Math.abs(vx) <= Math.abs(vy)) return false;
                if (start.getX() > end.getX() && currentStep[0] < 2) currentStep[0]++;
                else if (end.getX() > start.getX() && currentStep[0] > 0) currentStep[0]--;
                else return false;
                showStep.run();
                return true;
            }
        });
        image.setOnTouchListener((view, event) -> gesture.onTouchEvent(event));
        dialog.setOnDismissListener(ignored -> {
            image.setImageDrawable(null);
            if (bitmap[0] != null && !bitmap[0].isRecycled()) bitmap[0].recycle();
        });
        dialog.show();
        showStep.run();
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            int margin = Math.round(20 * metrics.density);
            window.setLayout(metrics.widthPixels - margin * 2, metrics.heightPixels - margin * 2);
        }
    }

    private int getDotActionDescription(int step) {
        if (step == 0) return R.string.camera_setup_step_1_dot_action;
        if (step == 1) return R.string.camera_setup_step_2_dot_action;
        return R.string.camera_setup_step_3_dot_action;
    }

    private void setSetupInstructionImage(ImageView image, int resourceId, android.graphics.Bitmap[] current) {
        image.setImageDrawable(null);
        if (current[0] != null && !current[0].isRecycled()) current[0].recycle();
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        try {
            options.inSampleSize = 1;
            options.inScaled = false;
            current[0] = android.graphics.BitmapFactory.decodeResource(getResources(), resourceId, options);
        } catch (OutOfMemoryError error) {
            options.inSampleSize = 2;
            options.inScaled = false;
            current[0] = android.graphics.BitmapFactory.decodeResource(getResources(), resourceId, options);
        }
        if (current[0] != null) current[0].setDensity(android.graphics.Bitmap.DENSITY_NONE);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(current[0]);
    }
}
