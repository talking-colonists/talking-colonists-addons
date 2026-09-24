package me.sshcrack.tc_postal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** {@code /mail}: collect your letters. {@code /mail rush} (operators): your letters arrive now. */
final class MailCommand {
    private MailCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mail")
                .executes(context -> collect(context.getSource()))
                .then(Commands.literal("rush")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> rush(context.getSource()))));
    }

    private static int collect(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PostOffice office = PostalService.office();
        if (office == null) return 0;
        int count = office.collect(player);
        if (count == 0) {
            long onTheWay = office.store().inTransit(player.getUUID());
            source.sendFailure(Component.literal(onTheWay == 0 ? "Your mailbox is empty."
                    : "Your mailbox is empty; " + onTheWay + (onTheWay == 1 ? " letter is" : " letters are") + " still on the way."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("You take " + count + (count == 1 ? " letter" : " letters") + " from your mailbox."), false);
        return count;
    }

    private static int rush(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PostOffice office = PostalService.office();
        if (office == null) return 0;
        int count = office.rush(player.getUUID());
        source.sendSuccess(() -> Component.literal(count + (count == 1 ? " letter arrives" : " letters arrive")
                + " within a few seconds."), false);
        return count;
    }
}
