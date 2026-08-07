// Shared FrameContext builder for every composition-generic world stage, so every slot call site sees
// identical frame state.
//
// Celestial state is deliberately absent: it belongs to whichever sky slot is selected and lives in that
// pass's own buffer, which engine-generic code cannot reach. A slot that needs the sun's direction reads
// its own. Weather has no source in the world push, so slots see a clear sky.
module frame;

import world_common;
import caustica_types;

public FrameContext frameContext(WorldPush push) {
    FrameContext frame;
    frame.rain = 0.0;
    frame.thunder = 0.0;
    return frame;
}
