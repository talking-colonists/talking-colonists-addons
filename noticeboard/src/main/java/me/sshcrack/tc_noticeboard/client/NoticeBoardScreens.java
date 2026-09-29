package me.sshcrack.tc_noticeboard.client;

import net.minecraft.core.BlockPos;

/** Opens the addon's windows. Client side only; blocks call it only when their level is client-side. */
public final class NoticeBoardScreens {
    private NoticeBoardScreens() {
    }

    public static void openNoticeBoard(BlockPos pos) {
        new NoticeBoardWindow(pos).open();
    }
}
