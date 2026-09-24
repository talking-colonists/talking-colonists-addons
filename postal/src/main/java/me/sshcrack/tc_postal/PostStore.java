package me.sshcrack.tc_postal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.sshcrack.tc_postal.shared.store.JsonFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Letters on their way to citizens, and replies waiting to be carried to players, saved as JSON in the world folder. Server thread only. */
public final class PostStore {
    /** A letter from a player on its way to a citizen. */
    public static final class Letter {
        public UUID id = UUID.randomUUID();
        public String colonyKey = "";
        public int recipientId;
        public String recipientName = "";
        public UUID playerId = new UUID(0, 0);
        public String playerName = "";
        public String title = "";
        public String body = "";
        public boolean handedOver;
        public long sentGameTime;
        public long dueGameTime;
        public int attempts;
        /** A reply is being written right now (not saved). */
        public transient boolean writing;
    }

    /**
     * A letter for a player, waiting until a citizen of {@code colonyKey} can carry it to them.
     * {@code fromCitizenId} is the writer (who carries it without a courier), or -1.
     */
    public record Mail(UUID id, String colonyKey, int fromCitizenId, String from, String title, List<String> pages) {
    }

    private final Path file;
    private final List<Letter> letters = new ArrayList<>();
    private final Map<UUID, List<Mail>> mailboxes = new HashMap<>();

    public PostStore(Path file) {
        this.file = file;
    }

    public List<Letter> letters() {
        return letters;
    }

    public List<Mail> mailbox(UUID player) {
        return mailboxes.computeIfAbsent(player, id -> new ArrayList<>());
    }

    public long inTransit(UUID player) {
        return letters.stream().filter(letter -> letter.playerId.equals(player)).count();
    }

    public void load() {
        letters.clear();
        mailboxes.clear();
        try {
            JsonObject root = JsonFile.read(file);
            if (root == null) return;
            if (root.get("letters") instanceof JsonArray array) {
                for (JsonElement element : array) {
                    if (element instanceof JsonObject json) letters.add(letter(json));
                }
            }
            if (root.get("mailboxes") instanceof JsonObject boxes) {
                for (Map.Entry<String, JsonElement> entry : boxes.entrySet()) {
                    List<Mail> box = mailbox(UUID.fromString(entry.getKey()));
                    for (JsonElement element : entry.getValue().getAsJsonArray()) {
                        JsonObject json = element.getAsJsonObject();
                        List<String> pages = new ArrayList<>();
                        json.getAsJsonArray("pages").forEach(page -> pages.add(page.getAsString()));
                        box.add(new Mail(json.has("id") ? UUID.fromString(json.get("id").getAsString()) : UUID.randomUUID(),
                                json.has("colonyKey") ? json.get("colonyKey").getAsString() : "",
                                json.has("fromCitizenId") ? json.get("fromCitizenId").getAsInt() : -1,
                                json.get("from").getAsString(), json.get("title").getAsString(), List.copyOf(pages)));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            PostalService.LOGGER.error("Could not read {}; starting with an empty post office", file, e);
        }
    }

    public void save() {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        letters.forEach(letter -> array.add(json(letter)));
        root.add("letters", array);
        JsonObject boxes = new JsonObject();
        mailboxes.forEach((player, box) -> {
            if (box.isEmpty()) return;
            JsonArray mails = new JsonArray();
            for (Mail mail : box) {
                JsonObject json = new JsonObject();
                json.addProperty("id", mail.id().toString());
                json.addProperty("colonyKey", mail.colonyKey());
                json.addProperty("fromCitizenId", mail.fromCitizenId());
                json.addProperty("from", mail.from());
                json.addProperty("title", mail.title());
                JsonArray pages = new JsonArray();
                mail.pages().forEach(pages::add);
                json.add("pages", pages);
                mails.add(json);
            }
            boxes.add(player.toString(), mails);
        });
        root.add("mailboxes", boxes);
        try {
            JsonFile.write(file, root);
        } catch (IOException e) {
            PostalService.LOGGER.error("Could not save {}", file, e);
        }
    }

    private static JsonObject json(Letter letter) {
        JsonObject json = new JsonObject();
        json.addProperty("id", letter.id.toString());
        json.addProperty("colonyKey", letter.colonyKey);
        json.addProperty("recipientId", letter.recipientId);
        json.addProperty("recipientName", letter.recipientName);
        json.addProperty("playerId", letter.playerId.toString());
        json.addProperty("playerName", letter.playerName);
        json.addProperty("title", letter.title);
        json.addProperty("body", letter.body);
        json.addProperty("handedOver", letter.handedOver);
        json.addProperty("sentGameTime", letter.sentGameTime);
        json.addProperty("dueGameTime", letter.dueGameTime);
        json.addProperty("attempts", letter.attempts);
        return json;
    }

    private static Letter letter(JsonObject json) {
        Letter letter = new Letter();
        letter.id = UUID.fromString(json.get("id").getAsString());
        letter.colonyKey = json.get("colonyKey").getAsString();
        letter.recipientId = json.get("recipientId").getAsInt();
        letter.recipientName = json.get("recipientName").getAsString();
        letter.playerId = UUID.fromString(json.get("playerId").getAsString());
        letter.playerName = json.get("playerName").getAsString();
        letter.title = json.get("title").getAsString();
        letter.body = json.get("body").getAsString();
        letter.handedOver = json.get("handedOver").getAsBoolean();
        letter.sentGameTime = json.get("sentGameTime").getAsLong();
        letter.dueGameTime = json.get("dueGameTime").getAsLong();
        letter.attempts = json.get("attempts").getAsInt();
        return letter;
    }
}
