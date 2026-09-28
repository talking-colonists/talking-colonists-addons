package me.sshcrack.tc_townhall.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
/*? if neoforge {*/
import net.neoforged.neoforge.client.ClientHooks;
/*?}*/
/*? if forge {*/
/*import net.minecraftforge.client.ForgeHooksClient;
*//*?}*/

/**
 * The mayor's hat on a head: an "armor model" that draws the hat's item model, with its {@code head}
 * display transform, where a worn carved pumpkin would sit. Armor layers of players, armor stands and
 * MineColonies citizens all ask the item for this model, and bind the block atlas the model's textures
 * live on (see {@code MayorHatItem#getArmorTexture}).
 */
public final class MayorHatModel extends HumanoidModel<LivingEntity> {
    private static @Nullable MayorHatModel instance;
    private ItemStack stack = ItemStack.EMPTY;

    private MayorHatModel() {
        super(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
    }

    /** The shared model, set to draw {@code stack}; armor is rendered on the render thread, one piece at a time. */
    public static MayorHatModel forStack(ItemStack stack) {
        if (instance == null) instance = new MayorHatModel();
        instance.stack = stack;
        return instance;
    }

    /*? if neoforge {*/
    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int light, int overlay, int color) {
        render(poseStack, buffer, light, overlay);
    }
    /*?}*/
    /*? if forge {*/
    /*@Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int light, int overlay, float red, float green,
                               float blue, float alpha) {
        render(poseStack, buffer, light, overlay);
    }
    *//*?}*/

    private void render(PoseStack poseStack, VertexConsumer buffer, int light, int overlay) {
        if (!head.visible || stack.isEmpty()) return;
        BakedModel model = Minecraft.getInstance().getItemRenderer().getModel(stack, null, null, 0);
        poseStack.pushPose();
        // As vanilla's CustomHeadLayer places items worn on the head.
        head.translateAndRotate(poseStack);
        poseStack.translate(0.0F, -0.25F, 0.0F);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.scale(0.625F, -0.625F, -0.625F);
        /*? if neoforge {*/
        model = ClientHooks.handleCameraTransforms(poseStack, model, ItemDisplayContext.HEAD, false);
        /*?}*/
        /*? if forge {*/
        /*model = ForgeHooksClient.handleCameraTransforms(poseStack, model, ItemDisplayContext.HEAD, false);
        *//*?}*/
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        RandomSource random = RandomSource.create(42L);
        for (Direction direction : Direction.values()) {
            quads(poseStack, buffer, model.getQuads(null, direction, random), light, overlay);
        }
        quads(poseStack, buffer, model.getQuads(null, null, random), light, overlay);
        poseStack.popPose();
    }

    private static void quads(PoseStack poseStack, VertexConsumer buffer, Iterable<BakedQuad> quads, int light, int overlay) {
        PoseStack.Pose pose = poseStack.last();
        for (BakedQuad quad : quads) {
            /*? if neoforge {*/
            buffer.putBulkData(pose, quad, 1.0F, 1.0F, 1.0F, 1.0F, light, overlay);
            /*?}*/
            /*? if forge {*/
            /*buffer.putBulkData(pose, quad, 1.0F, 1.0F, 1.0F, light, overlay);
            *//*?}*/
        }
    }
}
