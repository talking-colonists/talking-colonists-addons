package me.sshcrack.tc_playtest;

import net.minecraft.server.level.ServerPlayer;
/*? if forge {*/
/*import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
*//*?}*/
/*? if neoforge {*/
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
/*?}*/
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev-only mod, never released: turns a dev client into a ready-made test bed for the addons.
 * On the client it creates (or opens) the "TC_Playtest" world; on the server it builds a small colony
 * next to the first player and shows a clickable checklist of things to try ({@code /playtest}).
 */
@Mod(PlaytestMod.MOD_ID)
public class PlaytestMod {
    public static final String MOD_ID = /*$ mod_id*/ "tc_playtest";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public PlaytestMod() {
        /*? if forge {*/
        /*var bus = MinecraftForge.EVENT_BUS;
        *//*?}*/
        /*? if neoforge {*/
        var bus = NeoForge.EVENT_BUS;
        /*?}*/
        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) onJoin(player);
        });
        bus.addListener((RegisterCommandsEvent event) -> PlaytestCommand.register(event.getDispatcher()));
        if (FMLEnvironment.dist == Dist.CLIENT) PlaytestClient.init();
    }

    private static void onJoin(ServerPlayer player) {
        try {
            PlaytestColony.ensure(player);
            Checklist.send(player);
        } catch (RuntimeException e) {
            LOGGER.error("Playtest colony setup failed", e);
        }
    }
}
