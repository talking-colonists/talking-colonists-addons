package me.sshcrack.tc_gazette;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.sshcrack.tc_gazette.book.WrittenBooks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /gazette}: take a copy of your colony's latest issue (one per issue and player).
 * {@code /gazette publish}: operators write an issue right now.
 */
public final class GazetteCommand {
    private GazetteCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gazette")
                .executes(context -> takeCopy(context.getSource()))
                .then(Commands.literal("publish")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> publish(context.getSource()))));
    }

    private static int takeCopy(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        GazettePublisher publisher = ColonyGazette.publisher();
        IColony colony = colonyOf(player);
        if (publisher == null || colony == null) {
            source.sendFailure(Component.literal("You are not a member of a colony."));
            return 0;
        }
        GazetteStore.ColonyState state = publisher.store().get(GazettePublisher.key(colony));
        if (state == null || state.latest == null) {
            source.sendFailure(Component.literal("The " + colony.getName() + " Gazette has not published an issue yet."));
            return 0;
        }
        if (!player.getAbilities().instabuild && !state.claimed.add(player.getUUID())) {
            source.sendFailure(Component.literal("You already took a copy of issue No. " + state.latest.number() + "."));
            return 0;
        }
        publisher.store().save();
        ItemStack book = GazetteBook.create(state.latest, WrittenBooks.COPY);
        if (!player.getInventory().add(book)) player.drop(book, false);
        int number = state.latest.number();
        source.sendSuccess(() -> Component.literal("You take a copy of gazette No. " + number + "."), false);
        return 1;
    }

    private static int publish(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        GazettePublisher publisher = ColonyGazette.publisher();
        IColony colony = colonyOf(player);
        if (publisher == null || colony == null) {
            source.sendFailure(Component.literal("Stand in, or own, a colony to publish its gazette."));
            return 0;
        }
        if (!publisher.publishNow(colony, message -> source.sendSuccess(() -> message, true))) {
            source.sendFailure(Component.literal("An issue is already being written."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Writing a new issue of the " + colony.getName() + " Gazette..."), true);
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
