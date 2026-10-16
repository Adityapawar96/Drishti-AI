package com.example.drishtijava.face;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

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

import com.example.drishtijava.R;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Guided three-pose live enrollment. Only averaged embeddings are persisted. */
public final class FaceEnrollmentActivity extends AppCompatActivity {
    private static final String TAG = "FaceEnrollment";
    private static final long SAMPLE_INTERVAL_MS = 650;
    private static final int SAMPLES_PER_POSE = 3;

    private PreviewView previewView;
    private TextView titleView;
    private TextView statusView;
    private Button startButton;
    private String personName;
    private FaceProfileStore profileStore;
    private FaceDetector faceDetector;
    private FaceEmbedder embedder;
    private ProcessCameraProvider cameraProvider;
    private Preview cameraPreview;
    private ImageAnalysis imageAnalysis;
    private ExecutorService cameraExecutor;
    private ExecutorService worker;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private final AtomicBoolean frameInFlight = new AtomicBoolean(false);
    private final AtomicInteger frameNumber = new AtomicInteger();
    @SuppressWarnings("unchecked")
    private final List<float[]>[] samples = new List[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};
    private volatile boolean scanning;
    private volatile boolean closed;
    private int currentPose;
    private long lastSampleAtMs;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_face_enrollment);
        personName = getIntent().getStringExtra("person_name");
        if (personName == null || personName.trim().isEmpty()) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        previewView = findViewById(R.id.enrollmentPreview);
        titleView = findViewById(R.id.enrollmentTitle);
        statusView = findViewById(R.id.enrollmentStatus);
        startButton = findViewById(R.id.startEnrollmentButton);
        Button cancelButton = findViewById(R.id.cancelEnrollmentButton);
        titleView.setText("Adding " + personName.trim());
        startButton.setEnabled(false);
        startButton.setOnClickListener(view -> requestCameraAndStart());
        cancelButton.setOnClickListener(view -> finishWithCancel());
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        profileStore = new FaceProfileStore(getApplicationContext());
        cameraExecutor = Executors.newSingleThreadExecutor();
        worker = Executors.newSingleThreadExecutor();
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.12f)
                .build();
        faceDetector = FaceDetection.getClient(options);
        cameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) startFrontCamera();
            else {
                showStatus("Camera permission was denied. You can still use object alerts.");
                startButton.setEnabled(true);
            }
        });
        worker.execute(() -> {
            try {
                embedder = new FaceEmbedder(getApplicationContext());
                runOnUiThread(() -> {
                    if (closed) return;
                    startButton.setEnabled(true);
                    showStatus("The face model is ready. Ask " + personName + " to agree, then start guided capture.");
                });
            } catch (Exception e) {
                Log.e(TAG, "Could not load face embedding model", e);
                runOnUiThread(() -> showStatus("Face recognition could not start: " + e.getMessage()));
            }
        });
    }

    private void requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startFrontCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startFrontCamera() {
        if (closed || scanning) return;
        scanning = true;
        startButton.setEnabled(false);
        showPoseInstruction();
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                cameraPreview = new Preview.Builder().build();
                cameraPreview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setTargetRotation(previewView.getDisplay() == null ? android.view.Surface.ROTATION_0
                                : previewView.getDisplay().getRotation())
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyze);
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA,
                        cameraPreview, imageAnalysis);
            } catch (Exception e) {
                Log.e(TAG, "Could not open the front camera", e);
                scanning = false;
                showStatus("Could not open the front camera. Check that this phone has a front camera.");
                startButton.setEnabled(true);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(@NonNull ImageProxy image) {
        try {
            if (!scanning || frameNumber.incrementAndGet() % 3 != 0 || !frameInFlight.compareAndSet(false, true)) return;
            CameraBitmapUtils.FrameBitmap frame = CameraBitmapUtils.fromRgba(image, 640);
            BitmapFrame input = new BitmapFrame(frame.bitmap, frame.scaleX, frame.scaleY);
            faceDetector.process(InputImage.fromBitmap(input.bitmap, 0))
                    .addOnSuccessListener(faces -> onFaces(input, faces))
                    .addOnFailureListener(error -> {
                        Log.w(TAG, "Enrollment face detection failed", error);
                        releaseFrame(input.bitmap);
                        showStatus("Face detection failed. Keep one face clearly in view.");
                    });
        } catch (Exception e) {
            Log.w(TAG, "Could not copy enrollment camera frame", e);
            frameInFlight.set(false);
        } finally {
            image.close();
        }
    }

    private void onFaces(BitmapFrame frame, List<Face> faces) {
        if (closed || !scanning) {
            releaseFrame(frame.bitmap);
            return;
        }
        if (faces.size() != 1) {
            showStatus(faces.isEmpty() ? "Move into the center of the camera view." : "Only one person should be in the camera view during enrollment.");
            releaseFrame(frame.bitmap);
            return;
        }
        Face face = faces.get(0);
        android.graphics.Rect bounds = face.getBoundingBox();
        if (bounds.width() / frame.scaleX < 100 || bounds.height() / frame.scaleY < 100
                || !hasAllLandmarks(face)) {
            showStatus("Move closer and face the light so both eyes, nose, and mouth are clear.");
            releaseFrame(frame.bitmap);
            return;
        }
        float yaw = face.getHeadEulerAngleY();
        boolean correctPose = currentPose == 0 ? Math.abs(yaw) <= 10f
                : currentPose == 1 ? yaw <= -12f : yaw >= 12f;
        if (!correctPose) {
            showPoseInstruction();
            releaseFrame(frame.bitmap);
            return;
        }
        long now = SystemClockCompat.elapsedRealtime();
        if (now - lastSampleAtMs < SAMPLE_INTERVAL_MS) {
            releaseFrame(frame.bitmap);
            return;
        }
        lastSampleAtMs = now;
        int poseAtCapture = currentPose;
        showStatus(poseName(poseAtCapture) + " view. Capturing sample " + (samples[poseAtCapture].size() + 1)
                + " of " + SAMPLES_PER_POSE + ". Hold still.");
        try {
            worker.execute(() -> captureEmbedding(frame, face, poseAtCapture));
        } catch (RuntimeException e) {
            releaseFrame(frame.bitmap);
        }
    }

    private void captureEmbedding(BitmapFrame frame, Face face, int poseAtCapture) {
        try {
            android.graphics.Bitmap aligned = FaceAligner.align(frame.bitmap, face);
            if (aligned == null) {
                runOnUiThread(() -> showStatus("The face landmarks were unclear. Hold still and try again."));
                return;
            }
            float[] embedding;
            try {
                embedding = embedder.embed(aligned);
            } finally {
                aligned.recycle();
            }
            if (embedding.length == 0) return;
            samples[poseAtCapture].add(embedding);
            if (samples[poseAtCapture].size() >= SAMPLES_PER_POSE) {
                if (poseAtCapture < 2) {
                    currentPose = poseAtCapture + 1;
                    lastSampleAtMs = 0;
                    runOnUiThread(this::showPoseInstruction);
                } else {
                    saveCompletedProfile();
                }
            } else {
                runOnUiThread(this::showPoseInstruction);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not create enrollment embedding", e);
            runOnUiThread(() -> showStatus("Could not capture that sample. Keep the face well lit and try again."));
        } finally {
            if (!frame.bitmap.isRecycled()) frame.bitmap.recycle();
            frameInFlight.set(false);
        }
    }

    private void saveCompletedProfile() {
        List<float[]> poseAverages = new ArrayList<>();
        for (List<float[]> poseSamples : samples) {
            if (poseSamples.size() < SAMPLES_PER_POSE) {
                runOnUiThread(() -> showStatus("A pose needs three clear samples. Please try again."));
                return;
            }
            poseAverages.add(FaceMath.averageNormalized(poseSamples));
        }
        try {
            profileStore.save(personName, poseAverages);
            scanning = false;
            runOnUiThread(() -> {
                setResult(RESULT_OK);
                finish();
            });
        } catch (Exception e) {
            Log.w(TAG, "Could not save encrypted face profile", e);
            runOnUiThread(() -> {
                showStatus(e.getMessage() == null ? "Could not save the face profile." : e.getMessage());
                startButton.setEnabled(true);
            });
        }
    }

    private void showPoseInstruction() {
        if (closed) return;
        if (currentPose >= 3) return;
        int count = samples[currentPose].size();
        String cue;
        if (currentPose == 0) cue = "Look straight at the camera.";
        else if (currentPose == 1) cue = "Turn your head slightly to your left.";
        else cue = "Turn your head slightly to your right.";
        showStatus(cue + " Captured " + count + " of " + SAMPLES_PER_POSE + " samples for this pose.");
    }

    private static String poseName(int pose) {
        return pose == 0 ? "Front" : pose == 1 ? "Slight-left" : "Slight-right";
    }

    private static boolean hasAllLandmarks(Face face) {
        return face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE) != null
                && face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE) != null
                && face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE) != null
                && face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT) != null
                && face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT) != null;
    }

    private void showStatus(String value) {
        if (statusView != null && !value.contentEquals(statusView.getText())) statusView.setText(value);
    }

    private void releaseFrame(android.graphics.Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        frameInFlight.set(false);
    }

    private void finishWithCancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override protected void onDestroy() {
        closed = true;
        scanning = false;
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
                Log.w(TAG, "Could not release enrollment camera use cases", e);
            }
        }
        if (faceDetector != null) faceDetector.close();
        if (cameraExecutor != null) cameraExecutor.shutdownNow();
        if (worker != null) {
            worker.execute(() -> {
                if (embedder != null) embedder.close();
            });
            worker.shutdown();
        }
        super.onDestroy();
    }

    private static final class BitmapFrame {
        final android.graphics.Bitmap bitmap;
        final float scaleX;
        final float scaleY;
        BitmapFrame(android.graphics.Bitmap bitmap, float scaleX, float scaleY) {
            this.bitmap = bitmap;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }
    }

    /** Avoids requiring API 26 java.time on the app's min SDK. */
    private static final class SystemClockCompat {
        static long elapsedRealtime() { return android.os.SystemClock.elapsedRealtime(); }
    }
}
