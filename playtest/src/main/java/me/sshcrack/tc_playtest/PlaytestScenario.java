package me.sshcrack.tc_playtest;

import com.google.gson.JsonObject;
import com.ldtteam.blockui.BOScreen;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.AbstractTextElement;
import com.ldtteam.blockui.controls.TextField;
import com.ldtteam.blockui.views.View;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
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
import java.util.regex.Pattern;

/**
 * A scripted playtest for the headless scenario client ({@code scripts/scenario.sh}): once the
 * player is in the world it runs timed steps as that real player, so every ambient feature treats
 * it as a listener, then quits. A command starting with {@code chat:} is sent as a chat message.
 * Each step is logged as a speech-timeline mark, which the report
 * prints between the voices. Enabled with {@code -Dtc_playtest.scenario=<name>}. Commands starting with
 * {@code client:} run on the client: {@code client:camera first|back|front}, {@code client:hud on|off},
 * {@code client:use} (right-click the block looked at), {@code client:useitem} (use the held item), {@code client:useblock <dx> <dy> <dz>} (right-click the
 * block at that offset from the player), {@code client:close} (the open screen) and
 * {@code client:screenshot <name>} (saved to the run's {@code screenshots/} folder), {@code client:click <pane id>}
 * (a real mouse click on a pane of the open BlockUI window), {@code client:type <pane id> <text>} (into a text field),
 * {@code client:dump <pane id>} (logs the texts shown in it) and {@code client:screen} (logs the open screen).
 * {@code await <seconds> <regex>} holds the scenario clock until a log line matches (see {@link LogTap}).
 * What a run must show is checked afterwards by {@code scripts/scenario-checks.py}.
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
                    // #259: the guests sit in a ring around the fire.
                    // The fire is 3.5 blocks north of home: step back and up to see the whole ring.
                    new Step(169, "steps back from the fire", List.of("playtest home", "tp @s ~ ~2 ~6 facing ~ ~ ~-3.5",
                            "client:hud off")),
                    new Step(171, "screenshot: the ring at the fire", List.of("client:screenshot campfire_ring",
                            "client:hud on", "playtest home")),
                    new Step(200, "player speaks up at the fire",
                            List.of("chat:Excuse me, can one of you tell me what lies beyond the hills to the east?")),
                    new Step(320, "campfire stopped", List.of("campfire stop")),
                    new Step(335, "scenario done", List.of())),
            // The player stands for mayor with the campaign book at the Town Hall; the campaign is rushed, a
            // citizen stands against them, walks up with a pamphlet and gives a campaign speech.
            "election", List.of(
                    new Step(20, "day: at the town hall", List.of("playtest home", "time set 6000", "weather clear")),
                    new Step(25, "the player stands for mayor with the campaign book", List.of("playtest stand")),
                    // #256: the speech capture shows "Listening... N s left", counting down.
                    new Step(30, "screenshot: the speech countdown", List.of("client:screenshot speech_countdown")),
                    new Step(60, "the campaign is rushed: a citizen stands", List.of("townhall rush")),
                    new Step(330, "scenario done", List.of())),
            // Each addon window opens, and its close button closes it (a real mouse click).
            "windows", List.of(
                    new Step(20, "out in the open", List.of("playtest home", "tp @s ~24 ~ ~24", "execute align xyz run tp @s ~0.5 ~ ~0.5 0 0",
                            "item replace entity @s weapon.mainhand with air")),
                    new Step(23, "notice board: placed", List.of("setblock ~ ~ ~2 tc_noticeboard:notice_board[facing=north]", "tp @s ~ ~ ~ facing ~ ~0.5 ~2")),
                    new Step(25, "notice board: opened", List.of("client:useblock 0 0 2")),
                    new Step(27, "notice board: close clicked", List.of("client:screen", "client:screenshot window_notice_board", "client:click close")),
                    new Step(28, "notice board: after close", List.of("client:screen", "client:close", "setblock ~ ~ ~2 air")),
                    new Step(31, "ballot box: placed", List.of("setblock ~ ~ ~2 tc_townhall:ballot_box[facing=north]", "tp @s ~ ~ ~ facing ~ ~0.5 ~2")),
                    new Step(33, "ballot box: opened", List.of("client:useblock 0 0 2")),
                    new Step(35, "ballot box: close clicked", List.of("client:screen", "client:screenshot window_ballot_box", "client:click close")),
                    new Step(36, "ballot box: after close", List.of("client:screen", "client:close", "setblock ~ ~ ~2 air")),
                    new Step(39, "suggestion box: placed", List.of("setblock ~ ~ ~2 tc_townhall:suggestion_box[facing=north]", "tp @s ~ ~ ~ facing ~ ~0.5 ~2")),
                    new Step(41, "suggestion box: opened", List.of("client:useblock 0 0 2")),
                    new Step(43, "suggestion box: close clicked", List.of("client:screen", "client:screenshot window_suggestion_box", "client:click close")),
                    new Step(44, "suggestion box: after close", List.of("client:screen", "client:close", "setblock ~ ~ ~2 air")),
                    // The item reaches the client a tick later, so it is used in the next step.
                    new Step(46, "handbook: in hand", List.of("item replace entity @s weapon.mainhand with mc_talking:colony_handbook")),
                    new Step(47, "handbook: opened", List.of("client:useitem")),
                    new Step(49, "handbook: close clicked", List.of("client:screen", "client:screenshot window_handbook", "client:click close")),
                    new Step(50, "handbook: after close", List.of("client:screen", "client:close", "item replace entity @s weapon.mainhand with air")),
                    new Step(52, "scenario done", List.of())),
            // A brand-new colony: the welcome, a chat with the citizen who gave it (typed, as with
            // /citizen_chat), asking how to tell everyone something, then the next introduction.
            "firstday", List.of(
                    new Step(3, "day: the player arrives", List.of("time set 1000", "weather clear",
                            "awaitever 240 walks up to \\w+ about mc_talking:welcome")),
                    new Step(4, "the welcome is said", List.of("awaitever 90 \"type\":\"said\".*\"kind\":\"ADDON_AMBIENT\"")),
                    new Step(6, "talk to the citizen next to you", List.of("citizen_chat on", "playtest talk",
                            // Ready for the player: after the citizen's opening line, if any. A session already open
                            // (e.g. for an ambient line) logs no new ACTIVE.
                            "await 60 has dirty AI status LISTENING")),
                    new Step(8, "asks about the day", List.of("chat:Hey! How's your day going?",
                            "await 60 \"type\":\"said\".*\"kind\":\"PLAYER\"")),
                    new Step(14, "asks how to tell everyone", List.of(
                            "chat:How can I tell everyone in the colony something? Can you do it for me?",
                            "await 60 \"type\":\"said\".*\"kind\":\"PLAYER\"")),
                    new Step(28, "walks away", List.of("tp @s ~48 ~ ~")),
                    new Step(45, "waits for the next introduction", List.of("playtest home",
                            "await 420 walks up to \\w+ about (?!mc_talking:welcome)")),
                    new Step(46, "the introduction is said", List.of("await 90 \"type\":\"said\".*\"kind\":\"ADDON_AMBIENT\"")),
                    new Step(50, "scenario done", List.of())),
            // A new residence is ordered from the colony's builder; the builder and another citizen are asked
            // how it is going (their answers are compared with the logged TC_BUILD state).
            "construction", List.of(
                    new Step(20, "day: at home", List.of("playtest home", "time set 1000", "weather clear")),
                    new Step(22, "a new residence is ordered", List.of("playtest build", "await 300 TC_BUILD claimed by")),
                    new Step(90, "goes to the builder", List.of("playtest goto builder")),
                    new Step(92, "talks to the builder", List.of("citizen_chat on", "playtest talk", "await 60 has dirty AI status LISTENING")),
                    new Step(94, "asks the builder about the house", List.of(
                            "chat:Hey! How is the new house coming along? Do you need anything for it?",
                            "await 60 \"type\":\"said\".*\"kind\":\"PLAYER\"")),
                    new Step(110, "walks over to another citizen", List.of("playtest goto citizen")),
                    new Step(125, "talks to another citizen", List.of("playtest talk", "await 60 has dirty AI status LISTENING")),
                    new Step(127, "asks another citizen about the house", List.of(
                            "chat:Do you know how the new house is coming along?",
                            "await 60 \"type\":\"said\".*\"kind\":\"PLAYER\"")),
                    new Step(140, "scenario done", List.of())),
            // A notice is posted on the board by hand (typed and clicked), word spreads, replies are collected.
            "notice", List.of(
                    new Step(20, "day: in the colony", List.of("playtest home", "time set 1000", "weather clear",
                            "item replace entity @s weapon.mainhand with air", "execute align xyz run tp @s ~0.5 ~ ~0.5 0 0")),
                    new Step(22, "notice board placed", List.of("setblock ~ ~ ~2 tc_noticeboard:notice_board[facing=north]",
                            "tp @s ~ ~ ~ facing ~ ~0.5 ~2")),
                    new Step(24, "notice board opened", List.of("client:useblock 0 0 2")),
                    new Step(26, "notice posted", List.of("client:type titleInput Harvest fair",
                            "client:type bodyInput Next Sunday we hold a harvest fair at the town hall. Bring your best pumpkins and something to share!",
                            "client:screenshot notice_typed", "client:click post")),
                    new Step(28, "window closed", List.of("client:close")),
                    new Step(150, "replies are collected", List.of("noticeboard rush", "await 120 Pinned \\d+ replies")),
                    new Step(155, "the board is read again", List.of("client:useblock 0 0 2")),
                    new Step(157, "replies on the board", List.of("client:dump replies", "client:dump reach",
                            "client:screenshot notice_replies", "client:close")),
                    new Step(160, "scenario done", List.of())),
            // The colony from above, to look at its buildings.
            "overview", List.of(
                    new Step(15, "above the plaza", List.of("playtest home", "time set 6000", "weather clear", "client:hud off",
                            "gamemode spectator", "tp @s ~ ~35 ~-15 facing ~ ~ ~25")),
                    new Step(18, "screenshot: the colony", List.of("client:screenshot colony_overview")),
                    new Step(45, "screenshot: the colony later", List.of("client:screenshot colony_overview_45")),
                    new Step(90, "screenshot: the colony much later", List.of("client:screenshot colony_overview_90")),
                    new Step(91, "scenario done", List.of())),
            // A pair of citizens chatting by day, then night: chats end when they fall asleep, none start.
            "night", List.of(
                    new Step(20, "day: standing among citizens", List.of("playtest home", "time set 6000", "weather clear",
                            "playtest goto citizen",
                            "tp @e[type=minecolonies:citizen,sort=nearest,limit=1,distance=3..] ~2 ~ ~2",
                            // Pair chats depend on chance, budget and cooldowns: the scenario goes on without one.
                            "await? 300 \\[RandomConv\\] Starting conversation")),
                    new Step(25, "night falls", List.of("time set 18000")),
                    new Step(90, "citizens are asleep", List.of("playtest probe")),
                    new Step(180, "still night", List.of("playtest probe")),
                    new Step(185, "scenario done", List.of())),
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
    private static @Nullable Pattern awaiting;
    private static String awaitText = "";
    private static boolean awaitOptional;
    private static int awaitFrom;
    private static int awaitTicksLeft;

    private PlaytestScenario() {
    }

    static void init() {
        String name = System.getProperty("tc_playtest.scenario");
        if (name == null || name.isBlank()) return;
        steps = SCENARIOS.get(name);
        if (steps == null) throw new IllegalArgumentException("Unknown playtest scenario " + name
                + ", known: " + SCENARIOS.keySet());
        LogTap.install();
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
        if (mc.player == null || mc.level == null || next >= steps.size() && awaiting == null) return;
        if (ticksInWorld == 0) LogTap.install();
        // While waiting for something to happen, the scenario clock stands still.
        if (awaiting != null && !awaited()) return;
        if (next >= steps.size()) {
            finish(mc);
            return;
        }
        ticksInWorld++;
        Step step = steps.get(next);
        if (ticksInWorld < step.atSeconds() * 20) return;
        next++;
        int stepStart = LogTap.size();
        mark(step.mark());
        for (String command : step.commands()) {
            if (command.startsWith("chat:")) mc.player.connection.sendChat(command.substring("chat:".length()));
            else if (command.startsWith("client:")) client(mc, command.substring("client:".length()));
            else if (command.startsWith("await ") || command.startsWith("await? ") || command.startsWith("awaitever ")) {
                await(command, stepStart);
            }
            else mc.player.connection.sendCommand(command);
        }
        if (next == steps.size() && awaiting == null) finish(mc);
    }

    private static void finish(Minecraft mc) {
        next = steps.size() + 1;
        PlaytestMod.LOGGER.info("TC_PLAYTEST_SCENARIO_DONE");
        mc.stop();
    }

    /**
     * {@code await <seconds> <regex>}: waits until a log line logged since this step started matches
     * ({@code awaitever}: since the game started), at most that long. Logged as TC_AWAIT matched/timeout,
     * or TC_AWAIT gave up for {@code await?}, which may not happen (the checks then skip what depends on it),
     * which the scenario checks read.
     */
    private static void await(String command, int stepStart) {
        String[] parts = command.split(" ", 3);
        awaiting = Pattern.compile(parts[2]);
        awaitText = parts[2];
        awaitOptional = parts[0].equals("await?");
        awaitFrom = parts[0].equals("awaitever") ? 0 : stepStart;
        awaitTicksLeft = Integer.parseInt(parts[1]) * 20;
    }

    private static boolean awaited() {
        String line = LogTap.find(awaiting, awaitFrom);
        if (line != null) {
            PlaytestMod.LOGGER.info("TC_AWAIT matched {} :: {}", awaitText, line.length() > 300 ? line.substring(0, 300) : line);
        } else if (--awaitTicksLeft <= 0) {
            if (awaitOptional) PlaytestMod.LOGGER.info("TC_AWAIT gave up {}", awaitText);
            else PlaytestMod.LOGGER.warn("TC_AWAIT timeout {}", awaitText);
        } else {
            return false;
        }
        awaiting = null;
        return true;
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
            case "useitem" -> {
                if (mc.gameMode != null) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            }
            case "click" -> click(mc, parts.length > 1 ? parts[1] : "");
            case "type" -> type(mc, parts.length > 1 ? parts[1] : "");
            case "dump" -> dump(mc, parts.length > 1 ? parts[1] : "");
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
            // BOScreen maps a mouse position m to (m * mcScale - x) / renderScale; this is the inverse.
            double scale = screenField(screen, "renderScale");
            double mcScale = screenField(screen, "mcScale");
            double mouseX = (screenField(screen, "x") + x * scale) / mcScale;
            double mouseY = (screenField(screen, "y") + y * scale) / mcScale;
            boolean handled = screen.mouseClicked(mouseX, mouseY, 0);
            screen.mouseReleased(mouseX, mouseY, 0);
            PlaytestMod.LOGGER.info("TC_SCENARIO_CLICK {} at {},{}: handled={}", id, (int) mouseX, (int) mouseY, handled);
        } catch (ReflectiveOperationException e) {
            PlaytestMod.LOGGER.warn("TC_SCENARIO_CLICK {}: {}", id, e.toString());
        }
    }

    /** "<pane id> <text>": types the text into that text field of the open BlockUI window. */
    private static void type(Minecraft mc, String arguments) {
        String[] parts = arguments.split(" ", 2);
        if (!(mc.screen instanceof BOScreen screen)
                || !(screen.getWindow().findPaneByID(parts[0]) instanceof TextField field)) {
            PlaytestMod.LOGGER.warn("TC_SCENARIO_TYPE {}: no such text field in an open window", parts[0]);
            return;
        }
        field.setText(parts.length > 1 ? parts[1] : "");
        PlaytestMod.LOGGER.info("TC_SCENARIO_TYPE {}", parts[0]);
    }

    /** Logs every text shown inside that pane of the open BlockUI window (e.g. a list of replies) as TC_SCENARIO_TEXT. */
    private static void dump(Minecraft mc, String id) {
        Pane pane = mc.screen instanceof BOScreen screen ? screen.getWindow().findPaneByID(id) : null;
        if (pane == null) {
            PlaytestMod.LOGGER.warn("TC_SCENARIO_TEXT {}: no such pane in an open window", id);
            return;
        }
        dump(id, pane);
    }

    private static void dump(String id, Pane pane) {
        if (pane instanceof AbstractTextElement text && !text.getTextAsString().isBlank()) {
            PlaytestMod.LOGGER.info("TC_SCENARIO_TEXT {} :: {}", id, text.getTextAsString().replace('\n', ' '));
        }
        if (pane instanceof View view) {
            for (Pane child : view.getChildren()) dump(id, child);
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
