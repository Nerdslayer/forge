package forge.adventure.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Session-only checkpoint information; opening an old save does not itself create unsaved progress. */
public final class AdventureProgress {
    private boolean active;
    private boolean changed;
    private long savedAt;
    private Map<String, byte[]> checkpoint = new HashMap<>();

    public boolean isActive() { return active; }
    public long getSavedAt() { return savedAt; }

    public void startNew() {
        active = true;
        changed = true;
        savedAt = 0;
        checkpoint.clear();
    }

    public void checkpoint(long time, Map<String, byte[]> state) {
        Map<String, byte[]> copy = new HashMap<>();
        state.forEach((key, bytes) -> copy.put(key, bytes.clone()));
        checkpoint = copy;
        active = true;
        changed = false;
        savedAt = time;
    }

    public void markChanged() {
        if (active) changed = true;
    }

    public boolean hasChanges(Map<String, byte[]> state) {
        return active && (changed || !checkpoint.keySet().equals(state.keySet())
                || checkpoint.entrySet().stream().anyMatch(e -> !Arrays.equals(e.getValue(), state.get(e.getKey()))));
    }
}
