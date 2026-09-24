// GENERATED from shared/: edit it there, then run ./gradlew syncShared
package me.sshcrack.tc_noticeboard.shared.book;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookPagesTest {
    @Test
    void shortTextIsOnePage() {
        assertEquals(List.of("Hello colony."), BookPages.paginate("  Hello colony. ", 100));
    }

    @Test
    void emptyTextStillHasAPage() {
        assertEquals(List.of(""), BookPages.paginate("   ", 100));
    }

    @Test
    void splitsAtWordsAndKeepsEveryWord() {
        String text = "word ".repeat(120).strip();
        List<String> pages = BookPages.paginate(text, 50);
        assertTrue(pages.get(0).length() <= 50);
        for (String page : pages.subList(1, pages.size())) {
            assertTrue(page.length() <= BookPages.PAGE_CHARS, page);
        }
        for (String page : pages) {
            assertTrue(!page.startsWith(" ") && !page.endsWith(" ") && !page.contains("wor d"), page);
        }
        assertEquals(text, String.join(" ", pages));
    }

    @Test
    void cutsWordsLongerThanAPage() {
        String text = "x".repeat(450);
        List<String> pages = BookPages.paginate(text, BookPages.PAGE_CHARS);
        assertEquals(3, pages.size());
        assertEquals(text, String.join("", pages));
    }

    @Test
    void headingCostCountsWrappedLines() {
        assertEquals(38, BookPages.headingCost("Short"));
        assertEquals(57, BookPages.headingCost("A heading that wraps once"));
    }
}
