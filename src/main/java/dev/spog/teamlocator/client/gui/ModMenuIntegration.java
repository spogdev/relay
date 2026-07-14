package dev.spog.teamlocator.client.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.spog.teamlocator.client.TeamLocatorClient;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new TeamLocatorConfigScreen(parent, TeamLocatorClient.CONFIG);
    }
}
