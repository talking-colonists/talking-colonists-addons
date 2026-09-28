package me.sshcrack.tc_townhall.block;

import me.sshcrack.tc_townhall.client.TownHallScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
/*? if forge {*/
/*import net.minecraft.world.InteractionHand;
*//*?}*/
import org.jetbrains.annotations.Nullable;

/**
 * The colony's suggestion box: a wooden letterbox on a post, its slot at eye height. Citizens drop short
 * notes in it each morning; right-clicking opens a window with the notes. A note sticks out of the slot
 * while any wait. It stands two blocks tall like a door: the lower half holds the block entity and draws
 * the whole model, the upper half only takes the space and the clicks.
 */
public class SuggestionBoxBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty HAS_NOTES = BooleanProperty.create("has_notes");
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    /** The foot and the post. */
    private static final VoxelShape LOWER = Shapes.or(Block.box(4, 0, 4, 12, 2, 12), Block.box(6.5, 2, 6.5, 9.5, 16, 9.5));
    /** The box with its roof, at eye height (the model's y 16–31). */
    private static final VoxelShape UPPER_NORTH_SOUTH = Shapes.or(Block.box(6.5, 0, 6.5, 9.5, 1, 9.5), Block.box(1, 0, 2, 15, 15, 14));
    private static final VoxelShape UPPER_EAST_WEST = Shapes.or(Block.box(6.5, 0, 6.5, 9.5, 1, 9.5), Block.box(2, 0, 1, 14, 15, 15));

    public SuggestionBoxBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HAS_NOTES, false)
                .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HAS_NOTES, HALF);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos above = context.getClickedPos().above();
        Level level = context.getLevel();
        if (above.getY() >= level.getMaxBuildHeight() || !level.getBlockState(above).canBeReplaced(context)) return null;
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER).setValue(HAS_NOTES, false), 3);
    }

    /** A lower half set without the item (e.g. by a command) gets its upper half too, where there is room. */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (state.getValue(HALF) != DoubleBlockHalf.LOWER || level.isClientSide) return;
        BlockState above = level.getBlockState(pos.above());
        if (!above.is(this) && above.canBeReplaced()) {
            level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER).setValue(HAS_NOTES, false), 3);
        }
    }

    /** Without its other half a half goes too, as a door's does. */
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos,
                           BlockPos neighborPos) {
        DoubleBlockHalf half = state.getValue(HALF);
        if (direction.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)) {
            return neighbor.is(this) && neighbor.getValue(HALF) != half ? state : Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    /*? if neoforge {*/
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        breakLowerFromAbove(level, pos, state, player);
        return super.playerWillDestroy(level, pos, state, player);
    }
    /*?}*/

    /*? if forge {*/
    /*@Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        breakLowerFromAbove(level, pos, state, player);
        super.playerWillDestroy(level, pos, state, player);
    }
    *//*?}*/

    /**
     * Breaking the upper half breaks the box: the lower half (which alone drops the item, see the loot
     * table) drops it unless in creative, and goes without a second drop.
     */
    private static void breakLowerFromAbove(Level level, BlockPos pos, BlockState state, Player player) {
        if (level.isClientSide || state.getValue(HALF) != DoubleBlockHalf.UPPER) return;
        BlockPos below = pos.below();
        BlockState lower = level.getBlockState(below);
        if (!lower.is(state.getBlock()) || lower.getValue(HALF) != DoubleBlockHalf.LOWER) return;
        if (!player.isCreative()) {
            dropResources(lower, level, below, level.getBlockEntity(below), player, player.getMainHandItem());
        }
        level.setBlock(below, Blocks.AIR.defaultBlockState(), 35);
        level.levelEvent(player, 2001, below, Block.getId(lower));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) return LOWER;
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? UPPER_NORTH_SOUTH : UPPER_EAST_WEST;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? new SuggestionBoxBlockEntity(pos, state) : null;
    }

    /*? if neoforge {*/
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return open(level, lowerHalf(state, pos));
    }
    /*?}*/

    /*? if forge {*/
    /*@Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return open(level, lowerHalf(state, pos));
    }
    *//*?}*/

    /** Where the block entity is: the lower half. */
    public static BlockPos lowerHalf(BlockState state, BlockPos pos) {
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    private static InteractionResult open(Level level, BlockPos pos) {
        // The window lives on the client; the notes are already synced there.
        if (level.isClientSide) TownHallScreens.openSuggestionBox(pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
