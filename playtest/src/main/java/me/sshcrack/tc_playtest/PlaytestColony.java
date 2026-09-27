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
import net.minecraft.world.level.GameType;
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
    static final String STYLE = "Colonial";

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
        IColony colony = IColonyManager.getInstance().createColony(level, center, player, NAME, STYLE);
        if (colony == null) {
            player.sendSystemMessage(Component.literal("[Playtest] MineColonies refused to create a colony here."));
            return false;
        }
        // Finished buildings on a grid south of the plaza (the colony's center), pasted like the build
        // tool's instant placement in creative: beds to sleep in, rooms to work in.
        IBuilding townHall = built(level, colony, player, ModBlocks.blockHutTownHall, center.offset(-45, 0, 22), 1, "fundamentals/townhall");
        IBuilding school = built(level, colony, player, ModBlocks.blockHutSchool, center.offset(-15, 0, 22), 1, "education/school");
        IBuilding library = built(level, colony, player, ModBlocks.blockHutLibrary, center.offset(15, 0, 22), 1, "education/library");
        IBuilding tavern = built(level, colony, player, ModBlocks.blockHutTavern, center.offset(45, 0, 22), 1, "fundamentals/tavern");
        // A working builder, so placed huts actually get built (a level 0 builder's hut has no builder).
        IBuilding builder = built(level, colony, player, ModBlocks.blockHutBuilder, center.offset(-30, 0, 52), 1, "fundamentals/builder");
        List<IBuilding> homes = List.of(
                built(level, colony, player, ModBlocks.blockHutHome, center.offset(0, 0, 52), 3, "fundamentals/residence"),
                built(level, colony, player, ModBlocks.blockHutHome, center.offset(30, 0, 52), 3, "fundamentals/residence"));
        level.setBlock(ground(level, center.offset(0, 0, -6)), Blocks.CAMPFIRE.defaultBlockState(), 3);

        for (int i = 0; i < CITIZENS; i++) {
            ICitizenData data = colony.getCitizenManager().createAndRegisterCivilianData();
            colony.getCitizenManager().spawnOrCreateCivilian(data, level, List.of(center.offset(2 + i % 3, 1, 2 + i / 3)), true);
            IBuilding home = homes.get(i % homes.size());
            if (home != null) home.getModule(BuildingModules.LIVING).assignCitizen(data);
            if (i == 0 && school != null) school.getModule(BuildingModules.TEACHER_WORK).assignCitizen(data);
            if (i == 1 && library != null) library.getModule(BuildingModules.STUDENT_WORK).assignCitizen(data);
            if (i == 2 && builder != null) builder.getModule(BuildingModules.BUILDER_WORK).assignCitizen(data);
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

    static BlockPos ground(ServerLevel level, BlockPos pos) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);
    }

    /**
     * The finished building at {@code pos} (its hut there), pasted with Structurize's paste command as a
     * creative player would, then placed at once instead of over the next minutes. Falls back to a bare
     * hut when the paste does not produce the building.
     */
    private static @Nullable IBuilding built(ServerLevel level, IColony colony, ServerPlayer player, AbstractBlockHut<?> hut,
                                             BlockPos pos, int buildingLevel, String blueprint) {
        BlockPos anchor = ground(level, pos);
        String path = blueprint + buildingLevel + ".blueprint";
        GameType mode = player.gameMode.getGameModeForPlayer();
        player.setGameMode(GameType.CREATIVE);
        try {
            level.getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4),
                    "structurize paste %d %d %d \"%s\" \"%s\"".formatted(anchor.getX(), anchor.getY(), anchor.getZ(), STYLE,
                            // The command wants dots for folders and adds ".blueprint" itself.
                            (blueprint + buildingLevel).replace('/', '.')));
        } finally {
            player.setGameMode(mode);
        }
        int ticks = PlaytestPaste.finishQueued(level);
        IBuilding building = IColonyManager.getInstance().getBuilding(level, anchor);
        if (building == null || level.getBlockState(anchor).getBlock() != hut) {
            PlaytestMod.LOGGER.warn("TC_COLONY pasting {} did not make the building; placing a bare hut", path);
            return hut(level, colony, player, hut, anchor, buildingLevel, blueprint);
        }
        PlaytestMod.LOGGER.info("TC_COLONY pasted {} at {} ({} ticks)", path, anchor.toShortString(), ticks);
        building.setStructurePack(STYLE);
        building.setBlueprintPath(path);
        building.setBuildingLevel(buildingLevel);
        return building;
    }

    /**
     * Places a hut the way a player would and raises it to {@code buildingLevel}. {@code blueprint} is its
     * blueprint in the colony's style without the level, e.g. "fundamentals/residence": without it the
     * building has no blueprint, so builders cannot build or upgrade it (and Structurize logs errors).
     */
    static @Nullable IBuilding hut(ServerLevel level, IColony colony, ServerPlayer player,
                                   AbstractBlockHut<?> hut, BlockPos pos, int buildingLevel, String blueprint) {
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
        building.setStructurePack(STYLE);
        building.setBlueprintPath(blueprint + Math.max(1, buildingLevel) + ".blueprint");
        building.setBuildingLevel(buildingLevel);
        return building;
    }
}
