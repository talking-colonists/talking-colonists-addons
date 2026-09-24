package me.sshcrack.tc_tavern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.sshcrack.tc_tavern.shared.store.JsonFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Each visitor's original recruit amount and today's price changes, saved in the world folder. Server thread only. */
public final class HaggleStore {
    public static final class Entry {
        public int original;
        public long day = -1;
        public int changesToday;
    }

    private final Path file;
    private final Map<UUID, Entry> entries = new HashMap<>();

    public HaggleStore(Path file) {
        this.file = file;
    }

    /** The entry of this visitor; a new one remembers {@code currentAmount} as the original. */
    public Entry get(UUID visitor, int currentAmount) {
        return entries.computeIfAbsent(visitor, id -> {
            Entry entry = new Entry();
            entry.original = currentAmount;
            return entry;
        });
    }

    public void load() {
        entries.clear();
        try {
            JsonObject root = JsonFile.read(file);
            if (root == null) return;
            for (Map.Entry<String, JsonElement> item : root.entrySet()) {
                JsonObject json = item.getValue().getAsJsonObject();
                Entry entry = new Entry();
                entry.original = json.get("original").getAsInt();
                entry.day = json.get("day").getAsLong();
                entry.changesToday = json.get("changesToday").getAsInt();
                entries.put(UUID.fromString(item.getKey()), entry);
            }
        } catch (IOException | RuntimeException e) {
            TavernRecruiter.LOGGER.error("Could not read {}; starting with no haggling history", file, e);
        }
    }

    public void save() {
        JsonObject root = new JsonObject();
        entries.forEach((visitor, entry) -> {
            JsonObject json = new JsonObject();
            json.addProperty("original", entry.original);
            json.addProperty("day", entry.day);
            json.addProperty("changesToday", entry.changesToday);
            root.add(visitor.toString(), json);
        });
        try {
            JsonFile.write(file, root);
        } catch (IOException e) {
            TavernRecruiter.LOGGER.error("Could not save {}", file, e);
        }
    }
}
