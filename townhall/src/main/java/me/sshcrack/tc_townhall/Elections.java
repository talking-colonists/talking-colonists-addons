package me.sshcrack.tc_townhall;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.workerbuildings.ITownHall;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.api.ApiFeature;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import me.sshcrack.mc_talking.api.colony.AddonColonyEvent;
import me.sshcrack.mc_talking.api.colony.ColonyEventService;
import me.sshcrack.mc_talking.api.memory.AddonConfirmedOutcome;
import me.sshcrack.mc_talking.api.memory.BroadcastPublishResult;
import me.sshcrack.mc_talking.api.memory.BroadcastRequest;
import me.sshcrack.mc_talking.api.memory.BroadcastSource;
import me.sshcrack.mc_talking.api.memory.CitizenBroadcastMemoryView;
import me.sshcrack.mc_talking.api.memory.CitizenMemoryService;
import me.sshcrack.mc_talking.api.memory.CitizenMemorySnapshot;
import me.sshcrack.mc_talking.api.memory.CitizenRelationshipView;
import me.sshcrack.mc_talking.api.speech.PlayerSpeechCapture;
import me.sshcrack.mc_talking.api.speech.SpeechCaptureResult;
import me.sshcrack.mc_talking.api.text.CitizenTextService;
import me.sshcrack.mc_talking.api.text.TextRequest;
import me.sshcrack.tc_townhall.shared.book.BookPages;
import me.sshcrack.tc_townhall.shared.book.BookText;
import me.sshcrack.tc_townhall.shared.book.WrittenBooks;
import me.sshcrack.tc_townhall.shared.delivery.Couriers;
import me.sshcrack.tc_townhall.shared.provider.TextCapacity;
import me.sshcrack.tc_townhall.shared.store.JsonFile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mayoral elections. A player stands by right-clicking their colony's Town Hall block with a signed
 * book (title: slogan, text: platform) and may then give a speech through Simple Voice Chat. The
 * citizens at the town hall hear it and spread it. When the campaign ends, a citizen stands against a
 * lone candidate, then every loaded adult citizen votes in character, on what they heard and how they
 * feel about each candidate. The result is announced to the whole colony and a citizen brings each
 * player candidate the results book. Server thread only.
 */
public final class Elections {
    /** One Minecraft day of campaigning after the first candidacy. */
    static final long CAMPAIGN_TICKS = 24_000;
    /** A citizen rival's word needs a while to get around before the vote. */
    static final long RIVAL_CAMPAIGN_TICKS = 6_000;
    /** Votes still missing by then (unloaded citizens, quota) are not waited for. */
    static final long VOTING_TICKS = 12_000;
    /** Three days between elections. */
    static final long COOLDOWN_TICKS = 3 * 24_000;
    static final int MIN_VOTERS = 2;
    static final int MAX_CANDIDATES = 4;
    static final int MAX_VOTES_IN_FLIGHT = 2;
    static final int MAX_VOTE_ATTEMPTS = 2;
    static final int MAX_QUOTES = 4;
    static final Duration SPEECH_DURATION = Duration.ofSeconds(30);
    private static final int CHECK_INTERVAL_TICKS = 100;
    private static final Gson GSON = new Gson();

    /** Someone standing for mayor. Citizens are keyed by their entity UUID, as in relationship memory. */
    public static final class Candidate {
        public boolean citizen;
        public UUID id = new UUID(0, 0);
        public int citizenId = -1;
        public String name = "";
        public String slogan = "";
        public String platform = "";
        public List<String> broadcastIds = new ArrayList<>();
    }

    public static final class Election {
        public String id = UUID.randomUUID().toString();
        public String colonyKey = "";
        public long votingAt;
        public boolean voting;
        public boolean rivalAsked;
        public long votingEndsAt;
        public List<Candidate> candidates = new ArrayList<>();
        /** Citizen id → vote. */
        public Map<Integer, ElectionText.Vote> votes = new HashMap<>();
        public Map<Integer, String> voterNames = new HashMap<>();
        public Map<Integer, Integer> attempts = new HashMap<>();
        transient Set<Integer> inFlight = new HashSet<>();
        transient boolean rivalWriting;
    }

    public static final class Mayor {
        public String name = "";
        public boolean citizen;
        public UUID id = new UUID(0, 0);
        public int sinceDay;
        public String result = "";
    }

