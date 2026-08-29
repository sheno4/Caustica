package dev.comfyfluffy.caustica.example.gltfcontent;

/** Immutable decoded RGBA image used while adapting one glTF asset. */
record GltfImageData(int width, int height, int[] argb) {
    GltfImageData {
        argb = argb.clone();
        if (width <= 0 || height <= 0 || argb.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("decoded glTF image dimensions do not match its pixels");
        }
    }

    @Override
    public int[] argb() {
        return argb.clone();
    }
}
