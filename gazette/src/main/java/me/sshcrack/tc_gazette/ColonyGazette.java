package me.sshcrack.tc_gazette;

import me.sshcrack.mc_talking.api.ApiFeature;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
/*? if forge {*/
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
*//*?}*/
/*? if neoforge {*/
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
/*?}*/
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mod entry point: wires the gazette to server start, stop, ticks and commands. */
@Mod(ColonyGazette.MOD_ID)
public class ColonyGazette {
    public static final String MOD_ID = /*$ mod_id*/ "tc_gazette";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final boolean SELF_TEST = Boolean.getBoolean("tc_gazette.selftest");

    private static @Nullable GazettePublisher publisher;
    private static @Nullable MinecraftServer server;

    /*? if forge {*/
    /*public ColonyGazette() {
        if (!supported()) return;
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) -> start(event.getServer()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> stop());
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) tick();
        });
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> GazetteCommand.register(event.getDispatcher()));
    }
    *//*?}*/

    /*? if neoforge {*/
    public ColonyGazette(IEventBus modEventBus, ModContainer modContainer) {
        if (!supported()) return;
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> start(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> stop());
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> GazetteCommand.register(event.getDispatcher()));
    }
    /*?}*/

    /** The running publisher, or null while no server runs. */
    public static @Nullable GazettePublisher publisher() {
        return publisher;
    }

    private static boolean supported() {
        if (!TalkingColonistsApi.isAvailable()
                || !TalkingColonistsApi.supports(ApiFeature.TEXT_GENERATION)
                || !TalkingColonistsApi.supports(ApiFeature.COLONY_EVENTS)) {
            LOGGER.warn("Colony Gazette needs Talking Colonists 2.1 or newer (text generation and colony events); it stays inactive");
            return false;
        }
        return true;
    }

    private static void tick() {
        if (publisher == null) return;
        publisher.tick();
        // Only referenced when enabled, so the class (left out of the release jar) is never loaded otherwise.
        if (SELF_TEST && server != null) me.sshcrack.tc_gazette.dev.DevSelfTest.tick(server);
    }

    private static void start(MinecraftServer startedServer) {
        server = startedServer;
        GazetteStore store = new GazetteStore(startedServer.getWorldPath(LevelResource.ROOT).resolve("data")
                .resolve(MOD_ID + ".json").normalize());
        store.load();
        publisher = new GazettePublisher(startedServer, store);
    }

    private static void stop() {
        if (publisher != null) publisher.store().save();
        publisher = null;
        server = null;
    }
}
