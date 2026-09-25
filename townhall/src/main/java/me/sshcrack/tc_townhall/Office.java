package me.sshcrack.tc_townhall;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mayor's term, without Minecraft: the promises they were elected on and how they are going, and
 * the proposals they put to the players. Saved with the mayor; plain fields for Gson.
 */
public final class Office {
    public static final int MAX_PROMISES = 3;
    /** A proposal nobody answered in this many days counts as ignored. */
    public static final int ANSWER_DAYS = 2;
    /** An accepted "build a new ..." proposal must show a placed hut within this many days. */
    public static final int BUILD_DAYS = 3;
    /** An accepted upgrade the builder has not finished after this many days is left to the builder. */
    public static final int UPGRADE_DAYS = 6;
    /** After a refused or ignored proposal, the mayor waits this long before proposing for the same need. */
    public static final int RETRY_DAYS = 2;
    public static final int MAX_HISTORY = 8;

    /** How a promise is going: the number of affected citizens now against when the term began. */
    public enum Progress {
        KEPT, PARTLY, NOT_YET, WORSE
    }

    public static final class Promise {
        public String need = "";
        /** In the mayor's words, e.g. "a roof over every head". */
        public String summary = "";
        /** Citizens affected when the term began. */
        public int before;
    }

    public enum ProposalKind {
        /** Have the builder upgrade (or first build) an existing hut: accepting orders it. */
        UPGRADE,
        /** Place a new hut of a type the colony lacks: only a player can, so accepting is a promise. */
        BUILD
    }

    public enum ProposalStatus {
        PENDING, ACCEPTED, REFUSED, IGNORED, DONE, BROKEN, FAILED;

        public boolean open() {
            return this == PENDING || this == ACCEPTED;
        }
    }

    public static final class Proposal {
        public String id = UUID.randomUUID().toString();
        public ProposalKind kind = ProposalKind.UPGRADE;
        public String need = "";
        /** MineColonies building type, e.g. "residence". */
        public String building = "";
        /** The hut's position ({@code BlockPos#asLong}); only for upgrades. */
        public long pos;
        /** The hut's level when proposed; only for upgrades. */
        public int level;
        public int madeDay;
        public ProposalStatus status = ProposalStatus.PENDING;
        /** Who answered; empty while pending. */
        public String player = "";
        public @Nullable UUID playerId;
        public int answeredDay = -1;
        public String reason = "";

        /** "upgrade the residence to level 2", "have the builder build the hospital", "build a hospital". */
        public String what() {
            String name = buildingName(building);
            if (kind == ProposalKind.BUILD) return "build " + article(name) + " " + name;
            return level == 0 ? "have the builder build the " + name + " hut that stands ready"
                    : "upgrade the " + name + " to level " + (level + 1);
        }
    }

    /** A hut of the colony, as the mayor sees it when choosing a proposal. */
    public record Hut(String type, long pos, int level, int maxLevel, boolean busy) {
    }

    private Office() {
    }

    public static Progress progress(int before, int now) {
        if (now == 0) return Progress.KEPT;
        if (now < before) return Progress.PARTLY;
        return now == before ? Progress.NOT_YET : Progress.WORSE;
    }

    /** "residence", "restaurant", "guard tower". */
    public static String buildingName(String type) {
        return switch (type) {
            case "cook" -> "restaurant";
            case "farmer" -> "farm";
            case "guardtower" -> "guard tower";
            case "deliveryman" -> "courier's hut";
            case "townhall" -> "town hall";
            default -> type;
        };
    }

    private static String article(String noun) {
        return !noun.isEmpty() && "aeiou".indexOf(noun.charAt(0)) >= 0 ? "an" : "a";
    }

    /**
     * What the mayor proposes next, or null: for the need that affects the most citizens (the oldest
     * first on a tie), the lowest hut that helps and can be upgraded, else a new hut when the colony
     * has none of the kind. Needs refused or ignored recently are left alone for a while.
     */
    public static @Nullable Proposal choose(Map<Need, Integer> counts, Map<String, Integer> since, List<Hut> huts,
                                            List<Proposal> history, int day) {
        List<Need> needs = new ArrayList<>(counts.keySet());
        needs.removeIf(need -> counts.get(need) <= 0 || need.buildings().isEmpty());
        needs.sort(Comparator.comparingInt((Need need) -> -counts.get(need))
                .thenComparingInt(need -> since.getOrDefault(need.id(), day)));
        for (Need need : needs) {
            if (recentlyTurnedDown(need, history, day)) continue;
            Hut best = null;
            boolean any = false;
            for (Hut hut : huts) {
                if (!need.buildings().contains(hut.type())) continue;
                any = true;
                if (hut.busy() || hut.level() >= hut.maxLevel()) continue;
                if (best == null || hut.level() < best.level()) best = hut;
            }
            Proposal proposal = new Proposal();
            proposal.need = need.id();
            proposal.madeDay = day;
            if (best != null) {
                proposal.kind = ProposalKind.UPGRADE;
                proposal.building = best.type();
                proposal.pos = best.pos();
                proposal.level = best.level();
                return proposal;
            }
            if (!any) {
                proposal.kind = ProposalKind.BUILD;
                proposal.building = need.buildings().get(0);
                return proposal;
            }
        }
        return null;
    }

    private static boolean recentlyTurnedDown(Need need, List<Proposal> history, int day) {
        for (Proposal old : history) {
            if (!old.need.equals(need.id())) continue;
            boolean turnedDown = old.status == ProposalStatus.REFUSED || old.status == ProposalStatus.IGNORED
                    || old.status == ProposalStatus.FAILED;
            int when = old.answeredDay >= 0 ? old.answeredDay : old.madeDay;
            if (turnedDown && day - when < RETRY_DAYS) return true;
        }
        return false;
    }

    /** Keeps the newest {@link #MAX_HISTORY} finished proposals. */
    public static void archive(List<Proposal> history, Proposal proposal) {
        history.add(proposal);
        while (history.size() > MAX_HISTORY) history.remove(0);
    }
}
