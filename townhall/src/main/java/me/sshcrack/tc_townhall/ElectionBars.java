package me.sshcrack.tc_townhall;

import com.minecolonies.api.colony.IColony;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A boss bar per running election, shown to colony members inside the colony: the time until voting
 * during the campaign, then the turnout while citizens vote.
 */
final class ElectionBars {
    private final MinecraftServer server;
    private final Map<String, ServerBossEvent> bars = new HashMap<>();

    ElectionBars(MinecraftServer server) {
        this.server = server;
    }

    void show(IColony colony, String electionId, boolean voting, String title, float progress) {
        ServerBossEvent bar = bars.computeIfAbsent(electionId, id -> new ServerBossEvent(Component.empty(),
                BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS));
        bar.setName(Component.literal(title));
        bar.setColor(voting ? BossEvent.BossBarColor.GREEN : BossEvent.BossBarColor.YELLOW);
        bar.setProgress(Math.max(0, Math.min(1, progress)));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean inside = colony.getPermissions().isColonyMember(player) && player.level() == colony.getWorld()
                    && colony.isCoordInColony(player.level(), player.blockPosition());
            if (inside) bar.addPlayer(player);
            else bar.removePlayer(player);
        }
    }

    /** Removes the bars of elections that are over. */
    void keepOnly(Set<String> electionIds) {
        bars.entrySet().removeIf(entry -> {
            if (electionIds.contains(entry.getKey())) return false;
            entry.getValue().removeAllPlayers();
            return true;
        });
    }

    void clear() {
        keepOnly(Set.of());
    }
}
