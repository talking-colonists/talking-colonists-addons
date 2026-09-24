package me.sshcrack.tc_townhall.block;

import me.sshcrack.tc_townhall.TownHall;
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

/** The Town Hall addon's blocks, their items and block entities. */
public final class TownHallBlocks {
    /*? if neoforge {*/
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, TownHall.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, TownHall.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, TownHall.MOD_ID);
    /*?}*/
    /*? if forge {*/
    /*private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, TownHall.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, TownHall.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, TownHall.MOD_ID);
    *//*?}*/

    public static final Supplier<SuggestionBoxBlock> SUGGESTION_BOX = BLOCKS.register("suggestion_box",
            () -> new SuggestionBoxBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0f).sound(SoundType.WOOD).noOcclusion()));
    public static final Supplier<Item> SUGGESTION_BOX_ITEM = ITEMS.register("suggestion_box",
            () -> new BlockItem(SUGGESTION_BOX.get(), new Item.Properties()));
    public static final Supplier<BlockEntityType<SuggestionBoxBlockEntity>> SUGGESTION_BOX_ENTITY = BLOCK_ENTITIES.register("suggestion_box",
            () -> BlockEntityType.Builder.of(SuggestionBoxBlockEntity::new, SUGGESTION_BOX.get()).build(null));

    private TownHallBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(SUGGESTION_BOX_ITEM.get());
        });
    }
}
