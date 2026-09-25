package me.sshcrack.tc_playtest;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
/*? if forge {*/
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
*//*?}*/
/*? if neoforge {*/
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
/*?}*/

import java.util.List;
import java.util.Map;

/**
 * A scripted playtest for the headless scenario client ({@code scripts/scenario.sh}): once the
 * player is in the world it runs timed steps as that real player, so every ambient feature treats
 * it as a listener, then quits. A command starting with {@code chat:} is sent as a chat message
 * instead. Each step is logged as a speech-timeline mark, which the report
 * prints between the voices. Enabled with {@code -Dtc_playtest.scenario=<name>}.
 */
final class PlaytestScenario {
    /** One step: after {@code atSeconds} in the world, log {@code mark} and run the commands. */
    private record Step(int atSeconds, String mark, List<String> commands) {
    }

    private static final Map<String, List<Step>> SCENARIOS = Map.of(
            // Standing around the colony by day, then a campfire night next to the fire.
            "campfire", List.of(
                    new Step(20, "day: standing in the colony", List.of("playtest home", "time set 6000")),
                    new Step(110, "dusk: campfire night starts", List.of("time set 12500", "campfire start")),
                    new Step(200, "player speaks up at the fire",
                            List.of("chat:Excuse me, can one of you tell me what lies beyond the hills to the east?")),
                    new Step(320, "campfire stopped", List.of("campfire stop")),
                    new Step(335, "scenario done", List.of())),
            // Only the ambient life of the colony by day: greetings, mumbling, rumors, urgent contact.
            "ambient", List.of(
                    new Step(20, "day: standing in the colony", List.of("playtest home", "time set 6000")),
                    new Step(200, "scenario done", List.of())));

    private static List<Step> steps = List.of();
    private static int ticksInWorld;
    private static int next;

    private PlaytestScenario() {
    }

    static void init() {
        String name = System.getProperty("tc_playtest.scenario");
        if (name == null || name.isBlank()) return;
        steps = SCENARIOS.get(name);
        if (steps == null) throw new IllegalArgumentException("Unknown playtest scenario " + name
                + ", known: " + SCENARIOS.keySet());
        PlaytestMod.LOGGER.info("Running playtest scenario {}", name);
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || next >= steps.size()) return;
        ticksInWorld++;
        Step step = steps.get(next);
        if (ticksInWorld < step.atSeconds() * 20) return;
        next++;
        mark(step.mark());
        for (String command : step.commands()) {
            if (command.startsWith("chat:")) mc.player.connection.sendChat(command.substring("chat:".length()));
            else mc.player.connection.sendCommand(command);
        }
        if (next == steps.size()) {
            PlaytestMod.LOGGER.info("TC_PLAYTEST_SCENARIO_DONE");
            mc.stop();
        }
    }

    /** Same line format as Talking Colonists' SpeechTimeline, so the report shows it on the timeline. */
    private static void mark(String what) {
        JsonObject line = new JsonObject();
        line.addProperty("type", "mark");
        line.addProperty("at", System.currentTimeMillis());
        line.addProperty("what", what);
        PlaytestMod.LOGGER.info("[SpeechTimeline] {}", line);
    }
}
