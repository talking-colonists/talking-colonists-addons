package me.sshcrack.tc_noticeboard.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
/*? if neoforge {*/
import net.minecraft.core.HolderLookup;
/*?}*/

import java.util.ArrayList;
import java.util.List;

/**
 * What is pinned to a notice board: the player's notice and the citizens' replies, and how far the word
 * has spread. Saved with the block and synced to clients for the board's window and model.
 */
public class NoticeBoardBlockEntity extends BlockEntity {
    /** Block actions sent by the notice board window. */
    public static final String POST = "notice_post";
    public static final String ANNOUNCE = "notice_announce";
    public static final String TAKE_DOWN = "notice_take_down";
    /** Replies beyond this are not pinned. */
    public static final int MAX_REPLIES = 6;

    /** A citizen's reply: who wrote it, their job (may be blank) and the text. */
    public record Reply(String writer, String role, String text) {
    }

    private String noticeId = "";
    private String title = "";
    private String body = "";
    private String poster = "";
    private int day;
    private String reach = "";
    private final List<Reply> replies = new ArrayList<>();

    public NoticeBoardBlockEntity(BlockPos pos, BlockState state) {
        super(NoticeBoardBlocks.NOTICE_BOARD_ENTITY.get(), pos, state);
    }

    public boolean hasNotice() {
        return !noticeId.isEmpty();
    }

    public boolean shows(String id) {
        return noticeId.equals(id);
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public String poster() {
        return poster;
    }

    public int day() {
        return day;
    }

    public String reach() {
        return reach;
    }

    public List<Reply> replies() {
        return List.copyOf(replies);
    }

    /** Pins a new notice, replacing the old one and its replies. */
    public void pin(String id, String title, String body, String poster, int day) {
        this.noticeId = id;
        this.title = title;
        this.body = body;
        this.poster = poster;
        this.day = day;
        this.reach = "";
        replies.clear();
        changed();
    }

    public void addReply(Reply reply) {
        if (replies.size() >= MAX_REPLIES) return;
        replies.add(reply);
        changed();
    }

    public void setReach(String reach) {
        if (this.reach.equals(reach)) return;
        this.reach = reach;
        changed();
    }

    public void takeDown() {
        pin("", "", "", "", 0);
    }

    /** How full the board looks: 0 empty, 1 a notice with at most one reply, 2 more. */
    static int sheets(boolean notice, int replies) {
        if (!notice) return 0;
        return replies <= 1 ? 1 : 2;
    }

    /** Saves, updates the notes shown on the block, and syncs to watching clients. */
    private void changed() {
        setChanged();
        if (level == null || level.isClientSide) return;
        BlockState state = getBlockState();
        int sheets = sheets(hasNotice(), replies.size());
        if (state.hasProperty(NoticeBoardBlock.SHEETS) && state.getValue(NoticeBoardBlock.SHEETS) != sheets) {
            level.setBlock(worldPosition, state.setValue(NoticeBoardBlock.SHEETS, sheets), 3);
        }
        level.sendBlockUpdated(worldPosition, state, getBlockState(), 3);
    }

    private void write(CompoundTag tag) {
        tag.putString("notice", noticeId);
        tag.putString("title", title);
        tag.putString("body", body);
        tag.putString("poster", poster);
        tag.putInt("day", day);
        tag.putString("reach", reach);
        ListTag list = new ListTag();
        for (Reply reply : replies) {
            CompoundTag entry = new CompoundTag();
            entry.putString("writer", reply.writer());
            entry.putString("role", reply.role());
            entry.putString("text", reply.text());
            list.add(entry);
        }
        tag.put("replies", list);
    }

    private void read(CompoundTag tag) {
        noticeId = tag.getString("notice");
        title = tag.getString("title");
        body = tag.getString("body");
        poster = tag.getString("poster");
        day = tag.getInt("day");
        reach = tag.getString("reach");
        replies.clear();
        ListTag list = tag.getList("replies", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && replies.size() < MAX_REPLIES; i++) {
            CompoundTag entry = list.getCompound(i);
            replies.add(new Reply(entry.getString("writer"), entry.getString("role"), entry.getString("text")));
        }
    }

    /*? if neoforge {*/
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        write(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        read(tag);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }
    /*?}*/

    /*? if forge {*/
    /*@Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        write(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        read(tag);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }
    *//*?}*/

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
