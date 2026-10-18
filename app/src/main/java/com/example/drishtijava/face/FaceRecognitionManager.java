package com.example.drishtijava.face;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.drishtijava.Detection;
import com.example.drishtijava.ObjectAlertSpeaker;
import com.example.drishtijava.SpeechLocalizer;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.common.InputImage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns local face matching and person appearance alerts; YOLO remains the person detector. */
public final class FaceRecognitionManager implements AutoCloseable {
    private static final String TAG = "FaceRecognition";
    private static final float MATCH_THRESHOLD = 0.68f;
    private static final long GENERIC_DELAY_MS = 1800;
    private static final long CONTINUOUS_VISIBILITY_MS = 250;
    private static final int MIN_FACE_PIXELS = 48;
    private static final float POSSIBLE_PERSON_CONFIDENCE = 0.40f;
    private static final float MIN_GENERIC_PERSON_CONFIDENCE = 0.20f;
    private static final long MIN_FACE_SAMPLE_INTERVAL_MS = 1000;

    private final Context appContext;
    private final ObjectAlertSpeaker speaker;
    private final FaceProfileStore profileStore;
    private final FaceDetector faceDetector;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PersonAppearanceTracker tracker = new PersonAppearanceTracker();
    private final AtomicBoolean faceTaskInFlight = new AtomicBoolean(false);
    private final AtomicLong lastFaceSampleAtMs = new AtomicLong();
    private volatile List<FaceProfile> profiles = Collections.emptyList();
    private volatile int profileCount;
    private volatile boolean ready;
    private volatile boolean closed;
    private FaceEmbedder embedder;

    public FaceRecognitionManager(Context context, ObjectAlertSpeaker speaker) {
        appContext = context.getApplicationContext();
        this.speaker = speaker;
        profileStore = new FaceProfileStore(appContext);
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .enableTracking()
                .setMinFaceSize(0.06f)
                .build();
        faceDetector = FaceDetection.getClient(options);
    }

    public boolean hasProfiles() { return profileCount > 0; }
    public boolean isReady() { return ready; }
    public boolean hasRecentPersonTrack() {
        if (closed || !hasProfiles()) return false;
        long now = SystemClock.elapsedRealtime();
        for (PersonAppearanceTracker.PersonTrack track : tracker.snapshot()) {
            if (!PersonAppearanceTracker.isRearmed(track.lastSeenAtMs, now)) return true;
        }
        return false;
    }

    public boolean canAcceptSampledFrame() {
        return !closed && ready && !faceTaskInFlight.get()
                && SystemClock.elapsedRealtime() - lastFaceSampleAtMs.get() >= MIN_FACE_SAMPLE_INTERVAL_MS;
    }

