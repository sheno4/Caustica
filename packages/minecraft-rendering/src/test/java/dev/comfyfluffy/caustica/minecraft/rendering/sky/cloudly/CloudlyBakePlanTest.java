package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudlyBakePlanTest {
    @TempDir Path directory;

    @Test void dispatchKeepsVerifiedConstantSnapshotWhenThePrivateFileChanges() throws Exception {
        byte[] code = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x07230203).putInt(0x00010600).putInt(0).putInt(1).putInt(0).array();
        Files.write(directory.resolve("test.spv"), code);
        Files.writeString(directory.resolve("library.json"), """
                {"schemaVersion":1,"programs":[{"id":"test","entryPoint":"main","spirv":"test.spv",
                "sha256":"%s","sourceSha256":"%s","parametersByteSize":16,"bindingByteSize":0,
                "pushByteSize":16,"pushFields":[{"name":"parametersAddress","offset":0,"size":8},
                {"name":"bindingsAddress","offset":8,"size":8}]}]}
                """.formatted(hash(code), "1".repeat(64)));
        var library = CloudlyShaderLibrary.load(directory.resolve("library.json"));
        byte[] parameters = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putFloat(100000).array();
        Files.write(directory.resolve("parameters.bin"), parameters);
        Path recipe = directory.resolve("recipe.json");
        Files.writeString(recipe, """
                {"schemaVersion":1,"executionReady":true,"images":[],"samplers":[],"dispatches":[{
                "programId":"test","constants":{"path":"parameters.bin","sha256":"%s"},"bindings":{},
                "push":{"parametersAddress":{"kind":"parametersAddress"},"bindingsAddress":{"kind":"bindingsAddress"}},
                "groups":[1,1,1]}]}
                """.formatted(hash(parameters)));
        var loaded = CloudlyBakePlan.load(recipe, library);
        Files.write(directory.resolve("parameters.bin"), new byte[16]);
        var stages = loaded.bind(Map.of(), Map.of(), List.of()).stages();
        var dispatch = (CloudlyVolumeBake.DispatchSpec) stages.getFirst();
        assertEquals(100000, dispatch.constants().getFloat(0));
        assertTrue(dispatch.constants().isReadOnly());
        assertEquals(1, loaded.dispatchCount());
        assertThrows(java.io.IOException.class, () -> CloudlyBakePlan.load(recipe, library));
    }

    @Test void unresolvedOriginalInputsCannotCreateAnExecutableBake() throws Exception {
        Path recipe = directory.resolve("unresolved.json");
        Files.writeString(recipe, "{\"schemaVersion\":1,\"executionReady\":false}");
        assertThrows(java.io.IOException.class, () -> CloudlyBakePlan.load(recipe, null));
    }

    @Test void copyRetainsOrderAndRejectsFormatOrMipSizeChanges() throws Exception {
        Path recipe = directory.resolve("copy.json");
        String image = """
                {"name":"%s","dimension":"THREE_D","width":8,"height":8,"depth":4,"mipLevels":3,
                "format":9,"linearFiltering":true,"publish":false,"clearWords":[0,0,0,0]}
                """;
        Files.writeString(recipe, """
                {"schemaVersion":1,"executionReady":true,"images":[%s,%s],"samplers":[],"stages":[
                {"kind":"copy","source":"original","sourceMip":1,"destination":"filtered","destinationMip":1}]}
                """.formatted(image.formatted("original"), image.formatted("filtered")));
        var loaded = CloudlyBakePlan.load(recipe, null);
        var plan = loaded.bind(Map.of(), Map.of(), List.of());
        assertEquals(new CloudlyVolumeBake.CopySpec("original",1,"filtered",1), plan.stages().getFirst());
        assertEquals(0, loaded.dispatchCount());
        assertThrows(IllegalArgumentException.class, () -> new CloudlyVolumeBake.Plan(plan.images(), List.of(),
                List.of(new CloudlyVolumeBake.CopySpec("original",0,"filtered",1)), List.of()));
        var changed = new CloudlyVolumeBake.ImageSpec("filtered", CloudlyVolumeBake.Dimension.THREE_D,
                8,8,4,3,16,true,false,null);
        assertThrows(IllegalArgumentException.class, () -> new CloudlyVolumeBake.Plan(List.of(plan.images().getFirst(),changed), List.of(),
                plan.stages(), List.of()));
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
