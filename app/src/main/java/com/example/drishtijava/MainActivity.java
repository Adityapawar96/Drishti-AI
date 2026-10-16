package com.example.drishtijava;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.example.drishtijava.face.CameraBitmapUtils;
import com.example.drishtijava.face.FaceRecognitionManager;
import com.example.drishtijava.face.KnownPeopleActivity;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "DrishtiDetector";
    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView statusText;
    private TextView sceneAnalysisText;
    private TextView performanceText;
    private Button voiceAlertsButton;
    private Button knownPeopleButton;
    private Button detectionModeButton;
    private Button pauseDetectionButton;
    private Button helpButton;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private Preview cameraPreview;
    private ImageAnalysis imageAnalysis;
    private YoloDetector detector;
    private volatile YoloDetector potholeDetector;
    private GeminiSceneAnalyzer geminiSceneAnalyzer;
    private ObjectAlertSpeaker objectAlertSpeaker;
    private FaceRecognitionManager faceRecognitionManager;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<android.content.Intent> knownPeopleLauncher;
    private boolean modelReady;
    private volatile boolean potholeModelAvailable;
    private volatile boolean roadModeLoading;
    private boolean cameraStartPending;
    private boolean cameraBound;
    private volatile boolean detectionPaused;
    private volatile boolean roadMode;
    private volatile int lastFrameWidth;
    private volatile int lastFrameHeight;
    private volatile long previousFrameNs;
    private long previousTimingLogNs;
    private long previousPerformanceUpdateNs;
    private volatile float smoothedFps;
    private final AtomicBoolean sceneFrameRequested = new AtomicBoolean(false);
    private final AtomicBoolean sceneAnalysisInProgress = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Runnable sceneCaptureTimeout;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        statusText = findViewById(R.id.statusText);
        sceneAnalysisText = findViewById(R.id.sceneAnalysisText);
        performanceText = findViewById(R.id.performanceText);
        voiceAlertsButton = findViewById(R.id.voiceAlertsButton);
        knownPeopleButton = findViewById(R.id.knownPeopleButton);
        detectionModeButton = findViewById(R.id.detectionModeButton);
        pauseDetectionButton = findViewById(R.id.pauseDetectionButton);
        helpButton = findViewById(R.id.helpButton);
        objectAlertSpeaker = new ObjectAlertSpeaker(getApplicationContext());
        geminiSceneAnalyzer = new GeminiSceneAnalyzer(BuildConfig.GEMINI_API_KEY);
        faceRecognitionManager = new FaceRecognitionManager(getApplicationContext(), objectAlertSpeaker);
        knownPeopleLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    detectionPaused = false;
                    previousFrameNs = 0;
                    updateDetectionButton();
                    if (faceRecognitionManager != null) faceRecognitionManager.resetAppearances();
                    startCameraWhenReady();
                });
        knownPeopleButton.setOnClickListener(view -> knownPeopleLauncher.launch(
                new android.content.Intent(this, KnownPeopleActivity.class)));
        potholeModelAvailable = assetExists("pothole.tflite") && assetExists("pothole_labels.txt");
        detectionModeButton.setOnClickListener(view -> switchDetectionMode());
        updateDetectionModeButton();
        updateDetectionButton();
        pauseDetectionButton.setOnClickListener(view -> {
            detectionPaused = !detectionPaused;
            updateDetectionButton();
            if (detectionPaused) {
                if (faceRecognitionManager != null) faceRecognitionManager.resetAppearances();
                overlayView.update(java.util.Collections.emptyList(), lastFrameWidth, lastFrameHeight);
                performanceText.setText("0.0 FPS  |  0 ms");
                statusText.setText("Detection paused. Camera preview is still on.");
            } else {
                previousFrameNs = 0;
                statusText.setText(detectionStatusMessage());
            }
        });
        helpButton.setOnClickListener(view -> showHelp());
        updateVoiceAlertsButton(true);
        voiceAlertsButton.setOnClickListener(view -> {
            boolean enabled = !objectAlertSpeaker.isEnabled();
            objectAlertSpeaker.setEnabled(enabled);
            if (faceRecognitionManager != null) faceRecognitionManager.resetAppearances();
            updateVoiceAlertsButton(enabled);
        });
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        configureSceneAnalysisGesture();
        cameraExecutor = Executors.newSingleThreadExecutor();
        cameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) startCameraWhenReady();
            else statusText.setText("Camera permission denied. Allow camera access in Settings to detect objects.");
        });
        if (!assetExists("yolo26n.tflite")) {
            statusText.setText("Model not found: add yolo26n.tflite to app/src/main/assets/");
            return;
        }
        statusText.setText("Loading object detection model…");
        cameraExecutor.execute(() -> {
            try {
                detector = new YoloDetector(getApplicationContext());
                modelReady = true;
                runOnUiThread(() -> {
                    updateDetectionModeButton();
                    requestCameraPermissionIfNeeded();
                });
            } catch (Exception e) {
                Log.e(TAG, "Could not load detector", e);
                runOnUiThread(() -> statusText.setText("Model error: " + e.getMessage()));
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (faceRecognitionManager != null) faceRecognitionManager.refreshProfiles();
        if (modelReady && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCameraWhenReady();
        }
    }

    @Override protected void onPause() {
        if (sceneFrameRequested.compareAndSet(true, false)) {
            sceneAnalysisInProgress.set(false);
            Runnable timeout = sceneCaptureTimeout;
            if (timeout != null) mainHandler.removeCallbacks(timeout);
            sceneCaptureTimeout = null;
            sceneAnalysisText.setText("Scene analysis canceled because the camera was closed.");
        }
        // Release only this screen's use cases. Enrollment owns and unbinds its own camera.
        unbindMainCamera();
        super.onPause();
    }

    private boolean assetExists(String name) {
        try (java.io.InputStream ignored = getAssets().open(name)) { return true; }
        catch (IOException e) { return false; }
    }

    private void requestCameraPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraWhenReady();
        } else {
            statusText.setText("Camera permission is needed to detect objects.");
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCameraWhenReady() {
        if (!modelReady || isFinishing() || cameraStartPending || cameraBound) return;
        cameraStartPending = true;
        statusText.setText("Starting back camera…");
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                if (isFinishing() || isDestroyed()
                        || !getLifecycle().getCurrentState().isAtLeast(
                        androidx.lifecycle.Lifecycle.State.STARTED)) return;
                ProcessCameraProvider provider = providerFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setTargetRotation(previewView.getDisplay() == null ? android.view.Surface.ROTATION_0
                                : previewView.getDisplay().getRotation())
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyze);
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                cameraProvider = provider;
                cameraPreview = preview;
                imageAnalysis = analysis;
                cameraBound = true;
                statusText.setText(detectionPaused ? "Detection paused. Camera preview is still on."
                        : detectionStatusMessage());
            } catch (Exception e) {
                Log.e(TAG, "Camera setup failed", e);
                cameraBound = false;
                statusText.setText("Camera error: " + e.getMessage());
            } finally {
                cameraStartPending = false;
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void unbindMainCamera() {
        if (cameraProvider != null) {
            try {
                if (cameraPreview != null && imageAnalysis != null) {
                    cameraProvider.unbind(cameraPreview, imageAnalysis);
                } else if (cameraPreview != null) {
                    cameraProvider.unbind(cameraPreview);
                } else if (imageAnalysis != null) {
                    cameraProvider.unbind(imageAnalysis);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not release the main camera use cases", e);
            }
        }
        cameraPreview = null;
        imageAnalysis = null;
        cameraBound = false;
    }

    private void configureSceneAnalysisGesture() {
        previewView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        previewView.setContentDescription("Live camera preview. Double tap to send one camera image to Google Gemini for a scene description.");
        // The touch listener consumes regular taps so a single tap does not trigger analysis.
        // TalkBack and keyboard activation use performClick() and still invoke the action.
        previewView.setOnClickListener(view -> requestSceneAnalysis());
        GestureDetector gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent event) { return true; }

                    @Override public boolean onDoubleTap(MotionEvent event) {
                        requestSceneAnalysis();
                        return true;
                    }
                });
        previewView.setOnTouchListener((view, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void requestSceneAnalysis() {
        if (BuildConfig.GEMINI_API_KEY == null || BuildConfig.GEMINI_API_KEY.trim().isEmpty()) {
            showSceneAnalysisMessage("Gemini is not configured. Add geminiApiKey=YOUR_KEY to the ignored local.properties file, then rebuild. This personal test key is embedded in the APK; do not share the APK.");
            return;
        }
        if (!cameraBound || imageAnalysis == null) {
            showSceneAnalysisMessage("The camera preview is not ready. Try again when the preview is visible.");
            return;
        }
        if (!sceneAnalysisInProgress.compareAndSet(false, true)) {
            showSceneAnalysisMessage("Scene analysis is already in progress. Please wait.");
            return;
        }

        sceneAnalysisText.setVisibility(View.VISIBLE);
        sceneAnalysisText.setText("Capturing one camera image for Gemini…");
        sceneFrameRequested.set(true);
        Runnable timeout = () -> {
            if (sceneFrameRequested.compareAndSet(true, false)) {
                sceneAnalysisInProgress.set(false);
                sceneCaptureTimeout = null;
                showSceneAnalysisMessage("Could not capture a camera image. Please try again.");
            }
        };
        sceneCaptureTimeout = timeout;
        mainHandler.postDelayed(timeout, 5000);
    }

    private void captureSceneFrameIfRequested(@NonNull ImageProxy image) {
        if (!sceneFrameRequested.compareAndSet(true, false)) return;
        Runnable timeout = sceneCaptureTimeout;
        if (timeout != null) mainHandler.removeCallbacks(timeout);
        sceneCaptureTimeout = null;
        try {
            sceneAnalysisText.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    sceneAnalysisText.setText("Analyzing the scene with Gemini…");
                }
            });
            geminiSceneAnalyzer.analyze(image, new GeminiSceneAnalyzer.Callback() {
                @Override public void onSuccess(String description) {
                    runOnUiThread(() -> finishSceneAnalysis(description, null));
                }

                @Override public void onFailure(String message) {
                    runOnUiThread(() -> finishSceneAnalysis(null, message));
                }
            });
        } catch (Exception | OutOfMemoryError e) {
            Log.e(TAG, "Could not capture scene-analysis frame", e);
            runOnUiThread(() -> finishSceneAnalysis(null,
                    "Could not prepare the camera image. Please try again."));
        }
    }

    private void finishSceneAnalysis(String description, String error) {
        sceneAnalysisInProgress.set(false);
        if (isFinishing() || isDestroyed()) return;
        if (error != null) {
            showSceneAnalysisMessage(error);
            return;
        }
        sceneAnalysisText.setVisibility(View.VISIBLE);
        sceneAnalysisText.setText(description);
        if (objectAlertSpeaker != null && objectAlertSpeaker.isEnabled()) {
            objectAlertSpeaker.speakSceneSummary(description);
        }
    }

    private void showSceneAnalysisMessage(String message) {
        sceneAnalysisText.setVisibility(View.VISIBLE);
        sceneAnalysisText.setText(message);
    }

    private void analyze(@NonNull ImageProxy image) {
        long started = System.nanoTime();
        try {
            boolean frameRoadMode = roadMode;
            YoloDetector activeDetector = frameRoadMode ? potholeDetector : detector;
            if (activeDetector == null) {
                captureSceneFrameIfRequested(image);
                return;
            }
            int rotation = image.getImageInfo().getRotationDegrees();
            int rotatedWidth = rotation == 90 || rotation == 270 ? image.getHeight() : image.getWidth();
            int rotatedHeight = rotation == 90 || rotation == 270 ? image.getWidth() : image.getHeight();
            lastFrameWidth = rotatedWidth;
            lastFrameHeight = rotatedHeight;
            if (detectionPaused) {
                captureSceneFrameIfRequested(image);
                return;
            }
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            java.nio.ByteBuffer pixels = plane.getBuffer();
            pixels.rewind();
            java.util.List<Detection> detections = activeDetector.detect(pixels, plane.getRowStride(),
                    plane.getPixelStride(), image.getWidth(), image.getHeight(), rotation);
            if (frameRoadMode != roadMode) {
                captureSceneFrameIfRequested(image);
                return;
            }
            if (detectionPaused) {
                overlayView.update(java.util.Collections.emptyList(), rotatedWidth, rotatedHeight);
                captureSceneFrameIfRequested(image);
                return;
            }
            long inferenceMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            long now = System.nanoTime();
            if (previousFrameNs != 0) {
                float instant = 1_000_000_000f / (now - previousFrameNs);
                smoothedFps = smoothedFps == 0 ? instant : smoothedFps * 0.85f + instant * 0.15f;
            }
            previousFrameNs = now;
            if (now - previousTimingLogNs >= TimeUnit.SECONDS.toNanos(5)) {
                Log.d(TAG, "Pipeline mode=" + (frameRoadMode ? "road" : "objects")
                        + " ms: preprocess=" + activeDetector.getPreprocessMs()
                        + " model=" + activeDetector.getInferenceMs()
                        + " postprocess=" + activeDetector.getPostprocessMs()
                        + " total=" + inferenceMs);
                previousTimingLogNs = now;
            }
            overlayView.update(detections, rotatedWidth, rotatedHeight);
            if (now - previousPerformanceUpdateNs >= TimeUnit.MILLISECONDS.toNanos(250)) {
                previousPerformanceUpdateNs = now;
                String metrics = String.format(java.util.Locale.US, "%.1f FPS  |  %d ms",
                        smoothedFps, inferenceMs);
                performanceText.post(() -> performanceText.setText(metrics));
            }
            boolean faceAlertsActive = !frameRoadMode && faceRecognitionManager != null
                    && objectAlertSpeaker.isEnabled() && faceRecognitionManager.hasProfiles();
            if (faceAlertsActive) {
                java.util.List<Detection> people = new java.util.ArrayList<>();
                java.util.List<Detection> otherObjects = new java.util.ArrayList<>();
                for (Detection detection : detections) {
                    if ("person".equalsIgnoreCase(detection.label)) people.add(detection);
                    else otherObjects.add(detection);
                }
                faceRecognitionManager.onPersonDetections(people, rotatedWidth);
                if (!people.isEmpty() && faceRecognitionManager.isReady()
                        && faceRecognitionManager.canAcceptSampledFrame()) {
                    CameraBitmapUtils.FrameBitmap frame = CameraBitmapUtils.fromRgba(image, 640);
                    faceRecognitionManager.onSampledFrame(frame, rotatedWidth);
                }
                objectAlertSpeaker.onDetections(otherObjects, rotatedWidth);
            } else {
                objectAlertSpeaker.onDetections(detections, rotatedWidth);
            }
            captureSceneFrameIfRequested(image);
        } catch (Exception e) {
            Log.e(TAG, "Frame inference failed", e);
            runOnUiThread(() -> statusText.setText("Detection error: " + e.getMessage()));
        } finally {
            image.close();
        }
    }

    @Override protected void onDestroy() {
        sceneFrameRequested.set(false);
        Runnable timeout = sceneCaptureTimeout;
        if (timeout != null) mainHandler.removeCallbacks(timeout);
        if (geminiSceneAnalyzer != null) geminiSceneAnalyzer.close();
        if (faceRecognitionManager != null) faceRecognitionManager.close();
        if (objectAlertSpeaker != null) objectAlertSpeaker.close();
        if (detector != null) detector.close();
        YoloDetector loadedPotholeDetector;
        synchronized (this) {
            loadedPotholeDetector = potholeDetector;
            potholeDetector = null;
        }
        if (loadedPotholeDetector != null) loadedPotholeDetector.close();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
            try { if (!cameraExecutor.awaitTermination(1, TimeUnit.SECONDS)) cameraExecutor.shutdownNow(); }
            catch (InterruptedException e) { cameraExecutor.shutdownNow(); Thread.currentThread().interrupt(); }
        }
        super.onDestroy();
    }

    private void updateVoiceAlertsButton(boolean enabled) {
        voiceAlertsButton.setText(enabled ? "Voice alerts: On" : "Voice alerts: Off");
        voiceAlertsButton.setContentDescription(enabled
                ? "Voice alerts are on. Double tap to turn them off."
                : "Voice alerts are off. Double tap to turn them on.");
    }

    private String detectionStatusMessage() {
        if (roadModeLoading) return "Loading pothole detector…";
        return roadMode
                ? "Road mode is on. Detecting potholes only."
                : "Object mode is on. Detecting the usual objects.";
    }

    private void switchDetectionMode() {
        if (!modelReady || roadModeLoading) return;
        if (!roadMode && !potholeModelAvailable) {
            statusText.setText("Pothole model is unavailable. Object mode is still working.");
            return;
        }
        roadMode = !roadMode;
        previousFrameNs = 0;
        smoothedFps = 0f;
        if (objectAlertSpeaker != null) objectAlertSpeaker.resetDetections();
        if (faceRecognitionManager != null) faceRecognitionManager.resetAppearances();
        overlayView.update(java.util.Collections.emptyList(), lastFrameWidth, lastFrameHeight);
        performanceText.setText("0.0 FPS  |  0 ms");

        if (roadMode && potholeDetector == null) {
            roadModeLoading = true;
            updateDetectionModeButton();
            statusText.setText("Loading pothole detector. This may take a moment.");
            cameraExecutor.execute(() -> {
                try {
                    YoloDetector loaded = new YoloDetector(getApplicationContext(),
                            "pothole.tflite", "pothole_labels.txt");
                    synchronized (this) {
                        if (isFinishing() || isDestroyed()) {
                            loaded.close();
                            return;
                        }
                        potholeDetector = loaded;
                    }
                    Log.i(TAG, "Pothole detector loaded");
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        roadModeLoading = false;
                        updateDetectionModeButton();
                        if (!detectionPaused) statusText.setText(detectionStatusMessage());
                    });
                } catch (Exception | LinkageError | OutOfMemoryError potholeError) {
                    Log.e(TAG, "Could not load pothole detector", potholeError);
                    potholeModelAvailable = false;
                    roadMode = false;
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        roadModeLoading = false;
                        updateDetectionModeButton();
                        statusText.setText("Pothole model could not load. Object mode is still available.");
                    });
                }
            });
            return;
        }

        updateDetectionModeButton();
        statusText.setText(detectionPaused
                ? "Detection paused. Camera preview is still on."
                : detectionStatusMessage());
    }

    private void updateDetectionModeButton() {
        if (detectionModeButton == null) return;
        detectionModeButton.setText(roadMode ? "Mode: Road" : "Mode: Objects");
        detectionModeButton.setContentDescription(roadMode
                ? "Road mode is on and detects potholes only. Double tap to switch to object mode."
                : "Object mode is on and detects the usual objects. Double tap to switch to pothole-only road mode.");
        detectionModeButton.setEnabled(modelReady && (roadMode || potholeModelAvailable));
    }

    private void updateDetectionButton() {
        pauseDetectionButton.setText(detectionPaused ? "Resume detection" : "Pause detection");
        pauseDetectionButton.setContentDescription(detectionPaused
                ? "Detection is paused. Double tap to resume detection."
                : "Detection is running. Double tap to pause detection. The camera preview will stay on.");
    }

    private void showHelp() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Using Drishti")
                .setMessage("Point the back camera toward the scene. Detected objects appear in outlined boxes, and voice alerts describe important objects and their screen location. Double tap the live preview to send one still image to Google Gemini for a scene description; the camera does not stream video to Gemini.\n\nUse Mode: Objects for the usual object and known-person detection, or Mode: Road to run pothole detection only. Running one detector at a time can improve speed. Use Voice alerts to turn speech on or off, Pause detection to stop detection while keeping the preview on, and Known people to enroll or remove people who have agreed to be recognized. Face names are informational and should not be used for navigation or safety decisions.")
                .setPositiveButton("Got it", null)
                .show();
    }
}
