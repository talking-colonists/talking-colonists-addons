package me.sshcrack.tc_townhall.block;

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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** The notes waiting in a suggestion box, saved with the block and synced to clients for its window. */
public class SuggestionBoxBlockEntity extends BlockEntity {
    /** At most this many notes wait in a box; citizens stop writing until some are taken. */
    /** Block actions sent by the suggestion box window. */
    public static final String TAKE = "suggestion_take";
    public static final String DISCARD = "suggestion_discard";
    public static final int MAX_NOTES = 6;

    /** One note: who wrote it, their job (may be blank), the colony day and the text. */
    public record Note(String writer, String role, int day, String text) {
    }

    /** Loaded boxes on the server, so the morning note-writing can find them. */
    private static final Set<SuggestionBoxBlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    private final List<Note> notes = new ArrayList<>();

    public SuggestionBoxBlockEntity(BlockPos pos, BlockState state) {
        super(TownHallBlocks.SUGGESTION_BOX_ENTITY.get(), pos, state);
    }

    /** Loaded, not removed boxes on the server. */
    public static List<SuggestionBoxBlockEntity> loaded() {
        synchronized (LOADED) {
            LOADED.removeIf(BlockEntity::isRemoved);
            return List.copyOf(LOADED);
        }
    }

    public List<Note> notes() {
        return Collections.unmodifiableList(notes);
    }

    public boolean isFull() {
        return notes.size() >= MAX_NOTES;
    }

    public void add(Note note) {
        if (isFull()) return;
        notes.add(note);
        changed();
    }

    /** Removes and returns the note at {@code index}, or null when there is none. */
    public Note remove(int index) {
        if (index < 0 || index >= notes.size()) return null;
        Note note = notes.remove(index);
        changed();
        return note;
    }

    /** Saves, shows or hides the paper sticking out of the slot, and syncs the notes to watching clients. */
    private void changed() {
        setChanged();
        if (level == null || level.isClientSide) return;
        BlockState state = getBlockState();
        boolean hasNotes = !notes.isEmpty();
        if (state.hasProperty(SuggestionBoxBlock.HAS_NOTES) && state.getValue(SuggestionBoxBlock.HAS_NOTES) != hasNotes) {
            level.setBlock(worldPosition, state.setValue(SuggestionBoxBlock.HAS_NOTES, hasNotes), 3);
        }
        level.sendBlockUpdated(worldPosition, state, getBlockState(), 3);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            synchronized (LOADED) {
                LOADED.add(this);
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        synchronized (LOADED) {
            LOADED.remove(this);
        }
    }

    private void writeNotes(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Note note : notes) {
            CompoundTag entry = new CompoundTag();
            entry.putString("writer", note.writer());
            entry.putString("role", note.role());
            entry.putInt("day", note.day());
            entry.putString("text", note.text());
            list.add(entry);
        }
        tag.put("notes", list);
    }

    private void readNotes(CompoundTag tag) {
        notes.clear();
        ListTag list = tag.getList("notes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && notes.size() < MAX_NOTES; i++) {
            CompoundTag entry = list.getCompound(i);
            notes.add(new Note(entry.getString("writer"), entry.getString("role"), entry.getInt("day"), entry.getString("text")));
        }
    }

    /*? if neoforge {*/
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writeNotes(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        readNotes(tag);
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
        writeNotes(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        readNotes(tag);
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
