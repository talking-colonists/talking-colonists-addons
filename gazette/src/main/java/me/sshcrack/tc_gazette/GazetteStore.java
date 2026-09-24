package me.sshcrack.tc_gazette;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.sshcrack.tc_gazette.shared.store.JsonFile;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-colony gazette state, saved as JSON in the world folder. Only touched on the server thread.
 */
public final class GazetteStore {
    private final Path file;
    private final Map<String, ColonyState> colonies = new HashMap<>();

    public static final class ColonyState {
        /** In-game day of the last issue, or of the last day that was skipped as quiet. */
        public long lastIssueDay;
        /** Game time up to which events are covered. */
        public long coveredUntilGameTime;
        public int issueCount;
        public @Nullable GazetteIssue latest;
        /** Players who took a free copy of {@link #latest}. */
        public final Set<UUID> claimed = new HashSet<>();
    }

    public GazetteStore(Path file) {
        this.file = file;
    }

    public @Nullable ColonyState get(String colonyKey) {
        return colonies.get(colonyKey);
    }

    public ColonyState getOrCreate(String colonyKey) {
        return colonies.computeIfAbsent(colonyKey, key -> new ColonyState());
    }

    public void load() {
        colonies.clear();
        try {
            JsonObject root = JsonFile.read(file);
            if (root == null) return;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!(entry.getValue() instanceof JsonObject json)) continue;
                ColonyState state = new ColonyState();
                state.lastIssueDay = json.has("lastIssueDay") ? json.get("lastIssueDay").getAsLong() : 0;
                state.coveredUntilGameTime = json.has("coveredUntilGameTime") ? json.get("coveredUntilGameTime").getAsLong() : 0;
                state.issueCount = json.has("issueCount") ? json.get("issueCount").getAsInt() : 0;
                if (json.get("latest") instanceof JsonObject latest) state.latest = GazetteIssue.load(latest);
                if (json.get("claimed") instanceof JsonArray claimed) {
                    for (JsonElement id : claimed) {
                        try {
                            state.claimed.add(UUID.fromString(id.getAsString()));
                        } catch (RuntimeException ignored) {
                            // skip damaged ids
                        }
                    }
                }
                colonies.put(entry.getKey(), state);
            }
        } catch (IOException | RuntimeException e) {
            ColonyGazette.LOGGER.error("Could not read {}; starting with empty gazette state", file, e);
        }
    }

    public void save() {
        JsonObject root = new JsonObject();
        colonies.forEach((key, state) -> {
            JsonObject json = new JsonObject();
            json.addProperty("lastIssueDay", state.lastIssueDay);
            json.addProperty("coveredUntilGameTime", state.coveredUntilGameTime);
            json.addProperty("issueCount", state.issueCount);
            if (state.latest != null) json.add("latest", state.latest.toJson());
            JsonArray claimed = new JsonArray();
            state.claimed.forEach(id -> claimed.add(id.toString()));
            json.add("claimed", claimed);
            root.add(key, json);
        });
        try {
            JsonFile.write(file, root);
        } catch (IOException e) {
            ColonyGazette.LOGGER.error("Could not save {}", file, e);
        }
    }
}
