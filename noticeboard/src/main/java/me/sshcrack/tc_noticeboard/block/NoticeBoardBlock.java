package me.sshcrack.tc_noticeboard.block;

import me.sshcrack.tc_noticeboard.client.NoticeBoardScreens;
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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
/*? if forge {*/
/*import net.minecraft.world.InteractionHand;
*//*?}*/
import org.jetbrains.annotations.Nullable;

/**
 * The colony's notice board: a cork board on two posts. Right-clicking opens a window to post a notice,
 * read the replies citizens pin under it, or announce something to the whole colony. The sheets pinned
 * on the block show how much is on it.
 */
public class NoticeBoardBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** 0: empty, 1: a notice, 2: a notice with replies. */
    public static final IntegerProperty SHEETS = IntegerProperty.create("sheets", 0, 2);
    private static final VoxelShape SHAPE_NORTH_SOUTH = Block.box(0, 0, 6, 16, 16, 10);
    private static final VoxelShape SHAPE_EAST_WEST = Block.box(6, 0, 0, 10, 16, 16);

    public NoticeBoardBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SHEETS, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SHEETS);
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
        return new NoticeBoardBlockEntity(pos, state);
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
        // The window lives on the client; what is pinned is already synced there.
        if (level.isClientSide) NoticeBoardScreens.openNoticeBoard(pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
