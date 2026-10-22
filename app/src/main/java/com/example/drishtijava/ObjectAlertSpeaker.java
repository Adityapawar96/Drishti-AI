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
import java.util.LinkedHashMap;
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
    private static final long MAX_PENDING_OBJECT_AGE_MS = 1200;
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
    private boolean pendingOperationalRequired;
    private boolean speechDispatchScheduled;
    private long frameNumber;
    private long lastSpeechAtMs;
    private int utteranceNumber;
    private long nextTrackId = 1;
    private SpeechAlertArbiter.Kind currentSpeechKind;
    private final Map<String, PendingObjectGroup> pendingObjectGroups = new LinkedHashMap<>();
    private final Map<String, SpeechLocalizer.NamedPerson> pendingKnownPeople = new LinkedHashMap<>();
    private PendingPersonAlert pendingUnknownPersonAlert;
    private String pendingSceneSummary;
    private String pendingOperationalStatus;
    private String currentUtteranceId;
    private final Runnable speechDispatchRunnable = () -> {
        speechDispatchScheduled = false;
        dispatchPendingSpeech();
    };

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
            if (!enabled) {
                clearPendingAlerts();
                if (initialized) textToSpeech.stop();
                currentUtteranceId = null;
                currentSpeechKind = null;
            } else {
                dispatchPendingSpeech();
            }
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
            pendingObjectGroups.clear();
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
            pendingUnknownPersonAlert = new PendingPersonAlert(count, possible, location);
            dispatchPendingSpeech();
        });
    }

    /** Drops an unknown-person alert that was waiting after its people-present period ended. */
    public void clearPendingPersonAlert() {
        mainHandler.post(() -> pendingUnknownPersonAlert = null);
    }

    /** Speaks recognized names and localized positions with the dedicated person haptic cue. */
    public void speakKnownPersonAlerts(Iterable<SpeechLocalizer.NamedPerson> people) {
        mainHandler.post(() -> {
            if (closed) return;
            if (hapticManager != null && hapticManager.isEnabled()) {
                hapticManager.play(HapticPattern.KNOWN_PERSON);
            }
            if (!enabled) return;
            if (people != null) {
                for (SpeechLocalizer.NamedPerson person : people) {
                    if (person == null || person.name.isEmpty()) continue;
                    String key = person.name.toLowerCase(Locale.ROOT);
                    pendingKnownPeople.put(key, person);
                }
            }
            dispatchPendingSpeech();
        });
    }

    /** Gives a user-requested scene summary exclusive use of speech until it finishes. */
    public void speakSceneSummary(String message) {
        if (message == null || message.trim().isEmpty()) return;
        runOnMain(() -> {
            if (closed || !enabled) return;
            if (initialized && !speechVoiceReady) return;
            pendingSceneSummary = message;
            dispatchPendingSpeech();
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
                pendingOperationalRequired = requiredNotice;
                return;
            }
            pendingOperationalStatus = message;
            pendingOperationalRequired = requiredNotice;
            dispatchPendingSpeech();
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
        currentSpeechKind = null;
        Voice voice = findOfflineVoice(speechLanguage.getLocale());
        speechVoiceReady = voice != null && textToSpeech.setVoice(voice) == TextToSpeech.SUCCESS;
        if (!speechVoiceReady) {
            Log.w(TAG, "No offline text-to-speech voice installed for "
                    + speechLanguage.getPreferenceValue());
        }
        SpeechLanguageCallback callback = pendingLanguageCallback;
        pendingLanguageCallback = null;
        if (callback != null) callback.onVoiceReady(speechVoiceReady);
        dispatchPendingSpeech();
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

    private void dispatchPendingSpeech() {
        if (closed || !initialized || !speechVoiceReady) return;

        if (enabled && pendingSceneSummary != null) {
            String message = pendingSceneSummary;
            if (startSpeech(SpeechAlertArbiter.Kind.SCENE_SUMMARY, message, null)) {
                pendingSceneSummary = null;
            }
            return;
        }

        if (enabled) {
            long now = SystemClock.elapsedRealtime();
            pruneStaleObjectGroups(now);

            SpeechBatch urgent = nextObjectBatch(true);
            if (urgent != null) {
                if (startSpeech(SpeechAlertArbiter.Kind.URGENT_HAZARD,
                        urgent.message, urgent.groups)) {
                    removeSpokenObjectGroups(urgent.groups);
                }
                return;
            }

            if (!pendingKnownPeople.isEmpty()) {
                String message = speechLocalizer.knownPersonAlerts(pendingKnownPeople.values());
                if (message.isEmpty()) {
                    pendingKnownPeople.clear();
                } else if (startSpeech(SpeechAlertArbiter.Kind.KNOWN_PERSON, message, null)) {
                    pendingKnownPeople.clear();
                }
                return;
            }

            if (pendingUnknownPersonAlert != null) {
                PendingPersonAlert pending = pendingUnknownPersonAlert;
                String message = speechLocalizer.personAlert(
                        pending.count, pending.possible, pending.location);
                if (startSpeech(SpeechAlertArbiter.Kind.UNKNOWN_PERSON, message, null)) {
                    pendingUnknownPersonAlert = null;
                }
                return;
            }

            SpeechBatch routine = nextObjectBatch(false);
            if (routine != null) {
                long elapsed = now - lastSpeechAtMs;
                boolean rateLimitedClass = routine.groups.get(0).priority > 0;
                if (currentSpeechKind == null && rateLimitedClass
                        && elapsed < MIN_SPEECH_INTERVAL_MS) {
                    scheduleSpeechDispatch(MIN_SPEECH_INTERVAL_MS - elapsed);
                    return;
                }
                if (startSpeech(SpeechAlertArbiter.Kind.ROUTINE_OBJECT,
                        routine.message, routine.groups)) {
                    removeSpokenObjectGroups(routine.groups);
                }
                return;
            }
        }

        if (pendingOperationalStatus != null
                && (enabled || pendingOperationalRequired)) {
            String message = pendingOperationalStatus;
            if (startSpeech(SpeechAlertArbiter.Kind.OPERATIONAL_STATUS, message, null)) {
                pendingOperationalStatus = null;
                pendingOperationalRequired = false;
            }
        }
    }

    private boolean startSpeech(SpeechAlertArbiter.Kind kind, String message,
                                List<AlertGroup> spokenGroups) {
        if (message == null || message.trim().isEmpty() || !initialized || !speechVoiceReady) return false;
        SpeechAlertArbiter.Decision decision = SpeechAlertArbiter.decide(currentSpeechKind, kind);
        if (decision == SpeechAlertArbiter.Decision.WAIT) return false;

        if (decision == SpeechAlertArbiter.Decision.PREEMPT) {
            if (textToSpeech.isSpeaking() || currentSpeechKind != null) textToSpeech.stop();
            currentUtteranceId = null;
            currentSpeechKind = null;
        }

        String utteranceId = kind.name().toLowerCase(Locale.ROOT) + "-" + (++utteranceNumber);
        int result = textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Could not start " + kind + " speech: " + result);
            return false;
        }

        long now = SystemClock.elapsedRealtime();
        currentSpeechKind = kind;
        currentUtteranceId = utteranceId;
        if (kind != SpeechAlertArbiter.Kind.SCENE_SUMMARY
                && kind != SpeechAlertArbiter.Kind.OPERATIONAL_STATUS) {
            lastSpeechAtMs = now;
        }
        if (spokenGroups != null) {
            for (AlertGroup group : spokenGroups) {
                for (Candidate member : group.members) member.track.announced = true;
                lastAlertByClass.put(group.classKey, now);
            }
        }
        Log.d(TAG, "Speaking " + kind);
        return true;
    }

    private void onSpeechFinished(String utteranceId) {
        if (utteranceId == null || !utteranceId.equals(currentUtteranceId)) return;
        currentUtteranceId = null;
        currentSpeechKind = null;
        dispatchPendingSpeech();
    }

    private SpeechBatch nextObjectBatch(boolean urgentOnly) {
        if (pendingObjectGroups.isEmpty()) return null;
        List<AlertGroup> ordered = new ArrayList<>();
        for (PendingObjectGroup pending : pendingObjectGroups.values()) {
            AlertGroup group = pending.group;
            if (urgentOnly == isUrgentHazard(group)) ordered.add(group);
        }
        if (ordered.isEmpty()) return null;
        ordered.sort(Comparator.comparingInt((AlertGroup item) -> item.priority)
                .thenComparing(Comparator.comparingDouble(
                        (AlertGroup item) -> item.confidence).reversed()));

        AlertGroup first = ordered.get(0);
        List<AlertGroup> selected = new ArrayList<>();
        StringBuilder message = new StringBuilder();
        for (AlertGroup group : ordered) {
            if (group.priority != first.priority || selected.size() >= MAX_OBJECTS_PER_UTTERANCE) break;
            if (message.length() > 0) message.append(' ');
            message.append(speechLocalizer.objectAlert(group.label, group.members.size(),
                    group.lowConfidence, group.location));
            selected.add(group);
        }
        return selected.isEmpty() ? null : new SpeechBatch(message.toString(), selected);
    }

    private void updatePendingObjectGroups(List<AlertGroup> currentGroups, long now) {
        if (!enabled) {
            pendingObjectGroups.clear();
            return;
        }
        Set<String> currentKeys = new HashSet<>();
        for (AlertGroup group : currentGroups) {
            currentKeys.add(group.classKey);
            pendingObjectGroups.put(group.classKey, new PendingObjectGroup(group, now));
        }
        pendingObjectGroups.keySet().removeIf(key -> !currentKeys.contains(key));
    }

    private void pruneStaleObjectGroups(long now) {
        pendingObjectGroups.entrySet().removeIf(entry ->
                now - entry.getValue().updatedAtMs > MAX_PENDING_OBJECT_AGE_MS);
    }

    private void removeSpokenObjectGroups(List<AlertGroup> groups) {
        for (AlertGroup group : groups) pendingObjectGroups.remove(group.classKey);
    }

    private static boolean isUrgentHazard(AlertGroup group) {
        return "pothole".equalsIgnoreCase(group.label)
                || (group.priority == 0 && !"person".equalsIgnoreCase(group.label)
                && group.confidence >= IMMEDIATE_HAZARD_CONFIDENCE);
    }

    private void scheduleSpeechDispatch(long delayMs) {
        if (speechDispatchScheduled || closed) return;
        speechDispatchScheduled = true;
        mainHandler.postDelayed(speechDispatchRunnable, Math.max(1, delayMs));
    }

    private void clearPendingAlerts() {
        mainHandler.removeCallbacks(speechDispatchRunnable);
        speechDispatchScheduled = false;
        pendingObjectGroups.clear();
        pendingKnownPeople.clear();
        pendingUnknownPersonAlert = null;
        pendingSceneSummary = null;
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
        List<Candidate> voiceReady = new ArrayList<>();
        List<Candidate> hapticReady = new ArrayList<>();
        for (Candidate candidate : current) {
            int requiredFrames = requiredFrames(candidate);
            boolean voicePending = enabled && !candidate.track.announced;
            boolean hapticPending = hapticsEnabled && !candidate.track.hapticallyAnnounced;
            if (candidate.track.consecutiveFrames < requiredFrames) continue;
            if (voicePending) voiceReady.add(candidate);
            if (hapticPending) hapticReady.add(candidate);
        }
        Comparator<Candidate> candidateOrder = Comparator.comparingInt((Candidate item) -> item.priority)
                .thenComparing(Comparator.comparingDouble(
                        (Candidate item) -> item.detection.confidence).reversed());
        voiceReady.sort(candidateOrder);
        hapticReady.sort(candidateOrder);

        List<AlertGroup> hapticGroups = confirmedGroups(hapticReady, current, now);
        List<AlertGroup> voiceGroups = confirmedGroups(voiceReady, current, now);
        updatePendingObjectGroups(voiceGroups, now);

        // Haptics use the same confirmed and grouped detections as speech. The speaker's
        // normalized box-size proxy is only a rough on-screen proximity signal.
        if (hapticsEnabled) {
            for (AlertGroup group : hapticGroups) {
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
        dispatchPendingSpeech();
    }

    private List<AlertGroup> confirmedGroups(List<Candidate> ready, List<Candidate> current, long now) {
        List<AlertGroup> groups = new ArrayList<>();
        Set<String> groupedClassKeys = new HashSet<>();
        for (Candidate candidate : ready) {
            String classKey = candidate.track.classKey;
            Long lastAlertAt = lastAlertByClass.get(classKey);
            if (lastAlertAt != null && now - lastAlertAt < SAME_CLASS_COOLDOWN_MS) continue;
            if (!groupedClassKeys.add(classKey)) continue;
            groups.add(groupFor(candidate, current));
        }
        groups.sort(Comparator.comparingInt((AlertGroup item) -> item.priority)
                .thenComparing(Comparator.comparingDouble(
                        (AlertGroup item) -> item.confidence).reversed()));
        return groups;
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
        boolean urgentClass = candidate.priority == 0
                || "person".equalsIgnoreCase(candidate.detection.label);
        if (urgentClass && candidate.detection.confidence >= IMMEDIATE_HAZARD_CONFIDENCE) return 1;
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
            case "person":
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
            clearPendingAlerts();
            pendingOperationalStatus = null;
            currentUtteranceId = null;
            currentSpeechKind = null;
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

    private static final class PendingObjectGroup {
        final AlertGroup group;
        final long updatedAtMs;

        PendingObjectGroup(AlertGroup group, long updatedAtMs) {
            this.group = group;
            this.updatedAtMs = updatedAtMs;
        }
    }

    private static final class PendingPersonAlert {
        final int count;
        final boolean possible;
        final SpeechLocalizer.Location location;

        PendingPersonAlert(int count, boolean possible, SpeechLocalizer.Location location) {
            this.count = Math.max(1, count);
            this.possible = possible;
            this.location = location == null ? SpeechLocalizer.Location.VIEW : location;
        }
    }

    private static final class SpeechBatch {
        final String message;
        final List<AlertGroup> groups;

        SpeechBatch(String message, List<AlertGroup> groups) {
            this.message = message;
            this.groups = groups;
        }
    }
}
