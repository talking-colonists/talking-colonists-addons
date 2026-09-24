package me.sshcrack.tc_noticeboard;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.sshcrack.mc_talking.api.memory.BroadcastPublishResult;
import me.sshcrack.mc_talking.api.memory.BroadcastRequest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /loudspeaker <message>}: every citizen of your colony hears it at once.
 * {@code /noticeboard rush} (operators): notices collect their replies now.
 */
final class NoticeCommands {
    private NoticeCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("loudspeaker")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> loudspeaker(context.getSource(), StringArgumentType.getString(context, "message")))));
        dispatcher.register(Commands.literal("noticeboard")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("rush").executes(context -> {
                    Board board = NoticeBoard.board();
                    int count = board == null ? 0 : board.rush();
                    context.getSource().sendSuccess(() -> Component.literal(count + (count == 1 ? " notice collects" : " notices collect")
                            + " replies within a few seconds."), false);
                    return count;
                })));
    }

    private static int loudspeaker(CommandSourceStack source, String message) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Board board = NoticeBoard.board();
        IColony colony = colonyOf(player);
        if (board == null || colony == null) {
            source.sendFailure(Component.literal("Stand in, or own, a colony you belong to."));
            return 0;
        }
        if (message.strip().length() > BroadcastRequest.MAX_MESSAGE_LENGTH) {
            source.sendFailure(Component.literal("Keep it under " + BroadcastRequest.MAX_MESSAGE_LENGTH + " characters."));
            return 0;
        }
        BroadcastPublishResult result = board.announce(player, colony, message);
        if (!result.isPublished()) {
            source.sendFailure(Component.literal(switch (result.status()) {
                case RATE_LIMITED -> "The colony has had a lot of news lately; try again in a while.";
                case DISABLED -> "Broadcasts are turned off on this server.";
                default -> "Nobody could hear the announcement.";
            }));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[Loudspeaker] " + result.recipients() + " citizens of "
                + colony.getName() + " heard you."), false);
        return 1;
    }

    /** The colony the player stands in if they belong to it, otherwise the colony they own. */
    private static @Nullable IColony colonyOf(ServerPlayer player) {
        IColonyManager manager = IColonyManager.getInstance();
        IColony here = manager.getIColony(player.level(), player.blockPosition());
        if (here != null && here.getPermissions().isColonyMember(player)) return here;
        return manager.getIColonyByOwner(player.level(), player);
    }
}
