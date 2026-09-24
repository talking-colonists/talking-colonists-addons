package me.sshcrack.tc_noticeboard;

/** Pure text for notices: what citizens remember, what repliers are asked, and a pinned reply page. */
public final class NoticeText {
    /** Longest broadcast Talking Colonists accepts. */
    static final int MAX_BROADCAST_CHARS = 500;
    static final int MAX_NOTICE_CHARS = 1_500;
    static final int MAX_REPLY_CHARS = 170;
    /** How citizens refer to the board. */
    public static final String SOURCE_NAME = "the notice board";

    private NoticeText() {
    }

    /** The broadcast citizens remember: title and the start of the text, at most 500 characters. */
    public static String broadcast(String title, String body) {
        String head = "Notice \"" + title.strip() + "\"";
        String text = body.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) return cut(head, MAX_BROADCAST_CHARS);
        return cut(head + ": " + text, MAX_BROADCAST_CHARS);
    }

    /** What the town bell announces: the book's title and text, at most 500 characters. */
    public static String announcement(String title, String body) {
        String text = body.strip().replaceAll("\\s+", " ");
        return cut(text.isEmpty() ? title.strip() : title.strip() + ": " + text, MAX_BROADCAST_CHARS);
    }

    /** What a citizen is asked when writing a reply to pin under the notice. */
    public static String replyDirective(String poster, String title, String body) {
        return poster + " pinned a notice titled \"" + title.strip() + "\" to the colony notice board. It says:\n<<<\n"
                + cut(body.strip(), MAX_NOTICE_CHARS) + "\n>>>\n\n"
                + "Write a short reply to pin under it, in your own voice as yourself: your honest opinion, a worry, "
                + "a request or a petition. 1 or 2 sentences, at most 150 characters. Plain text only, no quotes, "
                + "no name or signature.";
    }

    /** Cleans a model reply: no surrounding quotes, one line, at most {@link #MAX_REPLY_CHARS}. */
    public static String cleanReply(String reply) {
        String text = reply.strip().replaceAll("\\s+", " ");
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("“") && text.endsWith("”"))) {
            text = text.substring(1, text.length() - 1).strip();
        }
        return cut(text, MAX_REPLY_CHARS);
    }

    /** One pinned reply as a book page. */
    public static String replyPage(String name, String role, String reply) {
        String signature = role == null || role.isBlank() ? name : name + ", " + role;
        return "Reply from " + signature + ":\n\n" + reply;
    }

    /** How far a notice has spread: 0 below half the colony, 1 from half on, 2 once everyone heard it. */
    public static int reachMilestone(int heard, int citizens) {
        if (citizens <= 0) return 0;
        if (heard >= citizens) return 2;
        return heard * 2 >= citizens ? 1 : 0;
    }

    /** "Word of "Harvest fair" has reached 5 of 12 citizens." */
    public static String reach(String title, int heard, int citizens) {
        if (heard >= citizens) return "Word of \"" + title.strip() + "\" has reached every citizen of the colony.";
        return "Word of \"" + title.strip() + "\" has reached " + heard + " of " + citizens + " citizens.";
    }

    static String cut(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, max - 1).stripTrailing() + "…";
    }
}
