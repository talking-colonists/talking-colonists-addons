package me.sshcrack.tc_townhall;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BallotViewTest {
    @Test
    void statusFollowsThePhase() {
        BallotView view = new BallotView();
        assertEquals("No mayor yet. Stand for mayor to call an election.", view.status());

        view.mayor = "Anna";
        view.mayorSinceDay = 4;
        view.nextElectionDays = 2;
        assertEquals("Mayor: Anna, since day 4. Next election possible in 2 days.", view.status());

        view.phase = BallotView.Phase.CAMPAIGN;
        view.minutesLeft = 14;
        assertEquals("Campaign: voting starts in about 14 minutes.", view.status());

        view.phase = BallotView.Phase.VOTING;
        view.voted = 6;
        view.voters = 11;
        view.minutesLeft = 1;
        assertEquals("Voting: 6 of 11 voted, closes in about a minute.", view.status());
    }
}
