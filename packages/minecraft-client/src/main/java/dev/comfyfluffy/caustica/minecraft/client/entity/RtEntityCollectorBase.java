package dev.comfyfluffy.caustica.minecraft.client.entity;

import dev.comfyfluffy.caustica.minecraft.rendering.entity.MinecraftEntityMesh;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.minecraft.client.mixin.ModelPartAccessor;
import dev.comfyfluffy.caustica.minecraft.client.mixin.RenderSetupAccessor;
import dev.comfyfluffy.caustica.minecraft.client.mixin.RenderTypeAccessor;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftTelemetry;
import dev.comfyfluffy.caustica.minecraft.client.MinecraftResourceIds;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialIds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;


import java.util.List;
import java.util.ArrayList;

/**
 * An entity submission sink that intercepts supported entity submissions and renders them straight
 * into an {@link RtEntityCapture}, reusing vanilla's posing and animation. Models, items, blocks, text,
 * custom quads, and line primitives are captured; screen-space effects and debug geometry are ignored.
 *
 * <p>Driven once per entity per frame: {@link #begin} sets the capture, then {@code
 * EntityRenderDispatcher.submit} fans out into {@code submitModel} here. Reused across entities.
 */
class RtEntityCollectorBase {
    private final RtEntityTextures textures;
    private final MinecraftTelemetry.Instrumentation instrumentation;

    RtEntityCollectorBase(RtEntityTextures textures, MinecraftTelemetry.Instrumentation instrumentation) {
        this.textures = java.util.Objects.requireNonNull(textures, "textures");
        this.instrumentation = java.util.Objects.requireNonNull(instrumentation, "instrumentation");
    }
    private static final Direction[] DIRECTIONS = Direction.values();
    // Vanilla leash constants (LeashFeatureRenderer.LEASH_RENDER_STEPS / LEASH_WIDTH).
    private static final int LEASH_STEPS = 24;
    private static final float LEASH_WIDTH = 0.05f;
    // Shared all-zero UV quad for untextured geometry (leash/line ribbons on the white slot).
    private static final float[] ZERO_UV = new float[4];
    private static final MinecraftEntityMesh.Material END_PORTAL_MATERIAL = new MinecraftEntityMesh.Material(
            MinecraftMaterialIds.END_PORTAL, null, MinecraftEntityMesh.Program.PORTAL);

    private RtEntityCapture capture;
    private boolean profileDynamicEntity;
    private final RtCuboidEmitter cuboidEmitter = new RtCuboidEmitter();
    // Set by order(int) for the very next submitModel call (banner/shield pattern-layer stacking), then
    // consumed. Baked-quad paths (addQuad) don't use ordering and always reset the capture's order to 0.
    private int pendingOrder;
    private final RtTextVertexConsumer textVertexConsumer = new RtTextVertexConsumer();
    private final TextGlyphVisitor textGlyphVisitor = new TextGlyphVisitor();
    private final RtCustomQuadVertexConsumer customQuadVertexConsumer = new RtCustomQuadVertexConsumer();
    private final RtLineVertexConsumer lineVertexConsumer = new RtLineVertexConsumer();
    private final float[] meshX = new float[4], meshY = new float[4], meshZ = new float[4];
    private final Vector3f meshPos = new Vector3f();
    // Vanilla already computes the Glowing-effect outline colour (opaque team colour, or 0 when not
    // glowing) per submitModel call — see EntityRenderer.extractCommon's outlineColor. Every submitModel
    // call for one entity carries the same value, so the last non-zero one seen this entity is enough.
    private int outlineColor;

    /**
     * Point the collector at the capture buffer for the next {@code dispatcher.submit}, resetting the
     * outline colour for the entity about to be captured. The end-of-entity {@code begin(null)} detach
     * call must NOT reset it — {@link RtEntities} reads {@link #outlineColor()} after that detach.
     */
    public void begin(RtEntityCapture capture, boolean profileDynamicEntity) {
        this.capture = capture;
        this.profileDynamicEntity = capture != null && profileDynamicEntity;
        if (capture != null) {
            this.outlineColor = 0;
            this.pendingOrder = 0;
        }
    }

    /** This entity's Glowing-effect outline colour (opaque ARGB), or 0 if it isn't glowing. */
    public int outlineColor() {
        return outlineColor;
    }

