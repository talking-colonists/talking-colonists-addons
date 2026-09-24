package me.sshcrack.tc_townhall;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Pure text for elections: what citizens hear, what voters and rivals are asked, and the results. */
public final class ElectionText {
    /** Longest broadcast Talking Colonists accepts. */
    static final int MAX_BROADCAST_CHARS = 500;
    static final int MAX_PLATFORM_CHARS = 600;
    static final int MAX_REASON_CHARS = 170;
    static final int MAX_SLOGAN_CHARS = 60;
    /** What a voter answers when no candidate deserves their vote. */
    static final String ABSTAIN = "abstain";
    /** How citizens refer to where the news comes from. */
    public static final String SOURCE_NAME = "the town hall";

    /** A candidate as one voter knows them. {@code heardPlatform} is null when the voter never heard it. */
    public record CandidateBrief(String name, @Nullable String heardPlatform, @Nullable String feelings) {
    }

    /** A citizen's campaign, as they wrote it. */
    public record Rival(String slogan, String platform) {
    }

    /** One relationship dimension towards a candidate, e.g. ("trust", 0.7). Neutral is zero. */
    public record Feeling(String dimension, float factor) {
    }

    /** A quoted voter in the results book. */
    public record Quote(String voter, String reason) {
    }

    /** One vote: the index of the chosen candidate, or -1 for an abstention, and the voter's reason. */
    public record Vote(int candidate, String reason) {
    }

    private ElectionText() {
    }

    /** What citizens at the town hall hear when someone stands for mayor, at most 500 characters. */
    public static String candidacy(String name, String slogan, String platform) {
        String text = platform.strip().replaceAll("\\s+", " ");
        String head = name + " is standing for mayor at the town hall, with the slogan \"" + slogan.strip() + "\"";
        return cut(text.isEmpty() ? head + "." : head + ". Their platform: " + text, MAX_BROADCAST_CHARS);
    }

    /** What citizens at the town hall hear of a campaign speech, at most 500 characters. */
    public static String speech(String name, String transcript) {
        return cut(name + " gave a campaign speech at the town hall: \"" + transcript.strip().replaceAll("\\s+", " ")
                + "\"", MAX_BROADCAST_CHARS);
    }

    /** The book's title as a slogan: the title, or "A better colony" when it is blank. */
    public static String slogan(String title) {
        String slogan = title.strip();
        return cut(slogan.isEmpty() ? "A better colony" : slogan, MAX_SLOGAN_CHARS);
    }

    /** What a citizen is asked when they decide to stand against the players. */
    public static String rivalDirective(String colonyName, List<String> opponents) {
        return "The colony " + colonyName + " is electing a mayor. " + join(opponents)
                + (opponents.size() == 1 ? " is" : " are") + " standing, and you have decided to stand against "
                + (opponents.size() == 1 ? "them" : "all of them") + ". Write your campaign in your own voice: "
                + "a short slogan, and a platform of 2 or 3 sentences, at most 300 characters, about what you would "
                + "change for the colony, grounded in the real problems and wishes you know about. Plain text only.";
    }

    public static JsonObject rivalSchema() {
        JsonObject properties = new JsonObject();
        properties.add("slogan", string("Your campaign slogan, at most 8 words"));
        properties.add("platform", string("Your platform, 2 or 3 sentences"));
        return object(properties, "slogan", "platform");
    }

    /** The rival's slogan and platform; null when the answer is unusable. */
    public static @Nullable Rival parseRival(@Nullable JsonObject json) {
        if (json == null) return null;
        String slogan = text(json.get("slogan"));
        String platform = text(json.get("platform"));
        if (platform == null || platform.isBlank()) return null;
        return new Rival(slogan(slogan == null ? "" : slogan), cut(platform.strip(), MAX_PLATFORM_CHARS));
    }

    /** What a citizen is asked when casting their vote. */
    public static String voteDirective(List<CandidateBrief> candidates) {
        StringBuilder text = new StringBuilder("Today the colony elects its mayor, and you cast your vote. The candidates:\n");
        for (CandidateBrief candidate : candidates) {
            text.append("- ").append(candidate.name()).append(": ");
            text.append(candidate.heardPlatform() == null
                    ? "you never heard what they stand for."
                    : "you heard this: " + cut(candidate.heardPlatform().strip(), MAX_BROADCAST_CHARS));
            if (candidate.feelings() != null) text.append(" About them: ").append(candidate.feelings());
            text.append('\n');
        }
        text.append("\nVote for the candidate you honestly prefer, as yourself: think of your own needs and worries, ")
                .append("what you heard them promise, and how you feel about them. You may abstain if none of them ")
                .append("deserves your vote. Give your reason in one sentence of at most 150 characters, in your own ")
                .append("voice, as you would tell a neighbour.");
        return text.toString();
    }

    public static JsonObject voteSchema(List<String> names) {
        JsonObject vote = string("The name of the candidate you vote for, or \"" + ABSTAIN + "\"");
        JsonArray options = new JsonArray();
        names.forEach(options::add);
        options.add(ABSTAIN);
        vote.add("enum", options);
        JsonObject properties = new JsonObject();
        properties.add("vote", vote);
        properties.add("reason", string("Why, in one sentence"));
        return object(properties, "vote", "reason");
    }

