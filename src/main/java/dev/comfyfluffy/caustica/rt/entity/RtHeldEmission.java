package dev.comfyfluffy.caustica.rt.entity;

import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.state.BlockState;

/** Measures the transformed quads of one held block item against its placed model. */
final class RtHeldEmission {
    private final FloatArrayList positions = new FloatArrayList();
    private final FloatArrayList primitives = new FloatArrayList();
    private final FloatArrayList cornerUvs = new FloatArrayList();
    private final ObjectArrayList<TextureAtlasSprite> sprites = ObjectArrayList.wrap(new TextureAtlasSprite[16], 0);
    private BlockState block;
    private int firstTriangle;
    private boolean complete;

    void begin(RtEntityCapture capture, BlockState block) {
        this.block = block;
        firstTriangle = capture.idx.size() / 3;
        complete = true;
        positions.clear();
        primitives.clear();
        cornerUvs.clear();
        sprites.clear();
    }

    void includeQuad(RtEntityCapture capture, TextureAtlasSprite sprite) {
        if (block == null || !complete) return;
        int primStart = capture.prim.size() - 24;
        float[] prim = capture.prim.elements();
        int id = Float.floatToRawIntBits(prim[primStart + 8]) & RtEntityCapture.MATERIAL_ID_MASK;
        RtMaterialRegistry registry = RtMaterialRegistry.INSTANCE;
        if (!registry.canSampleEmission(id, prim[primStart + 3])) return;
        if (sprite == null || !TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())) {
            complete = false;
            return;
        }

        // The immutable block record shares the same emission as a stochastic-alpha specialization.
        int material = registry.requireSnapshot().resolve(sprite, block, false);
        positions.addElements(positions.size(), capture.verts.elements(), capture.verts.size() - 12, 12);
        int output = primitives.size();
        primitives.addElements(output, prim, primStart, 24);
        primitives.set(output + 8, Float.intBitsToFloat(material));
        primitives.set(output + 20, Float.intBitsToFloat(material));
        float[] uv = capture.uvList.elements();
        int uvStart = capture.uvList.size() - 8;
        cornerUvs.addElements(cornerUvs.size(), uv, uvStart, 6);
        cornerUvs.addElements(cornerUvs.size(), uv, uvStart, 2);
        cornerUvs.addElements(cornerUvs.size(), uv, uvStart + 4, 4);
        sprites.add(sprite);
        sprites.add(sprite);
    }

    void finish(RtEntityCapture capture, boolean submitted) {
        BlockState state = block;
        block = null;
        if (!submitted || state == null || !complete || sprites.isEmpty()) return;
        float placed = RtTerrain.placedEmissionPower(state);
        if (!(placed > 0.0f)) return;
        float held = RtTerrain.meshEmissionPower(positions, primitives, cornerUvs, sprites.elements(),
                RtMaterialRegistry.INSTANCE.requireSnapshot());
        float scale = placed / held;
        if (scale > 0.0f && Float.isFinite(scale)) {
            capture.scaleEmittedPower(firstTriangle, scale);
        }
    }
}
