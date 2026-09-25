package me.sshcrack.tc_townhall;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Pure text for the mayor's office: promises, reports, proposals, and what citizens know about them. */
public final class OfficeText {
    static final int MAX_SUMMARY_CHARS = 60;
    static final int MAX_AGENDA_CHARS = 2000;

    private OfficeText() {
    }

    // ── Promises ────────────────────────────────────────────────────────────

    /** What a citizen is asked to read the new mayor's promises out of their platform. */
    public static String promiseDirective(String mayor, String platform) {
        return mayor + " was just elected mayor of your colony. This was their campaign: \"" + platform.strip()
                + "\"\nWhich of the colony's problems did they promise to tackle? Pick at most " + Office.MAX_PROMISES
                + " of: " + needIds() + ". Only what they actually promised; none if they promised nothing concrete. "
                + "Summarise each promise in at most 8 words, as people in the colony would repeat it.";
    }

    public static JsonObject promiseSchema() {
        JsonObject need = string("The problem: one of " + needIds());
        JsonArray options = new JsonArray();
        for (Need value : Need.values()) options.add(value.id());
        need.add("enum", options);
        JsonObject promiseProperties = new JsonObject();
        promiseProperties.add("need", need);
        promiseProperties.add("summary", string("The promise, at most 8 words"));
        JsonObject promise = object(promiseProperties, "need", "summary");
        JsonObject list = new JsonObject();
        list.addProperty("type", "array");
        list.add("items", promise);
        JsonObject properties = new JsonObject();
        properties.add("promises", list);
        return object(properties, "promises");
    }

    /** The promises in the answer, at most one per need; empty when there are none or it is unusable. */
    public static List<Office.Promise> parsePromises(@Nullable JsonObject json) {
        List<Office.Promise> promises = new ArrayList<>();
        if (json == null || !(json.get("promises") instanceof JsonArray array)) return promises;
        for (JsonElement element : array) {
            if (!(element instanceof JsonObject object) || promises.size() >= Office.MAX_PROMISES) continue;
            Need need = Need.byId(text(object.get("need")));
            String summary = text(object.get("summary"));
            if (need == null || promises.stream().anyMatch(promise -> promise.need.equals(need.id()))) continue;
            Office.Promise promise = new Office.Promise();
            promise.need = need.id();
            promise.summary = ElectionText.cut(summary == null || summary.isBlank() ? "better " + need.label() : summary.strip(),
                    MAX_SUMMARY_CHARS);
            promises.add(promise);
        }
        return promises;
    }

    /** "\"a roof over every head\": kept (3 citizens were homeless or in a home below level 3 when the term began, none now)". */
    public static String promiseLine(Office.Promise promise, int now) {
        Need need = Need.byId(promise.need);
        String head = "\"" + promise.summary + "\"";
        if (need == null) return head;
        int before = promise.before;
        return head + switch (Office.progress(before, now)) {
            case KEPT -> before == 0 ? ": kept so far (no citizen is " + need.affected() + ")"
                    : ": kept (" + was(before, need) + " when the term began, none now)";
            case PARTLY -> ": partly kept (" + was(before, need) + " when the term began, " + now + " now)";
            case NOT_YET -> ": not kept yet (still " + (now == 1 ? "one citizen" : now + " citizens") + " " + need.affected() + ")";
            case WORSE -> ": worse than before (" + was(before, need) + " when the term began, " + now + " now)";
        };
    }

    private static String was(int count, Need need) {
        return (count == 1 ? "one citizen was " : count + " citizens were ") + need.affected();
    }

    // ── Reports ─────────────────────────────────────────────────────────────

    /** "3 citizens are homeless or in a home below level 3, since day 4 (reported twice already)". */
    public static String needLine(Need need, int count, int sinceDay, int reported) {
        String line = need.describe(count) + ", since day " + sinceDay;
        if (reported <= 0) return line;
        return line + " (reported " + times(reported) + " already)";
    }

    /** The written report the mayor hands over. */
    public static List<String> reportPages(String colony, String mayor, int day, List<String> needLines,
                                           List<String> promiseLines, @Nullable Office.Proposal proposal) {
        List<String> pages = new ArrayList<>();
        StringBuilder first = new StringBuilder("Mayor's report on ").append(colony).append(", day ").append(day).append("\n\n");
        if (needLines.isEmpty()) first.append("No citizen lacks a home, work, food, health care, safety or supplies.");
        for (String line : needLines) first.append("- ").append(capitalize(line)).append(".\n");
        pages.add(first.toString().strip());
        if (!promiseLines.isEmpty()) {
            StringBuilder promises = new StringBuilder("What I promised\n\n");
            for (String line : promiseLines) promises.append("- ").append(line).append(".\n");
            pages.add(promises.toString().strip());
        }
        if (proposal != null) {
            pages.add("My proposal\n\nI propose we " + proposal.what() + ". " + alternatives(proposal)
                    + "\n\nTell me yes or no, or answer at the Ballot Box.\n\n" + mayor);
        } else {
            pages.set(pages.size() - 1, pages.get(pages.size() - 1) + "\n\n" + mayor);
        }
        return pages;
    }

