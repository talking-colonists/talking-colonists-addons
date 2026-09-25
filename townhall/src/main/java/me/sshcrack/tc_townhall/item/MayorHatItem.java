package me.sshcrack.tc_townhall.item;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
/*? if neoforge {*/
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
/*?}*/
/*? if forge {*/
/*import me.sshcrack.tc_townhall.client.MayorHatModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;
*//*?}*/

/**
 * The mayor's hat: the elected mayor wears it, a colonist on their head, a player when a citizen brings
 * it to them after the election. It gives no protection. It is drawn with its item model (a top hat), so
 * its "armor texture" is the block atlas that model's textures live on.
 */
public final class MayorHatItem extends ArmorItem {
    /** The block atlas (TextureAtlas.LOCATION_BLOCKS, a client class). */
    private static final String BLOCK_ATLAS = "minecraft:textures/atlas/blocks.png";

    /*? if neoforge {*/
    public MayorHatItem(Holder<ArmorMaterial> material, Properties properties) {
        super(material, Type.HELMET, properties);
    }

    @Override
    public @Nullable ResourceLocation getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, ArmorMaterial.Layer layer,
                                                      boolean innerModel) {
        return ResourceLocation.parse(BLOCK_ATLAS);
    }
    /*?}*/
    /*? if forge {*/
    /*public MayorHatItem(ArmorMaterial material, Properties properties) {
        super(material, Type.HELMET, properties);
    }

    @Override
    public @Nullable String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        return BLOCK_ATLAS;
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public HumanoidModel<?> getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot,
                                                          HumanoidModel<?> original) {
                return MayorHatModel.forStack(stack);
            }
        });
    }
    *//*?}*/
}
