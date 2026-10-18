package com.example.drishtijava;

import java.util.EnumMap;
import java.util.Map;

/** Pure-Java arbitration for cooldowns and one-at-a-time haptic playback. */
final class HapticAlertPolicy {
    private final Map<HapticPattern, Long> lastAcceptedAt = new EnumMap<>(HapticPattern.class);
    private HapticPattern activePattern;
    private long activeUntilMs;

    Decision request(HapticPattern pattern, long nowMs) {
        Long lastAt = lastAcceptedAt.get(pattern);
        if (lastAt != null && nowMs - lastAt < pattern.cooldownMs()) {
            return Decision.rejected();
        }

        boolean cancelActive = false;
        if (activePattern != null && nowMs < activeUntilMs) {
            if (pattern.priority() <= activePattern.priority()) {
                return Decision.rejected();
            }
            cancelActive = true;
        }

        lastAcceptedAt.put(pattern, nowMs);
        activePattern = pattern;
        activeUntilMs = nowMs + pattern.durationMs();
        return Decision.accepted(cancelActive);
    }

    void cancelActive() {
        activePattern = null;
        activeUntilMs = 0;
    }

    static final class Decision {
        final boolean accepted;
        final boolean cancelExisting;

        private Decision(boolean accepted, boolean cancelExisting) {
            this.accepted = accepted;
            this.cancelExisting = cancelExisting;
        }

        private static Decision rejected() { return new Decision(false, false); }
        private static Decision accepted(boolean cancelExisting) {
            return new Decision(true, cancelExisting);
        }
    }
}
