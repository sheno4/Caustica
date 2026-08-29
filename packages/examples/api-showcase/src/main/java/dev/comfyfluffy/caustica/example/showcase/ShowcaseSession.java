package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.pass.PassRegistration;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.session.RenderSessionContext;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftEnvironmentSelector;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContext;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;
import dev.comfyfluffy.caustica.settings.OptionLookup;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class ShowcaseSession implements MinecraftWorldSessionContribution {
    private final MinecraftEnvironmentSelector environment;
    private final ShowcasePrograms programs;
    private final List<PassRegistration> passes;
    private final ShowcaseScene scene;
    private final AtomicBoolean environmentPublished = new AtomicBoolean();
    private boolean stopped;

    ShowcaseSession(MinecraftWorldSessionContext context, OptionLookup options) {
        environment = context.environment();
        RenderSessionContext renderSession = context.renderSession();
        programs = new ShowcasePrograms(renderSession.program(), System.err::println);
        scene = new ShowcaseScene(programs.exports(), context.scene(),
                renderSession.geometry(), renderSession.lights());
        passes = List.of(
                renderSession.passes().addWorldResourcePass(
                        setup -> ShowcasePasses.worldResource(setup.gpu(), this::publishEnvironmentWhenReady)),
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
        return volumeView(scene.identity(), camera, programs.exports().volume(),
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

    private void publishEnvironmentWhenReady() {
        if (programs.ready() && environmentPublished.compareAndSet(false, true)) {
            environment.select(programs.environmentBinding(0L, () -> { }));
        }
    }

    @Override
    public void stop() {
        passes.forEach(PassRegistration::close);
        scene.stop();
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
