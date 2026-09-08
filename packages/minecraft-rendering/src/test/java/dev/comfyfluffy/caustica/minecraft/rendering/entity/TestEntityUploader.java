package dev.comfyfluffy.caustica.minecraft.rendering.entity;

/** Adapts worker-side fixture allocation to the staged upload contract. */
interface TestEntityUploader extends MinecraftEntityUploader {
    UploadedEntity upload(MinecraftEntityMesh source);

    @Override
    default UploadJob prepareUpload(MinecraftEntityMesh source) {
        return new UploadJob() {
            @Override public UploadedEntity finish() { return upload(source); }
            @Override public void close() { }
        };
    }
}
