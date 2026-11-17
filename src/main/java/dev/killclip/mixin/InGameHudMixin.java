package dev.killclip.mixin;

import dev.killclip.client.TextOverlays;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The text you add in the clip editor, drawn over the replay (the HUD itself is hidden in replays). */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
   @Inject(method = "render", at = @At("RETURN"), require = 0)
   private void killclip$editorText(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
      TextOverlays.drawInGame(context);
   }
}
