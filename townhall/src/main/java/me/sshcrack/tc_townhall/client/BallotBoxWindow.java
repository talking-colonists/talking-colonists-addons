package me.sshcrack.tc_townhall.client;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Gradient;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.controls.TextField;
import com.ldtteam.blockui.views.BOWindow;
import com.ldtteam.blockui.views.ScrollingList;
import com.ldtteam.blockui.views.View;
import me.sshcrack.tc_townhall.BallotView;
import me.sshcrack.tc_townhall.TownHall;
import me.sshcrack.tc_townhall.block.BallotBoxBlockEntity;
import me.sshcrack.tc_townhall.shared.net.BlockActions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The ballot box window: the candidates with their slogans and platforms, a live tally while citizens
 * vote, how they voted and why, and a form to stand for mayor and give a speech. The server sends the
 * election when the window opens and every two seconds after.
 */
public final class BallotBoxWindow extends BOWindow {
    private static final Gson GSON = new Gson();
    private static final int REFRESH_TICKS = 40;
    private static final int BAR_WIDTH = 120;

    private final BlockPos pos;
    private final ScrollingList candidates;
    private final ScrollingList reasons;
    private final TextField sloganInput;
    private final TextField platformInput;
    private BallotView view = new BallotView();
    private boolean loaded;
    private boolean prefilled;
    private int ticks;

