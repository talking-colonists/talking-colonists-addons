package me.sshcrack.tc_postal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LetterTextTest {
    private static final List<String> NAMES = List.of("Anna X. Smith", "Ben Miller", "Anna Brook", "Carl Y. Fox");

    @Test
    void matchesFullNamesFirstAndLastAndUniqueFirstNames() {
        assertEquals(0, LetterText.matchRecipient("Anna X. Smith", NAMES));
        assertEquals(0, LetterText.matchRecipient("To: anna smith", NAMES));
        assertEquals(1, LetterText.matchRecipient("Dear Ben,", NAMES));
        assertEquals(3, LetterText.matchRecipient("for Carl!", NAMES));
    }

    @Test
    void reportsAmbiguousAndUnknownRecipients() {
        assertEquals(LetterText.AMBIGUOUS, LetterText.matchRecipient("Anna", NAMES));
        assertEquals(2, LetterText.matchRecipient("Anna Brook", NAMES));
        assertEquals(LetterText.NOT_FOUND, LetterText.matchRecipient("Zoe", NAMES));
        assertEquals(LetterText.NOT_FOUND, LetterText.matchRecipient("  ", NAMES));
    }

    @Test
    void longNamesFitTheShortBookTitle() {
        List<String> names = List.of("Samira R. Coppinger", "George G. Ansty", "Samuel T. Brook");
        assertEquals(0, LetterText.matchRecipient("Coppinger", names), "last name");
        assertEquals(0, LetterText.matchRecipient("Samira C.", names), "first name and last initial");
        assertEquals(0, LetterText.matchRecipient("Samira R. Coppi", names), "cut short by the title limit");
        assertEquals(0, LetterText.matchRecipient("Samira Copp", names), "first and last, cut short");
        assertEquals(LetterText.NOT_FOUND, LetterText.matchRecipient("Sam", names), "too short to be a cut name");
    }

    @Test
    void theGreetingNamesTheRecipientWhenTheTitleDoesNot() {
        List<String> names = List.of("Samira R. Coppinger", "George G. Ansty");
        assertEquals(1, LetterText.matchRecipient("A question", "Dear George Ansty,\nHow are you?", names));
        assertEquals(0, LetterText.matchRecipient("Samira", "Dear George,", names), "the title wins");
        assertEquals(LetterText.NOT_FOUND, LetterText.matchRecipient("Hi", "Hello there", names));
    }

    @Test
    void directiveQuotesTheLetterAndCapsItsLength() {
        String directive = LetterText.directive("Steve", "Hello", "x".repeat(5_000), false);
        assertTrue(directive.startsWith("Steve sent you, by the colony's courier, a letter titled \"Hello\"."));
        assertTrue(directive.contains("<<<\n" + "x".repeat(10)));
        assertTrue(directive.length() < 3_000);
        assertTrue(LetterText.directive("Steve", "Hi", "Hey", true).startsWith("Steve handed you a letter"));
    }

    @Test
    void parsesTheAnswer() {
        JsonObject json = JsonParser.parseString("{\"reply\":\" Dear Steve, thanks! \",\"memory\":\"Steve wrote to me.\"}").getAsJsonObject();
        assertEquals(new LetterText.Answer("Dear Steve, thanks!", "Steve wrote to me."), LetterText.parse(json));
        assertEquals("", LetterText.parse(JsonParser.parseString("{\"reply\":\"Hi\"}").getAsJsonObject()).memory());
        assertNull(LetterText.parse(JsonParser.parseString("{\"reply\":\"  \"}").getAsJsonObject()));
        assertNull(LetterText.parse(JsonParser.parseString("{\"memory\":\"x\"}").getAsJsonObject()));
        assertNull(LetterText.parse(null));
    }

    @Test
    void replyTitlesFitABook() {
        assertEquals("Re: Hello", LetterText.replyTitle("Hello", "Anna X. Smith"));
        assertEquals("Letter from Anna", LetterText.replyTitle("Dear Anna", "Anna X. Smith"));
        assertEquals("Letter from Anna", LetterText.replyTitle("A very long letter title about the farm", "Anna X. Smith"));
        assertTrue(LetterText.replyTitle("t".repeat(40), "N".repeat(40)).length() <= 32);
    }
}
