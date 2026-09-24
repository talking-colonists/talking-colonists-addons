package me.sshcrack.tc_campfire;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.api.conversation.CitizenActivityReservation;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationService;
import me.sshcrack.mc_talking.api.conversation.ConversationKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Starts campfire nights. Each colony gets at most one per in-game day: at dusk, at a lit campfire
 * within its borders that a player is near, when enough idle citizens are around and Talking
 * Colonists has spare speech capacity. Runs on the server thread.
 */
public final class CampfireDirector {
    static final String OWNER = CampfireNights.MOD_ID + ":campfire_night";
    static final long DAY_TICKS = 24_000;
    static final long DUSK_START = 12_000;
    static final long DUSK_END = 13_500;
    static final int CHECK_INTERVAL_TICKS = 100;
    /** Players this close to the fire hear the stories and can join in by chatting. */
    static final double LISTEN_RANGE = 24;
    /** Tellers come from this far away. */
    static final double GATHER_RANGE = 48;
    /** Campfires are looked for this many chunks around the colony center. */
    static final int SEARCH_CHUNKS = 4;

    private final MinecraftServer server;
    private final Map<String, Long> lastNight = new HashMap<>();
    private final List<Gathering> active = new ArrayList<>();
    private int ticks;

    CampfireDirector(MinecraftServer server) {
        this.server = server;
    }

    void tick() {
        active.removeIf(Gathering::tick);
        if (++ticks % CHECK_INTERVAL_TICKS != 0) return;
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (!(colony.getWorld() instanceof ServerLevel level) || gatheringOf(colony) != null) continue;
            long dayTime = level.getDayTime();
            long timeOfDay = dayTime % DAY_TICKS;
            long day = dayTime / DAY_TICKS;
            if (timeOfDay < DUSK_START || timeOfDay > DUSK_END) continue;
            String key = key(colony);
            if (Long.valueOf(day).equals(lastNight.get(key))) continue;
            BlockPos fire = campfireWithListener(colony, level);
            if (fire == null || !CitizenConversationService.hasAmbientCapacity(1)) continue;
            if (start(colony, level, fire, CampfireStory.MIN_TELLERS, message -> { }) != null) {
                lastNight.put(key, day);
            }
        }
    }

    /**
     * Starts a night at {@code fire} now, ignoring the time of day. Returns the gathering, or null
     * (with the reason passed to {@code report}) if there are fewer than {@code minTellers} idle citizens.
     */
    public @Nullable Gathering start(IColony colony, ServerLevel level, BlockPos fire, int minTellers, Consumer<String> report) {
        if (gatheringOf(colony) != null) {
            report.accept("A campfire night is already going on in " + colony.getName() + ".");
            return null;
        }
        List<AbstractEntityCitizen> tellers = new ArrayList<>();
        List<CitizenActivityReservation> reservations = new ArrayList<>();
        for (AbstractEntityCitizen citizen : idleCitizensNear(colony, fire)) {
            if (tellers.size() >= CampfireStory.MAX_TELLERS) break;
            CitizenConversationService.reserveActivity(citizen, OWNER).ifPresent(reservation -> {
                tellers.add(citizen);
                reservations.add(reservation);
            });
        }
        if (tellers.size() < minTellers) {
            reservations.forEach(CitizenActivityReservation::close);
            report.accept("Only " + tellers.size() + " idle citizens are near the campfire; at least " + minTellers + " are needed.");
            return null;
        }
        Gathering gathering = new Gathering(server, colony, level, fire, tellers, reservations, report);
        active.add(gathering);
        CampfireNights.LOGGER.info("Campfire night in {} at {} with {}", colony.getName(), fire, Gathering.names(tellers));
        return gathering;
    }

    /** Idle, loaded citizens (not visitors) within reach of the fire, nearest first. */
    private static List<AbstractEntityCitizen> idleCitizensNear(IColony colony, BlockPos fire) {
        Vec3 center = Vec3.atCenterOf(fire);
        List<AbstractEntityCitizen> idle = new ArrayList<>();
        for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
            AbstractEntityCitizen citizen = data.getEntity().orElse(null);
            if (citizen == null || !citizen.isAlive() || citizen.isRemoved()) continue;
            if (citizen.position().distanceToSqr(center) > GATHER_RANGE * GATHER_RANGE) continue;
            if (CitizenConversationService.isBusy(citizen)
                    || !CitizenConversationService.canSpeak(citizen, ConversationKind.CONTROLLED)) continue;
            idle.add(citizen);
        }
        idle.sort(Comparator.comparingDouble(citizen -> citizen.position().distanceToSqr(center)));
        return idle;
    }

    /** A lit campfire in the colony with a player within {@link #LISTEN_RANGE}, or null. */
    private static @Nullable BlockPos campfireWithListener(IColony colony, ServerLevel level) {
        for (BlockPos fire : litCampfires(level, colony.getCenter(), SEARCH_CHUNKS)) {
            if (!colony.isCoordInColony(level, fire)) continue;
            Vec3 center = Vec3.atCenterOf(fire);
            boolean heard = level.players().stream()
                    .anyMatch(player -> !player.isSpectator() && player.distanceToSqr(center) <= LISTEN_RANGE * LISTEN_RANGE);
            if (heard) return fire;
        }
        return null;
    }

    /** Lit campfires in loaded chunks within {@code radiusChunks} of {@code around}, nearest first. */
    public static List<BlockPos> litCampfires(ServerLevel level, BlockPos around, int radiusChunks) {
        List<BlockPos> fires = new ArrayList<>();
        int cx = around.getX() >> 4;
        int cz = around.getZ() >> 4;
        for (int x = cx - radiusChunks; x <= cx + radiusChunks; x++) {
            for (int z = cz - radiusChunks; z <= cz + radiusChunks; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (entity instanceof CampfireBlockEntity && CampfireBlock.isLitCampfire(entity.getBlockState())) {
                        fires.add(entity.getBlockPos());
                    }
                }
            }
        }
        fires.sort(Comparator.comparingDouble(pos -> pos.distSqr(around)));
        return fires;
    }

    /** The running night of this colony, or null. */
    @Nullable Gathering gatheringOf(IColony colony) {
        for (Gathering gathering : active) {
            if (gathering.colony.getID() == colony.getID() && gathering.level == colony.getWorld()) return gathering;
        }
        return null;
    }

    /** Hands chat from a player near a running campfire night to the tellers. */
    boolean onChat(ServerPlayer player, String text) {
        for (Gathering gathering : active) {
            if (gathering.level == player.level() && gathering.listeners().contains(player)
                    && gathering.playerSays(player, text)) return true;
        }
        return false;
    }

    List<Gathering> active() {
        return active;
    }

    void stopAll() {
        for (Gathering gathering : List.copyOf(active)) {
            gathering.stop();
        }
        active.clear();
    }

    static String key(IColony colony) {
        return colony.getDimension().location() + "|" + colony.getID();
    }
}
