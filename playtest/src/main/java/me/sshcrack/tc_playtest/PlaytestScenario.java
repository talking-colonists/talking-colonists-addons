package me.sshcrack.tc_playtest;

import com.google.gson.JsonObject;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
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
 * {@code client:} run on the client: {@code client:camera first|back|front}, {@code client:hud on|off},
 * {@code client:use} (right-click the block looked at), {@code client:close} (the open screen) and
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
            // The player stands for mayor with the campaign book at the Town Hall; the campaign is rushed, a
            // citizen stands against them, walks up with a pamphlet and gives a campaign speech.
            "election", List.of(
                    new Step(20, "day: at the town hall", List.of("playtest home", "time set 6000", "weather clear")),
                    new Step(25, "the player stands for mayor with the campaign book", List.of("playtest stand")),
                    new Step(60, "the campaign is rushed: a citizen stands", List.of("townhall rush")),
                    new Step(200, "scenario done", List.of())),
            // The mayor's hat on a citizen mayor and on the player, then the mayor's report.
            "mayor", List.of(
                    new Step(20, "day: standing in the colony", List.of("playtest home", "time set 6000", "weather clear")),
                    new Step(25, "the player wears the mayor's hat, out in the open", List.of(
                            "item replace entity @s armor.head with tc_townhall:mayor_hat", "tp @s ~24 ~ ~24 0 15",
                            "client:hud off", "client:camera front")),
                    new Step(28, "screenshot: the player mayor", List.of("client:screenshot mayor_player")),
                    new Step(30, "back in the colony", List.of("client:camera first", "playtest home")),
                    // In one step, so the nearest citizen is the one appointed.
                    new Step(33, "the nearest citizen is appointed mayor and stands in front of the player", List.of(
                            "townhall appoint", "tp @e[type=minecolonies:citizen,sort=nearest,limit=1] ^ ^ ^4 facing entity @s")),
                    new Step(36, "screenshot: the citizen mayor", List.of("client:screenshot mayor_citizen")),
                    new Step(38, "the mayor reports", List.of("client:hud on", "townhall report")),
                    new Step(120, "a ballot box in front of the player", List.of("tp @s ~ ~ ~ 0 35",
                            "item replace entity @s weapon.mainhand with air", "setblock ~ ~ ~2 tc_townhall:ballot_box")),
                    new Step(123, "the ballot box is opened", List.of("client:use")),
                    new Step(126, "screenshot: the mayor's desk", List.of("client:screenshot mayor_desk", "client:close")),
                    new Step(220, "scenario done", List.of())),
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
            case "use" -> {
                if (mc.hitResult instanceof BlockHitResult hit && mc.gameMode != null) {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                }
            }
            case "close" -> mc.setScreen(null);
            case "hud" -> mc.options.hideGui = parts.length > 1 && parts[1].equals("off");
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
