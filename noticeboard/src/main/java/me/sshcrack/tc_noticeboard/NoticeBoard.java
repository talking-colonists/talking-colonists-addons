package me.sshcrack.tc_noticeboard;

import me.sshcrack.mc_talking.api.ApiFeature;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
/*? if forge {*/
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
*//*?}*/
/*? if neoforge {*/
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
/*?}*/
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Mod entry point: lecterns as notice boards, and bells as the colony's town crier. */
@Mod(NoticeBoard.MOD_ID)
public class NoticeBoard {
    public static final String MOD_ID = /*$ mod_id*/ "tc_noticeboard";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final boolean SELF_TEST = Boolean.getBoolean("tc_noticeboard.selftest");

    private record PendingPost(ServerPlayer player, ServerLevel level, BlockPos pos) {
    }

    private static final List<PendingPost> PENDING = new ArrayList<>();
    private static @Nullable Board board;
    private static @Nullable MinecraftServer server;

    public NoticeBoard() {
        if (!TalkingColonistsApi.isAvailable() || !TalkingColonistsApi.supports(ApiFeature.BROADCAST_PUBLISHING)
                || !TalkingColonistsApi.supports(ApiFeature.TEXT_GENERATION)) {
            LOGGER.warn("Notice Board needs Talking Colonists 2.1 or newer (broadcasts, text generation); it stays inactive");
            return;
        }
        /*? if forge {*/
        /*var bus = MinecraftForge.EVENT_BUS;
        bus.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) tick();
        });
        *//*?}*/
        /*? if neoforge {*/
        var bus = NeoForge.EVENT_BUS;
        bus.addListener((ServerTickEvent.Post event) -> tick());
        /*?}*/
        bus.addListener((ServerStartedEvent event) -> {
            server = event.getServer();
            board = new Board(event.getServer(), event.getServer().getWorldPath(LevelResource.ROOT).resolve("data")
                    .resolve(MOD_ID + ".json").normalize());
            board.load();
        });
        bus.addListener((ServerStoppingEvent event) -> {
            if (board != null) board.save();
            board = null;
            server = null;
            PENDING.clear();
        });
        bus.addListener(NoticeBoard::onRightClickBlock);
        bus.addListener((RegisterCommandsEvent event) -> NoticeCommands.register(event.getDispatcher()));
    }

    /**
     * A signed book going onto an empty lectern: once vanilla placed it (next tick), post it.
     * A bell rung with a signed book in hand: the whole colony hears the book.
     */
    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) return;
        if (!event.getItemStack().is(Items.WRITTEN_BOOK)) return;
        BlockState state = level.getBlockState(event.getPos());
        if (state.getBlock() instanceof BellBlock) {
            if (board != null) board.ringBell(player, level, event.getPos(), event.getItemStack());
            return;
        }
        if (!(state.getBlock() instanceof LecternBlock) || state.getValue(LecternBlock.HAS_BOOK)) return;
        PENDING.add(new PendingPost(player, level, event.getPos().immutable()));
    }

    /** The running board, or null while no server runs. */
    public static @Nullable Board board() {
        return board;
    }

    private static void tick() {
        if (board == null) return;
        for (PendingPost post : List.copyOf(PENDING)) {
            board.post(post.player(), post.level(), post.pos());
        }
        PENDING.clear();
        board.tick();
        // Only referenced when enabled, so the class (left out of the release jar) is never loaded otherwise.
        if (SELF_TEST && server != null) me.sshcrack.tc_noticeboard.dev.DevSelfTest.tick(server);
    }
}
