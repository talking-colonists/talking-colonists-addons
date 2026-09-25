package me.sshcrack.tc_townhall;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class OfficeTest {
    private static Map<Need, Integer> needs(Object... pairs) {
        Map<Need, Integer> needs = new EnumMap<>(Need.class);
        for (Need need : Need.values()) needs.put(need, 0);
        for (int i = 0; i < pairs.length; i += 2) needs.put((Need) pairs[i], (Integer) pairs[i + 1]);
        return needs;
    }

    @Test
    void promisesAreMeasuredAgainstTheStartOfTheTerm() {
        assertEquals(Office.Progress.KEPT, Office.progress(3, 0));
        assertEquals(Office.Progress.KEPT, Office.progress(0, 0));
        assertEquals(Office.Progress.PARTLY, Office.progress(4, 2));
        assertEquals(Office.Progress.NOT_YET, Office.progress(2, 2));
        assertEquals(Office.Progress.WORSE, Office.progress(1, 3));
        assertEquals(Office.Progress.WORSE, Office.progress(0, 1));
    }

    @Test
    void theBiggestNeedGetsTheLowestHutThatCanGrow() {
        List<Office.Hut> huts = List.of(
                new Office.Hut("residence", 1L, 3, 5, false),
                new Office.Hut("residence", 2L, 1, 5, false),
                new Office.Hut("hospital", 3L, 1, 5, false));
        Office.Proposal proposal = Office.choose(needs(Need.HOUSING, 4, Need.HEALTH, 1), Map.of(), huts, List.of(), 7);
        assertNotNull(proposal);
        assertEquals(Office.ProposalKind.UPGRADE, proposal.kind);
        assertEquals(2L, proposal.pos);
        assertEquals("upgrade the residence to level 2", proposal.what());
        assertEquals(7, proposal.madeDay);
    }

    @Test
    void busyOrFinishedHutsAreSkippedAndAMissingKindIsBuilt() {
        List<Office.Hut> huts = List.of(
                new Office.Hut("residence", 1L, 5, 5, false),
                new Office.Hut("residence", 2L, 2, 5, true));
        // Every residence is maxed or already ordered: nothing to propose for housing, but health has no hospital.
        Office.Proposal proposal = Office.choose(needs(Need.HOUSING, 5, Need.HEALTH, 1), Map.of(), huts, List.of(), 3);
        assertNotNull(proposal);
        assertEquals(Office.ProposalKind.BUILD, proposal.kind);
        assertEquals("build a hospital", proposal.what());
        assertEquals("have the builder build the guard tower hut that stands ready",
                Office.choose(needs(Need.SAFETY, 2), Map.of(), List.of(new Office.Hut("guardtower", 9L, 0, 5, false)), List.of(), 3).what());
    }

    @Test
    void needsWithoutAHutAndNeedsTurnedDownRecentlyAreLeftAlone() {
        assertNull(Office.choose(needs(Need.WORK, 6), Map.of(), List.of(), List.of(), 3), "no hut makes jobs");
        Office.Proposal refused = new Office.Proposal();
        refused.need = Need.HEALTH.id();
        refused.status = Office.ProposalStatus.REFUSED;
        refused.answeredDay = 4;
        List<Office.Proposal> history = new ArrayList<>(List.of(refused));
        assertNull(Office.choose(needs(Need.HEALTH, 1), Map.of(), List.of(), history, 5));
        assertNotNull(Office.choose(needs(Need.HEALTH, 1), Map.of(), List.of(), history, 4 + Office.RETRY_DAYS));
    }

    @Test
    void onATieTheOldestNeedComesFirst() {
        Office.Proposal proposal = Office.choose(needs(Need.FOOD, 2, Need.HEALTH, 2),
                Map.of(Need.FOOD.id(), 5, Need.HEALTH.id(), 2), List.of(), List.of(), 6);
        assertNotNull(proposal);
        assertEquals(Need.HEALTH.id(), proposal.need);
    }

    @Test
    void theHistoryKeepsTheNewestProposals() {
        List<Office.Proposal> history = new ArrayList<>();
        for (int i = 0; i < Office.MAX_HISTORY + 3; i++) {
            Office.Proposal proposal = new Office.Proposal();
            proposal.madeDay = i;
            Office.archive(history, proposal);
        }
        assertEquals(Office.MAX_HISTORY, history.size());
        assertEquals(3, history.get(0).madeDay);
    }
}
