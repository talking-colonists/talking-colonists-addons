package me.sshcrack.tc_noticeboard.client;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.controls.TextField;
import com.ldtteam.blockui.views.BOWindow;
import com.ldtteam.blockui.views.ScrollingList;
import me.sshcrack.tc_noticeboard.NoticeBoard;
import me.sshcrack.tc_noticeboard.block.NoticeBoardBlockEntity;
import me.sshcrack.tc_noticeboard.shared.net.BlockActions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The notice board window, in MineColonies' style: the pinned notice with who posted it and how far the
 * word has spread, the citizens' replies, and a form to post a new notice or announce something to the
 * whole colony.
 */
public final class NoticeBoardWindow extends BOWindow {
    private final BlockPos pos;
    private final ScrollingList replies;
    private final TextField titleInput;
    private final TextField bodyInput;

    public NoticeBoardWindow(BlockPos pos) {
        super(layout());
        this.pos = pos;
        this.replies = findPaneOfTypeByID("replies", ScrollingList.class);
        this.titleInput = findPaneOfTypeByID("titleInput", TextField.class);
        this.bodyInput = findPaneOfTypeByID("bodyInput", TextField.class);
        findPaneOfTypeByID("close", Button.class).setHandler(button -> close());
        findPaneOfTypeByID("post", Button.class).setHandler(button -> send(NoticeBoardBlockEntity.POST));
        findPaneOfTypeByID("announce", Button.class).setHandler(button -> send(NoticeBoardBlockEntity.ANNOUNCE));
        findPaneOfTypeByID("takeDown", Button.class).setHandler(button -> BlockActions.send(pos, NoticeBoardBlockEntity.TAKE_DOWN, 0));
        replies.setDataProvider(new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                NoticeBoardBlockEntity board = board();
                return board == null ? 0 : board.replies().size();
            }

            @Override
            public void updateElement(int index, Pane row) {
                NoticeBoardBlockEntity board = board();
                List<NoticeBoardBlockEntity.Reply> list = board == null ? List.of() : board.replies();
                if (index >= list.size()) return;
                NoticeBoardBlockEntity.Reply reply = list.get(index);
                String who = reply.role().isBlank() ? reply.writer() : reply.writer() + ", " + reply.role();
                row.findPaneOfTypeByID("who", Text.class).setText(Component.literal(who));
                row.findPaneOfTypeByID("text", Text.class).setText(Component.literal(reply.text()));
            }
        });
        render();
    }

    private static ResourceLocation layout() {
        /*? if neoforge {*/
        return ResourceLocation.fromNamespaceAndPath(NoticeBoard.MOD_ID, "gui/notice_board.xml");
        /*?}*/
        /*? if forge {*/
        /*return new ResourceLocation(NoticeBoard.MOD_ID, "gui/notice_board.xml");
        *//*?}*/
    }

    private void send(String action) {
        String title = titleInput.getText().strip();
        String body = bodyInput.getText().strip();
        if (title.isEmpty() || body.isEmpty()) return;
        BlockActions.send(pos, action, 0, title + "\n" + body);
        titleInput.setText("");
        bodyInput.setText("");
    }

    private @Nullable NoticeBoardBlockEntity board() {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(pos) instanceof NoticeBoardBlockEntity board ? board : null;
    }

    private void render() {
        NoticeBoardBlockEntity board = board();
        boolean notice = board != null && board.hasNotice();
        findPaneByID("empty").setVisible(!notice);
        for (String id : new String[] {"noticeTitle", "byline", "noticeBody", "reach", "takeDown"}) {
            findPaneByID(id).setVisible(notice);
        }
        findPaneByID("noReplies").setVisible(notice && board.replies().isEmpty());
        if (notice) {
            text("noticeTitle").setText(Component.literal(board.title()));
            text("byline").setText(Component.translatable("tc_noticeboard.gui.byline", board.poster(), board.day()));
            text("noticeBody").setText(Component.literal(board.body()));
            text("reach").setText(Component.literal(board.reach()));
        }
        replies.refreshElementPanes();
    }

    private Text text(String id) {
        return findPaneOfTypeByID(id, Text.class);
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (board() == null) {
            close();
            return;
        }
        render();
    }
}
