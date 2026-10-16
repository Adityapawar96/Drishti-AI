package com.example.drishtijava;

import android.content.Context;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Speaks stable object detections without queuing stale frame-by-frame announcements. */
public final class ObjectAlertSpeaker implements AutoCloseable {
    private static final String TAG = "DrishtiVoice";
    // These are alert-policy cutoffs over model scores, not calibrated probabilities.
    private static final int FRAMES_TO_CONFIRM = 2;
    private static final int FRAMES_FOR_LOW_CONFIDENCE = 3;
    private static final float IMMEDIATE_ALERT_CONFIDENCE = 0.80f;
    private static final float IMMEDIATE_HAZARD_CONFIDENCE = 0.70f;
    private static final float LOW_CONFIDENCE_CUTOFF = 0.55f;
    private static final float MIN_ALERT_CONFIDENCE = 0.40f;
    private static final float MIN_PERSON_ALERT_CONFIDENCE = 0.30f;
    private static final float TRACK_MATCH_IOU = 0.25f;
    private static final int MAX_MISSED_FRAMES = 4;
    private static final int MAX_OBJECTS_PER_UTTERANCE = 2;
    private static final long MIN_SPEECH_INTERVAL_MS = 2800;
    private static final long SAME_CLASS_COOLDOWN_MS = 3500;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TextToSpeech textToSpeech;
    private final List<AlertTrack> tracks = new ArrayList<>();
    private final Map<String, Long> lastAlertByClass = new HashMap<>();
    private volatile boolean enabled = true;
    private boolean initialized;
    private boolean closed;
    private boolean sceneSummaryActive;
    private long frameNumber;
    private long lastSpeechAtMs;
    private int currentSpeechPriority = Integer.MAX_VALUE;
    private int utteranceNumber;
    private long nextTrackId = 1;
    private String pendingFaceAlert;
    private String pendingSceneSummary;
    private String currentUtteranceId;

    public ObjectAlertSpeaker(Context context) {
        textToSpeech = new TextToSpeech(context.getApplicationContext(), this::onTtsInitialized);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        runOnMain(() -> {
            if (closed || this.enabled == enabled) return;
            this.enabled = enabled;
            tracks.clear();
            lastAlertByClass.clear();
            lastSpeechAtMs = 0;
            currentSpeechPriority = Integer.MAX_VALUE;
            currentUtteranceId = null;
            sceneSummaryActive = false;
            if (!enabled) pendingFaceAlert = null;
            if (!enabled) pendingSceneSummary = null;
            if (!enabled && initialized) textToSpeech.stop();
        });
    }

    public void onDetections(List<Detection> detections, int frameWidth) {
        List<Detection> snapshot = new ArrayList<>(detections);
        mainHandler.post(() -> processDetections(snapshot, frameWidth));
    }

    public void resetDetections() {
        mainHandler.post(() -> {
            tracks.clear();
            lastAlertByClass.clear();
            frameNumber = 0;
        });
    }

    /** Speaks an identity/person alert through the existing TTS engine at highest alert priority. */
    public void speakFaceAlert(String message) {
        if (message == null || message.trim().isEmpty()) return;
        mainHandler.post(() -> {
            if (closed || !enabled) return;
            if (!initialized) {
                pendingFaceAlert = pendingFaceAlert == null ? message : pendingFaceAlert + " " + message;
                return;
            }
            if (sceneSummaryActive) {
                if (pendingFaceAlert == null) pendingFaceAlert = message;
                return;
            }
            speakFaceAlertOnMain(message);
        });
    }

    /** Gives a user-requested scene summary exclusive use of speech until it finishes. */
    public void speakSceneSummary(String message) {
        if (message == null || message.trim().isEmpty()) return;
        runOnMain(() -> {
            if (closed || !enabled) return;
            if (!initialized) {
                pendingSceneSummary = message;
                return;
            }
            pendingSceneSummary = null;
            speakSceneSummaryOnMain(message);
        });
    }

    private void onTtsInitialized(int status) {
        if (closed) return;
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Text-to-speech initialization failed: " + status);
            return;
        }

