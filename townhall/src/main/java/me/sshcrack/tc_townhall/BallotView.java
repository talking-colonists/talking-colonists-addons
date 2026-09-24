package me.sshcrack.tc_townhall;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Ballot Box window shows: the colony's election or its mayor, as seen by one player. Sent to
 * the client as JSON; plain fields only, so it needs no Minecraft classes.
 */
public final class BallotView {
    /** The election phase; it also picks the Ballot Box's model. */
    public enum Phase {
        IDLE, CAMPAIGN, VOTING
    }

    public static final class Entry {
        public String name = "";
        public String slogan = "";
        public String platform = "";
        public boolean citizen;
        public boolean you;
        public int votes;
    }

    public static final class Reason {
        public String voter = "";
        /** The chosen candidate's name; empty for an abstention. */
        public String candidate = "";
        public String reason = "";
    }

    public String colony = "";
    public Phase phase = Phase.IDLE;
    /** Minutes until voting starts (campaign) or ends (voting). */
    public int minutesLeft;
    public List<Entry> candidates = new ArrayList<>();
    public int voted;
    public int voters;
    public List<Reason> reasons = new ArrayList<>();
    public String mayor = "";
    public int mayorSinceDay;
    public String mayorResult = "";
    /** Days until the next election can be called, 0 when it can be now. */
    public int nextElectionDays;
    public boolean member;
    public boolean canStand;
    public boolean youStand;
    public boolean speechSupported;

    /** The status line under the title. */
    public String status() {
        return switch (phase) {
            case CAMPAIGN -> "Campaign: voting starts in about " + minutes(minutesLeft) + ".";
            case VOTING -> "Voting: " + voted + " of " + voters + " voted, closes in about " + minutes(minutesLeft) + ".";
            case IDLE -> mayor.isEmpty()
                    ? (nextElectionDays > 0 ? "No mayor. The next election can be called in " + days(nextElectionDays) + "."
                    : "No mayor yet. Stand for mayor to call an election.")
                    : "Mayor: " + mayor + ", since day " + mayorSinceDay + "."
                    + (nextElectionDays > 0 ? " Next election possible in " + days(nextElectionDays) + "." : " An election can be called now.");
        };
    }

    private static String minutes(int minutes) {
        return minutes <= 1 ? "a minute" : minutes + " minutes";
    }

    private static String days(int days) {
        return days == 1 ? "a day" : days + " days";
    }
}
