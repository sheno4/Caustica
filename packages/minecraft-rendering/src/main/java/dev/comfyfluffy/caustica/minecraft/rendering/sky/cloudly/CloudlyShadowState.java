package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Source shadow geometry and immutable cache scheduling; no source assets or defaults are embedded. */
public final class CloudlyShadowState {
    private CloudlyShadowState() { }

    public record Vector(float x, float y, float z) {
        public double[] values() { return new double[]{x, y, z}; }
        Vector plus(Vector other) { return new Vector(x + other.x, y + other.y, z + other.z); }
        Vector minus(Vector other) { return new Vector(x - other.x, y - other.y, z - other.z); }
        Vector times(float scalar) { return new Vector(x * scalar, y * scalar, z * scalar); }
        float dot(Vector other) { return (x * other.x + y * other.y) + z * other.z; }
        Vector cross(Vector other) { return new Vector(y * other.z - z * other.y,
                z * other.x - x * other.z, x * other.y - y * other.x); }
        Vector normalized() { return times(1f / (float)Math.sqrt(dot(this))); }
    }

    public record Extent(int width, int height, int depth) {
        public Extent {
            if (width <= 0 || height <= 0 || depth <= 0) throw new IllegalArgumentException("Invalid shadow extent");
        }
        public double[] values() { return new double[]{width, height, depth}; }
    }

    public record SourceSettings(float earthRadiusKm, float cloudStartHeightKm, float cloudMaxHeightMeters,
                                 float cloudMaxTraceDistanceKm, boolean flatMap, boolean enableLowElevation,
                                 float lowElevationSwitchDegrees, Extent normalExtent, Extent lowExtent,
                                 float normalTexelKm, float lowTexelKm, float transitionX, float transitionY,
                                 boolean centerCloudBandAtLowElevation, float centerCloudBandFactor,
                                 int splitFrames, int splitX, int splitY) {
        public SourceSettings {
            Objects.requireNonNull(normalExtent); Objects.requireNonNull(lowExtent);
            if (earthRadiusKm <= 0 || cloudMaxTraceDistanceKm <= 0 || normalTexelKm <= 0 || lowTexelKm <= 0
                    || cloudMaxHeightMeters <= cloudStartHeightKm * 1000 || lowElevationSwitchDegrees <= 0
                    || splitFrames <= 0 || splitX <= 0 || splitY <= 0)
                throw new IllegalArgumentException("Invalid recovered shadow parameters");
        }
    }

    /** Supported host branch selections remain separate from recovered source settings. */
    public record HostChoices(boolean shadowEnabled, boolean flagBitOne, boolean snapInShadowBasis,
                              boolean centerCloudBandLevel0, boolean centerCloudBandLevel1,
                              boolean cutCorners, boolean jitter, boolean multiFrameFilter) { }

    /** Sun elevation remains the actual Sun even when the effective source light is the Moon. */
    public record LightAngles(float sunElevationDegrees, float effectiveElevationDegrees,
                              float effectiveAzimuthDegrees) { }

    public record CameraCentimeters(double x, double y, double z) { }

    public record Geometry(boolean lowElevation, Vector lightDirection, Vector xAxis, Vector yAxis,
                           float sinElevation, float cosElevation, Extent extent, float texelMeters,
                           float worldWidth, float worldHeight, float reciprocalWidth, float reciprocalHeight,
                           float transitionMin, float transitionMax, int flags, float slabDistance,
                           float reciprocalSlabDistance) {
        public Map<String, double[]> inputs() {
            var inputs = new LinkedHashMap<String, double[]>();
            inputs.put("nativeFrame.ShadowTraceConstants_LV0_Size", new double[]{worldWidth, worldHeight, reciprocalWidth, reciprocalHeight});
            inputs.put("nativeFrame.ShadowTraceConstants_ShadowMapXVector", xAxis.values());
            inputs.put("nativeFrame.ShadowTraceConstants_ShadowMapYVector", yAxis.values());
            inputs.put("nativeFrame.ShadowTraceConstants_TextureSize", extent.values());
            inputs.put("nativeFrame.ShadowTraceConstants_TransitionTex", new double[]{transitionMin, transitionMax});
            inputs.put("nativeFrame.ShadowTraceConstants_Flags", new double[]{flags});
            inputs.put("nativeFrame.ShadowTraceConstants_SlabDistanceAlongSunDirection", new double[]{slabDistance, reciprocalSlabDistance});
            return Map.copyOf(inputs);
        }
    }

