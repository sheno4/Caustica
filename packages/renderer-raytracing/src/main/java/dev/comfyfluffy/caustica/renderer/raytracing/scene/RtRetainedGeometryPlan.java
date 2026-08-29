package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.engine.program.ProgramResolution;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.RtAccel;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.RetainedGeometryRecordData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.RetainedGeometryRecordData.Float4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Pure planning and CPU ABI packing for native retained geometry. */
public final class RtRetainedGeometryPlan {
    public static final int HIT_RECORDS_PER_GEOMETRY = 2;
    public static final int RECORD_BYTES = RetainedGeometryRecordData.BYTE_SIZE;

    public static final int SURFACE_IMPLEMENTATION_OFFSET = 0;
    public static final int COVERAGE_IMPLEMENTATION_OFFSET = 4;
    public static final int VOLUME_IMPLEMENTATION_OFFSET = 8;
    public static final int FLAGS_OFFSET = 12;
    public static final int SURFACE_BINDING_OFFSET = 16;
    public static final int VOLUME_BINDING_OFFSET = 24;
    public static final int INSTANCE_DATA_OFFSET = 32;
    public static final int ALPHA_CUTOFF_OFFSET = 40;
    public static final int CURRENT_TRANSFORM_OFFSET = 48;
    public static final int PREVIOUS_TRANSFORM_OFFSET = 96;
    public static final int EMITTER_INDEX_ADDRESS_OFFSET = 144;
    public static final int EMITTER_PRIMITIVE_BASE_OFFSET = 152;

    public static final int HAS_SURFACE = 1;
    public static final int HAS_VOLUME = 2;
    public static final int CUTOUT = 4;

    private RtRetainedGeometryPlan() { }

    public static List<RtAccel.GeometryRange> blasRanges(MeshBuild<?> build) {
        return build.geometries().stream().map(geometry -> new RtAccel.GeometryRange(
                geometry.firstIndex(), geometry.indexCount(), isOpaque(geometry))).toList();
    }

    /** A BLAS can be shared only when every acceleration-structure-visible input is identical. */
    public static boolean canReuseBlas(MeshBuild<?> previous, MeshBuild<?> next) {
        return previous.positions().equals(next.positions())
                && previous.indices().equals(next.indices())
                && previous.vertexCount() == next.vertexCount()
                && previous.indexRevision() != null
                && previous.indexRevision().equals(next.indexRevision())
                && blasRanges(previous).equals(blasRanges(next));
    }

    public static List<GeometryRecord> records(RetainedSceneSnapshot.Mesh mesh,
                                               RetainedSceneSnapshot.Instance placement,
                                               GeometryTransform previousTransform) {
        List<GeometryRecord> records = new ArrayList<>(mesh.build().geometries().size());
        for (int i = 0; i < mesh.build().geometries().size(); i++) {
            MeshBuild.Geometry<?> geometry = mesh.build().geometries().get(i);
            RetainedSceneSnapshot.GeometryPrograms programs = mesh.geometryPrograms().get(i);
            int surface = surfaceIndex(programs.surface());
            boolean cutout = geometry.surface() != null
                    && geometry.surface().coverage() instanceof MeshBuild.CoveragePolicy.Cutout;
            int coverage = cutout ? surface : 0;
            int volume = volumeIndex(programs.volume());
            int flags = (geometry.surface() == null ? 0 : HAS_SURFACE)
                    | (geometry.volume() == null ? 0 : HAS_VOLUME)
                    | (cutout ? CUTOUT : 0);
            float alphaCutoff = cutout
                    ? ((MeshBuild.CoveragePolicy.Cutout) geometry.surface().coverage()).alphaCutoff()
                    : 0.0f;
            records.add(new GeometryRecord(surface, coverage, volume, flags,
                    geometry.surface() == null ? 0L : geometry.surface().bindingData().bits(),
                    geometry.volume() == null ? 0L : geometry.volume().bindingData().bits(),
                    placement.instanceData().bits(), alphaCutoff, placement.transform(), previousTransform,
                    0L, 0));
        }
        return List.copyOf(records);
    }