        int languageStatus = textToSpeech.setLanguage(Locale.getDefault());
        if (languageStatus == TextToSpeech.LANG_MISSING_DATA
                || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            languageStatus = textToSpeech.setLanguage(Locale.ENGLISH);
        }
        if (languageStatus == TextToSpeech.LANG_MISSING_DATA
                || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "No supported text-to-speech voice is installed");
            return;
        }

        textToSpeech.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        textToSpeech.setSpeechRate(1.0f);
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }

            @Override public void onDone(String utteranceId) {
                mainHandler.post(() -> onSpeechFinished(utteranceId));
            }

            @Override @SuppressWarnings("deprecation") public void onError(String utteranceId) {
                mainHandler.post(() -> onSpeechFinished(utteranceId));
            }

            @Override public void onStop(String utteranceId, boolean interrupted) {
                mainHandler.post(() -> onSpeechFinished(utteranceId));
            }
        });
        initialized = true;
        Log.d(TAG, "Text-to-speech ready");
        if (enabled && pendingSceneSummary != null) {
            String pending = pendingSceneSummary;
            pendingSceneSummary = null;
            speakSceneSummaryOnMain(pending);
        } else if (enabled && pendingFaceAlert != null) {
            String pending = pendingFaceAlert;
            pendingFaceAlert = null;
            speakFaceAlertOnMain(pending);
        }
    }

    private void speakFaceAlertOnMain(String message) {
        if (textToSpeech.isSpeaking()) textToSpeech.stop();
        String utteranceId = "face-alert-" + (++utteranceNumber);
        int result = textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Could not queue face alert: " + result);
            return;
        }
        lastSpeechAtMs = SystemClock.elapsedRealtime();
        currentSpeechPriority = 0;
        currentUtteranceId = utteranceId;
        Log.d(TAG, "Speaking face alert: " + message);
    }

    private void speakSceneSummaryOnMain(String message) {
        // Stop any alert already speaking. Detection remains active, but its voice output
        // is held until this scene description finishes.
        sceneSummaryActive = true;
        if (textToSpeech.isSpeaking()) textToSpeech.stop();
        String utteranceId = "scene-summary-" + (++utteranceNumber);
        int result = textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Could not queue scene summary: " + result);
            sceneSummaryActive = false;
            currentSpeechPriority = Integer.MAX_VALUE;
            currentUtteranceId = null;
            speakPendingFaceAlertIfIdle();
            return;
        }
        currentSpeechPriority = -1;
        currentUtteranceId = utteranceId;
        Log.d(TAG, "Speaking requested scene summary");
    }

    private void onSpeechFinished(String utteranceId) {
        if (utteranceId != null && utteranceId.equals(currentUtteranceId)) {
            boolean finishedSceneSummary = sceneSummaryActive
                    && utteranceId.startsWith("scene-summary-");
            currentUtteranceId = null;
            currentSpeechPriority = Integer.MAX_VALUE;
            if (finishedSceneSummary) {
                sceneSummaryActive = false;
                speakPendingFaceAlertIfIdle();
            }
        }
        speakPendingSceneSummaryIfIdle();
    }

    private void speakPendingFaceAlertIfIdle() {
        if (closed || !enabled || !initialized || sceneSummaryActive
                || pendingFaceAlert == null) return;
        String pending = pendingFaceAlert;
        pendingFaceAlert = null;
        speakFaceAlertOnMain(pending);
    }

    private void speakPendingSceneSummaryIfIdle() {
        if (closed || !enabled || !initialized || sceneSummaryActive || pendingSceneSummary == null
                || textToSpeech.isSpeaking() || currentSpeechPriority <= 2) return;
        String pending = pendingSceneSummary;
        pendingSceneSummary = null;
        speakSceneSummaryOnMain(pending);
    }

    private void processDetections(List<Detection> detections, int frameWidth) {
        if (closed || !enabled || !initialized || frameWidth <= 0) return;
        frameNumber++;

        List<Detection> accepted = new ArrayList<>();
        for (Detection detection : detections) {
            float minimumConfidence = "person".equalsIgnoreCase(detection.label)
                    ? MIN_PERSON_ALERT_CONFIDENCE : MIN_ALERT_CONFIDENCE;
            if (detection.confidence < minimumConfidence) continue;
            accepted.add(detection);
        }
        accepted.sort(Comparator.comparingDouble((Detection item) -> item.confidence).reversed());

        Set<Long> matchedTrackIds = new HashSet<>();
        List<Candidate> current = new ArrayList<>();
        for (Detection detection : accepted) {
            String classKey = detection.label.toLowerCase(Locale.ROOT);
            AlertTrack bestTrack = null;
            float bestIou = TRACK_MATCH_IOU;
            for (AlertTrack track : tracks) {
                int missedFrames = (int) (frameNumber - track.lastSeenFrame - 1);
                if (!track.classKey.equals(classKey) || missedFrames > MAX_MISSED_FRAMES
                        || matchedTrackIds.contains(track.id)) continue;
                float overlap = intersectionOverUnion(track.box, detection.box);
                if (overlap >= bestIou) {
                    bestIou = overlap;
                    bestTrack = track;
                }
            }

            if (bestTrack == null) {
                bestTrack = new AlertTrack(nextTrackId++, detection, classKey, frameNumber);
                tracks.add(bestTrack);
            } else {
                bestTrack.consecutiveFrames = bestTrack.lastSeenFrame == frameNumber - 1
                        ? bestTrack.consecutiveFrames + 1 : 1;
                bestTrack.box = new RectF(detection.box);
                bestTrack.lastSeenFrame = frameNumber;
            }
            matchedTrackIds.add(bestTrack.id);
            current.add(new Candidate(bestTrack, detection,
                    locationFor(detection, frameWidth), priorityFor(detection.label)));
        }
        tracks.removeIf(track -> frameNumber - track.lastSeenFrame > MAX_MISSED_FRAMES + 1);

        long now = SystemClock.elapsedRealtime();
        List<Candidate> ready = new ArrayList<>();
        for (Candidate candidate : current) {
            int requiredFrames = requiredFrames(candidate);
            if (!candidate.track.announced && candidate.track.consecutiveFrames >= requiredFrames) {
                ready.add(candidate);
            }
        }
        if (ready.isEmpty()) return;
        ready.sort(Comparator.comparingInt((Candidate item) -> item.priority)
                .thenComparing(Comparator.comparingDouble(
                        (Candidate item) -> item.detection.confidence).reversed()));

        List<AlertGroup> eligible = new ArrayList<>();
        Set<String> groupedClassKeys = new HashSet<>();
        for (Candidate candidate : ready) {
            String classKey = candidate.track.classKey;
            Long lastAlertAt = lastAlertByClass.get(classKey);
            if (lastAlertAt != null && now - lastAlertAt < SAME_CLASS_COOLDOWN_MS) continue;
            if (!groupedClassKeys.add(classKey)) continue;
            eligible.add(groupFor(candidate, current));
        }
        if (eligible.isEmpty()) return;
        eligible.sort(Comparator.comparingInt((AlertGroup item) -> item.priority)
                .thenComparing(Comparator.comparingDouble((AlertGroup item) -> item.confidence).reversed()));

        // Keep accumulating detections while the summary speaks, but do not let an alert
        // replace or overlap the user-requested description.
        if (sceneSummaryActive) return;

        AlertGroup first = eligible.get(0);
        if (textToSpeech.isSpeaking()) {
            if (first.priority < currentSpeechPriority) textToSpeech.stop();
            else return;
        }
        if (first.priority > 0 && now - lastSpeechAtMs < MIN_SPEECH_INTERVAL_MS) return;

        StringBuilder message = new StringBuilder();
        List<AlertGroup> selected = new ArrayList<>();
        for (AlertGroup group : eligible) {
            if (group.priority != first.priority || selected.size() >= MAX_OBJECTS_PER_UTTERANCE) break;
            if (message.length() > 0) message.append(". ");
            if (group.lowConfidence) message.append("Possible ");
            appendGroupDescription(message, group);
            selected.add(group);
        }
        if (selected.isEmpty()) return;
        message.append('.');

        String utteranceId = "object-alert-" + (++utteranceNumber);
        int result = textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Could not queue object alert: " + result);
            return;
        }

        lastSpeechAtMs = now;
        currentSpeechPriority = first.priority;
        currentUtteranceId = utteranceId;
        for (AlertGroup group : selected) {
            for (Candidate member : group.members) member.track.announced = true;
            lastAlertByClass.put(group.classKey, now);
        }
        Log.d(TAG, "Speaking: " + message);
    }

    private static int requiredFrames(Candidate candidate) {
        if (candidate.priority == 0 && candidate.detection.confidence >= IMMEDIATE_HAZARD_CONFIDENCE) return 1;
        if (candidate.detection.confidence >= IMMEDIATE_ALERT_CONFIDENCE) return 1;
        if (candidate.detection.confidence >= LOW_CONFIDENCE_CUTOFF) return FRAMES_TO_CONFIRM;
        return FRAMES_FOR_LOW_CONFIDENCE;
    }

    private static int priorityFor(String label) {
        switch (label.toLowerCase(Locale.ROOT)) {
            case "car":
            case "motorcycle":
            case "bus":
            case "train":
            case "truck":
            case "bicycle":
            case "person":
            case "pothole":
                return 0;
            case "traffic light":
            case "stop sign":
            case "chair":
            case "bench":
            case "suitcase":
            case "fire hydrant":
            case "potted plant":
                return 1;
            default:
                return 2;
        }
    }

    private static String locationFor(Detection detection, int frameWidth) {
        float centerX = detection.box.centerX();
        if (centerX < frameWidth / 3f) return "on the left side of the view";
        if (centerX > frameWidth * 2f / 3f) return "on the right side of the view";
        return "in the center of the view";
    }

    private static AlertGroup groupFor(Candidate seed, List<Candidate> current) {
        List<Candidate> members = new ArrayList<>();
        String commonLocation = null;
        boolean sameLocation = true;
        boolean lowConfidence = false;
        float confidence = 0f;
        for (Candidate candidate : current) {
            if (!candidate.track.classKey.equals(seed.track.classKey)
                    || candidate.track.consecutiveFrames < requiredFrames(candidate)) continue;
            members.add(candidate);
            if (commonLocation == null) commonLocation = candidate.location;
            else if (!commonLocation.equals(candidate.location)) sameLocation = false;
            lowConfidence |= candidate.detection.confidence < LOW_CONFIDENCE_CUTOFF;
            confidence = Math.max(confidence, candidate.detection.confidence);
        }
        return new AlertGroup(seed.track.classKey, seed.detection.label, seed.priority, confidence,
                lowConfidence, sameLocation ? commonLocation : null, members);
    }

    private static void appendGroupDescription(StringBuilder message, AlertGroup group) {
        int count = group.members.size();
        if (count == 1) {
            message.append(group.label).append(", ").append(group.location);
            return;
        }
        message.append(count).append(' ').append(pluralize(group.label, count))
                .append(" in the camera view");
        if (group.location != null) {
            message.append(", mostly ");
            if (group.location.startsWith("on the left")) message.append("on the left");
            else if (group.location.startsWith("on the right")) message.append("on the right");
            else message.append("in the center");
        }
    }

    private static String pluralize(String label, int count) {
        if (count == 1) return label;
        switch (label.toLowerCase(Locale.ROOT)) {
            case "person": return "people";
            case "mouse": return "mice";
            case "knife": return "knives";
            case "sheep":
            case "scissors": return label;
            case "tv": return "TVs";
            default:
                String lower = label.toLowerCase(Locale.ROOT);
                if (lower.endsWith("ch") || lower.endsWith("sh") || lower.endsWith("s")
                        || lower.endsWith("x") || lower.endsWith("z")) return label + "es";
                if (lower.endsWith("y") && lower.length() > 1
                        && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0) {
                    return label.substring(0, label.length() - 1) + "ies";
                }
                return label + "s";
        }
    }

    private static float intersectionOverUnion(RectF first, RectF second) {
        float left = Math.max(first.left, second.left);
        float top = Math.max(first.top, second.top);
        float right = Math.min(first.right, second.right);
        float bottom = Math.min(first.bottom, second.bottom);
        float intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
        float union = first.width() * first.height() + second.width() * second.height() - intersection;
        return union <= 0 ? 0 : intersection / union;
    }

    @Override public void close() {
        runOnMain(() -> {
            if (closed) return;
            closed = true;
            textToSpeech.stop();
            textToSpeech.shutdown();
            tracks.clear();
            lastAlertByClass.clear();
            pendingSceneSummary = null;
            pendingFaceAlert = null;
            currentUtteranceId = null;
            sceneSummaryActive = false;
        });
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }

    private static final class Candidate {
        final AlertTrack track;
        final Detection detection;
        final String location;
        final int priority;

        Candidate(AlertTrack track, Detection detection, String location, int priority) {
            this.track = track;
            this.detection = detection;
            this.location = location;
            this.priority = priority;
        }
    }

    private static final class AlertTrack {
        final long id;
        final String classKey;
        RectF box;
        long lastSeenFrame;
        int consecutiveFrames;
        boolean announced;

        AlertTrack(long id, Detection detection, String classKey, long frameNumber) {
            this.id = id;
            this.classKey = classKey;
            box = new RectF(detection.box);
            lastSeenFrame = frameNumber;
            consecutiveFrames = 1;
        }
    }

    private static final class AlertGroup {
        final String classKey;
        final String label;
        final int priority;
        final float confidence;
        final boolean lowConfidence;
        final String location;
        final List<Candidate> members;

        AlertGroup(String classKey, String label, int priority, float confidence,
                   boolean lowConfidence, String location, List<Candidate> members) {
            this.classKey = classKey;
            this.label = label;
            this.priority = priority;
            this.confidence = confidence;
            this.lowConfidence = lowConfidence;
            this.location = location;
            this.members = members;
        }
    }
}