    /** Arithmetic uses float32; original platform sin/cos polynomial bit identity is not asserted. */
    public static Geometry geometry(SourceSettings source, LightAngles light, HostChoices choices) {
        float degreeToRad = 0.01745329238474369f;
        float elevation = light.effectiveElevationDegrees * degreeToRad;
        float azimuth = (light.effectiveAzimuthDegrees - 90f) * degreeToRad;
        if (azimuth < 3.1415927410125732f) azimuth += 6.2831854820251465f;
        float se = (float)Math.sin(elevation), ce = (float)Math.cos(elevation);
        float sa = (float)Math.sin(azimuth), ca = (float)Math.cos(azimuth);
        var direction = new Vector(ce * ca, ce * sa, se);
        float raised = elevation + 1.5707963705062866f;
        float sr = (float)Math.sin(raised), cr = (float)Math.cos(raised);
        var y = new Vector(cr * ca, cr * sa, sr);
        var x = y.cross(direction).normalized();
        boolean low = source.enableLowElevation && Math.abs(light.effectiveElevationDegrees) < source.lowElevationSwitchDegrees;
        var extent = low ? source.lowExtent : source.normalExtent;
        float texel = (low ? source.lowTexelKm : source.normalTexelKm) * 1000f;
        float width = texel * extent.width, height = texel * extent.height;
        float slab, inverse;
        if (low) {
            float span = (ce * source.cloudMaxTraceDistanceKm) * 1000f;
            slab = Math.abs(span + span); inverse = 1f / slab;
        } else {
            inverse = Math.abs(se / (source.cloudMaxHeightMeters - source.cloudStartHeightKm * 1000f));
            slab = 1f / inverse;
        }
        float smallest = Math.min(source.transitionX, source.transitionY);
        float largest = Math.max(Math.max(source.transitionX, source.transitionY), smallest + 0.0001f);
        float reciprocalX = 1f / extent.width;
        return new Geometry(low, direction, x, y, se, ce, extent, texel,
                choices.shadowEnabled ? width : 0f, choices.shadowEnabled ? height : 0f,
                choices.shadowEnabled ? 1f / width : 0f, choices.shadowEnabled ? 1f / height : 0f,
                1f - largest * reciprocalX, 1f - smallest * reciprocalX,
                (low ? 16 : 0) | (choices.flagBitOne ? 2 : 0), slab, inverse);
    }

    public static Vector cameraCenter(SourceSettings source, LightAngles light, Geometry geometry,
                                      int cascade, CameraCentimeters camera, HostChoices choices) {
        requireCascade(cascade);
        // Native narrows source double centimetres before converting to float metres.
        var position = new Vector((float)camera.x * 0.01f, (float)camera.y * 0.01f, (float)camera.z * 0.01f);
        var target = position;
        boolean centerBand = cascade == 0 ? choices.centerCloudBandLevel0 : choices.centerCloudBandLevel1;
        if (source.centerCloudBandAtLowElevation && geometry.lowElevation && centerBand) {
            float earthZ = source.earthRadiusKm * -1000f;
            var anchor = source.flatMap ? new Vector(0, 0, earthZ) : new Vector(position.x, position.y, earthZ);
            float cloudStart = source.cloudStartHeightKm * 1000f;
            float shell = ((source.cloudMaxHeightMeters - cloudStart) * source.centerCloudBandFactor + cloudStart)
                    + source.earthRadiusKm * 1000f;
            var onShell = anchor.plus(position.minus(anchor).normalized().times(shell));
            target = onShell.plus(position.minus(onShell).times(Math.abs(geometry.sinElevation)));
        }
        if (!choices.snapInShadowBasis) return target;
        float snapScale = geometry.lowElevation ? 8f
                : light.sunElevationDegrees > 30f ? 8f : light.sunElevationDegrees <= 10f ? 2f : 4f;
        float numerator = ((geometry.texelMeters * geometry.extent.width) * snapScale) * (cascade == 0 ? 1f : 5f);
        float denominator = geometry.extent.width * (1f / snapScale);
        float step = numerator / denominator, reciprocalStep = 1f / step;
        float sx = (float)Math.floor(target.dot(geometry.xAxis) * reciprocalStep) * step;
        float sy = (float)Math.floor(target.dot(geometry.yAxis) * reciprocalStep) * step;
        var snapped = geometry.xAxis.times(sx).plus(geometry.yAxis.times(sy));
        if (geometry.lowElevation) {
            float cloudSpan = source.cloudMaxTraceDistanceKm * 1000f + source.cloudMaxTraceDistanceKm * 1000f;
            float lightStep = cloudSpan / (geometry.extent.width * (1f / snapScale));
            float sz = (float)Math.floor(target.dot(geometry.lightDirection) / lightStep) * lightStep;
            snapped = snapped.plus(geometry.lightDirection.times(sz));
        }
        return snapped;
    }

