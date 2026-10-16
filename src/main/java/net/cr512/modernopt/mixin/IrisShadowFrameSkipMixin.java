package net.cr512.modernopt.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Intentionally inert compatibility placeholder.
 *
 * Earlier versions cancelled Iris' entire renderShadows() call to throttle updates.
 * Iris may clear or rebind its shadow targets as part of that call, so cancelling it
 * can leave stale/empty shadow maps and make shadows pop in and out. Until an Iris
 * integration can preserve the shadow target lifecycle through a supported API, we
 * must never cancel this pass. The related UI setting is retained for config
 * compatibility but currently has no effect.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public abstract class IrisShadowFrameSkipMixin {
}
