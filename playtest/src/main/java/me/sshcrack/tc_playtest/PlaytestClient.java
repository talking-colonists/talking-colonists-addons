package me.sshcrack.tc_playtest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
/*? if forge {*/
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
*//*?}*/
/*? if neoforge {*/
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
/*?}*/

import java.nio.file.Files;

/**
 * Creates the "TC_Playtest" world on the first launch: a creative superflat world with cheats, so no
 * clicking through the world screen. Later launches open it directly (the playtest client run passes
 * {@code --quickPlaySingleplayer} once the save exists).
 */
final class PlaytestClient {
    static final String WORLD = "TC_Playtest";
    private static final int ENTER_KEY = 257;

    private static boolean creationRequested;
    private static boolean creationConfirmed;

    private PlaytestClient() {
    }

    static void init() {
        /*? if forge {*/
        /*MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) tick();
        });
        *//*?}*/
        /*? if neoforge {*/
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        /*?}*/
    }

    private static void tick() {
        if (creationConfirmed) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AccessibilityOnboardingScreen onboarding) {
            onboarding.onClose();
            return;
        }
        if (!creationRequested && mc.screen instanceof TitleScreen title) {
            creationRequested = true;
            if (Files.isDirectory(mc.gameDirectory.toPath().resolve("saves").resolve(WORLD))) {
                // Exists but was not quick-played (e.g. the save appeared after Gradle configured the run).
                creationConfirmed = true;
                /*? if neoforge {*/
                mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(title));
                /*?}*/
                /*? if forge {*/
                /*mc.createWorldOpenFlows().loadLevel(title, WORLD);
                *//*?}*/
                return;
            }
            PlaytestMod.LOGGER.info("Creating the {} world", WORLD);
            CreateWorldScreen.openFresh(mc, title);
            return;
        }
        if (creationRequested && mc.screen instanceof CreateWorldScreen create) {
            creationConfirmed = true;
            WorldCreationUiState state = create.getUiState();
            state.setName(WORLD);
            state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            /*? if neoforge {*/
            state.setAllowCommands(true);
            /*?}*/
            /*? if forge {*/
            /*state.setAllowCheats(true);
            *//*?}*/
            state.getNormalPresetList().stream()
                    .filter(entry -> entry.preset() != null && entry.preset().is(WorldPresets.FLAT))
                    .findFirst()
                    .ifPresent(state::setWorldType);
            create.keyPressed(ENTER_KEY, 0, 0);
        }
    }
}
