package me.sshcrack.tc_playtest;

import com.ldtteam.structurize.management.Manager;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

/** Runs Structurize's queued pastes to the end right away (it places a few blocks per tick otherwise). */
final class PlaytestPaste {
    private static final int MAX_TICKS = 50_000;

    private PlaytestPaste() {
    }

    /** Ticks Structurize's operations until its queue is empty; returns how many ticks that took. */
    static int finishQueued(ServerLevel level) {
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            if (queue().isEmpty()) return tick;
            Manager.onWorldTick(level);
        }
        return MAX_TICKS;
    }

    /** Structurize's queue of world operations; it has no public accessor. */
    private static Collection<?> queue() {
        try {
            Field field = Manager.class.getDeclaredField("scanToolOperationPool");
            field.setAccessible(true);
            return (Collection<?>) field.get(null);
        } catch (ReflectiveOperationException e) {
            PlaytestMod.LOGGER.warn("Cannot see Structurize's operation queue; the pastes finish over time", e);
            return List.of();
        }
    }
}
