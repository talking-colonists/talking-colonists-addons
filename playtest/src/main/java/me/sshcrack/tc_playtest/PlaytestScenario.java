package me.sshcrack.tc_playtest;

import com.google.gson.JsonObject;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
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
 * it as a listener, then quits. Each step is logged as a speech-timeline mark, which the report
 * prints between the voices. Enabled with {@code -Dtc_playtest.scenario=<name>}. Commands starting with
 * {@code client:} run on the client: {@code client:camera first|back|front} and
 * {@code client:screenshot <name>} (saved to the run's {@code screenshots/} folder).
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
                    new Step(290, "campfire stopped", List.of("campfire stop")),
                    new Step(305, "scenario done", List.of())),
            // The mayor's hat on a citizen mayor and on the player, then the mayor's report.
            "mayor", List.of(
                    new Step(20, "day: standing in the colony", List.of("playtest home", "time set 6000", "weather clear")),
                    new Step(30, "a citizen is appointed mayor", List.of("townhall appoint",
                            "item replace entity @s armor.head with tc_townhall:mayor_hat")),
                    new Step(40, "the mayor stands in front of the player", List.of(
                            "tp @e[type=minecolonies:citizen,sort=nearest,limit=1] ^ ^ ^3 facing entity @s")),
                    new Step(41, "screenshot: the citizen mayor", List.of("client:screenshot mayor_citizen")),
                    new Step(44, "camera on the player", List.of("client:camera front")),
                    new Step(46, "screenshot: the player mayor", List.of("client:screenshot mayor_player")),
                    new Step(48, "the mayor reports", List.of("client:camera first", "townhall report")),
                    new Step(150, "scenario done", List.of())),
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
            if (command.startsWith("client:")) client(mc, command.substring("client:".length()));
            else mc.player.connection.sendCommand(command);
        }
        if (next == steps.size()) {
            PlaytestMod.LOGGER.info("TC_PLAYTEST_SCENARIO_DONE");
            mc.stop();
        }
    }

    private static void client(Minecraft mc, String command) {
        String[] parts = command.split(" ", 2);
        switch (parts[0]) {
            case "camera" -> mc.options.setCameraType(switch (parts.length > 1 ? parts[1] : "first") {
                case "back" -> CameraType.THIRD_PERSON_BACK;
                case "front" -> CameraType.THIRD_PERSON_FRONT;
                default -> CameraType.FIRST_PERSON;
            });
            case "screenshot" -> Screenshot.grab(mc.gameDirectory, (parts.length > 1 ? parts[1] : "scenario") + ".png",
                    mc.getMainRenderTarget(), message -> PlaytestMod.LOGGER.info("Scenario screenshot: {}", message.getString()));
            default -> PlaytestMod.LOGGER.warn("Unknown scenario client command {}", command);
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
