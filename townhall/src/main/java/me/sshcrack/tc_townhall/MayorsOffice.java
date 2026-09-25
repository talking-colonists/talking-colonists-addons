package me.sshcrack.tc_townhall;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.inventory.InventoryCitizen;
import me.sshcrack.mc_talking.api.ApiFeature;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationService;
import me.sshcrack.mc_talking.api.conversation.ControlledConversationOptions;
import me.sshcrack.mc_talking.api.conversation.ConversationStartResult;
import me.sshcrack.mc_talking.api.conversation.PlayerConversationOptions;
import me.sshcrack.mc_talking.api.memory.AddonConfirmedOutcome;
import me.sshcrack.mc_talking.api.memory.BroadcastRequest;
import me.sshcrack.mc_talking.api.memory.BroadcastSource;
import me.sshcrack.mc_talking.api.memory.CitizenMemoryService;
import me.sshcrack.mc_talking.api.text.CitizenTextService;
import me.sshcrack.mc_talking.api.text.TextRequest;
import me.sshcrack.mc_talking.api.tool.AiToolRegistry;
import me.sshcrack.tc_townhall.block.TownHallBlocks;
import me.sshcrack.tc_townhall.shared.book.BookPages;
import me.sshcrack.tc_townhall.shared.book.WrittenBooks;
import me.sshcrack.tc_townhall.shared.delivery.Couriers;
import me.sshcrack.tc_townhall.shared.provider.TextCapacity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mayor's term. A citizen mayor wears the mayor's hat, walks up to a player once a day with a
 * written report on what the colony lacks, talks it through and puts a proposal to them; the colony
 * hears the report and the answer at once. A player mayor is brought the hat. Either way the promises
 * of their campaign are measured against the colony. Server thread only, except the read-only views
 * for prompts.
 */
public final class MayorsOffice {
    static final String TOOL = "answer_proposal";
    static final String TOOL_ID = TownHall.MOD_ID + ":" + TOOL;
    static final int MAX_REPORTED_NEEDS = 3;
    private static final int CHECK_TICKS = 200;
    private static final int MAX_PROMISE_ATTEMPTS = 2;
    private static final int MAX_REASON_CHARS = 160;

    private final MinecraftServer server;
    private final Elections elections;
    private final Couriers couriers;
    /** Colony key → the needs at the last check; prompts read them, possibly off the server thread. */
    private final Map<String, Map<Need, Integer>> counts = new ConcurrentHashMap<>();
    private final Set<String> reading = new HashSet<>();
    private int ticks;

    MayorsOffice(MinecraftServer server, Elections elections) {
        this.server = server;
        this.elections = elections;
        this.couriers = new Couriers(server, TownHall.MOD_ID + ":mayor");
    }

    void tick() {
        couriers.tick();
        if (++ticks % CHECK_TICKS == 0) check(false);
    }

    /** Operators: every citizen mayor reports now, whatever the time of day. */
    public int reportNow() {
        int count = 0;
        for (Elections.Mayor mayor : elections.mayors().values()) {
            if (!mayor.citizen) continue;
            mayor.lastReportDay = -1;
            count++;
        }
        check(true);
        return count;
    }

    private void check(boolean now) {
        boolean changed = false;
        for (Map.Entry<String, Elections.Mayor> entry : List.copyOf(elections.mayors().entrySet())) {
            String key = entry.getKey();
            Elections.Mayor mayor = entry.getValue();
            IColony colony = Elections.colony(key);
            if (colony == null) continue;
            int day = colony.getDay();
            Map<Need, Integer> needs = ColonyNeeds.count(colony);
            counts.put(key, needs);
            changed |= track(mayor, needs, day);
            if (mayor.promises.isEmpty() && mayor.promiseAttempts < MAX_PROMISE_ATTEMPTS && !mayor.platform.isBlank()) {
                readPromises(colony, key, mayor);
            }
            changed |= followUp(colony, mayor, needs, day);
            if (mayor.citizen) {
                ICitizenData data = citizen(colony, mayor);
                if (data == null) {
                    if (mayor.citizenId >= 0) vacate(colony, key, mayor);
                    continue;
                }
                AbstractEntityCitizen entity = data.getEntity().orElse(null);
                if (entity == null) continue;
                wearHat(data);
                if (mayor.proposal == null) {
                    mayor.proposal = Office.choose(needs, mayor.needSince, ColonyNeeds.huts(colony), mayor.proposals, day);
                    changed |= mayor.proposal != null;
                }
                changed |= report(colony, key, mayor, entity, needs, day, now);
            } else if (!mayor.hatDelivered) {
                deliverHat(colony, key, mayor);
            }
        }
        if (changed) elections.save();
    }

