package com.example.drishtijava.face;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.enrollmentRoot), (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            View topControls = findViewById(R.id.enrollmentTopControls);
            View bottomControls = findViewById(R.id.enrollmentBottomControls);
            float density = getResources().getDisplayMetrics().density;
            if (topControls != null) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) topControls.getLayoutParams();
                lp.topMargin = bars.top + Math.round(16 * density);
                topControls.setLayoutParams(lp);
            }
            if (bottomControls != null) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) bottomControls.getLayoutParams();
                lp.bottomMargin = bars.bottom + Math.round(16 * density);
                bottomControls.setLayoutParams(lp);
            }
            return insets;
        });
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
        titleView.setText(getString(R.string.enrolling_person, personName.trim()));
        startButton.setEnabled(false);
        startButton.setOnClickListener(view -> requestCameraAndStart());
        cancelButton.setOnClickListener(view -> finishWithCancel());
        findViewById(R.id.enrollmentHeaderCancelButton).setOnClickListener(view -> finishWithCancel());
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        profileStore = new FaceProfileStore(getApplicationContext());
        cameraExecutor = Executors.newSingleThreadExecutor();
        worker = Executors.newSingleThreadExecutor();
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.08f)
                .build();
        faceDetector = FaceDetection.getClient(options);
        cameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) startFrontCamera();
            else {
                showStatus(getString(R.string.enrollment_camera_permission_denied));
                startButton.setEnabled(true);
            }
        });
        worker.execute(() -> {
            try {
                embedder = new FaceEmbedder(getApplicationContext());
                runOnUiThread(() -> {
                    if (closed) return;
                    startButton.setEnabled(true);
                    showStatus(getString(R.string.face_model_ready, personName));
                });
            } catch (Exception e) {
                Log.e(TAG, "Could not load face embedding model", e);
                runOnUiThread(() -> showStatus(getString(R.string.face_model_start_failed, e.getMessage())));
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
                showStatus(getString(R.string.front_camera_open_failed));
                startButton.setEnabled(true);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(@NonNull ImageProxy image) {
        try {
            if (!scanning || frameNumber.incrementAndGet() % 3 != 0 || !frameInFlight.compareAndSet(false, true)) return;
            CameraBitmapUtils.FrameBitmap frame = CameraBitmapUtils.fromRgba(image,
                    CameraBitmapUtils.FACE_FRAME_MAX_DIMENSION);
            BitmapFrame input = new BitmapFrame(frame.bitmap, frame.scaleX, frame.scaleY);
            faceDetector.process(InputImage.fromBitmap(input.bitmap, 0))
                    .addOnSuccessListener(faces -> onFaces(input, faces))
                    .addOnFailureListener(error -> {
                        Log.w(TAG, "Enrollment face detection failed", error);
                        releaseFrame(input.bitmap);
                        showStatus(getString(R.string.enrollment_face_detection_failed));
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
            showStatus(getString(faces.isEmpty() ? R.string.move_center_camera
                    : R.string.one_person_enrollment));
            releaseFrame(frame.bitmap);
            return;
        }
        Face face = faces.get(0);
        android.graphics.Rect bounds = face.getBoundingBox();
        if (bounds.width() / frame.scaleX < 100 || bounds.height() / frame.scaleY < 100
                || !hasAllLandmarks(face)) {
            showStatus(getString(R.string.move_closer_light));
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
        showStatus(getString(R.string.capture_sample_status, poseName(poseAtCapture),
                samples[poseAtCapture].size() + 1, SAMPLES_PER_POSE));
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
                runOnUiThread(() -> showStatus(getString(R.string.face_landmarks_unclear)));
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
            runOnUiThread(() -> showStatus(getString(R.string.capture_sample_failed)));
        } finally {
            if (!frame.bitmap.isRecycled()) frame.bitmap.recycle();
            frameInFlight.set(false);
        }
    }

    private void saveCompletedProfile() {
        List<float[]> poseAverages = new ArrayList<>();
        for (List<float[]> poseSamples : samples) {
            if (poseSamples.size() < SAMPLES_PER_POSE) {
                runOnUiThread(() -> showStatus(getString(R.string.pose_samples_needed)));
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
                showStatus(e.getMessage() == null ? getString(R.string.save_face_profile_failed)
                        : e.getMessage());
                startButton.setEnabled(true);
            });
        }
    }

    private void showPoseInstruction() {
        if (closed) return;
        if (currentPose >= 3) return;
        int count = samples[currentPose].size();
        int cueId = currentPose == 0 ? R.string.look_straight_camera
                : currentPose == 1 ? R.string.turn_head_left : R.string.turn_head_right;
        showStatus(getString(R.string.pose_sample_progress, getString(cueId),
                count, SAMPLES_PER_POSE));
    }

    private String poseName(int pose) {
        return getString(pose == 0 ? R.string.pose_front
                : pose == 1 ? R.string.pose_slight_left : R.string.pose_slight_right);
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
