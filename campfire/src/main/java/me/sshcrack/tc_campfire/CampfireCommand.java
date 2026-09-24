package me.sshcrack.tc_campfire;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** {@code /campfire start} and {@code /campfire stop} (operators): run a campfire night now. */
final class CampfireCommand {
    private static final int FIRE_SEARCH_CHUNKS = 3;

    private CampfireCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("campfire")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("start").executes(CampfireCommand::start))
                .then(Commands.literal("stop").executes(CampfireCommand::stop)));
    }

    private static int start(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        CampfireDirector director = CampfireNights.director();
        if (director == null) return 0;
        ServerLevel level = player.serverLevel();
        List<BlockPos> fires = CampfireDirector.litCampfires(level, player.blockPosition(), FIRE_SEARCH_CHUNKS);
        for (BlockPos fire : fires) {
            IColony colony = IColonyManager.getInstance().getIColony(level, fire);
            if (colony == null) continue;
            Gathering gathering = director.start(colony, level, fire, 2,
                    message -> player.sendSystemMessage(Component.literal("[Campfire] " + message)));
            return gathering == null ? 0 : 1;
        }
        context.getSource().sendFailure(Component.literal("No lit campfire inside a colony near you."));
        return 0;
    }

    private static int stop(CommandContext<CommandSourceStack> context) {
        CampfireDirector director = CampfireNights.director();
        if (director == null || director.active().isEmpty()) {
            context.getSource().sendFailure(Component.literal("No campfire night is running."));
            return 0;
        }
        int count = director.active().size();
        director.stopAll();
        return count;
    }
}
