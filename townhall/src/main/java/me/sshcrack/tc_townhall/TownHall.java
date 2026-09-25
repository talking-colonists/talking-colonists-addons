package me.sshcrack.tc_townhall;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import me.sshcrack.mc_talking.api.ApiFeature;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import me.sshcrack.tc_townhall.shared.guide.Guides;
import me.sshcrack.mc_talking.api.prompt.CitizenPromptService;
import me.sshcrack.mc_talking.api.prompt.PromptContribution;
import me.sshcrack.mc_talking.api.prompt.PromptTarget;
import me.sshcrack.mc_talking.api.tool.AiToolRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
/*? if forge {*/
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
*//*?}*/
/*? if neoforge {*/
import me.sshcrack.tc_townhall.client.TownHallClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
/*?}*/
/*? if neoforge {*/
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
/*?}*/
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.google.gson.Gson;
import me.sshcrack.tc_townhall.block.BallotBoxBlock;
import me.sshcrack.tc_townhall.block.BallotBoxBlockEntity;
import me.sshcrack.tc_townhall.block.SuggestionBoxBlockEntity;
import me.sshcrack.tc_townhall.block.TownHallBlocks;
import me.sshcrack.tc_townhall.dev.DevSelfTest;
import me.sshcrack.tc_townhall.shared.net.BlockActions;

/** Mod entry point: stand for mayor at the Town Hall block, and the Suggestion Box block. */
@Mod(TownHall.MOD_ID)
public class TownHall {
    public static final String MOD_ID = /*$ mod_id*/ "tc_townhall";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /** MineColonies' Town Hall hut block; the id is the same on both loaders and versions. */
    static final String TOWN_HALL_BLOCK = "minecolonies:blockhuttownhall";

    private static final boolean SELF_TEST = Boolean.getBoolean("tc_townhall.selftest");

    private static @Nullable Elections elections;
    private static @Nullable SuggestionBox suggestions;
    private static @Nullable MinecraftServer server;
    private static final Gson GSON = new Gson();
    private static final int PHASE_CHECK_TICKS = 100;
    private static int ticks;

    /*? if neoforge {*/
    public TownHall(IEventBus modBus, ModContainer container) {
    /*?}*/
    /*? if forge {*/
    /*public TownHall() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
    *//*?}*/
        // The blocks always register, so worlds that contain them load even when the addon stays inactive.
        TownHallBlocks.register(modBus);
        /*? if neoforge {*/
        if (FMLEnvironment.dist == Dist.CLIENT) TownHallClient.init(modBus);
        /*?}*/
        /*? if neoforge {*/
        BlockActions.init(MOD_ID, modBus);
        /*?}*/
        /*? if forge {*/
        /*BlockActions.init(MOD_ID);
        *//*?}*/
        BlockActions.on(SuggestionBoxBlockEntity.TAKE, TownHall::takeNote);
        BlockActions.on(SuggestionBoxBlockEntity.DISCARD, (player, pos, index, text) -> {
            if (player.level().getBlockEntity(pos) instanceof SuggestionBoxBlockEntity box) box.remove(index);
        });
        BlockActions.on(BallotBoxBlockEntity.VIEW, (player, pos, argument, text) -> sendBallot(player, pos));
        BlockActions.on(BallotBoxBlockEntity.STAND, TownHall::standAtBallotBox);
        BlockActions.on(BallotBoxBlockEntity.ACCEPT, (player, pos, argument, text) -> answerAtBallotBox(player, pos, true));
        BlockActions.on(BallotBoxBlockEntity.REFUSE, (player, pos, argument, text) -> answerAtBallotBox(player, pos, false));
        BlockActions.on(BallotBoxBlockEntity.SPEAK, (player, pos, argument, text) -> {
            IColony colony = colonyAt(player, pos);
            if (colony != null && elections != null) elections.speakAgain(player, colony, pos);
        });
        if (!TalkingColonistsApi.isAvailable() || !TalkingColonistsApi.supports(ApiFeature.BROADCAST_PUBLISHING)
                || !TalkingColonistsApi.supports(ApiFeature.TEXT_GENERATION)) {
            LOGGER.warn("Town Hall needs Talking Colonists 2.1 or newer (broadcasts, text generation); it stays inactive");
            return;
        }
        registerGuide();
        AiToolRegistry.register(MOD_ID, MayorsOffice.TOOL, new AnswerProposalTool());
        CitizenPromptService.registerContributor(MOD_ID + ":politics", 100, context -> {
            Elections running = elections;
            if (running == null || context.view().visitor() != null) return List.of();
            PromptTarget target = context.target();
            if (target != PromptTarget.CITIZEN_ROLEPLAY && target != PromptTarget.SYSTEM_CONTROLLED_ROLEPLAY
                    && target != PromptTarget.CONVERSATIONAL_INFO) {
                return List.of();
            }
            return politics(running, context.view().colony().id(), context.view().citizenId(), context.view().playerId());
        });

        /*? if forge {*/
        /*var bus = MinecraftForge.EVENT_BUS;
        bus.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) tick();
        });
        *//*?}*/
        /*? if neoforge {*/
        var bus = NeoForge.EVENT_BUS;
        bus.addListener((ServerTickEvent.Post event) -> tick());
        /*?}*/
        bus.addListener((ServerStartedEvent event) -> {
            server = event.getServer();
            elections = new Elections(event.getServer(), event.getServer().getWorldPath(LevelResource.ROOT).resolve("data")
                    .resolve(MOD_ID + ".json").normalize());
            elections.load();
            suggestions = new SuggestionBox(event.getServer());
        });
        bus.addListener((ServerStoppingEvent event) -> {
            if (elections != null) {
                elections.save();
                elections.stopAll();
            }
            elections = null;
            suggestions = null;
            server = null;
        });
        bus.addListener(TownHall::onRightClickBlock);
        bus.addListener((RegisterCommandsEvent event) -> TownHallCommands.register(event.getDispatcher()));
    }

