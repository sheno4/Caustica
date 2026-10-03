package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly.gen.CloudlyNoiseProfileData;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly.gen.CloudlyNoiseProfileData.Float4;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Source fractal settings for a declared Perlin/Worley detail approximation, not recovered Cloudly shader code. */
record CloudlyDetailProfile(CloudlyNoiseProfileData data) {
    static List<CloudlyDetailProfile> from(CloudlySourcePack source) {
        if (!"authoredPerlinWorleyErosion".equals(source.rendererAdapter().get("detailModel"))) {
            return List.of(disabled(), disabled(), disabled(), disabled(), disabled(), disabled());
        }
        float scale = number(source.rendererAdapter(), "detailDisplacementScale", 0);
        Map<String, Object> defaults = object(source.rendererAdapter().get("detailDefaults"));
        Map<String, Object> sky = source.skyParameters();
        return List.of(read(sky, "NoiseTexParamsB", scale, defaults), read(sky, "NoiseTexParamsC", scale, defaults),
                disabled(), disabled(), read(sky, "NoiseTexParams_THINB_DISP", scale, defaults), disabled());
    }

    private static CloudlyDetailProfile read(Map<String, Object> sky, String name, float scale, Map<String, Object> defaults) {
        Map<String, Object> group = object(value(sky, name));
        Map<String, Object> runtime = object(value(group, "RuntimeParams"));
        Map<String, Object> create = object(value(group, "CreateParams"));
        Object channels = value(create, "Channel");
        if (!(channels instanceof List<?> list) || list.isEmpty()) return disabled();
        Map<String, Object> channel = object(list.getFirst());
        Map<String, Object> cell = object(value(channel, "AbsoluteCellSizeLog2f"));
        Map<String, Object> edge = object(value(object(value(runtime, "EdgeDetailSDF")), "EdgeDetailSDFDisplace"));
        float texel = number(runtime, "TexelWorldSizeInMeters", 0);
        if (texel <= 0 || cell.isEmpty() || edge.isEmpty()) return disabled();
        return new CloudlyDetailProfile(new CloudlyNoiseProfileData(
                new Float4((float) Math.pow(2, number(cell, "X", 0)) * texel,
                        (float) Math.pow(2, number(cell, "Y", 0)) * texel,
                        (float) Math.pow(2, number(cell, "Z", 0)) * texel,
                        Math.min(4, number(channel, "NumOctaves", number(defaults, "octaves", 4)))),
                new Float4(number(channel, "Frequency", number(defaults, "frequency", 2)),
                        number(channel, "Persistence", number(defaults, "persistence", .5f)),
                        number(channel, "PerlinWeight", number(defaults, "perlinWeight", .5f)),
                        number(channel, "WorleyWeight", number(defaults, "worleyWeight", .5f))),
                new Float4(number(channel, "RemapMin", number(defaults, "remapMin", 0)),
                        number(channel, "RemapMax", number(defaults, "remapMax", 1)),
                        number(channel, "Power", number(defaults, "power", 1)),
                        number(channel, "Contrast", number(defaults, "contrast", 1))),
                new Float4(number(edge, "Factor", 0) * scale, number(edge, "DeflateAuto", 0),
                        number(edge, "VerticalSquash", 1), number(edge, "BaseSurfaceFadeLen", 0))));
    }

    private static CloudlyDetailProfile disabled() {
        Float4 zero = new Float4(0, 0, 0, 0);
        return new CloudlyDetailProfile(new CloudlyNoiseProfileData(zero, zero, zero, zero));
    }

    static Object value(Map<String, Object> map, String name) {
        Pattern indexed = Pattern.compile(Pattern.quote(name) + "\\[\\d+]");
        return map.entrySet().stream().filter(e -> e.getKey().equals(name) || indexed.matcher(e.getKey()).matches())
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }
    private static float number(Map<String, Object> map, String name, float fallback) {
        Object value = value(map, name);
        return value instanceof Number number ? number.floatValue() : fallback;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
}
