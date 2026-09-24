package me.sshcrack.tc_townhall.block;

import me.sshcrack.tc_townhall.client.TownHallScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
/*? if forge {*/
/*import net.minecraft.world.InteractionHand;
*//*?}*/
import org.jetbrains.annotations.Nullable;

/**
 * The colony's suggestion box: a small wooden box with a slot. Citizens drop short notes in it each
 * morning; right-clicking opens a window with the notes. A note sticks out of the slot while any wait.
 */
public class SuggestionBoxBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty HAS_NOTES = BooleanProperty.create("has_notes");
    private static final VoxelShape SHAPE_NORTH_SOUTH = Block.box(1, 0, 2, 15, 12, 14);
    private static final VoxelShape SHAPE_EAST_WEST = Block.box(2, 0, 1, 14, 12, 15);

    public SuggestionBoxBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HAS_NOTES, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HAS_NOTES);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? SHAPE_NORTH_SOUTH : SHAPE_EAST_WEST;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SuggestionBoxBlockEntity(pos, state);
    }

    /*? if neoforge {*/
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return open(level, pos);
    }
    /*?}*/

    /*? if forge {*/
    /*@Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return open(level, pos);
    }
    *//*?}*/

    private static InteractionResult open(Level level, BlockPos pos) {
        // The window lives on the client; the notes are already synced there.
        if (level.isClientSide) TownHallScreens.openSuggestionBox(pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
