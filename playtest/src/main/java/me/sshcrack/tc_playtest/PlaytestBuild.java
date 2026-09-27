package me.sshcrack.tc_playtest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.IBuilderWorkOrder;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * For the construction scenario: orders a new residence from the colony's builder, as placing a hut and
 * pressing "build" would, then logs how the order goes as TC_BUILD lines (claimed by whom, stage,
 * materials still needed) whenever that changes, so the scenario checks can compare what citizens say.
 */
final class PlaytestBuild {
    private static final int CHECK_TICKS = 100;
    private static @Nullable IColony colony;
    private static @Nullable BlockPos site;
    private static String lastState = "";
    private static int ticks;

    private PlaytestBuild() {
    }

    /** Places a residence hut 24 blocks from the player and asks the builder to build it. */
    static boolean order(ServerPlayer player) {
        IColony owned = PlaytestColony.find(player);
        AbstractBuildingStructureBuilder builderHut = owned == null ? null : builderHut(owned);
        if (builderHut == null) {
            PlaytestMod.LOGGER.warn("TC_BUILD no colony with a builder's hut");
            return false;
        }
        BlockPos pos = PlaytestColony.ground(player.serverLevel(), player.blockPosition().offset(0, 0, -24));
        IBuilding residence = PlaytestColony.hut(player.serverLevel(), owned, player, ModBlocks.blockHutHome, pos, 0,
                "fundamentals/residence");
        if (residence == null) {
            PlaytestMod.LOGGER.warn("TC_BUILD the residence hut did not register");
            return false;
        }
        residence.requestUpgrade(player, builderHut.getPosition());
        colony = owned;
        site = pos;
        lastState = "";
        PlaytestMod.LOGGER.info("TC_BUILD ordered a residence at {} from the builder's hut at {}", pos.toShortString(),
                builderHut.getPosition().toShortString());
        return true;
    }

    /** Teleports the player next to the colony's builder. */
    static boolean gotoBuilder(ServerPlayer player) {
        IColony owned = PlaytestColony.find(player);
        AbstractBuildingStructureBuilder builderHut = owned == null ? null : builderHut(owned);
        ICitizenData builder = builderHut == null ? null : builderHut.getAllAssignedCitizen().stream().findFirst().orElse(null);
        Entity entity = builder == null ? null : builder.getEntity().orElse(null);
        if (entity == null) {
            PlaytestMod.LOGGER.warn("TC_GOTO no builder to go to");
            return false;
        }
        player.teleportTo(entity.getX() + 1.5, entity.getY(), entity.getZ() + 1.5);
        PlaytestMod.LOGGER.info("TC_GOTO next to the builder {}", builder.getName());
        return true;
    }

    /** Teleports the player next to an awake citizen who is not the builder, nearest to the colony center. */
    static boolean gotoCitizen(ServerPlayer player) {
        IColony owned = PlaytestColony.find(player);
        if (owned == null) return false;
        AbstractBuildingStructureBuilder builderHut = builderHut(owned);
        BlockPos center = owned.getCenter();
        AbstractEntityCitizen citizen = owned.getCitizenManager().getCitizens().stream()
                .filter(data -> builderHut == null || !builderHut.getAllAssignedCitizen().contains(data))
                .map(data -> data.getEntity().orElse(null))
                .filter(entity -> entity != null && entity.isAlive() && !entity.isSleeping())
                .min(Comparator.comparingDouble(entity -> entity.blockPosition().distSqr(center)))
                .orElse(null);
        if (citizen == null) {
            PlaytestMod.LOGGER.warn("TC_GOTO no citizen to go to");
            return false;
        }
        player.teleportTo(citizen.getX() + 1.5, citizen.getY(), citizen.getZ() + 1.5);
        PlaytestMod.LOGGER.info("TC_GOTO next to {}", citizen.getCitizenData().getName());
        return true;
    }

    static void tick() {
        if (colony == null || site == null || ++ticks % CHECK_TICKS != 0) return;
        String state = state(colony, site);
        if (!state.equals(lastState)) {
            lastState = state;
            PlaytestMod.LOGGER.info("TC_BUILD {}", state);
        }
    }

    private static String state(IColony colony, BlockPos site) {
        IServerWorkOrder order = colony.getWorkManager().getWorkOrders().values().stream()
                .filter(candidate -> candidate.getLocation().equals(site)).findFirst().orElse(null);
        if (order == null) {
            IBuilding building = colony.getServerBuildingManager().getBuilding(site);
            return "no order; residence level " + (building == null ? "gone" : building.getBuildingLevel());
        }
        if (!order.isClaimed()) return "waiting for a builder";
        AbstractBuildingStructureBuilder hut = colony.getServerBuildingManager().getBuilding(order.getClaimedBy())
                instanceof AbstractBuildingStructureBuilder builderHut ? builderHut : null;
        ICitizenData builder = hut == null ? null : hut.getAllAssignedCitizen().stream().findFirst().orElse(null);
        String name = builder == null ? "nobody" : builder.getName();
        IBuilderWorkOrder current = hut == null ? null : hut.getWorkOrder();
        if (current == null || current.getID() != order.getID()) return "claimed by " + name + ", not started";
        long needed = 0;
        for (ItemStorage resource : hut.getNeededResources().values()) needed += resource.getAmount();
        return "claimed by " + name + ", stage " + Objects.toString(current.getStage(), "none") + ", materials still needed "
                + needed + " of " + current.getAmountOfResources() + ", open requests: " + requests(hut, builder);
    }

    /** What the builder asked for, e.g. a tool before clearing the site; "none" if nothing. */
    private static String requests(IBuilding hut, @Nullable ICitizenData builder) {
        if (builder == null) return "none";
        List<String> asked = new ArrayList<>();
        for (IRequest<?> request : hut.getOpenRequests(builder.getId())) {
            asked.add(request.getShortDisplayString().getString());
        }
        return asked.isEmpty() ? "none" : String.join("; ", asked);
    }

    private static @Nullable AbstractBuildingStructureBuilder builderHut(IColony colony) {
        return colony.getServerBuildingManager().getBuildings().values().stream()
                .filter(building -> building instanceof AbstractBuildingStructureBuilder)
                .map(building -> (AbstractBuildingStructureBuilder) building)
                .filter(building -> !building.getAllAssignedCitizen().isEmpty())
                .findFirst().orElse(null);
    }
}
