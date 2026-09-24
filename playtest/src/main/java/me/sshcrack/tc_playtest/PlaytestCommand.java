package me.sshcrack.tc_playtest;

import com.minecolonies.api.colony.IColony;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import me.sshcrack.mc_talking.api.colony.AddonColonyEvent;
import me.sshcrack.mc_talking.api.colony.ColonyEventService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** {@code /playtest}: the checklist, plus small helpers its buttons use. */
final class PlaytestCommand {
    private PlaytestCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("playtest")
                .executes(context -> {
                    Checklist.send(context.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("home").executes(PlaytestCommand::home))
                .then(Commands.literal("news").executes(PlaytestCommand::news)));
    }

    private static int home(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        IColony colony = colony(player);
        if (colony == null) return 0;
        BlockPos center = colony.getCenter();
        player.teleportTo(center.getX() + 0.5, center.getY() + 1, center.getZ() - 2.5);
        return 1;
    }

    private static int news(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        IColony colony = colony(player);
        if (colony == null) return 0;
        String[][] events = {
                {"raid", "Three raiders attacked the east wall at night; the guards drove them off and nobody was hurt"},
                {"harvest", "The farmers brought in a record pumpkin harvest"},
                {"newcomer", "A travelling merchant arrived at the tavern with stories from the coast"}};
        int recorded = 0;
        for (String[] event : events) {
            if (ColonyEventService.record(colony, new AddonColonyEvent(PlaytestMod.MOD_ID, event[0], event[1]))) recorded++;
        }
        int count = recorded;
        context.getSource().sendSuccess(() -> Component.literal("[Playtest] Recorded " + count + " colony events."), false);
        return count;
    }

    private static IColony colony(ServerPlayer player) {
        IColony colony = PlaytestColony.find(player);
        if (colony == null) player.sendSystemMessage(Component.literal("[Playtest] You own no colony in this dimension."));
        return colony;
    }
}
