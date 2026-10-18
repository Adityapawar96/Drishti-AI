package com.example.drishtijava;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.example.drishtijava.face.CameraBitmapUtils;
import com.example.drishtijava.face.FaceRecognitionManager;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground owner of the camera analysis and assistive alert pipeline. */
public final class DrishtiService extends LifecycleService implements SensorEventListener {
    private static final String TAG = "DrishtiService";
    private static final String CHANNEL_ID = "drishti_detection";
    private static final int NOTIFICATION_ID = 42;
    private static final String ACTION_STOP = "com.example.drishtijava.action.STOP";
    private static final String ACTION_SWITCH_MODE = "com.example.drishtijava.action.SWITCH_MODE";
    private static final float TORCH_DARKNESS_THRESHOLD = 0.5f;
    private static final long TORCH_THRESHOLD_DWELL_MS = 1_000L;
    private static final long TORCH_PULSE_DURATION_MS = 25_000L;
    private static final long TORCH_POST_PULSE_RECHECK_MS = 1_000L;

    public interface Listener {
        void onServiceState(String message, boolean ready, boolean roadMode,
                            boolean paused, boolean roadAvailable);
        void onPreviewAvailable(Preview preview);
        void onDetections(List<Detection> detections, int width, int height);
        void onPerformance(String value);
        void onLightStatus(String value);
        void onSceneResult(String value, boolean error);
        void onOfflineVoiceUnavailable(SpeechLanguage language);
    }

    public final class LocalBinder extends android.os.Binder {
        public DrishtiService getService() { return DrishtiService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean sceneFrameRequested = new AtomicBoolean(false);
    private final AtomicBoolean sceneAnalysisInProgress = new AtomicBoolean(false);
    private final DrishtiServiceState runState = new DrishtiServiceState();
    private volatile Listener listener;
    private ProcessCameraProvider cameraProvider;
    private Camera boundCamera;
    private Preview cameraPreview;
    private ImageAnalysis imageAnalysis;
    private YoloDetector detector;
    private volatile YoloDetector potholeDetector;
    private volatile FaceRecognitionManager faceRecognitionManager;
    private volatile ObjectAlertSpeaker objectAlertSpeaker;
    private HapticAlertManager hapticAlertManager;
    private GeminiSceneAnalyzer geminiSceneAnalyzer;
    private volatile boolean modelReady;
    private volatile boolean potholeModelAvailable;
    private volatile boolean roadModeLoading;
    private volatile boolean detectionPaused;
    private volatile boolean roadMode;
    private volatile boolean cameraBound;
    private boolean cameraHasFlash;
    private volatile boolean shuttingDown;
    private boolean suspendedForSettings;
    private boolean previewAttached;
    private Preview.SurfaceProvider previewSurfaceProvider;
    private boolean cameraStartPending;
    private boolean startupAnnounced;
    private boolean cameraUnavailable;
    private int displayRotation = android.view.Surface.ROTATION_0;
    private int lastFrameWidth;
    private int lastFrameHeight;
    private long previousFrameNs;
    private long previousPerformanceUpdateNs;
    private float smoothedFps;
    private long lastAnalyzedAtMs;
    private volatile long minimumFrameIntervalMs;
    private long retryDelayMs = 1_000L;
    private Runnable cameraRetry;
    private volatile SpeechLanguage speechLanguage = SpeechLanguage.ENGLISH;
    private volatile int cameraBrightnessThreshold = AppSettings.DEFAULT_CAMERA_DARKNESS_THRESHOLD;
    private volatile boolean autoTorchEnabled;
    private volatile boolean torchOn;
    private boolean torchControlFailed;
    private boolean awaitingPostPulseReading;
    private boolean lightSensorRegistered;
    private long darkSinceMs;
    private long lastLightStatusUpdateMs;
    private float lastAmbientLux = Float.NaN;
    private float lastRearLuminance = Float.NaN;
    private Runnable torchPulseTimeout;
    private Runnable postPulseDarknessCheck;
    private SensorManager sensorManager;
    private Sensor ambientLightSensor;
    private PowerManager powerManager;
    private PowerManager.OnThermalStatusChangedListener thermalListener;
    private BroadcastReceiver batteryReceiver;
    private boolean receiverRegistered;
    private volatile int batteryPercent = 100;
    private volatile int thermalStatus = PowerManager.THERMAL_STATUS_NONE;
    private boolean lowPowerWarningSpoken;
    private boolean offlineVoicePromptSent;
    private Runnable sceneCaptureTimeout;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        ambientLightSensor = findRearLightSensor();
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        registerPowerMonitors();
        SharedPreferencesSnapshot settings = readSettings();
        autoTorchEnabled = settings.autoTorch;
        cameraBrightnessThreshold = settings.threshold;
        speechLanguage = settings.language;
        hapticAlertManager = new HapticAlertManager(getApplicationContext());
        objectAlertSpeaker = new ObjectAlertSpeaker(getApplicationContext(), hapticAlertManager);
        geminiSceneAnalyzer = new GeminiSceneAnalyzer(BuildConfig.GEMINI_API_KEY);
        faceRecognitionManager = new FaceRecognitionManager(getApplicationContext(), objectAlertSpeaker);
        applySpeechLanguage();
        potholeModelAvailable = assetExists("pothole.tflite") && assetExists("pothole_labels.txt");
        analysisExecutor.execute(this::loadModels);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        runState.requestStart();
        startAsForeground();
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopDetection(true);
            return START_NOT_STICKY;
        }
        if (ACTION_SWITCH_MODE.equals(action)) switchDetectionMode();
        else if ("suspend".equals(action)) suspendForSettings();
        else if ("resume".equals(action)) resumeFromSettings();
        else if (!cameraBound && !suspendedForSettings) startCameraWhenReady();
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // A foreground service outlives its activity. Explicitly stop detection when
        // the user removes Drishti's task from Recents.
        if (!shuttingDown) stopDetection(true);
        super.onTaskRemoved(rootIntent);
    }

