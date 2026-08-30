package dev.comfyfluffy.caustica.minecraft.client.mixin;

import dev.comfyfluffy.caustica.minecraft.client.screen.CausticaOptionsScreen;
import dev.comfyfluffy.caustica.minecraft.client.CausticaClientComposition;
import dev.comfyfluffy.caustica.minecraft.client.screen.CausticaTheme;
import dev.comfyfluffy.caustica.minecraft.client.screen.widget.CausticaTextButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the entry point for Caustica's settings to the vanilla Video Settings screen. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    @Inject(method = "addOptions", at = @At("HEAD"))
    private void caustica$addOptionsEntry(CallbackInfo ci) {
        OptionsList list = ((OptionsSubScreenAccessor) (Object) this).getList();
        if (list == null) {
            return;
        }
        VideoSettingsScreen self = (VideoSettingsScreen) (Object) this;
        list.addBig(CausticaTextButton.text(
                Component.translatable("caustica.options.open"),
                Minecraft.getInstance().font,
                CausticaTheme.ACCENT_ENGINE,
                () -> {
                    var services = CausticaClientComposition.current().apiServices();
                    Minecraft.getInstance().setScreenAndShow(
                            new CausticaOptionsScreen(self, services.settings(), services.options()));
                }));
    }
}
