package me.sshcrack.tc_tavern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HaggleTest {
    @Test
    void floorIsHalfRoundedUpAndAtLeastOne() {
        assertEquals(4, Haggle.floor(8));
        assertEquals(5, Haggle.floor(9));
        assertEquals(1, Haggle.floor(1));
    }

    @Test
    void acceptsRequestsWithinOneStep() {
        Haggle.Result result = Haggle.apply(8, 8, 6);
        assertEquals(6, result.amount());
        assertFalse(result.limited());
    }

    @Test
    void limitsBigDropsToOneStep() {
        Haggle.Result result = Haggle.apply(8, 8, 1);
        assertEquals(6, result.amount());
        assertTrue(result.limited());
        assertTrue(result.note().contains("for now"));
    }

    @Test
    void neverGoesBelowTheFloor() {
        Haggle.Result result = Haggle.apply(8, 5, 1);
        assertEquals(4, result.amount());
        assertTrue(result.note().contains("lowest they will ever accept"));
        Haggle.Result atFloor = Haggle.apply(8, 4, 2);
        assertEquals(4, atFloor.amount());
        assertTrue(atFloor.limited());
    }

    @Test
    void neverRaisesThePrice() {
        assertEquals(5, Haggle.apply(8, 5, 12).amount());
    }

    @Test
    void promptNamesTheCostAndTool() {
        String text = RecruitPrompt.text("8 x Diamond", "tc_9_tc_tavern_negotiate_recruit_cost");
        assertTrue(text.contains("recruit you for 8 x Diamond"));
        assertTrue(text.contains("call tc_9_tc_tavern_negotiate_recruit_cost"));
        assertTrue(RecruitPrompt.text(null, "t").contains("a price in items"));
    }
}
