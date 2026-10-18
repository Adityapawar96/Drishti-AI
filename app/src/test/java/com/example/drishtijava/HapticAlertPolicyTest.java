package com.example.drishtijava;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HapticAlertPolicyTest {
    @Test public void samePatternIsBlockedUntilItsCooldownExpires() {
        HapticAlertPolicy policy = new HapticAlertPolicy();

        assertTrue(policy.request(HapticPattern.MODE_OBJECT, 1_000).accepted);
        assertFalse(policy.request(HapticPattern.MODE_OBJECT, 1_100).accepted);
        assertTrue(policy.request(HapticPattern.MODE_OBJECT, 1_300).accepted);
    }

    @Test public void higherPriorityPatternCancelsActiveLowerPriorityPattern() {
        HapticAlertPolicy policy = new HapticAlertPolicy();

        assertTrue(policy.request(HapticPattern.GEMINI_STARTED, 1_000).accepted);
        HapticAlertPolicy.Decision urgent = policy.request(HapticPattern.URGENT_OBSTACLE, 1_010);

        assertTrue(urgent.accepted);
        assertTrue(urgent.cancelExisting);
    }

    @Test public void lowerPriorityPatternIsRejectedWhileHigherPriorityPatternIsActive() {
        HapticAlertPolicy policy = new HapticAlertPolicy();

        assertTrue(policy.request(HapticPattern.URGENT_OBSTACLE, 1_000).accepted);
        assertFalse(policy.request(HapticPattern.GEMINI_STARTED, 1_010).accepted);
    }

    @Test public void rejectedPatternIsNotQueuedForLaterPlayback() {
        HapticAlertPolicy policy = new HapticAlertPolicy();

        assertTrue(policy.request(HapticPattern.OBSTACLE_NEAR, 1_000).accepted);
        assertFalse(policy.request(HapticPattern.GEMINI_STARTED, 1_010).accepted);
        // Nothing is replayed when the first vibration expires; a fresh request is required.
        assertTrue(policy.request(HapticPattern.GEMINI_STARTED, 1_300).accepted);
    }
}
