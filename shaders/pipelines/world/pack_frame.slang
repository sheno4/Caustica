// Shared PackFrameContext builder for every runtime-compiled, pack-generic world stage
// (pack_sky_miss, pack_closest_hit, pack_indirect). Deliberately carries no stage infix — see the
// "no stage infix = reached only through import" convention in pack_sky_miss.slang — so build-time
// compileShaders never touches it; only the runtime pack compiler resolves it via import.
//
// One implementation so every stage a pack sees agrees on frame/sun/moon state, the same reason
// pack_sky_miss derives its directions from `sky` instead of re-deriving them locally.
import world_common;
import sky;
import caustica_ray_pack_api;

// WorldPush carries Minecraft's eased celestial angles and the look package's constants; the Pack API
// exposes only the semantic subset an appearance implementation is allowed to see. Anything not in
// PackFrameContext is deliberately withheld, not accidentally missing.
public PackFrameContext packFrameContext(WorldPush push, SkyState state) {
    PackFrameContext frame;
    frame.timeSeconds = push.waterParams.w;
    frame.frameIndex = push.frameIndex;
    frame.cameraPosition = push.camOffset;
    // No weather field reaches the world push yet (that is the environment service, section 3), so a
    // pack sees a clear sky rather than a fabricated value.
    frame.rain = 0.0;
    frame.thunder = 0.0;
    frame.sunDirection = state.sunDir;
    frame.moonDirection = state.moonDir;
    // Night fraction drives a pack's day/night blend. The sun's elevation is the honest source: it is
    // continuous through dawn/dusk and already carries the look package's noon tilt.
    float sunElevation = state.sunDir.y;
    frame.nightFraction = clamp(1.0 - smoothstep(-0.10, 0.10, sunElevation), 0.0, 1.0);
    frame.dayFraction = 1.0 - frame.nightFraction;
    frame.dimensionFlags = PACK_DIMENSION_HAS_SKY | PACK_DIMENSION_HAS_WEATHER;
    return frame;
}
