package me.sshcrack.tc_townhall;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.entity.citizen.happiness.IHappinessModifier;
import com.minecolonies.api.util.constant.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the colony lacks, counted from MineColonies: grown citizens whose happiness modifier is below neutral. */
final class ColonyNeeds {
    /** MineColonies' {@code RaidManager} aborts a raid on a colony below this raid level. */
    private static final int MIN_RAID_LEVEL = 75;

    private ColonyNeeds() {
    }

    static Map<Need, Integer> count(IColony colony) {
        Map<Need, Integer> counts = new EnumMap<>(Need.class);
        for (Need need : Need.values()) counts.put(need, 0);
        boolean raidsPossible = raidsPossible(colony);
        for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
            if (data.isChild()) continue;
            for (Need need : Need.values()) {
                if (need == Need.SAFETY && !raidsPossible) continue;
                IHappinessModifier modifier = data.getCitizenHappinessHandler().getModifier(need.modifier());
                if (modifier != null && modifier.getFactor(data) < 1.0) counts.merge(need, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Whether guards matter yet. MineColonies counts too few guards against every colony from day one,
     * but it never raids a colony below raid level 75 (see docs/minecolonies-mechanics.md in the main
     * repo), nor on peaceful. Until the colony was raided or is strong enough to be, the mayor does not
     * make safety an issue.
     */
    static boolean raidsPossible(IColony colony) {
        if (colony.getWorld().getDifficulty() == Difficulty.PEACEFUL || !colony.getRaiderManager().canHaveRaiderEvents()) return false;
        return colony.getStatisticsManager().getStatTotal(Constants.MOD_ID + ".raids_total") > 0
                || colony.getRaiderManager().getColonyRaidLevel() >= MIN_RAID_LEVEL;
    }

    /** Every hut of the colony, whether there is a work order for it, and the builder at work on it. */
    static List<Office.Hut> huts(IColony colony) {
        Set<BlockPos> ordered = new HashSet<>();
        Map<BlockPos, String> builders = new HashMap<>();
        for (IServerWorkOrder order : colony.getWorkManager().getWorkOrders().values()) {
            ordered.add(order.getLocation());
            String builder = builder(colony, order);
            if (builder != null) builders.put(order.getLocation(), builder);
        }
        List<Office.Hut> huts = new ArrayList<>();
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            huts.add(new Office.Hut(type(building), building.getPosition().asLong(), building.getBuildingLevel(),
                    building.getMaxBuildingLevel(), ordered.contains(building.getPosition()), builders.get(building.getPosition())));
        }
        return huts;
    }

    /** The builder who claimed the work order, when their hut has one; null while it waits for a builder. */
    private static @Nullable String builder(IColony colony, IServerWorkOrder order) {
        if (!order.isClaimed()) return null;
        IBuilding hut = colony.getServerBuildingManager().getBuilding(order.getClaimedBy());
        if (hut == null) return null;
        return hut.getAllAssignedCitizen().stream().findFirst().map(ICitizenData::getName).orElse(null);
    }

    static String type(IBuilding building) {
        return building.getBuildingType().getRegistryName().getPath();
    }

    static boolean hasWorkOrder(IColony colony, BlockPos pos) {
        for (IServerWorkOrder order : colony.getWorkManager().getWorkOrders().values()) {
            if (order.getLocation().equals(pos)) return true;
        }
        return false;
    }
}
