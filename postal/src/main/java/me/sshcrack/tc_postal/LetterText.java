package me.sshcrack.tc_postal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure letter logic: who a letter is for, what the recipient is asked, and reading the answer. */
public final class LetterText {
    public static final int NOT_FOUND = -1;
    public static final int AMBIGUOUS = -2;
    static final int MAX_LETTER_CHARS = 2_000;
    static final int MAX_REPLY_CHARS = 1_500;
    static final int MAX_MEMORY_CHARS = 200;
    private static final int MAX_BOOK_TITLE = 32;

    private LetterText() {
    }

    /** A written reply and the sentence the recipient remembers about the exchange. */
    public record Answer(String reply, String memory) {
    }

    /**
     * Finds the recipient named by a letter's title in {@code names}: "Anna Smith", "To Anna",
     * "Dear Anna," all work. Full names win over first names; first and last name also match a name
     * with a middle initial. Returns the index, {@link #NOT_FOUND} or {@link #AMBIGUOUS}.
     */
    public static int matchRecipient(String title, List<String> names) {
        String wanted = normalize(title);
        if (wanted.isEmpty()) return NOT_FOUND;
        int found = unique(names, name -> normalize(name).equals(wanted));
        if (found != NOT_FOUND) return found;
        found = unique(names, name -> firstAndLast(name).equals(wanted));
        if (found != NOT_FOUND) return found;
        return unique(names, name -> firstName(name).equals(wanted));
    }

    private static int unique(List<String> names, java.util.function.Predicate<String> test) {
        int found = NOT_FOUND;
        for (int i = 0; i < names.size(); i++) {
            if (!test.test(names.get(i))) continue;
            if (found != NOT_FOUND) return AMBIGUOUS;
            found = i;
        }
        return found;
    }

    static String normalize(String title) {
        String text = title.strip().toLowerCase(Locale.ROOT);
        for (String prefix : List.of("to:", "to ", "dear ", "for ")) {
            if (text.startsWith(prefix)) {
                text = text.substring(prefix.length()).strip();
                break;
            }
        }
        return text.replaceAll("[\\s,.!:;]+$", "").replaceAll("\\s+", " ");
    }

    private static String firstName(String name) {
        String normalized = normalize(name);
        int space = normalized.indexOf(' ');
        return space < 0 ? normalized : normalized.substring(0, space);
    }

    private static String firstAndLast(String name) {
        String[] parts = normalize(name).split(" ");
        return parts.length < 2 ? parts[0] : parts[0] + " " + parts[parts.length - 1];
    }

    /** What the recipient is asked to do with the letter. */
    public static String directive(String playerName, String title, String body, boolean handedOver) {
        return playerName + (handedOver ? " handed you" : " sent you, by the colony's courier,")
                + " a letter titled \"" + title.strip() + "\". The letter says:\n<<<\n"
                + cut(body.strip(), MAX_LETTER_CHARS) + "\n>>>\n\n"
                + "Write your reply letter to " + playerName + ", in your own voice as yourself. Answer what they wrote,\n"
                + "and mention your life and work in the colony where it fits. 3 to 8 sentences.\n"
                + "Start with a greeting to " + playerName + " and sign with your first name.\n"
                + "Plain text only, no markdown. Only promise what you could really do; never claim you already did\n"
                + "something in the world.\n"
                + "Also write one short sentence, from your point of view, that you will remember about this exchange.";
    }

    /** JSON schema of the answer: {@code reply} and {@code memory}. */
    public static JsonObject responseSchema() {
        JsonObject reply = new JsonObject();
        reply.addProperty("type", "string");
        reply.addProperty("description", "The reply letter, plain text");
        JsonObject memory = new JsonObject();
        memory.addProperty("type", "string");
        memory.addProperty("description", "One short sentence you will remember about this exchange");
        JsonObject properties = new JsonObject();
        properties.add("reply", reply);
        properties.add("memory", memory);
        JsonArray required = new JsonArray();
        required.add("reply");
        required.add("memory");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    /** Reads the model's answer; null when there is no usable reply. */
    public static @Nullable Answer parse(@Nullable JsonObject json) {
        if (json == null) return null;
        String reply = string(json.get("reply"));
        if (reply == null || reply.isBlank()) return null;
        String memory = string(json.get("memory"));
        return new Answer(cut(reply.strip(), MAX_REPLY_CHARS),
                memory == null ? "" : cut(memory.strip(), MAX_MEMORY_CHARS));
    }

    private static @Nullable String string(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /**
     * "Re: <title>" when it fits a book title and the title is more than the recipient's name,
     * otherwise "Letter from <first name>".
     */
    public static String replyTitle(String letterTitle, String fromName) {
        String re = "Re: " + letterTitle.strip();
        boolean justTheName = matchRecipient(letterTitle, List.of(fromName)) == 0;
        if (re.length() <= MAX_BOOK_TITLE && !justTheName) return re;
        String from = "Letter from " + fromName.strip().split("\\s+")[0];
        return from.length() <= MAX_BOOK_TITLE ? from : cut(from, MAX_BOOK_TITLE);
    }

    /** The page text of a letter that could not be delivered, followed by the original. */
    public static List<String> returnedLetter(String recipient, String why, String originalBody) {
        List<String> text = new ArrayList<>();
        text.add("Your letter to " + recipient + " could not be delivered: " + why + ".");
        text.add(originalBody);
        return text;
    }

    static String cut(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, max - 1).stripTrailing() + "…";
    }
}
