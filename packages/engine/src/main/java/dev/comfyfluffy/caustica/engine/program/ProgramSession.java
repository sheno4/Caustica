package dev.comfyfluffy.caustica.engine.program;

import dev.comfyfluffy.caustica.api.program.EnvironmentDefinition;
import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.program.ProgramBuilder;
import dev.comfyfluffy.caustica.api.program.ProgramFailure;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.SurfaceDefinition;
import dev.comfyfluffy.caustica.api.program.SurfaceId;
import dev.comfyfluffy.caustica.api.program.VolumeDefinition;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.engine.resource.ResourceDirectory;
import dev.comfyfluffy.caustica.engine.resource.ResourceLease;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Session-wide ordered program registry and publication state machine.
 *
 * <p>Declarations are accepted synchronously. {@link #progress()} is the session-control-thread boundary
 * which consumes asynchronous compiler results, publishes last-good compositions, invokes serialized
 * readiness callbacks, and starts the next isolated compilation.
 */
public final class ProgramSession {
    private final ResourceDirectory resources;
    private final ProgramBackend backend;
    private final ProgramEngineFailureHandler failures;
    private final List<Registration<?>> accepted = new ArrayList<>();
    private final Queue<CompletionEvent> compilerCompletions = new ConcurrentLinkedQueue<>();
    private final Queue<CallbackTask> callbacks = new ArrayDeque<>();
    private List<Registration<?>> published = List.of();
    private ProgramBackend.CompiledProgram activeProgram;
    private BuildRequest inFlight;
    private final int[] nextDeclarationSequences = new int[ProgramKey.Kind.values().length];
    private boolean declarationActive;

    public ProgramSession(ResourceDirectory resources, ProgramBackend backend,
                          ProgramEngineFailureHandler failures) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    /** Creates a distinct owner-scoped channel for one contribution. */
    public synchronized ProgramContributionChannel openChannel(ContributionOwner owner) {
        return new ProgramContributionChannel(this, Objects.requireNonNull(owner, "owner"));
    }

    /**
     * Advances compilation/publication and runs queued readiness and internal release work in FIFO order.
     * Call only from the render session's callback/control thread.
     */
    public void progress() {
        while (true) {
            CompletionEvent completion = compilerCompletions.poll();
            if (completion == null) break;
            acceptCompletion(completion);
        }
        runCallbacks();
        startNextBuild();
    }

    /**
     * Whether this owner has no accepted registration, compiler candidate, or readiness callback left.
     * Session teardown waits for this before invoking the contribution's final close.
     */
    public synchronized boolean isDrained(ProgramContributionChannel channel) {
        requireChannel(channel);
        return accepted.stream().noneMatch(registration -> registration.channel == channel)
                && (inFlight == null || inFlight.target.stream()
                        .noneMatch(registration -> registration.channel == channel))
                && callbacks.stream().noneMatch(callback -> callback.channel == channel);
    }

    /** Advances publication and waits for this owner's compiler and published program uses to drain. */
    public void drain(ProgramContributionChannel channel) {
        while (true) {
            progress();
            backend.drainPublishedUses();
            progress();
            synchronized (this) {
                if (isDrained(channel)) return;
                if (!compilerCompletions.isEmpty() || !callbacks.isEmpty()) continue;
                awaitChange();
            }
        }
    }

    /** Returns the published implementation index, or zero for a stale/foreign surface. */
    public synchronized int resolve(SurfaceId<?, ?> id) {
        if (!(id instanceof SurfaceReference reference) || reference.session != this
                || !reference.registration.published || activeProgram == null) {
            return 0;
        }
        return reference.key.implementationIndex();
    }

    /** Returns the published implementation index, or zero for vacuum. */
    public synchronized int resolve(VolumeId<?, ?> id) {
        if (!(id instanceof VolumeReference reference) || reference.session != this
                || !reference.registration.published || activeProgram == null) {
            return 0;
        }
        return reference.key.implementationIndex();
    }

    /** Returns the published implementation index, or zero for the error environment. */
    public synchronized int resolve(EnvironmentId<?> id) {
        if (!(id instanceof EnvironmentReference reference) || reference.session != this
                || !reference.registration.published || activeProgram == null) {
            return 0;
        }
        return reference.key.implementationIndex();
    }

    /** Validates a surface reference and its erased geometry-slot schemas for this session. */
    public synchronized void validateSurface(SurfaceId<?, ?> id, ShaderData<?> bindingData,
                                             ShaderDataType<?> instanceType, boolean cutout) {
        if (!(id instanceof SurfaceReference reference) || reference.session != this) {
            throw new IllegalArgumentException("surface belongs to another program session");
        }
        reference.definition.bindingDataType().require(bindingData);
        if (reference.definition.instanceDataType() != instanceType) {
            throw new IllegalArgumentException("surface instance-data schema does not match the mesh");
        }
        if (cutout && reference.definition.coverage() == null) {
            throw new IllegalArgumentException("cutout geometry requires a coverage implementation");
        }
    }

    /** Validates a volume reference and its erased geometry-slot schemas for this session. */
    public synchronized void validateVolume(VolumeId<?, ?> id, ShaderData<?> bindingData,
                                            ShaderDataType<?> instanceType) {
        if (!(id instanceof VolumeReference reference) || reference.session != this) {
            throw new IllegalArgumentException("volume belongs to another program session");
        }
        reference.definition.bindingDataType().require(bindingData);
        if (reference.definition.instanceDataType() != instanceType) {
            throw new IllegalArgumentException("volume instance-data schema does not match the mesh");
        }
    }

    /** Validates a same-session environment reference and its erased scene-binding schema. */
    public synchronized void validateEnvironment(EnvironmentId<?> id, ShaderData<?> bindingData) {
        if (!(id instanceof EnvironmentReference reference) || reference.session != this) {
            throw new IllegalArgumentException("environment belongs to another program session");
        }
        reference.definition.bindingDataType().require(bindingData);
    }

    synchronized <E> ProgramRegistration<E> register(
            ProgramContributionChannel channel, Function<? super ProgramBuilder, ? extends E> declaration) {
        requireChannel(channel);
        Objects.requireNonNull(declaration, "declaration");
        if (!channel.accepting) throw new IllegalStateException("program channel no longer accepts declarations");
        if (declarationActive) throw new IllegalStateException("program declarations must not overlap");

        Builder builder = new Builder(this);
        E exports;
        declarationActive = true;
        try {
            exports = Objects.requireNonNull(declaration.apply(builder), "program declaration returned null");
        } finally {
            builder.active = false;
            declarationActive = false;
        }
        requireNoTypeConflicts(builder.declarations);

        List<ResourceLease> resourceLeases = acquireImplementationResources(channel, builder.declarations);
        try {
            Registration<E> registration = new Registration<>(
                    this, channel, exports, builder.declarations, resourceLeases);
            builder.declarations.forEach(
                    declarationValue -> declarationValue.reference().registration = registration);
            accepted.add(registration);
            return registration;
        } catch (Throwable failure) {
            resourceLeases.forEach(ResourceLease::close);
            throw failure;
        }
    }

    synchronized void quiesce(ProgramContributionChannel channel) {
        requireChannel(channel);
        channel.accepting = false;
    }

    synchronized void invalidate(ProgramContributionChannel channel) {
        quiesce(channel);
        accepted.stream().filter(registration -> registration.channel == channel).toList()
                .forEach(Registration::close);
    }

    private void startNextBuild() {
        BuildRequest request;
        synchronized (this) {
            if (inFlight != null) return;
            List<Registration<?>> retained = published.stream().filter(registration -> !registration.closed).toList();
            Registration<?> introduced = null;
            List<Registration<?>> target;
            if (!retained.equals(published)) {
                target = retained;
            } else {
                introduced = accepted.stream()
                        .filter(registration -> registration.status == RegistrationStatus.PENDING
                                && !registration.closed && !published.contains(registration))
                        .findFirst().orElse(null);
                if (introduced == null) return;
                target = new ArrayList<>(published);
                target.add(introduced);
                target = List.copyOf(target);
            }
            request = new BuildRequest(target, introduced);
            inFlight = request;
        }
        ProgramComposition composition = composition(request);
        try {
            backend.compile(composition, result -> {
                compilerCompletions.add(new CompletionEvent(request, Objects.requireNonNull(result)));
                synchronized (this) { notifyAll(); }
            });
        } catch (Throwable failure) {
            compilerCompletions.add(new CompletionEvent(request,
                    new ProgramBackend.Compilation.Failed(new ProgramFailure(
                            "Program compiler invocation failed", failure.toString()))));
            synchronized (this) { notifyAll(); }
        }
    }

    private ProgramComposition composition(BuildRequest request) {
        return new ProgramComposition(request.target.stream()
                .flatMap(registration -> registration.declarations.stream())
                .map(Declaration::external)
                .toList());
    }

    private void acceptCompletion(CompletionEvent event) {
        synchronized (this) {
            if (inFlight != event.request) {
                if (event.result instanceof ProgramBackend.Compilation.Succeeded succeeded) {
                    succeeded.program().close();
                }
                return;
            }
            inFlight = null;
            if (event.result instanceof ProgramBackend.Compilation.Failed failed) {
                if (event.request.introduced != null) {
                    Registration<?> registration = event.request.introduced;
                    if (!registration.closed && registration.status == RegistrationStatus.PENDING) {
                        registration.fail(failed.failure());
                        accepted.remove(registration);
                        retire(registration);
                    } else if (registration.closed) {
                        retire(registration);
                    }
                } else {
                    failures.report(new IllegalStateException(
                            "A composition made only from previously published registrations failed: "
                                    + failed.failure().summary() + "\n" + failed.failure().diagnostics()));
                }
                return;
            }

            ProgramBackend.CompiledProgram candidate =
                    ((ProgramBackend.Compilation.Succeeded) event.result).program();
            if (!valid(event.request)) {
                candidate.close();
                if (event.request.introduced != null && event.request.introduced.closed) {
                    retire(event.request.introduced);
                }
                return;
            }

            List<Registration<?>> previous = published;
            List<Registration<?>> removed = previous.stream()
                    .filter(registration -> !event.request.target.contains(registration)).toList();
            backend.publish(candidate, () -> enqueue(null, () -> removed.forEach(this::retire)));
            removed.forEach(registration -> registration.published = false);
            event.request.target.forEach(registration -> registration.published = true);
            activeProgram = candidate;
            published = event.request.target;
            if (event.request.introduced != null) event.request.introduced.ready();
        }
    }

    private boolean valid(BuildRequest request) {
        if (request.target.stream().anyMatch(registration -> registration.closed)) return false;
        if (request.introduced != null) {
            return request.introduced.status == RegistrationStatus.PENDING
                    && request.target.equals(append(published, request.introduced));
        }
        return request.target.equals(published.stream().filter(registration -> !registration.closed).toList());
    }

    private static List<Registration<?>> append(List<Registration<?>> values, Registration<?> value) {
        List<Registration<?>> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    private void runCallbacks() {
        while (true) {
            CallbackTask callback;
            synchronized (this) {
                callback = callbacks.poll();
            }
            if (callback == null) return;
            try {
                callback.action.run();
            } catch (Throwable failure) {
                failures.report(failure);
            }
        }
    }

    private synchronized void enqueue(ProgramContributionChannel channel, Runnable callback) {
        callbacks.add(new CallbackTask(channel, callback));
        notifyAll();
    }

    private void awaitChange() {
        try {
            wait();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while draining programs", interrupted);
        }
    }

    private void retire(Registration<?> registration) {
        synchronized (this) {
            if (registration.retired) return;
            registration.retired = true;
            accepted.remove(registration);
        }
        registration.resourceLeases.forEach(ResourceLease::close);
    }

    private List<ResourceLease> acquireImplementationResources(
            ProgramContributionChannel channel, List<Declaration> declarations) {
        Map<ResourceRef, Boolean> acquired = new IdentityHashMap<>();
        List<ResourceLease> leases = new ArrayList<>();
        try {
            for (Declaration declaration : declarations) {
                ResourceRef reference = declaration.implementationDataResource();
                if (reference == ResourceRef.none() || acquired.put(reference, Boolean.TRUE) != null) continue;
                leases.add(resources.acquire(channel.owner, reference));
            }
            return List.copyOf(leases);
        } catch (Throwable failure) {
            leases.forEach(ResourceLease::close);
            throw failure;
        }
    }

    private void requireNoTypeConflicts(List<Declaration> declarations) {
        Map<String, ShaderIdentity> types = new LinkedHashMap<>();
        accepted.stream().filter(registration -> !registration.retired)
                .flatMap(registration -> registration.declarations.stream())
                .forEach(declaration -> declaration.shaders().forEach(shader -> putShader(types, shader)));
        declarations.forEach(declaration -> declaration.shaders().forEach(shader -> putShader(types, shader)));
    }

    private static void putShader(Map<String, ShaderIdentity> types, ShaderDefinition shader) {
        ShaderIdentity identity = ShaderIdentity.of(shader);
        ShaderIdentity existing = types.putIfAbsent(shader.type(), identity);
        if (existing != null && !existing.equals(identity)) {
            throw new IllegalStateException("shader type " + shader.type()
                    + " resolves from conflicting modules or sources");
        }
    }

    private void requireChannel(ProgramContributionChannel channel) {
        if (channel == null || channel.session != this) {
            throw new IllegalArgumentException("program channel belongs to another session");
        }
    }

    private ProgramKey nextKey(ProgramKey.Kind kind) {
        int ordinal = kind.ordinal();
        return new ProgramKey(kind, ++nextDeclarationSequences[ordinal]);
    }

    private record BuildRequest(List<Registration<?>> target, Registration<?> introduced) { }
    private record CompletionEvent(BuildRequest request, ProgramBackend.Compilation result) { }
    private record CallbackTask(ProgramContributionChannel channel, Runnable action) { }

    private enum RegistrationStatus { PENDING, READY, FAILED, CANCELLED }

    private record ShaderIdentity(Class<?> anchor, String root, List<String> subdirectories,
                                  String module, String type) {
        static ShaderIdentity of(ShaderDefinition shader) {
            return new ShaderIdentity(shader.source().resourceAnchor(), shader.source().classpathRoot(),
                    shader.source().subdirectories(), shader.module(), shader.type());
        }
    }

    private abstract static class Reference {
        final ProgramSession session;
        final ProgramKey key;
        Registration<?> registration;

        Reference(ProgramSession session, ProgramKey key) {
            this.session = session;
            this.key = key;
        }
    }

    private static final class SurfaceReference extends Reference implements SurfaceId<Object, Object> {
        private final SurfaceDefinition<?, ?> definition;
        SurfaceReference(ProgramSession session, ProgramKey key, SurfaceDefinition<?, ?> definition) {
            super(session, key);
            this.definition = definition;
        }
    }
    private static final class VolumeReference extends Reference implements VolumeId<Object, Object> {
        private final VolumeDefinition<?, ?> definition;
        VolumeReference(ProgramSession session, ProgramKey key, VolumeDefinition<?, ?> definition) {
            super(session, key);
            this.definition = definition;
        }
    }
    private static final class EnvironmentReference extends Reference implements EnvironmentId<Object> {
        final EnvironmentDefinition<?> definition;
        EnvironmentReference(ProgramSession session, ProgramKey key, EnvironmentDefinition<?> definition) {
            super(session, key);
            this.definition = definition;
        }
    }

    private sealed interface Declaration permits SurfaceDeclaration, VolumeDeclaration, EnvironmentDeclaration {
        Reference reference();
        ProgramComposition.Declaration external();
        List<ShaderDefinition> shaders();
        ResourceRef implementationDataResource();
    }

    private record SurfaceDeclaration(SurfaceReference reference, SurfaceDefinition<?, ?> definition)
            implements Declaration {
        @Override public ProgramComposition.Declaration external() {
            return new ProgramComposition.Surface(reference.key, definition);
        }
        @Override public List<ShaderDefinition> shaders() {
            return definition.coverage() == null ? List.of(definition.surface())
                    : List.of(definition.surface(), definition.coverage());
        }
        @Override public ResourceRef implementationDataResource() {
            return definition.implementationData().resource();
        }
    }

    private record VolumeDeclaration(VolumeReference reference, VolumeDefinition<?, ?> definition)
            implements Declaration {
        @Override public ProgramComposition.Declaration external() {
            return new ProgramComposition.Volume(reference.key, definition);
        }
        @Override public List<ShaderDefinition> shaders() { return List.of(definition.implementation()); }
        @Override public ResourceRef implementationDataResource() {
            return definition.implementationData().resource();
        }
    }

    private record EnvironmentDeclaration(EnvironmentReference reference, EnvironmentDefinition<?> definition)
            implements Declaration {
        @Override public ProgramComposition.Declaration external() {
            return new ProgramComposition.Environment(reference.key, definition);
        }
        @Override public List<ShaderDefinition> shaders() { return List.of(definition.implementation()); }
        @Override public ResourceRef implementationDataResource() { return ResourceRef.none(); }
    }

    private final class Builder implements ProgramBuilder {
        private final ProgramSession session;
        private final List<Declaration> declarations = new ArrayList<>();
        private boolean active = true;

        private Builder(ProgramSession session) { this.session = session; }

        @Override
        @SuppressWarnings("unchecked")
        public <B, N> SurfaceId<B, N> surface(SurfaceDefinition<B, N> definition) {
            requireActive();
            Objects.requireNonNull(definition, "definition");
            SurfaceReference reference = new SurfaceReference(session, nextKey(ProgramKey.Kind.SURFACE), definition);
            declarations.add(new SurfaceDeclaration(reference, definition));
            return (SurfaceId<B, N>) (SurfaceId<?, ?>) reference;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <B, N> VolumeId<B, N> volume(VolumeDefinition<B, N> definition) {
            requireActive();
            Objects.requireNonNull(definition, "definition");
            VolumeReference reference = new VolumeReference(session, nextKey(ProgramKey.Kind.VOLUME), definition);
            declarations.add(new VolumeDeclaration(reference, definition));
            return (VolumeId<B, N>) (VolumeId<?, ?>) reference;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <B> EnvironmentId<B> environment(EnvironmentDefinition<B> definition) {
            requireActive();
            Objects.requireNonNull(definition, "definition");
            EnvironmentReference reference = new EnvironmentReference(
                    session, nextKey(ProgramKey.Kind.ENVIRONMENT), definition);
            declarations.add(new EnvironmentDeclaration(reference, definition));
            return (EnvironmentId<B>) (EnvironmentId<?>) reference;
        }

        private void requireActive() {
            if (!active) throw new IllegalStateException("program builder is no longer active");
        }
    }

    private static final class Registration<E> implements ProgramRegistration<E> {
        private final ProgramSession session;
        private final ProgramContributionChannel channel;
        private final E exports;
        private final List<Declaration> declarations;
        private final List<ResourceLease> resourceLeases;
        private final List<Consumer<? super Completion>> observers = new ArrayList<>();
        private RegistrationStatus status = RegistrationStatus.PENDING;
        private Completion completion;
        private boolean closed;
        private boolean retired;
        private boolean published;

        private Registration(ProgramSession session, ProgramContributionChannel channel,
                             E exports, List<Declaration> declarations,
                             List<ResourceLease> resourceLeases) {
            this.session = session;
            this.channel = channel;
            this.exports = exports;
            this.declarations = List.copyOf(declarations);
            this.resourceLeases = resourceLeases;
        }

        @Override public E exports() { return exports; }

        @Override
        public void whenComplete(Consumer<? super Completion> observer) {
            Objects.requireNonNull(observer, "callback");
            synchronized (session) {
                if (completion == null) observers.add(observer);
                else session.enqueue(channel, () -> observer.accept(completion));
            }
        }

        @Override
        public void close() {
            synchronized (session) {
                if (closed) return;
                closed = true;
                if (status == RegistrationStatus.PENDING) {
                    complete(RegistrationStatus.CANCELLED, new Cancelled());
                    if (session.inFlight == null || !session.inFlight.target.contains(this)) {
                        session.retire(this);
                    }
                }
            }
        }

        private void ready() {
            complete(RegistrationStatus.READY, new Ready());
        }

        private void fail(ProgramFailure failure) {
            complete(RegistrationStatus.FAILED, new Failed(failure));
        }

        private void complete(RegistrationStatus status, Completion completion) {
            if (this.status != RegistrationStatus.PENDING) return;
            this.status = status;
            this.completion = completion;
            observers.forEach(observer -> session.enqueue(channel, () -> observer.accept(completion)));
            observers.clear();
        }
    }
}
