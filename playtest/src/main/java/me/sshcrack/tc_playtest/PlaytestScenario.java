package me.sshcrack.tc_playtest;

import com.google.gson.JsonObject;
import com.ldtteam.blockui.BOScreen;
import com.ldtteam.blockui.Pane;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
/*? if forge {*/
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
*//*?}*/
/*? if neoforge {*/
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
/*?}*/

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * A scripted playtest for the headless scenario client ({@code scripts/scenario.sh}): once the
 * player is in the world it runs timed steps as that real player, so every ambient feature treats
 * it as a listener, then quits. Each step is logged as a speech-timeline mark, which the report
 * prints between the voices. Enabled with {@code -Dtc_playtest.scenario=<name>}. Commands starting with
 * {@code client:} run on the client: {@code client:camera first|back|front}, {@code client:hud on|off},
 * {@code client:use} (right-click the block looked at), {@code client:useblock <dx> <dy> <dz>} (right-click the
 * block at that offset from the player), {@code client:close} (the open screen) and
 * {@code client:screenshot <name>} (saved to the run's {@code screenshots/} folder), {@code client:click <pane id>}
 * (a real mouse click on a pane of the open BlockUI window) and {@code client:screen} (logs the open screen).
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
            // Each addon window opens, and its close button closes it (a real mouse click).
            "windows", List.of(
                    new Step(20, "out in the open", List.of("playtest home", "tp @s ~24 ~ ~24", "execute align xyz run tp @s ~0.5 ~ ~0.5 0 0",
                            "item replace entity @s weapon.mainhand with air")),
                    new Step(23, "notice board: placed", List.of("setblock ~ ~ ~2 tc_noticeboard:notice_board[facing=north]", "tp @s ~ ~ ~ facing ~ ~0.5 ~2")),
                    new Step(25, "notice board: opened", List.of("client:useblock 0 0 2")),
                    new Step(27, "notice board: close clicked", List.of("client:screen", "client:click close")),
                    new Step(28, "notice board: after close", List.of("client:screen", "client:close", "setblock ~ ~ ~2 air")),
                    new Step(31, "ballot box: placed", List.of("setblock ~ ~ ~2 tc_townhall:ballot_box[facing=north]", "tp @s ~ ~ ~ facing ~ ~0.5 ~2")),
                    new Step(33, "ballot box: opened", List.of("client:useblock 0 0 2")),
                    new Step(35, "ballot box: close clicked", List.of("client:screen", "client:click close")),
                    new Step(36, "ballot box: after close", List.of("client:screen", "client:close", "setblock ~ ~ ~2 air")),
                    new Step(39, "suggestion box: placed", List.of("setblock ~ ~ ~2 tc_townhall:suggestion_box[facing=north]", "tp @s ~ ~ ~ facing ~ ~0.5 ~2")),
                    new Step(41, "suggestion box: opened", List.of("client:useblock 0 0 2")),
                    new Step(43, "suggestion box: close clicked", List.of("client:screen", "client:click close")),
                    new Step(44, "suggestion box: after close", List.of("client:screen", "client:close", "setblock ~ ~ ~2 air")),
                    new Step(47, "scenario done", List.of())),
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
            case "useblock" -> useBlock(mc, parts.length > 1 ? parts[1] : "0 0 0");
            case "close" -> mc.setScreen(null);
            case "click" -> click(mc, parts.length > 1 ? parts[1] : "");
            case "screen" -> PlaytestMod.LOGGER.info("TC_SCENARIO_SCREEN {}", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
            case "hud" -> mc.options.hideGui = parts.length > 1 && parts[1].equals("off");
            case "screenshot" -> Screenshot.grab(mc.gameDirectory, (parts.length > 1 ? parts[1] : "scenario") + ".png",
                    mc.getMainRenderTarget(), message -> PlaytestMod.LOGGER.info("Scenario screenshot: {}", message.getString()));
            default -> PlaytestMod.LOGGER.warn("Unknown scenario client command {}", command);
        }
    }

    /** Right-clicks the block at "dx dy dz" from the player's block, whatever the crosshair is on. */
    private static void useBlock(Minecraft mc, String offset) {
        String[] parts = offset.trim().split("\\s+");
        BlockPos pos = mc.player.blockPosition().offset(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        if (mc.gameMode == null) return;
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false));
    }

    /**
     * Clicks a pane of the open BlockUI window by id, with a real mouse press and release at its center,
     * so hit-testing is part of the test (a pane covering the button would take the click).
     */
    private static void click(Minecraft mc, String id) {
        if (!(mc.screen instanceof BOScreen screen)) {
            PlaytestMod.LOGGER.warn("TC_SCENARIO_CLICK {}: no BlockUI window is open", id);
            return;
        }
        Pane pane = screen.getWindow().findPaneByID(id);
        if (pane == null) {
            PlaytestMod.LOGGER.warn("TC_SCENARIO_CLICK {}: no such pane", id);
            return;
        }
        double x = pane.getWidth() / 2.0;
        double y = pane.getHeight() / 2.0;
        for (Pane current = pane; current != null && current != screen.getWindow(); current = current.getParent()) {
            x += current.getX();
            y += current.getY();
        }
        try {
            double scale = screenField(screen, "renderScale");
            double mouseX = screenField(screen, "x") + x * scale;
            double mouseY = screenField(screen, "y") + y * scale;
            boolean handled = screen.mouseClicked(mouseX, mouseY, 0);
            screen.mouseReleased(mouseX, mouseY, 0);
            PlaytestMod.LOGGER.info("TC_SCENARIO_CLICK {} at {},{}: handled={}", id, (int) mouseX, (int) mouseY, handled);
        } catch (ReflectiveOperationException e) {
            PlaytestMod.LOGGER.warn("TC_SCENARIO_CLICK {}: {}", id, e.toString());
        }
    }

    private static double screenField(BOScreen screen, String name) throws ReflectiveOperationException {
        Field field = BOScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getDouble(screen);
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
