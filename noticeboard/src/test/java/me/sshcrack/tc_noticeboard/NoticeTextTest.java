package me.sshcrack.tc_noticeboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoticeTextTest {
    @Test
    void broadcastKeepsTitleAndFitsTheLimit() {
        assertEquals("Notice \"Harvest fair\": Bring pumpkins to the town hall on Sunday.",
                NoticeText.broadcast(" Harvest fair ", "Bring pumpkins\nto the town hall   on Sunday."));
        assertEquals("Notice \"Empty\"", NoticeText.broadcast("Empty", "  "));
        String long_ = NoticeText.broadcast("T", "word ".repeat(300));
        assertTrue(long_.length() <= NoticeText.MAX_BROADCAST_CHARS);
        assertTrue(long_.endsWith("…"));
    }

    @Test
    void directiveQuotesTheNotice() {
        String directive = NoticeText.replyDirective("Steve", "New wall", "We build a wall.");
        assertTrue(directive.startsWith("Steve pinned a notice titled \"New wall\""));
        assertTrue(directive.contains("<<<\nWe build a wall.\n>>>"));
        assertTrue(directive.contains("at most 150 characters"));
    }

    @Test
    void cleansReplies() {
        assertEquals("Good idea!", NoticeText.cleanReply("  \"Good idea!\" "));
        assertEquals("Yes please", NoticeText.cleanReply("“Yes please”"));
        assertEquals("one line", NoticeText.cleanReply("one\n  line"));
        assertTrue(NoticeText.cleanReply("x".repeat(400)).length() <= NoticeText.MAX_REPLY_CHARS);
    }

    @Test
    void replyPageSignsWithNameAndRole() {
        assertEquals("Reply from Anna, baker:\n\nYes!", NoticeText.replyPage("Anna", "baker", "Yes!"));
        assertEquals("Reply from Ben:\n\nNo.", NoticeText.replyPage("Ben", "", "No."));
    }

    @Test
    void announcementIsTitleAndText() {
        assertEquals("Fair today: Starts at noon.", NoticeText.announcement(" Fair today ", "Starts\n at noon."));
        assertEquals("Fair today", NoticeText.announcement("Fair today", " "));
        assertTrue(NoticeText.announcement("T", "x ".repeat(400)).length() <= NoticeText.MAX_BROADCAST_CHARS);
    }
}
