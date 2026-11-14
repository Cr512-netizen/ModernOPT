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
        try {
            Class.forName("net.irisshaders.iris.Iris", false,
                    ModernOptIrisMixinPlugin.class.getClassLoader());
            irisPresent = true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            irisPresent = false;
        }
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
