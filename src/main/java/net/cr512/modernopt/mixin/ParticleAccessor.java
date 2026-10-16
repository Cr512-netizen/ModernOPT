package net.cr512.modernopt.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accesses the particle position fields declared on Particle.
 * TextureSheetParticle inherits these fields, so they cannot be shadowed directly
 * from a mixin targeting TextureSheetParticle.
 */
@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("x")
    double modernopt$getX();

    @Accessor("y")
    double modernopt$getY();

    @Accessor("z")
    double modernopt$getZ();
}