    /** Release model/resource-pack-owned CPU caches after reload or RT shutdown. */
    public void clearCaches() {
        cuboidEmitter.clear();
    }

    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                int lightCoords, int overlayCoords, int tintedColor, TextureAtlasSprite sprite,
                                int outlineColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        if (capture == null) {
            return;
        }
        if (outlineColor != 0) {
            this.outlineColor = outlineColor;
        }
        // Vanilla gives the base-colour banner pass order 0 even though it is drawn after an uncoloured,
        // exactly coplanar cloth pass. Move every banner-pattern pass out one rank: base colour becomes 1,
        // and the explicitly ordered emblem layers become 2+. Otherwise the BVH can select white cloth.
        capture.currentOrder = pendingOrder + (isBannerPattern(renderType) ? 1 : 0);
        pendingOrder = 0;
        if (profileDynamicEntity) {
            instrumentation.count("entityModelSubmissions", 1);
        }
        long materialStart = profileDynamicEntity ? instrumentation.startStage() : 0L;
        boolean stochasticAlpha = isTranslucent(renderType);
        // Block-entity models texture from an atlas sprite; mobs use a standalone texture.
        try {
            capture.currentCoverage = stochasticAlpha ? MinecraftEntityMesh.Coverage.STOCHASTIC
                    : hasCutoutDefine(renderType) ? MinecraftEntityMesh.Coverage.CUTOUT : MinecraftEntityMesh.Coverage.OPAQUE;
            if (sprite != null) {
                capture.setUvRemap(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
                capture.currentMaterial = spriteMaterial(sprite,
                        MinecraftEntityMesh.MaterialProfile.ROUGH_DIELECTRIC, false);
            } else {
                capture.currentMaterial = standaloneMaterial(renderType);
                if (isEndPortal(renderType)) capture.currentCoverage = MinecraftEntityMesh.Coverage.OPAQUE;
                capture.clearUvRemap();
            }
        } finally {
            instrumentation.endStage("entity.capture.submit.material", materialStart);
        }
        // Pose the model from its render state (idempotent re-pose; mirrors what the renderer does for
        // its feature layers), then render the posed parts into the capture. renderToBuffer applies the
        // PoseStack to every vertex/normal, so the capture receives world-/camera-relative geometry.
        long setupStart = profileDynamicEntity ? instrumentation.startStage() : 0L;
        try {
            model.setupAnim(state);
        } finally {
            instrumentation.endStage("entity.capture.submit.setupAnim", setupStart);
        }
        if (profileDynamicEntity && instrumentation.enabled()) {
            long metricsStart = instrumentation.startStage();
            try {
                instrumentation.count("entityCuboids", countVisibleCuboids(model.root()));
            } finally {
                instrumentation.endStage("entity.capture.submit.metrics", metricsStart);
            }
        }
        int color = tintedColor == 0 ? -1 : tintedColor; // vanilla uses 0 as the no-tint sentinel in some submit paths
        int vertStart = capture.verts.size();
        int idxStart = capture.idx.size();
        RtCuboidEmitter.PartTemplate directTemplate = cuboidEmitter.prepare(model);
        long directCubeCounts = 0L;
        long drawStart = profileDynamicEntity ? instrumentation.startStage() : 0L;
        try {
            if (directTemplate != null) {
                directCubeCounts = cuboidEmitter.emit(directTemplate, poseStack, capture, color);
            } else {
                model.renderToBuffer(poseStack, capture, lightCoords, overlayCoords, color);
            }
        } finally {
            instrumentation.endStage(directTemplate != null
                    ? "entity.capture.submit.modelDraw.direct"
                    : "entity.capture.submit.modelDraw.fallback", drawStart);
            instrumentation.endStage("entity.capture.submit.modelDraw", drawStart);
        }
        int addedVertices = (capture.verts.size() - vertStart) / 3;
        int addedQuads = (capture.idx.size() - idxStart) / 6;
        if (profileDynamicEntity) {
            instrumentation.count("entityModelVertices", addedVertices);
            instrumentation.count("entityModelQuads", addedQuads);
            instrumentation.count(directTemplate != null ? "entityDirectSubmissions" : "entityDirectFallbacks", 1);
            if (directTemplate != null) {
                instrumentation.count("entityDirectVertices", addedVertices);
                instrumentation.count("entityDirectQuads", addedQuads);
                instrumentation.count("entitySpecializedCuboids", directCubeCounts >>> 32);
                instrumentation.count("entityGenericCuboids", directCubeCounts & 0xffffffffL);
            }
        }
    }

