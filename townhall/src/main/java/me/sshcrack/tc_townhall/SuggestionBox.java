package me.sshcrack.tc_townhall;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.api.text.CitizenTextService;
import me.sshcrack.mc_talking.api.text.TextRequest;
import me.sshcrack.tc_townhall.block.SuggestionBoxBlockEntity;
import me.sshcrack.tc_townhall.shared.book.WrittenBooks;
import me.sshcrack.tc_townhall.shared.provider.TextCapacity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The suggestion box: a Suggestion Box block anywhere in the colony. Every morning up to
 * {@link #MAX_WRITERS} of the least happy grown citizens drop a short note in it, one real concern or
 * wish each, in their own voice. The player reads them in the box's window, and may take one along as a
 * one-page book. At most {@link SuggestionBoxBlockEntity#MAX_NOTES} notes wait. Which morning was last
 * collected is not saved: a restart at worst lets citizens write twice that day.
 */
public final class SuggestionBox {
    static final int MAX_WRITERS = 3;
    /** Only citizens below this happiness (0 to 10) write; content citizens have nothing to post. */
    static final double WRITE_BELOW_HAPPINESS = 7.5;
    /** Mornings: the first tenth of the day. */
    private static final long MORNING_END = 2_400;
    private static final int CHECK_INTERVAL_TICKS = 200;
    static final String NOTE_TITLE_PREFIX = "Note from ";

    private final MinecraftServer server;
    /** Colony key → the colony day notes were last written. */
    private final Map<String, Integer> lastDay = new HashMap<>();
    private final Set<Integer> writing = new HashSet<>();
    private int ticks;

    SuggestionBox(MinecraftServer server) {
        this.server = server;
    }

    void tick() {
        if (++ticks % CHECK_INTERVAL_TICKS != 0) return;
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (!(colony.getWorld() instanceof ServerLevel level) || level.getDayTime() % 24_000 > MORNING_END) continue;
            String key = Elections.key(colony);
            if (lastDay.getOrDefault(key, -1) == colony.getDay()) continue;
            SuggestionBoxBlockEntity box = find(colony, level);
            if (box == null) continue;
            if (!TextCapacity.hasSpare()) continue; // try again later this morning
            lastDay.put(key, colony.getDay());
            collect(colony, box);
        }
    }

    /** Operators and the self-test: citizens write their notes now, whatever the time of day. */
    public void rush(IColony colony) {
        if (!(colony.getWorld() instanceof ServerLevel level)) return;
        SuggestionBoxBlockEntity box = find(colony, level);
        if (box == null) return;
        lastDay.put(Elections.key(colony), colony.getDay());
        collect(colony, box);
    }

    /** A loaded Suggestion Box inside the colony, the one with the most room; null when there is none. */
    public static @Nullable SuggestionBoxBlockEntity find(IColony colony, ServerLevel level) {
        SuggestionBoxBlockEntity best = null;
        for (SuggestionBoxBlockEntity box : SuggestionBoxBlockEntity.loaded()) {
            if (box.getLevel() != level || !colony.isCoordInColony(level, box.getBlockPos())) continue;
            if (best == null || box.notes().size() < best.notes().size()) best = box;
        }
        return best;
    }

    private void collect(IColony colony, SuggestionBoxBlockEntity box) {
        int room = SuggestionBoxBlockEntity.MAX_NOTES - box.notes().size();
        if (room <= 0) return;
        List<ICitizenData> writers = colony.getCitizenManager().getCitizens().stream()
                .filter(data -> !data.isChild() && data.getEntity().isPresent() && !writing.contains(data.getId()))
                .filter(data -> happiness(colony, data) < WRITE_BELOW_HAPPINESS)
                .sorted(Comparator.comparingDouble(data -> happiness(colony, data)))
                .limit(Math.min(room, MAX_WRITERS))
                .toList();
        for (ICitizenData writer : writers) {
            write(colony, box, writer);
        }
    }

    private void write(IColony colony, SuggestionBoxBlockEntity box, ICitizenData writer) {
        AbstractEntityCitizen entity = writer.getEntity().orElse(null);
        if (entity == null || !TextCapacity.hasSpare()) return;
        writing.add(writer.getId());
        TextRequest request = TextRequest.of(TownHall.MOD_ID + ":suggestion", SuggestionText.directive())
                .withMaxChars(SuggestionText.MAX_NOTE_CHARS);
        CitizenTextService.generate(entity, request).whenComplete((result, error) -> server.execute(() -> {
            writing.remove(writer.getId());
            String note = error == null && result.isSuccess() && result.text() != null ? SuggestionText.clean(result.text()) : "";
            if (note.isEmpty()) {
                TownHall.LOGGER.warn("{}'s suggestion note failed: {}", writer.getName(),
                        error != null ? error.toString() : result.status() + " " + result.detail());
                return;
            }
            if (box.isRemoved() || box.isFull()) return;
            box.add(new SuggestionBoxBlockEntity.Note(writer.getName(), role(writer), colony.getDay(), note));
            tellMembersNear(colony, writer.getName() + " dropped a note in the suggestion box.");
        }));
    }

    /** A note taken from the box, as a one-page signed book. */
    public static ItemStack note(String writer, String text) {
        return WrittenBooks.create(ElectionText.cut(NOTE_TITLE_PREFIX + writer, 32), writer, WrittenBooks.ORIGINAL,
                List.of(Component.literal(text)));
    }

    private static String role(ICitizenData data) {
        try {
            return data.getJob() == null ? "" : data.getJob().getJobRegistryEntry().getKey().getPath().replace('_', ' ');
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static double happiness(IColony colony, ICitizenData data) {
        try {
            return data.getCitizenHappinessHandler().getHappiness(colony, data);
        } catch (RuntimeException e) {
            return 10;
        }
    }

    private void tellMembersNear(IColony colony, String text) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (colony.getPermissions().isColonyMember(player) && player.level() == colony.getWorld()
                    && colony.isCoordInColony(player.level(), player.blockPosition())) {
                Elections.tell(player, text);
            }
        }
    }
}
