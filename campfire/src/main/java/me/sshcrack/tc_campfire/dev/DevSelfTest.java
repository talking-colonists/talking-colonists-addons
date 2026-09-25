package me.sshcrack.tc_campfire.dev;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.mojang.authlib.GameProfile;
import me.sshcrack.mc_talking.api.colony.ColonyEventService;
import me.sshcrack.tc_campfire.CampfireDirector;
import me.sshcrack.tc_campfire.CampfireNights;
import me.sshcrack.tc_campfire.Gathering;
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
 * checks that at least one story was told and recorded as colony news. It runs at night and checks that the
 * tellers' MineColonies routine never turns to sleep while the story is told (they would walk off to bed), that
 * they sit around the fire, and that they stand up when the night ends. Logs
 * {@code TC_CAMPFIRE_SELFTEST_SUCCESS} or {@code TC_CAMPFIRE_SELFTEST_FAIL} and stops the server.
 */
public final class DevSelfTest {
    private static final GameProfile OWNER = new GameProfile(UUID.fromString("74635f63-616d-7066-6972-6573656c6674"), "campfire_selftest");
    private static final int START_TICK = 40;
    private static final int TIMEOUT_TICKS = 20 * 60 * 9;

    private static int ticks;
    private static boolean done;
    private static IColony colony;
    private static Gathering gathering;
    private static int samples;
    private static String wentToBed = "";
    private static int seated;
    private static int checks;

    private DevSelfTest() {
    }

    public static void tick(MinecraftServer server) {
        if (done) return;
        ticks++;
        try {
            if (ticks == START_TICK) start(server);
            else if (ticks > START_TICK && ticks % 20 == 0) sample();
            else if (ticks > TIMEOUT_TICKS) fail(server, "timed out waiting for the campfire night");
        } catch (RuntimeException | Error e) {
            CampfireNights.LOGGER.error("Self-test crashed", e);
            fail(server, e.toString());
        }
    }

    private static void start(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.setDayTime(13_000); // dusk: MineColonies' routine wants its citizens in bed
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
        gathering = director.start(colony, level, fire, 2, message -> finish(server, message));
        require(gathering != null, "the night starts with the three citizens");
        CampfireNights.LOGGER.info("TC_CAMPFIRE_SELFTEST: gathering at {}", fire);
    }

    /** While the story is told, no teller's routine may turn to sleep. */
    private static void sample() {
        if (gathering == null || gathering.phase() != Gathering.Phase.TELLING) return;
        for (AbstractEntityCitizen teller : gathering.tellers()) {
            if (teller instanceof EntityCitizen citizen && citizen.getCitizenAI().getState() == CitizenAIState.SLEEP) {
                wentToBed = teller.getName().getString();
            }
            checks++;
            if (teller.getVehicle() != null) seated++;
        }
        samples++;
    }

    private static void finish(MinecraftServer server, String summary) {
        if (done) return;
        CampfireNights.LOGGER.info("TC_CAMPFIRE_SELFTEST: checked the tellers' routine {} times during the story", samples);
        if (!wentToBed.isEmpty()) {
            fail(server, wentToBed + "'s routine turned to sleep during the story");
            return;
        }
        CampfireNights.LOGGER.info("TC_CAMPFIRE_SELFTEST: tellers were seated in {} of {} checks", seated, checks);
        if (seated * 10 < checks * 9) {
            fail(server, "the tellers did not stay seated around the fire");
            return;
        }
        if (gathering.tellers().stream().anyMatch(teller -> teller.getVehicle() != null)) {
            fail(server, "the tellers did not stand up after the night");
            return;
        }
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
