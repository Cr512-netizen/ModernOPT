package net.cr512.modernopt.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.cr512.modernopt.ModernOPT;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** Game-bus client hooks: the options keybind. */
@EventBusSubscriber(modid = ModernOPT.MOD_ID, value = Dist.CLIENT)
public final class ClientInit {

    private static final KeyMapping OPEN_CONFIG = new KeyMapping("key.modernopt.config",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, "key.categories.misc");

    private ClientInit() {
    }

    public static KeyMapping openConfigKey() {
        return OPEN_CONFIG;
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null) {
            return;
        }
        boolean pressed = false;
        while (OPEN_CONFIG.consumeClick()) {
            pressed = true;
        }
        if (pressed) {
            minecraft.setScreen(new ModernOptConfigScreen(null));
        }
    }
}
