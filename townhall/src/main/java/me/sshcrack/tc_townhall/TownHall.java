package me.sshcrack.tc_townhall;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import me.sshcrack.mc_talking.api.ApiFeature;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import me.sshcrack.tc_townhall.shared.guide.Guides;
import me.sshcrack.mc_talking.api.prompt.CitizenPromptService;
import me.sshcrack.mc_talking.api.prompt.PromptContribution;
import me.sshcrack.mc_talking.api.prompt.PromptTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
/*? if forge {*/
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
*//*?}*/
/*? if neoforge {*/
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
import me.sshcrack.tc_townhall.dev.DevSelfTest;

/** Mod entry point: stand for mayor at the Town Hall block, and the suggestion box next to it. */
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

    public TownHall() {
        if (!TalkingColonistsApi.isAvailable() || !TalkingColonistsApi.supports(ApiFeature.BROADCAST_PUBLISHING)
                || !TalkingColonistsApi.supports(ApiFeature.TEXT_GENERATION)) {
            LOGGER.warn("Town Hall needs Talking Colonists 2.1 or newer (broadcasts, text generation); it stays inactive");
            return;
        }
        registerGuide();
        CitizenPromptService.registerContributor(MOD_ID + ":politics", 100, context -> {
            Elections running = elections;
            if (running == null || context.view().visitor() != null) return List.of();
            PromptTarget target = context.target();
            if (target != PromptTarget.CITIZEN_ROLEPLAY && target != PromptTarget.SYSTEM_CONTROLLED_ROLEPLAY
                    && target != PromptTarget.CONVERSATIONAL_INFO) {
                return List.of();
            }
            return politics(running, context.view().colony().id(), context.view().citizenId());
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
                "Stand for mayor with a speech, and every citizen votes on what reached them. A barrel next to the town hall becomes a suggestion box for citizens' notes.",
                List.of(
                        "Write your platform in a book and quill and sign it; the title is your slogan.",
                        "Right-click the Town Hall block while holding it to stand for mayor.",
                        "With voice chat, you then have 30 seconds for a speech to the citizens there.",
                        "The campaign lasts a day. Then the citizens vote, and one brings you the results book.",
                        "Place a barrel within 4 blocks of the Town Hall block to get a suggestion box."),
                List.of(
                        "If you are the only candidate, the unhappiest citizen stands against you, so you can lose.",
                        "A new election can be called three days after the last one.",
                        "Operators: /townhall rush ends campaigns now, /townhall notes has citizens write notes now."));
    }

    /** What citizens know about the colony's politics: a running campaign, the mayor, and being the mayor. */
    static List<PromptContribution> politics(Elections running, int colonyId, UUID citizenId) {
        List<PromptContribution> contributions = new ArrayList<>();
        Elections.Election election = running.electionById(colonyId);
        if (election != null && !election.candidates.isEmpty()) {
            contributions.add(PromptContribution.observation(MOD_ID + ":campaign", "Colony politics",
                    ElectionText.campaignObservation(election.candidates.stream().map(c -> c.name).toList(),
                            election.candidates.stream().map(c -> c.slogan).toList(), election.voting)));
        }
        Elections.Mayor mayor = running.mayorById(colonyId);
        if (mayor != null) {
            contributions.add(PromptContribution.observation(MOD_ID + ":mayor", "Colony politics",
                    ElectionText.mayorObservation(mayor.name, mayor.sinceDay, mayor.result)));
            if (mayor.citizen && mayor.id.equals(citizenId)) {
                contributions.add(PromptContribution.instruction(MOD_ID + ":mayor_role", "Your office",
                        ElectionText.mayorInstruction(mayor.sinceDay)));
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
        if (suggestions != null) suggestions.tick();
        // Only referenced when enabled, so the class (left out of the release jar) is never loaded otherwise.
        if (SELF_TEST && server != null) DevSelfTest.tick(server);
    }
}
