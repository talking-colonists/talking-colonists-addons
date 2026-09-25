package me.sshcrack.tc_campfire;

import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import me.sshcrack.tc_campfire.shared.guide.Guides;
import net.minecraft.server.MinecraftServer;
/*? if forge {*/
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
*//*?}*/
/*? if neoforge {*/
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
/*?}*/
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import me.sshcrack.tc_campfire.dev.DevSelfTest;

/** Mod entry point: wires campfire nights to server start, stop, ticks, chat and commands. */
@Mod(CampfireNights.MOD_ID)
public class CampfireNights {
    public static final String MOD_ID = /*$ mod_id*/ "tc_campfire";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final boolean SELF_TEST = Boolean.getBoolean("tc_campfire.selftest");

    private static @Nullable CampfireDirector director;
    private static @Nullable MinecraftServer server;

    public CampfireNights() {
        if (!TalkingColonistsApi.isAvailable()) {
            LOGGER.warn("Campfire Nights needs Talking Colonists 2.1 or newer; it stays inactive");
            return;
        }
        registerGuide();
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
            director = new CampfireDirector(event.getServer());
        });
        bus.addListener((ServerStoppingEvent event) -> {
            if (director != null) director.stopAll();
            director = null;
            server = null;
        });
        bus.addListener((ServerChatEvent event) -> {
            if (director != null) director.onChat(event.getPlayer(), event.getRawText());
        });
        bus.addListener((RegisterCommandsEvent event) -> CampfireCommand.register(event.getDispatcher()));
    }

    private static void registerGuide() {
        Guides.register(MOD_ID + ":guide", "Campfire Nights",
                "At dusk, idle citizens gather around a lit campfire in the colony, sit down and take turns telling stories: memories, colony news and tales from before they came.",
                List.of(
                        "Place a campfire inside your colony and keep it lit.",
                        "Be near it at dusk: three to five idle citizens walk over and sit around it.",
                        "Listen as they tell their stories in turn. Chat while you stand by the fire and they may answer you."),
                List.of(
                        "There is at most one campfire night per colony and day, and only while the citizens' voices are not all busy.",
                        "The night becomes colony news, so the Colony Gazette may report it.",
                        "Operators: /campfire start begins one at the nearest campfire, /campfire stop ends it."));
    }

    /** The running director, or null while no server runs. */
    public static @Nullable CampfireDirector director() {
        return director;
    }

    private static void tick() {
        if (director == null) return;
        director.tick();
        // Only referenced when enabled, so the class (left out of the release jar) is never loaded otherwise.
        if (SELF_TEST && server != null) DevSelfTest.tick(server);
    }
}