    /** Opaque resource ownership travels with its centre through every native record exchange. */
    public record ResourceRecord<R>(Vector center, R resources, boolean completed) {
        public ResourceRecord { Objects.requireNonNull(center); Objects.requireNonNull(resources); }
    }

    public record Cache<R>(ResourceRecord<R> current, ResourceRecord<R> next, ResourceRecord<R> work,
                           int tileIndex, int filterPhase, int lastFilterFrame, int blendStart, int blendDuration) {
        public Cache {
            Objects.requireNonNull(current); Objects.requireNonNull(next); Objects.requireNonNull(work);
            if (filterPhase < 0 || filterPhase > 2) throw new IllegalArgumentException("Invalid shadow filter phase");
            if (current.resources.equals(next.resources) || current.resources.equals(work.resources) || next.resources.equals(work.resources))
                throw new IllegalArgumentException("Native shadow records must own three distinct resource sets");
        }
    }

    public record Stage<R>(String programId, R workResources, Map<String, double[]> inputs,
                           int groupsX, int groupsY, int groupsZ, boolean allThreadsOutsideRange) {
        public Stage { inputs = copyInputs(inputs); }
        @Override public Map<String, double[]> inputs() { return copyInputs(inputs); }
    }

    /** Install this candidate only after its ordered GPU stages have completed successfully. */
    public record Update<R>(Cache<R> stateAfterGpuCompletion, List<Stage<R>> stages, boolean publishesRecords) {
        public Update { stages = List.copyOf(stages); }
    }

    public static <R> Update<R> plan(Cache<R> cache, SourceSettings source, HostChoices choices,
                                   LightAngles light, Geometry geometry, int cascade, int frame,
                                   CameraCentimeters camera, boolean allocationChanged, boolean forceSingleFrame) {
        requireCascade(cascade);
        int total = forceSingleFrame ? 1 : Math.multiplyExact(source.splitFrames, cascade == 0 ? 1 : 2);
        int splitX = forceSingleFrame ? 1 : cascade == 0 ? source.splitX : source.splitY;
        int splitY = forceSingleFrame ? 1 : source.splitY;
        int tile = cache.tileIndex, phase = forceSingleFrame ? 2 : cache.filterPhase;
        int lastFilter = cache.lastFilterFrame, blendStart = cache.blendStart, duration = cache.blendDuration;
        var current = cache.current; var next = cache.next; var work = cache.work;
        if (Integer.compareUnsigned(tile, total) >= 0 && (phase == 2 || !choices.multiFrameFilter)) {
            phase = 2; tile = 0;
        }
        var stages = new ArrayList<Stage<R>>();
        if (phase == 2 || Integer.compareUnsigned(tile, total) < 0) {
            int previous = tile;
            if (previous == 0) work = new ResourceRecord<>(cameraCenter(source, light, geometry, cascade, camera, choices), work.resources, false);
            var inputs = new LinkedHashMap<>(geometry.inputs());
            inputs.put("frame.shadowTileIndex", new double[]{Integer.toUnsignedLong(previous)});
            inputs.put("frame.shadowCascadeLevel", new double[]{cascade});
            inputs.put("nativeFrame.ShadowTracePlaneSpaceCenter", work.center.values());
            inputs.put("nativeFrame.ShadowTraceClearCorners", new double[]{allocationChanged ? 1 : 0});
            inputs.put("nativeFrame.ShadowSplitFrame", new double[]{total});
            inputs.put("nativeFrame.ShadowSplitFrameForX", new double[]{splitX});
            inputs.put("nativeFrame.ShadowSplitFrameForY", new double[]{splitY});
            inputs.put("nativeFrame.ShadowTraceCutCorners", new double[]{choices.cutCorners ? 1 : 0});
            inputs.put("nativeFrame.ShadowTraceEnableJitter", new double[]{choices.jitter ? 1 : 0});
            stages.add(new Stage<>("69", work.resources, inputs, (geometry.extent.width / splitX + 7) / 8,
                    (geometry.extent.height / splitY + 7) / 8, 1, Integer.compareUnsigned(previous, splitX * splitY) >= 0 && total != 1));
            tile++;
            if (Integer.compareUnsigned(previous, total) < 0 && Integer.compareUnsigned(tile, total) >= 0) {
                lastFilter = frame; phase = 0; tile = 512;
            }
        }
        if (phase != 2 && Integer.compareUnsigned(tile, total) >= 0) {
            if (cascade == 0) {
                boolean immediate = total == 1 || forceSingleFrame || !choices.multiFrameFilter;
                boolean runX = immediate || phase == 0 && lastFilter != frame;
                if (runX) {
                    lastFilter = frame; phase = 1;
                    stages.add(filterStage("115", work.resources, geometry.extent, light.sunElevationDegrees));
                }
                boolean runY = immediate || phase == 1 && lastFilter != frame;
                if (runY) {
                    lastFilter = frame; stages.add(filterStage("107", work.resources, geometry.extent, light.sunElevationDegrees));
                } else return new Update<>(new Cache<>(current, next, work, tile, phase, lastFilter, blendStart, duration), stages, false);
            }
            stages.add(filterStage("114", work.resources, geometry.extent, light.sunElevationDegrees));
            work = new ResourceRecord<>(work.center, work.resources, true); phase = 2;
        }
        boolean publish = phase == 2 && Integer.compareUnsigned(tile, total) >= 0;
        if (publish) {
            var oldCurrent = current; current = next; next = work; work = oldCurrent;
            tile = 512; duration = total == 1 || forceSingleFrame ? 0 : frame - blendStart; blendStart = frame;
        }
        return new Update<>(new Cache<>(current, next, work, tile, phase, lastFilter, blendStart, duration), stages, publish);
    }

