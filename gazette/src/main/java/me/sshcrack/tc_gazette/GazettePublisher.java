package me.sshcrack.tc_gazette;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.jobs.ModJobs;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.api.ApiFeature;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import me.sshcrack.mc_talking.api.colony.AddonColonyEvent;
import me.sshcrack.mc_talking.api.colony.ColonyEventService;
import me.sshcrack.mc_talking.api.colony.ColonyEventView;
import me.sshcrack.mc_talking.api.provider.ProviderBudgetService;
import me.sshcrack.mc_talking.api.provider.ProviderBudgetView;
import me.sshcrack.mc_talking.api.provider.ProviderConfigView;
import me.sshcrack.mc_talking.api.provider.ProviderQuotaState;
import me.sshcrack.mc_talking.api.text.CitizenTextService;
import me.sshcrack.mc_talking.api.text.TextRequest;
import me.sshcrack.mc_talking.api.text.TextResult;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Decides when each colony's gazette is written, asks Talking Colonists to write it, and hands out
 * copies. Everything here runs on the server thread.
 *
 * <p>A colony gets a new issue on the first check after {@link #PUBLISH_TIME_OF_DAY} of a new
 * in-game day, if anything happened since the last issue and the provider has a free background
 * slot. Quiet days are skipped, so a sleepy colony costs no quota.</p>
 */
public final class GazettePublisher {
    public static final String NS = ColonyGazette.MOD_ID;
    static final long DAY_TICKS = 24_000;
    /** 07:00 in the morning. */
    static final long PUBLISH_TIME_OF_DAY = 1_000;
    static final int CHECK_INTERVAL_TICKS = 200;
    static final long RETRY_DELAY_TICKS = 1_200;
    static final int MAX_ATTEMPTS_PER_DAY = 5;
    static final int MAX_EVENTS = 15;
    static final int MAX_EVENT_CHARS = 200;
    /** Events older than this are never news, even after a long pause. */
    static final long MAX_COVERAGE_TICKS = 2 * DAY_TICKS;

    private final MinecraftServer server;
    private final GazetteStore store;
    private final Set<String> inFlight = new HashSet<>();
    private final Map<String, Retry> retries = new HashMap<>();
    private int ticks;

    private record Retry(long day, int attempts, long notBeforeGameTime) {
    }

    private record Author(@Nullable AbstractEntityCitizen entity, String name, String role) {
    }

    public GazettePublisher(MinecraftServer server, GazetteStore store) {
        this.server = server;
        this.store = store;
    }

    public GazetteStore store() {
        return store;
    }

    public void tick() {
        if (++ticks % CHECK_INTERVAL_TICKS != 0) return;
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            try {
                checkColony(colony);
            } catch (RuntimeException e) {
                ColonyGazette.LOGGER.error("Gazette check failed for colony {}", colony.getID(), e);
            }
        }
    }

    public static String key(IColony colony) {
        return colony.getDimension().location() + "|" + colony.getID();
    }

    private void checkColony(IColony colony) {
        Level level = colony.getWorld();
        if (level == null) return;
        String key = key(colony);
        long day = level.getDayTime() / DAY_TICKS;
        long now = level.getGameTime();
        GazetteStore.ColonyState state = store.get(key);
        if (state == null) {
            // First time we see this colony: the first issue comes tomorrow and covers the last day.
            state = store.getOrCreate(key);
            state.lastIssueDay = day;
            state.coveredUntilGameTime = Math.max(0, now - DAY_TICKS);
            store.save();
            return;
        }
        if (day < state.lastIssueDay) {
            // Time was set back; publish again from the next new day.
            state.lastIssueDay = day;
            store.save();
            return;
        }
        if (day == state.lastIssueDay || level.getDayTime() % DAY_TICKS < PUBLISH_TIME_OF_DAY) return;
        if (inFlight.contains(key)) return;

        Retry retry = retries.get(key);
        if (retry != null && retry.day() == day) {
            if (retry.attempts() >= MAX_ATTEMPTS_PER_DAY) {
                ColonyGazette.LOGGER.warn("Giving up on today's gazette for {} after {} attempts", colony.getName(), retry.attempts());
                skipDay(state, day, now);
                return;
            }
            if (now < retry.notBeforeGameTime()) return;
        }

        List<String> news = newsSince(colony, state.coveredUntilGameTime, now);
        if (news.isEmpty()) {
            skipDay(state, day, now);
            return;
        }
        if (!hasSpareCapacity()) return;
        publish(colony, day, news, null);
    }

    private void skipDay(GazetteStore.ColonyState state, long day, long now) {
        state.lastIssueDay = day;
        state.coveredUntilGameTime = now;
        store.save();
    }

    /**
     * Publishes an issue now, even on a quiet day. Used by {@code /gazette publish}. {@code feedback}
     * receives the outcome on the server thread. Returns false if an issue is already being written.
     */
    public boolean publishNow(IColony colony, Consumer<Component> feedback) {
        Level level = colony.getWorld();
        if (level == null) return false;
        GazetteStore.ColonyState state = store.getOrCreate(key(colony));
        long now = level.getGameTime();
        long since = state.coveredUntilGameTime > 0 ? state.coveredUntilGameTime : now - DAY_TICKS;
        return publish(colony, level.getDayTime() / DAY_TICKS, newsSince(colony, since, now), feedback);
    }

    private boolean publish(IColony colony, long day, List<String> news, @Nullable Consumer<Component> feedback) {
        String key = key(colony);
        if (!inFlight.add(key)) return false;
        Level level = colony.getWorld();
        long startedAt = level.getGameTime();
        GazetteStore.ColonyState state = store.getOrCreate(key);
        int number = state.issueCount + 1;
        Author author = pickAuthor(colony);
        String colonyName = colony.getName();

        TextRequest request = TextRequest.of(NS + ":issue",
                        GazetteIssue.directive(colonyName, number, author.role(), news))
                .withMaxChars(2_500)
                .withResponseSchema(GazetteIssue.responseSchema());
        ColonyGazette.LOGGER.info("Writing gazette No. {} for {} ({} events, writer: {})", number, colonyName,
                news.size(), author.name());
        CompletableFuture<TextResult> future = author.entity() != null
                ? CitizenTextService.generate(author.entity(), request)
                : CitizenTextService.generateColonyVoice(colony, request);
        future.whenComplete((result, error) -> server.execute(() -> {
            inFlight.remove(key);
            GazetteIssue issue = null;
            if (error == null && result.isSuccess() && result.json() != null) {
                issue = GazetteIssue.fromJson(result.json(), number, day, colonyName, author.name(), author.role());
            }
            if (issue == null) {
                String reason = error != null ? error.toString() : result.status() + " " + result.detail();
                ColonyGazette.LOGGER.warn("Gazette No. {} for {} was not written: {}", number, colonyName, reason);
                Retry previous = retries.get(key);
                int attempts = previous != null && previous.day() == day ? previous.attempts() + 1 : 1;
                retries.put(key, new Retry(day, attempts, startedAt + RETRY_DELAY_TICKS));
                if (feedback != null) {
                    feedback.accept(Component.literal("The gazette could not be written: " + reason)
                            .withStyle(ChatFormatting.RED));
                }
                return;
            }
            retries.remove(key);
            state.issueCount = number;
            state.latest = issue;
            state.claimed.clear();
            state.lastIssueDay = Math.max(state.lastIssueDay, day);
            state.coveredUntilGameTime = startedAt;
            store.save();
            announce(colony, issue);
            if (feedback != null) feedback.accept(Component.literal("Published gazette No. " + number + "."));
        }));
        return true;
    }

    /** News since {@code sinceGameTime}, oldest first, without this addon's own events. */
    public static List<String> newsSince(IColony colony, long sinceGameTime, long now) {
        long since = Math.max(sinceGameTime, now - MAX_COVERAGE_TICKS);
        Duration window = Duration.ofSeconds(Math.max(0, now - since) / 20 + 1);
        List<ColonyEventView> events = ColonyEventService.recent(colony, window);
        List<String> news = new ArrayList<>();
        for (ColonyEventView event : events) { // newest first
            if (event.gameTime() <= since) continue;
            if (event.isAddonEvent() && NS.equals(event.addonNamespace())) continue;
            news.add(GazetteIssue.cut(event.description(), MAX_EVENT_CHARS));
            if (news.size() >= MAX_EVENTS) break;
        }
        Collections.reverse(news);
        return news;
    }

    static boolean hasSpareCapacity() {
        if (!TalkingColonistsApi.supports(ApiFeature.PROVIDER_BUDGET)) return true;
        ProviderConfigView config = ProviderBudgetService.config();
        if (!config.apiKeySet()) return false;
        ProviderBudgetView budget = ProviderBudgetService.snapshot();
        if (budget.background().available() <= 0) return false;
        // Text requests fall back to the Live model when the text model is out of quota.
        return usable(budget, config.textModel()) || usable(budget, config.liveModel());
    }

    private static boolean usable(ProviderBudgetView budget, String model) {
        return budget.model(model).map(view -> view.state() == ProviderQuotaState.OK).orElse(true);
    }

    /** The teacher writes the paper; failing that a library student; failing that the colony itself. */
    private static Author pickAuthor(IColony colony) {
        Author student = null;
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            if (citizen.getJob() == null) continue;
            JobEntry job = citizen.getJob().getJobRegistryEntry();
            AbstractEntityCitizen entity = citizen.getEntity().filter(AbstractEntityCitizen::isAlive).orElse(null);
            if (entity == null) continue;
            if (job == ModJobs.teacher.get()) return new Author(entity, citizen.getName(), "teacher");
            if (student == null && job == ModJobs.student.get()) {
                student = new Author(entity, citizen.getName(), "library student");
            }
        }
        if (student != null) return student;
        return new Author(null, colony.getName(), "");
    }

    private void announce(IColony colony, GazetteIssue issue) {
        if (TalkingColonistsApi.supports(ApiFeature.COLONY_EVENTS)) {
            ColonyEventService.record(colony, new AddonColonyEvent(NS, "issue_published",
                    "The " + issue.colonyName() + " Gazette No. " + issue.number() + " came out: \"" + issue.headline() + "\""));
        }
        Component link = Component.literal("[Get a copy]").withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gazette"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/gazette"))));
        Component message = Component.literal("The " + issue.colonyName() + " Gazette No. " + issue.number() + ": ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(issue.headline()).withStyle(ChatFormatting.WHITE))
                .append(" ")
                .append(link);
        for (Player player : colony.getMessagePlayerEntities()) {
            player.sendSystemMessage(message);
        }
    }
}