    private static int countVisibleCuboids(ModelPart part) {
        if (!part.visible) {
            return 0;
        }
        ModelPartAccessor access = (ModelPartAccessor) (Object) part;
        int count = part.skipDraw ? 0 : access.caustica$cubes().size();
        for (ModelPart child : access.caustica$children().values()) {
            count += countVisibleCuboids(child);
        }
        return count;
    }

    protected final void setOrder(int order) {
        // Single un-ordered capture sink reused synchronously (no queuing): the caller always issues the
        // very next submission immediately after requesting an order, so stashing it for that one
        // submitModel call (see there) is enough to recover banner/shield pattern-layer stacking.
        pendingOrder = order;
    }

    /** Capture a list of baked quads (items / block models), each textured from its sprite's atlas. */
    private void addQuads(Matrix4f pose, List<BakedQuad> quads, int[] tintLayers) {
        int idxStart = capture.idx.size();
        long started = profileDynamicEntity ? instrumentation.startStage() : 0L;
        try {
            for (BakedQuad q : quads) {
                addQuad(pose, q, tintLayers);
            }
        } finally {
            instrumentation.endStage("entity.capture.submit.bakedQuads", started);
            countBakedOutput(idxStart);
        }
    }

    private void countBakedOutput(int idxStart) {
        if (!profileDynamicEntity) {
            return;
        }
        int quads = (capture.idx.size() - idxStart) / 6;
        instrumentation.count("entityBakedQuads", quads);
        instrumentation.count("entityBakedVertices", (long) quads * 4L);
    }

    /** Capture one baked quad with its atlas texture and source-level surface selector. */
    private void addQuad(Matrix4f pose, BakedQuad q, int[] tintLayers) {
        TextureAtlasSprite sprite = q.materialInfo().sprite();
        // Baked item quads retain the block model's material layer. Mirror terrain's classification so
        // dropped and held translucent block items (glass, ice, etc.) use the thin-dielectric variant
        // instead of the opaque DEFAULT variant. No BlockState reaches submitItem, so the layer is the
        // authoritative semantic available here; glass-model roughness/IOR are profile-independent.
        ChunkSectionLayer layer = q.materialInfo().layer();
        boolean transmissive = layer == ChunkSectionLayer.TRANSLUCENT;
        boolean cutout = !transmissive && layer != ChunkSectionLayer.SOLID;
        capture.currentMaterial = spriteMaterial(sprite, transmissive ? MinecraftEntityMesh.MaterialProfile.SMOOTH_DIELECTRIC
                        : MinecraftEntityMesh.MaterialProfile.ROUGH_DIELECTRIC,
                transmissive);
        capture.currentCoverage = transmissive ? MinecraftEntityMesh.Coverage.STOCHASTIC
                : cutout ? MinecraftEntityMesh.Coverage.CUTOUT : MinecraftEntityMesh.Coverage.OPAQUE;
        capture.currentOrder = 0; // baked-quad paths never stack decal layers
        capture.addBakedQuad(pose, q, tintColor(q.materialInfo().tintIndex(), tintLayers));
    }

