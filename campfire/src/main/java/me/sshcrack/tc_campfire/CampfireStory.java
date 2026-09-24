package me.sshcrack.tc_campfire;

import java.util.ArrayList;
import java.util.List;

/** Pure helpers for a campfire night: the storytelling agenda, seat layout and the news line. */
public final class CampfireStory {
    public static final int MIN_TELLERS = 3;
    public static final int MAX_TELLERS = 5;
    /** Seats are this far from the campfire's center, in blocks. */
    public static final double SEAT_RADIUS = 2.5;
    static final int MAX_NEWS = 5;
    static final int MAX_NEWS_CHARS = 160;

    private CampfireStory() {
    }

    /** The shared agenda every teller sees. {@code news} are recent colony events they may bring up. */
    public static String agenda(String colonyName, List<String> names, List<String> news) {
        StringBuilder out = new StringBuilder()
                .append("Campfire night in ").append(colonyName).append(". It is dusk, and ")
                .append(joinNames(names))
                .append(" sit together around the campfire after the day's work. Players may be listening.\n\n")
                .append("""
                        Take turns telling short stories: a memory of your own, something that happened in the colony,
                        or a tale from before you came here, such as your home village, a journey or a legend your family told.
                        Everyone has a story; never apologize for not having one. React to what the others said before you.
                        Speak only as yourself, 2 to 4 sentences per turn, warm and relaxed.
                        Colony events are facts: never invent deaths, attacks or names in the colony. If a player speaks, answer them kindly.""");
        List<String> lines = news.stream().map(String::strip).filter(line -> !line.isEmpty()).limit(MAX_NEWS).toList();
        if (!lines.isEmpty()) {
            out.append("\n\nThings that happened in the colony lately, if you want to bring them up:\n");
            for (String line : lines) {
                out.append("- ").append(cut(line, MAX_NEWS_CHARS)).append('\n');
            }
        }
        return out.toString().strip();
    }

    /** Seat offsets (x, z) from the campfire's center, evenly spaced on a circle. */
    public static List<double[]> seats(int count) {
        List<double[]> seats = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / count;
            seats.add(new double[]{SEAT_RADIUS * Math.cos(angle), SEAT_RADIUS * Math.sin(angle)});
        }
        return seats;
    }

    /** "Anna", "Anna and Ben", "Anna, Ben and Carl". */
    public static String joinNames(List<String> names) {
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    /** The colony event recorded after a night with at least one story, at most 300 characters. */
    public static String newsLine(List<String> tellers, String firstSpeaker, String firstStory) {
        String line = joinNames(tellers) + " told stories around the campfire";
        String story = withoutSpeaker(firstSpeaker, firstStory);
        if (!story.isEmpty() && firstSpeaker != null && !firstSpeaker.isBlank()) {
            line += "; " + firstSpeaker + " began: \"" + cut(story, 140) + "\"";
        }
        return cut(line, 300);
    }

    /** Transcript lines may start with "Name: "; drops that prefix. */
    public static String withoutSpeaker(String speaker, String text) {
        String story = text == null ? "" : text.strip();
        if (speaker != null && !speaker.isBlank() && story.startsWith(speaker + ":")) {
            story = story.substring(speaker.length() + 1).strip();
        }
        return story;
    }

    static String cut(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, max - 1).stripTrailing() + "…";
    }
}
