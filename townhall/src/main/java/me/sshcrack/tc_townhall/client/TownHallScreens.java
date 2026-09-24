package me.sshcrack.tc_townhall.client;

import net.minecraft.core.BlockPos;

/** Opens the addon's windows. Client side only; blocks call it only when their level is client-side. */
public final class TownHallScreens {
    private TownHallScreens() {
    }

    public static void openSuggestionBox(BlockPos pos) {
        new SuggestionBoxWindow(pos).open();
    }
}
