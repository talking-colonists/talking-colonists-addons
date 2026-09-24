package me.sshcrack.tc_shared.delivery;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.jobs.ModJobs;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.api.conversation.CitizenActivityReservation;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Citizens who walk up to a player and hand them an item, so nothing has to be fetched by command.
 * On handing it over the citizen says a short line aloud through Talking Colonists; when it cannot
 * speak right now (quota, capacity, cooldown, no key) the line becomes a chat message instead.
 * The colony's courier carries when there is one (or a preferred citizen, such as a letter's writer);
 * otherwise the nearest free citizen does. A delivery fails, and the caller keeps the item for later,
 * when the player leaves, logs out or cannot be reached in time. Server thread only.
 */
public final class Couriers {
    /** Players farther than this from the carrier are not walked to. */
    public static final double MAX_RANGE = 96;
    static final double HAND_OVER_DISTANCE = 2.5;
    static final int TIMEOUT_TICKS = 20 * 90;
    /** Stuck this long without getting closer: hand over from where they are if close enough, else give up. */
    static final int STUCK_TICKS = 20 * 12;
    static final double STUCK_HAND_OVER_DISTANCE = 12;
    private static final double WALK_SPEED = 0.8;

    /** One walk to a player. {@code done} gets true once the item is in the player's hands. */
    private static final class Job {
        final String key;
        final ServerPlayer player;
        final AbstractEntityCitizen carrier;
        final CitizenActivityReservation reservation;
        final String what;
        final @Nullable String lineHint;
        final Supplier<ItemStack> item;
        final Consumer<Boolean> done;
        int ticks;
        int lastProgressTick;
        double bestDistanceSq = Double.MAX_VALUE;

        Job(String key, ServerPlayer player, AbstractEntityCitizen carrier, CitizenActivityReservation reservation,
            String what, @Nullable String lineHint, Supplier<ItemStack> item, Consumer<Boolean> done) {
            this.key = key;
            this.player = player;
            this.carrier = carrier;
            this.reservation = reservation;
            this.what = what;
            this.lineHint = lineHint;
            this.item = item;
            this.done = done;
        }
    }

    private final MinecraftServer server;
    private final String ownerId;
    private final Logger logger;
    private final List<Job> jobs = new ArrayList<>();
    /** Carriers who just got stuck, and the tick until which someone else goes instead. */
    private final Map<UUID, Integer> resting = new HashMap<>();
    private int now;
    static final int REST_TICKS = 20 * 60;

    /** {@code ownerId} names the activity reservation, e.g. {@code "tc_postal:delivery"}. */
    public Couriers(MinecraftServer server, String ownerId) {
        this.server = server;
        this.ownerId = ownerId;
        this.logger = LoggerFactory.getLogger(ownerId);
    }

    /** Whether a delivery with this key is on its way. */
    public boolean isRunning(String key) {
        return jobs.stream().anyMatch(job -> job.key.equals(key));
    }

    /**
     * Sends a citizen of {@code colony} to {@code player} with the item. {@code what} completes
     * "&lt;name&gt; hands you ...", e.g. "the new gazette". {@code lineHint} is optional extra context
     * for the spoken line, e.g. the headline. Returns false, without calling {@code done}, when nobody
     * can go right now.
     */
    public boolean dispatch(String key, IColony colony, ServerPlayer player, @Nullable AbstractEntityCitizen preferred,
                            String what, @Nullable String lineHint, Supplier<ItemStack> item, Consumer<Boolean> done) {
        if (isRunning(key) || player.isSpectator()) return false;
        for (AbstractEntityCitizen carrier : candidates(colony, player, preferred)) {
            if (jobs.stream().anyMatch(job -> job.carrier == carrier)) continue;
            if (resting.getOrDefault(carrier.getUUID(), 0) > now) continue;
            CitizenActivityReservation reservation = CitizenConversationService
                    .reserveActivity(carrier, ownerId, Duration.ofSeconds(TIMEOUT_TICKS / 20 + 30)).orElse(null);
            if (reservation == null) continue;
            jobs.add(new Job(key, player, carrier, reservation, what, lineHint, item, done));
            return true;
        }
        return false;
    }

