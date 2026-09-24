package me.sshcrack.tc_noticeboard.dev;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.authlib.GameProfile;
import me.sshcrack.tc_noticeboard.Board;
import me.sshcrack.tc_noticeboard.NoticeBoard;
import me.sshcrack.tc_noticeboard.shared.book.BookText;
import me.sshcrack.tc_noticeboard.shared.book.WrittenBooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
 * ({@code -Dtc_noticeboard.selftest=true}); excluded from the release jar. It creates a colony with three
 * citizens around a lectern, posts a notice, rushes the replies (real Talking Colonists text generation),
 * and checks that they were pinned into the book; then it rings the town bell with a book. Logs
 * {@code TC_NOTICEBOARD_SELFTEST_SUCCESS} or {@code TC_NOTICEBOARD_SELFTEST_FAIL} and stops the server.
 */
public final class DevSelfTest {
    private static final GameProfile POSTER = new GameProfile(UUID.fromString("74635f6e-6f74-6963-6573-656c66746573"), "notice_selftest");
    private static final int START_TICK = 60;
    private static final int TIMEOUT_TICKS = 20 * 60 * 5;

    private static int ticks;
    private static boolean done;
    private static int pagesBefore;
    private static ServerLevel level;
    private static BlockPos lecternPos;
    private static IColony colony;
    private static ServerPlayer poster;

    private DevSelfTest() {
    }

    public static void tick(MinecraftServer server) {
        if (done) return;
        ticks++;
        try {
            if (ticks == START_TICK) start(server);
            else if (ticks > START_TICK && ticks % 20 == 0) check(server);
            if (ticks > TIMEOUT_TICKS) fail(server, "timed out waiting for replies");
        } catch (RuntimeException | Error e) {
            NoticeBoard.LOGGER.error("Self-test crashed", e);
            fail(server, e.toString());
        }
    }

    private static void start(MinecraftServer server) {
        level = server.overworld();
        BlockPos center = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level.getSharedSpawnPos());
        poster = FakePlayerFactory.get(level, POSTER);
        colony = IColonyManager.getInstance().createColony(level, center, poster, "Selftest Square", "Colonial");
        require(colony != null, "MineColonies created the colony");
        for (int i = 0; i < 3; i++) {
            ICitizenData data = colony.getCitizenManager().createAndRegisterCivilianData();
            colony.getCitizenManager().spawnOrCreateCivilian(data, level, List.of(center.offset(-1 + i, 1, 3)), true);
        }
        lecternPos = center.offset(0, 0, 5);
        level.setBlock(lecternPos, Blocks.LECTERN.defaultBlockState(), 3);
        ItemStack notice = WrittenBooks.create("Harvest fair", "notice_selftest", WrittenBooks.ORIGINAL, List.of(
                Component.literal("Next Sunday we hold a harvest fair at the town hall. Everyone brings their best "
                        + "pumpkins; the finest wins a golden hoe. Who wants to help build the stalls?")));
        BlockState state = level.getBlockState(lecternPos);
        require(LecternBlock.tryPlaceBook(poster, level, lecternPos, state, notice), "the book is on the lectern");
        pagesBefore = pages();

        Board board = NoticeBoard.board();
        require(board != null, "board running");
        require(board.post(poster, level, lecternPos), "the notice was posted");
        require(board.notices().size() == 1, "one notice waits for replies");
        board.rush();
        NoticeBoard.LOGGER.info("TC_NOTICEBOARD_SELFTEST: notice posted, {} page(s)", pagesBefore);
    }

    private static int pages() {
        LecternBlockEntity lectern = (LecternBlockEntity) level.getBlockEntity(lecternPos);
        BookText text = BookText.read(lectern.getBook());
        return text == null ? -1 : text.pages().size();
    }

    private static void check(MinecraftServer server) {
        Board board = NoticeBoard.board();
        if (board == null || !board.notices().isEmpty()) return;
        LecternBlockEntity lectern = (LecternBlockEntity) level.getBlockEntity(lecternPos);
        BookText text = BookText.read(lectern.getBook());
        require(text != null && text.pages().size() > pagesBefore, "replies were pinned into the book");
        for (String page : text.pages().subList(pagesBefore, text.pages().size())) {
            NoticeBoard.LOGGER.info("TC_NOTICEBOARD_SELFTEST: pinned: {}", page.replace('\n', ' '));
        }
        BlockPos bell = lecternPos.offset(2, 0, 0);
        level.setBlock(bell, Blocks.BELL.defaultBlockState(), 3);
        ItemStack crier = WrittenBooks.create("Fair today", "notice_selftest", WrittenBooks.ORIGINAL,
                List.of(Component.literal("The fair starts at noon, see you all there!")));
        boolean rang = board.ringBell(poster, level, bell, crier);
        NoticeBoard.LOGGER.info("TC_NOTICEBOARD_SELFTEST: town bell announced: {}", rang);
        require(rang, "the town bell announces the book");
        done = true;
        NoticeBoard.LOGGER.info("TC_NOTICEBOARD_SELFTEST_SUCCESS");
        server.halt(false);
    }

    private static void fail(MinecraftServer server, String reason) {
        if (done) return;
        done = true;
        NoticeBoard.LOGGER.error("TC_NOTICEBOARD_SELFTEST_FAIL: {}", reason);
        server.halt(false);
    }

    private static void require(boolean condition, String what) {
        if (!condition) throw new IllegalStateException("check failed: " + what);
    }
}