    public void refreshProfiles() {
        if (closed) return;
        ready = false;
        profileCount = 0;
        profiles = Collections.emptyList();
        tracker.clear();
        try {
            worker.execute(() -> {
                try {
                    List<FaceProfile> loaded = profileStore.load();
                    profiles = Collections.unmodifiableList(loaded);
                    profileCount = loaded.size();
                    if (!loaded.isEmpty() && embedder == null) embedder = new FaceEmbedder(appContext);
                    ready = !loaded.isEmpty() && embedder != null;
                    tracker.clear();
                    Log.i(TAG, "Loaded " + loaded.size() + " local face profiles");
                } catch (Exception e) {
                    ready = false;
                    profileCount = 0;
                    Log.e(TAG, "Face profiles or model could not be loaded", e);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The activity is already shutting down.
        }
    }

    public void resetAppearances() { tracker.clear(); }

    /** Track YOLO person boxes on every analyzer result, without blocking the camera thread. */
    public void onPersonDetections(List<Detection> detections, int frameWidth) {
        if (closed || !hasProfiles() || frameWidth <= 0) return;
        long now = SystemClock.elapsedRealtime();
        Set<Long> oldIds = new HashSet<>();
        for (PersonAppearanceTracker.PersonTrack track : tracker.snapshot()) oldIds.add(track.id);
        List<PersonAppearanceTracker.PersonTrack> current = tracker.update(detections, now);
        for (PersonAppearanceTracker.PersonTrack track : current) {
            if (!oldIds.contains(track.id)) {
                mainHandler.postDelayed(this::flushGenericAlerts, GENERIC_DELAY_MS);
            }
        }
        frameWidthForAlerts = frameWidth;
    }

    private volatile int frameWidthForAlerts;

    /** Takes ownership of the bitmap; busy frames are discarded instead of queued. */
    public void onSampledFrame(CameraBitmapUtils.FrameBitmap frame, int originalFrameWidth) {
        if (frame == null || frame.bitmap == null) return;
        Bitmap bitmap = frame.bitmap;
        long now = SystemClock.elapsedRealtime();
        long previousSampleAtMs = lastFaceSampleAtMs.get();
        if (closed || !ready || now - previousSampleAtMs < MIN_FACE_SAMPLE_INTERVAL_MS
                || !lastFaceSampleAtMs.compareAndSet(previousSampleAtMs, now)
                || !faceTaskInFlight.compareAndSet(false, true)) {
            bitmap.recycle();
            return;
        }
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            faceDetector.process(image)
                .addOnSuccessListener(faces -> {
                    try {
                        worker.execute(() -> {
                            try {
                                analyzeFaces(bitmap, frame.scaleX, frame.scaleY, originalFrameWidth, faces);
                            } catch (Exception e) {
                                Log.w(TAG, "Could not process a sampled face frame", e);
                            } finally {
                                if (!bitmap.isRecycled()) bitmap.recycle();
                                faceTaskInFlight.set(false);
                            }
                        });
                    } catch (RejectedExecutionException ignored) {
                        if (!bitmap.isRecycled()) bitmap.recycle();
                        faceTaskInFlight.set(false);
                    }
                })
                .addOnFailureListener(error -> {
                    Log.w(TAG, "Face detection failed", error);
                    if (!bitmap.isRecycled()) bitmap.recycle();
                    faceTaskInFlight.set(false);
                });
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not submit a face frame", e);
            if (!bitmap.isRecycled()) bitmap.recycle();
            faceTaskInFlight.set(false);
        }
    }

    private void analyzeFaces(Bitmap bitmap, float scaleX, float scaleY, int frameWidth, List<Face> faces) {
        long now = SystemClock.elapsedRealtime();
        List<SpeechLocalizer.NamedPerson> namedAlerts = new ArrayList<>();
        Set<Long> processedTracks = new HashSet<>();
        synchronized (tracker) {
            for (Face face : faces) {
                Rect bounds = face.getBoundingBox();
                if (bounds.width() < MIN_FACE_PIXELS || bounds.height() < MIN_FACE_PIXELS) continue;
                RectF sourceBox = new RectF(bounds.left / scaleX, bounds.top / scaleY,
                        bounds.right / scaleX, bounds.bottom / scaleY);
                PersonAppearanceTracker.PersonTrack track = tracker.findForFace(sourceBox);
                if (track == null || track.nameAnnounced
                        || !processedTracks.add(track.id)) continue;
                Bitmap aligned = FaceAligner.align(bitmap, face);
                if (aligned == null) continue;
                float[] embedding;
                try {
                    embedding = embedder.embed(aligned);
                } finally {
                    aligned.recycle();
                }
                if (embedding.length == 0) continue;

                FaceMath.Match match = FaceMath.bestMatch(embedding, profiles, MATCH_THRESHOLD);
                String resolved = track.consensus.addSample(match.name);
                if (resolved != null && !track.nameAnnounced) {
                    track.nameAnnounced = true;
                    track.resolvedName = resolved;
                    namedAlerts.add(new SpeechLocalizer.NamedPerson(
                            resolved, locationFor(track.box, frameWidth)));
                }
            }
        }
        if (!namedAlerts.isEmpty()) {
            speaker.speakKnownPersonAlerts(namedAlerts);
        }
    }

    private void flushGenericAlerts() {
        if (closed || !hasProfiles()) return;
        long now = SystemClock.elapsedRealtime();
        List<PersonAppearanceTracker.PersonTrack> pending = new ArrayList<>();
        synchronized (tracker) {
            for (PersonAppearanceTracker.PersonTrack track : tracker.snapshot()) {
                if (!track.nameAnnounced && !track.genericAnnounced
                        && track.confidence >= MIN_GENERIC_PERSON_CONFIDENCE
                        && now - track.firstSeenAtMs >= GENERIC_DELAY_MS
                        && now - track.lastSeenAtMs <= CONTINUOUS_VISIBILITY_MS) {
                    track.genericAnnounced = true;
                    pending.add(track);
                }
            }
        }
        if (pending.isEmpty()) return;
        if (pending.size() == 1) {
            boolean possible = pending.get(0).confidence < POSSIBLE_PERSON_CONFIDENCE;
            speaker.speakPersonAlert(1, possible,
                    locationFor(pending.get(0).box, frameWidthForAlerts));
        } else {
            speaker.speakPersonAlert(pending.size(), false, SpeechLocalizer.Location.VIEW);
        }
    }

    private static SpeechLocalizer.Location locationFor(RectF box, int width) {
        if (width <= 0) return SpeechLocalizer.Location.VIEW;
        if (box.centerX() < width / 3f) return SpeechLocalizer.Location.LEFT;
        if (box.centerX() > width * 2f / 3f) return SpeechLocalizer.Location.RIGHT;
        return SpeechLocalizer.Location.CENTER;
    }

    @Override public void close() {
        closed = true;
        faceDetector.close();
        worker.execute(() -> {
            if (embedder != null) {
                embedder.close();
                embedder = null;
            }
        });
        worker.shutdown();
        mainHandler.removeCallbacksAndMessages(null);
        tracker.clear();
    }
}
