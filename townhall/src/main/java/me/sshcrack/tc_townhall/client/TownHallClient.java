package me.sshcrack.tc_townhall.client;

/*? if neoforge {*/
import me.sshcrack.tc_townhall.block.TownHallBlocks;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
/*?}*/

/** Client-only registrations. On Forge the hat registers its model itself ({@code MayorHatItem#initializeClient}). */
public final class TownHallClient {
    private TownHallClient() {
    }

    /*? if neoforge {*/
    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterClientExtensionsEvent event) -> event.registerItem(new IClientItemExtensions() {
            @Override
            public HumanoidModel<?> getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot,
                                                          HumanoidModel<?> original) {
                return MayorHatModel.forStack(stack);
            }
        }, TownHallBlocks.MAYOR_HAT.get()));
    }
    /*?}*/
}
