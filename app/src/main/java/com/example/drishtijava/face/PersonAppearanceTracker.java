package com.example.drishtijava.face;

import android.graphics.RectF;

import com.example.drishtijava.Detection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Lightweight person-box tracks with a two-second absence window for re-arming alerts. */
public final class PersonAppearanceTracker {
    public static final long REARM_AFTER_ABSENT_MS = 2000;
    // Keep aligned with ObjectAlertSpeaker.MIN_PERSON_ALERT_CONFIDENCE.
    private static final float MIN_PERSON_CONFIDENCE = 0.30f;
    private static final float MIN_IOU = 0.20f;
    private final List<PersonTrack> tracks = new ArrayList<>();
    private long nextId = 1;

    public synchronized List<PersonTrack> update(List<Detection> detections, long nowMs) {
        for (Iterator<PersonTrack> iterator = tracks.iterator(); iterator.hasNext();) {
            if (isRearmed(iterator.next().lastSeenAtMs, nowMs)) iterator.remove();
        }

        Set<Long> used = new HashSet<>();
        for (Detection detection : detections) {
            if (!"person".equalsIgnoreCase(detection.label) || detection.confidence < MIN_PERSON_CONFIDENCE) continue;
            PersonTrack best = null;
            float bestIou = MIN_IOU;
            for (PersonTrack track : tracks) {
                if (used.contains(track.id)) continue;
                float overlap = iou(track.box, detection.box);
                if (overlap >= bestIou) {
                    bestIou = overlap;
                    best = track;
                }
            }
            if (best == null) {
                best = new PersonTrack(nextId++, detection.box, detection.confidence, nowMs);
                tracks.add(best);
            } else {
                best.box = new RectF(detection.box);
                best.confidence = detection.confidence;
                best.lastSeenAtMs = nowMs;
            }
            used.add(best.id);
        }
        return new ArrayList<>(tracks);
    }

    public synchronized List<PersonTrack> snapshot() { return new ArrayList<>(tracks); }

    public synchronized PersonTrack findForFace(RectF faceBox) {
        float centerX = faceBox.centerX();
        float centerY = faceBox.centerY();
        PersonTrack best = null;
        float bestScore = Float.MAX_VALUE;
        for (PersonTrack track : tracks) {
            RectF upperBody = new RectF(track.box.left, track.box.top,
                    track.box.right, track.box.top + track.box.height() * 0.72f);
            if (!upperBody.contains(centerX, centerY)) continue;
            float score = Math.abs(upperBody.centerX() - centerX) / Math.max(1f, upperBody.width())
                    + Math.abs(upperBody.centerY() - centerY) / Math.max(1f, upperBody.height());
            if (score < bestScore) {
                bestScore = score;
                best = track;
            }
        }
        return best;
    }

    public synchronized void clear() { tracks.clear(); }

    public static boolean isRearmed(long lastSeenAtMs, long nowMs) {
        return nowMs - lastSeenAtMs >= REARM_AFTER_ABSENT_MS;
    }

    private static float iou(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
        float union = a.width() * a.height() + b.width() * b.height() - intersection;
        return union <= 0 ? 0 : intersection / union;
    }

    public static final class PersonTrack {
        public final long id;
        public RectF box;
        public final long firstSeenAtMs;
        public long lastSeenAtMs;
        public float confidence;
        public boolean nameAnnounced;
        public boolean genericAnnounced;
        public String resolvedName;
        public final IdentityConsensus consensus = new IdentityConsensus();

        PersonTrack(long id, RectF box, float confidence, long nowMs) {
            this.id = id;
            this.box = new RectF(box);
            this.confidence = confidence;
            firstSeenAtMs = nowMs;
            lastSeenAtMs = nowMs;
        }
    }
}
