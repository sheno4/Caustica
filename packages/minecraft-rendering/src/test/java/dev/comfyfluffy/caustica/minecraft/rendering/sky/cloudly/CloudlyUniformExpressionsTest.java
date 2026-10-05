package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CloudlyUniformExpressionsTest {
    @Test void selectedBranchesDoNotReadUnavailableRuntimeInputs() {
        var recipe = JsonParser.parseString("""
                {"op":"select","args":[true,[0.65,0.85],{"input":"unused.nativeValue"}]}
                """);
        assertArrayEquals(new double[]{0.65,0.85}, CloudlyUniformExpressions.evaluate(recipe, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> CloudlyUniformExpressions.evaluate(
                JsonParser.parseString("{\"input\":\"required.nativeValue\"}"), Map.of()));
    }

    @Test void packedFlagsRetainLowBitsAboveFloatMantissaRange() {
        var recipe = JsonParser.parseString("""
                {"op":"bitOr","args":[6699,{"op":"shiftLeft","args":[1,24]}]}
                """);
        assertEquals(0x01001a2b, CloudlyUniformExpressions.evaluate(recipe, Map.of())[0]);
        assertEquals(0xffffffffL, CloudlyUniformExpressions.evaluate(
                JsonParser.parseString("{\"op\":\"bitOr\",\"args\":[4294967295,0]}"),Map.of())[0]);
        assertEquals(0, CloudlyUniformExpressions.evaluate(
                JsonParser.parseString("{\"op\":\"equal\",\"args\":[16777217,16777216]}"),Map.of())[0]);
    }

    @Test void fieldOfViewInterpolationBroadcastsWithFloat32Arithmetic() {
        var recipe = JsonParser.parseString("""
                {"op":"lerp","args":[[0.65,0.85],[0.45,1.0],{"op":"saturate","args":[
                {"op":"divide","args":[{"op":"subtract","args":[{"input":"fov"},5]},45]}]}]}
                """);
        assertArrayEquals(new double[]{(float)0.65,(float)0.85}, CloudlyUniformExpressions.evaluate(recipe,Map.of("fov",new double[]{0})),1e-7);
        assertArrayEquals(new double[]{(float)0.45,1}, CloudlyUniformExpressions.evaluate(recipe,Map.of("fov",new double[]{90})),1e-7);
        assertArrayEquals(new double[]{(float)0.55,(float)0.925}, CloudlyUniformExpressions.evaluate(recipe,Map.of("fov",new double[]{27.5})),1e-7);
    }

    @Test void viewportAndTraceRatiosComposeInSourceFloat4Order() {
        var expression = JsonParser.parseString("""
                {"op":"vector","args":[
                {"op":"divide","args":[1,1920]},
                {"op":"divide","args":[1,1080]},
                {"op":"divide","args":[{"input":"nativeTrace"},[1920,1080]]}]}
                """);
        assertArrayEquals(new double[]{1f/1920f,1f/1080f,960f/1920f,540f/1080f},
                CloudlyUniformExpressions.evaluate(expression, Map.of("nativeTrace",new double[]{960,540})));
        assertThrows(IllegalArgumentException.class, () -> CloudlyUniformExpressions.evaluate(
                JsonParser.parseString("{\"op\":\"vector\",\"args\":[[1,2,3],[4,5]]}"), Map.of()));
    }
}
