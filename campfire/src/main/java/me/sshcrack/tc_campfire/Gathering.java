package me.sshcrack.tc_campfire;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.api.ApiFeature;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import me.sshcrack.mc_talking.api.colony.AddonColonyEvent;
import me.sshcrack.mc_talking.api.colony.ColonyEventService;
import me.sshcrack.mc_talking.api.conversation.AutonomousDiscussionHandle;
import me.sshcrack.mc_talking.api.conversation.AutonomousDiscussionPolicy;
import me.sshcrack.mc_talking.api.conversation.CitizenActivityReservation;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationService;
import me.sshcrack.mc_talking.api.conversation.ControlledConversationOptions;
import me.sshcrack.mc_talking.api.conversation.ControlledConversationSession;
import me.sshcrack.mc_talking.api.conversation.ConversationTranscriptEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One campfire night: the tellers walk to seats around the fire, then Talking Colonists runs an
 * autonomous storytelling discussion between them. Lives on the server thread; {@link #tick()} returns
 * true once it is over.
 */
public final class Gathering {
    public enum Phase { GATHERING, TELLING, DONE }

    static final int GATHER_TIMEOUT_TICKS = 20 * 45;
    static final int TELLING_TIMEOUT_TICKS = 20 * 60 * 8;
    /** Long enough to outlast a neighbouring conversation, which holds the floor until it ends. */
    static final int PAUSED_GIVE_UP_TICKS = 20 * 180;
    private static final double SEATED_DISTANCE_SQ = 1.5 * 1.5;
    private static final double DRIFT_DISTANCE_SQ = 3.5 * 3.5;
    private static final double WALK_SPEED = 0.6;

    private final MinecraftServer server;
    final IColony colony;
    final ServerLevel level;
    final BlockPos campfire;
    private final List<AbstractEntityCitizen> tellers;
    private final List<Vec3> seats = new ArrayList<>();
    private final List<CitizenActivityReservation> reservations;
    private final Consumer<String> report;

    private Phase phase = Phase.GATHERING;
    private int ticks;
    private int pausedTicks;
    private @Nullable ControlledConversationSession session;
    private @Nullable AutonomousDiscussionHandle discussion;

    Gathering(MinecraftServer server, IColony colony, ServerLevel level, BlockPos campfire,
              List<AbstractEntityCitizen> tellers, List<CitizenActivityReservation> reservations, Consumer<String> report) {
        this.server = server;
        this.colony = colony;
        this.level = level;
        this.campfire = campfire;
        this.tellers = new ArrayList<>(tellers);
        this.reservations = new ArrayList<>(reservations);
        this.report = report;
        for (double[] offset : CampfireStory.seats(tellers.size())) {
            seats.add(new Vec3(campfire.getX() + 0.5 + offset[0], campfire.getY(), campfire.getZ() + 0.5 + offset[1]));
        }
        tellListeners(names() + " gather around the campfire to tell stories.");
    }

    public Phase phase() {
        return phase;
    }

    public List<AbstractEntityCitizen> tellers() {
        return tellers;
    }

    /** Advances the night; returns true when it is over and can be dropped. */
    boolean tick() {
        if (phase == Phase.DONE) return true;
        ticks++;
        if (phase == Phase.GATHERING) {
            tickGathering();
        } else {
            tickTelling();
        }
        return phase == Phase.DONE;
    }

    private void tickGathering() {
        if (tellers.removeIf(teller -> !teller.isAlive() || teller.isRemoved()) && tellers.size() < 2) {
            finish("Not enough tellers are left");
            return;
        }
        boolean allSeated = true;
        for (int i = 0; i < tellers.size(); i++) {
            allSeated &= holdSeat(tellers.get(i), seats.get(i), ticks % 20 == 1);
        }
        if (allSeated || ticks >= GATHER_TIMEOUT_TICKS) beginTelling();
    }

    private void tickTelling() {
        if (ticks % 10 == 0) {
            for (int i = 0; i < tellers.size(); i++) {
                AbstractEntityCitizen teller = tellers.get(i);
                if (teller.isAlive() && !teller.isRemoved()) holdSeat(teller, seats.get(i), true);
            }
        }
        AutonomousDiscussionHandle handle = discussion;
        if (handle == null) return;
        if (handle.state() == AutonomousDiscussionHandle.State.PAUSED
                && handle.pauseReason().orElse(null) != AutonomousDiscussionHandle.PauseReason.CALLER) {
            // Paused for capacity, because someone nearby is speaking, or because a player spoke to a
            // teller: try again every couple of seconds, then give up.
            pausedTicks++;
            if (pausedTicks % 40 == 0) handle.resume();
            if (pausedTicks >= PAUSED_GIVE_UP_TICKS) finish("The fire burned down while everyone waited");
        } else {
            pausedTicks = 0;
        }
        if (ticks >= GATHER_TIMEOUT_TICKS + TELLING_TIMEOUT_TICKS) finish("The night is over");
    }

    /** Walks the teller to the seat (re-pathing when {@code repath}) or keeps it there facing the fire. */
    private boolean holdSeat(AbstractEntityCitizen teller, Vec3 seat, boolean repath) {
        double distanceSq = teller.position().distanceToSqr(seat);
        boolean seated = distanceSq <= SEATED_DISTANCE_SQ;
        double limit = phase == Phase.GATHERING ? SEATED_DISTANCE_SQ : DRIFT_DISTANCE_SQ;
        if (distanceSq > limit) {
            if (repath) teller.getNavigation().moveTo(seat.x, seat.y, seat.z, WALK_SPEED);
        } else {
            if (!teller.getNavigation().isDone()) teller.getNavigation().stop();
            teller.getLookControl().setLookAt(campfire.getX() + 0.5, campfire.getY() + 0.5, campfire.getZ() + 0.5);
        }
        return seated;
    }

    private void beginTelling() {
        // An addon reservation makes a citizen "busy", which controlled turns refuse; the session owns them now.
        releaseReservations();
        List<AbstractEntityCitizen> present = new ArrayList<>();
        List<Vec3> presentSeats = new ArrayList<>();
        for (int i = 0; i < tellers.size(); i++) {
            AbstractEntityCitizen teller = tellers.get(i);
            if (teller.isAlive() && !teller.isRemoved() && teller.position().distanceToSqr(seats.get(i)) <= DRIFT_DISTANCE_SQ * 4) {
                present.add(teller);
                presentSeats.add(seats.get(i));
            }
        }
        if (present.size() < 2) {
            finish("Too few tellers reached the campfire");
            return;
        }
        tellers.clear();
        tellers.addAll(present);
        seats.clear();
        seats.addAll(presentSeats);
        phase = Phase.TELLING;
        try {
            session = CitizenConversationService.createControlledSession(server, tellers,
                    CampfireStory.agenda(colony.getName(), names(tellers), recentNews()),
                    ControlledConversationOptions.noAddonTools());
            int turns = Math.min(12, Math.max(4, tellers.size() * 2));
            discussion = session.delegateAutonomousDiscussion(
                    // Live output tokens include the spoken audio; 320 cut 4-sentence turns off mid-sentence.
                    new AutonomousDiscussionPolicy(turns, Duration.ofMinutes(6), 900));
        } catch (RuntimeException e) {
            CampfireNights.LOGGER.warn("Campfire night at {} could not start", campfire, e);
            finish("The stories could not start: " + e.getMessage());
            return;
        }
        discussion.completion().whenComplete((reason, error) ->
                server.execute(() -> finish(error != null ? "The stories stopped: " + error : describe(reason))));
    }

    private String describe(AutonomousDiscussionHandle.CompletionReason reason) {
        return switch (reason) {
            case TURN_LIMIT, DURATION_LIMIT -> "The stories are told";
            case NO_AVAILABLE_PARTICIPANTS -> "Nobody was free to tell a story";
            case PROVIDER_FAILURE -> "The storytellers lost their voice (provider failure)";
            case STOPPED -> "The campfire night was stopped";
        };
    }

    /** A player near the fire said something: the tellers hear it and may answer. */
    boolean playerSays(ServerPlayer player, String text) {
        ControlledConversationSession current = session;
        if (phase != Phase.TELLING || current == null) return false;
        current.addPlayerStatement(player, text);
        return true;
    }

    /** Ends the night early (command, server stop). */
    void stop() {
        finish("The campfire night was stopped");
    }

    private void finish(String why) {
        if (phase == Phase.DONE) return;
        phase = Phase.DONE;
        releaseReservations();
        int told = discussion == null ? 0 : discussion.completedTurns();
        List<ConversationTranscriptEntry> transcript = session == null ? List.of() : session.transcript();
        if (discussion != null) discussion.stop();
        if (session != null) session.end();
        for (AbstractEntityCitizen teller : tellers) {
            if (teller.isAlive()) teller.getNavigation().stop();
        }
        for (ConversationTranscriptEntry entry : transcript) {
            CampfireNights.LOGGER.info("Campfire story, {}: {}", entry.speakerName(),
                    CampfireStory.withoutSpeaker(entry.speakerName(), entry.text()));
        }
        if (told > 0 && TalkingColonistsApi.supports(ApiFeature.COLONY_EVENTS)) {
            ConversationTranscriptEntry first = transcript.stream()
                    .filter(entry -> entry.speakerKind() == ConversationTranscriptEntry.SpeakerKind.CITIZEN)
                    .findFirst().orElse(null);
            ColonyEventService.record(colony, new AddonColonyEvent(CampfireNights.MOD_ID, "campfire_night",
                    CampfireStory.newsLine(names(tellers), first == null ? null : first.speakerName(),
                            first == null ? null : first.text())));
        }
        String summary = why + " (" + told + (told == 1 ? " story" : " stories") + ").";
        tellListeners(summary);
        report.accept(summary);
    }

    private void releaseReservations() {
        for (CitizenActivityReservation reservation : reservations) {
            reservation.close();
        }
        reservations.clear();
    }

    private List<String> recentNews() {
        if (!TalkingColonistsApi.supports(ApiFeature.COLONY_EVENTS)) return List.of();
        return ColonyEventService.recent(colony, Duration.ofMinutes(40)).stream()
                .filter(event -> !CampfireNights.MOD_ID.equals(event.addonNamespace()))
                .map(event -> event.description())
                .toList();
    }

    private String names() {
        return CampfireStory.joinNames(names(tellers));
    }

    static List<String> names(List<AbstractEntityCitizen> citizens) {
        List<String> names = new ArrayList<>();
        for (AbstractEntityCitizen citizen : citizens) {
            names.add(citizen.getCitizenData() != null ? citizen.getCitizenData().getName() : citizen.getName().getString());
        }
        return names;
    }

    /** Players close enough to hear the stories. */
    List<ServerPlayer> listeners() {
        double range = CampfireDirector.LISTEN_RANGE;
        return level.players().stream()
                .filter(player -> player.distanceToSqr(Vec3.atCenterOf(campfire)) <= range * range)
                .toList();
    }

    private void tellListeners(String text) {
        Component message = Component.literal("[Campfire] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY));
        for (ServerPlayer player : listeners()) {
            player.sendSystemMessage(message);
        }
    }
}
