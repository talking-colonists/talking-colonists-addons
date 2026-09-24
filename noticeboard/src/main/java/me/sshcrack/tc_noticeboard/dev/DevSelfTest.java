package me.sshcrack.tc_noticeboard.dev;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.authlib.GameProfile;
import me.sshcrack.tc_noticeboard.Board;
import me.sshcrack.tc_noticeboard.NoticeBoard;
import me.sshcrack.tc_noticeboard.block.NoticeBoardBlock;
import me.sshcrack.tc_noticeboard.block.NoticeBoardBlockEntity;
import me.sshcrack.tc_noticeboard.block.NoticeBoardBlocks;
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
import net.minecraft.world.inventory.LecternMenu;

/**
 * Dev-only end-to-end check, run by the {@code selfTestServer} Gradle run
 * ({@code -Dtc_noticeboard.selftest=true}); excluded from the release jar. It creates a colony with three
 * citizens around a lectern, posts a notice, rushes the replies (real Talking Colonists text generation),
 * and checks that they were pinned into the book; then it rings the town bell with a book. Then it posts
 * a notice on a Notice Board block and checks the replies are pinned on it, and that it can be taken down. Logs
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
    private static BlockPos boardPos;

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
        if (boardPos != null) {
            checkBoard(server, board);
            return;
        }
        LecternBlockEntity lectern = (LecternBlockEntity) level.getBlockEntity(lecternPos);
        BookText text = BookText.read(lectern.getBook());
        require(text != null && text.pages().size() > pagesBefore, "replies were pinned into the book");
        // Turn the page the way a reader does: the lectern menu's "next page" button.
        var menu = (LecternMenu) lectern.createMenu(0, poster.getInventory(), poster);
        menu.clickMenuButton(poster, 2);
        require(lectern.getPage() == 1, "readers can turn to the pinned replies on the lectern");
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

        boardPos = lecternPos.offset(-2, 0, 0);
        level.setBlock(boardPos, NoticeBoardBlocks.NOTICE_BOARD.get().defaultBlockState(), 3);
        require(board.postOnBoard(poster, level, boardPos, "Well needed",
                "We should dig a well by the fields so the farmers stop walking to the river. Who can help?"), "the notice was posted on the board");
        NoticeBoardBlockEntity entity = (NoticeBoardBlockEntity) level.getBlockEntity(boardPos);
        require(entity.hasNotice() && level.getBlockState(boardPos).getValue(NoticeBoardBlock.SHEETS) == 1,
                "the board shows the notice");
        board.rush();
        NoticeBoard.LOGGER.info("TC_NOTICEBOARD_SELFTEST: notice pinned on the board");
    }

    private static void checkBoard(MinecraftServer server, Board board) {
        NoticeBoardBlockEntity entity = (NoticeBoardBlockEntity) level.getBlockEntity(boardPos);
        require(!entity.replies().isEmpty(), "replies were pinned on the board");
        int sheets = level.getBlockState(boardPos).getValue(NoticeBoardBlock.SHEETS);
        require(sheets == (entity.replies().size() <= 1 ? 1 : 2), "the board shows its replies");
        for (NoticeBoardBlockEntity.Reply reply : entity.replies()) {
            NoticeBoard.LOGGER.info("TC_NOTICEBOARD_SELFTEST: on the board: {} ({}): {}", reply.writer(), reply.role(), reply.text());
        }
        NoticeBoard.LOGGER.info("TC_NOTICEBOARD_SELFTEST: reach: {}", entity.reach());
        board.takeDown(poster, level, boardPos);
        require(!entity.hasNotice() && level.getBlockState(boardPos).getValue(NoticeBoardBlock.SHEETS) == 0, "the notice can be taken down");
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
