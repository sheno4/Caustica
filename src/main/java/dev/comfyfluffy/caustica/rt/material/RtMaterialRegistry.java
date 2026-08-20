package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.api.gpu.GpuBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.gen.MaterialBindingData;
import dev.comfyfluffy.caustica.rt.gen.SurfaceMaterialData;
import dev.comfyfluffy.caustica.rt.gen.SurfaceMaterialData.Float4;
import dev.comfyfluffy.caustica.rt.gen.SurfaceMaterialData.Int4;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resource-epoch material registry shared by all geometry producers.
 *
 * <p>The compiled record is two tables. {@link SurfaceMaterialData} carries the closest-hit surface
 * parameters; {@link MaterialBindingData} carries the sixteen definition-owned bytes traversal reads.
 * Per-triangle texture and coverage facts live in {@code Prim.aux0}, so each definition has one immutable binding.
 *
 * <p>Workers read an immutable {@link MaterialEpochSnapshot}. Published records never mutate.
 */
public final class RtMaterialRegistry {

    // Canonical transport values compiled into SurfaceMaterial and MaterialBinding.
    public static final int TRANSPORT_SURFACE = 0;
    public static final int TRANSPORT_MEDIUM_BOUNDARY = 3;
    /** Visible fallback for unresolved, rejected, or out-of-range surface implementations. */
    public static final int ERROR_SURFACE_IMPLEMENTATION = 0;

    // Transmittance — how much light passes where the surface is present. Mirrors BINDING_* in Slang.
    private static final int BINDING_TRANSMISSIVE = MaterialBindingAbi.FLAG_TRANSMISSIVE;
    // MaterialBinding.packed0 = definition flags:8 | reserved:16 | surfaceImpl:8;
    // packed1 = coverageCutoff:8. The any-hit reads this aligned definition record beside Prim.aux0.

    // Coverage cutoffs, packed into MaterialBinding.packed1 so no any-hit branches on the producer.
    private static final float RUNTIME_TEXTURE_COVERAGE_CUTOFF = 0.1f;
    // Emission enters fp16 payload fields after shading, so the encoded range stops at the largest finite
    // half value rather than allowing an authored luminance to become infinity during conversion.
    private static final float MAX_EMISSION_LUMINANCE = 65504.0f;

    private volatile MaterialEpochSnapshot snapshot;
    private GpuBuffer bindingTable;
    private GpuBuffer surfaceTable;
    private long nextEpoch;
    private Map<ResourceId, Integer> namedMaterialIds = Map.of();
    private int runtimeFallbackId;

    RtMaterialRegistry() {
    }

    /** Build and atomically publish the registry for the current resource epoch. */
    public void rebuild(GpuContext ctx, List<MaterialDefinition> definitions,
                        SurfaceResolver surfaces, Set<ResourceId> availableSurfaces) {
        CompiledTables tables = new CompiledTables(1 + definitions.size());
        RtMaterialDesc fallbackDesc = new RtMaterialDesc(TRANSPORT_SURFACE,
                1.0f, 0.0f, 1.5f, 0.0f, 1.0f, ERROR_SURFACE_IMPLEMENTATION);
        int nextRuntimeFallbackId = tables.add(fallbackDesc,
                surfaceData(fallbackDesc, providerData(dev.comfyfluffy.caustica.api.provider.MaterialProviderData.ZERO),
                        new Float4(1.0f, 1.0f, 1.0f, 1.0f)),
                RUNTIME_TEXTURE_COVERAGE_CUTOFF);
        Map<ResourceId, Integer> nextNamedMaterialIds = new HashMap<>();
        for (MaterialDefinition definition : definitions) {
            if (nextNamedMaterialIds.containsKey(definition.id())) {
                throw new IllegalStateException("Duplicate submitted material " + definition.id());
            }
            int surfaceImplementation = resolveSurfaceImplementation(definition, surfaces);
            int definitionTransport = transport(definition.topology());
            RtMaterialDesc desc = new RtMaterialDesc(definitionTransport,
                    definition.specularRoughness(), definition.baseMetalness(), definition.specularIor(),
                    definition.transmissionWeight(), definition.transmissionColorR(), definition.transmissionColorG(),
                    definition.transmissionColorB(), definition.subsurfaceWeight(), definition.subsurfaceColorR(),
                    definition.subsurfaceColorG(), definition.subsurfaceColorB(),
                    definition.subsurfaceScatterAnisotropy(), definition.emissionColorR(),
                    definition.emissionColorG(), definition.emissionColorB(),
                    definition.emissionLuminanceCdM2(), surfaceImplementation);
            int id = tables.addDefinition(desc, definition);
            nextNamedMaterialIds.put(definition.id(), id);
        }

        int surfaceCount = tables.surfaces.size();
        int nextSurfaceCapacity = surfaceCount;
        int bindingCount = tables.bindings.size();
        int nextBindingCapacity = bindingCount;
        GpuBuffer nextSurfaceTable = createTable(ctx, nextSurfaceCapacity, SurfaceMaterialData.BYTE_SIZE,
                "surface material table");
        GpuBuffer nextBindingTable = null;
        try {
            writeRecords(nextSurfaceTable, tables.surfaces, SurfaceMaterialData.BYTE_SIZE,
                    SurfaceMaterialData::write);
            nextBindingTable = createTable(ctx, nextBindingCapacity, MaterialBindingData.BYTE_SIZE,
                    "material binding table");
            writeRecords(nextBindingTable, tables.bindings, MaterialBindingData.BYTE_SIZE,
                    MaterialBindingData::write);
        } catch (Throwable t) {
            if (nextBindingTable != null) nextBindingTable.destroy();
            nextSurfaceTable.destroy();
            throw t;
        }

        GpuBuffer oldBindingTable = bindingTable;
        GpuBuffer oldSurfaceTable = surfaceTable;
        long epoch = ++nextEpoch;
        byte[] sbtClasses = new byte[tables.bindings.size()];
        for (int i = 0; i < sbtClasses.length; i++) {
            sbtClasses[i] = (byte) sbtClassOf(tables.bindings.get(i));
        }
        MaterialEpochSnapshot next = new MaterialEpochSnapshot(epoch,
                Collections.unmodifiableMap(new HashMap<>(nextNamedMaterialIds)), availableSurfaces, tables.descriptions,
                sbtClasses);
        namedMaterialIds = Collections.unmodifiableMap(nextNamedMaterialIds);
        runtimeFallbackId = nextRuntimeFallbackId;
        bindingTable = nextBindingTable;
        surfaceTable = nextSurfaceTable;
        snapshot = next; // volatile publication: map and arrays are never mutated afterward
        if (oldBindingTable != null) oldBindingTable.destroy();
        if (oldSurfaceTable != null) oldSurfaceTable.destroy();
        CausticaMod.LOGGER.info("RT materials: epoch={}, surfaces={}, bindings={}, tableKiB={}",
                epoch, surfaceCount, bindingCount,
                ((long) nextSurfaceCapacity * SurfaceMaterialData.BYTE_SIZE
                        + (long) nextBindingCapacity * MaterialBindingData.BYTE_SIZE) / 1024);
    }