    private static <R> Stage<R> filterStage(String id, R resources, Extent traceExtent, float sunElevation) {
        int z = traceExtent.depth;
        if (id.equals("114")) {
            int width = traceExtent.width / 4, height = traceExtent.height / 4;
            return new Stage<>(id, resources, Map.of("TexSize", new double[]{width, height, z}),
                    (width + 7) / 8, (height + 7) / 8, (z + 7) / 8, false);
        }
        int width = traceExtent.width / 2, height = traceExtent.height / 2;
        int radius = 1 + ((90f - sunElevation) * 0.3333333432674408f > 2f ? 1 : 0);
        boolean x = id.equals("115");
        var inputs = Map.of("bBlur", new double[]{1}, "RadiusGround", new double[]{radius},
                "SigmaGround", new double[]{(float)radius}, "BlurDir", x ? new double[]{1, 0} : new double[]{0, 1},
                "TexSize", new double[]{width, height, z});
        return new Stage<>(id, resources, inputs, x ? (width + 255) / 256 : width,
                x ? height : (height + 255) / 256, (z + 3) / 4, false);
    }

    public static float blend(int frame, int start, int duration) {
        if (Integer.compareUnsigned(frame, start) < 0) return 1f;
        int elapsed = frame - start;
        if (Integer.compareUnsigned(elapsed, duration) >= 0) return 1f;
        return Math.clamp((float)Integer.toUnsignedLong(elapsed) / (float)Integer.toUnsignedLong(duration), 0f, 1f);
    }

    public static <R> Map<String, double[]> samplingInputs(Geometry geometry, Cache<R> level0, Cache<R> level1, int frame) {
        var inputs = new LinkedHashMap<>(geometry.inputs());
        float blend0 = samplingLevel(inputs, level0, 0, frame), blend1 = samplingLevel(inputs, level1, 1, frame);
        inputs.put("nativeFrame.ShadowTexturesForSampling_Blend", new double[]{blend0, blend1, 0, 0});
        return Map.copyOf(inputs);
    }

    private static <R> float samplingLevel(Map<String, double[]> inputs, Cache<R> cache, int level, int frame) {
        float factor = blend(frame, cache.blendStart, cache.blendDuration);
        if (factor < 1 && !cache.current.completed || factor > 0 && !cache.next.completed)
            throw new IllegalStateException("Source shadow sampling requires completed weighted records");
        inputs.put("nativeFrame.ShadowTraceConstants_PlaneSpaceCenterLV" + level, cache.current.center.values());
        inputs.put("nativeFrame.ShadowTexturesForSampling_PlaneSpaceCenterLV" + level + "_Next", cache.next.center.values());
        return factor;
    }

    private static Map<String, double[]> copyInputs(Map<String, double[]> source) {
        var result = new LinkedHashMap<String, double[]>();
        source.forEach((key, value) -> result.put(key, value.clone()));
        return Map.copyOf(result);
    }

    private static void requireCascade(int cascade) {
        if (cascade != 0 && cascade != 1) throw new IllegalArgumentException("Source shadow cascade must be 0 or 1");
    }
}
