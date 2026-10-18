package com.example.drishtijava;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DrishtiServiceStateTest {
    @Test public void startMovesThroughStartingToRunningAndRejectsDuplicateStart() {
        DrishtiServiceState state = new DrishtiServiceState();
        assertTrue(state.requestStart());
        assertEquals(DrishtiServiceState.State.STARTING, state.current());
        assertFalse(state.requestStart());
        assertTrue(state.markRunning());
        assertEquals(DrishtiServiceState.State.RUNNING, state.current());
        assertFalse(state.requestStart());
    }

    @Test public void stopIsIdempotentAndCanReturnToStopped() {
        DrishtiServiceState state = new DrishtiServiceState();
        assertTrue(state.requestStart());
        assertTrue(state.markRunning());
        assertTrue(state.requestStop());
        assertEquals(DrishtiServiceState.State.STOPPING, state.current());
        assertFalse(state.requestStop());
        assertFalse(state.markError());
        state.markStopped();
        assertEquals(DrishtiServiceState.State.STOPPED, state.current());
    }

    @Test public void errorCanRetryButCannotOverrideStop() {
        DrishtiServiceState state = new DrishtiServiceState();
        assertTrue(state.requestStart());
        assertTrue(state.markError());
        assertEquals(DrishtiServiceState.State.ERROR, state.current());
        assertTrue(state.requestStart());
        assertTrue(state.markRunning());
        assertTrue(state.requestStop());
        assertFalse(state.markError());
        assertEquals(DrishtiServiceState.State.STOPPING, state.current());
    }
}
