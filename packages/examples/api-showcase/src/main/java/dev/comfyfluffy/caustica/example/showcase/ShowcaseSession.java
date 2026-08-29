package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.pass.PassRegistration;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.session.RenderSessionContext;
import dev.comfyfluffy.caustica.api.session.RenderSessionContribution;

import java.util.List;

final class ShowcaseSession implements RenderSessionContribution {
    private final RenderSessionContext context;
    private final ShowcasePrograms programs;
    private final List<PassRegistration> passes;
    private ShowcaseScene scene;
    private boolean stopped;

    ShowcaseSession(RenderSessionContext context) {
        this.context = context;
        programs = new ShowcasePrograms(context.program(), System.err::println);
        passes = List.of(
                context.passes().addWorldResourcePass(setup -> ShowcasePasses.worldResource(setup.gpu())),
                context.passes().addPostEffectPass(setup -> ShowcasePasses.postEffect(setup.gpu())),
                context.passes().addUiPass(setup -> ShowcasePasses.ui(setup.gpu())));
    }

    /** Called by the Minecraft-facing package when its host-owned scene becomes available. */
    void openScene(SceneId sceneId) {
        scene = new ShowcaseScene(programs.exports(), sceneId, context.geometry(), context.lights());
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
