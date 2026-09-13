package dev.comfyfluffy.caustica.slangtooling

import org.gradle.api.GradleException

/** Converts a reflected record layout into Java fields and byte-buffer accessors. */
final class ShaderRecordSource {
    private ShaderRecordSource() { }

    private static String upperSnake(String name) {
        name.replaceAll(/([a-z0-9])([A-Z])/, '$1_$2').toUpperCase(Locale.ROOT)
    }

    private static String vectorType(Map type) {
        def scalar = type.elementType.scalarType
        def prefix = scalar == "float32" ? "Float" : (scalar in ["int32", "uint32"] ? "Int" : null)
        if (prefix == null || !(type.elementCount in [2, 3, 4])) {
            throw new GradleException("unsupported reflected vector type: ${type}")
        }
        "${prefix}${type.elementCount}"
    }

    private static String javaType(Map type) {
        switch (type.kind) {
            case "scalar":
                switch (type.scalarType) {
                    case "float32": return "float"
                    case "int32":
                    case "uint32": return "int"
                    case "int64":
                    case "uint64": return "long"
                    default: throw new GradleException("unsupported reflected scalar type: ${type.scalarType}")
                }
            case "vector": return vectorType(type)
            case "matrix":
                if (type.rowCount == 4 && type.columnCount == 4 && type.elementType.scalarType == "float32") {
                    return "Matrix4fc"
                }
                throw new GradleException("unsupported reflected matrix type: ${type}")
            case "struct": return type.name
            case "array": return "${javaType(type.elementType as Map)}[]"
            default: throw new GradleException("unsupported reflected type kind: ${type.kind}")
        }
    }

    private static void collectTypes(Map type, Set<String> vectors, Map<String, Map> structs) {
        if (type.kind == "vector") {
            vectors.add(vectorType(type))
        } else if (type.kind == "struct") {
            structs.putIfAbsent(type.name as String, type)
            type.fields.each { collectTypes(it.type as Map, vectors, structs) }
        } else if (type.kind == "array") {
            collectTypes(type.elementType as Map, vectors, structs)
        }
    }

    private static boolean containsKind(Map type, String kind) {
        if (type.kind == kind) return true
        if (type.kind == "array") return containsKind(type.elementType as Map, kind)
        if (type.kind == "struct") return type.fields.any { containsKind(it.type as Map, kind) }
        false
    }

    private static String at(String base, int offset) {
        base == "0" ? "${offset}" : (offset == 0 ? base : "${base} + ${offset}")
    }

    private static int scalarByteSize(Map type) {
        switch (type.scalarType) {
            case "float32":
            case "int32":
            case "uint32": return 4
            case "int64":
            case "uint64": return 8
            default: throw new GradleException("unsupported reflected scalar type: ${type.scalarType}")
        }
    }

    private static void collectOccupiedRanges(Map type, Map binding, int base,
                                              List<List<Integer>> ranges) {
        int address = base + ((binding.offset ?: 0) as int)
        switch (type.kind) {
            case "scalar":
                ranges.add([address, address + scalarByteSize(type)])
                return
            case "vector":
                int size = scalarByteSize(type.elementType as Map)
                int stride = (binding.elementStride ?: size) as int
                for (int index = 0; index < (type.elementCount as int); index++) {
                    ranges.add([address + index * stride, address + index * stride + size])
                }
                return
            case "matrix":
                if (type.rowCount != 4 || type.columnCount != 4 || type.elementType.scalarType != "float32") {
                    throw new GradleException("unsupported matrix writer: ${type}")
                }
                ranges.add([address, address + 16 * Float.BYTES])
                return
            case "struct":
                type.fields.each { field ->
                    collectOccupiedRanges(field.type as Map, field.binding as Map, address, ranges)
                }
                return
            case "array":
                int stride = (type.uniformStride ?: binding.elementStride) as int
                for (int index = 0; index < (type.elementCount as int); index++) {
                    collectOccupiedRanges(type.elementType as Map, [offset: 0], address + index * stride, ranges)
                }
                return
            default:
                throw new GradleException("unsupported writer type: ${type.kind}")
        }
    }