    /** Preserve the block material selector and atlas identity for upload-time resolution. */
    private MinecraftEntityMesh.Material spriteMaterial(TextureAtlasSprite sprite,
                                                         MinecraftEntityMesh.MaterialProfile profile,
                                                         boolean transmissive) {
        if (sprite == null) return missingMaterial();
        MinecraftEntityMesh.Texture texture = textures.contributeAtlas(sprite.atlasLocation());
        if (TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())) {
            return new MinecraftEntityMesh.Material(MinecraftResourceIds.material(sprite),
                    texture, MinecraftEntityMesh.Program.MATERIAL, profile, transmissive);
        }
        return new MinecraftEntityMesh.Material(MinecraftMaterialIds.PARTICLE_BILLBOARD,
                texture, MinecraftEntityMesh.Program.MATERIAL);
    }

    private MinecraftEntityMesh.Material standaloneMaterial(RenderType renderType) {
        if (isEndPortal(renderType)) return END_PORTAL_MATERIAL;
        var texture = textures.contribute(renderType);
        if (texture == null) return missingMaterial();
        return standaloneMaterial(texture);
    }

    static MinecraftEntityMesh.Material standaloneMaterial(MinecraftEntityMesh.Texture texture) {
        return new MinecraftEntityMesh.Material(texture.resource(), texture, MinecraftEntityMesh.Program.MATERIAL);
    }

    private static boolean isEndPortal(RenderType renderType) {
        return renderType == RenderTypes.endPortal() || renderType == RenderTypes.endGateway();
    }

    private static MinecraftEntityMesh.Material missingMaterial() {
        return new MinecraftEntityMesh.Material(MinecraftMaterialIds.PARTICLE_BILLBOARD, null,
                MinecraftEntityMesh.Program.MATERIAL);
    }

    /** Whether a render type is alpha-blended (translucent) — its pipeline's color target has a blend
     *  function. Cutout/solid have none. Drives stochastic entity transparency in world.rahit. */
    private static boolean isTranslucent(RenderType renderType) {
        if (renderType == null) {
            return false;
        }
        // RenderSetup is final, so the accessor cast goes through Object (the interface is mixed in at
        // runtime), mirroring RtEntityTextures#textureLocation.
        Object setup = ((RenderTypeAccessor) renderType).caustica$state();
        RenderPipeline pipeline = ((RenderSetupAccessor) setup).caustica$pipeline();
        ColorTargetState cts = pipeline.getColorTargetState();
        return cts != null && cts.blendFunction().isPresent();
    }

    /**
     * True when a render type's pipeline carries the vanilla {@code ALPHA_CUTOUT} shader define — a
     * genuinely masked (alpha-tested) submission, as opposed to blended (stochastic) or fully opaque.
     * Matching the define is more robust than matching pipeline names and also works for mod-provided
     * RenderPipelines. Together with blend state, it selects the source coverage mode.
     *
     * <p>A submission with no render type to inspect is treated as masked: coverage now has to be asked
     * for, and an unknown submission losing its alpha test shows up as solid quads, while a redundant
     * alpha test only costs an any-hit.
     */
    private static boolean hasCutoutDefine(RenderType renderType) {
        if (renderType == null) {
            return true;
        }
        Object setup = ((RenderTypeAccessor) renderType).caustica$state();
        RenderPipeline pipeline = ((RenderSetupAccessor) setup).caustica$pipeline();
        return pipeline.getShaderDefines().values().containsKey("ALPHA_CUTOUT")
                || pipeline.getShaderDefines().flags().contains("ALPHA_CUTOUT");
    }

    /** Read the draw topology so custom triangle effects are never mis-grouped as RT quads. */
    private static PrimitiveTopology primitiveTopology(RenderType renderType) {
        if (renderType == null) {
            return null;
        }
        Object setup = ((RenderTypeAccessor) renderType).caustica$state();
        return ((RenderSetupAccessor) setup).caustica$pipeline().getPrimitiveTopology();
    }

    private static boolean isBannerPattern(RenderType renderType) {
        if (renderType == null) {
            return false;
        }
        Object setup = ((RenderTypeAccessor) renderType).caustica$state();
        RenderPipeline pipeline = ((RenderSetupAccessor) setup).caustica$pipeline();
        return "minecraft:pipeline/banner_pattern".equals(pipeline.getLocation().toString());
    }

    /** Resolve a quad's tint colour from its tint index + the submission's tint layers (white if untinted). */
    private static int tintColor(int tintIndex, int[] tintLayers) {
        if (tintIndex < 0 || tintLayers == null || tintIndex >= tintLayers.length) {
            return -1; // white
        }
        return tintLayers[tintIndex] | 0xFF000000; // force opaque; capture uses only the rgb
    }

    /** Capture a contained block-model display through vanilla's baked model parts. */
    public void captureBlockState(BlockState blockState, Matrix4fc transform, PoseStack poseStack) {
        if (capture == null || blockState.isAir()) {
            return;
        }
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(blockState);
        if (model == null) {
            return;
        }
        poseStack.pushPose();
        if (transform != null) {
            poseStack.mulPose(transform);
        }
        try {
            emitBlockModel(poseStack, model, blockState, BlockPos.ZERO, 42L, false);
        } finally {
            poseStack.popPose();
        }
    }

    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    public void submitNameTag(PoseStack poseStack, Vec3 nameTagAttachment, int offset, Component name,
                              boolean seeThrough, int lightCoords, CameraRenderState camera) {
    }


    // Sign text (AbstractSignRenderer) and any other in-world text (maps, …) land here. Mirrors
    // TextFeatureRenderer.buildGroup's own flush path exactly (same Font.prepareText/prepare8xTextOutline
    // + GlyphVisitor calls) but visits glyphs straight into the capture instead of a real vertex buffer,
    // so sign text gets real ray-traced geometry instead of being dropped.
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
                           Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor, int outlineColor) {
        if (capture == null) {
            return;
        }
        Matrix4f pose = poseStack.last().pose();
        Font font = Minecraft.getInstance().font;
        textGlyphVisitor.pose = pose;
        textGlyphVisitor.lightCoords = lightCoords;
        if (outlineColor == 0) {
            textGlyphVisitor.displayMode = displayMode;
            font.prepareText(string, x, y, color, dropShadow, false, backgroundColor).visit(textGlyphVisitor);
        } else {
            // Same two-pass outline technique as vanilla: an 8-directional expanded copy first (flat,
            // NORMAL), then the real text on top with a polygon-offset display mode so it doesn't z-fight
            // the outline it's sitting on.
            Font.PreparedText outline = font.prepare8xTextOutline(string, x, y, outlineColor);
            Font.PreparedText text = font.prepareText(string, x, y, color, false, false, 0);
            textGlyphVisitor.displayMode = Font.DisplayMode.NORMAL;
            outline.visit(textGlyphVisitor);
            textGlyphVisitor.displayMode = Font.DisplayMode.POLYGON_OFFSET;
            text.visit(textGlyphVisitor);
        }
    }

    /** Resolves each glyph's render type to a stable texture identity and captures its geometry. */
    private final class TextGlyphVisitor implements Font.GlyphVisitor {
        Matrix4f pose;
        int lightCoords;
        Font.DisplayMode displayMode;

        @Override
        public void acceptRenderable(TextRenderable renderable) {
            RenderType renderType = renderable.renderType(displayMode);
            boolean stochasticAlpha = isTranslucent(renderType);
            capture.currentMaterial = standaloneMaterial(renderType);
            capture.currentCoverage = stochasticAlpha ? MinecraftEntityMesh.Coverage.STOCHASTIC
                    : hasCutoutDefine(renderType) ? MinecraftEntityMesh.Coverage.CUTOUT : MinecraftEntityMesh.Coverage.OPAQUE;
            capture.currentOrder = 0;
            capture.clearUvRemap(); // glyph U/V are already atlas-space
            renderable.render(pose, textVertexConsumer, lightCoords, false);
        }
    }

    /**
     * Adapts the builder-style {@link VertexConsumer} calls glyph rendering makes ({@code
     * addVertex(pose,x,y,z).setColor(c).setUv(u,v).setLight(light)}) into {@link RtEntityCapture}'s bulk
     * {@code addVertex}. {@code setUv2} (light, via the default {@code setLight}) is always the last call
     * per vertex in {@code BakedSheetGlyph}, so committing there is safe — no vertex is ever left pending.
     */
    private final class RtTextVertexConsumer implements VertexConsumer {
        private float vx, vy, vz;
        private int vColor = -1;
        private float vu, vv;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vx = x;
            vy = y;
            vz = z;
            vColor = -1;
            vu = 0f;
            vv = 0f;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            vColor = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            vColor = color;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            vu = u;
            vv = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this; // overlay unused by the capture
        }

        @Override
        public VertexConsumer setUv2(int lightU, int lightV) {
            // Inverts VertexConsumer#setLight's default packing; light itself is unused by the capture
            // (entities are fully path-traced), so any value round-trips fine.
            int light = (lightU & 0xFFFF) | (lightV << 16);
            capture.addVertex(vx, vy, vz, vColor, vu, vv, 0, light, 0f, 0f, 0f);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this; // planar glyph quad → emitQuad's geometric fallback is exact
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
    }

    // Leashes/leads: replicate LeashFeatureRenderer's geometry — a 24-segment curve with two crossed
    // diagonal ribbons across the 0.05-wide cross-section (the crossing keeps the leash visible from
    // every view direction), checkered per segment by darkening alternate ranks. Vanilla draws it as an
    // untextured triangle strip (POSITION_COLOR_LIGHTMAP); here each strip step becomes one RT quad on
    // the white texture with the leash colour as per-primitive tint. Light coords are path-traced away.
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
        if (capture == null) {
            return;
        }
        capture.clearUvRemap();
        capture.currentOrder = 0;
        capture.currentMaterial = new MinecraftEntityMesh.Material(
                MinecraftMaterialIds.VERTEX_COLOR, textures.whiteTexture(),
                MinecraftEntityMesh.Program.MATERIAL);
        capture.currentCoverage = MinecraftEntityMesh.Coverage.OPAQUE;
        Matrix4f pose = poseStack.last().pose();
        // Same derivation as LeashFeatureRenderer.prepare: the ribbon's horizontal half-extent is the
        // curve's ground-plane perpendicular, and the attachment offset shifts the whole curve in the
        // pose's local space (translate-then-transform == transform(v + offset)).
        float dx = (float) (leashState.end.x - leashState.start.x);
        float dy = (float) (leashState.end.y - leashState.start.y);
        float dz = (float) (leashState.end.z - leashState.start.z);
        float offsetFactor = Mth.invSqrt(dx * dx + dz * dz) * LEASH_WIDTH / 2.0f;
        float dxOff = dz * offsetFactor;
        float dzOff = dx * offsetFactor;
        if (!Float.isFinite(dxOff) || !Float.isFinite(dzOff)) {
            // Perfectly vertical leash: the ground-plane perpendicular degenerates (vanilla emits NaN
            // vertices and draws nothing). Pick an arbitrary horizontal extent instead — NaN positions
            // must never reach the capture, where they'd poison the motion/hash passes.
            dxOff = LEASH_WIDTH / 2.0f;
            dzOff = 0f;
        }
        emitLeashRibbon(pose, leashState, dx, dy, dz, dxOff, dzOff, LEASH_WIDTH, false);
        emitLeashRibbon(pose, leashState, dx, dy, dz, dxOff, dzOff, 0f, true);
    }

    /** One diagonal leash ribbon: 25 vertex pairs along the curve, quadified pairwise. */
    private void emitLeashRibbon(Matrix4f pose, EntityRenderState.LeashState state,
                                 float dx, float dy, float dz, float dxOff, float dzOff,
                                 float fudge, boolean altParity) {
        float ox = (float) state.offset.x;
        float oy = (float) state.offset.y;
        float oz = (float) state.offset.z;
        float prevAx = 0f, prevAy = 0f, prevAz = 0f, prevBx = 0f, prevBy = 0f, prevBz = 0f;
        int prevColor = 0;
        for (int k = 0; k <= LEASH_STEPS; k++) {
            float progress = (float) k / LEASH_STEPS;
            float x = dx * progress;
            float y;
            if (state.slack) {
                y = dy > 0.0f ? dy * progress * progress : dy - dy * (1.0f - progress) * (1.0f - progress);
            } else {
                y = dy * progress;
            }
            float z = dz * progress;
            // Vanilla's per-pair checker: the two crossed ribbons darken opposite ranks (backwards pass).
            float m = k % 2 == (altParity ? 1 : 0) ? 0.7f : 1.0f;
            int color = 0xFF000000
                    | ((int) (0.5f * m * 255.0f) << 16)
                    | ((int) (0.4f * m * 255.0f) << 8)
                    | (int) (0.3f * m * 255.0f);
            pose.transformPosition(ox + x - dxOff, oy + y + fudge, oz + z + dzOff, meshPos);
            float ax = meshPos.x, ay = meshPos.y, az = meshPos.z;
            pose.transformPosition(ox + x + dxOff, oy + y + LEASH_WIDTH - fudge, oz + z - dzOff, meshPos);
            float bx = meshPos.x, by = meshPos.y, bz = meshPos.z;
            if (k > 0) {
                meshX[0] = prevAx; meshY[0] = prevAy; meshZ[0] = prevAz;
                meshX[1] = prevBx; meshY[1] = prevBy; meshZ[1] = prevBz;
                meshX[2] = bx; meshY[2] = by; meshZ[2] = bz;
                meshX[3] = ax; meshY[3] = ay; meshZ[3] = az;
                capture.addDirectQuad(meshX, meshY, meshZ, ZERO_UV, ZERO_UV, 0f, 0f, 0f, prevColor);
            }
            prevAx = ax; prevAy = ay; prevAz = az;
            prevBx = bx; prevBy = by; prevBz = bz;
            prevColor = color;
        }
    }

    // Falling blocks and moving piston blocks use the same vanilla baked model path as item and block
    // display submissions.
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state, int outlineColor) {
        if (capture == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        BlockState bs = state.blockState;
        BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(bs);
        if (model == null) {
            return;
        }
        emitBlockModel(poseStack, model, bs, state.blockPos, bs.getSeed(state.randomSeedPos), true);
    }

    private void emitBlockModel(PoseStack poseStack, BlockStateModel model, BlockState state, BlockPos pos,
                                long seed, boolean applyBlockOffset) {
        if (applyBlockOffset) {
            Vec3 offset = state.getOffset(pos);
            poseStack.pushPose();
            poseStack.translate(offset.x, offset.y, offset.z);
        }
        int idxStart = capture.idx.size();
        long started = profileDynamicEntity ? instrumentation.startStage() : 0L;
        try {
            RandomSource random = RandomSource.create();
            random.setSeed(seed);
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(random, parts);
            Matrix4f pose = poseStack.last().pose();
            for (BlockStateModelPart part : parts) {
                addQuads(pose, part.getQuads(null), null);
                for (Direction direction : DIRECTIONS) {
                    addQuads(pose, part.getQuads(direction), null);
                }
            }
        } finally {
            instrumentation.endStage("entity.capture.submit.bakedQuads", started);
            countBakedOutput(idxStart);
            if (applyBlockOffset) {
                poseStack.popPose();
            }
        }
    }

    // FallingBlockEntity renders its block model here. Capture every part's quads (direction-independent
    // + all six cullface lists), block-atlas textured (slot 0).
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        if (capture == null) {
            return;
        }
        Matrix4f pose = poseStack.last().pose();
        for (BlockStateModelPart part : parts) {
            addQuads(pose, part.getQuads(null), tintLayers);
            for (Direction d : DIRECTIONS) {
                addQuads(pose, part.getQuads(d), tintLayers);
            }
        }
    }


    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
    }

    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
                                   float width, boolean afterTerrain) {
    }

    // Held weapons/tools (via the in-hand layer) + dropped items (ItemEntity) render here as baked
    // quads on the block atlas. Capture them block-atlas textured (slot 0).
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords,
                           int outlineColor, int[] tintLayers, List<BakedQuad> quads, ItemStackRenderState.FoilType foilType) {
        if (capture == null) {
            return;
        }
        addQuads(poseStack.last().pose(), quads, tintLayers);
    }

    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        if (capture == null) {
            return;
        }
        PrimitiveTopology topology = primitiveTopology(renderType);
        boolean lines = topology == PrimitiveTopology.LINES || topology == PrimitiveTopology.DEBUG_LINES
                || renderType == RenderTypes.lines() || renderType == RenderTypes.linesTranslucent();
        if (!lines && topology != PrimitiveTopology.QUADS) {
            return;
        }

        capture.currentOrder = pendingOrder;
        pendingOrder = 0;
        capture.clearUvRemap(); // custom callbacks already emit final texture/atlas UV coordinates
        boolean stochasticAlpha = isTranslucent(renderType);
        // Lines are untextured: bind the white texture so base color is exactly the vertex colour (index 0 is
        // the block atlas, whose (0,0) texel would tint the ribbon arbitrarily).
        if (lines) {
            capture.currentMaterial = new MinecraftEntityMesh.Material(
                    MinecraftMaterialIds.VERTEX_COLOR, textures.whiteTexture(),
                    MinecraftEntityMesh.Program.MATERIAL);
        } else {
            capture.currentMaterial = standaloneMaterial(renderType);
        }
        capture.currentCoverage = lines ? MinecraftEntityMesh.Coverage.OPAQUE
                : isEndPortal(renderType) ? MinecraftEntityMesh.Coverage.OPAQUE
                : stochasticAlpha ? MinecraftEntityMesh.Coverage.STOCHASTIC
                : hasCutoutDefine(renderType) ? MinecraftEntityMesh.Coverage.CUTOUT : MinecraftEntityMesh.Coverage.OPAQUE;

        if (lines) {
            lineVertexConsumer.begin();
            customGeometryRenderer.render(poseStack.last(), lineVertexConsumer);
            lineVertexConsumer.finish();
        } else {
            customQuadVertexConsumer.begin();
            customGeometryRenderer.render(poseStack.last(), customQuadVertexConsumer);
            customQuadVertexConsumer.finish();
            capture.requireCompleteQuads("custom geometry " + renderType);
        }
    }

    /**
     * Converts builder-style custom quad vertices into the bulk vertex form consumed by
     * {@link RtEntityCapture}. A new vertex commits the previous one because vanilla does not expose an
     * explicit endVertex call; {@link #finish()} commits the final vertex after the callback returns.
     */
    private final class RtCustomQuadVertexConsumer implements VertexConsumer {
        private float x, y, z, u, v, nx, ny, nz;
        private int color;
        private boolean pending;

        void begin() {
            pending = false;
        }

        void finish() {
            commit();
        }

        private void commit() {
            if (!pending) {
                return;
            }
            capture.addVertex(x, y, z, color, u, v, 0, 0, nx, ny, nz);
            pending = false;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            commit();
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = 0f;
            this.v = 0f;
            this.nx = 0f;
            this.ny = 0f;
            this.nz = 0f;
            this.color = -1;
            this.pending = true;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            color = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            this.color = color;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.nx = x;
            this.ny = y;
            this.nz = z;
            return this;
        }

        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

    /** Expands each raster line segment into two crossed, camera-independent RT ribbons. */
    private final class RtLineVertexConsumer implements VertexConsumer {
        private static final float WORLD_UNITS_PER_PIXEL = 0.0025f;
        private final float[] ax = new float[4], ay = new float[4], az = new float[4];
        private float x, y, z, width;
        private int color;
        private boolean pending;
        private boolean haveFirst;
        private float firstX, firstY, firstZ, firstWidth;
        private int firstColor;

        void begin() {
            pending = false;
            haveFirst = false;
        }

        void finish() {
            commit();
            if (haveFirst) {
                haveFirst = false;
                throw new IllegalStateException("custom line geometry left an unmatched vertex");
            }
        }

        private void commit() {
            if (!pending) {
                return;
            }
            if (!haveFirst) {
                firstX = x;
                firstY = y;
                firstZ = z;
                firstWidth = width;
                firstColor = color;
                haveFirst = true;
            } else {
                emitSegment(firstX, firstY, firstZ, x, y, z,
                        Math.max(firstWidth, width), firstColor);
                haveFirst = false;
            }
            pending = false;
        }

        private void emitSegment(float x0, float y0, float z0, float x1, float y1, float z1,
                                 float pixelWidth, int color) {
            float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
            float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length <= 1.0e-6f) {
                return;
            }
            dx /= length;
            dy /= length;
            dz /= length;
            float halfWidth = Math.max(0.0015f, pixelWidth * WORLD_UNITS_PER_PIXEL * 0.5f);

            // Cross the segment with the least-parallel cardinal axis for a stable perpendicular.
            float px, py, pz;
            float adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz);
            if (adx <= ady && adx <= adz) {
                px = 0f;
                py = dz;
                pz = -dy;
            } else if (ady <= adz) {
                px = -dz;
                py = 0f;
                pz = dx;
            } else {
                px = dy;
                py = -dx;
                pz = 0f;
            }
            float plen = (float) Math.sqrt(px * px + py * py + pz * pz);
            px = px / plen * halfWidth;
            py = py / plen * halfWidth;
            pz = pz / plen * halfWidth;
            emitRibbon(x0, y0, z0, x1, y1, z1, px, py, pz, color);

            float qx = (dy * pz - dz * py);
            float qy = (dz * px - dx * pz);
            float qz = (dx * py - dy * px);
            emitRibbon(x0, y0, z0, x1, y1, z1, qx, qy, qz, color);
        }

        private void emitRibbon(float x0, float y0, float z0, float x1, float y1, float z1,
                                float px, float py, float pz, int color) {
            ax[0] = x0 - px; ay[0] = y0 - py; az[0] = z0 - pz;
            ax[1] = x1 - px; ay[1] = y1 - py; az[1] = z1 - pz;
            ax[2] = x1 + px; ay[2] = y1 + py; az[2] = z1 + pz;
            ax[3] = x0 + px; ay[3] = y0 + py; az[3] = z0 + pz;
            capture.addDirectQuad(ax, ay, az, ZERO_UV, ZERO_UV, 0f, 0f, 0f, color);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            commit();
            this.x = x;
            this.y = y;
            this.z = z;
            this.width = 1f;
            this.color = -1;
            this.pending = true;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            color = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            return this;
        }

        @Override public VertexConsumer setColor(int color) { this.color = color; return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { this.width = width; return this; }
    }

    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
    }

    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
    }

}
