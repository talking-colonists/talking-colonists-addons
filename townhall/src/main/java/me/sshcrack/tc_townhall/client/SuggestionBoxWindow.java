package me.sshcrack.tc_townhall.client;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.BOWindow;
import com.ldtteam.blockui.views.ScrollingList;
import me.sshcrack.tc_townhall.TownHall;
import me.sshcrack.tc_townhall.block.SuggestionBoxBlockEntity;
import me.sshcrack.tc_townhall.shared.net.BlockActions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * The suggestion box window, in MineColonies' style: the notes citizens dropped in, each with who
 * wrote it and when, and buttons to take a note along (as a one-page book) or throw it away.
 */
public final class SuggestionBoxWindow extends BOWindow {

    private final BlockPos pos;
    private final ScrollingList list;
    private final Text empty;

    public SuggestionBoxWindow(BlockPos pos) {
        super(layout());
        this.pos = pos;
        this.list = findPaneOfTypeByID("notes", ScrollingList.class);
        this.empty = findPaneOfTypeByID("empty", Text.class);
        findPaneOfTypeByID("close", Button.class).setHandler(button -> close());
        list.setDataProvider(new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                return notes().size();
            }

            @Override
            public void updateElement(int index, Pane row) {
                List<SuggestionBoxBlockEntity.Note> notes = notes();
                if (index >= notes.size()) return;
                SuggestionBoxBlockEntity.Note note = notes.get(index);
                String who = note.role().isBlank() ? note.writer() : note.writer() + ", " + note.role();
                row.findPaneOfTypeByID("writer", Text.class).setText(Component.literal(who));
                row.findPaneOfTypeByID("day", Text.class).setText(Component.translatable("tc_townhall.gui.suggestion_box.day", note.day()));
                row.findPaneOfTypeByID("text", Text.class).setText(Component.literal(note.text()));
                row.findPaneOfTypeByID("take", Button.class).setHandler(button -> BlockActions.send(pos, SuggestionBoxBlockEntity.TAKE, index));
                row.findPaneOfTypeByID("discard", Button.class).setHandler(button -> BlockActions.send(pos, SuggestionBoxBlockEntity.DISCARD, index));
            }
        });
    }

    private static ResourceLocation layout() {
        /*? if neoforge {*/
        return ResourceLocation.fromNamespaceAndPath(TownHall.MOD_ID, "gui/suggestion_box.xml");
        /*?}*/
        /*? if forge {*/
        /*return new ResourceLocation(TownHall.MOD_ID, "gui/suggestion_box.xml");
        *//*?}*/
    }

    /** The notes as synced to this client; empty when the box is gone. */
    private List<SuggestionBoxBlockEntity.Note> notes() {
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getBlockEntity(pos) instanceof SuggestionBoxBlockEntity box)) return List.of();
        return box.notes();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        // Taking or discarding a note syncs the box back; keep the list in step with it.
        list.refreshElementPanes();
        empty.setVisible(notes().isEmpty());
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getBlockEntity(pos) instanceof SuggestionBoxBlockEntity)) close();
    }
}
