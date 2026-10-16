package com.example.drishtijava.face;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class FaceMathTest {
    @Test public void embeddingNormalizationProducesUnitLength() {
        float[] normalized = FaceMath.normalized(new float[]{3f, 4f});
        assertEquals(0.6f, normalized[0], 0.0001f);
        assertEquals(0.8f, normalized[1], 0.0001f);
        assertEquals(1f, (float) Math.sqrt(normalized[0] * normalized[0] + normalized[1] * normalized[1]), 0.0001f);
    }

    @Test public void knownEmbeddingMustMeetThreshold() {
        FaceProfile profile = new FaceProfile("1", "Maya", Collections.singletonList(new float[]{1f, 0f}));
        assertEquals("Maya", FaceMath.bestMatch(new float[]{1f, 0f}, Collections.singletonList(profile), 0.75f).name);
        assertNull(FaceMath.bestMatch(new float[]{0f, 1f}, Collections.singletonList(profile), 0.75f).name);
    }

    @Test public void sameIdentityMustWinTwoOfThreeValidSamples() {
        IdentityConsensus consensus = new IdentityConsensus();
        assertNull(consensus.addSample("Maya"));
        assertNull(consensus.addSample(null));
        assertEquals("Maya", consensus.addSample("Maya"));

        consensus.clear();
        assertNull(consensus.addSample("Maya"));
        assertNull(consensus.addSample("Arjun"));
        assertEquals("Arjun", consensus.addSample("Arjun"));
    }

    @Test public void unknownEmbeddingsDoNotResolveAnIdentity() {
        IdentityConsensus consensus = new IdentityConsensus();
        assertNull(consensus.addSample(null));
        assertNull(consensus.addSample(null));
        assertNull(consensus.addSample(null));
    }

    @Test public void appearanceRearmsAfterTwoSecondsAbsent() {
        long lastSeen = 10_000;
        assertTrue(!PersonAppearanceTracker.isRearmed(lastSeen, lastSeen + 1999));
        assertTrue(PersonAppearanceTracker.isRearmed(lastSeen, lastSeen + 2000));
    }

    @Test public void poseReferenceAverageIsNormalized() {
        float[] average = FaceMath.averageNormalized(Arrays.asList(new float[]{1f, 0f}, new float[]{0.8f, 0.6f}));
        assertEquals(1f, (float) Math.sqrt(average[0] * average[0] + average[1] * average[1]), 0.0001f);
    }
}