    /**
     * The mayor's instructions for the conversation after handing over the report. {@code tool} is the
     * provider name of the answer tool.
     */
    public static String agenda(String player, List<String> needLines, List<String> promiseLines,
                                @Nullable Office.Proposal proposal, String tool) {
        StringBuilder text = new StringBuilder("As the colony's mayor you just walked up to ").append(player)
                .append(" and handed them your written report. You speak for the whole colony here, not only for yourself.\n");
        if (needLines.isEmpty()) text.append("The report: nobody lacks anything right now; say so gladly.\n");
        else {
            text.append("The report:\n");
            for (String line : needLines) text.append("- ").append(line).append('\n');
            text.append("Where you reported a problem before and it is still there, say so plainly, and let the frustration show "
                    + "the more often you had to report it.\n");
        }
        if (!promiseLines.isEmpty()) {
            text.append("Your own promises, so you can be honest about them:\n");
            for (String line : promiseLines) text.append("- ").append(line).append('\n');
        }
        if (proposal != null) {
            text.append("Then propose to ").append(player).append(" that you ").append(proposal.what())
                    .append(", and why it would help. ").append(alternatives(proposal))
                    .append(" Ask for a clear yes or no. When they clearly agree or refuse, call ")
                    .append(tool).append(" with their answer; if they are unsure, leave it, they can answer at the Ballot Box later.\n");
        }
        text.append("Keep it short, like a word in passing: 2 or 3 sentences, then listen.");
        return ElectionText.cut(text.toString(), MAX_AGENDA_CHARS);
    }

    /** The game event that starts the mayor speaking. */
    public static String opening(String player) {
        return "You just handed " + player + " your report as mayor. Greet them briefly and tell them the most important point.";
    }

    /** What the whole colony hears at once after a report: the mayor's word travels fast. */
    public static String statement(String mayor, String player, @Nullable String topNeed, @Nullable Office.Proposal proposal) {
        String text = "Mayor " + mayor + " gave " + player + " the mayor's report"
                + (topNeed == null ? ": nobody in the colony lacks anything right now." : ". The most pressing problem: " + topNeed + ".");
        if (proposal != null) text += " The mayor asked " + player + " to " + proposal.what() + ".";
        return ElectionText.cut(text, ElectionText.MAX_BROADCAST_CHARS);
    }

    /** What the colony hears when a player answers the mayor's proposal. */
    public static String answered(String mayor, Office.Proposal proposal) {
        boolean yes = proposal.status != Office.ProposalStatus.REFUSED;
        String text = proposal.player + (yes ? " agreed to" : " turned down") + " Mayor " + mayor + "'s proposal to "
                + proposal.what() + (proposal.reason.isBlank() ? "." : ": \"" + proposal.reason + "\"");
        return ElectionText.cut(text, ElectionText.MAX_BROADCAST_CHARS);
    }

    /** What the colony hears when an accepted proposal is done or was not kept. */
    public static String outcome(String mayor, Office.Proposal proposal) {
        String text = switch (proposal.status) {
            case DONE -> (proposal.builder.isBlank()
                    ? "Work on the " + Office.buildingName(proposal.building) + " has come along"
                    : proposal.builder + " the builder is now at work on the " + Office.buildingName(proposal.building))
                    + ", as " + proposal.player + " agreed with Mayor " + mayor + ".";
            case BROKEN -> proposal.player + " agreed with Mayor " + mayor + " on day " + proposal.answeredDay + " to "
                    + proposal.what() + ", but no building work for it was ever ordered.";
            case FAILED -> proposal.player + " agreed with Mayor " + mayor + " to " + proposal.what()
                    + ", but no builder has been free to take the work on.";
            case IGNORED -> proposal.player.isEmpty() ? "Nobody answered Mayor " + mayor + "'s proposal to " + proposal.what() + "."
                    : proposal.player + " never answered Mayor " + mayor + "'s proposal to " + proposal.what() + ".";
            default -> "";
        };
        return ElectionText.cut(text, ElectionText.MAX_BROADCAST_CHARS);
    }

