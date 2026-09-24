package me.sshcrack.tc_townhall.client;

import com.ldtteam.blockui.BOScreen;
import me.sshcrack.tc_townhall.block.BallotBoxBlockEntity;
import me.sshcrack.tc_townhall.shared.net.BlockActions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Opens the addon's windows. Client side only; blocks call it only when their level is client-side. */
public final class TownHallScreens {
    static {
        BlockActions.onView(BallotBoxBlockEntity.VIEW_KIND, (pos, json) -> {
            if (Minecraft.getInstance().screen instanceof BOScreen screen && screen.getWindow() instanceof BallotBoxWindow window
                    && window.pos().equals(pos)) {
                window.receive(json);
            }
        });
    }

    private TownHallScreens() {
    }

    public static void openSuggestionBox(BlockPos pos) {
        new SuggestionBoxWindow(pos).open();
    }

    public static void openBallotBox(BlockPos pos) {
        new BallotBoxWindow(pos).open();
    }
}
