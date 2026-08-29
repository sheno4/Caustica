package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.pass.PassRegistration;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.session.RenderSessionContext;
import dev.comfyfluffy.caustica.api.session.RenderSessionContribution;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.settings.OptionLookup;

import java.util.List;

final class ShowcaseSession implements RenderSessionContribution {
    private final RenderSessionContext context;
    private final ShowcasePrograms programs;
    private final List<PassRegistration> passes;
    private ShowcaseScene scene;
    private boolean stopped;

    ShowcaseSession(RenderSessionContext context, OptionLookup options) {
        this.context = context;
        programs = new ShowcasePrograms(context.program(), System.err::println);
        passes = List.of(
                context.passes().addWorldResourcePass(setup -> ShowcasePasses.worldResource(setup.gpu())),
                context.passes().addPostEffectPass(
                        ShowcasePasses.POST_EFFECT, ShowcasePasses.POST_EFFECT_PLACEMENT,
                        setup -> ShowcasePasses.postEffect(setup.gpu(), options)),
                context.passes().addUiPass(
                        ShowcasePasses.UI, ShowcasePasses.UI_PLACEMENT,
                        setup -> ShowcasePasses.ui(setup.gpu())));
    }

    /** Called by the Minecraft-facing package when its host-owned scene becomes available. */
    void openScene(SceneId sceneId) {
        scene = new ShowcaseScene(programs.exports(), sceneId, context.geometry(), context.lights());
    }

    SceneView vacuumView(Camera camera) {
        return vacuumView(requireScene().identity(), camera);
    }

    SceneView underwaterView(Camera camera, long volumeBindingWord, long volumeInstanceWord) {
        return volumeView(requireScene().identity(), camera, programs.exports().volume(),
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

    private ShowcaseScene requireScene() {
        if (scene == null) throw new IllegalStateException("the host scene is not open");
        return scene;
    }

    /** Binding exported to the Minecraft scene selector; this contribution receives no scene authority. */
    EnvironmentBinding<ShowcasePrograms.EnvironmentBindingData> environment(
            long bindingWord, Runnable retired) {
        return programs.environmentBinding(bindingWord, retired);
    }

    /** Called by the Minecraft-facing package before its host-owned scene disappears. */
    void closeScene() {
        scene.stop();
        scene = null;
    }

    @Override
    public void stop() {
        passes.forEach(PassRegistration::close);
        if (scene != null) {
            closeScene();
        }
        programs.close();
        stopped = true;
    }

    @Override
    public void close() {
        if (!stopped) {
            throw new IllegalStateException("session close must follow producer stop and scoped drainage");
        }
    }
}
