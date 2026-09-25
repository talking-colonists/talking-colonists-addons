package me.sshcrack.tc_townhall;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficeTextTest {
    private static JsonObject promise(String need, String summary) {
        JsonObject json = new JsonObject();
        json.addProperty("need", need);
        json.addProperty("summary", summary);
        return json;
    }

    private static Office.Proposal proposal(Office.ProposalStatus status) {
        Office.Proposal proposal = new Office.Proposal();
        proposal.need = Need.HOUSING.id();
        proposal.building = "residence";
        proposal.level = 1;
        proposal.madeDay = 4;
        proposal.status = status;
        proposal.player = "Steve";
        proposal.answeredDay = 5;
        return proposal;
    }

    @Test
    void promisesAreReadOncePerNeedAndOnlyKnownNeeds() {
        JsonArray array = new JsonArray();
        array.add(promise("housing", " A roof over every head "));
        array.add(promise("housing", "More homes"));
        array.add(promise("walls", "A wall"));
        array.add(promise("food", ""));
        JsonObject json = new JsonObject();
        json.add("promises", array);
        List<Office.Promise> promises = OfficeText.parsePromises(json);
        assertEquals(2, promises.size());
        assertEquals("A roof over every head", promises.get(0).summary);
        assertEquals("better food", promises.get(1).summary);
        assertTrue(OfficeText.parsePromises(null).isEmpty());
        assertTrue(OfficeText.promiseSchema().toString().contains("\"enum\":[\"housing\",\"work\",\"food\",\"health\",\"safety\",\"supplies\"]"));
    }

    @Test
    void promiseLinesSayHowTheyAreGoing() {
        Office.Promise promise = new Office.Promise();
        promise.need = Need.HOUSING.id();
        promise.summary = "A roof over every head";
        promise.before = 3;
        assertEquals("\"A roof over every head\": kept (3 citizens were without a proper home when the term began, none now)",
                OfficeText.promiseLine(promise, 0));
        assertEquals("\"A roof over every head\": partly kept (3 citizens were without a proper home when the term began, 1 now)",
                OfficeText.promiseLine(promise, 1));
        assertEquals("\"A roof over every head\": not kept yet (still 3 citizens without a proper home)", OfficeText.promiseLine(promise, 3));
        promise.before = 0;
        assertEquals("\"A roof over every head\": kept so far (no citizen is without a proper home)", OfficeText.promiseLine(promise, 0));
    }

    @Test
    void theReportCountsHowOftenAProblemWasReported() {
        assertEquals("3 citizens are without a proper home, since day 4", OfficeText.needLine(Need.HOUSING, 3, 4, 0));
        assertEquals("one citizen is sick, since day 2 (reported twice already)", OfficeText.needLine(Need.HEALTH, 1, 2, 2));
        List<String> pages = OfficeText.reportPages("Oakvale", "Remy", 6, List.of("one citizen is sick, since day 2"), List.of(),
                proposal(Office.ProposalStatus.PENDING));
        assertEquals(2, pages.size());
        assertTrue(pages.get(0).startsWith("Mayor's report on Oakvale, day 6"));
        assertTrue(pages.get(0).contains("- One citizen is sick, since day 2."));
        assertTrue(pages.get(1).contains("I propose we upgrade the residence to level 2.") && pages.get(1).endsWith("Remy"));
        assertTrue(OfficeText.reportPages("Oakvale", "Remy", 6, List.of(), List.of(), null).get(0).endsWith("Remy"));
    }

    @Test
    void theAgendaAsksForAClearAnswerAndNamesTheTool() {
        String agenda = OfficeText.agenda("Steve", List.of("one citizen is sick, since day 2 (reported twice already)"),
                List.of("\"Healthy people\": not kept yet (still one citizen sick)"), proposal(Office.ProposalStatus.PENDING), "tc_tool");
        assertTrue(agenda.contains("propose to Steve that you upgrade the residence to level 2"));
        assertTrue(agenda.contains("call tc_tool"));
        assertTrue(agenda.contains("Any building work for homes counts: a new or upgraded residence. It is kept once a builder starts on it."));
        assertTrue(agenda.contains("let the frustration show"));
        assertTrue(agenda.length() <= OfficeText.MAX_AGENDA_CHARS);
        assertFalse(OfficeText.agenda("Steve", List.of(), List.of(), null, "tc_tool").contains("tc_tool"));
    }

    @Test
    void theColonyHearsAnswersAndOutcomes() {
        Office.Proposal proposal = proposal(Office.ProposalStatus.REFUSED);
        proposal.reason = "Not now";
        assertEquals("Steve turned down Mayor Remy's proposal to upgrade the residence to level 2: \"Not now\"",
                OfficeText.answered("Remy", proposal));
        proposal.status = Office.ProposalStatus.FAILED;
        assertTrue(OfficeText.answered("Remy", proposal).startsWith("Steve agreed to"));
        proposal.status = Office.ProposalStatus.BROKEN;
        assertEquals("Steve agreed with Mayor Remy on day 5 to upgrade the residence to level 2, but no building work for it was ever ordered.",
                OfficeText.outcome("Remy", proposal));
        assertEquals("Mayor Remy gave Steve the mayor's report. The most pressing problem: one citizen is sick. The mayor asked Steve to upgrade the residence to level 2.",
                OfficeText.statement("Remy", "Steve", "one citizen is sick", proposal(Office.ProposalStatus.PENDING)));
    }

    @Test
    void aKeptProposalNamesTheBuilderAtWork() {
        Office.Proposal proposal = proposal(Office.ProposalStatus.DONE);
        proposal.building = "barracks";
        proposal.builder = "Anna";
        assertEquals("Anna the builder is now at work on the barracks, as Steve agreed with Mayor Remy.", OfficeText.outcome("Remy", proposal));
        assertTrue(OfficeText.proposalLine(proposal).endsWith("(Steve agreed; Anna the builder took it on)"));
        Need safety = Need.SAFETY;
        proposal.need = safety.id();
        assertEquals("Any building work for safety counts: a new guard tower or barracks, or an upgraded barracks or barracks tower. It is kept once a builder starts on it.",
                OfficeText.alternatives(proposal));
    }

    @Test
    void votersSeeTheRecord() {
        assertNull(OfficeText.playerRecord("Steve", List.of()));
        assertEquals("When the mayor asked Steve: upgrade the residence to level 2 (Steve turned it down on day 5).",
                OfficeText.playerRecord("Steve", List.of(proposal(Office.ProposalStatus.REFUSED))));
        String record = OfficeText.record("Remy", List.of("\"More homes\": kept"), List.of(proposal(Office.ProposalStatus.DONE)));
        assertEquals("Remy's promises: \"More homes\": kept. The mayor's proposals to the players: upgrade the residence to level 2 (Steve agreed; the work came along).",
                record);
        String directive = ElectionText.voteDirective(List.of(new ElectionText.CandidateBrief("Remy", null, null, "the sitting mayor. " + record)));
        assertTrue(directive.contains("Their record: the sitting mayor."));
    }
}
