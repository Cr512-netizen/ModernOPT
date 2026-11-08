package net.cr512.modernopt.mixin;

import net.cr512.modernopt.render.MotionVectorCollector;
import net.cr512.modernopt.render.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records entity motion vectors for MSR 2.0 temporal reprojection.
 * Hooks into entity rendering to capture screen-space positions.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(
        method = "render",
        at = @At("HEAD")
    )
    private void modernopt$recordEntityMotion(Entity entity, double x, double y, double z, float yaw, float pitch, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource multiBufferSource, int packedLight, CallbackInfo ci) {
        MotionVectorCollector collector = RenderPipeline.getMotionVectorCollector();
        if (collector == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) return;

        // Use the already-captured matrices from RenderPipeline
        Matrix4f projection = RenderPipeline.getCurrentProjection();
        Matrix4f view = RenderPipeline.getCurrentView();
        if (projection == null || view == null) return;

        // Project entity world position to clip space
        float wx = (float) entity.getX();
        float wy = (float) entity.getY();
        float wz = (float) entity.getZ();

        // Transform to clip space using Vector4f
        Vector4f clipPos = new Vector4f(wx, wy, wz, 1.0f);
        clipPos.mul(view);
        clipPos.mul(projection);

        // Convert to NDC (-1 to 1)
        if (clipPos.w <= 0) return;

        float ndcX = clipPos.x / clipPos.w;
        float ndcY = clipPos.y / clipPos.w;

        // Convert to UV (0 to 1)
        float screenX = (ndcX + 1.0f) * 0.5f;
        float screenY = (1.0f - ndcY) * 0.5f; // Flip Y for UV space

        // Clamp to valid range
        screenX = Math.max(0.0f, Math.min(1.0f, screenX));
        screenY = Math.max(0.0f, Math.min(1.0f, screenY));

        // Only record if entity is in view
        if (screenX >= 0 && screenX <= 1 && screenY >= 0 && screenY <= 1) {
            collector.recordMotion(entity.getId(), screenX, screenY);
        }
    }
}