    // ── Needs and promises ──────────────────────────────────────────────────

    /** Remembers since when each need lasts; one that went away starts over. */
    private static boolean track(Elections.Mayor mayor, Map<Need, Integer> needs, int day) {
        boolean changed = false;
        for (Need need : Need.values()) {
            if (needs.getOrDefault(need, 0) > 0) {
                changed |= mayor.needSince.putIfAbsent(need.id(), day) == null;
            } else {
                changed |= mayor.needSince.remove(need.id()) != null;
                mayor.needReported.remove(need.id());
            }
        }
        return changed;
    }

    /** The biggest needs right now, the ones the mayor reports. */
    private List<Need> topNeeds(String key) {
        Map<Need, Integer> needs = counts.getOrDefault(key, Map.of());
        List<Need> sorted = new ArrayList<>();
        for (Need need : Need.values()) {
            if (needs.getOrDefault(need, 0) > 0) sorted.add(need);
        }
        sorted.sort(Comparator.comparingInt((Need need) -> -needs.get(need)));
        return sorted.subList(0, Math.min(MAX_REPORTED_NEEDS, sorted.size()));
    }

    /** The biggest needs right now, as the mayor reports them. */
    List<String> needLines(String key, Elections.Mayor mayor) {
        Map<Need, Integer> needs = counts.getOrDefault(key, Map.of());
        List<String> lines = new ArrayList<>();
        for (Need need : topNeeds(key)) {
            lines.add(OfficeText.needLine(need, needs.get(need), mayor.needSince.getOrDefault(need.id(), mayor.sinceDay),
                    mayor.needReported.getOrDefault(need.id(), 0)));
        }
        return lines;
    }

    List<String> promiseLines(String key, Elections.Mayor mayor) {
        Map<Need, Integer> needs = counts.getOrDefault(key, Map.of());
        List<String> lines = new ArrayList<>();
        for (Office.Promise promise : mayor.promises) {
            Need need = Need.byId(promise.need);
            lines.add(OfficeText.promiseLine(promise, need == null ? 0 : needs.getOrDefault(need, 0)));
        }
        return lines;
    }

    /** The mayor's record: promises and proposals, the open one included. */
    public String record(String key, Elections.Mayor mayor) {
        List<Office.Proposal> proposals = new ArrayList<>(mayor.proposals);
        if (mayor.proposal != null) proposals.add(mayor.proposal);
        return OfficeText.record(mayor.name, promiseLines(key, mayor), proposals);
    }

