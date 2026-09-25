package me.sshcrack.tc_campfire;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampfireStoryTest {
    @Test
    void joinsNamesLikeASentence() {
        assertEquals("", CampfireStory.joinNames(List.of()));
        assertEquals("Anna", CampfireStory.joinNames(List.of("Anna")));
        assertEquals("Anna and Ben", CampfireStory.joinNames(List.of("Anna", "Ben")));
        assertEquals("Anna, Ben and Carl", CampfireStory.joinNames(List.of("Anna", "Ben", "Carl")));
    }

    @Test
    void agendaNamesTellersAndListsNews() {
        String agenda = CampfireStory.agenda("Oakvale", List.of("Anna", "Ben", "Carl"),
                List.of("A raid hit the east wall", " ", "Anna was hired as a baker", "4", "5", "6", "7"));
        assertTrue(agenda.startsWith("Campfire night in Oakvale. It is dusk, and Anna, Ben and Carl sit together"));
        assertTrue(agenda.contains("never invent deaths"));
        assertTrue(agenda.contains("never apologize"));
        assertTrue(agenda.contains("- A raid hit the east wall\n- Anna was hired as a baker\n"));
        assertTrue(agenda.endsWith("- 6"), "blank lines do not count");
        assertFalse(agenda.contains("- 7"), "at most five news lines");
    }

    @Test
    void agendaWithoutNewsHasNoNewsSection() {
        assertFalse(CampfireStory.agenda("Oakvale", List.of("Anna", "Ben"), List.of()).contains("lately"));
    }

    @Test
    void seatsAreEvenlySpacedAroundTheFire() {
        List<double[]> seats = CampfireStory.seats(4);
        assertEquals(4, seats.size());
        for (double[] seat : seats) {
            assertEquals(CampfireStory.SEAT_RADIUS, Math.hypot(seat[0], seat[1]), 1e-9);
        }
        assertEquals(CampfireStory.SEAT_RADIUS, seats.get(0)[0], 1e-9);
        assertEquals(-CampfireStory.SEAT_RADIUS, seats.get(2)[0], 1e-9);
    }

    @Test
    void newsLineQuotesTheFirstStoryWithinLimits() {
        assertEquals("Anna and Ben told stories around the campfire",
                CampfireStory.newsLine(List.of("Anna", "Ben"), null, null));
        String line = CampfireStory.newsLine(List.of("Anna", "Ben"), "Ben", "word ".repeat(100));
        assertTrue(line.startsWith("Anna and Ben told stories around the campfire; Ben began: \"word word"));
        assertTrue(line.length() <= 300);
        assertTrue(line.endsWith("…\""));
    }

    @Test
    void dropsTheSpeakerPrefixFromStories() {
        assertEquals("Um, hello.", CampfireStory.withoutSpeaker("Lea X. Groston", "Lea X. Groston: Um, hello."));
        assertEquals("Ben: hi", CampfireStory.withoutSpeaker("Lea", "Ben: hi"));
        assertEquals("", CampfireStory.withoutSpeaker("Lea", null));
        assertEquals("Anna and Ben told stories around the campfire; Ben began: \"Once upon a time.\"",
                CampfireStory.newsLine(List.of("Anna", "Ben"), "Ben", "Ben: Once upon a time."));
    }

    @Test
    void aPlayerWhoSpeaksUpIsAnsweredFirst() {
        String instruction = CampfireStory.answer("Steve", "  Tell us about the old mine!  ");
        assertTrue(instruction.startsWith("Steve, standing with you at the campfire, spoke up"));
        assertTrue(instruction.contains("\"Tell us about the old mine!\""));
        assertTrue(instruction.contains("Answer Steve directly"));
        assertTrue(CampfireStory.answer("Steve", "x".repeat(1000)).length() < 1000);
    }
}