    private static void registerGuide() {
        Guides.register(MOD_ID + ":guide", "Town Hall and Elections",
                "Stand for mayor with a speech, and every citizen votes on what reached them. A Suggestion Box collects citizens' notes about what worries them.",
                List.of(
                        "Craft a Ballot Box (spruce planks around paper and an iron ingot) and place it in your colony.",
                        "Right-click it, type a slogan and what you will do, and stand for mayor.",
                        "With voice chat, you then have 30 seconds for a speech to the citizens nearby.",
                        "The campaign lasts a day. Then the citizens vote; the box shows a live tally, and a citizen brings you the results.",
                        "The winner wears the Mayor's Hat. A citizen mayor brings you a report once a day, with what the colony lacks and a proposal: say yes or no, or answer at the Ballot Box.",
                        "Craft a Suggestion Box (paper over a chest over a log): each morning unhappy citizens drop notes in it."),
                List.of(
                        "Right-clicking the Town Hall block with a signed book also works: the title is your slogan, the text your platform.",
                        "If you are the only candidate, the unhappiest citizen stands against you, so you can lose. They bring you their campaign pamphlet and give their speech aloud.",
                        "A new election can be called three days after the last one. A sitting citizen mayor stands for re-election, and voters judge the promises and how you answered the mayor.",
                        "Agreeing to an upgrade places the builder's work order. Agreeing to a new hut is a promise: place it within three days.",
                        "Operators: /townhall rush ends campaigns now, /townhall notes has citizens write notes now, /townhall report has the mayor report now."));
        Guides.introduce(MOD_ID + ":elections", "elections",
                "Tell them they can stand for mayor: craft a Ballot Box, right-click it to put their slogan and promises forward, and give a speech; then the colony votes.",
                MOD_ID + ":guide", (player, colony) -> colony.getServerBuildingManager().hasTownHall());
    }

    /**
     * What citizens know about the colony's politics: a running campaign, the mayor and their record, and
     * being the mayor or talking with them.
     */
    public static List<PromptContribution> politics(Elections running, int colonyId, UUID citizenId, @Nullable UUID playerId) {
        List<PromptContribution> contributions = new ArrayList<>();
        Elections.Election election = running.electionById(colonyId);
        if (election != null && !election.candidates.isEmpty()) {
            contributions.add(PromptContribution.observation(MOD_ID + ":campaign", "Colony politics",
                    ElectionText.campaignObservation(election.candidates.stream().map(c -> c.name).toList(),
                            election.candidates.stream().map(c -> c.slogan).toList(), election.voting)));
        }
        String key = running.mayorKeyById(colonyId);
        Elections.Mayor mayor = key == null ? null : running.mayors().get(key);
        if (mayor != null) {
            MayorsOffice office = running.office();
            String record = office.record(key, mayor);
            contributions.add(PromptContribution.observation(MOD_ID + ":mayor", "Colony politics",
                    ElectionText.mayorObservation(mayor.name, mayor.sinceDay, mayor.result) + (record.isBlank() ? "" : " " + record)));
            boolean self = mayor.citizen && mayor.id.equals(citizenId);
            if (self) {
                Office.Proposal pending = mayor.proposal != null && mayor.proposal.status == Office.ProposalStatus.PENDING
                        ? mayor.proposal : null;
                contributions.add(PromptContribution.instruction(MOD_ID + ":mayor_role", "Your office",
                        ElectionText.mayorInstruction(mayor.sinceDay) + " " + OfficeText.mayorDuties(office.needLines(key, mayor),
                                pending, AiToolRegistry.providerName(MOD_ID, MayorsOffice.TOOL))));
            } else if (!mayor.citizen && mayor.id.equals(playerId)) {
                contributions.add(PromptContribution.instruction(MOD_ID + ":mayor_talk", "Colony politics",
                        OfficeText.talkingToMayor(mayor.name)));
            } else {
                contributions.add(PromptContribution.instruction(MOD_ID + ":mayor_weight", "Colony politics",
                        OfficeText.listenToMayor(mayor.name)));
            }
        }
        return contributions;
    }

