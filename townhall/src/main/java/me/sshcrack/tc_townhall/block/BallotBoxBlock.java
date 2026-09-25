package me.sshcrack.tc_townhall.block;

import me.sshcrack.tc_townhall.BallotView;
import me.sshcrack.tc_townhall.client.TownHallScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
/*? if forge {*/
/*import net.minecraft.world.InteractionHand;
*//*?}*/
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The colony's ballot box. Right-clicking opens the election window: stand for mayor, give a speech,
 * the candidates and, while citizens vote, the live tally. The model shows the phase: a poster during
 * the campaign, a flag while voting is open.
 */
public class BallotBoxBlock extends Block implements EntityBlock {
    public enum Phase implements StringRepresentable {
        IDLE, CAMPAIGN, VOTING;

        public static Phase of(BallotView.Phase phase) {
            return values()[phase.ordinal()];
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Phase> PHASE = EnumProperty.create("phase", Phase.class);
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 13, 14);

    public BallotBoxBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PHASE, Phase.IDLE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PHASE);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BallotBoxBlockEntity(pos, state);
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
        // The window asks the server for the election as soon as it opens.
        if (level.isClientSide) TownHallScreens.openBallotBox(pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
