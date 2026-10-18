package com.example.drishtijava;

import android.content.Context;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
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
    private static final float NEAR_BOX_SIZE_PROXY = 0.28f;
    private static final float TRACK_MATCH_IOU = 0.25f;
    private static final int MAX_MISSED_FRAMES = 4;
    private static final int MAX_OBJECTS_PER_UTTERANCE = 2;
    private static final long MIN_SPEECH_INTERVAL_MS = 2800;
    private static final long SAME_CLASS_COOLDOWN_MS = 3500;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TextToSpeech textToSpeech;
    private final HapticAlertManager hapticManager;
    private SpeechLanguage speechLanguage = SpeechLanguage.ENGLISH;
    private SpeechLocalizer speechLocalizer = new SpeechLocalizer(SpeechLanguage.ENGLISH);
    private SpeechLanguageCallback pendingLanguageCallback;
    private final List<AlertTrack> tracks = new ArrayList<>();
    private final Map<String, Long> lastAlertByClass = new HashMap<>();
    private volatile boolean enabled = true;
    private boolean initialized;
    private boolean speechVoiceReady;
    private boolean closed;
    private boolean sceneSummaryActive;
    private long frameNumber;
    private long lastSpeechAtMs;
    private int currentSpeechPriority = Integer.MAX_VALUE;
    private int utteranceNumber;
    private long nextTrackId = 1;
    private String pendingFaceAlert;
    private String pendingSceneSummary;
    private String pendingOperationalStatus;
    private String currentUtteranceId;

    public ObjectAlertSpeaker(Context context) {
        this(context, null);
    }

    public ObjectAlertSpeaker(Context context, HapticAlertManager hapticManager) {
        this.hapticManager = hapticManager;
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
        onDetections(detections, frameWidth, frameWidth);
    }

    public void onDetections(List<Detection> detections, int frameWidth, int frameHeight) {
        List<Detection> snapshot = new ArrayList<>(detections);
        mainHandler.post(() -> processDetections(snapshot, frameWidth, frameHeight));
    }

    /** Lets newly enabled haptics announce currently visible tracks once. */
    public void resetHapticAnnouncements() {
        mainHandler.post(() -> tracks.forEach(track -> track.hapticallyAnnounced = false));
    }

    public void resetDetections() {
        mainHandler.post(() -> {
            tracks.clear();
            lastAlertByClass.clear();
            frameNumber = 0;
        });
    }

    /** Changes the alert language, accepting only an installed voice that works offline. */
    public void setSpeechLanguage(SpeechLanguage language, SpeechLanguageCallback callback) {
        runOnMain(() -> {
            if (closed) {
                if (callback != null) callback.onVoiceReady(false);
                return;
            }
            SpeechLanguage selected = language == null ? SpeechLanguage.ENGLISH : language;
            boolean changed = selected != speechLanguage;
            if (changed) {
                pendingFaceAlert = null;
                pendingSceneSummary = null;
            }
            speechLanguage = selected;
            speechLocalizer = new SpeechLocalizer(speechLanguage);
            pendingLanguageCallback = callback;
            if (initialized && !changed && speechVoiceReady) {
                SpeechLanguageCallback readyCallback = pendingLanguageCallback;
                pendingLanguageCallback = null;
                if (readyCallback != null) readyCallback.onVoiceReady(true);
            } else if (initialized) {
                applySpeechLanguageOnMain();
            }
        });
    }

    public interface SpeechLanguageCallback {
        void onVoiceReady(boolean ready);
    }

    /** Speaks an unrecognized person alert using localized, structured parts. */
    public void speakPersonAlert(int count, boolean possible, SpeechLocalizer.Location location) {
        mainHandler.post(() -> {
            if (closed || !enabled) return;
            String message = speechLocalizer.personAlert(count, possible, location);
            if (!initialized) {
                pendingFaceAlert = message;
                return;
            }
            if (!speechVoiceReady) return;
            if (sceneSummaryActive) {
                if (pendingFaceAlert == null) pendingFaceAlert = message;
                return;
            }
            speakFaceAlertOnMain(message);
        });
    }

    /** Speaks recognized names and localized positions with the dedicated person haptic cue. */
    public void speakKnownPersonAlerts(Iterable<SpeechLocalizer.NamedPerson> people) {
        mainHandler.post(() -> {
            if (closed) return;
            if (hapticManager != null && hapticManager.isEnabled()) {
                hapticManager.play(HapticPattern.KNOWN_PERSON);
            }
            if (!enabled) return;
            String message = speechLocalizer.knownPersonAlerts(people);
            if (message.isEmpty()) return;
            if (!initialized) {
                pendingFaceAlert = message;
                return;
            }
            if (!speechVoiceReady) return;
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
            if (initialized && !speechVoiceReady) return;
            if (!initialized) {
                pendingSceneSummary = message;
                return;
            }
            pendingSceneSummary = null;
            speakSceneSummaryOnMain(message);
        });
    }

    /** Speaks a short operational state update without involving camera-frame alert policy. */
    public void speakOperationalStatus(String message) {
        speakOperationalStatus(message, false);
    }

    /** Required service notices can be spoken even when ordinary alert speech is disabled. */
    public void speakOperationalStatus(String message, boolean requiredNotice) {
        if (message == null || message.trim().isEmpty()) return;
        mainHandler.post(() -> {
            if (closed || (!enabled && !requiredNotice)) return;
            if (!initialized || !speechVoiceReady) {
                pendingOperationalStatus = message;
                return;
            }
            int queueMode = textToSpeech.isSpeaking() ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH;
            String utteranceId = "operational-status-" + (++utteranceNumber);
            int result = textToSpeech.speak(message, queueMode, null, utteranceId);
            if (result != TextToSpeech.SUCCESS) Log.w(TAG, "Could not queue operational status: " + result);
        });
    }

    private void onTtsInitialized(int status) {
        if (closed) return;
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Text-to-speech initialization failed: " + status);
            SpeechLanguageCallback callback = pendingLanguageCallback;
            pendingLanguageCallback = null;
            if (callback != null) callback.onVoiceReady(false);
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
        applySpeechLanguageOnMain();
    }

    private void applySpeechLanguageOnMain() {
        if (textToSpeech.isSpeaking()) textToSpeech.stop();
        currentUtteranceId = null;
        currentSpeechPriority = Integer.MAX_VALUE;
        sceneSummaryActive = false;
        Voice voice = findOfflineVoice(speechLanguage.getLocale());
        speechVoiceReady = voice != null && textToSpeech.setVoice(voice) == TextToSpeech.SUCCESS;
        if (!speechVoiceReady) {
            Log.w(TAG, "No offline text-to-speech voice installed for "
                    + speechLanguage.getPreferenceValue());
        }
        SpeechLanguageCallback callback = pendingLanguageCallback;
        pendingLanguageCallback = null;
        if (callback != null) callback.onVoiceReady(speechVoiceReady);
        if (!speechVoiceReady) return;
        if (pendingOperationalStatus != null) {
            String pending = pendingOperationalStatus;
            pendingOperationalStatus = null;
            speakFaceAlertOnMain(pending);
            return;
        }
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

    private Voice findOfflineVoice(Locale locale) {
        Set<Voice> voices = textToSpeech.getVoices();
        if (voices == null) return null;
        Voice best = null;
        for (Voice voice : voices) {
            Locale voiceLocale = voice.getLocale();
            if (voiceLocale == null || !locale.getLanguage().equals(voiceLocale.getLanguage())
                    || voice.isNetworkConnectionRequired()) continue;
            boolean voiceMatchesCountry = locale.getCountry().equalsIgnoreCase(voiceLocale.getCountry());
            boolean bestMatchesCountry = best != null
                    && locale.getCountry().equalsIgnoreCase(best.getLocale().getCountry());
            if (best == null || (voiceMatchesCountry && !bestMatchesCountry)
                    || (voiceMatchesCountry == bestMatchesCountry
                    && voice.getQuality() > best.getQuality())) {
                best = voice;
            }
        }
        return best;
    }

    private void speakFaceAlertOnMain(String message) {
        if (!speechVoiceReady) return;
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
        if (closed || !enabled || !initialized || !speechVoiceReady || sceneSummaryActive
                || pendingFaceAlert == null) return;
        String pending = pendingFaceAlert;
        pendingFaceAlert = null;
        speakFaceAlertOnMain(pending);
    }

    private void speakPendingSceneSummaryIfIdle() {
        if (closed || !enabled || !initialized || !speechVoiceReady || sceneSummaryActive || pendingSceneSummary == null
                || textToSpeech.isSpeaking() || currentSpeechPriority <= 2) return;
        String pending = pendingSceneSummary;
        pendingSceneSummary = null;
        speakSceneSummaryOnMain(pending);
    }

    private void processDetections(List<Detection> detections, int frameWidth, int frameHeight) {
        boolean hapticsEnabled = hapticManager != null && hapticManager.isEnabled();
        if (closed || (!enabled && !hapticsEnabled) || frameWidth <= 0 || frameHeight <= 0) return;
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
                    locationFor(detection, frameWidth), priorityFor(detection.label),
                    boxSizeProxy(detection, frameWidth, frameHeight)));
        }
        tracks.removeIf(track -> frameNumber - track.lastSeenFrame > MAX_MISSED_FRAMES + 1);

        long now = SystemClock.elapsedRealtime();
        List<Candidate> ready = new ArrayList<>();
        for (Candidate candidate : current) {
            int requiredFrames = requiredFrames(candidate);
            boolean voicePending = enabled && !candidate.track.announced;
            boolean hapticPending = hapticsEnabled && !candidate.track.hapticallyAnnounced;
            if ((voicePending || hapticPending)
                    && candidate.track.consecutiveFrames >= requiredFrames) {
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

        AlertGroup first = eligible.get(0);
        StringBuilder message = new StringBuilder();
        List<AlertGroup> selected = new ArrayList<>();
        for (AlertGroup group : eligible) {
            if (group.priority != first.priority || selected.size() >= MAX_OBJECTS_PER_UTTERANCE) break;
            if (message.length() > 0) message.append(' ');
            message.append(speechLocalizer.objectAlert(group.label, group.members.size(),
                    group.lowConfidence, group.location));
            selected.add(group);
        }
        if (selected.isEmpty()) return;
        // Haptics use the same confirmed and grouped detections as speech. The speaker's
        // normalized box-size proxy is only a rough on-screen proximity signal.
        if (hapticsEnabled) {
            for (AlertGroup group : selected) {
                HapticPattern pattern = hapticPatternFor(group);
                boolean pendingForGroup = false;
                for (Candidate member : group.members) {
                    pendingForGroup |= !member.track.hapticallyAnnounced;
                }
                if (!pendingForGroup) continue;
                if (pattern != null) {
                    hapticManager.play(pattern, group.proximityScale);
                }
                for (Candidate member : group.members) member.track.hapticallyAnnounced = true;
            }
        }

        // Keep accumulating detections while the summary speaks, but do not let an alert
        // replace or overlap the user-requested description.
        if (sceneSummaryActive || !enabled || !initialized || !speechVoiceReady) return;
        if (textToSpeech.isSpeaking()) {
            if (first.priority < currentSpeechPriority) textToSpeech.stop();
            else return;
        }
        if (first.priority > 0 && now - lastSpeechAtMs < MIN_SPEECH_INTERVAL_MS) return;

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
        Log.d(TAG, "Speaking object alert");
    }

    private static HapticPattern hapticPatternFor(AlertGroup group) {
        String label = group.label.toLowerCase(Locale.ROOT);
        if ("pothole".equals(label)) return HapticPattern.POTHOLE;
        if ("person".equals(label)) return null;
        if (group.priority == 0 && group.confidence >= IMMEDIATE_HAZARD_CONFIDENCE) {
            return HapticPattern.URGENT_OBSTACLE;
        }
        if (group.boxSizeProxy >= NEAR_BOX_SIZE_PROXY) return HapticPattern.OBSTACLE_NEAR;
        return null;
    }

    private static float boxSizeProxy(Detection detection, int frameWidth, int frameHeight) {
        float widthFraction = Math.max(0f, Math.min(1f, detection.box.width() / frameWidth));
        float heightFraction = Math.max(0f, Math.min(1f, detection.box.height() / frameHeight));
        return (float) Math.sqrt(widthFraction * heightFraction);
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

    private static SpeechLocalizer.Location locationFor(Detection detection, int frameWidth) {
        float centerX = detection.box.centerX();
        if (centerX < frameWidth / 3f) return SpeechLocalizer.Location.LEFT;
        if (centerX > frameWidth * 2f / 3f) return SpeechLocalizer.Location.RIGHT;
        return SpeechLocalizer.Location.CENTER;
    }

    private static AlertGroup groupFor(Candidate seed, List<Candidate> current) {
        List<Candidate> members = new ArrayList<>();
        SpeechLocalizer.Location commonLocation = null;
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
        final SpeechLocalizer.Location location;
        final int priority;
        final float boxSizeProxy;

        Candidate(AlertTrack track, Detection detection, SpeechLocalizer.Location location, int priority,
                  float boxSizeProxy) {
            this.track = track;
            this.detection = detection;
            this.location = location;
            this.priority = priority;
            this.boxSizeProxy = boxSizeProxy;
        }
    }

    private static final class AlertTrack {
        final long id;
        final String classKey;
        RectF box;
        long lastSeenFrame;
        int consecutiveFrames;
        boolean announced;
        boolean hapticallyAnnounced;

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
        final SpeechLocalizer.Location location;
        final List<Candidate> members;
        final float boxSizeProxy;
        final float proximityScale;

        AlertGroup(String classKey, String label, int priority, float confidence,
                   boolean lowConfidence, SpeechLocalizer.Location location, List<Candidate> members) {
            this.classKey = classKey;
            this.label = label;
            this.priority = priority;
            this.confidence = confidence;
            this.lowConfidence = lowConfidence;
            this.location = location;
            this.members = members;
            float largestProxy = 0f;
            for (Candidate member : members) largestProxy = Math.max(largestProxy, member.boxSizeProxy);
            boxSizeProxy = largestProxy;
            proximityScale = Math.max(0.65f, Math.min(1f,
                    0.65f + Math.max(0f, largestProxy - NEAR_BOX_SIZE_PROXY) * 0.7f));
        }
    }
}
