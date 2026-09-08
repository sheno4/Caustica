package dev.comfyfluffy.caustica.minecraft.rendering.entity;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class MinecraftEntityUploaderTest {
    @Test void defaultJobDefersUploadUntilWorkerFinishAndTransfersOwnership() throws Exception {
        var caller = Thread.currentThread();
        var uploadedThreads = new ArrayList<Thread>();
        var closes = new AtomicInteger();
        var uploaded = new MinecraftEntityUploader.UploadedEntity() {
            @Override public MeshBuild<MinecraftProgramTypes.InstanceData> build() { throw new AssertionError(); }
            @Override public ShaderData<MinecraftProgramTypes.InstanceData> instanceData() { throw new AssertionError(); }
            @Override public void close() { closes.incrementAndGet(); }
        };
        var source = mesh();
        MinecraftEntityUploader uploader = input -> {
            assertSame(source, input);
            uploadedThreads.add(Thread.currentThread());
            return uploaded;
        };
        uploader.prepareUpload(source).close();
        var job = uploader.prepareUpload(source);
        assertTrue(uploadedThreads.isEmpty());

        try (var worker = Executors.newSingleThreadExecutor()) {
            assertSame(uploaded, worker.submit(job::finish).get());
        }
        job.close();
        assertEquals(1, uploadedThreads.size());
        assertNotSame(caller, uploadedThreads.getFirst());
        assertEquals(0, closes.get());
        uploaded.close();
        assertEquals(1, closes.get());
    }

    private static MinecraftEntityMesh mesh() {
        var material = new MinecraftEntityMesh.Material(ResourceId.of("test", "entity"), null,
                MinecraftEntityMesh.Program.MATERIAL);
        return new MinecraftEntityMesh(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2},
                new float[6], new float[12], List.of(new MinecraftEntityMesh.Triangle(material,
                MinecraftEntityMesh.Coverage.OPAQUE, 0, 0, 1, 0)), 1);
    }
}
