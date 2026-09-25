package me.sshcrack.tc_townhall;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.entity.citizen.happiness.IHappinessModifier;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the colony lacks, counted from MineColonies: grown citizens whose happiness modifier is below neutral. */
final class ColonyNeeds {
    private ColonyNeeds() {
    }

    static Map<Need, Integer> count(IColony colony) {
        Map<Need, Integer> counts = new EnumMap<>(Need.class);
        for (Need need : Need.values()) counts.put(need, 0);
        for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
            if (data.isChild()) continue;
            for (Need need : Need.values()) {
                IHappinessModifier modifier = data.getCitizenHappinessHandler().getModifier(need.modifier());
                if (modifier != null && modifier.getFactor(data) < 1.0) counts.merge(need, 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Every hut of the colony, and whether a builder already has a work order for it. */
    static List<Office.Hut> huts(IColony colony) {
        Set<BlockPos> ordered = new HashSet<>();
        for (IServerWorkOrder order : colony.getWorkManager().getWorkOrders().values()) ordered.add(order.getLocation());
        List<Office.Hut> huts = new ArrayList<>();
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            huts.add(new Office.Hut(type(building), building.getPosition().asLong(), building.getBuildingLevel(),
                    building.getMaxBuildingLevel(), ordered.contains(building.getPosition())));
        }
        return huts;
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
