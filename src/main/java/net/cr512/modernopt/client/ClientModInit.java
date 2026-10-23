package net.cr512.modernopt.client;

import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Mod-bus client hooks. Registered from {@link net.cr512.modernopt.ModernOPT} so this class -
 * which mentions client-only types - is only ever loaded on a physical client.
 */
public final class ClientModInit {

    private ClientModInit() {
    }

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ClientInit.openConfigKey());
    }
}
