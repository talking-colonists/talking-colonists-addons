package me.sshcrack.tc_playtest;

import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Builds the playtest colony the first time a player joins: town hall, school with a teacher, library
 * with a student, tavern with a visitor, two houses, six citizens and a campfire. The huts are bare
 * blocks (no structures), which is all MineColonies needs to register the buildings.
 */
public final class PlaytestColony {
    public static final String NAME = "Playtest Hollow";
    private static final int CITIZENS = 6;

    private PlaytestColony() {
    }

    /** The player's playtest colony, building it first if there is none. Returns false if MineColonies refused. */
    static boolean ensure(ServerPlayer player) {
        if (find(player) != null) return true;
        ServerLevel level = player.serverLevel();
        var server = level.getServer();
        // A calm test bed: no hostile mobs wandering in, no rain.
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);

        BlockPos center = ground(level, player.blockPosition().offset(8, 0, 0));
        IColony colony = IColonyManager.getInstance().createColony(level, center, player, NAME, "Colonial");
        if (colony == null) {
            player.sendSystemMessage(Component.literal("[Playtest] MineColonies refused to create a colony here."));
            return false;
        }
        IBuilding townHall = hut(level, colony, player, ModBlocks.blockHutTownHall, center, 1);
        IBuilding school = hut(level, colony, player, ModBlocks.blockHutSchool, ground(level, center.offset(10, 0, 0)), 1);
        IBuilding library = hut(level, colony, player, ModBlocks.blockHutLibrary, ground(level, center.offset(10, 0, 10)), 1);
        IBuilding tavern = hut(level, colony, player, ModBlocks.blockHutTavern, ground(level, center.offset(-10, 0, 0)), 1);
        List<IBuilding> homes = List.of(
                hut(level, colony, player, ModBlocks.blockHutHome, ground(level, center.offset(0, 0, 10)), 3),
                hut(level, colony, player, ModBlocks.blockHutHome, ground(level, center.offset(-10, 0, 10)), 3));
        level.setBlock(ground(level, center.offset(0, 0, -6)), Blocks.CAMPFIRE.defaultBlockState(), 3);

        for (int i = 0; i < CITIZENS; i++) {
            ICitizenData data = colony.getCitizenManager().createAndRegisterCivilianData();
            colony.getCitizenManager().spawnOrCreateCivilian(data, level, List.of(center.offset(2 + i % 3, 1, 2 + i / 3)), true);
            IBuilding home = homes.get(i % homes.size());
            if (home != null) home.getModule(BuildingModules.LIVING).assignCitizen(data);
            if (i == 0 && school != null) school.getModule(BuildingModules.TEACHER_WORK).assignCitizen(data);
            if (i == 1 && library != null) library.getModule(BuildingModules.STUDENT_WORK).assignCitizen(data);
        }
        if (tavern != null) tavern.getModule(BuildingModules.TAVERN_VISITOR).spawnVisitor();

        player.getInventory().add(new ItemStack(Items.WRITABLE_BOOK));
        PlaytestMod.LOGGER.info("Built the playtest colony {} (town hall {})", colony.getID(), townHall != null);
        return true;
    }

    /** The colony the player owns in their current dimension, or null. */
    static @Nullable IColony find(ServerPlayer player) {
        return IColonyManager.getInstance().getIColonyByOwner(player.serverLevel(), player);
    }

    private static BlockPos ground(ServerLevel level, BlockPos pos) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);
    }

    /** Places a hut the way a player would and raises it to {@code buildingLevel}. */
    private static @Nullable IBuilding hut(ServerLevel level, IColony colony, ServerPlayer player,
                                           AbstractBlockHut<?> hut, BlockPos pos, int buildingLevel) {
        BlockState state = hut.defaultBlockState();
        level.setBlock(pos, state, 3);
        // An empty stack: a hut item would make Structurize look for a blueprint we do not place.
        hut.setPlacedBy(level, pos, state, player, ItemStack.EMPTY);
        IBuilding building = IColonyManager.getInstance().getBuilding(level, pos);
        if (building == null && level.getBlockEntity(pos) instanceof AbstractTileEntityColonyBuilding tile) {
            building = colony.getServerBuildingManager().addNewBuilding(tile, level);
        }
        if (building == null) {
            PlaytestMod.LOGGER.warn("{} at {} did not register a building", hut, pos);
            return null;
        }
        building.setBuildingLevel(buildingLevel);
        return building;
    }
}
