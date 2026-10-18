package com.example.drishtijava;

/** Small Android-independent service state machine used to guard duplicate starts and stops. */
final class DrishtiServiceState {
    enum State { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    private State state = State.STOPPED;

    synchronized boolean requestStart() {
        if (state != State.STOPPED && state != State.ERROR) return false;
        state = State.STARTING;
        return true;
    }

    synchronized boolean markRunning() {
        if (state != State.STARTING && state != State.RUNNING) return false;
        state = State.RUNNING;
        return true;
    }

    synchronized boolean requestStop() {
        if (state == State.STOPPED || state == State.STOPPING) return false;
        state = State.STOPPING;
        return true;
    }

    synchronized boolean markError() {
        if (state == State.STOPPED || state == State.STOPPING) return false;
        state = State.ERROR;
        return true;
    }

    synchronized void markStopped() { state = State.STOPPED; }

    synchronized State current() { return state; }
}
