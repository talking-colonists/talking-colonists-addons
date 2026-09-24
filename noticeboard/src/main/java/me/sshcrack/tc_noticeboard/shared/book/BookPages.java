// GENERATED from shared/: edit it there, then run ./gradlew syncShared
package me.sshcrack.tc_noticeboard.shared.book;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into book pages. A written book page shows about 14 lines of 19 average characters;
 * the budgets here stay below that so wrapped words never push text off the page.
 */
public final class BookPages {
    /** Characters of body text that safely fit on one page. */
    public static final int PAGE_CHARS = 200;
    /** Minecraft keeps at most 100 pages in a written book. */
    public static final int MAX_PAGES = 100;

    private BookPages() {
    }

    /**
     * Splits {@code text} at word boundaries. The first page holds at most {@code firstPageChars}
     * (it may share its page with a heading), later pages {@link #PAGE_CHARS}. Words longer than a
     * page are cut. Returns at least one page.
     */
    public static List<String> paginate(String text, int firstPageChars) {
        List<String> pages = new ArrayList<>();
        String rest = text.strip();
        int budget = Math.max(1, firstPageChars);
        while (!rest.isEmpty()) {
            if (rest.length() <= budget) {
                pages.add(rest);
                break;
            }
            int split = rest.lastIndexOf(' ', budget);
            int newline = rest.lastIndexOf('\n', budget);
            split = Math.max(split, newline);
            if (split <= 0) split = budget;
            pages.add(rest.substring(0, split).strip());
            rest = rest.substring(split).strip();
            budget = PAGE_CHARS;
        }
        if (pages.isEmpty()) pages.add("");
        return pages;
    }

    /** Characters a heading takes from its page: its wrapped lines plus one blank line. */
    public static int headingCost(String heading) {
        int lines = Math.max(1, (heading.length() + 18) / 19);
        return (lines + 1) * 19;
    }
}
