package net.cr512.modernopt.mixin;

import java.lang.reflect.Method;

import net.cr512.modernopt.ModernOPT;
import net.cr512.modernopt.client.ModernOptConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts a "ModernOPT" button on the Sodium video settings page.
 *
 * <p>{@link Pseudo} plus {@code require = 0} means the whole thing is inert when Sodium is not
 * installed, or when Sodium renames its options screen in a future release: no crash, just no
 * button. Both historical package names are targeted, only one of them can exist.</p>
 *
 * <p>{@code Screen#addRenderableWidget} is protected, and this mixin cannot extend
 * {@link Screen} without inventing a constructor for someone else's class, so the widget is
 * added through a cached reflected reference to that very method.</p>
 */
@Pseudo
@Mixin(targets = {
        "net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI",
        "me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI"
}, remap = false)
public abstract class SodiumOptionsScreenMixin {

    private static boolean addWidgetLookupDone;
    private static Method addRenderableWidget;

    @Inject(method = "init()V", at = @At("TAIL"), require = 0, remap = false)
    private void modernopt$addConfigButton(CallbackInfo callbackInfo) {
        Screen screen = (Screen) (Object) this;
        if (screen == null) {
            return;
        }

        Method adder = addWidgetMethod();
        if (adder == null) {
            return;
        }

        Button button = Button
                .builder(Component.literal("ModernOPT"), pressed ->
                        Minecraft.getInstance().setScreen(new ModernOptConfigScreen(screen)))
                .bounds(Math.max(4, screen.width - 110), 6, 100, 20)
                .build();

        try {
            adder.invoke(screen, button);
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[ModernOPT] cannot add the button to the Sodium options screen", throwable);
        }
    }

    private static Method addWidgetMethod() {
        if (!addWidgetLookupDone) {
            addWidgetLookupDone = true;
            try {
                Method method = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
                method.setAccessible(true);
                addRenderableWidget = method;
            } catch (Throwable throwable) {
                ModernOPT.LOG.warn("[ModernOPT] Screen#addRenderableWidget lookup failed, no Sodium button", throwable);
                addRenderableWidget = null;
            }
        }
        return addRenderableWidget;
    }
}
