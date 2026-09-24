package me.sshcrack.tc_campfire.dev;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.authlib.GameProfile;
import me.sshcrack.mc_talking.api.colony.ColonyEventService;
import me.sshcrack.tc_campfire.CampfireDirector;
import me.sshcrack.tc_campfire.CampfireNights;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
/*? if neoforge {*/
import net.neoforged.neoforge.common.util.FakePlayerFactory;
/*?}*/
/*? if forge {*/
/*import net.minecraftforge.common.util.FakePlayerFactory;
*//*?}*/

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Dev-only end-to-end check, run by the {@code selfTestServer} Gradle run
 * ({@code -Dtc_campfire.selftest=true}); excluded from the release jar. It creates a colony with three
 * citizens and a lit campfire, runs a real campfire night through Talking Colonists (Gemini Live), and
 * checks that at least one story was told and recorded as colony news. Logs
 * {@code TC_CAMPFIRE_SELFTEST_SUCCESS} or {@code TC_CAMPFIRE_SELFTEST_FAIL} and stops the server.
 */
public final class DevSelfTest {
    private static final GameProfile OWNER = new GameProfile(UUID.fromString("74635f63-616d-7066-6972-6573656c6674"), "campfire_selftest");
    private static final int START_TICK = 40;
    private static final int TIMEOUT_TICKS = 20 * 60 * 9;

    private static int ticks;
    private static boolean done;
    private static IColony colony;

    private DevSelfTest() {
    }

    public static void tick(MinecraftServer server) {
        if (done) return;
        ticks++;
        try {
            if (ticks == START_TICK) start(server);
            else if (ticks > TIMEOUT_TICKS) fail(server, "timed out waiting for the campfire night");
        } catch (RuntimeException | Error e) {
            CampfireNights.LOGGER.error("Self-test crashed", e);
            fail(server, e.toString());
        }
    }

    private static void start(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos center = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level.getSharedSpawnPos());
        ServerPlayer owner = FakePlayerFactory.get(level, OWNER);
        colony = IColonyManager.getInstance().createColony(level, center, owner, "Selftest Embers", "Colonial");
        require(colony != null, "MineColonies created the colony");

        BlockPos fire = center.offset(3, 0, 3);
        level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState(), 3);
        require(CampfireDirector.litCampfires(level, fire, 1).contains(fire), "the campfire is found");
        for (int i = 0; i < 3; i++) {
            ICitizenData data = colony.getCitizenManager().createAndRegisterCivilianData();
            colony.getCitizenManager().spawnOrCreateCivilian(data, level, List.of(center.offset(-4 + i, 1, -4)), true);
        }

        CampfireDirector director = CampfireNights.director();
        require(director != null, "director running");
        require(director.start(colony, level, fire, 2, message -> finish(server, message)) != null,
                "the night starts with the three citizens");
        CampfireNights.LOGGER.info("TC_CAMPFIRE_SELFTEST: gathering at {}", fire);
    }

    private static void finish(MinecraftServer server, String summary) {
        if (done) return;
        CampfireNights.LOGGER.info("TC_CAMPFIRE_SELFTEST: {}", summary);
        var news = ColonyEventService.recent(colony, Duration.ofHours(1)).stream()
                .filter(event -> CampfireNights.MOD_ID.equals(event.addonNamespace()))
                .toList();
        if (news.isEmpty()) {
            fail(server, "no story was told: " + summary);
            return;
        }
        CampfireNights.LOGGER.info("TC_CAMPFIRE_SELFTEST: news: {}", news.get(0).description());
        done = true;
        CampfireNights.LOGGER.info("TC_CAMPFIRE_SELFTEST_SUCCESS");
        server.halt(false);
    }

    private static void fail(MinecraftServer server, String reason) {
        if (done) return;
        done = true;
        CampfireNights.LOGGER.error("TC_CAMPFIRE_SELFTEST_FAIL: {}", reason);
        server.halt(false);
    }

    private static void require(boolean condition, String what) {
        if (!condition) throw new IllegalStateException("check failed: " + what);
    }
}
