package me.sshcrack.tc_tavern.dev;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IVisitorData;
import com.minecolonies.api.entity.ModEntities;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.authlib.GameProfile;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationService;
import me.sshcrack.mc_talking.api.conversation.ControlledConversationOptions;
import me.sshcrack.mc_talking.api.conversation.ControlledConversationSession;
import me.sshcrack.mc_talking.api.conversation.ControlledTurnResult;
import me.sshcrack.tc_tavern.TavernRecruiter;
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
import java.util.Set;
import java.util.UUID;

/**
 * Dev-only end-to-end check, run by the {@code selfTestServer} Gradle run
 * ({@code -Dtc_tavern.selftest=true}); excluded from the release jar. It creates a colony and a visitor
 * who costs 8 diamonds, then a fake colony member makes a friendly offer in a controlled Gemini Live
 * turn that may use the negotiation tool. Passes when the visitor lowered the price (at most to 4).
 * Logs {@code TC_TAVERN_SELFTEST_SUCCESS} or {@code TC_TAVERN_SELFTEST_FAIL} and stops the server.
 */
public final class DevSelfTest {
    private static final GameProfile PLAYER = new GameProfile(UUID.fromString("74635f74-6176-6572-6e73-656c66746573"), "tavern_selftest");
    private static final int START_TICK = 60;
    private static final int TIMEOUT_TICKS = 20 * 60 * 5;
    private static final int ORIGINAL = 8;
    private static final String[] OFFERS = {
            "Hello! Our colony really needs a baker, and everyone says your bread is the best in the land. "
                    + "We are a young colony and 8 diamonds is everything we have. Would you join us for 4 diamonds? "
                    + "You would get your own house next to the bakery.",
            "Please, I promise we will treat you like family here. Could you lower your price a little? "
                    + "Even 6 diamonds would help us a lot."};

    private static int ticks;
    private static boolean done;
    private static int turn;
    private static IVisitorData visitor;
    private static ControlledConversationSession session;

    private DevSelfTest() {
    }

    public static void tick(MinecraftServer server) {
        if (done) return;
        ticks++;
        try {
            if (ticks == START_TICK) start(server);
            else if (ticks > TIMEOUT_TICKS) fail(server, "timed out");
        } catch (RuntimeException | Error e) {
            TavernRecruiter.LOGGER.error("Self-test crashed", e);
            fail(server, e.toString());
        }
    }

    private static void start(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos center = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level.getSharedSpawnPos());
        ServerPlayer player = FakePlayerFactory.get(level, PLAYER);
        IColony colony = IColonyManager.getInstance().createColony(level, center, player, "Selftest Tavern", "Colonial");
        require(colony != null, "MineColonies created the colony");
        visitor = (IVisitorData) colony.getVisitorManager().createAndRegisterCivilianData();
        visitor.setRecruitCosts(new ItemStack(Items.DIAMOND, ORIGINAL));
        // Without a tavern MineColonies does not spawn visitors itself; place one the way its GameTests do.
        AbstractEntityCitizen entity = ModEntities.VISITOR.create(level);
        require(entity != null, "visitor entity created");
        BlockPos pos = center.offset(2, 1, 2);
        entity.setUUID(visitor.getUUID());
        entity.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        entity.setCitizenId(visitor.getId());
        entity.getCitizenColonyHandler().setColonyId(colony.getID());
        entity.setNoAi(true);
        level.addFreshEntity(entity);
        entity.getCitizenColonyHandler().registerWithColony(colony.getID(), visitor.getId());
        require(entity.getCitizenData() == visitor, "the visitor entity is bound to its data");
        require(TavernRecruiter.store() != null, "haggle store running");

        String tool = TavernRecruiter.MOD_ID + ":negotiate_recruit_cost";
        session = CitizenConversationService.createControlledSession(server, List.of(entity),
                "A colonist talks to you at the tavern about joining the colony.",
                ControlledConversationOptions.allowAddonTools(Set.of(tool)));
        offer(server, player, entity);
    }

    private static void offer(MinecraftServer server, ServerPlayer player, AbstractEntityCitizen entity) {
        session.addPlayerStatement(player, OFFERS[turn]);
        TavernRecruiter.LOGGER.info("TC_TAVERN_SELFTEST: offer {}: {}", turn + 1, OFFERS[turn]);
        session.requestTurn(entity, "Answer the colonist in character. Decide whether to lower your price.")
                .whenComplete((result, error) -> server.execute(() -> answered(server, player, entity, result, error)));
    }

    private static void answered(MinecraftServer server, ServerPlayer player, AbstractEntityCitizen entity,
                                 ControlledTurnResult result, Throwable error) {
        if (done) return;
        if (error != null || result == null || !result.completed()) {
            fail(server, "turn did not complete: " + (error != null ? error : result));
            return;
        }
        TavernRecruiter.LOGGER.info("TC_TAVERN_SELFTEST: visitor said: {}", result.transcript());
        int price = visitor.getRecruitCost().getCount();
        TavernRecruiter.LOGGER.info("TC_TAVERN_SELFTEST: price is now {} diamonds", price);
        if (price < ORIGINAL) {
            require(price >= ORIGINAL / 2, "the price never drops below half");
            done = true;
            session.end();
            TavernRecruiter.LOGGER.info("TC_TAVERN_SELFTEST_SUCCESS");
            server.halt(false);
            return;
        }
        if (++turn < OFFERS.length) {
            offer(server, player, entity);
        } else {
            session.end();
            fail(server, "the visitor never lowered the price");
        }
    }

    private static void fail(MinecraftServer server, String reason) {
        if (done) return;
        done = true;
        TavernRecruiter.LOGGER.error("TC_TAVERN_SELFTEST_FAIL: {}", reason);
        server.halt(false);
    }

    private static void require(boolean condition, String what) {
        if (!condition) throw new IllegalStateException("check failed: " + what);
    }
}
