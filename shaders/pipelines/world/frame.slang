// Shared FrameContext builder for every runtime-compiled, composition-generic world stage. This module
// deliberately carries no stage infix, so build-time shader compilation skips it and only the runtime
// world compiler resolves it through import.
//
// One implementation keeps frame, sun, and moon state identical for every slot call site.
import world_common;
import sky;
import caustica_types;

// WorldPush carries Minecraft's eased celestial angles and look constants; FrameContext exposes only
// the semantic subset an appearance implementation is allowed to see.
public FrameContext frameContext(WorldPush push, SkyState state) {
    FrameContext frame;
    frame.timeSeconds = push.waterParams.w;
    frame.frameIndex = push.frameIndex;
    frame.cameraPosition = push.camOffset;
    // No weather field reaches the world push, so
    // slots see a clear sky rather than fabricated weather values.
    frame.rain = 0.0;
    frame.thunder = 0.0;
    frame.sunDirection = state.sunDir;
    frame.moonDirection = state.moonDir;
    // Night fraction drives a slot's day/night blend. The sun's elevation is the source: it is
    // continuous through dawn/dusk and already carries the look package's noon tilt.
    float sunElevation = state.sunDir.y;
    frame.nightFraction = clamp(1.0 - smoothstep(-0.10, 0.10, sunElevation), 0.0, 1.0);
    frame.dayFraction = 1.0 - frame.nightFraction;
    frame.dimensionFlags = DIMENSION_HAS_SKY | DIMENSION_HAS_WEATHER;
    return frame;
}
