package me.sshcrack.tc_playtest;

import com.minecolonies.api.colony.ICitizenData;
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
import net.minecraft.world.item.ItemStack;
import me.sshcrack.tc_playtest.shared.book.WrittenBooks;

import java.util.List;

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
                .then(Commands.literal("news").executes(PlaytestCommand::news))
                .then(Commands.literal("letter").executes(PlaytestCommand::letter))
                .then(Commands.literal("visitor").executes(PlaytestCommand::visitor)));
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

    /** A signed letter addressed to a random citizen, ready to hand over. */
    private static int letter(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        IColony colony = colony(player);
        if (colony == null) return 0;
        List<ICitizenData> citizens = colony.getCitizenManager().getCitizens();
        if (citizens.isEmpty()) return 0;
        ICitizenData recipient = citizens.get(player.getRandom().nextInt(citizens.size()));
        String name = recipient.getName();
        ItemStack letter = WrittenBooks.create(name.length() <= 32 ? name : name.split(" ")[0], player.getGameProfile().getName(),
                WrittenBooks.ORIGINAL, List.of(Component.literal("Hello " + name.split(" ")[0]
                        + "! How is your work going, and is there anything you need from me? What should the colony build next?")));
        if (!player.getInventory().add(letter)) player.drop(letter, false);
        context.getSource().sendSuccess(() -> Component.literal("[Playtest] A letter to " + name
                + ". Right-click them (or the courier) with it."), false);
        return 1;
    }

    /** Teleports next to a loaded tavern visitor and says what they ask to join. */
    private static int visitor(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        IColony colony = colony(player);
        if (colony == null) return 0;
        for (var data : colony.getVisitorManager().getCivilianDataMap().values()) {
            var entity = data.getEntity().orElse(null);
            if (!(data instanceof com.minecolonies.api.colony.IVisitorData visitor) || entity == null) continue;
            player.teleportTo(entity.getX() + 1.5, entity.getY(), entity.getZ());
            var cost = visitor.getRecruitCost();
            context.getSource().sendSuccess(() -> Component.literal("[Playtest] " + data.getName() + " asks "
                    + cost.getCount() + " x " + cost.getHoverName().getString() + " to join. Talk them down!"), false);
            return 1;
        }
        context.getSource().sendFailure(Component.literal("[Playtest] No visitor is at the tavern right now; one arrives every few minutes."));
        return 0;
    }

    private static IColony colony(ServerPlayer player) {
        IColony colony = PlaytestColony.find(player);
        if (colony == null) player.sendSystemMessage(Component.literal("[Playtest] You own no colony in this dimension."));
        return colony;
    }
}
