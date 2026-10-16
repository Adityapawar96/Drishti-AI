package com.example.drishtijava.face;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Accepts an identity only when it wins at least two of the latest three valid samples. */
public final class IdentityConsensus {
    private final Deque<String> recent = new ArrayDeque<>();

    public String addSample(String matchedName) {
        recent.addLast(matchedName == null ? "" : matchedName);
        while (recent.size() > 3) recent.removeFirst();
        if (matchedName == null) return null;
        Map<String, Integer> counts = new HashMap<>();
        for (String candidate : recent) {
            if (!candidate.isEmpty()) counts.put(candidate, counts.getOrDefault(candidate, 0) + 1);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() >= 2) return entry.getKey();
        }
        return null;
    }

    public void clear() { recent.clear(); }
}