    /** A citizen reads the promises out of the new mayor's campaign; they are measured from now on. */
    private void readPromises(IColony colony, String key, Elections.Mayor mayor) {
        if (reading.contains(key) || !TextCapacity.hasSpare()) return;
        AbstractEntityCitizen reader = null;
        ICitizenData self = mayor.citizen ? citizen(colony, mayor) : null;
        if (self != null) reader = self.getEntity().orElse(null);
        if (reader == null) {
            for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
                if (!data.isChild() && data.getEntity().isPresent()) {
                    reader = data.getEntity().get();
                    break;
                }
            }
        }
        if (reader == null) return;
        reading.add(key);
        mayor.promiseAttempts++;
        TextRequest request = TextRequest.of(TownHall.MOD_ID + ":promises", OfficeText.promiseDirective(mayor.name, mayor.platform))
                .withMaxChars(500).withResponseSchema(OfficeText.promiseSchema());
        CitizenTextService.generate(reader, request).whenComplete((result, error) -> server.execute(() -> {
            reading.remove(key);
            if (elections.mayors().get(key) != mayor) return;
            if (error != null || !result.isSuccess()) {
                TownHall.LOGGER.warn("Could not read {}'s promises: {}", mayor.name,
                        error != null ? error.toString() : result.status() + " " + result.detail());
                elections.save();
                return;
            }
            List<Office.Promise> promises = OfficeText.parsePromises(result.json());
            Map<Need, Integer> needs = counts.getOrDefault(key, ColonyNeeds.count(colony));
            for (Office.Promise promise : promises) {
                Need need = Need.byId(promise.need);
                promise.before = need == null ? 0 : needs.getOrDefault(need, 0);
            }
            mayor.promises = promises;
            if (promises.isEmpty()) mayor.promiseAttempts = MAX_PROMISE_ATTEMPTS;
            elections.save();
            TownHall.LOGGER.info("Mayor {} of {} promised: {}", mayor.name, colony.getName(),
                    promises.stream().map(promise -> promise.need + " \"" + promise.summary + "\"").toList());
        }));
    }

    // ── Reports ─────────────────────────────────────────────────────────────

    /** Once a colony day, by daylight, the mayor walks up to a member nearby with the report. */
    private boolean report(IColony colony, String key, Elections.Mayor mayor, AbstractEntityCitizen entity,
                           Map<Need, Integer> needs, int day, boolean now) {
        String job = "report|" + key;
        if (mayor.lastReportDay == day || couriers.isRunning(job)) return false;
        if (!now && (!colony.getWorld().isDay() || entity.isSleeping())) return false;
        ServerPlayer player = nearestMember(colony, entity);
        if (player == null) return false;
        Office.Proposal pending = mayor.proposal != null && mayor.proposal.status == Office.ProposalStatus.PENDING ? mayor.proposal : null;
        List<Need> reported = topNeeds(key);
        List<String> needLines = needLines(key, mayor);
        if (needLines.isEmpty() && pending == null) {
            mayor.lastReportDay = day; // nothing to report today
            return true;
        }
        List<String> promiseLines = promiseLines(key, mayor);
        List<String> pages = new ArrayList<>();
        for (String page : OfficeText.reportPages(colony.getName(), mayor.name, day, needLines, promiseLines, pending)) {
            pages.addAll(BookPages.paginate(page, BookPages.PAGE_CHARS));
        }
        String playerName = player.getGameProfile().getName();
        boolean sent = couriers.bring(job, entity, player, "the mayor's report",
                () -> WrittenBooks.create("Mayor's report, day " + day, mayor.name, WrittenBooks.ORIGINAL,
                        pages.stream().map(page -> (Component) Component.literal(page)).toList()),
                delivered -> {
                    if (!delivered) return;
                    mayor.lastReportDay = day;
                    for (Need need : reported) mayor.needReported.merge(need.id(), 1, Integer::sum);
                    elections.save();
                    Need top = top(needs);
                    publish(colony, mayor, OfficeText.statement(mayor.name, playerName,
                            top == null ? null : top.describe(needs.get(top)), pending));
                    talk(player, entity, needLines, promiseLines, pending);
                });
        if (sent) TownHall.LOGGER.info("Mayor {} walks to {} with the report", mayor.name, playerName);
        return false;
    }

    /** After the hand-over: a conversation about the report, where the player can answer the proposal. */
    private void talk(ServerPlayer player, AbstractEntityCitizen mayor, List<String> needLines, List<String> promiseLines,
                      @Nullable Office.Proposal pending) {
        String name = player.getGameProfile().getName();
        if (TalkingColonistsApi.supports(ApiFeature.PLAYER_CONVERSATION_OPTIONS)) {
            PlayerConversationOptions options = PlayerConversationOptions.defaults()
                    .withAgenda(OfficeText.agenda(name, needLines, promiseLines, pending, AiToolRegistry.providerName(TownHall.MOD_ID, TOOL)))
                    .withTools(ControlledConversationOptions.allowAddonTools(Set.of(TOOL_ID)))
                    .withPurpose(TownHall.MOD_ID + ":mayor_report");
            ConversationStartResult result = CitizenConversationService.startPlayerConversation(player, mayor, options);
            if (result.started()) {
                if (TalkingColonistsApi.supports(ApiFeature.PLAYER_TEXT_INPUT)) {
                    CitizenConversationService.addContext(player, mayor, OfficeText.opening(name));
                }
                return;
            }
            TownHall.LOGGER.info("The mayor's report conversation did not start: {} {}", result.status(), result.detail());
        }
        String point = pending != null ? "your proposal: " + pending.what() : needLines.isEmpty() ? "all is well" : needLines.get(0);
        CitizenConversationService.requestAmbientLine(mayor, "As the colony's mayor you just handed " + name
                + " your written report. Tell them in one or two sentences the most important point, " + point + ".");
    }

    private static @Nullable Need top(Map<Need, Integer> needs) {
        Need top = null;
        for (Need need : Need.values()) {
            int count = needs.getOrDefault(need, 0);
            if (count > 0 && (top == null || count > needs.get(top))) top = need;
        }
        return top;
    }

    private static @Nullable ServerPlayer nearestMember(IColony colony, AbstractEntityCitizen entity) {
        ServerPlayer best = null;
        double bestDistance = Couriers.MAX_RANGE * Couriers.MAX_RANGE;
        for (ServerPlayer player : entity.level().players().stream().filter(ServerPlayer.class::isInstance).map(ServerPlayer.class::cast).toList()) {
            if (player.isSpectator() || !colony.getPermissions().isColonyMember(player)
                    || !colony.isCoordInColony(player.level(), player.blockPosition())
                    || CitizenConversationService.isPlayerInConversation(player)) {
                continue;
            }
            double distance = player.distanceToSqr(entity);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    // ── Proposals ───────────────────────────────────────────────────────────

    /**
     * {@code player} answers the mayor's open proposal: accepting an upgrade orders it from the builders,
     * accepting a new hut is a promise to place one. Returns what happened, for the player or the model.
     */
    public String answer(IColony colony, ServerPlayer player, boolean accept, String reason) {
        String key = Elections.key(colony);
        Elections.Mayor mayor = elections.mayors().get(key);
        Office.Proposal proposal = mayor == null ? null : mayor.proposal;
        if (proposal == null || proposal.status != Office.ProposalStatus.PENDING) return "There is no proposal waiting for an answer.";
        if (!colony.getPermissions().isColonyMember(player)) return "Only members of " + colony.getName() + " can answer the mayor.";
        int day = colony.getDay();
        proposal.player = player.getGameProfile().getName();
        proposal.playerId = player.getUUID();
        proposal.answeredDay = day;
        proposal.reason = ElectionText.cut(reason.strip().replaceAll("\\s+", " "), MAX_REASON_CHARS);
        String outcome;
        if (!accept) {
            proposal.status = Office.ProposalStatus.REFUSED;
            outcome = "You turned down the mayor's proposal to " + proposal.what() + ".";
        } else if (proposal.kind == Office.ProposalKind.BUILD) {
            proposal.status = Office.ProposalStatus.ACCEPTED;
            outcome = "You agreed to " + proposal.what() + ". Place its hut within " + Office.BUILD_DAYS + " days; the colony will remember.";
        } else {
            BlockPos pos = BlockPos.of(proposal.pos);
            IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
            if (building != null && !ColonyNeeds.hasWorkOrder(colony, pos)) building.requestUpgrade(player, BlockPos.ZERO);
            if (building != null && ColonyNeeds.hasWorkOrder(colony, pos)) {
                proposal.status = Office.ProposalStatus.ACCEPTED;
                outcome = "You agreed to " + proposal.what() + ". The work order is placed; a builder will take it on.";
            } else {
                proposal.status = Office.ProposalStatus.FAILED;
                outcome = "You agreed, but no builder can take it on" + (building == null ? ": the hut is gone." : " (see the message above).");
            }
        }
        publish(colony, mayor, OfficeText.answered(mayor.name, proposal));
        remember(colony, mayor, proposal);
        if (!proposal.status.open()) archive(mayor, proposal);
        elections.save();
        Elections.tell(player, outcome);
        TownHall.LOGGER.info("{} answered mayor {}'s proposal to {}: {}", proposal.player, mayor.name, proposal.what(), proposal.status);
        return outcome;
    }

    /** Proposals nobody answered, and accepted ones that got done or were not kept. */
    private boolean followUp(IColony colony, Elections.Mayor mayor, Map<Need, Integer> needs, int day) {
        Office.Proposal proposal = mayor.proposal;
        if (proposal == null) return false;
        Need need = Need.byId(proposal.need);
        if (proposal.status == Office.ProposalStatus.PENDING) {
            if (need == null || needs.getOrDefault(need, 0) == 0) {
                mayor.proposal = null; // solved another way: nothing to ask any more
                return true;
            }
            if (day - proposal.madeDay < Office.ANSWER_DAYS) return false;
            proposal.status = Office.ProposalStatus.IGNORED;
        } else if (proposal.kind == Office.ProposalKind.BUILD) {
            IBuilding hut = colony.getServerBuildingManager().getBuildings().values().stream()
                    .filter(building -> ColonyNeeds.type(building).equals(proposal.building))
                    .max(Comparator.comparingInt(IBuilding::getBuildingLevel)).orElse(null);
            if (hut == null) {
                if (day - proposal.answeredDay <= Office.BUILD_DAYS) return false;
                proposal.status = Office.ProposalStatus.BROKEN;
            } else if (hut.getBuildingLevel() >= 1) {
                proposal.status = Office.ProposalStatus.DONE;
            } else {
                boolean changed = false;
                if (proposal.placedDay < 0) {
                    proposal.placedDay = day;
                    proposal.pos = hut.getPosition().asLong();
                    changed = true;
                }
                // The player agreed to it being built: once the hut stands, the mayor orders the build.
                ServerPlayer player = proposal.playerId == null ? null : server.getPlayerList().getPlayer(proposal.playerId);
                if (player != null && !ColonyNeeds.hasWorkOrder(colony, hut.getPosition())) {
                    hut.requestUpgrade(player, BlockPos.ZERO);
                    if (ColonyNeeds.hasWorkOrder(colony, hut.getPosition())) {
                        TownHall.LOGGER.info("Mayor {} ordered the build of the new {}", mayor.name, proposal.building);
                    }
                }
                if (day - proposal.placedDay <= Office.CONSTRUCTION_DAYS) return changed;
                archive(mayor, proposal); // still with the builders: part of the record, not waited on any more
                return true;
            }
        } else {
            BlockPos pos = BlockPos.of(proposal.pos);
            IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
            if (building != null && building.getBuildingLevel() > proposal.level) proposal.status = Office.ProposalStatus.DONE;
            else if (building == null || !ColonyNeeds.hasWorkOrder(colony, pos)) proposal.status = Office.ProposalStatus.BROKEN;
            else if (day - proposal.answeredDay > Office.UPGRADE_DAYS) {
                archive(mayor, proposal); // still with the builders: part of the record, not waited on any more
                return true;
            } else return false;
        }
        publish(colony, mayor, OfficeText.outcome(mayor.name, proposal));
        remember(colony, mayor, proposal);
        archive(mayor, proposal);
        return true;
    }

    private static void archive(Elections.Mayor mayor, Office.Proposal proposal) {
        if (mayor.proposal == proposal) mayor.proposal = null;
        Office.archive(mayor.proposals, proposal);
    }

    /** The colony hears it at once: the mayor's word travels faster than gossip. */
    private void publish(IColony colony, Elections.Mayor mayor, String message) {
        if (message.isBlank()) return;
        CitizenMemoryService.publishBroadcast(colony, BroadcastRequest.immediate(
                BroadcastSource.addon(TownHall.MOD_ID, "Mayor " + mayor.name), message));
    }

    /** A citizen mayor remembers how the player answered and how it turned out. */
    private static void remember(IColony colony, Elections.Mayor mayor, Office.Proposal proposal) {
        if (!mayor.citizen) return;
        ICitizenData data = citizen(colony, mayor);
        if (data == null) return;
        String event = "As mayor I proposed to " + proposal.what() + ": " + OfficeText.proposalLine(proposal)
                + (proposal.reason.isBlank() ? "." : ". " + proposal.player + " said: \"" + proposal.reason + "\"");
        try {
            CitizenMemoryService.confirmOutcome(data, new AddonConfirmedOutcome(TownHall.MOD_ID,
                    "proposal:" + proposal.id + ":" + proposal.status, ElectionText.cut(event, 300),
                    proposal.playerId, List.of(), List.of()));
        } catch (RuntimeException e) {
            TownHall.LOGGER.warn("Could not store the proposal as {}'s memory", mayor.name, e);
        }
    }

    // ── The hat and the office itself ───────────────────────────────────────

    /** Called when an election has a winner: the hat changes heads, and a re-elected mayor keeps their record. */
    void elected(IColony colony, @Nullable Elections.Mayor previous, Elections.Mayor mayor) {
        if (previous != null && previous.id.equals(mayor.id)) {
            mayor.proposal = previous.proposal;
            mayor.proposals = previous.proposals;
            mayor.hatDelivered = previous.hatDelivered;
        } else if (previous != null && previous.citizen) {
            ICitizenData old = citizen(colony, previous);
            if (old != null) takeHat(old);
        }
        if (mayor.citizen) {
            ICitizenData data = citizen(colony, mayor);
            if (data != null) wearHat(data);
        }
    }

    private static void wearHat(ICitizenData data) {
        InventoryCitizen inventory = data.getInventory();
        if (inventory.getArmorInSlot(EquipmentSlot.HEAD).isEmpty()) {
            inventory.forceArmorStackToSlot(EquipmentSlot.HEAD, new ItemStack(TownHallBlocks.MAYOR_HAT.get()));
        }
    }

    private static void takeHat(ICitizenData data) {
        InventoryCitizen inventory = data.getInventory();
        ItemStack head = inventory.getArmorInSlot(EquipmentSlot.HEAD);
        if (head.is(TownHallBlocks.MAYOR_HAT.get())) inventory.forceClearArmorInSlot(EquipmentSlot.HEAD, head);
    }

    /** A citizen brings a player mayor the hat, once they are in the colony. */
    private void deliverHat(IColony colony, String key, Elections.Mayor mayor) {
        ServerPlayer player = server.getPlayerList().getPlayer(mayor.id);
        String job = "hat|" + key;
        if (player == null || couriers.isRunning(job) || player.level() != colony.getWorld()
                || !colony.isCoordInColony(player.level(), player.blockPosition())) {
            return;
        }
        couriers.dispatch(job, colony, player, null, "the mayor's hat",
                "It is the hat of office for the newly elected mayor; congratulate them.",
                () -> new ItemStack(TownHallBlocks.MAYOR_HAT.get()),
                delivered -> {
                    if (delivered && elections.mayors().get(key) == mayor) {
                        mayor.hatDelivered = true;
                        elections.save();
                    }
                });
    }

    /** The mayor died: the colony has no mayor, and may elect one right away. */
    private void vacate(IColony colony, String key, Elections.Mayor mayor) {
        elections.vacate(key);
        publish(colony, mayor, "Mayor " + mayor.name + " is no longer with us. The colony has no mayor until it elects a new one.");
        TownHall.LOGGER.info("Mayor {} of {} is gone; the office is vacant", mayor.name, colony.getName());
    }

    /** The mayor's citizen data, or null when they are gone (or not known yet and not loaded). */
    static @Nullable ICitizenData citizen(IColony colony, Elections.Mayor mayor) {
        if (mayor.citizenId >= 0) return colony.getCitizenManager().getCivilian(mayor.citizenId);
        for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
            if (data.getEntity().map(entity -> entity.getUUID().equals(mayor.id)).orElse(false)) {
                mayor.citizenId = data.getId();
                return data;
            }
        }
        return null;
    }

    /** The needs counted at the last check, for tests and views. */
    public Map<Need, Integer> needs(String key) {
        return counts.getOrDefault(key, new EnumMap<>(Need.class));
    }

    void stopAll() {
        couriers.stopAll();
    }
}
