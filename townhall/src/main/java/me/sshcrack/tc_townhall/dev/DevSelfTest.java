package me.sshcrack.tc_townhall.dev;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.authlib.GameProfile;
import me.sshcrack.tc_townhall.Elections;
import me.sshcrack.tc_townhall.TownHall;
import me.sshcrack.tc_townhall.shared.book.WrittenBooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
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
 * ({@code -Dtc_townhall.selftest=true}); excluded from the release jar. It creates a colony with three
 * citizens, has a player stand for mayor with a book, rushes the campaign so a citizen rival stands
 * (real Talking Colonists text generation), rushes again, and waits for the citizens' votes and the
 * result. Logs {@code TC_TOWNHALL_SELFTEST_SUCCESS} or {@code TC_TOWNHALL_SELFTEST_FAIL} and stops the server.
 */
public final class DevSelfTest {
    private static final GameProfile CANDIDATE = new GameProfile(UUID.fromString("74635f74-6f77-6e68-616c-6c73656c6674"), "mayor_selftest");
    private static final int START_TICK = 60;
    private static final int TIMEOUT_TICKS = 20 * 60 * 6;

    private static int ticks;
    private static boolean done;
    private static boolean rivalRushed;
    private static IColony colony;

    private DevSelfTest() {
    }

    public static void tick(MinecraftServer server) {
        if (done) return;
        ticks++;
        try {
            if (ticks == START_TICK) start(server);
            else if (ticks > START_TICK && ticks % 20 == 0) check(server);
            if (ticks > TIMEOUT_TICKS) fail(server, "timed out waiting for the election");
        } catch (RuntimeException | Error e) {
            TownHall.LOGGER.error("Self-test crashed", e);
            fail(server, e.toString());
        }
    }

    private static void start(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos center = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level.getSharedSpawnPos());
        ServerPlayer candidate = FakePlayerFactory.get(level, CANDIDATE);
        colony = IColonyManager.getInstance().createColony(level, center, candidate, "Selftest Borough", "Colonial");
        require(colony != null, "MineColonies created the colony");
        for (int i = 0; i < 3; i++) {
            ICitizenData data = colony.getCitizenManager().createAndRegisterCivilianData();
            colony.getCitizenManager().spawnOrCreateCivilian(data, level, List.of(center.offset(-1 + i, 1, 3)), true);
        }
        ItemStack book = WrittenBooks.create("Walls before wishes", "mayor_selftest", WrittenBooks.ORIGINAL, List.of(
                Component.literal("I will build a wall around the colony before winter, hire two guards, and open "
                        + "a bakery so nobody goes hungry.")));
        Elections elections = TownHall.elections();
        require(elections != null, "elections running");
        require(elections.stand(candidate, colony, book, center), "the player stands for mayor");
        Elections.Election election = elections.election(colony);
        require(election != null && election.candidates.size() == 1, "one candidate is on the ballot");
        require(!election.candidates.get(0).broadcastIds.isEmpty(), "the citizens heard the candidacy");
        elections.rush();
        TownHall.LOGGER.info("TC_TOWNHALL_SELFTEST: {} stands, campaign rushed", election.candidates.get(0).name);
    }

    private static void check(MinecraftServer server) {
        Elections elections = TownHall.elections();
        if (elections == null) return;
        Elections.Election election = elections.election(colony);
        if (election != null) {
            if (!election.voting && election.candidates.size() == 2 && !rivalRushed) {
                rivalRushed = true;
                Elections.Candidate rival = election.candidates.get(1);
                require(rival.citizen, "the rival is a citizen");
                TownHall.LOGGER.info("TC_TOWNHALL_SELFTEST: rival {} stands: \"{}\" {}", rival.name, rival.slogan, rival.platform);
                elections.rush();
            }
            return;
        }
        require(rivalRushed, "a citizen stood against the lone candidate");
        // Every voter may abstain; then the election ends without a mayor, which is a valid outcome.
        Elections.Mayor mayor = elections.mayor(colony);
        TownHall.LOGGER.info("TC_TOWNHALL_SELFTEST: result: {}", mayor == null ? "nobody was elected" : mayor.result);
        done = true;
        TownHall.LOGGER.info("TC_TOWNHALL_SELFTEST_SUCCESS");
        server.halt(false);
    }

    private static void fail(MinecraftServer server, String reason) {
        if (done) return;
        done = true;
        TownHall.LOGGER.error("TC_TOWNHALL_SELFTEST_FAIL: {}", reason);
        server.halt(false);
    }

    private static void require(boolean condition, String what) {
        if (!condition) throw new IllegalStateException("check failed: " + what);
    }
}