    public static ByteBuffer pack(List<GeometryRecord> records, SceneOrigin origin) {
        ByteBuffer packed = ByteBuffer.allocate(Math.multiplyExact(records.size(), RECORD_BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (GeometryRecord record : records) {
            int base = packed.position();
            float[] current = record.currentTransform().relativeTo(origin.x(), origin.y(), origin.z());
            float[] previous = record.previousTransform().relativeTo(origin.x(), origin.y(), origin.z());
            new RetainedGeometryRecordData(record.surfaceImplementation(), record.coverageImplementation(),
                    record.volumeImplementation(), record.flags(), record.surfaceBinding(),
                    record.volumeBinding(), record.instanceData(), record.alphaCutoff(), 0,
                    row(current, 0), row(current, 4), row(current, 8),
                    row(previous, 0), row(previous, 4), row(previous, 8),
                    record.emitterIndexAddress(), record.emitterPrimitiveBase(), 0)
                    .write(packed.slice(base, RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN));
            packed.position(base + RECORD_BYTES);
        }
        return packed.flip();
    }

    public static List<HitGroup> hitGroups(List<GeometryRecord> records) {
        List<HitGroup> groups = new ArrayList<>(Math.multiplyExact(records.size(), HIT_RECORDS_PER_GEOMETRY));
        for (GeometryRecord record : records) {
            boolean cutout = (record.flags() & CUTOUT) != 0 && (record.flags() & HAS_VOLUME) == 0;
            groups.add(cutout ? HitGroup.RADIANCE_CUTOUT : HitGroup.RADIANCE_OPAQUE);
            groups.add(cutout ? HitGroup.SHADOW_CUTOUT : HitGroup.SHADOW_OPAQUE);
        }
        return List.copyOf(groups);
    }

    private static Float4 row(float[] values, int offset) {
        return new Float4(values[offset], values[offset + 1], values[offset + 2], values[offset + 3]);
    }

    private static boolean isOpaque(MeshBuild.Geometry<?> geometry) {
        return geometry.volume() != null || geometry.surface() == null
                || geometry.surface().coverage() instanceof MeshBuild.CoveragePolicy.Opaque;
    }

    private static int surfaceIndex(ProgramResolution.Surface resolution) {
        return resolution instanceof ProgramResolution.ActiveSurface active ? active.implementationIndex() : 0;
    }

    private static int volumeIndex(ProgramResolution.Volume resolution) {
        return resolution instanceof ProgramResolution.ActiveVolume active ? active.implementationIndex() : 0;
    }

    public record GeometryRecord(int surfaceImplementation, int coverageImplementation,
                                 int volumeImplementation, int flags, long surfaceBinding,
                                 long volumeBinding, long instanceData, float alphaCutoff,
                                 GeometryTransform currentTransform, GeometryTransform previousTransform,
                                 long emitterIndexAddress, int emitterPrimitiveBase) {
        public GeometryRecord(int surfaceImplementation, int coverageImplementation,
                              int volumeImplementation, int flags, long surfaceBinding,
                              long volumeBinding, long instanceData, float alphaCutoff,
                              GeometryTransform currentTransform, GeometryTransform previousTransform) {
            this(surfaceImplementation, coverageImplementation, volumeImplementation, flags,
                    surfaceBinding, volumeBinding, instanceData, alphaCutoff, currentTransform,
                    previousTransform, 0L, 0);
        }

        GeometryRecord withEmitterIndex(long address, int primitiveBase) {
            return new GeometryRecord(surfaceImplementation, coverageImplementation, volumeImplementation,
                    flags, surfaceBinding, volumeBinding, instanceData, alphaCutoff, currentTransform,
                    previousTransform, address, primitiveBase);
        }
    }

    public enum HitGroup {
        RADIANCE_OPAQUE,
        RADIANCE_CUTOUT,
        SHADOW_OPAQUE,
        SHADOW_CUTOUT
    }
}
