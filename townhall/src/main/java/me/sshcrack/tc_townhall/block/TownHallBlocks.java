package me.sshcrack.tc_townhall.block;

import me.sshcrack.tc_townhall.TownHall;
import me.sshcrack.tc_townhall.item.MayorHatItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.sounds.SoundEvents;
/*? if neoforge {*/
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.EnumMap;
import java.util.List;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
/*?}*/
/*? if forge {*/
/*import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
*//*?}*/

import java.util.function.Supplier;

/** The Town Hall addon's blocks, their items and block entities, and the mayor's hat. */
public final class TownHallBlocks {
    /*? if neoforge {*/
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, TownHall.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, TownHall.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, TownHall.MOD_ID);
    private static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS = DeferredRegister.create(Registries.ARMOR_MATERIAL, TownHall.MOD_ID);

    /** No protection at all; the hat is drawn from its item model, not from these layers. */
    private static final DeferredHolder<ArmorMaterial, ArmorMaterial> MAYOR = ARMOR_MATERIALS.register("mayor", () -> {
        EnumMap<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
        for (ArmorItem.Type type : ArmorItem.Type.values()) defense.put(type, 0);
        return new ArmorMaterial(defense, 0, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.EMPTY,
                List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(TownHall.MOD_ID, "mayor"))), 0.0F, 0.0F);
    });
    /*?}*/
    /*? if forge {*/
    /*private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, TownHall.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, TownHall.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, TownHall.MOD_ID);

    /^* No protection at all; the hat is drawn from its item model, not from an armor texture. *^/
    private static final ArmorMaterial MAYOR = new ArmorMaterial() {
        @Override
        public int getDurabilityForType(ArmorItem.Type type) {
            return 0;
        }

        @Override
        public int getDefenseForType(ArmorItem.Type type) {
            return 0;
        }

        @Override
        public int getEnchantmentValue() {
            return 0;
        }

        @Override
        public SoundEvent getEquipSound() {
            return SoundEvents.ARMOR_EQUIP_LEATHER;
        }

        @Override
        public Ingredient getRepairIngredient() {
            return Ingredient.EMPTY;
        }

        @Override
        public String getName() {
            return TownHall.MOD_ID + ":mayor";
        }

        @Override
        public float getToughness() {
            return 0.0F;
        }

        @Override
        public float getKnockbackResistance() {
            return 0.0F;
        }
    };
    *//*?}*/

    public static final Supplier<SuggestionBoxBlock> SUGGESTION_BOX = BLOCKS.register("suggestion_box",
            () -> new SuggestionBoxBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0f).sound(SoundType.WOOD).noOcclusion()));
    public static final Supplier<Item> SUGGESTION_BOX_ITEM = ITEMS.register("suggestion_box",
            () -> new BlockItem(SUGGESTION_BOX.get(), new Item.Properties()));
    public static final Supplier<BlockEntityType<SuggestionBoxBlockEntity>> SUGGESTION_BOX_ENTITY = BLOCK_ENTITIES.register("suggestion_box",
            () -> BlockEntityType.Builder.of(SuggestionBoxBlockEntity::new, SUGGESTION_BOX.get()).build(null));

    public static final Supplier<BallotBoxBlock> BALLOT_BOX = BLOCKS.register("ballot_box",
            () -> new BallotBoxBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD).noOcclusion()));
    public static final Supplier<Item> BALLOT_BOX_ITEM = ITEMS.register("ballot_box",
            () -> new BlockItem(BALLOT_BOX.get(), new Item.Properties()));
    public static final Supplier<BlockEntityType<BallotBoxBlockEntity>> BALLOT_BOX_ENTITY = BLOCK_ENTITIES.register("ballot_box",
            () -> BlockEntityType.Builder.of(BallotBoxBlockEntity::new, BALLOT_BOX.get()).build(null));

    public static final Supplier<MayorHatItem> MAYOR_HAT = ITEMS.register("mayor_hat",
            () -> new MayorHatItem(MAYOR, new Item.Properties().stacksTo(1)));

    private TownHallBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        /*? if neoforge {*/
        ARMOR_MATERIALS.register(modBus);
        /*?}*/
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() != CreativeModeTabs.FUNCTIONAL_BLOCKS) return;
            event.accept(SUGGESTION_BOX_ITEM.get());
            event.accept(BALLOT_BOX_ITEM.get());
        });
    }
}
