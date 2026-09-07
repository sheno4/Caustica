package dev.comfyfluffy.caustica.renderer.raytracing.accel;

import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class TlasBuilderPackingTest {
    @Test void workerPacksEveryFieldInPageOrderWithoutOverwritingTheNextRecord() throws Exception {
        var instances = SnapshotList.ofPages(List.of(List.of(7, 2), List.of(19)));
        int bytes = VkAccelerationStructureInstanceKHR.SIZEOF * (instances.size() + 1);
        var memory = MemoryUtil.memAlloc(bytes);
        for (int index = 0; index < bytes; index++) memory.put(index, (byte) 0x5a);
        long address = MemoryUtil.memAddress(memory);
        Thread caller = Thread.currentThread();
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                assertNotSame(caller, Thread.currentThread());
                TlasBuilder.writeInstances(instances, address, (value, target) -> {
                    for (int component = 0; component < 12; component++) {
                        target.transform().matrix(component, value + component * 0.25f);
                    }
                    target.instanceCustomIndex(value * 3).mask(value + 1)
                            .instanceShaderBindingTableRecordOffset(value * 6).flags(value + 2)
                            .accelerationStructureReference(0x100000000L + value * 256L);
                });
            }).get();
            var records = VkAccelerationStructureInstanceKHR.create(address, instances.size());
            for (int index = 0; index < instances.size(); index++) {
                int value = instances.get(index);
                var record = records.get(index);
                for (int component = 0; component < 12; component++) {
                    assertEquals(value + component * 0.25f, record.transform().matrix(component));
                }
                assertEquals(value * 3, record.instanceCustomIndex());
                assertEquals(value + 1, record.mask());
                assertEquals(value * 6, record.instanceShaderBindingTableRecordOffset());
                assertEquals(value + 2, record.flags());
                assertEquals(0x100000000L + value * 256L, record.accelerationStructureReference());
            }
            for (int index = instances.size() * VkAccelerationStructureInstanceKHR.SIZEOF; index < bytes; index++) {
                assertEquals((byte) 0x5a, memory.get(index));
            }
        } finally {
            MemoryUtil.memFree(memory);
        }
    }
}
