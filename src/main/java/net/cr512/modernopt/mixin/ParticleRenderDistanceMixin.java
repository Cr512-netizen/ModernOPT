package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.cr512.modernopt.config.ModernOptConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional draw-distance culling only: particles keep ticking and are not removed,
 * so enabling this cannot change gameplay or particle simulation.
 */
@Mixin(TextureSheetParticle.class)
public abstract class ParticleRenderDistanceMixin {
    @Shadow protected double x;
    @Shadow protected double y;
    @Shadow protected double z;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void modernopt$cullDistantParticle(VertexConsumer consumer, Camera camera, float partialTick, CallbackInfo ci) {
        if (!ModernOptConfig.ENABLED.get() || !ModernOptConfig.PARTICLE_CULLING_ENABLED.get()) return;
        Vec3 cameraPos = camera.getPosition();
        double distance = ModernOptConfig.PARTICLE_RENDER_DISTANCE.get();
        if (cameraPos.distanceToSqr(this.x, this.y, this.z) > distance * distance) ci.cancel();
    }
}
