package me.sshcrack.tc_townhall;

import com.google.gson.JsonObject;
import me.sshcrack.mc_talking.api.tool.AiCommandTool;
import me.sshcrack.mc_talking.api.tool.AiToolContext;
import me.sshcrack.mc_talking.api.tool.AiToolParameter;
import me.sshcrack.mc_talking.api.tool.AiToolScope;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * {@code tc_townhall:answer_proposal}: the player told the citizen mayor yes or no to the mayor's
 * proposal. Only the sitting citizen mayor can use it, with a colony member, while a proposal waits.
 */
final class AnswerProposalTool implements AiCommandTool {
    @Override
    public String description() {
        return "Only for the colony's mayor: record the player's answer to your proposal, once they clearly agreed "
                + "or refused. accept is true when they agreed. reason is their reason in a few words. The result "
                + "says what happened; tell the player.";
    }

    @Override
    public AiToolParameter parameters() {
        return AiToolParameter.object(Map.of(
                "accept", AiToolParameter.bool(true),
                "reason", AiToolParameter.string(false)));
    }

    @Override
    public AiToolScope scope() {
        return AiToolScope.PLAYER_CONVERSATION;
    }

    @Override
    public boolean canExecute(AiToolContext context) {
        Elections elections = TownHall.elections();
        ServerPlayer player = context.player();
        if (elections == null || player == null || !context.colony().getPermissions().isColonyMember(player)) return false;
        Elections.Mayor mayor = elections.mayor(context.colony());
        return mayor != null && mayor.citizen && mayor.id.equals(context.citizen().getUUID())
                && mayor.proposal != null && mayor.proposal.status == Office.ProposalStatus.PENDING;
    }

    @Override
    public CompletionStage<JsonObject> executeCommand(AiToolContext context, @Nullable JsonObject parameters) {
        return context.supplyOnServerThread(() -> {
            JsonObject result = new JsonObject();
            Elections elections = TownHall.elections();
            if (elections == null || !canExecute(context)) {
                result.addProperty("error", "there is no proposal of yours waiting for this player's answer");
                return result;
            }
            boolean accept = parameters != null && parameters.has("accept") && parameters.get("accept").getAsBoolean();
            String reason = parameters != null && parameters.has("reason") ? parameters.get("reason").getAsString() : "";
            result.addProperty("result", elections.office().answer(context.colony(), context.requirePlayer(), accept, reason));
            return result;
        });
    }
}
