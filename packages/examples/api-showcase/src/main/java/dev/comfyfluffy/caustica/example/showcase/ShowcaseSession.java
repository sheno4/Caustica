package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.pass.PassRegistration;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.session.RenderSessionContext;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContext;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;
import dev.comfyfluffy.caustica.settings.OptionLookup;

import java.util.List;

final class ShowcaseSession implements MinecraftWorldSessionContribution {
    private final ShowcaseHandoff.Selections selections;
    private final List<PassRegistration> passes;
    private final ShowcaseScene scene;
    private boolean stopped;

    ShowcaseSession(MinecraftWorldSessionContext context, OptionLookup options,
                    ShowcaseHandoff.Selections selections) {
        this.selections = java.util.Objects.requireNonNull(selections, "selections");
        RenderSessionContext renderSession = context.renderSession();
        scene = new ShowcaseScene(selections.programs(), selections.lights(),
                context.scene(), renderSession.geometry());
        passes = List.of(
                renderSession.passes().addWorldResourcePass(
                        setup -> ShowcasePasses.worldResource(setup.gpu(), selections::ready, scene)),
                renderSession.passes().addPostEffectPass(
                        ShowcasePasses.POST_EFFECT, ShowcasePasses.POST_EFFECT_PLACEMENT,
                        setup -> ShowcasePasses.postEffect(setup.gpu(), options)),
                renderSession.passes().addUiPass(
                        ShowcasePasses.UI, setup -> ShowcasePasses.ui(setup.gpu())));
    }

    SceneView vacuumView(Camera camera) {
        return vacuumView(scene.identity(), camera);
    }

    SceneView underwaterView(Camera camera, long volumeBindingWord, long volumeInstanceWord) {
        return volumeView(scene.identity(), camera, selections.programs().volume(),
                ShowcasePrograms.VOLUME_BINDING.data(volumeBindingWord),
                ShowcasePrograms.INSTANCE.data(volumeInstanceWord));
    }

    static SceneView vacuumView(SceneId scene, Camera camera) {
        return new SceneView(scene, camera, ViewMedium.Vacuum.INSTANCE);
    }

    static <B, N> SceneView volumeView(SceneId scene, Camera camera,
                                       dev.comfyfluffy.caustica.api.program.VolumeId<B, N> volume,
                                       dev.comfyfluffy.caustica.api.program.ShaderData<B> bindingData,
                                       dev.comfyfluffy.caustica.api.program.ShaderData<N> instanceData) {
        return new SceneView(scene, camera, new ViewMedium.Volume<>(volume, bindingData, instanceData));
    }

    @Override
    public void stop() {
        passes.forEach(PassRegistration::close);
        scene.stop();
        stopped = true;
    }

    @Override
    public void close() {
        if (!stopped) {
            throw new IllegalStateException("session close must follow producer stop and scoped drainage");
        }
    }
}