    /** Preferred citizen first, then couriers, then everyone else; nearest to the player first. */
    private static List<AbstractEntityCitizen> candidates(IColony colony, ServerPlayer player, @Nullable AbstractEntityCitizen preferred) {
        List<AbstractEntityCitizen> couriers = new ArrayList<>();
        List<AbstractEntityCitizen> others = new ArrayList<>();
        for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
            AbstractEntityCitizen citizen = data.getEntity().orElse(null);
            if (citizen == null || !usable(citizen, player) || citizen == preferred) continue;
            boolean courier = data.getJob() != null && data.getJob().getJobRegistryEntry() == ModJobs.delivery.get();
            (courier ? couriers : others).add(citizen);
        }
        Comparator<AbstractEntityCitizen> nearest = Comparator.comparingDouble(citizen -> citizen.distanceToSqr(player));
        couriers.sort(nearest);
        others.sort(nearest);
        List<AbstractEntityCitizen> all = new ArrayList<>();
        if (preferred != null && usable(preferred, player)) all.add(preferred);
        all.addAll(couriers);
        all.addAll(others);
        return all;
    }

    private static boolean usable(AbstractEntityCitizen citizen, ServerPlayer player) {
        return citizen.isAlive() && !citizen.isRemoved() && !citizen.isSleeping()
                && citizen.level() == player.level()
                && citizen.distanceToSqr(player) <= MAX_RANGE * MAX_RANGE
                && !CitizenConversationService.isBusy(citizen);
    }

    public void tick() {
        now++;
        if (now % 1200 == 0) resting.values().removeIf(until -> until <= now);
        for (Job job : List.copyOf(jobs)) {
            step(job);
        }
    }

    private void step(Job job) {
        job.ticks++;
        ServerPlayer player = current(job.player);
        if (player == null || !job.carrier.isAlive() || job.carrier.isRemoved() || job.carrier.level() != player.level()
                || job.ticks > TIMEOUT_TICKS || job.reservation.isClosed()) {
            logger.info("Delivery {} ended without hand-over (player {}, carrier alive {}, ticks {}, reservation closed {})",
                    job.key, player == null ? "gone" : "present", job.carrier.isAlive(), job.ticks, job.reservation.isClosed());
            finish(job, false);
            return;
        }
        double distanceSq = job.carrier.distanceToSqr(player);
        if (distanceSq <= HAND_OVER_DISTANCE * HAND_OVER_DISTANCE) {
            handOver(job, player);
            return;
        }
        if (distanceSq < job.bestDistanceSq - 1) {
            job.bestDistanceSq = distanceSq;
            job.lastProgressTick = job.ticks;
        } else if (job.ticks - job.lastProgressTick > STUCK_TICKS) {
            if (distanceSq <= STUCK_HAND_OVER_DISTANCE * STUCK_HAND_OVER_DISTANCE && job.carrier.hasLineOfSight(player)) {
                handOver(job, player); // e.g. the player stands on a roof: toss it up
            } else {
                logger.info("Delivery {} gave up: stuck {} blocks away", job.key, Math.round(Math.sqrt(distanceSq)));
                resting.put(job.carrier.getUUID(), now + REST_TICKS);
                finish(job, false);
            }
            return;
        }
        if (job.ticks % 100 == 0) {
            logger.debug("Delivery {}: {} blocks to go", job.key, Math.round(Math.sqrt(distanceSq)));
        }
        // moveTo(entity) walks to the player's block position, which is not walkable while they jump or
        // fly. Aim at the block above what the player stands on instead, and take the path back whenever
        // the citizen's own AI (wandering, work) sent them somewhere else.
        BlockPos feet = player.getOnPos().above();
        BlockPos heading = job.carrier.getNavigation().getTargetPos();
        boolean offCourse = heading == null || heading.distSqr(feet) > 4 || job.carrier.getNavigation().isDone();
        if (job.ticks % 10 == 1 && offCourse) {
            job.carrier.getNavigation().moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, WALK_SPEED);
        }
        job.carrier.getLookControl().setLookAt(player, 30, 30);
    }

    /**
     * The player's current entity (a respawn replaces it), or null once they logged out. Fake players
     * (tests, automation) are never in the player list and count as present while they exist.
     */
    private @Nullable ServerPlayer current(ServerPlayer player) {
        ServerPlayer listed = server.getPlayerList().getPlayer(player.getUUID());
        if (listed != null) return listed;
        return player.isRemoved() || player.hasDisconnected() ? null : player;
    }

    private void handOver(Job job, ServerPlayer player) {
        ItemStack stack = job.item.get();
        if (stack.isEmpty()) {
            finish(job, false);
            return;
        }
        job.carrier.getNavigation().stop();
        job.carrier.swing(InteractionHand.MAIN_HAND);
        if (!player.getInventory().add(stack)) player.drop(stack, false);
        // Release the reservation first: it marks the citizen busy, and busy citizens cannot speak.
        finish(job, true);
        speak(job, player);
    }

    /** The citizen says a line about the hand-over; chat only when it cannot speak right now. */
    private void speak(Job job, ServerPlayer player) {
        String name = job.carrier.getCitizenData() != null ? job.carrier.getCitizenData().getName()
                : job.carrier.getName().getString();
        Component chat = Component.literal(name + " hands you " + job.what + ".").withStyle(ChatFormatting.GRAY);
        String directive = "You just walked up to " + player.getGameProfile().getName() + " and handed them "
                + job.what + "." + (job.lineHint == null ? "" : " " + job.lineHint)
                + " Say one short, friendly sentence to them about it, at most 20 words, in your own voice.";
        try {
            CitizenConversationService.requestAmbientLine(job.carrier, directive).whenComplete((result, error) ->
                    server.execute(() -> {
                        boolean spoke = error == null && result != null
                                && (result.completed() || !result.transcript().isBlank());
                        if (!spoke) player.sendSystemMessage(chat);
                    }));
        } catch (RuntimeException e) {
            player.sendSystemMessage(chat);
        }
    }

    private void finish(Job job, boolean delivered) {
        jobs.remove(job);
        job.reservation.close();
        if (job.carrier.isAlive()) job.carrier.getNavigation().stop();
        job.done.accept(delivered);
    }

    /** Cancels every delivery (server stop); callers keep their items. */
    public void stopAll() {
        for (Job job : List.copyOf(jobs)) {
            finish(job, false);
        }
    }
}
