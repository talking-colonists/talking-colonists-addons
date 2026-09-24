package me.sshcrack.tc_postal;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.api.ApiFeature;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
/*? if forge {*/
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
*//*?}*/
/*? if neoforge {*/
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
/*?}*/
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entry point. Right-clicking a citizen with a signed book hands it over as a letter (on both
 * sides, so MineColonies does not open the citizen window instead).
 */
@Mod(PostalService.MOD_ID)
public class PostalService {
    public static final String MOD_ID = /*$ mod_id*/ "tc_postal";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final boolean SELF_TEST = Boolean.getBoolean("tc_postal.selftest");

    private static @Nullable PostOffice office;
    private static @Nullable MinecraftServer server;

    public PostalService() {
        if (!TalkingColonistsApi.isAvailable() || !TalkingColonistsApi.supports(ApiFeature.TEXT_GENERATION)) {
            LOGGER.warn("Postal Service needs Talking Colonists 2.1 or newer (text generation); it stays inactive");
            return;
        }
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
        bus.addListener((ServerStartedEvent event) -> start(event.getServer()));
        bus.addListener((ServerStoppingEvent event) -> {
            if (office != null) office.store().save();
            office = null;
            server = null;
        });
        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (office != null && event.getEntity() instanceof ServerPlayer player) office.onLogin(player);
        });
        bus.addListener(PostalService::onInteract);
        bus.addListener((RegisterCommandsEvent event) -> MailCommand.register(event.getDispatcher()));
    }

    private static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof AbstractEntityCitizen citizen) || !event.getItemStack().is(Items.WRITTEN_BOOK)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (office != null && event.getEntity() instanceof ServerPlayer player) {
            office.accept(player, citizen, event.getItemStack());
        }
    }

    /** The running post office, or null while no server runs. */
    public static @Nullable PostOffice office() {
        return office;
    }

    private static void start(MinecraftServer startedServer) {
        server = startedServer;
        PostStore store = new PostStore(startedServer.getWorldPath(LevelResource.ROOT).resolve("data")
                .resolve(MOD_ID + ".json").normalize());
        store.load();
        office = new PostOffice(startedServer, store);
    }

    private static void tick() {
        if (office == null) return;
        office.tick();
        // Only referenced when enabled, so the class (left out of the release jar) is never loaded otherwise.
        if (SELF_TEST && server != null) me.sshcrack.tc_postal.dev.DevSelfTest.tick(server);
    }
}
