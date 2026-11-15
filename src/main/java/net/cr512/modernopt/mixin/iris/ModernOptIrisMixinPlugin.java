package net.cr512.modernopt.mixin.iris;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the Iris profiling mixins only when Iris is installed.
 *
 * <p>The mixins in this config reference Iris classes directly, so they are kept in their own
 * config file and are skipped entirely on a client without Iris.</p>
 */
public final class ModernOptIrisMixinPlugin implements IMixinConfigPlugin {

    private static boolean irisPresent;

    @Override
    public void onLoad(String mixinPackage) {
        // Do NOT Class.forName here: loading net.irisshaders.iris.Iris at this point makes other
        // mods' Iris mixins fail with MixinTargetAlreadyLoadedException (RenderScale hits exactly
        // that). Looking the class file up as a resource is enough to know whether Iris exists.
        irisPresent = ModernOptIrisMixinPlugin.class.getClassLoader()
                .getResource("net/irisshaders/iris/Iris.class") != null;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return irisPresent;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