    /** The vote in the answer; null when it names nobody on the ballot. */
    public static @Nullable Vote parseVote(@Nullable JsonObject json, List<String> names) {
        if (json == null) return null;
        String vote = text(json.get("vote"));
        if (vote == null) return null;
        String reason = text(json.get("reason"));
        reason = reason == null ? "" : cut(reason.strip().replaceAll("\\s+", " "), MAX_REASON_CHARS);
        if (vote.strip().equalsIgnoreCase(ABSTAIN)) return new Vote(-1, reason);
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(vote.strip())) return new Vote(i, reason);
        }
        return null;
    }

    /** Votes per candidate. */
    public static int[] tally(List<Vote> votes, int candidates) {
        int[] counts = new int[candidates];
        for (Vote vote : votes) {
            if (vote.candidate() >= 0 && vote.candidate() < candidates) counts[vote.candidate()]++;
        }
        return counts;
    }

    /** The candidates with the most votes (more than one on a tie); empty when nobody got a vote. */
    public static List<Integer> leaders(int[] counts) {
        int best = 0;
        for (int count : counts) best = Math.max(best, count);
        List<Integer> leaders = new ArrayList<>();
        if (best == 0) return leaders;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == best) leaders.add(i);
        }
        return leaders;
    }

    /** "Dev was elected mayor with 7 of 11 votes (Remy 3, 1 abstained)." */
    public static String result(List<String> names, int[] counts, int winner, int voters, boolean coinToss) {
        StringBuilder text = new StringBuilder(names.get(winner)).append(" was elected mayor with ")
                .append(counts[winner]).append(" of ").append(voters).append(voters == 1 ? " vote" : " votes");
        List<String> others = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            if (i != winner) others.add(names.get(i) + " " + counts[i]);
        }
        int abstained = voters;
        for (int count : counts) abstained -= count;
        if (abstained > 0) others.add(abstained + " abstained");
        if (!others.isEmpty()) text.append(" (").append(String.join(", ", others)).append(")");
        if (coinToss) text.append(", after a tie settled by the town clerk's coin toss");
        return text.append('.').toString();
    }

    /** "Nobody was elected: all 4 voters abstained." */
    public static String noWinner(int voters) {
        if (voters == 0) return "Nobody was elected: no citizen came to vote.";
        return "Nobody was elected: " + (voters == 1 ? "the only voter" : "all " + voters + " voters") + " abstained.";
    }

    /** The results book: the tally, then a page per quoted voter. */
    public static List<String> resultPages(String colonyName, int day, String result, List<Quote> quotes) {
        List<String> pages = new ArrayList<>();
        pages.add("Mayoral election of " + colonyName + ", day " + day + "\n\n" + result);
        for (Quote quote : quotes) {
            pages.add(quote.voter() + ":\n\n\"" + quote.reason() + "\"");
        }
        return pages;
    }

    /** How a voter feels about a candidate, from their relationship memory; null when neutral. */
    public static @Nullable String feelings(List<Feeling> feelings) {
        List<String> parts = new ArrayList<>();
        for (Feeling feeling : feelings) {
            float strength = Math.abs(feeling.factor());
            if (strength < 0.2f) continue;
            if (feeling.factor() > 0) parts.add((strength >= 0.6f ? "strong " : "some ") + feeling.dimension());
            else parts.add((strength >= 0.6f ? "very little " : "little ") + feeling.dimension());
            if (parts.size() == 3) break;
        }
        return parts.isEmpty() ? null : "you feel " + String.join(", ", parts) + " towards them.";
    }

    /** What every citizen knows while a campaign runs. */
    public static String campaignObservation(List<String> names, List<String> slogans, boolean voting) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            parts.add(names.get(i) + " (\"" + slogans.get(i) + "\")");
        }
        return "The colony is electing a mayor. Standing: " + join(parts) + ". "
                + (voting ? "Voting is under way today." : "Voting begins soon; people talk about who they will vote for.");
    }

    /** What every citizen knows about the elected mayor. */
    public static String mayorObservation(String mayor, int sinceDay, String result) {
        return mayor + " is the colony's elected mayor since day " + sinceDay + ". " + result;
    }

    /** Told to a citizen who is the mayor. */
    public static String mayorInstruction(int sinceDay) {
        return "You are the colony's elected mayor since day " + sinceDay + ". You take the office seriously and "
                + "talk about the colony's future and your plans like a mayor would, without boasting.";
    }

    static String join(List<String> names) {
        if (names.size() <= 1) return names.isEmpty() ? "" : names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    static String cut(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, max - 1).stripTrailing() + "…";
    }

    private static @Nullable String text(@Nullable JsonElement element) {
        return element == null || !element.isJsonPrimitive() ? null : element.getAsString();
    }

    private static JsonObject string(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        return property;
    }

    private static JsonObject object(JsonObject properties, String... required) {
        JsonArray names = new JsonArray();
        for (String name : required) names.add(name);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", names);
        return schema;
    }
}
