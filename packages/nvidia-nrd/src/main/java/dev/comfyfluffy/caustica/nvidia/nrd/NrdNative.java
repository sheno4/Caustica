package dev.comfyfluffy.caustica.nvidia.nrd;

import java.lang.foreign.MemorySegment;

interface NrdNative {
    MemorySegment create(MemorySegment description);
    int record(MemorySegment instance, long commandBuffer, MemorySegment common, MemorySegment resources);
    void destroy(MemorySegment instance);
    String lastError();
}
