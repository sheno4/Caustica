package dev.comfyfluffy.caustica.minecraft.client.terrain;

import dev.comfyfluffy.caustica.minecraft.client.MinecraftOptions;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialClassification;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialKey;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialProfile;
import dev.comfyfluffy.caustica.minecraft.client.material.MinecraftMaterialClassifier;
import dev.comfyfluffy.caustica.minecraft.rendering.material.MinecraftMaterialLookup;
import dev.comfyfluffy.caustica.minecraft.rendering.terrain.MinecraftTerrainMesh;
import dev.comfyfluffy.caustica.minecraft.rendering.terrain.MinecraftTerrainMesh.Coverage;
import dev.comfyfluffy.caustica.minecraft.rendering.light.MinecraftTerrainEmitter;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialEmission;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialIds;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialResolution;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTopology;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftResourceIds;
import dev.comfyfluffy.caustica.support.ColorSpaces;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RtTerrainMesher {
    private static final Direction[] DIRECTIONS = Direction.values();
    /**
     * Per-worker capture state and reusable accumulators. Each job resets the captures and copies its
     * finished streams before another job reuses their backing arrays.
     */
    static final class WorkerTessState {
        final QuadCapture capture = new QuadCapture();
        final RandomSource blockRandom = RandomSource.createThreadLocalInstance(0L);
        final List<BlockStateModelPart> modelParts = new ArrayList<>();
        final FluidCapture fluidCapture = new FluidCapture();
        final SectionMesh mesh = new SectionMesh();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        void reset(BlockColors blockColors) {
            capture.blockColors = blockColors;
            capture.reset();
            fluidCapture.reset();
            mesh.reset();
        }
    }

    static final ThreadLocal<WorkerTessState> WORKER_TESS = ThreadLocal.withInitial(WorkerTessState::new);

    /**
     * Tessellates and packs a section on its worker using one captured material lookup. Mesh ordinals
     * and light extraction therefore use the same resource epoch. Empty sections return a null mesh.
     */
    static CpuSection buildCpuSection(BlockAndTintGetter region, BlockStateModelSet modelSet,
                                              RandomSource blockRandom, List<BlockStateModelPart> modelParts,
                                              QuadCapture capture,
                                              FluidStateModelSet fluidModels, FluidCapture fluidCapture,
                                              SectionMesh mesh, BlockPos.MutableBlockPos m,
                                              MinecraftMaterialLookup materials,
                                              int scx, int scy, int scz) {
        capture.materials = materials;
        fluidCapture.materials = materials;
        tessellate(region, modelSet, blockRandom, modelParts, capture,
                fluidModels, fluidCapture, mesh, m, scx, scy, scz);
        if (mesh.isEmpty()) {
            return new CpuSection(null, List.of());
        }
        // Material luminance and sampled footprint determine which quads contribute retained lights.
        var lights = new ArrayList<MinecraftTerrainEmitter>();
        collectLights(lights, mesh.geometry, CausticaConfig.get(MinecraftOptions.Rt.Lights.MIN_FILL_RATIO));
        PackedSection packed = packSection(mesh);
        return new CpuSection(packed.mesh(), remapLights(lights, packed.sourceToDestinationPrimitives()));
    }

    private static void collectLights(List<MinecraftTerrainEmitter> out, Geom geom, float minFillRatio) {
        if (geom != null && !geom.idx.isEmpty()) {
            RtLightCollector.collectClass(out, geom.verts, geom.prim, geom.cornerUv,
                    geom.lightSprites, geom.materialEmissions, minFillRatio);
        }
    }

    private static PackedSection packSection(SectionMesh mesh) {
        Geom geom = mesh.geometry();
        ArrayList<TriangleRouting> routing = new ArrayList<>(geom.surfaces.size());
        for (int triangle = 0; triangle < geom.surfaces.size(); triangle++) {
            TerrainSurface surface = geom.surfaces.get(triangle);
            var program = surface.material().material().equals(MinecraftMaterialIds.WATER)
                    ? MinecraftTerrainMesh.ProgramCategory.WATER
                    : MinecraftTerrainMesh.ProgramCategory.MATERIAL;
            routing.add(new TriangleRouting(program, surface.coverage(), 0.5f));
        }
        var packed = bucketTriangles(
                java.util.Arrays.copyOf(geom.idx.elements(), geom.idx.size()),
                java.util.Arrays.copyOf(geom.cornerUv.elements(), geom.cornerUv.size()),
                java.util.Arrays.copyOf(geom.prim.elements(), geom.prim.size()), routing);
        return new PackedSection(new MinecraftTerrainMesh(
                java.util.Arrays.copyOf(geom.verts.elements(), geom.verts.size()),
                packed.indices(), packed.cornerUvs(), packed.primitiveData(), packed.geometries(), 0L),
                packed.sourceToDestinationPrimitives());
    }

    static List<MinecraftTerrainEmitter> remapLights(List<MinecraftTerrainEmitter> source,
                                                    int[] sourceToDestinationPrimitives) {
        var remapped = new ArrayList<MinecraftTerrainEmitter>(source.size());
        for (var emitter : source) {
            int sourcePrimitive = emitter.firstPrimitive();
            int destinationPrimitive = sourceToDestinationPrimitives[sourcePrimitive];
            if (sourceToDestinationPrimitives[sourcePrimitive + 1] != destinationPrimitive + 1) {
                throw new IllegalStateException("terrain quad triangles must remain adjacent after routing");
            }
            remapped.add(new MinecraftTerrainEmitter(emitter.descriptor(), destinationPrimitive,
                    emitter.primitiveCount()));
        }
        remapped.sort(Comparator.comparingInt(MinecraftTerrainEmitter::firstPrimitive));
        return List.copyOf(remapped);
    }

    /** Vulkan routing shared by triangles that may occupy one contiguous acceleration-geometry range. */
    record TriangleRouting(MinecraftTerrainMesh.ProgramCategory program,
                           MinecraftTerrainMesh.Coverage coverage,
                           float alphaCutoff) { }

    /** Triangle streams packed in stable routing-bucket order. */
    private record PackedSection(MinecraftTerrainMesh mesh, int[] sourceToDestinationPrimitives) { }

    record PackedTriangles(int[] indices, float[] cornerUvs, float[] primitiveData,
                           List<MinecraftTerrainMesh.Geometry> geometries,
                           int[] sourceToDestinationPrimitives) { }

    static PackedTriangles bucketTriangles(int[] sourceIndices, float[] sourceCornerUvs,
                                           float[] sourcePrimitiveData, List<TriangleRouting> routing) {
        var buckets = new LinkedHashMap<TriangleRouting, IntArrayList>();
        for (int triangle = 0; triangle < routing.size(); triangle++) {
            buckets.computeIfAbsent(routing.get(triangle), ignored -> new IntArrayList()).add(triangle);
        }

        int[] indices = new int[sourceIndices.length];
        float[] cornerUvs = new float[sourceCornerUvs.length];
        float[] primitiveData = new float[sourcePrimitiveData.length];
        int[] sourceToDestinationPrimitives = new int[routing.size()];
        ArrayList<MinecraftTerrainMesh.Geometry> geometries = new ArrayList<>(buckets.size());
        int destinationTriangle = 0;
        for (var bucket : buckets.entrySet()) {
            int firstIndex = destinationTriangle * 3;
            IntArrayList sourceTriangles = bucket.getValue();
            for (int i = 0; i < sourceTriangles.size(); i++) {
                int sourceTriangle = sourceTriangles.getInt(i);
                System.arraycopy(sourceIndices, sourceTriangle * 3, indices, destinationTriangle * 3, 3);
                System.arraycopy(sourceCornerUvs, sourceTriangle * 6, cornerUvs, destinationTriangle * 6, 6);
                System.arraycopy(sourcePrimitiveData, sourceTriangle * MinecraftTerrainMesh.PRIMITIVE_FLOATS,
                        primitiveData, destinationTriangle * MinecraftTerrainMesh.PRIMITIVE_FLOATS,
                        MinecraftTerrainMesh.PRIMITIVE_FLOATS);
                sourceToDestinationPrimitives[sourceTriangle] = destinationTriangle;
                destinationTriangle++;
            }
            TriangleRouting route = bucket.getKey();
            geometries.add(new MinecraftTerrainMesh.Geometry(route.program(), route.coverage(), firstIndex,
                    sourceTriangles.size() * 3, route.alphaCutoff()));
        }
        return new PackedTriangles(indices, cornerUvs, primitiveData, geometries,
                sourceToDestinationPrimitives);
    }

    private static void tessellate(BlockAndTintGetter region, BlockStateModelSet modelSet,
                                   RandomSource blockRandom, List<BlockStateModelPart> modelParts,
                                   QuadCapture capture, FluidStateModelSet fluidModels, FluidCapture fluidCapture,
                                   SectionMesh mesh, BlockPos.MutableBlockPos m, int scx, int scy, int scz) {
        int sox = scx << 4, soy = scy << 4, soz = scz << 4;
        capture.cur = mesh;
        capture.view = region;
        fluidCapture.cur = mesh;
        for (int lx = 0; lx < 16; lx++) {
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = sox + lx, wy = soy + ly, wz = soz + lz;
                    m.set(wx, wy, wz);
                    BlockState state = region.getBlockState(m);
                    if (state.isAir()) {
                        continue;
                    }
                    // Fluids (water/lava, incl. waterlogged blocks): separate mesher, INVISIBLE render
                    // shape, so handled independently of the block model below. Emits section-local
                    // coords + atlas sprite UVs straight into the capturing consumer. Lava's block light
                    // (15) rides the emission channel (water emits 0).
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        fluidCapture.emission = state.getLightEmission() / 15f;
                        // Minecraft selects the named transmissive fluid material; lava stays the
                        // adapter's opaque emitter fallback.
                        fluidCapture.water = fluid.is(FluidTags.WATER);
                        RtFluidMesher.tesselate(region, m, fluidCapture, fluidModels, state, fluid);
                    }
                    if (state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }
                    BlockStateModel model = modelSet.get(state);
                    if (model == null) {
                        continue;
                    }
                    capture.state = state;
                    capture.pos = m;
                    Vec3 offset = state.getOffset(m);
                    capture.originX = lx + (float) offset.x;
                    capture.originY = ly + (float) offset.y;
                    capture.originZ = lz + (float) offset.z;
                    blockRandom.setSeed(state.getSeed(m));
                    modelParts.clear();
                    model.collectParts(blockRandom, modelParts);
                    for (BlockStateModelPart part : modelParts) {
                        for (BakedQuad quad : part.getQuads(null)) {
                            capture.putVanilla(quad);
                        }
                        for (Direction direction : DIRECTIONS) {
                            if (!capture.isCulled(direction)) {
                                for (BakedQuad quad : part.getQuads(direction)) {
                                    capture.putVanilla(quad);
                                }
                            }
                        }
                    }
                    capture.flushBlock(); // resolve coplanar ties (grass overlay / cross faces), then emit
                }
            }
        }
    }


    /** Pure-CPU worker result: tessellated mesh and CPU-only light metadata. */
    record CpuSection(MinecraftTerrainMesh mesh, List<MinecraftTerrainEmitter> lights) {
    }


    /** Transient CPU accumulator for one section's source-order mesh. */
    private static final class SectionMesh {
        final Geom geometry = new Geom(1216);

        Geom geometry() { return geometry; }

        boolean isEmpty() {
            return geometry.idx.isEmpty();
        }

        /** Clear the section while retaining array capacity for the next job on this worker. */
        void reset() {
            geometry.reset();
        }
    }

    private record TerrainMaterial(int materialIndex, ResourceId material, boolean textured) { }

    private record TerrainSurface(TerrainMaterial material, Coverage coverage) { }

    /** One geometry class's packed, section-local mesh data. */
    private static final class Geom {
        final FloatArrayList verts;
        final IntArrayList idx;
        // Three float2 UVs per triangle, in index-stream order. Upload converts these CPU lanes into
        // the primitive records consumed by the surface shader.
        final FloatArrayList cornerUv;
        // 12 CPU lanes/triangle: normal/emission float4, tint float4, material index, atlas presence,
        // then two reserved lanes.
        final FloatArrayList prim;
        final List<TerrainSurface> surfaces;
        // One sprite per triangle for CPU light extraction.
        final List<TextureAtlasSprite> lightSprites;
        final List<MinecraftMaterialEmission> materialEmissions;

        Geom(int triCapacity) {
            int cap = Math.max(2, triCapacity);
            int quadCapacity = (cap + 1) >>> 1;
            verts = new FloatArrayList(quadCapacity * 12); // 4 xyz vertices per quad
            idx = new IntArrayList(cap * 3);
            cornerUv = new FloatArrayList(cap * 6);
            prim = new FloatArrayList(cap * 12);
            surfaces = new ArrayList<>(cap);
            lightSprites = new ArrayList<>(cap);
            materialEmissions = new ArrayList<>(cap);
        }

        void reset() {
            verts.clear();       // fastutil clear() keeps the backing array
            idx.clear();
            cornerUv.clear();
            prim.clear();
            surfaces.clear();
            lightSprites.clear();
            materialEmissions.clear();
        }
    }

    /** Captures vanilla baked model quads into the current section's mesh. */
    private static final class QuadCapture {
        SectionMesh cur; // set before each block model emission
        MinecraftMaterialLookup materials;

        // BlockColors resolves biome tint before raster lighting, so extraction receives unlit albedo.
        BlockColors blockColors;
        BlockAndTintGetter view;
        BlockState state;
        BlockPos pos;
        float originX, originY, originZ;
        private final BlockPos.MutableBlockPos cullPos = new BlockPos.MutableBlockPos();

        // Coplanar-resolution: vanilla emits coincident quads that tie on depth in the BVH and flicker —
        // a block face's opaque base + its tinted cutout overlay (grass/snowy sides), and a cross model's
        // two-sided faces. put() buffers a block's quads here; flushBlock() (called per block) nudges all
        // but the first member of each coincident group outward along its own normal so each lands on its
        // own plane (base stays, overlay moves in front so its cutout reveals the base; cross back-face
        // separates from the front). Pooled — reset each block, never reallocated steady-state.
        private static final float OFFSET = 2.0e-4f;         // outward nudge (blocks) to break coplanar depth ties
        private static final float TRANSLUCENT_INSET = 2.0e-4f; // inward recess (blocks) for glass/ice vs coplanar neighbours
        private static final float COINCIDENT_EPS = 1.0e-4f; // verts this close are "the same" point
        private static final int RESOLVE_CAP = 128;          // skip the O(n^2) resolve for pathological blocks
        private final List<PendingQuad> pending = new ArrayList<>(8);
        private final Map<BlockState, MinecraftMaterialClassification> classifications = new IdentityHashMap<>();
        private int pendingCount;
        private int[] gidScratch = new int[0];

        /** Capture a vanilla baked quad before raster AO/directional lighting is applied. */
        private void putVanilla(BakedQuad quad) {
            PendingQuad q = acquire();
            for (int i = 0; i < 4; i++) {
                q.x[i] = quad.position(i).x() + originX;
                q.y[i] = quad.position(i).y() + originY;
                q.z[i] = quad.position(i).z() + originZ;
                q.uv[i] = quad.packedUV(i);
            }

            float ex1 = q.x[1] - q.x[0], ey1 = q.y[1] - q.y[0], ez1 = q.z[1] - q.z[0];
            float ex2 = q.x[2] - q.x[0], ey2 = q.y[2] - q.y[0], ez2 = q.z[2] - q.z[0];
            float nx = ey1 * ez2 - ez1 * ey2, ny = ez1 * ex2 - ex1 * ez2, nz = ex1 * ey2 - ey1 * ex2;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-6f) { nx /= len; ny /= len; nz /= len; }
            q.nx = nx; q.ny = ny; q.nz = nz;

            ChunkSectionLayer layer = quad.materialInfo().layer();
            q.cutout = layer != ChunkSectionLayer.SOLID;
            q.translucent = layer == ChunkSectionLayer.TRANSLUCENT;

            float tr = 1f;
            float tg = 1f;
            float tb = 1f;
            int tintIndex = quad.materialInfo().tintIndex();
            q.tinted = tintIndex >= 0;
            if (tintIndex >= 0 && blockColors != null && state != null) {
                BlockTintSource src = blockColors.getTintSource(state, tintIndex);
                if (src != null) {
                    int rgb = src.colorInWorld(state, view, pos);
                    float[] tint = ColorSpaces.srgbToAcesCg(
                            ((rgb >> 16) & 0xFF) * (1f / 255f),
                            ((rgb >> 8) & 0xFF) * (1f / 255f),
                            (rgb & 0xFF) * (1f / 255f));
                    tr = tint[0];
                    tg = tint[1];
                    tb = tint[2];
                }
            }
            q.tr = tr; q.tg = tg; q.tb = tb;

            q.emission = Math.max(quad.materialInfo().lightEmission(),
                    state != null ? state.getLightEmission() : 0) / 15f;
            TextureAtlasSprite sprite = quad.materialInfo().sprite();
            q.sprite = sprite;
            MinecraftMaterialClassification classification = classifications.computeIfAbsent(
                    state, MinecraftMaterialClassifier::classify);
            MinecraftMaterialKey key = new MinecraftMaterialKey(
                    MinecraftResourceIds.material(sprite), classification.geometry(), classification.profile(),
                    q.translucent ? MinecraftMaterialTopology.MEDIUM_BOUNDARY
                            : MinecraftMaterialTopology.SURFACE);
            MinecraftMaterialResolution terrainMaterial = materials.resolve(key);
            q.material = new TerrainMaterial(terrainMaterial.materialIndex(), terrainMaterial.material(),
                    true);
            q.coverage = q.translucent ? Coverage.STOCHASTIC
                    : q.cutout ? Coverage.CUTOUT : Coverage.OPAQUE;
            q.materialEmission = terrainMaterial.emission();
        }

        /** Returns true when vanilla's nominal face should be discarded. */
        private boolean isCulled(Direction direction) {
            if (direction == null) {
                return false;
            }
            BlockState neighbor = view.getBlockState(cullPos.setWithOffset(pos, direction));
            return !Block.shouldRenderFace(state, neighbor, direction);
        }

        /** Acquire a pooled PendingQuad for the current block (grown on demand, count reset by flushBlock). */
        private PendingQuad acquire() {
            if (pendingCount == pending.size()) {
                pending.add(new PendingQuad());
            }
            return pending.get(pendingCount++);
        }

        /** Reset the current section while retaining reusable backing storage. */
        void reset() {
            discardBlock();
            classifications.clear();
        }

        /** Drop the current block's buffered quads without emitting. */
        void discardBlock() {
            pendingCount = 0;
        }

        /** Resolve coplanar ties among the current block's quads, then emit them into the section classes. */
        void flushBlock() {
            int n = pendingCount;
            if (n == 0) {
                return;
            }
            if (n >= 2 && n <= RESOLVE_CAP) {
                resolveCoplanar(n);
            }
            for (int i = 0; i < n; i++) {
                emit(pending.get(i));
            }
            pendingCount = 0;
        }

        /**
         * Union coincident quads (same 4 corners, any winding) into groups, then within each group keep the
         * first member (the opaque/untinted base if present) in place and push the rest outward along their
         * own normals by {@link #OFFSET} × rank. Same-normal layers (grass base/overlay) fan out along one
         * direction; opposite-normal pairs (cross faces) separate because each moves along its own normal.
         */
        private void resolveCoplanar(int n) {
            int[] gid = gidScratch.length >= n ? gidScratch : (gidScratch = new int[n]);
            for (int i = 0; i < n; i++) {
                gid[i] = -1;
            }
            for (int i = 0; i < n; i++) {
                if (gid[i] != -1) {
                    continue;
                }
                gid[i] = i;
                for (int j = i + 1; j < n; j++) {
                    if (gid[j] == -1 && coincident(pending.get(i), pending.get(j))) {
                        gid[j] = i;
                    }
                }
            }
            for (int r = 0; r < n; r++) {
                if (gid[r] != r) {
                    continue; // not a group representative
                }
                int rank = 0;
                // Pass 1: bases (opaque + untinted) — the first stays put (rank 0), so the overlay lands in
                // front of it. Pass 2: overlays (cutout or tinted) — always pushed outward.
                for (int k = 0; k < n; k++) {
                    PendingQuad q = pending.get(k);
                    if (gid[k] == r && !(q.cutout || q.tinted)) {
                        if (rank > 0) {
                            offset(q, OFFSET * rank);
                        }
                        rank++;
                    }
                }
                for (int k = 0; k < n; k++) {
                    PendingQuad q = pending.get(k);
                    if (gid[k] == r && (q.cutout || q.tinted)) {
                        if (rank > 0) {
                            offset(q, OFFSET * rank);
                        }
                        rank++;
                    }
                }
            }
        }

        /** True if every corner of {@code a} coincides with a corner of {@code b} (same quad, any winding). */
        private static boolean coincident(PendingQuad a, PendingQuad b) {
            for (int k = 0; k < 4; k++) {
                boolean found = false;
                for (int m = 0; m < 4; m++) {
                    if (Math.abs(a.x[k] - b.x[m]) < COINCIDENT_EPS
                            && Math.abs(a.y[k] - b.y[m]) < COINCIDENT_EPS
                            && Math.abs(a.z[k] - b.z[m]) < COINCIDENT_EPS) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        }

        /** Shift all four of a quad's corners by {@code d} along its (outward) normal. */
        private static void offset(PendingQuad q, float d) {
            for (int v = 0; v < 4; v++) {
                q.x[v] += q.nx * d;
                q.y[v] += q.ny * d;
                q.z[v] += q.nz * d;
            }
        }

        /** Emit one resolved quad into its section class (2 triangles, corner UVs, per-prim records). */
        private void emit(PendingQuad q) {
            // Recess translucent (glass / ice) faces slightly into their own block. Vanilla culls a glass
            // face that touches a full solid block, but KEEPS the one touching a non-occluding neighbour
            // (slabs / stairs) — which lands exactly coplanar with that neighbour's face and z-fights. A tiny
            // inward inset makes the glass resolve consistently behind the neighbour's surface.
            if (q.translucent) {
                offset(q, -TRANSLUCENT_INSET);
            }
            Geom g = cur.geometry();
            int base = g.verts.size() / 3;
            for (int k = 0; k < 4; k++) {
                g.verts.add(q.x[k]);
                g.verts.add(q.y[k]);
                g.verts.add(q.z[k]);
            }
            IntArrayList idx = g.idx;
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);
            // Per-triangle corner UVs (primitive order matching the two triangles: 0,1,2 then 0,2,3).
            addTriUv(g, q.uv[0], q.uv[1], q.uv[2]);
            addTriUv(g, q.uv[0], q.uv[2], q.uv[3]);
            FloatArrayList prim = g.prim;
            for (int t = 0; t < 2; t++) {
                prim.add(q.nx);
                prim.add(q.ny);
                prim.add(q.nz);
                // normal.w is the primitive's state-derived emission strength in [0,1].
                prim.add(q.emission);
                prim.add(q.tr);
                prim.add(q.tg);
                prim.add(q.tb);
                prim.add(0f);
                prim.add(q.material.materialIndex());
                prim.add(q.material.textured() ? 1f : 0f);
                prim.add(0f); // aux0
                prim.add(0f); // aux1
                g.lightSprites.add(q.sprite);
                g.materialEmissions.add(q.materialEmission);
                g.surfaces.add(new TerrainSurface(q.material, q.coverage));
            }
        }
    }

    /** One block's buffered quad, awaiting coplanar resolution before it is emitted into a section class. */
    private static final class PendingQuad {
        final float[] x = new float[4], y = new float[4], z = new float[4];
        final long[] uv = new long[4];
        float nx, ny, nz;
        boolean cutout; // non-SOLID render layer (alpha-tested) — also an overlay candidate
        boolean translucent; // TRANSLUCENT layer (stained glass / ice): colored-transmission dielectric
        boolean tinted; // tintIndex >= 0 — the tinted member of a base+overlay pair
        float tr, tg, tb, emission;
        MinecraftMaterialEmission materialEmission;
        TerrainMaterial material;
        Coverage coverage;
        TextureAtlasSprite sprite;
    }

    /** Append one triangle's 3 corner UVs (6 floats) from packed atlas-space UVs. */
    private static void addTriUv(Geom g, long pa, long pb, long pc) {
        FloatArrayList c = g.cornerUv;
        c.add(Float.intBitsToFloat((int) (pa >>> 32)));
        c.add(Float.intBitsToFloat((int) pa));
        c.add(Float.intBitsToFloat((int) (pb >>> 32)));
        c.add(Float.intBitsToFloat((int) pb));
        c.add(Float.intBitsToFloat((int) (pc >>> 32)));
        c.add(Float.intBitsToFloat((int) pc));
    }

    /** Append one triangle's 3 corner UVs (6 floats) from float u,v pairs (fluid path). */
    private static void addTriUv(Geom g, float ua, float va, float ub, float vb, float uc, float vc) {
        FloatArrayList c = g.cornerUv;
        c.add(ua);
        c.add(va);
        c.add(ub);
        c.add(vb);
        c.add(uc);
        c.add(vc);
    }

    /**
     * Captures four section-local vertices per fluid face, computes its geometric normal, and emits
     * two triangles. Water uses the source vertex colors for absorption tint; lava uses its textures.
     */
    static final class FluidCapture implements RtFluidMesher.Output {
        SectionMesh cur;     // set before each section
        MinecraftMaterialLookup materials;
        MinecraftMaterialResolution faceMaterial;
        float emission;      // set per fluid block (lava = 1, water = 0)
        boolean water;       // set per fluid block: true for water (dielectric), false for lava
        private int n;
        private final float[] qx = new float[4], qy = new float[4], qz = new float[4], qu = new float[4], qv = new float[4];
        private final int[] qc = new int[4]; // per-vertex packed ARGB (vanilla bakes the biome water tint here)

        /** Reset per-job assembly state (a mid-quad meshing throw could leave a partial quad buffered). */
        void reset() {
            n = 0;
            faceMaterial = null;
        }

        @Override
        public void beginFace(ResourceId material) {
            faceMaterial = water ? materials.resolve(MinecraftMaterialIds.WATER)
                    : materials.resolve(new MinecraftMaterialKey(material, null,
                            MinecraftMaterialProfile.MEDIUM_ROUGH_DIELECTRIC,
                            MinecraftMaterialTopology.SURFACE));
        }

        @Override
        public void vertex(float x, float y, float z, int color, float u, float v) {
            qx[n] = x; qy[n] = y; qz[n] = z; qu[n] = u; qv[n] = v; qc[n] = color;
            if (++n == 4) {
                emitQuad();
                n = 0;
            }
        }

        private void emitQuad() {
            Geom g = cur.geometry();
            TerrainMaterial material = new TerrainMaterial(faceMaterial.materialIndex(),
                    faceMaterial.material(), !water);
            MinecraftMaterialEmission materialEmission = faceMaterial.emission();
            FloatArrayList verts = g.verts;
            IntArrayList idx = g.idx;
            int base = verts.size() / 3;
            for (int i = 0; i < 4; i++) {
                verts.add(qx[i]);
                verts.add(qy[i]);
                verts.add(qz[i]);
            }
            idx.add(base);
            idx.add(base + 1);
            idx.add(base + 2);
            idx.add(base);
            idx.add(base + 2);
            idx.add(base + 3);
            // Per-triangle corner UVs (primitive order: 0,1,2 then 0,2,3), matching the two triangles above.
            addTriUv(g, qu[0], qv[0], qu[1], qv[1], qu[2], qv[2]);
            addTriUv(g, qu[0], qv[0], qu[2], qv[2], qu[3], qv[3]);

            float ex1 = qx[1] - qx[0], ey1 = qy[1] - qy[0], ez1 = qz[1] - qz[0];
            float ex2 = qx[2] - qx[0], ey2 = qy[2] - qy[0], ez2 = qz[2] - qz[0];
            float nx = ey1 * ez2 - ez1 * ey2;
            float ny = ez1 * ex2 - ex1 * ez2;
            float nz = ex1 * ey2 - ey1 * ex2;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-6f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            // The average source vertex colour supplies water's biome absorption tint.
            // Lava's colour comes from its material textures.
            float tr = 1f, tg = 1f, tb = 1f;
            if (water) {
                int sr = 0, sg = 0, sb = 0;
                for (int i = 0; i < 4; i++) {
                    sr += (qc[i] >> 16) & 0xFF;
                    sg += (qc[i] >> 8) & 0xFF;
                    sb += qc[i] & 0xFF;
                }
                float[] tint = ColorSpaces.srgbToAcesCg(
                        sr / 1020f, sg / 1020f, sb / 1020f); // 4 vertices * 255
                tr = tint[0];
                tg = tint[1];
                tb = tint[2];
            }
            FloatArrayList prim = g.prim;
            for (int t = 0; t < 2; t++) { // one {normal+emission, tint, mat} record per triangle
                prim.add(nx);
                prim.add(ny);
                prim.add(nz);
                prim.add(emission);
                prim.add(tr);
                prim.add(tg);
                prim.add(tb);
                prim.add(0f);
                prim.add(material.materialIndex());
                prim.add(material.textured() ? 1f : 0f);
                prim.add(0f);
                prim.add(0f);
                g.lightSprites.add(null);
                g.materialEmissions.add(materialEmission);
                g.surfaces.add(new TerrainSurface(material, Coverage.OPAQUE));
            }
        }

    }

}