    /** Byte ranges not written by reflected fields, represented as [start, end). */
    static List<List<Integer>> paddingRanges(Map rootType, int byteSize) {
        List<List<Integer>> occupied = []
        rootType.fields.each { field ->
            collectOccupiedRanges(field.type as Map, field.binding as Map, 0, occupied)
        }
        occupied.sort { first, second -> first[0] <=> second[0] }
        List<List<Integer>> padding = []
        int cursor = 0
        for (List<Integer> range : occupied) {
            if (range[0] > cursor) padding.add([cursor, range[0]])
            cursor = Math.max(cursor, range[1])
        }
        if (cursor < byteSize) padding.add([cursor, byteSize])
        padding
    }

    private static void emitZeroRange(StringBuilder sb, int start, int end, String indent, String base = "0") {
        int cursor = start
        while (end - cursor >= Long.BYTES) {
            sb << "${indent}dst.putLong(${at(base, cursor)}, 0L);\n"
            cursor += Long.BYTES
        }
        if (end - cursor >= Integer.BYTES) {
            sb << "${indent}dst.putInt(${at(base, cursor)}, 0);\n"
            cursor += Integer.BYTES
        }
        if (end - cursor >= Short.BYTES) {
            sb << "${indent}dst.putShort(${at(base, cursor)}, (short) 0);\n"
            cursor += Short.BYTES
        }
        if (cursor < end) sb << "${indent}dst.put(${at(base, cursor)}, (byte) 0);\n"
    }

    private static void emitWrite(StringBuilder sb, Map type, Map binding, String expr, String base,
                                  String indent, int depth) {
        def address = at(base, (binding.offset ?: 0) as int)
        switch (type.kind) {
            case "scalar":
                sb << "${indent}dst.put${javaType(type).capitalize()}(${address}, ${expr});\n"
                return
            case "vector":
                def method = type.elementType.scalarType == "float32" ? "putFloat" : "putInt"
                def lanes = ["x", "y", "z", "w"]
                def stride = (binding.elementStride ?: 4) as int
                for (int i = 0; i < (type.elementCount as int); i++) {
                    sb << "${indent}dst.${method}(${at(address, i * stride)}, ${expr}.${lanes[i]}());\n"
                }
                return
            case "matrix":
                if (type.rowCount != 4 || type.columnCount != 4 || type.elementType.scalarType != "float32") {
                    throw new GradleException("unsupported matrix writer: ${type}")
                }
                sb << "${indent}${expr}.get(${address}, dst);\n"
                return
            case "struct":
                type.fields.each { field ->
                    emitWrite(sb, field.type as Map, field.binding as Map,
                            "${expr}.${field.name}()", address, indent, depth + 1)
                }
                return
            case "array":
                def index = "i${depth}"
                def stride = (type.uniformStride ?: binding.elementStride) as int
                sb << "${indent}for (int ${index} = 0; ${index} < ${expr}.length; ${index}++) {\n"
                emitWrite(sb, type.elementType as Map, [offset: 0], "${expr}[${index}]",
                        "${address} + ${index} * ${stride}", indent + "    ", depth + 1)
                sb << "${indent}}\n"
                return
            default:
                throw new GradleException("unsupported writer type: ${type.kind}")
        }
    }

    private static String emitReadExpression(Map type, Map binding, String base) {
        def address = at(base, (binding.offset ?: 0) as int)
        if (type.kind != "scalar") {
            throw new GradleException("generated readers currently support scalar fields only: ${type}")
        }
        "src.get${javaType(type).capitalize()}(${address})"
    }

