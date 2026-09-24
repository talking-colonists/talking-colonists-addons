package me.sshcrack.tc_playtest;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
/*? if forge {*/
/*import net.minecraftforge.fml.ModList;
*//*?}*/
/*? if neoforge {*/
import net.neoforged.fml.ModList;
/*?}*/

import java.util.List;

/**
 * The clickable "things to try" list shown on join and by {@code /playtest}. Add a section when an
 * addon lands; sections of addons that are not loaded are hidden.
 */
final class Checklist {
    private record Button(String label, String command, String hint) {
    }

    private record Section(String modId, String title, String howTo, List<Button> buttons) {
    }

    private static final List<Section> SECTIONS = List.of(
            new Section("", "World", "Colony \"" + PlaytestColony.NAME + "\" is next to spawn.", List.of(
                    new Button("Go to colony", "/playtest home", "Teleport to the town hall"),
                    new Button("Morning", "/time set 1000", "Set the time to 1000"),
                    new Button("Dusk", "/time set 12000", "Set the time to 12000"),
                    new Button("Sample news", "/playtest news", "Record a raid, a harvest and a newcomer as colony events"))),
            new Section("tc_gazette", "Colony Gazette",
                    "Every morning the teacher writes about yesterday. Add news, publish, then read your copy.", List.of(
                    new Button("Publish now", "/gazette publish", "Write an issue right away (uses one Gemini request)"),
                    new Button("Get a copy", "/gazette", "Hand me the latest issue"))),
            new Section("tc_campfire", "Campfire Nights",
                    "At dusk, idle citizens tell stories at the campfire. Stand close and chat to join in.", List.of(
                    new Button("Start now", "/campfire start", "Gather idle citizens at the nearest campfire (uses Gemini Live)"),
                    new Button("Stop", "/campfire stop", "End the campfire night"))));

    private Checklist() {
    }

    static void send(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("== Talking Colonists addon playtest ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (Section section : SECTIONS) {
            if (!section.modId().isEmpty() && !ModList.get().isLoaded(section.modId())) continue;
            player.sendSystemMessage(Component.literal(section.title()).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(" - " + section.howTo()).withStyle(ChatFormatting.GRAY)));
            MutableComponent line = Component.literal("  ");
            for (Button button : section.buttons()) {
                line.append(button(button)).append(" ");
            }
            player.sendSystemMessage(line);
        }
        player.sendSystemMessage(Component.literal("Type /playtest to show this again.").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static Component button(Button button) {
        return Component.literal("[" + button.label() + "]").withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, button.command()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal(button.hint() + "\n" + button.command()))));
    }
}
