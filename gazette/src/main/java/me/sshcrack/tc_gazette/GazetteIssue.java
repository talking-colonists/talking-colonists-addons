package me.sshcrack.tc_gazette;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One published issue of a colony's gazette. Plain data, so it can be saved as JSON and turned into
 * a written book on either loader.
 *
 * @param number     issue number, counting from 1 per colony
 * @param day        in-game day the issue was published on
 * @param colonyName colony name at publishing time
 * @param author     name of the writer shown on the book
 * @param authorRole short role such as "teacher", or empty for the colony's own voice
 * @param headline   front page headline
 * @param articles   articles, most important first
 */
public record GazetteIssue(int number, long day, String colonyName, String author, String authorRole,
                           String headline, List<Article> articles) {
    public static final int MAX_ARTICLES = 5;
    public static final int MAX_HEADLINE_CHARS = 80;
    public static final int MAX_TITLE_CHARS = 48;
    public static final int MAX_BODY_CHARS = 700;

    public GazetteIssue {
        articles = List.copyOf(articles);
    }

    public record Article(String title, String body) {
    }

    /** Title shown on the book item; Minecraft limits book titles to 32 characters. */
    public String bookTitle() {
        String title = colonyName + " Gazette #" + number;
        if (title.length() <= 32) return title;
        return "Gazette #" + number;
    }

    /** JSON schema for the structured text request. */
    public static JsonObject responseSchema() {
        JsonObject string = new JsonObject();
        string.addProperty("type", "string");

        JsonObject article = new JsonObject();
        article.addProperty("type", "object");
        JsonObject articleProps = new JsonObject();
        articleProps.add("title", string.deepCopy());
        articleProps.add("body", string.deepCopy());
        article.add("properties", articleProps);
        JsonArray articleRequired = new JsonArray();
        articleRequired.add("title");
        articleRequired.add("body");
        article.add("required", articleRequired);

        JsonObject articles = new JsonObject();
        articles.addProperty("type", "array");
        articles.add("items", article);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("headline", string.deepCopy());
        props.add("articles", articles);
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("headline");
        required.add("articles");
        schema.add("required", required);
        return schema;
    }

    /**
     * What the writer is asked to write. {@code events} are the event descriptions since the last
     * issue, oldest first.
     */
    public static String directive(String colonyName, int number, @Nullable String authorRole, List<String> events) {
        StringBuilder out = new StringBuilder();
        out.append("Write issue No. ").append(number).append(" of \"The ").append(colonyName)
                .append(" Gazette\", the colony's newspaper.");
        if (authorRole != null && !authorRole.isBlank()) {
            out.append(" You write it as the colony's ").append(authorRole).append(", in your own voice.");
        }
        out.append("\n\nThese are the events since the last issue, oldest first. They are the only news you know:\n");
        for (String event : events) {
            out.append("- ").append(event.strip()).append('\n');
        }
        out.append("""

                Rules:
                - Report only these events and what you know about the colony. Never invent deaths, attacks, names or numbers.
                - Group related events into one article.
                - Give the front page a short, catchy headline about the biggest story (not the paper's name or issue number).
                - Write 1 to 4 articles, most important first. Each has a short title (at most 40 characters)
                  and a body of 2 to 4 sentences.
                - Plain text only: no markdown, no emoji.""");
        return out.toString();
    }

    /**
     * Reads the model's structured answer. Returns null when it has no usable headline or article.
     * Over-long fields are cut to fit a book.
     */
    public static @Nullable GazetteIssue fromJson(JsonObject json, int number, long day, String colonyName,
                                                  String author, String authorRole) {
        String headline = string(json, "headline");
        if (headline == null) return null;
        List<Article> articles = new ArrayList<>();
        if (json.get("articles") instanceof JsonArray array) {
            for (JsonElement element : array) {
                if (articles.size() >= MAX_ARTICLES) break;
                if (!(element instanceof JsonObject object)) continue;
                String title = string(object, "title");
                String body = string(object, "body");
                if (title == null || body == null) continue;
                articles.add(new Article(cut(title, MAX_TITLE_CHARS), cut(body, MAX_BODY_CHARS)));
            }
        }
        if (articles.isEmpty()) return null;
        return new GazetteIssue(number, day, colonyName, author, authorRole, cut(headline, MAX_HEADLINE_CHARS), articles);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("number", number);
        json.addProperty("day", day);
        json.addProperty("colonyName", colonyName);
        json.addProperty("author", author);
        json.addProperty("authorRole", authorRole);
        json.addProperty("headline", headline);
        JsonArray array = new JsonArray();
        for (Article article : articles) {
            JsonObject object = new JsonObject();
            object.addProperty("title", article.title());
            object.addProperty("body", article.body());
            array.add(object);
        }
        json.add("articles", array);
        return json;
    }

    /** Reads an issue saved by {@link #toJson()}; null if the data is damaged. */
    public static @Nullable GazetteIssue load(JsonObject json) {
        try {
            GazetteIssue parsed = fromJson(json, json.get("number").getAsInt(), json.get("day").getAsLong(),
                    json.get("colonyName").getAsString(), json.get("author").getAsString(),
                    json.get("authorRole").getAsString());
            return parsed;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) return null;
        String value = element.getAsString().strip();
        return value.isEmpty() ? null : value;
    }

    static String cut(String text, int max) {
        if (text.length() <= max) return text;
        String cut = text.substring(0, max - 1);
        int space = cut.lastIndexOf(' ');
        if (space > max / 2) cut = cut.substring(0, space);
        return cut.stripTrailing() + "…";
    }
}
