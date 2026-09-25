package me.sshcrack.tc_townhall;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;

/**
 * Operators, for testing: {@code /townhall rush} moves every campaign on to voting now;
 * {@code /townhall notes} has citizens of the colony you stand in write suggestion notes now;
 * {@code /townhall report} has every citizen mayor bring a report now; {@code /townhall appoint} makes the
 * nearest grown citizen the mayor without an election.
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
                .then(Commands.literal("appoint").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    IColony colony = IColonyManager.getInstance().getIColony(player.level(), player.blockPosition());
                    Elections elections = TownHall.elections();
                    ICitizenData nearest = colony == null ? null : colony.getCitizenManager().getCitizens().stream()
                            .filter(data -> !data.isChild() && data.getEntity().isPresent())
                            .min(Comparator.comparingDouble(data -> data.getEntity().get().distanceToSqr(player))).orElse(null);
                    if (elections == null || nearest == null) {
                        context.getSource().sendFailure(Component.literal("Stand inside a colony, near a grown citizen."));
                        return 0;
                    }
                    elections.appoint(colony, nearest, "I will see that everyone has a home, work and enough to eat.");
                    context.getSource().sendSuccess(() -> Component.literal(nearest.getName() + " is now the mayor of "
                            + colony.getName() + " (appointed, for testing)."), false);
                    return 1;
                }))
                .then(Commands.literal("report").executes(context -> {
                    Elections elections = TownHall.elections();
                    int count = elections == null ? 0 : elections.office().reportNow();
                    context.getSource().sendSuccess(() -> Component.literal(count + (count == 1 ? " citizen mayor walks" : " citizen mayors walk")
                            + " to the nearest colony member with a report, if there is anything to report."), false);
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
