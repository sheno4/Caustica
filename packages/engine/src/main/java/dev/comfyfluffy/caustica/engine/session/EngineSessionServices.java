package dev.comfyfluffy.caustica.engine.session;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.engine.pass.PassContributionChannel;
import dev.comfyfluffy.caustica.engine.pass.PassFailureHandler;
import dev.comfyfluffy.caustica.engine.pass.PassSchedulerBackend;
import dev.comfyfluffy.caustica.engine.pass.PassSession;
import dev.comfyfluffy.caustica.engine.program.ProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramContributionChannel;
import dev.comfyfluffy.caustica.engine.program.ProgramEngineFailureHandler;
import dev.comfyfluffy.caustica.engine.program.ProgramSession;
import dev.comfyfluffy.caustica.engine.scene.GeometryContributionChannel;
import dev.comfyfluffy.caustica.engine.scene.LightContributionChannel;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;
import dev.comfyfluffy.caustica.engine.scene.SceneDirectory;
import dev.comfyfluffy.caustica.engine.scene.SceneRetirementFailureHandler;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Shared renderer services and owner-scope factory for one concrete render session. */
public final class EngineSessionServices implements ContributionScopeFactory, AutoCloseable {
    private final GpuDevice gpu;
    private final ProgramSession programs;
    private final SceneDirectory scenes;
    private final PassSession passes;
    private final Set<Scope> scopes = new LinkedHashSet<>();
    private boolean accepting = true;

    public EngineSessionServices(
            GpuDevice gpu,
            ProgramBackend programBackend,
            RetainedSceneBackend sceneBackend,
            PassSchedulerBackend passBackend,
            ProgramEngineFailureHandler programFailures,
            SceneRetirementFailureHandler sceneFailures,
            PassFailureHandler passFailures) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        programs = new ProgramSession(programBackend, programFailures);
        scenes = new SceneDirectory(programs, sceneBackend, sceneFailures);
        passes = new PassSession(passBackend, passFailures);
    }

    @Override
    public synchronized ContributionScope create(ContributionOwner owner) {
        Objects.requireNonNull(owner, "owner");
        if (!accepting) throw new IllegalStateException("session services are closed");
        Scope scope = new Scope(owner);
        scopes.add(scope);
        return scope;
    }

    /** Host program resolution and publication state. */
    public ProgramSession programs() { return programs; }

    /** Host scene creation, removal, snapshots, and retained publication state. */
    public SceneDirectory scenes() { return scenes; }

    /** Host pass dispatch and scheduler progress. */
    public PassSession passes() { return passes; }

    /** Advances compiler publication, retained retirements, and eligible pass closes. */
    public void progress() {
        programs.progress();
        scenes.progressCallbacks();
        passes.progress();
    }

    /** Stops new owner scopes after the render-session orchestrator has closed its contributions. */
    @Override
    public synchronized void close() {
        if (!accepting) return;
        if (!scopes.isEmpty()) {
            throw new IllegalStateException("contribution scopes remain open");
        }
        accepting = false;
        passes.close();
        progress();
    }

    private final class Scope implements ContributionScope {
        private final ProgramContributionChannel program;
        private final PassContributionChannel pass;
        private final GeometryContributionChannel geometry;
        private final LightContributionChannel lights;
        private boolean quiesced;
        private boolean invalidated;
        private boolean drained;
        private boolean closed;

        private Scope(ContributionOwner owner) {
            program = programs.openChannel(owner);
            pass = passes.openChannel(owner);
            geometry = scenes.openGeometry(owner);
            lights = scenes.openLights(owner);
        }

        @Override public GpuDevice gpu() { return gpu; }
        @Override public ProgramChannel program() { return program; }
        @Override public PassChannel passes() { return pass; }
        @Override public GeometryChannel geometry() { return geometry; }
        @Override public LightChannel lights() { return lights; }

        @Override
        public void quiesce() {
            if (quiesced) return;
            pass.quiesce();
            program.quiesce();
            geometry.quiesce();
            lights.quiesce();
            quiesced = true;
        }

        @Override
        public void invalidate() {
            if (invalidated) return;
            quiesce();
            pass.invalidate();
            geometry.invalidate();
            lights.invalidate();
            program.invalidate();
            invalidated = true;
        }

        @Override
        public void drain() {
            if (drained) return;
            invalidate();
            pass.drain();
            geometry.drain();
            lights.drain();
            program.drain();
            drained = true;
        }

        @Override
        public void close() {
            if (closed) return;
            if (!drained) throw new IllegalStateException("scope must drain before close");
            synchronized (EngineSessionServices.this) {
                scopes.remove(this);
            }
            closed = true;
        }
    }
}