    static String generateJava(Map rootType, int byteSize, String packageName, String className,
                               boolean emitReader = false) {
        def fields = rootType.fields as List<Map>
        def arrays = fields.findAll { it.type.kind == "array" }
        def vectors = new LinkedHashSet<String>()
        def structs = new LinkedHashMap<String, Map>()
        collectTypes(rootType, vectors, structs)
        structs.remove(rootType.name)

        def sb = new StringBuilder()
        sb << "// GENERATED by generateShaderRecords from Slang reflection — DO NOT EDIT.\n"
        sb << "package ${packageName};\n\n"
        sb << "import java.nio.ByteBuffer;\n"
        sb << "import java.util.Objects;\n"
        if (containsKind(rootType, "matrix")) sb << "import org.joml.Matrix4fc;\n"
        sb << "\npublic record ${className}(\n"
        fields.eachWithIndex { field, i ->
            sb << "        ${javaType(field.type as Map)} ${field.name}${i + 1 == fields.size() ? '' : ','}\n"
        }
        sb << ") {\n"
        sb << "    public static final int BYTE_SIZE = ${byteSize};\n"
        fields.each { field ->
            sb << "    public static final int ${upperSnake(field.name)}_OFFSET = ${field.binding.offset ?: 0};\n"
        }
        arrays.each { field ->
            sb << "    public static final int ${upperSnake(field.name)}_CAPACITY = ${field.type.elementCount};\n"
            sb << "    public static final int ${upperSnake(field.name)}_STRIDE = ${field.type.uniformStride ?: field.binding.elementStride};\n"
        }

        def validatedFields = fields.findAll { it.type.kind in ["matrix", "struct", "array"] }
        if (!validatedFields.isEmpty()) {
            sb << "\n    public ${className} {\n"
            validatedFields.each { field ->
                sb << "        Objects.requireNonNull(${field.name}, \"${field.name}\");\n"
                if (field.type.kind == "array") {
                    sb << "        if (${field.name}.length > ${upperSnake(field.name)}_CAPACITY) {\n"
                    sb << "            throw new IllegalArgumentException(\"${field.name} has \" + ${field.name}.length + \" entries; capacity is \" + ${upperSnake(field.name)}_CAPACITY);\n"
                    sb << "        }\n"
                    sb << "        ${field.name} = ${field.name}.clone();\n"
                    sb << "        for (var value : ${field.name}) Objects.requireNonNull(value, \"${field.name} entry\");\n"
                }
            }
            sb << "    }\n"
        }

        sb << "\n    /** Clears reflected padding for a record starting at the absolute byte offset. */\n"
        sb << "    public static void clearPadding(ByteBuffer dst, int offset) {\n"
        paddingRanges(rootType, byteSize).each { range ->
            emitZeroRange(sb, range[0], range[1], "        ", "offset")
        }
        sb << "    }\n"
        sb << "\n    public void write(ByteBuffer dst) {\n"
        sb << "        Objects.requireNonNull(dst, \"dst\");\n"
        sb << "        if (dst.capacity() < BYTE_SIZE) throw new IllegalArgumentException(\"${className} buffer is too small: \" + dst.capacity());\n"
        sb << "        clearPadding(dst, 0);\n"
        arrays.each { field ->
            int offset = (field.binding.offset ?: 0) as int
            int stride = (field.type.uniformStride ?: field.binding.elementStride) as int
            int byteSizeForCapacity = (field.type.elementCount as int) * stride
            sb << "        for (int i = ${field.name}().length * ${stride}; i < ${byteSizeForCapacity}; i++) dst.put(${offset} + i, (byte) 0);\n"
        }
        fields.each { field ->
            emitWrite(sb, field.type as Map, field.binding as Map, "${field.name}()", "0", "        ", 0)
        }
        sb << "    }\n\n"

        if (emitReader) {
            sb << "    public static ${className} read(ByteBuffer src) {\n"
            sb << "        Objects.requireNonNull(src, \"src\");\n"
            sb << "        if (src.capacity() < BYTE_SIZE) throw new IllegalArgumentException(\"${className} buffer is too small: \" + src.capacity());\n"
            sb << "        return new ${className}(\n"
            fields.eachWithIndex { field, i ->
                sb << "                ${emitReadExpression(field.type as Map, field.binding as Map, '0')}"
                sb << (i + 1 == fields.size() ? "\n" : ",\n")
            }
            sb << "        );\n"
            sb << "    }\n\n"
        }

        vectors.sort().each { name ->
            def count = Integer.parseInt(name.substring(name.length() - 1))
            def primitive = name.startsWith("Float") ? "float" : "int"
            def lanes = ["x", "y", "z", "w"].take(count)
            sb << "    public record ${name}(" + lanes.collect { "${primitive} ${it}" }.join(", ") + ") {}\n"
        }
        if (!vectors.isEmpty() && !structs.isEmpty()) sb << "\n"
        structs.each { name, type ->
            sb << "    public record ${name}("
            sb << (type.fields as List<Map>).collect { "${javaType(it.type as Map)} ${it.name}" }.join(", ")
            sb << ") {}\n"
        }
        sb << "}\n"
        sb.toString()
    }

}
