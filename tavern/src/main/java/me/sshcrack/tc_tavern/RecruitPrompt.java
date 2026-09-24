package me.sshcrack.tc_tavern;

import org.jetbrains.annotations.Nullable;

/** The instruction a tavern visitor gets about joining the colony. */
public final class RecruitPrompt {
    private RecruitPrompt() {
    }

    public static String text(@Nullable String recruitCost, String toolName) {
        String cost = recruitCost == null || recruitCost.isBlank() ? "a price in items" : recruitCost;
        return "You are a guest at this colony's tavern, not a citizen yet. The player can recruit you for "
                + cost + ".\n"
                + "You may haggle. If the player makes a good case (kindness, a job that suits you, a fair offer, "
                + "an honest story), call " + toolName + " with a lower amount, then tell them the price it returns. "
                + "Go down in small steps and do not give yourself away for nothing; you will never go below about half. "
                + "Never say the price changed unless the tool confirmed it.";
    }
}
