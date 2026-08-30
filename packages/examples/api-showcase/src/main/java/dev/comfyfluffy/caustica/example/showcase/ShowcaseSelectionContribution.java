package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContext;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftWorldSessionContribution;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns program and light mutation while exporting only non-owning selections to the geometry contribution. */
final class ShowcaseSelectionContribution implements MinecraftWorldSessionContribution {
    private final MinecraftWorldSessionContext context;
    private final ShowcaseHandoff handoff;
    private final ShowcasePrograms programs;
    private final ShowcaseHandoff.Selections selections;
    private final List<LightId> lights;
    private final AtomicBoolean environmentPublished = new AtomicBoolean();
    private boolean stopped;

    ShowcaseSelectionContribution(MinecraftWorldSessionContext context, ShowcaseHandoff handoff) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.handoff = java.util.Objects.requireNonNull(handoff, "handoff");
        programs = new ShowcasePrograms(context.renderSession().program(), System.err::println);
        LightChannel channel = context.renderSession().lights();
        lights = List.of(channel.newLight(), channel.newLight(), channel.newLight());
        channel.submit(RetainedBatch.of(List.of(
                new LightChannel.SetLight(lights.get(0), context.scene(), new LightDescriptor.Rectangle(
                        0, 66, 0, 0.5, 0, 0, 0, 0, 0.5, 20, 18, 15)),
                new LightChannel.SetLight(lights.get(1), context.scene(), new LightDescriptor.Spot(
                        0, 66, 0, 0, -1, 0, 24, 0.35, 500, 450, 400)),
                new LightChannel.SetLight(lights.get(2), context.scene(), new LightDescriptor.Distant(
                        0, 1, 0, 100_000, 95_000, 90_000, 0.00465, false)))));
        selections = new ShowcaseHandoff.Selections(programs.exports(), lights, programs::ready);
        handoff.publish(context.scene(), selections);
        programs.whenReady(this::publishEnvironment);
    }

    private synchronized void publishEnvironment() {
        if (!stopped && environmentPublished.compareAndSet(false, true)) {
            ShowcaseMinecraftSky sky = new ShowcaseMinecraftSky(
                    context.dimension(), context.resourcePackEpoch());
            context.environment().select(sky.binding(programs, () -> { }));
        }
    }

    @Override
    public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        handoff.remove(context.scene(), selections);
        context.renderSession().lights().submit(RetainedBatch.of(lights.stream()
                .map(LightChannel.DropLight::new)
                .map(LightChannel.Operation.class::cast)
                .toList()));
        programs.close();
    }

    @Override
    public synchronized void close() {
        if (!stopped) throw new IllegalStateException("selection contribution close must follow stop");
    }
}