    /** The results book, waiting for a citizen to bring it to a player candidate. */
    public static final class ResultsBook {
        public UUID id = UUID.randomUUID();
        public UUID playerId = new UUID(0, 0);
        public String colonyKey = "";
        public List<String> pages = new ArrayList<>();
    }

    /** Everything that is saved. */
    static final class State {
        List<Election> elections = new ArrayList<>();
        Map<String, Mayor> mayors = new HashMap<>();
        Map<String, Long> lastElectionAt = new HashMap<>();
        List<ResultsBook> books = new ArrayList<>();
    }

    private final MinecraftServer server;
    private final Path file;
    private final Couriers couriers;
    private State state = new State();
    private int ticks;

    Elections(MinecraftServer server, Path file) {
        this.server = server;
        this.file = file;
        this.couriers = new Couriers(server, TownHall.MOD_ID + ":results");
    }

    private long now() {
        return server.overworld().getGameTime();
    }

    public @Nullable Election election(IColony colony) {
        String key = key(colony);
        return state.elections.stream().filter(election -> election.colonyKey.equals(key)).findFirst().orElse(null);
    }

    public @Nullable Mayor mayor(IColony colony) {
        return state.mayors.get(key(colony));
    }

    /** The running election or mayor of the colony with this id, for prompts (which only know the id). */
    @Nullable Election electionById(int colonyId) {
        return state.elections.stream().filter(election -> election.colonyKey.endsWith("|" + colonyId)).findFirst().orElse(null);
    }

