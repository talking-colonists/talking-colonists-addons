package me.sshcrack.tc_townhall;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElectionTextTest {
    private static JsonObject vote(String vote, String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("vote", vote);
        json.addProperty("reason", reason);
        return json;
    }

    @Test
    void candidacyNamesSloganAndPlatformWithinTheLimit() {
        assertEquals("Dev is standing for mayor at the town hall, with the slogan \"Walls first\". Their platform: More guards and a wall.",
                ElectionText.candidacy("Dev", "Walls first", " More guards\nand a wall. "));
        assertTrue(ElectionText.candidacy("Dev", "x", "word ".repeat(300)).length() <= ElectionText.MAX_BROADCAST_CHARS);
        assertEquals("A better colony", ElectionText.slogan("  "));
    }

    @Test
    void votesAreParsedAgainstTheBallot() {
        List<String> names = List.of("Dev", "Remy J. Fudd");
        assertEquals(new ElectionText.Vote(1, "He knows our problems."), ElectionText.parseVote(vote("remy j. fudd", " He knows our problems. "), names));
        assertEquals(-1, ElectionText.parseVote(vote("abstain", "Neither."), names).candidate());
        assertNull(ElectionText.parseVote(vote("Somebody else", "x"), names));
        assertNull(ElectionText.parseVote(null, names));
        assertTrue(ElectionText.voteSchema(names).toString().contains("\"enum\":[\"Dev\",\"Remy J. Fudd\",\"abstain\"]"));
    }

    @Test
    void tallyLeadersAndResult() {
        List<ElectionText.Vote> votes = List.of(new ElectionText.Vote(0, ""), new ElectionText.Vote(1, ""),
                new ElectionText.Vote(0, ""), new ElectionText.Vote(-1, ""));
        int[] counts = ElectionText.tally(votes, 2);
        assertArrayEquals(new int[]{2, 1}, counts);
        assertEquals(List.of(0), ElectionText.leaders(counts));
        assertEquals(List.of(0, 1), ElectionText.leaders(new int[]{1, 1}));
        assertEquals(List.of(), ElectionText.leaders(new int[]{0, 0}));
        assertEquals("Dev was elected mayor with 2 of 4 votes (Remy 1, 1 abstained).",
                ElectionText.result(List.of("Dev", "Remy"), counts, 0, 4, false));
        assertTrue(ElectionText.result(List.of("Dev", "Remy"), new int[]{1, 1}, 1, 2, true).contains("coin toss"));
        assertEquals("Nobody was elected: all 3 voters abstained.", ElectionText.noWinner(3));
    }

    @Test
    void voterOnlyKnowsWhatTheyHeard() {
        String directive = ElectionText.voteDirective(List.of(
                new ElectionText.CandidateBrief("Dev", "Dev is standing for mayor with the slogan \"Walls first\".", null),
                new ElectionText.CandidateBrief("Remy", null, "you feel strong trust towards them.")));
        assertTrue(directive.contains("- Dev: you heard this: Dev is standing"));
        assertTrue(directive.contains("- Remy: you never heard what they stand for. About them: you feel strong trust"));
    }

    @Test
    void feelingsSkipNeutralDimensions() {
        assertNull(ElectionText.feelings(List.of(new ElectionText.Feeling("trust", 0.1f))));
        assertEquals("you feel strong trust, little respect towards them.", ElectionText.feelings(List.of(
                new ElectionText.Feeling("trust", 0.8f), new ElectionText.Feeling("respect", -0.3f),
                new ElectionText.Feeling("anger", 0.05f))));
    }

    @Test
    void rivalCampaignNeedsAPlatform() {
        JsonObject json = new JsonObject();
        json.addProperty("slogan", "Bread for all");
        json.addProperty("platform", "A bakery before anything else.");
        ElectionText.Rival rival = ElectionText.parseRival(json);
        assertNotNull(rival);
        assertEquals("Bread for all", rival.slogan());
        json.addProperty("platform", " ");
        assertNull(ElectionText.parseRival(json));
    }

    @Test
    void suggestionNotesAreShort() {
        assertEquals("We need a bakery.", SuggestionText.clean(" \"We need a bakery.\" "));
        assertTrue(SuggestionText.clean("word ".repeat(100)).length() <= SuggestionText.MAX_NOTE_CHARS);
    }

    @Test
    void aCitizenCandidateGivesTheirSpeechAloud() {
        String toSteve = ElectionText.rivalSpeech("Oakvale", "Homes first ", "More   houses.", List.of("Steve"), false, "Steve");
        assertTrue(toSteve.startsWith("You stand for mayor of Oakvale against Steve. Your slogan: \"Homes first\". Your platform: More houses."));
        assertTrue(toSteve.contains("You just handed Steve your campaign pamphlet."));
        assertTrue(toSteve.contains("aloud to Steve and everyone around you"));
        assertTrue(toSteve.contains("never insult your opponents"));
        String incumbent = ElectionText.rivalSpeech("Oakvale", "Steady hands", "Keep going.", List.of("Steve", "Alex"), true, null);
        assertTrue(incumbent.contains("as the sitting mayor seeking re-election"));
        assertTrue(incumbent.contains("stand by what you did in office"));
        assertTrue(incumbent.contains("aloud to everyone around you"));
        assertEquals("Jobs first.", ElectionText.withoutSpeaker("Dalton E. Clerk", "Dalton E. Clerk: Jobs first. "));
        assertEquals("Jobs first.", ElectionText.withoutSpeaker("Dalton E. Clerk", "Jobs first."));
        assertEquals("\"Homes first\"\n\nMore houses.\n\nVote Remy for mayor!", ElectionText.pamphlet("Remy", "Homes first", "More houses."));
    }
}
