package me.sshcrack.tc_townhall.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Keeps no data (the election lives with the colony); it only lets the server find loaded ballot boxes,
 * to show the election phase on them.
 */
public class BallotBoxBlockEntity extends BlockEntity {
    /** Block actions sent by the ballot box window. */
    public static final String VIEW = "ballot_view";
    public static final String STAND = "ballot_stand";
    public static final String SPEAK = "ballot_speak";
    /** The kind of view the server sends back. */
    public static final String VIEW_KIND = "ballot";

    private static final Set<BallotBoxBlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    public BallotBoxBlockEntity(BlockPos pos, BlockState state) {
        super(TownHallBlocks.BALLOT_BOX_ENTITY.get(), pos, state);
    }

    /** Loaded, not removed boxes on the server. */
    public static List<BallotBoxBlockEntity> loaded() {
        synchronized (LOADED) {
            LOADED.removeIf(BlockEntity::isRemoved);
            return List.copyOf(LOADED);
        }
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
}