    /**
     * A signed book used on the colony's Town Hall block: stand for mayor with it. Cancelled on both
     * sides, so the hut's own window does not open over it.
     */
    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getItemStack().is(Items.WRITTEN_BOOK)) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (!BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString().equals(TOWN_HALL_BLOCK)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(event.getEntity() instanceof ServerPlayer player) || !(level instanceof ServerLevel serverLevel) || elections == null) return;
        IColony colony = IColonyManager.getInstance().getIColony(serverLevel, pos);
        if (colony == null || !Elections.isTownHall(colony, pos)) return;
        elections.stand(player, colony, event.getItemStack(), pos);
    }

    /** A player takes a note out of the suggestion box, as a one-page book. */
    private static void takeNote(ServerPlayer player, BlockPos pos, int index, String text) {
        BlockEntity entity = player.level().getBlockEntity(pos);
        if (!(entity instanceof SuggestionBoxBlockEntity box)) return;
        SuggestionBoxBlockEntity.Note note = box.remove(index);
        if (note == null) return;
        ItemStack book = SuggestionBox.note(note.writer(), note.text());
        if (!player.getInventory().add(book)) player.drop(book, false);
    }

    private static @Nullable IColony colonyAt(ServerPlayer player, BlockPos pos) {
        return IColonyManager.getInstance().getIColony(player.level(), pos);
    }

    /** "Agree" or "Turn down" at the Mayor's desk of the ballot box window. */
    private static void answerAtBallotBox(ServerPlayer player, BlockPos pos, boolean accept) {
        IColony colony = colonyAt(player, pos);
        if (colony == null || elections == null) return;
        elections.office().answer(colony, player, accept, "");
        sendBallot(player, pos);
    }

    /** Sends the ballot box window the colony's election; an empty colony name means "not in a colony". */
    private static void sendBallot(ServerPlayer player, BlockPos pos) {
        IColony colony = colonyAt(player, pos);
        BallotView view = colony != null && elections != null ? elections.view(colony, player) : new BallotView();
        BlockActions.view(player, pos, BallotBoxBlockEntity.VIEW_KIND, GSON.toJson(view));
    }

    /** "Stand" in the ballot box window: the text is the slogan, a line break, then the platform. */
    private static void standAtBallotBox(ServerPlayer player, BlockPos pos, int argument, String text) {
        IColony colony = colonyAt(player, pos);
        if (colony == null || elections == null) {
            Elections.tell(player, "Place the ballot box inside your colony.");
            return;
        }
        int split = text.indexOf('\n');
        String slogan = split < 0 ? text : text.substring(0, split);
        String platform = split < 0 ? "" : text.substring(split + 1);
        elections.stand(player, colony, slogan, platform, pos);
    }

    /** Shows each loaded ballot box its colony's election phase: a poster while campaigning, a flag while voting. */
    private static void showPhases() {
        for (BallotBoxBlockEntity box : BallotBoxBlockEntity.loaded()) {
            if (box.getLevel() == null) continue;
            IColony colony = IColonyManager.getInstance().getIColony(box.getLevel(), box.getBlockPos());
            BallotView.Phase phase = colony != null && elections != null ? elections.phase(colony) : BallotView.Phase.IDLE;
            BlockState state = box.getBlockState();
            BallotBoxBlock.Phase shown = BallotBoxBlock.Phase.of(phase);
            if (state.hasProperty(BallotBoxBlock.PHASE) && state.getValue(BallotBoxBlock.PHASE) != shown) {
                box.getLevel().setBlockAndUpdate(box.getBlockPos(), state.setValue(BallotBoxBlock.PHASE, shown));
            }
        }
    }

    /** The running elections, or null while no server runs. */
    public static @Nullable Elections elections() {
        return elections;
    }

    /** The running suggestion box, or null while no server runs. */
    public static @Nullable SuggestionBox suggestions() {
        return suggestions;
    }

    private static void tick() {
        if (elections != null) elections.tick();
        if (++ticks % PHASE_CHECK_TICKS == 0) showPhases();
        if (suggestions != null) suggestions.tick();
        // Only referenced when enabled, so the class (left out of the release jar) is never loaded otherwise.
        if (SELF_TEST && server != null) DevSelfTest.tick(server);
    }
}
