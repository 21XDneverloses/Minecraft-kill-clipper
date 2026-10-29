package dev.killclip.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class KillClipModMenu implements ModMenuApi {
   public ConfigScreenFactory<?> getModConfigScreenFactory() {
      return KillClipSettingsScreen::new;
   }
}