    public BallotBoxWindow(BlockPos pos) {
        super(layout());
        this.pos = pos;
        this.candidates = findPaneOfTypeByID("candidates", ScrollingList.class);
        this.reasons = findPaneOfTypeByID("reasons", ScrollingList.class);
        this.sloganInput = findPaneOfTypeByID("sloganInput", TextField.class);
        this.platformInput = findPaneOfTypeByID("platformInput", TextField.class);
        findPaneOfTypeByID("close", Button.class).setHandler(button -> close());
        findPaneOfTypeByID("stand", Button.class).setHandler(button -> stand());
        findPaneOfTypeByID("speak", Button.class).setHandler(button -> {
            BlockActions.send(pos, BallotBoxBlockEntity.SPEAK, 0);
            close();
        });
        candidates.setDataProvider(new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                return view.candidates.size();
            }

            @Override
            public void updateElement(int index, Pane row) {
                if (index >= view.candidates.size()) return;
                BallotView.Entry entry = view.candidates.get(index);
                String tag = entry.you ? " (you)" : entry.citizen ? " (citizen)" : "";
                row.findPaneOfTypeByID("name", Text.class).setText(Component.literal(entry.name + tag));
                row.findPaneOfTypeByID("slogan", Text.class).setText(Component.literal("\"" + entry.slogan + "\""));
                row.findPaneOfTypeByID("platform", Text.class).setText(Component.literal(entry.platform));
                boolean voting = view.phase == BallotView.Phase.VOTING;
                Gradient bar = row.findPaneOfTypeByID("bar", Gradient.class);
                Text votes = row.findPaneOfTypeByID("votes", Text.class);
                bar.setVisible(voting);
                votes.setVisible(voting);
                if (voting) {
                    int most = view.candidates.stream().mapToInt(candidate -> candidate.votes).max().orElse(0);
                    boolean leading = entry.votes > 0 && entry.votes == most;
                    bar.setSize(Math.max(1, view.voted == 0 ? 1 : entry.votes * BAR_WIDTH / Math.max(1, view.voted)), 5);
                    if (leading) {
                        bar.setGradientStart(70, 140, 60, 255);
                        bar.setGradientEnd(40, 110, 40, 255);
                    } else {
                        bar.setGradientStart(150, 120, 80, 255);
                        bar.setGradientEnd(120, 90, 60, 255);
                    }
                    votes.setText(Component.literal(entry.votes + (entry.votes == 1 ? " vote" : " votes")));
                }
            }
        });
        reasons.setDataProvider(new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                return view.reasons.size();
            }

            @Override
            public void updateElement(int index, Pane row) {
                if (index >= view.reasons.size()) return;
                BallotView.Reason reason = view.reasons.get(index);
                String who = reason.candidate.isEmpty() ? reason.voter + " did not vote" : reason.voter + " voted for " + reason.candidate;
                row.findPaneOfTypeByID("who", Text.class).setText(Component.literal(who));
                row.findPaneOfTypeByID("why", Text.class).setText(Component.literal(reason.reason));
            }
        });
        render();
        BlockActions.send(pos, BallotBoxBlockEntity.VIEW, 0);
    }

    private static ResourceLocation layout() {
        /*? if neoforge {*/
        return ResourceLocation.fromNamespaceAndPath(TownHall.MOD_ID, "gui/ballot_box.xml");
        /*?}*/
        /*? if forge {*/
        /*return new ResourceLocation(TownHall.MOD_ID, "gui/ballot_box.xml");
        *//*?}*/
    }

    public BlockPos pos() {
        return pos;
    }

    /** A view from the server. */
    void receive(String json) {
        try {
            BallotView received = GSON.fromJson(json, BallotView.class);
            if (received == null) return;
            view = received;
            loaded = true;
        } catch (JsonParseException e) {
            TownHall.LOGGER.warn("Unreadable ballot view", e);
            return;
        }
        if (!prefilled) {
            prefilled = true;
            view.candidates.stream().filter(entry -> entry.you).findFirst().ifPresent(entry -> {
                sloganInput.setText(entry.slogan);
                platformInput.setText(entry.platform);
            });
        }
        render();
    }

    private void stand() {
        String slogan = sloganInput.getText().strip();
        String platform = platformInput.getText().strip();
        if (slogan.isEmpty() || platform.isEmpty()) {
            text("hint").setText(Component.translatable("tc_townhall.gui.ballot_box.fill_in"));
            return;
        }
        BlockActions.send(pos, BallotBoxBlockEntity.STAND, 0, slogan + "\n" + platform);
        // The speech starts right away, like at the Town Hall; the window would be in the way.
        close();
    }

    private void render() {
        boolean inColony = !view.colony.isEmpty();
        text("title").setText(!loaded ? Component.translatable("tc_townhall.gui.ballot_box.loading")
                : inColony ? Component.translatable("tc_townhall.gui.ballot_box.title", view.colony)
                : Component.translatable("tc_townhall.gui.ballot_box.no_colony"));
        text("status").setText(Component.literal(loaded && inColony ? view.status() : ""));
        findPaneOfTypeByID("nobody", Text.class).setVisible(loaded && inColony && view.candidates.isEmpty());

        boolean voting = view.phase == BallotView.Phase.VOTING;
        findPaneOfTypeByID("results", View.class).setVisible(voting);
        findPaneOfTypeByID("form", View.class).setVisible(!voting && loaded && inColony);
        boolean canStand = view.canStand;
        for (String id : new String[] {"sloganLabel", "sloganInput", "platformLabel", "platformInput", "stand"}) {
            findPaneByID(id).setVisible(canStand);
        }
        findPaneOfTypeByID("stand", Button.class).setText(Component.translatable(view.youStand
                ? "tc_townhall.gui.ballot_box.update" : "tc_townhall.gui.ballot_box.stand"));
        findPaneByID("speak").setVisible(view.youStand && view.speechSupported && view.phase == BallotView.Phase.CAMPAIGN);
        text("hint").setText(hint());

        candidates.refreshElementPanes();
        reasons.refreshElementPanes();
    }

    private @Nullable Component hint() {
        if (!view.member) return Component.translatable("tc_townhall.gui.ballot_box.not_member");
        if (view.phase == BallotView.Phase.IDLE && !view.mayorResult.isEmpty()) return Component.literal(view.mayorResult);
        if (view.phase == BallotView.Phase.IDLE && view.canStand) return Component.translatable("tc_townhall.gui.ballot_box.how");
        if (view.phase == BallotView.Phase.CAMPAIGN && view.youStand) return Component.translatable("tc_townhall.gui.ballot_box.campaigning");
        if (view.phase == BallotView.Phase.CAMPAIGN) return Component.translatable("tc_townhall.gui.ballot_box.join");
        return Component.empty();
    }

    private Text text(String id) {
        return findPaneOfTypeByID(id, Text.class);
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (++ticks % REFRESH_TICKS == 0) BlockActions.send(pos, BallotBoxBlockEntity.VIEW, 0);
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getBlockEntity(pos) instanceof BallotBoxBlockEntity)) close();
    }
}
