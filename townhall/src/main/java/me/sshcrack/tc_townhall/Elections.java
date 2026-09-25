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
import java.util.stream.Collectors;

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
        /** Colony members were told voting starts in a minute. */
        public boolean reminded;
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
        /** The citizen's colony id, -1 for a player (or a mayor elected before it was saved). */
        public int citizenId = -1;
        public int sinceDay;
        public String result = "";
        /** The campaign they were elected on. */
        public String platform = "";
        public List<Office.Promise> promises = new ArrayList<>();
        /** Attempts to read the promises out of the platform. */
        public int promiseAttempts;
        public int lastReportDay = -1;
        /** Need id → the day it began (it has lasted since). */
        public Map<String, Integer> needSince = new HashMap<>();
        /** Need id → how often the mayor reported it while it lasted. */
        public Map<String, Integer> needReported = new HashMap<>();
        /** The proposal waiting for an answer, or accepted and not done yet. */
        public @Nullable Office.Proposal proposal;
        /** Finished proposals, oldest first. */
        public List<Office.Proposal> proposals = new ArrayList<>();
        /** A player mayor received the mayor's hat. */
        public boolean hatDelivered;
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
    private final ElectionBars bars;
    private final MayorsOffice office;
    private State state = new State();
    private final Set<UUID> speaking = new HashSet<>();
    private int ticks;

    Elections(MinecraftServer server, Path file) {
        this.server = server;
        this.file = file;
        this.couriers = new Couriers(server, TownHall.MOD_ID + ":results");
        this.bars = new ElectionBars(server);
        this.office = new MayorsOffice(server, this);
    }

    public MayorsOffice office() {
        return office;
    }

    /** Colony key → its mayor; the office changes it too. */
    Map<String, Mayor> mayors() {
        return state.mayors;
    }

    /**
     * Makes a citizen the mayor without an election, on {@code platform}. For the dev self-test, which
     * cannot rely on who wins a real vote.
     */
    public Mayor appoint(IColony colony, ICitizenData data, String platform) {
        Mayor mayor = new Mayor();
        mayor.name = data.getName();
        mayor.citizen = true;
        mayor.id = data.getEntity().map(AbstractEntityCitizen::getUUID).orElse(new UUID(0, 0));
        mayor.citizenId = data.getId();
        mayor.sinceDay = colony.getDay();
        mayor.result = data.getName() + " was appointed mayor.";
        mayor.platform = platform;
        Mayor previous = state.mayors.put(key(colony), mayor);
        office.elected(colony, previous, mayor);
        save();
        return mayor;
    }

    /** The mayor is gone: no mayor, and a new election may be called right away. */
    void vacate(String key) {
        state.mayors.remove(key);
        state.lastElectionAt.remove(key);
        save();
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
        String key = mayorKeyById(colonyId);
        return key == null ? null : state.mayors.get(key);
    }

    @Nullable String mayorKeyById(int colonyId) {
        return state.mayors.keySet().stream().filter(key -> key.endsWith("|" + colonyId)).findFirst().orElse(null);
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
        return stand(player, colony, text.title(), text.body(), townHall);
    }

    /**
     * The player stands for mayor with a slogan and a platform, typed in the Ballot Box window or read from
     * a book; citizens near {@code where} hear it first. Then, with voice chat, they give a speech.
     */
    public boolean stand(ServerPlayer player, IColony colony, String title, String body, BlockPos where) {
        if (title.isBlank() || body.isBlank()) {
            tell(player, "Give your campaign a slogan and a few words about what you will do.");
            return false;
        }
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
        candidate.slogan = ElectionText.slogan(title);
        candidate.platform = ElectionText.cut(body.strip().replaceAll("\\s+", " "), ElectionText.MAX_PLATFORM_CHARS);
        announce(colony, candidate, where, ElectionText.candidacy(candidate.name, candidate.slogan, candidate.platform));
        save();

        long minutes = Math.max(1, (election.votingAt - now()) / 1_200);
        tell(player, (renewed ? "Your platform is updated: \"" : "You stand for mayor of " + colony.getName() + " with \"")
                + candidate.slogan + "\". Citizens nearby heard it and will spread the word. Voting starts in about "
                + minutes + " minutes; talk to your citizens until then.");
        giveSpeech(player, colony, election, candidate, where);
        return true;
    }

    /** A player candidate speaks to the crowd again during the campaign. Returns whether the speech starts. */
    public boolean speakAgain(ServerPlayer player, IColony colony, BlockPos where) {
        Election election = election(colony);
        Candidate candidate = election == null ? null : election.candidates.stream()
                .filter(c -> !c.citizen && c.id.equals(player.getUUID())).findFirst().orElse(null);
        if (candidate == null || election.voting) {
            tell(player, "Only candidates can give a speech, and only before voting begins.");
            return false;
        }
        if (!TalkingColonistsApi.supports(ApiFeature.PLAYER_SPEECH_CAPTURE)) {
            tell(player, "Speeches need Simple Voice Chat.");
            return false;
        }
        giveSpeech(player, colony, election, candidate, where);
        return true;
    }

    /** With voice chat: the player has 30 seconds to speak; the transcript is heard by citizens near {@code where}. */
    private void giveSpeech(ServerPlayer player, IColony colony, Election election, Candidate candidate, BlockPos where) {
        if (!TalkingColonistsApi.supports(ApiFeature.PLAYER_SPEECH_CAPTURE) || !speaking.add(player.getUUID())) return;
        PlayerSpeechCapture.capture(player, SPEECH_DURATION).whenComplete((result, error) -> server.execute(() -> {
            speaking.remove(player.getUUID());
            if (error != null || result == null) return;
            if (result.status() == SpeechCaptureResult.Status.TRANSCRIBED && result.transcript() != null
                    && !result.transcript().isBlank()) {
                if (!state.elections.contains(election) || election.voting) return;
                announce(colony, candidate, where, ElectionText.speech(candidate.name, result.transcript()));
                candidate.platform = ElectionText.cut(candidate.platform + " Speech: " + result.transcript().strip(),
                        ElectionText.MAX_PLATFORM_CHARS);
                save();
                tell(player, "The crowd heard your speech.");
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

    /** What the colony's Ballot Box shows right now. */
    public BallotView.Phase phase(IColony colony) {
        Election election = election(colony);
        if (election == null) return BallotView.Phase.IDLE;
        return election.voting ? BallotView.Phase.VOTING : BallotView.Phase.CAMPAIGN;
    }

    /** The Ballot Box window's view of the colony's election, for {@code player}. */
    public BallotView view(IColony colony, ServerPlayer player) {
        BallotView view = new BallotView();
        view.colony = colony.getName();
        view.member = colony.getPermissions().isColonyMember(player);
        view.speechSupported = TalkingColonistsApi.supports(ApiFeature.PLAYER_SPEECH_CAPTURE);
        Election election = election(colony);
        view.phase = phase(colony);
        if (election != null) {
            long until = election.voting ? election.votingEndsAt : election.votingAt;
            view.minutesLeft = (int) Math.max(0, (until - now() + 1_199) / 1_200);
            int[] counts = ElectionText.tally(List.copyOf(election.votes.values()), election.candidates.size());
            for (int i = 0; i < election.candidates.size(); i++) {
                Candidate candidate = election.candidates.get(i);
                BallotView.Entry entry = new BallotView.Entry();
                entry.name = candidate.name;
                entry.slogan = candidate.slogan;
                entry.platform = candidate.platform;
                entry.citizen = candidate.citizen;
                entry.you = !candidate.citizen && candidate.id.equals(player.getUUID());
                entry.votes = counts[i];
                view.candidates.add(entry);
                if (entry.you) view.youStand = true;
            }
            view.voted = election.votes.size();
            view.voters = Math.max(view.voted, voters(colony, election).size());
            election.votes.forEach((citizenId, vote) -> {
                BallotView.Reason reason = new BallotView.Reason();
                reason.voter = election.voterNames.getOrDefault(citizenId, "A citizen");
                reason.candidate = vote.candidate() >= 0 && vote.candidate() < election.candidates.size()
                        ? election.candidates.get(vote.candidate()).name : "";
                reason.reason = vote.reason();
                view.reasons.add(reason);
            });
            view.canStand = view.member && !election.voting && (view.youStand || election.candidates.size() < MAX_CANDIDATES);
        } else {
            Long last = state.lastElectionAt.get(key(colony));
            view.nextElectionDays = last == null ? 0 : (int) Math.max(0, (COOLDOWN_TICKS - (now() - last) + 23_999) / 24_000);
            view.canStand = view.member && view.nextElectionDays == 0;
        }
        Mayor mayor = mayor(colony);
        if (mayor != null) {
            String key = key(colony);
            view.mayor = mayor.name;
            view.mayorSinceDay = mayor.sinceDay;
            view.mayorResult = mayor.result;
            view.youAreMayor = !mayor.citizen && mayor.id.equals(player.getUUID());
            view.promises = office.promiseLines(key, mayor);
            if (mayor.proposal != null) {
                view.proposal = mayor.proposal.status == Office.ProposalStatus.PENDING
                        ? "The mayor proposes to " + mayor.proposal.what() + "." : OfficeText.proposalLine(mayor.proposal);
                view.canAnswer = view.member && mayor.proposal.status == Office.ProposalStatus.PENDING;
            }
            for (Office.Proposal proposal : mayor.proposals) view.proposals.add(OfficeText.proposalLine(proposal));
        }
        return view;
    }

    private void showBar(IColony colony, Election election) {
        long left = Math.max(0, (election.voting ? election.votingEndsAt : election.votingAt) - now());
        int voters = Math.max(election.votes.size(), voters(colony, election).size());
        float progress = election.voting ? (voters == 0 ? 0 : (float) election.votes.size() / voters)
                : 1 - (float) left / CAMPAIGN_TICKS;
        bars.show(colony, election.id, election.voting,
                ElectionText.barTitle(election.voting, (int) ((left + 1_199) / 1_200), election.votes.size(), voters), progress);
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
        office.tick();
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
                else if (!election.reminded && election.votingAt - now() <= 1_200) {
                    election.reminded = true;
                    tellMembers(colony, "Voting for mayor of " + colony.getName() + " starts in a minute.");
                }
            } else {
                collectVotes(colony, election);
            }
            if (state.elections.contains(election)) showBar(colony, election);
        }
        bars.keepOnly(state.elections.stream().map(election -> election.id).collect(Collectors.toSet()));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            bringResults(player);
        }
    }

    private void campaignEnds(IColony colony, Election election) {
        if (election.rivalWriting) return;
        long players = election.candidates.stream().filter(candidate -> !candidate.citizen).count();
        boolean lone = players == 1 && election.candidates.size() == 1;
        if ((lone || incumbent(colony, election) != null) && !election.rivalAsked
                && election.candidates.size() < MAX_CANDIDATES) {
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

    /** A sitting citizen mayor who is around and not on the ballot yet, or null. */
    private @Nullable ICitizenData incumbent(IColony colony, Election election) {
        Mayor mayor = state.mayors.get(election.colonyKey);
        if (mayor == null || !mayor.citizen) return null;
        ICitizenData data = MayorsOffice.citizen(colony, mayor);
        if (data == null || data.isChild() || data.getEntity().isEmpty()) return null;
        if (election.candidates.stream().anyMatch(candidate -> candidate.citizen && candidate.citizenId == data.getId())) return null;
        return data;
    }

    /**
     * A citizen stands: the sitting mayor for re-election, or else the unhappiest grown citizen against a
     * lone player. Returns whether they were asked.
     */
    private boolean askRival(IColony colony, Election election) {
        if (!TextCapacity.hasSpare()) return false;
        ICitizenData incumbent = incumbent(colony, election);
        ICitizenData rival = incumbent != null ? incumbent : voters(colony, null).stream()
                .min(Comparator.comparingDouble(data -> data.getCitizenHappinessHandler().getHappiness(colony, data)))
                .orElse(null);
        AbstractEntityCitizen entity = rival == null ? null : rival.getEntity().orElse(null);
        if (entity == null) return false;
        election.rivalWriting = true;
        List<String> opponents = election.candidates.stream().map(candidate -> candidate.name).toList();
        String directive = ElectionText.rivalDirective(colony.getName(), opponents);
        if (incumbent != null) {
            String record = office.record(election.colonyKey, state.mayors.get(election.colonyKey));
            directive += " You are the sitting mayor and stand for re-election, so defend what you did in office"
                    + (record.isBlank() ? "." : ": " + record);
        }
        TextRequest request = TextRequest.of(TownHall.MOD_ID + ":rival", directive)
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
                    ElectionText.feelings(feelings(memory, candidate.id)), record(election.colonyKey, candidate)));
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

    /**
     * What a candidate did that voters know about: the sitting mayor's promises and proposals, or how a
     * player answered a citizen mayor's proposals. Null when there is nothing.
     */
    private @Nullable String record(String key, Candidate candidate) {
        Mayor mayor = state.mayors.get(key);
        if (mayor == null) return null;
        if (mayor.id.equals(candidate.id)) {
            String record = office.record(key, mayor);
            return record.isBlank() ? "the sitting mayor since day " + mayor.sinceDay + "." : "the sitting mayor since day "
                    + mayor.sinceDay + ". " + record;
        }
        if (!mayor.citizen || candidate.citizen) return null;
        List<Office.Proposal> answered = new ArrayList<>();
        for (Office.Proposal proposal : mayor.proposals) {
            if (candidate.id.equals(proposal.playerId)) answered.add(proposal);
        }
        if (mayor.proposal != null && candidate.id.equals(mayor.proposal.playerId)) answered.add(mayor.proposal);
        return OfficeText.playerRecord(candidate.name, answered);
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
            mayor.citizenId = winner.citizen ? winner.citizenId : -1;
            mayor.sinceDay = colony.getDay();
            mayor.result = result;
            mayor.platform = winner.platform;
            Mayor previous = state.mayors.put(election.colonyKey, mayor);
            office.elected(colony, previous, mayor);
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

    static @Nullable IColony colony(String key) {
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
        office.stopAll();
        bars.clear();
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
