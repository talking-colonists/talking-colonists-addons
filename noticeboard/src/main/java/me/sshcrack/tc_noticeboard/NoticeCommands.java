package me.sshcrack.tc_noticeboard;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /noticeboard rush} (operators): notices collect their replies now. */
final class NoticeCommands {
    private NoticeCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
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
}