    @Nullable Mayor mayorById(int colonyId) {
        return state.mayors.entrySet().stream().filter(entry -> entry.getKey().endsWith("|" + colonyId))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /** Whether {@code pos} is the colony's Town Hall block. */
    public static boolean isTownHall(IColony colony, BlockPos pos) {
        ITownHall townHall = colony.getServerBuildingManager().getTownHall();
        return townHall != null && townHall.getPosition().equals(pos);
    }

    /**
     * The player stands for mayor with the book: its title is the slogan and its text the platform.
     * Then, with voice chat, they give a speech. Tells the player the outcome. Returns whether they stand.
     */
    public boolean stand(ServerPlayer player, IColony colony, ItemStack book, BlockPos townHall) {
        BookText text = BookText.read(book);
        if (text == null) return false;
        if (!colony.getPermissions().isColonyMember(player)) {
            tell(player, "Only members of " + colony.getName() + " can stand for its mayor.");
            return false;
        }
        Election election = election(colony);
        if (election == null) {
            Long last = state.lastElectionAt.get(key(colony));
            if (last != null && now() - last < COOLDOWN_TICKS) {
                long days = (COOLDOWN_TICKS - (now() - last) + 23_999) / 24_000;
                tell(player, "The colony only just voted. The next election can be called in " + days
                        + (days == 1 ? " day." : " days."));
                return false;
            }
            if (voters(colony, null).size() < MIN_VOTERS) {
                tell(player, colony.getName() + " needs at least " + MIN_VOTERS + " grown citizens around to hold an election.");
                return false;
            }
            election = new Election();
            election.colonyKey = key(colony);
            election.votingAt = now() + CAMPAIGN_TICKS;
            state.elections.add(election);
        } else if (election.voting) {
            tell(player, "Voting has already begun; it's too late to stand.");
            return false;
        }

        Candidate candidate = election.candidates.stream().filter(c -> !c.citizen && c.id.equals(player.getUUID()))
                .findFirst().orElse(null);
        boolean renewed = candidate != null;
        if (candidate == null) {
            if (election.candidates.size() >= MAX_CANDIDATES) {
                tell(player, "The ballot is full: " + MAX_CANDIDATES + " candidates are already standing.");
                return false;
            }
            candidate = new Candidate();
            candidate.id = player.getUUID();
            candidate.name = player.getGameProfile().getName();
            election.candidates.add(candidate);
        }
        candidate.slogan = ElectionText.slogan(text.title());
        candidate.platform = ElectionText.cut(text.body().strip().replaceAll("\\s+", " "), ElectionText.MAX_PLATFORM_CHARS);
        announce(colony, candidate, townHall, ElectionText.candidacy(candidate.name, candidate.slogan, candidate.platform));
        save();

        long minutes = Math.max(1, (election.votingAt - now()) / 1_200);
        tell(player, (renewed ? "Your platform is updated: \"" : "You stand for mayor of " + colony.getName() + " with \"")
                + candidate.slogan + "\". Citizens at the town hall heard it and will spread the word. Voting starts in about "
                + minutes + " minutes; talk to your citizens until then.");
        giveSpeech(player, colony, election, candidate, townHall);
        return true;
    }

    /** With voice chat: the player has 30 seconds to speak; the transcript is heard at the town hall. */
    private void giveSpeech(ServerPlayer player, IColony colony, Election election, Candidate candidate, BlockPos townHall) {
        if (!TalkingColonistsApi.supports(ApiFeature.PLAYER_SPEECH_CAPTURE)) return;
        PlayerSpeechCapture.capture(player, SPEECH_DURATION).whenComplete((result, error) -> server.execute(() -> {
            if (error != null || result == null) return;
            if (result.status() == SpeechCaptureResult.Status.TRANSCRIBED && result.transcript() != null
                    && !result.transcript().isBlank()) {
                if (!state.elections.contains(election) || election.voting) return;
                announce(colony, candidate, townHall, ElectionText.speech(candidate.name, result.transcript()));
                candidate.platform = ElectionText.cut(candidate.platform + " Speech: " + result.transcript().strip(),
                        ElectionText.MAX_PLATFORM_CHARS);
                save();
                tell(player, "The crowd at the town hall heard your speech.");
            }
            // No voice chat, silence or no quota: the book alone is the platform.
        }));
    }

    private void announce(IColony colony, Candidate candidate, BlockPos townHall, String message) {
        BroadcastSource source = candidate.citizen
                ? BroadcastSource.addon(TownHall.MOD_ID, candidate.name)
                : BroadcastSource.player(candidate.id, candidate.name);
        BroadcastRequest request = candidate.citizen
                ? BroadcastRequest.fromCitizen(source, message, candidate.id)
                : BroadcastRequest.fromPosition(source, message, townHall);
        BroadcastPublishResult result = CitizenMemoryService.publishBroadcast(colony,
                request.withExpiry(Duration.ofHours(2)));
        if (result.status() == BroadcastPublishResult.Status.NO_RECIPIENTS && !candidate.citizen) {
            // Nobody is at the town hall: the nearest citizen hears it first and passes it on.
            AbstractEntityCitizen nearest = nearest(colony, townHall);
            if (nearest != null) {
                result = CitizenMemoryService.publishBroadcast(colony,
                        BroadcastRequest.fromCitizen(source, message, nearest.getUUID()).withExpiry(Duration.ofHours(2)));
            }
        }
        if (result.isPublished() && result.broadcastId() != null) candidate.broadcastIds.add(result.broadcastId());
        else TownHall.LOGGER.info("Campaign broadcast for {} not published: {}", candidate.name, result.status());
    }

    /** Operators: every campaign moves on to voting now. */
    public int rush() {
        long now = now();
        int count = 0;
        for (Election election : state.elections) {
            if (!election.voting) {
                election.votingAt = Math.min(election.votingAt, now);
                count++;
            }
        }
        return count;
    }

    void tick() {
        couriers.tick();
        if (++ticks % CHECK_INTERVAL_TICKS != 0) return;
        for (Election election : List.copyOf(state.elections)) {
            IColony colony = colony(election.colonyKey);
            if (colony == null) {
                state.elections.remove(election);
                save();
                continue;
            }
            if (!election.voting) {
                if (now() >= election.votingAt) campaignEnds(colony, election);
            } else {
                collectVotes(colony, election);
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            bringResults(player);
        }
    }

    private void campaignEnds(IColony colony, Election election) {
        if (election.rivalWriting) return;
        long players = election.candidates.stream().filter(candidate -> !candidate.citizen).count();
        if (players == 1 && election.candidates.size() == 1 && !election.rivalAsked) {
            election.rivalAsked = true;
            if (askRival(colony, election)) return;
        }
        election.voting = true;
        election.votingEndsAt = now() + VOTING_TICKS;
        save();
        tellMembers(colony, "Voting for mayor of " + colony.getName() + " has begun: "
                + ElectionText.join(election.candidates.stream().map(candidate -> candidate.name).toList())
                + (election.candidates.size() == 1 ? " stands alone." : " stand."));
    }

    /** The unhappiest grown citizen stands against a lone player. Returns whether they were asked. */
    private boolean askRival(IColony colony, Election election) {
        if (!TextCapacity.hasSpare()) return false;
        ICitizenData rival = voters(colony, null).stream()
                .min(Comparator.comparingDouble(data -> data.getCitizenHappinessHandler().getHappiness(colony, data)))
                .orElse(null);
        AbstractEntityCitizen entity = rival == null ? null : rival.getEntity().orElse(null);
        if (entity == null) return false;
        election.rivalWriting = true;
        List<String> opponents = election.candidates.stream().map(candidate -> candidate.name).toList();
        TextRequest request = TextRequest.of(TownHall.MOD_ID + ":rival", ElectionText.rivalDirective(colony.getName(), opponents))
                .withMaxChars(600).withResponseSchema(ElectionText.rivalSchema());
        CitizenTextService.generate(entity, request).whenComplete((result, error) -> server.execute(() -> {
            election.rivalWriting = false;
            if (!state.elections.contains(election) || election.voting) return;
            ElectionText.Rival campaign = error == null && result.isSuccess() ? ElectionText.parseRival(result.json()) : null;
            if (campaign == null) {
                TownHall.LOGGER.warn("No rival stands in {}: {}", colony.getName(),
                        error != null ? error.toString() : result.status() + " " + result.detail());
                return; // the next check starts the vote with the lone candidate
            }
            Candidate candidate = new Candidate();
            candidate.citizen = true;
            candidate.id = entity.getUUID();
            candidate.citizenId = rival.getId();
            candidate.name = rival.getName();
            candidate.slogan = campaign.slogan();
            candidate.platform = campaign.platform();
            election.candidates.add(candidate);
            election.votingAt = now() + RIVAL_CAMPAIGN_TICKS;
            announce(colony, candidate, entity.blockPosition(), ElectionText.candidacy(candidate.name, candidate.slogan, candidate.platform));
            save();
            tellMembers(colony, candidate.name + " stands for mayor against " + ElectionText.join(opponents)
                    + ": \"" + candidate.slogan + "\". Voting starts in about "
                    + RIVAL_CAMPAIGN_TICKS / 1_200 + " minutes.");
        }));
        return true;
    }

    private void collectVotes(IColony colony, Election election) {
        List<ICitizenData> waiting = voters(colony, election).stream()
                .filter(data -> !election.votes.containsKey(data.getId()))
                .filter(data -> election.attempts.getOrDefault(data.getId(), 0) < MAX_VOTE_ATTEMPTS)
                .toList();
        if ((waiting.isEmpty() || now() >= election.votingEndsAt) && election.inFlight.isEmpty()) {
            finish(colony, election);
            return;
        }
        for (ICitizenData voter : waiting) {
            if (election.inFlight.size() >= MAX_VOTES_IN_FLIGHT || !TextCapacity.hasSpare()) return;
            if (election.inFlight.contains(voter.getId())) continue;
            castVote(colony, election, voter);
        }
    }

    private void castVote(IColony colony, Election election, ICitizenData voter) {
        AbstractEntityCitizen entity = voter.getEntity().orElse(null);
        if (entity == null) return;
        List<String> names = election.candidates.stream().map(candidate -> candidate.name).toList();
        CitizenMemorySnapshot memory = CitizenMemoryService.snapshot(voter).orElse(null);
        List<ElectionText.CandidateBrief> briefs = new ArrayList<>();
        for (Candidate candidate : election.candidates) {
            briefs.add(new ElectionText.CandidateBrief(candidate.name, heardPlatform(memory, candidate),
                    ElectionText.feelings(feelings(memory, candidate.id))));
        }
        election.inFlight.add(voter.getId());
        election.attempts.merge(voter.getId(), 1, Integer::sum);
        TextRequest request = TextRequest.of(TownHall.MOD_ID + ":vote", ElectionText.voteDirective(briefs))
                .withMaxChars(400).withResponseSchema(ElectionText.voteSchema(names));
        CitizenTextService.generate(entity, request).whenComplete((result, error) -> server.execute(() -> {
            election.inFlight.remove(voter.getId());
            if (!state.elections.contains(election)) return;
            ElectionText.Vote vote = error == null && result.isSuccess() ? ElectionText.parseVote(result.json(), names) : null;
            if (vote == null) {
                TownHall.LOGGER.warn("{}'s vote failed: {}", voter.getName(),
                        error != null ? error.toString() : result.status() + " " + result.detail());
                return;
            }
            election.votes.put(voter.getId(), vote);
            election.voterNames.put(voter.getId(), voterLabel(voter));
            save();
            remember(voter, election, vote, names);
        }));
    }

    /** What the voter heard of the candidate's campaign, or null if nothing reached them. */
    private static @Nullable String heardPlatform(@Nullable CitizenMemorySnapshot memory, Candidate candidate) {
        if (memory == null) return null;
        List<String> heard = new ArrayList<>();
        for (CitizenBroadcastMemoryView broadcast : memory.broadcasts()) {
            if (candidate.broadcastIds.contains(broadcast.id())) heard.add(broadcast.message());
        }
        return heard.isEmpty() ? null : String.join(" ", heard);
    }

    private static List<ElectionText.Feeling> feelings(@Nullable CitizenMemorySnapshot memory, UUID target) {
        if (memory == null) return List.of();
        return memory.relationships().stream()
                .filter(relationship -> relationship.targetId().equals(target))
                .sorted(Comparator.comparingDouble((CitizenRelationshipView relationship) -> -Math.abs(relationship.factor())))
                .map(relationship -> new ElectionText.Feeling(
                        relationship.dimension().name().toLowerCase().replace('_', ' '), relationship.factor()))
                .toList();
    }

    /** The citizen remembers how they voted and why, so they can talk about it. */
    private static void remember(ICitizenData voter, Election election, ElectionText.Vote vote, List<String> names) {
        String event = vote.candidate() < 0
                ? "Abstained in the mayoral election" + (vote.reason().isEmpty() ? "." : ": " + vote.reason())
                : "Voted for " + names.get(vote.candidate()) + " in the mayoral election"
                + (vote.reason().isEmpty() ? "." : ": " + vote.reason());
        Candidate chosen = vote.candidate() < 0 ? null : election.candidates.get(vote.candidate());
        try {
            CitizenMemoryService.confirmOutcome(voter, new AddonConfirmedOutcome(TownHall.MOD_ID,
                    election.id + ":" + voter.getId(), ElectionText.cut(event, 300),
                    chosen != null && !chosen.citizen ? chosen.id : null, List.of(), List.of()));
        } catch (RuntimeException e) {
            TownHall.LOGGER.warn("Could not store {}'s vote as a memory", voter.getName(), e);
        }
    }

    private void finish(IColony colony, Election election) {
        state.elections.remove(election);
        state.lastElectionAt.put(election.colonyKey, now());
        List<String> names = election.candidates.stream().map(candidate -> candidate.name).toList();
        List<ElectionText.Vote> votes = List.copyOf(election.votes.values());
        int[] counts = ElectionText.tally(votes, names.size());
        List<Integer> leaders = ElectionText.leaders(counts);
        String result;
        Candidate winner = null;
        if (leaders.isEmpty()) {
            result = ElectionText.noWinner(votes.size());
        } else {
            int index = leaders.get(server.overworld().getRandom().nextInt(leaders.size()));
            winner = election.candidates.get(index);
            result = ElectionText.result(names, counts, index, votes.size(), leaders.size() > 1);
            Mayor mayor = new Mayor();
            mayor.name = winner.name;
            mayor.citizen = winner.citizen;
            mayor.id = winner.id;
            mayor.sinceDay = colony.getDay();
            mayor.result = result;
            state.mayors.put(election.colonyKey, mayor);
        }
        save();

        CitizenMemoryService.publishBroadcast(colony, BroadcastRequest.immediate(
                BroadcastSource.addon(TownHall.MOD_ID, ElectionText.SOURCE_NAME), ElectionText.cut(result, ElectionText.MAX_BROADCAST_CHARS)));
        if (TalkingColonistsApi.supports(ApiFeature.COLONY_EVENTS)) {
            ColonyEventService.record(colony, new AddonColonyEvent("elections", winner == null ? "election_undecided" : "election_won",
                    ElectionText.cut(result, AddonColonyEvent.MAX_DESCRIPTION_LENGTH)));
        }
        tellMembers(colony, result);
        TownHall.LOGGER.info("Election in {} finished: {}", colony.getName(), result);

        List<String> pages = new ArrayList<>();
        for (String page : ElectionText.resultPages(colony.getName(), colony.getDay(), result, quotes(election, winner))) {
            pages.addAll(BookPages.paginate(page, BookPages.PAGE_CHARS));
        }
        for (Candidate candidate : election.candidates) {
            if (candidate.citizen) continue;
            ResultsBook book = new ResultsBook();
            book.playerId = candidate.id;
            book.colonyKey = election.colonyKey;
            book.pages = pages;
            state.books.add(book);
        }
        save();
    }

    /** Voters in their own words: mostly the winner's, one or two of the others'. */
    private static List<ElectionText.Quote> quotes(Election election, @Nullable Candidate winner) {
        int winnerIndex = winner == null ? -2 : election.candidates.indexOf(winner);
        List<ElectionText.Quote> forWinner = new ArrayList<>();
        List<ElectionText.Quote> others = new ArrayList<>();
        for (Map.Entry<Integer, ElectionText.Vote> entry : election.votes.entrySet()) {
            ElectionText.Vote vote = entry.getValue();
            if (vote.reason().isBlank()) continue;
            String choice = vote.candidate() < 0 ? "abstained" : "for " + election.candidates.get(vote.candidate()).name;
            ElectionText.Quote quote = new ElectionText.Quote(
                    election.voterNames.getOrDefault(entry.getKey(), "A citizen") + ", " + choice, vote.reason());
            (vote.candidate() == winnerIndex ? forWinner : others).add(quote);
        }
        List<ElectionText.Quote> quotes = new ArrayList<>(forWinner.subList(0, Math.min(forWinner.size(), MAX_QUOTES - Math.min(2, others.size()))));
        quotes.addAll(others.subList(0, Math.min(others.size(), MAX_QUOTES - quotes.size())));
        return quotes;
    }

    /** A citizen brings a waiting results book to the player, once they are inside its colony. */
    private void bringResults(ServerPlayer player) {
        for (ResultsBook book : List.copyOf(state.books)) {
            if (!book.playerId.equals(player.getUUID())) continue;
            IColony colony = colony(book.colonyKey);
            if (colony == null) {
                state.books.remove(book);
                save();
                continue;
            }
            if (player.level() != colony.getWorld() || !colony.isCoordInColony(player.level(), player.blockPosition())) continue;
            String key = "results|" + book.id;
            if (couriers.isRunning(key)) return;
            couriers.dispatch(key, colony, player, null, "the election results", "The colony just elected its mayor.",
                    () -> WrittenBooks.create("Election results", ElectionText.SOURCE_NAME, WrittenBooks.ORIGINAL,
                            book.pages.stream().map(page -> (Component) Component.literal(page)).toList()),
                    delivered -> {
                        if (delivered && state.books.remove(book)) save();
                    });
            return;
        }
    }

    /** Grown, loaded citizens who may vote: everyone but children and the citizen candidates. */
    private static List<ICitizenData> voters(IColony colony, @Nullable Election election) {
        List<ICitizenData> voters = new ArrayList<>();
        for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
            if (data.isChild() || data.getEntity().isEmpty()) continue;
            if (election != null && election.candidates.stream().anyMatch(c -> c.citizen && c.citizenId == data.getId())) continue;
            voters.add(data);
        }
        return voters;
    }

    private static @Nullable AbstractEntityCitizen nearest(IColony colony, BlockPos pos) {
        AbstractEntityCitizen best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
            AbstractEntityCitizen citizen = data.getEntity().orElse(null);
            if (citizen == null || !citizen.isAlive() || citizen.level() != colony.getWorld()) continue;
            double distance = citizen.blockPosition().distSqr(pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = citizen;
            }
        }
        return best;
    }

