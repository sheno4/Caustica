package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.JsonElement;
import java.util.Map;

/** Typed private parameter recipes preserve scalar branches, integer masks and float32 arithmetic. */
public final class CloudlyUniformExpressions {
    private CloudlyUniformExpressions() { }

    public static double[] evaluate(JsonElement expression, Map<String, double[]> inputs) {
        if (expression.isJsonPrimitive()) {
            var primitive = expression.getAsJsonPrimitive();
            return new double[]{primitive.isBoolean() ? primitive.getAsBoolean() ? 1 : 0 : primitive.getAsDouble()};
        }
        if (expression.isJsonArray()) {
            var array = expression.getAsJsonArray();
            double[] value = new double[array.size()];
            for (int index = 0; index < value.length; index++) value[index] = array.get(index).getAsDouble();
            return value;
        }
        var object = expression.getAsJsonObject();
        if (object.has("input")) {
            String name = object.get("input").getAsString();
            double[] value = inputs.get(name);
            if (value == null) throw new IllegalArgumentException("Missing original runtime input " + name);
            return value.clone();
        }
        String op = object.get("op").getAsString();
        var args = object.getAsJsonArray("args");
        double[] first = evaluate(args.get(0), inputs);
        if (op.equals("select")) return evaluate(args.get(scalar(first) != 0 ? 1 : 2), inputs);
        if (op.equals("and")) return new double[]{scalar(first) != 0 && scalar(evaluate(args.get(1), inputs)) != 0 ? 1 : 0};
        if (op.equals("saturate")) {
            for (int index = 0; index < first.length; index++) first[index] = Math.clamp((float)first[index], 0f, 1f);
            return first;
        }
        double[] second = evaluate(args.get(1), inputs);
        if (op.equals("component")) return new double[]{first[(int)scalar(second)]};
        if (op.equals("bitOr")) return new double[]{Integer.toUnsignedLong((int)(long)scalar(first) | (int)(long)scalar(second))};
        if (op.equals("shiftLeft")) return new double[]{Integer.toUnsignedLong((int)(long)scalar(first) << (int)(long)scalar(second))};
        double[] third = op.equals("lerp") ? evaluate(args.get(2), inputs) : new double[]{0};
        int length = Math.max(Math.max(first.length, second.length), third.length);
        if (first.length != 1 && first.length != length || second.length != 1 && second.length != length
                || third.length != 1 && third.length != length) throw new IllegalArgumentException("Original expression vector widths differ");
        double[] result = new double[length];
        for (int index = 0; index < length; index++) {
            double exactA = first[first.length == 1 ? 0 : index], exactB = second[second.length == 1 ? 0 : index];
            float a = (float)exactA, b = (float)exactB;
            float t = (float)third[third.length == 1 ? 0 : index];
            result[index] = switch (op) {
                case "add" -> a + b;
                case "subtract" -> a - b;
                case "multiply" -> a * b;
                case "divide" -> a / b;
                case "min" -> Math.min(a, b);
                case "max" -> Math.max(a, b);
                case "lerp" -> a + (b - a) * t;
                case "equal" -> exactA == exactB ? 1 : 0;
                case "lessEqual" -> exactA <= exactB ? 1 : 0;
                case "greaterEqual" -> exactA >= exactB ? 1 : 0;
                default -> throw new IllegalArgumentException("Unknown original parameter operation " + op);
            };
        }
        return result;
    }

    private static double scalar(double[] value) {
        if (value.length != 1) throw new IllegalArgumentException("Original expression requires a scalar");
        return value[0];
    }
}
