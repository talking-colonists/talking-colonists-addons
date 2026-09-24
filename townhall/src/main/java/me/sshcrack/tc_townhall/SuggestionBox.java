package me.sshcrack.tc_townhall;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.workerbuildings.ITownHall;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.api.text.CitizenTextService;
import me.sshcrack.mc_talking.api.text.TextRequest;
import me.sshcrack.tc_townhall.shared.book.WrittenBooks;
import me.sshcrack.tc_townhall.shared.provider.TextCapacity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The suggestion box: a barrel next to the colony's Town Hall block. Every morning up to
 * {@link #MAX_WRITERS} of the least happy grown citizens drop a short signed note in it, one real
 * concern or wish each, in their own voice. The player reads them by opening the barrel. At most
 * {@link #MAX_NOTES} notes wait in the box. Not saved: a restart at worst skips one morning.
 */
public final class SuggestionBox {
    static final int RANGE = 4;
    static final int MAX_WRITERS = 3;
    static final int MAX_NOTES = 6;
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
            BarrelBlockEntity box = find(colony, level);
            if (box == null) continue;
            if (!TextCapacity.hasSpare()) continue; // try again later this morning
            lastDay.put(key, colony.getDay());
            collect(colony, box);
        }
    }

    /** Operators and the self-test: citizens write their notes now, whatever the time of day. */
    public void rush(IColony colony) {
        if (!(colony.getWorld() instanceof ServerLevel level)) return;
        BarrelBlockEntity box = find(colony, level);
        if (box == null) return;
        lastDay.put(Elections.key(colony), colony.getDay());
        collect(colony, box);
    }

    /** The barrel nearest to the colony's Town Hall block, within {@link #RANGE}; null when there is none. */
    public static @Nullable BarrelBlockEntity find(IColony colony, ServerLevel level) {
        ITownHall townHall = colony.getServerBuildingManager().getTownHall();
        if (townHall == null) return null;
        BlockPos center = townHall.getPosition();
        BarrelBlockEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-RANGE, -RANGE, -RANGE), center.offset(RANGE, RANGE, RANGE))) {
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof BarrelBlockEntity barrel)) continue;
            double distance = pos.distSqr(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = barrel;
            }
        }
        return best;
    }

    private void collect(IColony colony, BarrelBlockEntity box) {
        int room = MAX_NOTES - notes(box);
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

    private void write(IColony colony, BarrelBlockEntity box, ICitizenData writer) {
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
            if (box.isRemoved() || notes(box) >= MAX_NOTES || !drop(box, note(writer.getName(), note))) return;
            tellMembersNear(colony, writer.getName() + " dropped a note in the suggestion box at the town hall.");
        }));
    }

    static ItemStack note(String writer, String text) {
        return WrittenBooks.create(ElectionText.cut(NOTE_TITLE_PREFIX + writer, 32), writer, WrittenBooks.ORIGINAL,
                List.of(Component.literal(text)));
    }

    /** Notes already waiting in the box. */
    static int notes(BarrelBlockEntity box) {
        int count = 0;
        for (int slot = 0; slot < box.getContainerSize(); slot++) {
            ItemStack stack = box.getItem(slot);
            if (stack.is(Items.WRITTEN_BOOK) && stack.getHoverName().getString().startsWith(NOTE_TITLE_PREFIX)) count++;
        }
        return count;
    }

    private static boolean drop(BarrelBlockEntity box, ItemStack note) {
        for (int slot = 0; slot < box.getContainerSize(); slot++) {
            if (box.getItem(slot).isEmpty()) {
                box.setItem(slot, note);
                box.setChanged();
                return true;
            }
        }
        return false;
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
