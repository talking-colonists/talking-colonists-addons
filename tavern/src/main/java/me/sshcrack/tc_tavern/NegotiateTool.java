package me.sshcrack.tc_tavern;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IVisitorData;
import me.sshcrack.mc_talking.api.memory.AddonConfirmedOutcome;
import me.sshcrack.mc_talking.api.memory.CitizenMemoryService;
import me.sshcrack.mc_talking.api.tool.AiCommandTool;
import me.sshcrack.mc_talking.api.tool.AiToolContext;
import me.sshcrack.mc_talking.api.tool.AiToolParameter;
import me.sshcrack.mc_talking.api.tool.AiToolScope;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * {@code tc_tavern:negotiate_recruit_cost}: a tavern visitor lowers what recruiting them costs, within
 * the limits of {@link Haggle}. Only visitors can use it, and only with a colony member.
 */
final class NegotiateTool implements AiCommandTool {
    static final String NAME = "negotiate_recruit_cost";
    private static final int MAX_REASON_CHARS = 160;

    /** The running store; supplies null while no server runs. */
    private final Supplier<HaggleStore> store;

    NegotiateTool(Supplier<HaggleStore> store) {
        this.store = store;
    }

    @Override
    public String description() {
        return "Only for a tavern visitor: lower what the player must pay to recruit you, when the conversation "
                + "convinced you. new_amount is the new number of items; the item stays the same. The result says "
                + "the price that actually applies; tell the player that price.";
    }

    @Override
    public AiToolParameter parameters() {
        return AiToolParameter.object(Map.of(
                "new_amount", AiToolParameter.integer(true),
                "reason", AiToolParameter.string(true)));
    }

    @Override
    public AiToolScope scope() {
        return AiToolScope.PLAYER_CONVERSATION;
    }

    @Override
    public boolean canExecute(AiToolContext context) {
        ServerPlayer player = context.player();
        return context.citizen().getCitizenData() instanceof IVisitorData
                && player != null && context.colony().getPermissions().isColonyMember(player);
    }

    @Override
    public CompletionStage<JsonObject> executeCommand(AiToolContext context, @Nullable JsonObject parameters) {
        return context.supplyOnServerThread(() -> negotiate(context, parameters));
    }

    private JsonObject negotiate(AiToolContext context, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        HaggleStore haggles = store.get();
        if (!(context.citizen().getCitizenData() instanceof IVisitorData visitor) || haggles == null) {
            result.addProperty("error", "only a tavern visitor can change a recruit price");
            return result;
        }
        ItemStack cost = visitor.getRecruitCost();
        if (cost == null || cost.isEmpty()) {
            result.addProperty("error", "you have no recruit price");
            return result;
        }
        int requested = parameters != null && parameters.has("new_amount") ? parameters.get("new_amount").getAsInt() : cost.getCount();
        String reason = parameters != null && parameters.has("reason") ? parameters.get("reason").getAsString().strip() : "";
        if (reason.length() > MAX_REASON_CHARS) reason = reason.substring(0, MAX_REASON_CHARS);

        HaggleStore.Entry entry = haggles.get(context.citizen().getUUID(), cost.getCount());
        long day = context.citizen().level().getDayTime() / 24_000;
        if (entry.day != day) {
            entry.day = day;
            entry.changesToday = 0;
        }
        String item = cost.getHoverName().getString();
        if (entry.changesToday >= Haggle.MAX_CHANGES_PER_DAY) {
            result.addProperty("price", cost.getCount() + " x " + item);
            result.addProperty("note", "You already changed your price " + Haggle.MAX_CHANGES_PER_DAY
                    + " times today; it stays as it is until tomorrow.");
            return result;
        }
        Haggle.Result outcome = Haggle.apply(entry.original, cost.getCount(), requested);
        int before = cost.getCount();
        if (outcome.amount() < before) {
            visitor.setRecruitCosts(cost.copyWithCount(outcome.amount()));
            entry.changesToday++;
            haggles.save();
            announce(context, visitor, outcome.amount() + " x " + item, before + " x " + item);
            remember(context, visitor, before, outcome.amount(), item, reason, entry);
        }
        result.addProperty("price", outcome.amount() + " x " + item);
        result.addProperty("previous_price", before + " x " + item);
        result.addProperty("note", outcome.note());
        result.addProperty("lowest_possible", Haggle.floor(entry.original) + " x " + item);
        return result;
    }

    private static void announce(AiToolContext context, ICitizenData visitor, String price, String before) {
        ServerPlayer player = context.player();
        if (player == null) return;
        player.sendSystemMessage(Component.literal("[Tavern] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(visitor.getName() + " now asks " + price + " to join (was " + before + ").")
                        .withStyle(ChatFormatting.GRAY)));
    }

    /** The visitor remembers the deal; the memory moves with them if they are recruited. */
    private static void remember(AiToolContext context, ICitizenData visitor, int before, int after, String item,
                                 String reason, HaggleStore.Entry entry) {
        ServerPlayer player = context.player();
        String who = player == null ? "a colonist" : player.getGameProfile().getName();
        String event = "Agreed with " + who + " to join " + context.colony().getName() + " for " + after + " x " + item
                + " instead of " + before + (reason.isEmpty() ? "." : " because " + reason);
        try {
            CitizenMemoryService.confirmOutcome(visitor, new AddonConfirmedOutcome(TavernRecruiter.MOD_ID,
                    context.citizen().getUUID() + ":" + entry.day + ":" + entry.changesToday,
                    event.length() > 300 ? event.substring(0, 300) : event,
                    player == null ? null : player.getUUID(), List.of(), List.of()));
        } catch (RuntimeException e) {
            TavernRecruiter.LOGGER.warn("Could not store the haggling memory of {}", visitor.getName(), e);
        }
    }
}