    /**
     * "Any building work for safety counts: a new guard tower or barracks, or an upgraded barracks or
     * barracks tower. It is kept once a builder starts on it." Building takes days; the mayor only asks
     * that the work begins.
     */
    public static String alternatives(Office.Proposal proposal) {
        Need need = Need.byId(proposal.need);
        if (need == null || need.buildings().isEmpty()) return "";
        String built = names(need.buildings());
        String upgraded = names(need.upgrades());
        String kinds = built.equals(upgraded) ? "a new or upgraded " + built
                : upgraded.isEmpty() ? "a new " + built : "a new " + built + ", or an upgraded " + upgraded;
        return "Any building work for " + need.label() + " counts: " + kinds + ". It is kept once a builder starts on it.";
    }

    /** "guard tower or barracks", "restaurant, kitchen or farm". */
    private static String names(List<String> types) {
        List<String> names = new ArrayList<>();
        for (String type : types) names.add(Office.buildingName(type));
        if (names.size() <= 1) return String.join("", names);
        return String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.get(names.size() - 1);
    }

    // ── Track record ────────────────────────────────────────────────────────

    /** "upgrade the residence to level 2 (Steve agreed on day 5; done)". */
    public static String proposalLine(Office.Proposal proposal) {
        String status = switch (proposal.status) {
            case PENDING -> "waiting for an answer since day " + proposal.madeDay;
            case ACCEPTED -> proposal.placedDay >= 0
                    ? proposal.player + " agreed and placed the hut on day " + proposal.placedDay + "; waiting for a builder to start"
                    : proposal.player + " agreed on day " + proposal.answeredDay + "; no builder has started yet";
            case REFUSED -> proposal.player + " turned it down on day " + proposal.answeredDay;
            case IGNORED -> "never answered";
            case DONE -> proposal.player + " agreed; " + (proposal.builder.isBlank() ? "the work came along"
                    : proposal.builder + " the builder took it on");
            case BROKEN -> proposal.player + " agreed on day " + proposal.answeredDay + " but never did it";
            case FAILED -> proposal.player + " agreed, but no builder could take it on";
        };
        return proposal.what() + " (" + status + ")";
    }

    /** What citizens (and voters) know of a mayor's term. */
    public static String record(String mayor, List<String> promiseLines, List<Office.Proposal> proposals) {
        StringBuilder text = new StringBuilder();
        if (!promiseLines.isEmpty()) {
            text.append(mayor).append("'s promises: ").append(String.join("; ", promiseLines)).append(". ");
        }
        if (!proposals.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (Office.Proposal proposal : proposals) lines.add(proposalLine(proposal));
            text.append("The mayor's proposals to the players: ").append(String.join("; ", lines)).append('.');
        }
        return text.toString().strip();
    }

    /** How a player candidate answered a citizen mayor's proposals, for voters; null when they never did. */
    public static @Nullable String playerRecord(String player, List<Office.Proposal> answered) {
        if (answered.isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        for (Office.Proposal proposal : answered) lines.add(proposalLine(proposal));
        return "When the mayor asked " + player + ": " + String.join("; ", lines) + ".";
    }

    /** Every citizen but the mayor: the mayor's word carries weight. */
    public static String listenToMayor(String mayor) {
        return "People here listen to the mayor, " + mayor + ": what the mayor says carries weight with you, even when you "
                + "don't fully agree, and you often repeat it.";
    }

    /** The player in the conversation is the mayor. */
    public static String talkingToMayor(String player) {
        return "You are talking with " + player + ", the colony's elected mayor. Give their words weight, and feel free "
                + "to hold them to their promises.";
    }

    /** A citizen who is the mayor: their duties, the colony's problems, and their open proposal. */
    public static String mayorDuties(List<String> needLines, @Nullable Office.Proposal pending, String tool) {
        StringBuilder text = new StringBuilder("You speak for the whole colony, not only for yourself.");
        if (!needLines.isEmpty()) text.append(" The colony's problems as you know them: ").append(String.join("; ", needLines)).append('.');
        if (pending != null) {
            text.append(" You proposed to the players that you ").append(pending.what())
                    .append(" and wait for an answer. If the player clearly agrees or refuses, call ").append(tool).append('.');
        }
        return text.toString();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    static String times(int count) {
        return switch (count) {
            case 1 -> "once";
            case 2 -> "twice";
            default -> count + " times";
        };
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String needIds() {
        List<String> ids = new ArrayList<>();
        for (Need need : Need.values()) ids.add(need.id());
        return String.join(", ", ids);
    }

    private static @Nullable String text(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static JsonObject string(String description) {
        JsonObject object = new JsonObject();
        object.addProperty("type", "string");
        object.addProperty("description", description);
        return object;
    }

    private static JsonObject object(JsonObject properties, String... required) {
        JsonObject object = new JsonObject();
        object.addProperty("type", "object");
        object.add("properties", properties);
        JsonArray names = new JsonArray();
        for (String name : required) names.add(name);
        object.add("required", names);
        return object;
    }
}
