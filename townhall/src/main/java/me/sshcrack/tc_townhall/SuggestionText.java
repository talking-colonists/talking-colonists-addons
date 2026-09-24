package me.sshcrack.tc_townhall;

/** Pure text for the suggestion box. */
public final class SuggestionText {
    static final int MAX_NOTE_CHARS = 170;

    private SuggestionText() {
    }

    /** What a citizen is asked when writing a note for the box. */
    public static String directive() {
        return "The colony keeps a suggestion box at the town hall. Write one short note to drop in it, in your own "
                + "voice: one real concern or wish about your own life or the colony, based only on facts you actually "
                + "know right now. Do not blame anyone for things that could not have been done yet. If nothing is "
                + "really wrong, write a small wish or a word of thanks instead. 1 or 2 sentences, at most 150 "
                + "characters. Plain text only, no quotes, no greeting, no name or signature.";
    }

    /** Cleans a model note: no surrounding quotes, one line, at most {@link #MAX_NOTE_CHARS}. */
    public static String clean(String note) {
        String text = note.strip().replaceAll("\\s+", " ");
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("“") && text.endsWith("”"))) {
            text = text.substring(1, text.length() - 1).strip();
        }
        return ElectionText.cut(text, MAX_NOTE_CHARS);
    }
}
