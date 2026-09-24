package me.sshcrack.tc_tavern;

import me.sshcrack.mc_talking.api.ApiFeature;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationRules;
import me.sshcrack.mc_talking.api.conversation.ConversationKind;
import me.sshcrack.mc_talking.api.prompt.CitizenPromptService;
import me.sshcrack.mc_talking.api.prompt.PromptContribution;
import me.sshcrack.mc_talking.api.prompt.PromptTarget;
import me.sshcrack.mc_talking.api.tool.AiToolRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
/*? if forge {*/
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
*//*?}*/
/*? if neoforge {*/
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
/*?}*/
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import me.sshcrack.tc_tavern.dev.DevSelfTest;

/**
 * Mod entry point: lets players talk to tavern visitors, tells visitors they may haggle over their
 * recruit cost, and gives them the tool to do it.
 */
@Mod(TavernRecruiter.MOD_ID)
public class TavernRecruiter {
    public static final String MOD_ID = /*$ mod_id*/ "tc_tavern";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final boolean SELF_TEST = Boolean.getBoolean("tc_tavern.selftest");

    private static @Nullable HaggleStore store;
    private static @Nullable MinecraftServer server;

    public TavernRecruiter() {
        if (!TalkingColonistsApi.isAvailable() || !TalkingColonistsApi.supports(ApiFeature.VISITOR_SPEAKERS)) {
            LOGGER.warn("Tavern Recruiter needs Talking Colonists 2.1 or newer (visitor speakers); it stays inactive");
            return;
        }
        // Players may talk to visitors; controlled sessions (other addons, the self-test) may include them.
        CitizenConversationRules.registerVisitorPolicy(MOD_ID + ":tavern_guests", 100,
                (visitor, kind) -> kind == ConversationKind.PLAYER || kind == ConversationKind.CONTROLLED);
        AiToolRegistry.register(MOD_ID, NegotiateTool.NAME, new NegotiateTool(() -> store));
        String tool = AiToolRegistry.providerName(MOD_ID, NegotiateTool.NAME);
        CitizenPromptService.registerContributor(MOD_ID + ":haggling", 100, context -> {
            if (context.view().visitor() == null) return List.of();
            if (context.target() != PromptTarget.CITIZEN_ROLEPLAY && context.target() != PromptTarget.SYSTEM_CONTROLLED_ROLEPLAY) {
                return List.of();
            }
            return List.of(PromptContribution.instruction(MOD_ID + ":haggling", "Joining the colony",
                    RecruitPrompt.text(context.view().visitor().recruitCost(), tool)));
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
            store = new HaggleStore(event.getServer().getWorldPath(LevelResource.ROOT).resolve("data")
                    .resolve(MOD_ID + ".json").normalize());
            store.load();
        });
        bus.addListener((ServerStoppingEvent event) -> {
            if (store != null) store.save();
            store = null;
            server = null;
        });
    }

    /** The visitor's haggling state, or null while no server runs. */
    public static @Nullable HaggleStore store() {
        return store;
    }


    private static void tick() {
        // Only referenced when enabled, so the class (left out of the release jar) is never loaded otherwise.
        if (SELF_TEST && server != null) DevSelfTest.tick(server);
    }
}