    private static GpuBuffer createTable(GpuContext ctx, int capacity, int stride, String label) {
        long byteSize = Math.multiplyExact((long) capacity, stride);
        if (byteSize > Integer.MAX_VALUE) {
            throw new IllegalStateException("RT " + label + " exceeds mapped-buffer limit: " + byteSize);
        }
        return ctx.createAsyncBuffer(byteSize, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, label);
    }

    private static <T> void writeRecords(GpuBuffer table, List<T> records, int stride,
                                         java.util.function.BiConsumer<T, ByteBuffer> writer) {
        ByteBuffer mapped = MemoryUtil.memByteBuffer(table.mapped(), records.size() * stride)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < records.size(); i++) {
            writer.accept(records.get(i), mapped.slice(i * stride, stride).order(ByteOrder.nativeOrder()));
        }
        table.flush();
    }

    public MaterialEpochSnapshot requireSnapshot() {
        MaterialEpochSnapshot current = snapshot;
        if (current == null) throw new IllegalStateException("RT materials are not prepared");
        return current;
    }

    /** Resolve a stable extension material name to the current resource epoch's private binding ID. */
    public int bindingId(ResourceId material) {
        Integer id = namedMaterialIds.get(material);
        if (id == null) throw new IllegalArgumentException("No submitted material named " + material);
        return id;
    }

    /** Address of the {@code MaterialBinding} table, indexed by the material ID geometry records carry. */
    public long bindingTableAddress() {
        GpuBuffer current = bindingTable;
        if (current == null) throw new IllegalStateException("RT material binding table is not uploaded");
        return current.deviceAddress();
    }

    /** Address of the {@code SurfaceMaterial} table, indexed by {@code MaterialBinding.surface}. */
    public long surfaceTableAddress() {
        GpuBuffer current = surfaceTable;
        if (current == null) throw new IllegalStateException("RT surface material table is not uploaded");
        return current.deviceAddress();
    }

    /** Neutral canonical material used by runtime-only textures. */
    public int runtimeFallbackId() {
        return runtimeFallbackId;
    }

    /** Returns the definition's base SBT class. Per-triangle non-opaque coverage uses the masked class. */
    public int sbtClassFor(int bindingId) {
        return requireSnapshot().sbtClassFor(bindingId);
    }

    private static int sbtClassOf(MaterialBindingData binding) {
        int flags = MaterialBindingAbi.flags(binding.packed0());
        return (flags & BINDING_TRANSMISSIVE) != 0 ? RtAccel.CLASS_TRANSMISSIVE : RtAccel.CLASS_OPAQUE;
    }

    /** Caller must ensure no in-flight trace references the current table. */
    public void destroy() {
        snapshot = null;
        namedMaterialIds = Map.of();
        runtimeFallbackId = 0;
        if (bindingTable != null) {
            bindingTable.destroy();
            bindingTable = null;
        }
        if (surfaceTable != null) {
            surfaceTable.destroy();
            surfaceTable = null;
        }
    }

    static int transport(MaterialTopology topology) {
        return topology == MaterialTopology.SURFACE ? TRANSPORT_SURFACE : TRANSPORT_MEDIUM_BOUNDARY;
    }

    static int resolveSurfaceImplementation(MaterialDefinition definition, SurfaceResolver surfaces) {
        int implementation = surfaces.indexOf(definition.surface());
        if (implementation >= 0) return implementation;
        CausticaMod.LOGGER.warn("Material definition {} names unregistered surface {}; using the error surface",
                definition.id(), definition.surface());
        return ERROR_SURFACE_IMPLEMENTATION;
    }

    @FunctionalInterface
    public interface SurfaceResolver {
        int indexOf(ResourceId surface);
    }

    /**
     * The compiled tables under construction during a rebuild. Every definition gets one binding while
     * content-equal surface records share one surface-table index.
     */
    static final class CompiledTables {
        final List<SurfaceMaterialData> surfaces;
        private final Map<SurfaceMaterialData, Integer> surfaceIds;
        final List<MaterialBindingData> bindings;
        final List<RtMaterialDesc> descriptions;

        CompiledTables(int expected) {
            surfaces = new ArrayList<>(expected);
            surfaceIds = new HashMap<>(expected);
            bindings = new ArrayList<>(expected);
            descriptions = new ArrayList<>(expected);
        }

        int add(RtMaterialDesc desc, SurfaceMaterialData surface,
                float coverageCutoff) {
            int surfaceId = internSurface(surface);
            return append(binding(surfaceId, desc, coverageCutoff), desc);
        }

        int addDefinition(RtMaterialDesc desc, MaterialDefinition definition) {
            int surfaceId = internSurface(surfaceDefinition(desc, definition));
            return append(binding(surfaceId, desc, definition.alphaCutoff()), desc);
        }

        private int internSurface(SurfaceMaterialData surface) {
            Integer current = surfaceIds.get(surface);
            if (current != null) return current;
            int id = surfaces.size();
            surfaces.add(surface);
            surfaceIds.put(surface, id);
            return id;
        }

        private int append(MaterialBindingData binding, RtMaterialDesc desc) {
            int id = bindings.size();
            bindings.add(binding);
            descriptions.add(desc);
            return id;
        }
    }

    private static SurfaceMaterialData surfaceDefinition(RtMaterialDesc desc, MaterialDefinition definition) {
        return surfaceData(desc, providerData(definition.providerData()),
                new Float4(definition.baseColorR(), definition.baseColorG(), definition.baseColorB(), 1.0f));
    }

    private static SurfaceMaterialData surfaceData(RtMaterialDesc desc,
                                                   SurfaceMaterialData.MaterialProviderData providerData,
                                                   Float4 baseColorFactor) {
        return new SurfaceMaterialData(providerData, desc.specularRoughness(), desc.baseMetalness(),
                desc.specularIor(), desc.transmissionWeight(), baseColorFactor,
                new Float4(desc.transmissionColorR(), desc.transmissionColorG(),
                        desc.transmissionColorB(), desc.subsurfaceWeight()),
                new Float4(desc.subsurfaceColorR(), desc.subsurfaceColorG(),
                        desc.subsurfaceColorB(), desc.subsurfaceScatterAnisotropy()),
                new Float4(desc.emissionColorR(), desc.emissionColorG(), desc.emissionColorB(),
                        Math.min(MAX_EMISSION_LUMINANCE, desc.emissionLuminance())));
    }

    private static SurfaceMaterialData.MaterialProviderData providerData(
            dev.comfyfluffy.caustica.api.provider.MaterialProviderData source) {
        return new SurfaceMaterialData.MaterialProviderData(
                new Int4(source.word(0), source.word(1), source.word(2), source.word(3)),
                new Int4(source.word(4), source.word(5), source.word(6), source.word(7)),
                new Int4(source.word(8), source.word(9), source.word(10), source.word(11)));
    }

    /**
     * The immutable traversal binding for one definition. Geometry carries texture and coverage in Prim.aux0.
     */
    private static MaterialBindingData binding(int surfaceId, RtMaterialDesc desc, float coverageCutoff) {
        int flags = 0;
        switch (desc.transport()) {
            case TRANSPORT_MEDIUM_BOUNDARY -> {
                flags = BINDING_TRANSMISSIVE;
            }
            default -> {
            }
        }
        return new MaterialBindingData(
                MaterialBindingAbi.pack(flags, desc.surfaceImplementation()), surfaceId,
                0, MaterialBindingAbi.packCoverageCutoff(coverageCutoff));
    }

}
