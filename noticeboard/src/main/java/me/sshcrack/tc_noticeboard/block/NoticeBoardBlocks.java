package me.sshcrack.tc_noticeboard.block;

import me.sshcrack.tc_noticeboard.NoticeBoard;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
/*? if neoforge {*/
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
/*?}*/
/*? if forge {*/
/*import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
*//*?}*/

import java.util.function.Supplier;

/** The Notice Board addon's blocks, their items and block entities. */
public final class NoticeBoardBlocks {
    /*? if neoforge {*/
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, NoticeBoard.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, NoticeBoard.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, NoticeBoard.MOD_ID);
    /*?}*/
    /*? if forge {*/
    /*private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, NoticeBoard.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, NoticeBoard.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, NoticeBoard.MOD_ID);
    *//*?}*/

    public static final Supplier<NoticeBoardBlock> NOTICE_BOARD = BLOCKS.register("notice_board",
            () -> new NoticeBoardBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0f).sound(SoundType.WOOD).noOcclusion()));
    public static final Supplier<Item> NOTICE_BOARD_ITEM = ITEMS.register("notice_board",
            () -> new BlockItem(NOTICE_BOARD.get(), new Item.Properties()));
    public static final Supplier<BlockEntityType<NoticeBoardBlockEntity>> NOTICE_BOARD_ENTITY = BLOCK_ENTITIES.register("notice_board",
            () -> BlockEntityType.Builder.of(NoticeBoardBlockEntity::new, NOTICE_BOARD.get()).build(null));

    private NoticeBoardBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() != CreativeModeTabs.FUNCTIONAL_BLOCKS) return;
            event.accept(NOTICE_BOARD_ITEM.get());
        });
    }
}
