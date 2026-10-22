package com.example.drishtijava;

/** Pure speech-preemption policy. Callers own all state and invoke it on the TTS thread. */
final class SpeechAlertArbiter {
    enum Kind {
        SCENE_SUMMARY,
        URGENT_HAZARD,
        KNOWN_PERSON,
        ROUTINE_OBJECT,
        UNKNOWN_PERSON,
        OPERATIONAL_STATUS
    }

    enum Decision { START, PREEMPT, WAIT }

    private SpeechAlertArbiter() { }

    static Decision decide(Kind current, Kind incoming) {
        if (incoming == null) return Decision.WAIT;
        if (current == null) return Decision.START;
        if (incoming == Kind.SCENE_SUMMARY) return Decision.PREEMPT;
        if (current == Kind.SCENE_SUMMARY) return Decision.WAIT;
        if (incoming == Kind.URGENT_HAZARD) {
            return current == Kind.URGENT_HAZARD ? Decision.WAIT : Decision.PREEMPT;
        }
        if (incoming == Kind.KNOWN_PERSON) {
            return current == Kind.ROUTINE_OBJECT
                    || current == Kind.UNKNOWN_PERSON
                    || current == Kind.OPERATIONAL_STATUS
                    ? Decision.PREEMPT : Decision.WAIT;
        }
        return Decision.WAIT;
    }
}