    private static String voterLabel(ICitizenData data) {
        try {
            String job = data.getJob() == null ? "" : data.getJob().getJobRegistryEntry().getKey().getPath().replace('_', ' ');
            return job.isEmpty() ? data.getName() : data.getName() + ", " + job;
        } catch (RuntimeException e) {
            return data.getName();
        }
    }

    private void tellMembers(IColony colony, String text) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (colony.getPermissions().isColonyMember(player)) tell(player, text);
        }
    }

    static void tell(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[Town hall] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }

    private static @Nullable IColony colony(String key) {
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (key(colony).equals(key)) return colony;
        }
        return null;
    }

    static String key(IColony colony) {
        return colony.getDimension().location() + "|" + colony.getID();
    }

    void stopAll() {
        couriers.stopAll();
    }

    void load() {
        try {
            JsonObject root = JsonFile.read(file);
            State loaded = root == null ? null : GSON.fromJson(root, State.class);
            state = loaded == null ? new State() : loaded;
            for (Election election : state.elections) {
                election.inFlight = new HashSet<>();
            }
        } catch (IOException | RuntimeException e) {
            TownHall.LOGGER.error("Could not read {}; starting with no elections", file, e);
            state = new State();
        }
    }

    void save() {
        try {
            JsonFile.write(file, GSON.toJsonTree(state).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            TownHall.LOGGER.error("Could not save {}", file, e);
        }
    }
}