    @Override public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return binder;
    }

    @Override public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener == null) return;
        postState(currentStatus(), modelReady, roadMode, detectionPaused, potholeModelAvailable);
        if (cameraPreview != null) post(() -> listener.onPreviewAvailable(cameraPreview));
        post(() -> listener.onLightStatus(lightStatusText()));
    }

    public void attachPreview(Preview.SurfaceProvider surfaceProvider, int rotation) {
        displayRotation = rotation;
        if (surfaceProvider == null) return;
        previewSurfaceProvider = surfaceProvider;
        previewAttached = true;
        Preview preview = cameraPreview;
        if (preview == null) return;
        preview.setTargetRotation(rotation);
        preview.setSurfaceProvider(ContextCompat.getMainExecutor(this), surfaceProvider);
    }

    public void detachPreview() {
        if (cameraPreview != null) cameraPreview.setSurfaceProvider(null);
        previewSurfaceProvider = null;
        previewAttached = false;
    }

    public void pauseDetection() {
        detectionPaused = !detectionPaused;
        previousFrameNs = 0;
        smoothedFps = 0f;
        if (detectionPaused && faceRecognitionManager != null) faceRecognitionManager.resetAppearances();
        Listener current = listener;
        if (current != null && detectionPaused) {
            post(() -> current.onDetections(Collections.emptyList(), lastFrameWidth, lastFrameHeight));
            post(() -> current.onPerformance("0.0 FPS  |  0 ms"));
        }
        postState(detectionPaused ? "Detection paused. Camera preview is still on." : currentStatus(),
                modelReady, roadMode, detectionPaused, potholeModelAvailable);
        updateNotification();
    }

    public void switchDetectionMode() {
        if (!modelReady || roadModeLoading || shuttingDown) return;
        if (!roadMode && !potholeModelAvailable) {
            postState("Pothole model is unavailable. Object mode is still working.", true,
                    false, detectionPaused, false);
            return;
        }
        roadMode = !roadMode;
        previousFrameNs = 0;
        smoothedFps = 0f;
        if (objectAlertSpeaker != null) objectAlertSpeaker.resetDetections();
        if (faceRecognitionManager != null) faceRecognitionManager.resetAppearances();
        if (listener != null) post(() -> listener.onDetections(Collections.emptyList(), lastFrameWidth, lastFrameHeight));
        if (roadMode && potholeDetector == null) {
            roadModeLoading = true;
            postState("Loading pothole detector. This may take a moment.", true, true,
                    detectionPaused, potholeModelAvailable);
            analysisExecutor.execute(() -> {
                try {
                    YoloDetector loaded = new YoloDetector(getApplicationContext(),
                            "pothole.tflite", "pothole_labels.txt");
                    if (shuttingDown) {
                        loaded.close();
                        return;
                    }
                    potholeDetector = loaded;
                    roadModeLoading = false;
                    if (hapticAlertManager != null) hapticAlertManager.play(HapticPattern.MODE_ROAD);
                    postState(currentStatus(), true, true, detectionPaused, true);
                    updateNotification();
                } catch (Exception | LinkageError | OutOfMemoryError e) {
                    Log.e(TAG, "Could not load pothole detector", e);
                    potholeModelAvailable = false;
                    roadMode = false;
                    roadModeLoading = false;
                    postState("Pothole model could not load. Object mode is still available.",
                            true, false, detectionPaused, false);
                    updateNotification();
                }
            });
            return;
        }
        if (hapticAlertManager != null) {
            hapticAlertManager.play(roadMode ? HapticPattern.MODE_ROAD : HapticPattern.MODE_OBJECT);
        }
        postState(currentStatus(), modelReady, roadMode, detectionPaused, potholeModelAvailable);
        updateNotification();
    }

    public void suspendForSettings() {
        suspendedForSettings = true;
        detachPreview();
        unbindCamera();
        postState("Detection paused while Settings is open.", modelReady, roadMode,
                detectionPaused, potholeModelAvailable);
        updateNotification();
    }

    public void resumeFromSettings() {
        suspendedForSettings = false;
        applySettings();
        if (faceRecognitionManager != null) {
            faceRecognitionManager.refreshProfiles();
            faceRecognitionManager.resetAppearances();
        }
        if (!shuttingDown) startCameraWhenReady();
    }

    public void requestSceneAnalysis() {
        if (geminiSceneAnalyzer == null || BuildConfig.GEMINI_API_KEY.trim().isEmpty()) {
            sceneResult("Gemini is not configured. Add geminiApiKey to the ignored local.properties file, then rebuild. This personal test key is embedded in the APK; do not share it.", true);
            return;
        }
        if (!cameraBound || imageAnalysis == null) {
            sceneResult("The camera preview is not ready. Try again when the preview is visible.", true);
            return;
        }
        if (!sceneAnalysisInProgress.compareAndSet(false, true)) {
            sceneResult("Scene analysis is already in progress. Please wait.", true);
            return;
        }
        if (hapticAlertManager != null) hapticAlertManager.play(HapticPattern.GEMINI_STARTED);
        sceneResult("Capturing one camera image for Gemini…", false);
        sceneFrameRequested.set(true);
        sceneCaptureTimeout = () -> {
            if (!sceneFrameRequested.compareAndSet(true, false)) return;
            sceneAnalysisInProgress.set(false);
            sceneResult("Could not capture a camera image. Please try again.", true);
        };
        mainHandler.postDelayed(sceneCaptureTimeout, 5_000L);
    }

    public void applySettings() {
        SharedPreferencesSnapshot settings = readSettings();
        autoTorchEnabled = settings.autoTorch;
        cameraBrightnessThreshold = settings.threshold;
        if (settings.language != speechLanguage) {
            speechLanguage = settings.language;
            offlineVoicePromptSent = false;
        }
        if (hapticAlertManager != null) hapticAlertManager.reloadPreferences();
        if (objectAlertSpeaker != null) {
            objectAlertSpeaker.setEnabled(settings.voiceAlerts);
            objectAlertSpeaker.resetHapticAnnouncements();
            applySpeechLanguage();
        }
        if (!autoTorchEnabled) {
            cancelTorchPulseTimeout();
            cancelPostPulseDarknessCheck();
            requestTorchState(false);
            unregisterLightSensor();
        } else registerLightSensorIfReady();
        if (listener != null) post(() -> listener.onLightStatus(lightStatusText()));
        updateNotification();
    }

    public void speakNotificationPermissionInfo() {
        ObjectAlertSpeaker speaker = objectAlertSpeaker;
        if (speaker != null) speaker.speakOperationalStatus(localize("Notifications are off. Detection continues, but the status notification may be hidden."), true);
    }

    private void applySpeechLanguage() {
        ObjectAlertSpeaker speaker = objectAlertSpeaker;
        if (speaker == null) return;
        speaker.setSpeechLanguage(speechLanguage, ready -> {
            if (!ready && speaker.isEnabled() && !offlineVoicePromptSent) {
                offlineVoicePromptSent = true;
                Listener current = listener;
                if (current != null) post(() -> current.onOfflineVoiceUnavailable(speechLanguage));
            }
            if (ready) maybeSpeakBackgroundPrompt(speaker);
        });
    }

    private void maybeSpeakBackgroundPrompt(ObjectAlertSpeaker speaker) {
        android.content.SharedPreferences prefs = getSharedPreferences(AppSettings.PREFERENCES, MODE_PRIVATE);
        if (prefs.getBoolean(AppSettings.BACKGROUND_PROMPT_SHOWN, false)) return;
        prefs.edit().putBoolean(AppSettings.BACKGROUND_PROMPT_SHOWN, true).apply();
        speaker.speakOperationalStatus(localize("Some phones may stop background detection. Allow Drishti to run without battery restrictions for more reliable alerts."), true);
    }

    private void loadModels() {
        try {
            if (!assetExists("yolo26n.tflite")) throw new IOException("Object model is missing.");
            YoloDetector loaded = new YoloDetector(getApplicationContext());
            if (shuttingDown) {
                loaded.close();
                return;
            }
            detector = loaded;
            modelReady = true;
            if (faceRecognitionManager != null) faceRecognitionManager.refreshProfiles();
            mainHandler.post(() -> {
                if (shuttingDown) return;
                postState(currentStatus(), true, roadMode, detectionPaused, potholeModelAvailable);
                startCameraWhenReady();
            });
        } catch (Exception | LinkageError | OutOfMemoryError e) {
            Log.e(TAG, "Could not load object detector", e);
            runState.markError();
            postState("Model error: " + e.getMessage(), false, roadMode, detectionPaused, false);
        }
    }

    private void startCameraWhenReady() {
        if (shuttingDown || suspendedForSettings || !modelReady || cameraBound || cameraStartPending
                || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return;
        cameraStartPending = true;
        if (cameraRetry != null) mainHandler.removeCallbacks(cameraRetry);
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (shuttingDown || suspendedForSettings || cameraBound) {
                cameraStartPending = false;
                return;
            }
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setTargetRotation(displayRotation);
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setTargetRotation(displayRotation)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyze);
                Camera camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, analysis);
                cameraPreview = preview;
                imageAnalysis = analysis;
                boundCamera = camera;
                cameraBound = true;
                cameraStartPending = false;
                runState.markRunning();
                retryDelayMs = 1_000L;
                camera.getCameraInfo().getCameraState().observe(this, state -> {
                    if (state != null && state.getError() != null && !shuttingDown) {
                        reportCameraProblem("Camera unavailable. Trying to reconnect.");
                        scheduleCameraRetry();
                    }
                });
                if (previewAttached && previewSurfaceProvider != null) {
                    preview.setSurfaceProvider(ContextCompat.getMainExecutor(this), previewSurfaceProvider);
                }
                if (previewAttached && listener != null) post(() -> listener.onPreviewAvailable(preview));
                cameraHasFlash = camera.getCameraInfo().hasFlashUnit();
                torchControlFailed = false;
                torchOn = false;
                lastAmbientLux = Float.NaN;
                lastRearLuminance = Float.NaN;
                registerLightSensorIfReady();
                if (cameraUnavailable) {
                    cameraUnavailable = false;
                    if (objectAlertSpeaker != null) objectAlertSpeaker.speakOperationalStatus(localize("Camera restored."), true);
                } else if (!startupAnnounced) {
                    startupAnnounced = true;
                    if (objectAlertSpeaker != null) objectAlertSpeaker.speakOperationalStatus(localize("Drishti started."), true);
                    if (hapticAlertManager != null) hapticAlertManager.play(HapticPattern.MODE_OBJECT);
                }
                postState(currentStatus(), true, roadMode, detectionPaused, potholeModelAvailable);
                if (listener != null) post(() -> listener.onLightStatus(lightStatusText()));
            } catch (Exception | LinkageError e) {
                Log.e(TAG, "Camera binding failed", e);
                unbindCamera();
                runState.markError();
                reportCameraProblem("Camera unavailable. Trying to reconnect.");
                scheduleCameraRetry();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void scheduleCameraRetry() {
        if (shuttingDown || suspendedForSettings || cameraRetry != null) return;
        long delay = retryDelayMs;
        retryDelayMs = Math.min(30_000L, retryDelayMs * 2L);
        cameraRetry = () -> {
            cameraRetry = null;
            unbindCamera();
            runState.requestStart();
            startCameraWhenReady();
        };
        mainHandler.postDelayed(cameraRetry, delay);
    }

    private void reportCameraProblem(String message) {
        cameraUnavailable = true;
        runState.markError();
        postState(message, modelReady, roadMode, detectionPaused, potholeModelAvailable);
        if (objectAlertSpeaker != null) objectAlertSpeaker.speakOperationalStatus(localize(message), true);
    }

    private void analyze(@NonNull ImageProxy image) {
        long started = System.nanoTime();
        try {
            evaluateRearLightFromFrame(image);
            long nowMs = SystemClock.elapsedRealtime();
            if (minimumFrameIntervalMs > 0 && nowMs - lastAnalyzedAtMs < minimumFrameIntervalMs) {
                captureSceneFrameIfRequested(image);
                return;
            }
            lastAnalyzedAtMs = nowMs;
            boolean frameRoadMode = roadMode;
            YoloDetector activeDetector = frameRoadMode ? potholeDetector : detector;
            int rotation = image.getImageInfo().getRotationDegrees();
            int width = rotation == 90 || rotation == 270 ? image.getHeight() : image.getWidth();
            int height = rotation == 90 || rotation == 270 ? image.getWidth() : image.getHeight();
            lastFrameWidth = width;
            lastFrameHeight = height;
            if (activeDetector == null || detectionPaused || activePowerStop()) {
                captureSceneFrameIfRequested(image);
                return;
            }
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            java.nio.ByteBuffer pixels = plane.getBuffer();
            pixels.rewind();
            List<Detection> detections = activeDetector.detect(pixels, plane.getRowStride(),
                    plane.getPixelStride(), image.getWidth(), image.getHeight(), rotation);
            if (frameRoadMode != roadMode || detectionPaused) {
                captureSceneFrameIfRequested(image);
                return;
            }
            long elapsedNs = System.nanoTime() - started;
            if (previousFrameNs != 0) {
                float instant = 1_000_000_000f / (System.nanoTime() - previousFrameNs);
                smoothedFps = smoothedFps == 0f ? instant : smoothedFps * 0.85f + instant * 0.15f;
            }
            previousFrameNs = System.nanoTime();
            Listener current = listener;
            if (current != null) {
                List<Detection> snapshot = new ArrayList<>(detections);
                post(() -> current.onDetections(snapshot, width, height));
                if (SystemClock.elapsedRealtime() - previousPerformanceUpdateNs >= 250L) {
                    previousPerformanceUpdateNs = SystemClock.elapsedRealtime();
                    String metrics = String.format(Locale.US, "%.1f FPS  |  %d ms", smoothedFps,
                            TimeUnit.NANOSECONDS.toMillis(elapsedNs));
                    post(() -> current.onPerformance(metrics));
                }
            }
            FaceRecognitionManager faceManager = faceRecognitionManager;
            ObjectAlertSpeaker speaker = objectAlertSpeaker;
            boolean faceAlertsActive = !frameRoadMode && faceManager != null && speaker != null
                    && (speaker.isEnabled() || hapticAlertManager != null && hapticAlertManager.isEnabled())
                    && faceManager.hasProfiles();
            if (faceAlertsActive) {
                List<Detection> people = new ArrayList<>();
                List<Detection> otherObjects = new ArrayList<>();
                for (Detection item : detections) {
                    if ("person".equalsIgnoreCase(item.label)) people.add(item);
                    else otherObjects.add(item);
                }
                faceManager.onPersonDetections(people, width);
                if (faceManager.hasRecentPersonTrack() && faceManager.isReady()
                        && faceManager.canAcceptSampledFrame()) {
                    CameraBitmapUtils.FrameBitmap frame = CameraBitmapUtils.fromRgba(image,
                            CameraBitmapUtils.FACE_FRAME_MAX_DIMENSION);
                    faceManager.onSampledFrame(frame, width);
                }
                speaker.onDetections(otherObjects, width, height);
            } else if (speaker != null) speaker.onDetections(detections, width, height);
            captureSceneFrameIfRequested(image);
        } catch (Exception | LinkageError e) {
            Log.e(TAG, "Frame inference failed", e);
        } finally {
            image.close();
        }
    }

    private void captureSceneFrameIfRequested(@NonNull ImageProxy image) {
        if (!sceneFrameRequested.compareAndSet(true, false)) return;
        if (sceneCaptureTimeout != null) mainHandler.removeCallbacks(sceneCaptureTimeout);
        sceneCaptureTimeout = null;
        sceneResult("Analyzing the scene with Gemini…", false);
        GeminiSceneAnalyzer analyzer = geminiSceneAnalyzer;
        if (analyzer == null) {
            sceneAnalysisInProgress.set(false);
            sceneResult("Scene analysis is unavailable right now.", true);
            return;
        }
        analyzer.analyze(image, speechLanguage, new GeminiSceneAnalyzer.Callback() {
            @Override public void onSuccess(String description) {
                sceneAnalysisInProgress.set(false);
                if (hapticAlertManager != null) hapticAlertManager.play(HapticPattern.GEMINI_DONE);
                if (objectAlertSpeaker != null && objectAlertSpeaker.isEnabled()) {
                    objectAlertSpeaker.speakSceneSummary(description);
                }
                sceneResult(description, false);
            }
            @Override public void onFailure(String message) {
                sceneAnalysisInProgress.set(false);
                sceneResult(message, true);
            }
        });
    }

    private void sceneResult(String message, boolean error) {
        if (error && hapticAlertManager != null) hapticAlertManager.play(HapticPattern.GEMINI_ERROR);
        Listener current = listener;
        if (current != null) post(() -> current.onSceneResult(message, error));
    }

    private boolean activePowerStop() {
        return batteryPercent <= 5 || thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE;
    }

    private void registerPowerMonitors() {
        batteryReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) batteryPercent = Math.round(level * 100f / scale);
                updatePowerPolicy();
            }
        };
        Intent sticky = registerReceiver(batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        receiverRegistered = true;
        if (sticky != null) batteryReceiver.onReceive(this, sticky);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            thermalStatus = powerManager.getCurrentThermalStatus();
            thermalListener = status -> {
                thermalStatus = status;
                updatePowerPolicy();
            };
            powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(this), thermalListener);
        }
    }

    private void updatePowerPolicy() {
        boolean warning = batteryPercent < 15 || thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE;
        minimumFrameIntervalMs = warning ? 500L : 0L;
        if (warning && !lowPowerWarningSpoken && objectAlertSpeaker != null) {
            lowPowerWarningSpoken = true;
            objectAlertSpeaker.speakOperationalStatus(localize("Low battery or high temperature. Detection speed reduced."), true);
        }
        if (!warning) lowPowerWarningSpoken = false;
        if (activePowerStop()) stopDetection(false);
    }

    private void stopDetection(boolean userInitiated) {
        if (shuttingDown) return;
        shuttingDown = true;
        runState.requestStop();
        if (objectAlertSpeaker != null) {
            objectAlertSpeaker.speakOperationalStatus(localize(userInitiated ? "Drishti stopped." : "Detection stopped for device safety."), true);
        }
        if (hapticAlertManager != null) hapticAlertManager.play(HapticPattern.MODE_OBJECT);
        postState(userInitiated ? "Detection stopped. Reopen Drishti to resume."
                : "Detection stopped for device safety.", false, roadMode, true, potholeModelAvailable);
        releaseCameraAndModels();
        mainHandler.postDelayed(() -> {
            if (objectAlertSpeaker != null) objectAlertSpeaker.close();
            if (hapticAlertManager != null) hapticAlertManager.close();
            runState.markStopped();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }, 1_800L);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Drishti detection", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when camera detection is active");
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else startForeground(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 100, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = serviceAction(ACTION_STOP, 101);
        PendingIntent mode = serviceAction(ACTION_SWITCH_MODE, 102);
        SpeechLanguage language = speechLanguage;
        String title = language == SpeechLanguage.HINDI ? "दृष्टि आपके रास्ते पर नज़र रख रहा है"
                : language == SpeechLanguage.MARATHI ? "दृष्टी तुमच्या मार्गावर लक्ष ठेवत आहे"
                : "Drishti is watching your path";
        String text = suspendedForSettings ? "Paused while Settings is open"
                : detectionPaused ? "Detection paused" : roadMode ? "Road mode · pothole detection active" : "Object detection active";
        if (language == SpeechLanguage.HINDI) text = suspendedForSettings ? "सेटिंग्ज में रुका हुआ"
                : detectionPaused ? "पहचान रुकी हुई है"
                : roadMode ? "सड़क मोड · गड्ढा पहचान चालू" : "वस्तु पहचान चालू";
        if (language == SpeechLanguage.MARATHI) text = suspendedForSettings ? "सेटिंग्जमध्ये थांबवले"
                : detectionPaused ? "ओळख थांबवली आहे"
                : roadMode ? "रस्ता मोड · खड्डा ओळख सुरू" : "वस्तू ओळख सुरू";
        String stopLabel = language == SpeechLanguage.HINDI ? "रोकें"
                : language == SpeechLanguage.MARATHI ? "थांबवा" : "Stop";
        String switchLabel = language == SpeechLanguage.HINDI ? "मोड बदलें"
                : language == SpeechLanguage.MARATHI ? "मोड बदला" : "Switch mode";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, switchLabel, mode)
                .addAction(0, stopLabel, stop)
                .build();
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        Intent intent = new Intent(this, DrishtiService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void unbindCamera() {
        awaitingPostPulseReading = false;
        cancelTorchPulseTimeout();
        cancelPostPulseDarknessCheck();
        unregisterLightSensor();
        requestTorchState(false);
        ProcessCameraProvider provider = cameraProvider;
        if (provider != null) {
            try {
                if (cameraPreview != null && imageAnalysis != null) provider.unbind(cameraPreview, imageAnalysis);
                else if (cameraPreview != null) provider.unbind(cameraPreview);
                else if (imageAnalysis != null) provider.unbind(imageAnalysis);
            } catch (RuntimeException e) { Log.w(TAG, "Could not unbind camera use cases", e); }
        }
        cameraPreview = null;
        imageAnalysis = null;
        boundCamera = null;
        cameraBound = false;
        cameraStartPending = false;
        cameraHasFlash = false;
        torchOn = false;
    }

    private void registerLightSensorIfReady() {
        if (!autoTorchEnabled || !cameraBound || boundCamera == null || !cameraHasFlash
                || torchControlFailed || ambientLightSensor == null || sensorManager == null
                || lightSensorRegistered) return;
        lightSensorRegistered = sensorManager.registerListener(this, ambientLightSensor,
                SensorManager.SENSOR_DELAY_NORMAL, mainHandler);
    }

    private void unregisterLightSensor() {
        if (sensorManager != null && lightSensorRegistered) sensorManager.unregisterListener(this, ambientLightSensor);
        lightSensorRegistered = false;
        darkSinceMs = 0;
        cancelTorchPulseTimeout();
        cancelPostPulseDarknessCheck();
        awaitingPostPulseReading = false;
        lastRearLuminance = Float.NaN;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.values.length == 0 || !autoTorchEnabled || !cameraBound
                || !cameraHasFlash || torchControlFailed || torchOn
                || event.sensor.getType() != Sensor.TYPE_LIGHT) return;
        float lux = event.values[0];
        if (Float.isNaN(lux) || Float.isInfinite(lux) || lux < 0f) return;
        lastAmbientLux = lux;
        if (SystemClock.elapsedRealtime() - lastLightStatusUpdateMs >= 1_000L) {
            lastLightStatusUpdateMs = SystemClock.elapsedRealtime();
            Listener current = listener;
            if (current != null) post(() -> current.onLightStatus(lightStatusText()));
        }
        long now = SystemClock.elapsedRealtime();
        if (awaitingPostPulseReading) {
            awaitingPostPulseReading = false;
            darkSinceMs = 0;
            if (lux <= TORCH_DARKNESS_THRESHOLD) schedulePostPulseDarknessCheck();
            return;
        }
        if (postPulseDarknessCheck != null) return;
        if (lux <= TORCH_DARKNESS_THRESHOLD) {
            if (darkSinceMs == 0) darkSinceMs = now;
            if (now - darkSinceMs >= TORCH_THRESHOLD_DWELL_MS) requestTorchState(true);
        } else darkSinceMs = 0;
    }

    private void evaluateRearLightFromFrame(@NonNull ImageProxy image) {
        if (!autoTorchEnabled || !cameraBound || !cameraHasFlash || torchControlFailed
                || ambientLightSensor != null && lightSensorRegistered || torchOn) return;
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        java.nio.ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        int pixelStride = plane.getPixelStride(), rowStride = plane.getRowStride();
        int width = image.getWidth(), height = image.getHeight();
        long total = 0;
        int samples = 0;
        for (int y = 0; y < height; y += 16) for (int x = 0; x < width; x += 16) {
            int index = y * rowStride + x * pixelStride;
            if (index + 2 >= buffer.remaining()) continue;
            int r = buffer.get(index) & 0xff, g = buffer.get(index + 1) & 0xff, b = buffer.get(index + 2) & 0xff;
            total += (299 * r + 587 * g + 114 * b) / 1000;
            samples++;
        }
        if (samples == 0) return;
        float luminance = total / (float) samples;
        lastRearLuminance = luminance;
        long now = SystemClock.elapsedRealtime();
        if (now - lastLightStatusUpdateMs >= 1_000L) {
            lastLightStatusUpdateMs = now;
            Listener current = listener;
            if (current != null) post(() -> current.onLightStatus(lightStatusText()));
        }
        if (awaitingPostPulseReading) {
            awaitingPostPulseReading = false;
            darkSinceMs = 0;
            if (luminance <= cameraBrightnessThreshold) schedulePostPulseDarknessCheck();
            return;
        }
        if (postPulseDarknessCheck != null) return;
        if (luminance <= cameraBrightnessThreshold) {
            if (darkSinceMs == 0) darkSinceMs = now;
            if (now - darkSinceMs >= TORCH_THRESHOLD_DWELL_MS) mainHandler.post(() -> requestTorchState(true));
        } else darkSinceMs = 0;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void requestTorchState(boolean enabled) {
        Camera camera = boundCamera;
        if (!enabled) cancelTorchPulseTimeout();
        if (camera == null || !cameraHasFlash || torchOn == enabled || enabled && (!autoTorchEnabled || torchControlFailed)) return;
        torchOn = enabled;
        darkSinceMs = 0;
        if (enabled) {
            cancelPostPulseDarknessCheck();
            awaitingPostPulseReading = false;
            lastAmbientLux = Float.NaN;
            lastRearLuminance = Float.NaN;
            scheduleTorchPulseTimeout();
        } else {
            lastAmbientLux = Float.NaN;
            lastRearLuminance = Float.NaN;
        }
        ListenableFuture<Void> request = camera.getCameraControl().enableTorch(enabled);
        request.addListener(() -> {
            try { request.get(); }
            catch (Exception e) {
                Log.w(TAG, "Torch request failed", e);
                if (boundCamera == camera) {
                    torchOn = false;
                    torchControlFailed = true;
                    unregisterLightSensor();
                }
            }
        }, ContextCompat.getMainExecutor(this));
        Listener current = listener;
        if (current != null) post(() -> current.onLightStatus(lightStatusText()));
    }

    private void scheduleTorchPulseTimeout() {
        cancelTorchPulseTimeout();
        torchPulseTimeout = () -> {
            torchPulseTimeout = null;
            if (!torchOn || !autoTorchEnabled || !cameraBound || !cameraHasFlash) return;
            awaitingPostPulseReading = true;
            requestTorchState(false);
        };
        mainHandler.postDelayed(torchPulseTimeout, TORCH_PULSE_DURATION_MS);
    }

    private void cancelTorchPulseTimeout() {
        if (torchPulseTimeout != null) mainHandler.removeCallbacks(torchPulseTimeout);
        torchPulseTimeout = null;
    }

    private void schedulePostPulseDarknessCheck() {
        cancelPostPulseDarknessCheck();
        postPulseDarknessCheck = () -> {
            postPulseDarknessCheck = null;
            boolean dark = ambientLightSensor != null && lightSensorRegistered
                    ? !Float.isNaN(lastAmbientLux) && lastAmbientLux <= TORCH_DARKNESS_THRESHOLD
                    : !Float.isNaN(lastRearLuminance) && lastRearLuminance <= cameraBrightnessThreshold;
            if (autoTorchEnabled && cameraBound && cameraHasFlash && !torchOn
                    && !torchControlFailed && dark) requestTorchState(true);
            else darkSinceMs = 0;
        };
        mainHandler.postDelayed(postPulseDarknessCheck, TORCH_POST_PULSE_RECHECK_MS);
    }

    private void cancelPostPulseDarknessCheck() {
        if (postPulseDarknessCheck != null) mainHandler.removeCallbacks(postPulseDarknessCheck);
        postPulseDarknessCheck = null;
    }

    private String lightStatusText() {
        if (!cameraBound || boundCamera == null) return "Rear light sensor status unavailable while camera is closed";
        if (!cameraHasFlash) return "Back camera has no flash";
        if (!autoTorchEnabled) return "Auto torch off · Flash off";
        if (torchOn) return "Auto torch on for up to 25 seconds";
        if (ambientLightSensor != null && lightSensorRegistered) {
            return Float.isNaN(lastAmbientLux) ? "Rear sensor " + ambientLightSensor.getName() + ": waiting for reading"
                    : String.format(Locale.US, "Rear sensor %s: %.2f · dark at ≤%.2f · Flash off",
                    ambientLightSensor.getName(), lastAmbientLux, TORCH_DARKNESS_THRESHOLD);
        }
        return Float.isNaN(lastRearLuminance) ? "Rear camera brightness fallback · waiting for frame"
                : String.format(Locale.US, "Rear camera brightness %.1f / 255 · dark at ≤%d · Flash off",
                lastRearLuminance, cameraBrightnessThreshold);
    }

    private Sensor findRearLightSensor() {
        if (sensorManager == null) return null;
        List<Sensor> lightSensors = sensorManager.getSensorList(Sensor.TYPE_LIGHT);
        for (int pass = 0; pass <= 1; pass++) for (Sensor sensor : lightSensors) {
            if (sensor.isWakeUpSensor() != (pass == 1)) continue;
            String text = (sensor.getName() + " " + sensor.getVendor() + " " + sensor.getStringType()).toLowerCase(Locale.ROOT);
            if (text.contains("back") || text.contains("rear")) return sensor;
        }
        Sensor defaultSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (lightSensors.size() == 2 && defaultSensor != null) for (Sensor sensor : lightSensors) {
            if (sensor.getId() != defaultSensor.getId()) return sensor;
        }
        return null;
    }

    private String currentStatus() {
        if (roadModeLoading) return "Loading pothole detector…";
        if (!modelReady) return "Loading object detection model…";
        return roadMode ? "Road mode is on. Detecting potholes only."
                : "Object mode is on. Detecting the usual objects.";
    }

    private void postState(String value, boolean ready, boolean road, boolean paused, boolean roadAvailable) {
        Listener current = listener;
        if (current != null) post(() -> current.onServiceState(value, ready, road, paused, roadAvailable));
    }

    private void post(Runnable runnable) { mainHandler.post(runnable); }

    private boolean assetExists(String name) {
        try (java.io.InputStream ignored = getAssets().open(name)) { return true; }
        catch (IOException e) { return false; }
    }

    private SharedPreferencesSnapshot readSettings() {
        android.content.SharedPreferences preferences = getSharedPreferences(AppSettings.PREFERENCES, MODE_PRIVATE);
        return new SharedPreferencesSnapshot(
                preferences.getBoolean(AppSettings.AUTO_TORCH_ENABLED, true),
                preferences.getInt(AppSettings.CAMERA_DARKNESS_THRESHOLD, AppSettings.DEFAULT_CAMERA_DARKNESS_THRESHOLD),
                preferences.getBoolean(AppSettings.VOICE_ALERTS_ENABLED, true),
                SpeechLanguage.fromPreference(preferences.getString(AppSettings.SPEECH_LANGUAGE,
                        AppSettings.DEFAULT_SPEECH_LANGUAGE)));
    }

    private String localize(String english) {
        if (speechLanguage == SpeechLanguage.HINDI) {
            if (english.startsWith("Drishti started")) return "दृष्टि शुरू हो गया।";
            if (english.startsWith("Drishti stopped")) return "दृष्टि बंद हो गया।";
            if (english.startsWith("Camera unavailable")) return "कैमरा उपलब्ध नहीं है। फिर से जोड़ने की कोशिश की जा रही है।";
            if (english.startsWith("Camera restored")) return "कैमरा फिर से शुरू हो गया।";
            if (english.startsWith("Low battery")) return "बैटरी कम है या फोन गर्म है। पहचान की गति कम की गई है।";
            if (english.startsWith("Battery critically")) return "बैटरी बहुत कम है या फोन बहुत गर्म है। पहचान रोक दी गई है।";
            if (english.startsWith("Detection stopped")) return "बैटरी बहुत कम है या फोन बहुत गर्म है। पहचान रोक दी गई है।";
            if (english.startsWith("Notifications are off")) return "सूचनाएं बंद हैं। पहचान जारी रहेगी, लेकिन स्थिति सूचना छिपी हो सकती है।";
            if (english.startsWith("Some phones")) return "कुछ फोन बैकग्राउंड में पहचान बंद कर सकते हैं। बेहतर अलर्ट के लिए Drishti को बैटरी प्रतिबंधों से छूट दें।";
        } else if (speechLanguage == SpeechLanguage.MARATHI) {
            if (english.startsWith("Drishti started")) return "दृष्टी सुरू झाले.";
            if (english.startsWith("Drishti stopped")) return "दृष्टी थांबवले.";
            if (english.startsWith("Camera unavailable")) return "कॅमेरा उपलब्ध नाही. पुन्हा जोडण्याचा प्रयत्न सुरू आहे.";
            if (english.startsWith("Camera restored")) return "कॅमेरा पुन्हा सुरू झाला.";
            if (english.startsWith("Low battery")) return "बॅटरी कमी आहे किंवा फोन गरम आहे. ओळखीचा वेग कमी केला आहे.";
            if (english.startsWith("Battery critically")) return "बॅटरी खूप कमी आहे किंवा फोन खूप गरम आहे. ओळख थांबवली आहे.";
            if (english.startsWith("Detection stopped")) return "बॅटरी खूप कमी आहे किंवा फोन खूप गरम आहे. ओळख थांबवली आहे.";
            if (english.startsWith("Notifications are off")) return "सूचना बंद आहेत. ओळख सुरू राहील, पण स्थिती सूचना लपलेली असू शकते.";
            if (english.startsWith("Some phones")) return "काही फोन पार्श्वभूमीतील ओळख बंद करू शकतात. चांगल्या अलर्टसाठी Drishti ला बॅटरी निर्बंधांमधून सूट द्या.";
        }
        return english;
    }

    private void releaseCameraAndModels() {
        if (cameraRetry != null) mainHandler.removeCallbacks(cameraRetry);
        cameraRetry = null;
        if (sceneCaptureTimeout != null) mainHandler.removeCallbacks(sceneCaptureTimeout);
        sceneCaptureTimeout = null;
        sceneFrameRequested.set(false);
        sceneAnalysisInProgress.set(false);
        cancelTorchPulseTimeout();
        cancelPostPulseDarknessCheck();
        unregisterLightSensor();
        requestTorchState(false);
        unbindCamera();
        if (faceRecognitionManager != null) faceRecognitionManager.close();
        if (geminiSceneAnalyzer != null) geminiSceneAnalyzer.close();
        if (detector != null) detector.close();
        YoloDetector road = potholeDetector;
        potholeDetector = null;
        if (road != null) road.close();
    }

    private void cleanupCameraAndModels() {
        releaseCameraAndModels();
        if (objectAlertSpeaker != null) objectAlertSpeaker.close();
        if (hapticAlertManager != null) hapticAlertManager.close();
    }

    @Override public void onDestroy() {
        boolean unexpectedStop = !shuttingDown && modelReady;
        shuttingDown = true;
        cleanupCameraAndModels();
        runState.markStopped();
        if (receiverRegistered && batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (IllegalArgumentException ignored) { }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null && powerManager != null) {
            powerManager.removeThermalStatusListener(thermalListener);
        }
        analysisExecutor.shutdownNow();
        if (unexpectedStop) showReopenNotification();
        super.onDestroy();
    }

    private void showReopenNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 103, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Detection stopped")
                .setContentText("Reopen Drishti to resume camera detection.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID + 1, notification);
    }

    private static final class SharedPreferencesSnapshot {
        final boolean autoTorch;
        final int threshold;
        final boolean voiceAlerts;
        final SpeechLanguage language;
        SharedPreferencesSnapshot(boolean autoTorch, int threshold, boolean voiceAlerts, SpeechLanguage language) {
            this.autoTorch = autoTorch;
            this.threshold = threshold;
            this.voiceAlerts = voiceAlerts;
            this.language = language;
        }
    }
}
