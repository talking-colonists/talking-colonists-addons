package me.sshcrack.tc_townhall;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Operators, for testing: {@code /townhall rush} moves every campaign on to voting now;
 * {@code /townhall notes} has citizens of the colony you stand in write suggestion notes now.
 */
final class TownHallCommands {
    private TownHallCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("townhall")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("rush").executes(context -> {
                    Elections elections = TownHall.elections();
                    int count = elections == null ? 0 : elections.rush();
                    context.getSource().sendSuccess(() -> Component.literal(count + (count == 1 ? " campaign moves" : " campaigns move")
                            + " on to voting within a few seconds."), false);
                    return count;
                }))
                .then(Commands.literal("notes").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    IColony colony = IColonyManager.getInstance().getIColony(player.level(), player.blockPosition());
                    SuggestionBox box = TownHall.suggestions();
                    if (colony == null || box == null) {
                        context.getSource().sendFailure(Component.literal("Stand inside a colony."));
                        return 0;
                    }
                    box.rush(colony);
                    context.getSource().sendSuccess(() -> Component.literal("Citizens of " + colony.getName()
                            + " write their notes now, if the colony has a Suggestion Box."), false);
                    return 1;
                })));
    }
}
