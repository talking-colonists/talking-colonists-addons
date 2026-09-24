package me.sshcrack.tc_postal.dev;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.authlib.GameProfile;
import me.sshcrack.tc_postal.PostOffice;
import me.sshcrack.tc_postal.PostStore;
import me.sshcrack.tc_postal.PostalService;
import me.sshcrack.tc_postal.shared.book.WrittenBooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
/*? if neoforge {*/
import net.neoforged.neoforge.common.util.FakePlayerFactory;
/*?}*/
/*? if forge {*/
/*import net.minecraftforge.common.util.FakePlayerFactory;
*//*?}*/

import java.util.List;
import java.util.UUID;

/**
 * Dev-only end-to-end check, run by the {@code selfTestServer} Gradle run
 * ({@code -Dtc_postal.selftest=true}); excluded from the release jar. It creates a colony with two
 * citizens, hands one of them a letter addressed to the other (no courier: anyone may carry it),
 * rushes it, and checks that a real reply written through Talking Colonists is carried back to the
 * sender as a book. Logs {@code TC_POSTAL_SELFTEST_SUCCESS} or
 * {@code TC_POSTAL_SELFTEST_FAIL} and stops the server.
 */
public final class DevSelfTest {
    private static final GameProfile SENDER = new GameProfile(UUID.fromString("74635f70-6f73-7461-6c73-656c66746573"), "postal_selftest");
    private static final int START_TICK = 60;
    private static final int TIMEOUT_TICKS = 20 * 60 * 4;

    private static int ticks;
    private static boolean done;
    private static boolean carrying;
    private static ServerPlayer sender;

    private DevSelfTest() {
    }

    public static void tick(MinecraftServer server) {
        if (done) return;
        ticks++;
        try {
            if (ticks == START_TICK) start(server);
            else if (ticks > START_TICK && ticks % 20 == 0) check(server);
            if (ticks > TIMEOUT_TICKS) fail(server, "timed out waiting for the reply");
        } catch (RuntimeException | Error e) {
            PostalService.LOGGER.error("Self-test crashed", e);
            fail(server, e.toString());
        }
    }

    private static void start(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos center = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level.getSharedSpawnPos());
        sender = FakePlayerFactory.get(level, SENDER);
        IColony colony = IColonyManager.getInstance().createColony(level, center, sender, "Selftest Letters", "Colonial");
        require(colony != null, "MineColonies created the colony");
        ICitizenData carrier = colony.getCitizenManager().createAndRegisterCivilianData();
        colony.getCitizenManager().spawnOrCreateCivilian(carrier, level, List.of(center.offset(2, 1, 0)), true);
        ICitizenData recipient = colony.getCitizenManager().createAndRegisterCivilianData();
        colony.getCitizenManager().spawnOrCreateCivilian(recipient, level, List.of(center.offset(-2, 1, 0)), true);
        AbstractEntityCitizen carrierEntity = carrier.getEntity().orElseThrow();
        // The sender waits a few blocks away for the reply to be brought.
        sender.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 4.5);

        PostOffice office = PostalService.office();
        require(office != null, "post office running");
        ItemStack unknown = WrittenBooks.create("Nobody Atall", "postal_selftest", WrittenBooks.ORIGINAL,
                List.of(Component.literal("Hello?")));
        require(!office.accept(sender, carrierEntity, unknown) && unknown.getCount() == 1, "unknown recipients are refused");

        String firstName = recipient.getName().split(" ")[0];
        ItemStack letter = WrittenBooks.create("Dear " + firstName, "postal_selftest", WrittenBooks.ORIGINAL, List.of(
                Component.literal("Hello " + firstName + "! I am new here. What do you like most about living in "
                        + colony.getName() + ", and what should I build next for everyone?")));
        require(letter.is(Items.WRITTEN_BOOK), "letter is a written book");
        require(office.accept(sender, carrierEntity, letter), "the carrier accepts the letter");
        require(letter.isEmpty(), "the letter was taken");
        require(office.store().inTransit(sender.getUUID()) == 1, "one letter on its way");
        PostalService.LOGGER.info("TC_POSTAL_SELFTEST: letter to {} is on its way", recipient.getName());
        office.rush(sender.getUUID());
    }

    private static void check(MinecraftServer server) {
        PostOffice office = PostalService.office();
        if (office == null || sender == null) return;
        List<PostStore.Mail> box = office.store().mailbox(sender.getUUID());
        if (box.isEmpty() && !carrying) return;
        PostStore.Mail mail = box.isEmpty() ? null : box.get(0);
        if (mail != null && !carrying) {
            require(!mail.from().equals("Post office"), "the letter was answered, not returned: " + mail.pages());
            PostalService.LOGGER.info("TC_POSTAL_SELFTEST: reply \"{}\" from {}: {}", mail.title(), mail.from(), String.join(" ", mail.pages()));
        }
        if (!carrying) {
            require(office.bringMail(sender), "a citizen sets off with the reply");
            carrying = true;
            PostalService.LOGGER.info("TC_POSTAL_SELFTEST: a citizen brings the reply...");
            return;
        }
        if (sender.getInventory().countItem(Items.WRITTEN_BOOK) == 0) {
            // bringMail is what the post office's regular check calls for real players; it is a no-op while a citizen is on the way.
            office.bringMail(sender);
            return;
        }
        require(office.store().mailbox(sender.getUUID()).isEmpty(), "the mailbox is empty afterwards");
        done = true;
        PostalService.LOGGER.info("TC_POSTAL_SELFTEST_SUCCESS");
        server.halt(false);
    }

    private static void fail(MinecraftServer server, String reason) {
        if (done) return;
        done = true;
        PostalService.LOGGER.error("TC_POSTAL_SELFTEST_FAIL: {}", reason);
        server.halt(false);
    }

    private static void require(boolean condition, String what) {
        if (!condition) throw new IllegalStateException("check failed: " + what);
    }
}
