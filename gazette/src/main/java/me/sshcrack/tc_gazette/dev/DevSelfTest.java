package me.sshcrack.tc_gazette.dev;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.authlib.GameProfile;
import me.sshcrack.mc_talking.api.colony.AddonColonyEvent;
import me.sshcrack.mc_talking.api.colony.ColonyEventService;
import me.sshcrack.tc_gazette.ColonyGazette;
import me.sshcrack.tc_gazette.GazetteBook;
import me.sshcrack.tc_gazette.GazetteIssue;
import me.sshcrack.tc_gazette.GazettePublisher;
import me.sshcrack.tc_gazette.GazetteStore;
import me.sshcrack.tc_gazette.shared.book.WrittenBooks;
import net.minecraft.core.BlockPos;
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
 * ({@code -Dtc_gazette.selftest=true}); excluded from the release jar. It creates a colony, records
 * events, writes one real issue through Talking Colonists, hands out a copy with {@code /gazette},
 * logs {@code TC_GAZETTE_SELFTEST_SUCCESS} or {@code TC_GAZETTE_SELFTEST_FAIL}, and stops the server.
 * Without a Gemini API key in the Talking Colonists config the issue cannot be written and the test fails.
 */
public final class DevSelfTest {
    private static final GameProfile OWNER = new GameProfile(UUID.fromString("74635f67-617a-6574-7465-73656c667465"), "gazette_selftest");
    private static final int START_TICK = 40;
    private static final int TIMEOUT_TICKS = 20 * 240;

    private static int ticks;
    private static boolean done;
    private static IColony colony;
    private static ServerPlayer owner;

    private DevSelfTest() {
    }

    public static void tick(MinecraftServer server) {
        if (done) return;
        ticks++;
        try {
            if (ticks == START_TICK) start(server);
            else if (ticks > TIMEOUT_TICKS) fail(server, "timed out waiting for the issue");
        } catch (RuntimeException | Error e) {
            ColonyGazette.LOGGER.error("Self-test crashed", e);
            fail(server, e.toString());
        }
    }

    private static void start(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos center = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn);
        owner = FakePlayerFactory.get(level, OWNER);
        colony = IColonyManager.getInstance().createColony(level, center, owner, "Selftest Hollow", "Colonial");
        require(colony != null, "MineColonies created the colony");

        require(ColonyEventService.record(colony, new AddonColonyEvent("tc_gazette_selftest", "raid",
                "Three raiders attacked the east wall at night; the guards drove them off and nobody was hurt")), "event recorded");
        require(ColonyEventService.record(colony, new AddonColonyEvent("tc_gazette_selftest", "harvest",
                "The farmers brought in a record pumpkin harvest")), "event recorded");
        require(ColonyEventService.record(colony, new AddonColonyEvent(GazettePublisher.NS, "issue_published",
                "An older gazette came out")), "own event recorded");

        long now = level.getGameTime();
        List<String> news = GazettePublisher.newsSince(colony, now - 24_000, now);
        require(news.size() == 2, "own events are not news: " + news);
        require(news.get(0).startsWith("Three raiders") && news.get(1).startsWith("The farmers"), "news is oldest first: " + news);

        GazettePublisher publisher = ColonyGazette.publisher();
        require(publisher != null, "publisher running");
        require(publisher.publishNow(colony, message -> finish(server, message.getString())), "publish started");
        require(!publisher.publishNow(colony, message -> {
        }), "a second publish waits for the first");
        ColonyGazette.LOGGER.info("TC_GAZETTE_SELFTEST: writing issue...");
    }

    private static void finish(MinecraftServer server, String feedback) {
        if (done) return;
        try {
            GazetteStore.ColonyState state = ColonyGazette.publisher().store().get(GazettePublisher.key(colony));
            require(state != null && state.latest != null, "issue written (" + feedback + ")");
            GazetteIssue issue = state.latest;
            require(issue.number() == 1, "first issue is No. 1");

            ItemStack book = GazetteBook.create(issue, WrittenBooks.ORIGINAL);
            require(book.is(Items.WRITTEN_BOOK), "book item");

            var dispatcher = server.getCommands().getDispatcher();
            int first = dispatcher.execute("gazette", owner.createCommandSourceStack());
            require(first == 1 && owner.getInventory().countItem(Items.WRITTEN_BOOK) == 1, "/gazette hands out a copy");
            int second = dispatcher.execute("gazette", owner.createCommandSourceStack());
            require(second == 0 && owner.getInventory().countItem(Items.WRITTEN_BOOK) == 1, "only one copy per player");

            ColonyGazette.LOGGER.info("TC_GAZETTE_SELFTEST: headline: {}", issue.headline());
            for (GazetteIssue.Article article : issue.articles()) {
                ColonyGazette.LOGGER.info("TC_GAZETTE_SELFTEST: article: {} | {}", article.title(), article.body());
            }
            done = true;
            ColonyGazette.LOGGER.info("TC_GAZETTE_SELFTEST_SUCCESS");
            server.halt(false);
        } catch (Exception | Error e) {
            ColonyGazette.LOGGER.error("Self-test failed", e);
            fail(server, e.toString());
        }
    }

    private static void fail(MinecraftServer server, String reason) {
        if (done) return;
        done = true;
        ColonyGazette.LOGGER.error("TC_GAZETTE_SELFTEST_FAIL: {}", reason);
        server.halt(false);
    }

    private static void require(boolean condition, String what) {
        if (!condition) throw new IllegalStateException("check failed: " + what);
    }
}
