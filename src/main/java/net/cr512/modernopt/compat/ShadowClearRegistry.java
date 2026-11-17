package net.cr512.modernopt.compat;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Identity registry for the shader pipeline's shadow clear passes.
 *
 * <p>Mixin classes may not expose non-private static helpers, so the shared state lives in this
 * plain class: the creator mixin tags the passes, the clear-pass mixin asks whether a pass has to
 * be skipped while the shadow map is held.</p>
 */
public final class ShadowClearRegistry {

    private static final Set<Object> SHADOW_PASSES =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private ShadowClearRegistry() {
    }

    public static void tag(Object clearPass) {
        if (clearPass != null) {
            SHADOW_PASSES.add(clearPass);
        }
    }

    public static boolean isShadowPass(Object clearPass) {
        return clearPass != null && SHADOW_PASSES.contains(clearPass);
    }
}
