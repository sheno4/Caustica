package dev.comfyfluffy.caustica.example.gltfviewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Strict decoder for the static glTF 2.0 triangle-mesh subset consumed by Caustica extensions. */
public final class GltfLoader {
  private static final int GLB_MAGIC = 0x46546c67;
  private static final int JSON_CHUNK = 0x4e4f534a;
  private static final int BIN_CHUNK = 0x004e4942;
  private static final Set<String> SUPPORTED_EXTENSIONS =
      Set.of("KHR_materials_ior", "KHR_materials_transmission", "KHR_materials_emissive_strength");

  private GltfLoader() {}

  public record Asset(
      Scene scene,
      List<Node> nodes,
      List<Mesh> meshes,
      List<Material> materials,
      List<Texture> textures,
      List<Image> images) {
    public Asset {
      Objects.requireNonNull(scene);
      nodes = List.copyOf(nodes);
      meshes = List.copyOf(meshes);
      materials = List.copyOf(materials);
      textures = List.copyOf(textures);
      images = List.copyOf(images);
    }
  }

  public record Scene(String name, int[] roots) {
    public Scene {
      Objects.requireNonNull(name);
      roots = roots.clone();
    }

    @Override
    public int[] roots() {
      return roots.clone();
    }
  }

  /** The matrix is the authored local, column-major glTF transform. */
  public record Node(String name, int mesh, float[] localMatrix, int[] children) {
    public Node {
      Objects.requireNonNull(name);
      localMatrix = localMatrix.clone();
      children = children.clone();
    }

    @Override
    public float[] localMatrix() {
      return localMatrix.clone();
    }

    @Override
    public int[] children() {
      return children.clone();
    }
  }

  public record Mesh(String name, List<Primitive> primitives) {
    public Mesh {
      Objects.requireNonNull(name);
      primitives = List.copyOf(primitives);
    }
  }

  /**
   * Attributes remain in authored mesh-local space. Empty arrays denote absent optional attributes.
   */
  public record Primitive(
      float[] positions,
      int[] indices,
      float[] normals,
      float[] tangents,
      float[] colors,
      float[] textureCoordinates,
      int material) {
    public Primitive {
      positions = positions.clone();
      indices = indices.clone();
      normals = normals.clone();
      tangents = tangents.clone();
      colors = colors.clone();
      textureCoordinates = textureCoordinates.clone();
    }

    @Override
    public float[] positions() {
      return positions.clone();
    }

    @Override
    public int[] indices() {
      return indices.clone();
    }

    @Override
    public float[] normals() {
      return normals.clone();
    }

    @Override
    public float[] tangents() {
      return tangents.clone();
    }

    @Override
    public float[] colors() {
      return colors.clone();
    }

    @Override
    public float[] textureCoordinates() {
      return textureCoordinates.clone();
    }
  }

  public record Material(
      String name,
      float baseColorR,
      float baseColorG,
      float baseColorB,
      float baseColorA,
      TextureInfo baseColorTexture,
      float metallic,
      float roughness,
      TextureInfo metallicRoughnessTexture,
      TextureInfo normalTexture,
      float emissiveR,
      float emissiveG,
      float emissiveB,
      TextureInfo emissiveTexture,
      AlphaMode alphaMode,
      float alphaCutoff,
      boolean doubleSided,
      float ior,
      float transmissionFactor,
      float emissiveStrength) {
    public Material {
      Objects.requireNonNull(name);
      Objects.requireNonNull(alphaMode);
    }
  }

  public record TextureInfo(int texture, int texCoord, float scale) {}

  public record Texture(String name, int image, Sampler sampler) {
    public Texture {
      Objects.requireNonNull(name);
      Objects.requireNonNull(sampler);
    }
  }

  /** Encoded holds the original PNG or JPEG file bytes. */
  public record Image(String name, String mimeType, byte[] encoded) {
    public Image {
      Objects.requireNonNull(name);
      Objects.requireNonNull(mimeType);
      encoded = encoded.clone();
    }

    @Override
    public byte[] encoded() {
      return encoded.clone();
    }
  }

  public record Sampler(MagFilter magFilter, MinFilter minFilter, Wrap wrapS, Wrap wrapT) {
    public Sampler {
      Objects.requireNonNull(magFilter);
      Objects.requireNonNull(minFilter);
      Objects.requireNonNull(wrapS);
      Objects.requireNonNull(wrapT);
    }
  }

  public enum AlphaMode {
    OPAQUE,
    MASK,
    BLEND
  }

  public enum MagFilter {
    NEAREST,
    LINEAR
  }

  public enum MinFilter {
    NEAREST,
    LINEAR,
    NEAREST_MIPMAP_NEAREST,
    LINEAR_MIPMAP_NEAREST,
    NEAREST_MIPMAP_LINEAR,
    LINEAR_MIPMAP_LINEAR
  }

  public enum Wrap {
    CLAMP_TO_EDGE,
    MIRRORED_REPEAT,
    REPEAT
  }

  @FunctionalInterface
  public interface ResourceResolver {
    byte[] read(String uri) throws IOException;
  }

  public static Asset load(Path path) throws IOException {
    Path source = path.toAbsolutePath().normalize();
    Path base = source.getParent();
    return load(
        Files.readAllBytes(source),
        uri -> {
          Path resolved = base.resolve(URI.create(uri).getPath()).normalize();
          if (!resolved.startsWith(base))
            throw new IOException("glTF resource escapes its source directory: " + uri);
          return Files.readAllBytes(resolved);
        });
  }

  public static Asset load(InputStream input, ResourceResolver resolver) throws IOException {
    return load(input.readAllBytes(), resolver);
  }

  public static Asset load(byte[] source, ResourceResolver resolver) throws IOException {
    Objects.requireNonNull(source);
    Objects.requireNonNull(resolver);
    ParsedDocument doc = parseDocument(source);
    JsonObject root;
    try (var reader =
        new InputStreamReader(new ByteArrayInputStream(doc.json), StandardCharsets.UTF_8)) {
      root = JsonParser.parseReader(reader).getAsJsonObject();
    } catch (RuntimeException e) {
      throw new IOException("invalid glTF JSON", e);
    }
    return new Decoder(root, doc.binaryChunk, resolver).decode();
  }

  private static ParsedDocument parseDocument(byte[] source) throws IOException {
    if (source.length < 4
        || ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).getInt() != GLB_MAGIC)
      return new ParsedDocument(source, null);
    if (source.length < 20) throw new IOException("truncated GLB header");
    ByteBuffer b = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
    b.getInt();
    int version = b.getInt();
    int declared = b.getInt();
    if (version != 2) throw new IOException("unsupported GLB version " + version);
    if (declared != source.length) throw new IOException("GLB length does not match its header");
    byte[] json = null;
    byte[] bin = null;
    while (b.remaining() >= 8) {
      int length = b.getInt();
      int type = b.getInt();
      if (length < 0 || length > b.remaining()) throw new IOException("invalid GLB chunk length");
      byte[] chunk = new byte[length];
      b.get(chunk);
      if (type == JSON_CHUNK && json == null) json = chunk;
      else if (type == BIN_CHUNK && bin == null) bin = chunk;
    }
    if (b.hasRemaining()) throw new IOException("truncated GLB chunk header");
    if (json == null) throw new IOException("GLB has no JSON chunk");
    return new ParsedDocument(json, bin);
  }

  private record ParsedDocument(byte[] json, byte[] binaryChunk) {}

  private static final class Decoder {
    private final JsonObject root;
    private final byte[] binaryChunk;
    private final ResourceResolver resolver;
    private final List<byte[]> buffers = new ArrayList<>();

    Decoder(JsonObject root, byte[] binaryChunk, ResourceResolver resolver) {
      this.root = root;
      this.binaryChunk = binaryChunk;
      this.resolver = resolver;
    }

    Asset decode() throws IOException {
      JsonObject asset = object(root, "asset", true);
      if (!"2.0".equals(string(asset, "version", true)))
        throw new IOException("only glTF 2.0 is supported");
      rejectNonEmpty(root, "animations", "animated glTF is not supported");
      rejectNonEmpty(root, "skins", "skinned glTF is not supported");
      validateExtensions();
      loadBuffers();
      List<Image> images = decodeImages();
      List<Sampler> samplers = decodeSamplers();
      List<Texture> textures = decodeTextures(images.size(), samplers);
      List<Material> materials = decodeMaterials(textures.size());
      List<Mesh> meshes = decodeMeshes(materials.size());
      List<Node> nodes = decodeNodes(meshes.size());
      Scene scene = decodeScene(nodes);
      validateHierarchy(nodes, scene.roots());
      return new Asset(scene, nodes, meshes, materials, textures, images);
    }

    private void validateExtensions() throws IOException {
      JsonArray a = array(root, "extensionsRequired", false);
      if (a != null)
        for (JsonElement e : a) {
          String n = e.getAsString();
          if (!SUPPORTED_EXTENSIONS.contains(n))
            throw new IOException("unsupported required glTF extension " + n);
        }
    }

    private void loadBuffers() throws IOException {
      JsonArray a = array(root, "buffers", false);
      if (a == null) return;
      for (int i = 0; i < a.size(); i++) {
        JsonObject d = a.get(i).getAsJsonObject();
        int length = integer(d, "byteLength", -1);
        if (length < 0) throw new IOException("buffer " + i + " has no byteLength");
        String uri = string(d, "uri", false);
        byte[] data;
        if (uri == null) {
          if (i != 0 || binaryChunk == null)
            throw new IOException("buffer " + i + " has no URI or GLB BIN chunk");
          data = binaryChunk;
        } else data = uri.startsWith("data:") ? decodeDataUri(uri) : resolver.read(uri);
        if (data.length < length)
          throw new IOException("buffer " + i + " is shorter than byteLength");
        buffers.add(data);
      }
    }

    private List<Image> decodeImages() throws IOException {
      JsonArray a = array(root, "images", false);
      if (a == null) return List.of();
      List<Image> out = new ArrayList<>();
      for (int i = 0; i < a.size(); i++) {
        JsonObject d = a.get(i).getAsJsonObject();
        String uri = string(d, "uri", false), mime = string(d, "mimeType", false);
        int view = integer(d, "bufferView", -1);
        if ((uri == null) == (view < 0))
          throw new IOException("image " + i + " must define exactly one of uri or bufferView");
        byte[] bytes;
        if (uri != null) {
          bytes = uri.startsWith("data:") ? decodeDataUri(uri) : resolver.read(uri);
          if (mime == null) mime = uri.startsWith("data:") ? dataMime(uri) : mimeFromUri(uri);
        } else {
          if (mime == null) throw new IOException("bufferView image " + i + " has no mimeType");
          bytes = bufferViewBytes(view);
        }
        mime = normalizeMime(mime);
        if (!mime.equals("image/png") && !mime.equals("image/jpeg"))
          throw new IOException("image " + i + " must be PNG or JPEG, found " + mime);
        out.add(new Image(name(d), mime, bytes));
      }
      return List.copyOf(out);
    }

    private List<Sampler> decodeSamplers() throws IOException {
      JsonArray a = array(root, "samplers", false);
      if (a == null) return List.of();
      List<Sampler> out = new ArrayList<>();
      for (JsonElement e : a) {
        JsonObject d = e.getAsJsonObject();
        MagFilter mag =
            switch (integer(d, "magFilter", 9729)) {
              case 9728 -> MagFilter.NEAREST;
              case 9729 -> MagFilter.LINEAR;
              default -> throw new IOException("invalid sampler magFilter");
            };
        MinFilter min =
            switch (integer(d, "minFilter", 9987)) {
              case 9728 -> MinFilter.NEAREST;
              case 9729 -> MinFilter.LINEAR;
              case 9984 -> MinFilter.NEAREST_MIPMAP_NEAREST;
              case 9985 -> MinFilter.LINEAR_MIPMAP_NEAREST;
              case 9986 -> MinFilter.NEAREST_MIPMAP_LINEAR;
              case 9987 -> MinFilter.LINEAR_MIPMAP_LINEAR;
              default -> throw new IOException("invalid sampler minFilter");
            };
        out.add(
            new Sampler(
                mag, min, wrap(integer(d, "wrapS", 10497)), wrap(integer(d, "wrapT", 10497))));
      }
      return List.copyOf(out);
    }

    private List<Texture> decodeTextures(int imageCount, List<Sampler> samplers)
        throws IOException {
      JsonArray a = array(root, "textures", false);
      if (a == null) return List.of();
      List<Texture> out = new ArrayList<>();
      Sampler defaults =
          new Sampler(MagFilter.LINEAR, MinFilter.LINEAR_MIPMAP_LINEAR, Wrap.REPEAT, Wrap.REPEAT);
      for (int i = 0; i < a.size(); i++) {
        JsonObject d = a.get(i).getAsJsonObject();
        int image = integer(d, "source", -1);
        int sampler = integer(d, "sampler", -1);
        if (image < 0 || image >= imageCount)
          throw new IOException("texture " + i + " has invalid source image");
        if (sampler >= samplers.size())
          throw new IOException("texture " + i + " has invalid sampler");
        out.add(new Texture(name(d), image, sampler < 0 ? defaults : samplers.get(sampler)));
      }
      return List.copyOf(out);
    }

    private List<Material> decodeMaterials(int textureCount) throws IOException {
      JsonArray a = array(root, "materials", false);
      if (a == null) return List.of();
      List<Material> out = new ArrayList<>();
      for (JsonElement e : a) {
        JsonObject d = e.getAsJsonObject();
        JsonObject pbr = object(d, "pbrMetallicRoughness", false);
        float[] base =
            pbr == null
                ? new float[] {1, 1, 1, 1}
                : vector(pbr, "baseColorFactor", 4, new float[] {1, 1, 1, 1});
        float[] emissive = vector(d, "emissiveFactor", 3, new float[] {0, 0, 0});
        float metallic = pbr == null ? 1 : number(pbr, "metallicFactor", 1);
        float roughness = pbr == null ? 1 : number(pbr, "roughnessFactor", 1);
        float ior = 1.5f;
        float transmission = 0;
        float emissiveStrength = 1;
        TextureInfo baseTex =
            pbr == null ? null : textureInfo(pbr, "baseColorTexture", textureCount);
        TextureInfo mrTex =
            pbr == null ? null : textureInfo(pbr, "metallicRoughnessTexture", textureCount);
        TextureInfo normalTex = textureInfo(d, "normalTexture", textureCount);
        TextureInfo emissiveTex = textureInfo(d, "emissiveTexture", textureCount);
        JsonObject ext = object(d, "extensions", false);
        if (ext != null) {
          JsonObject x = object(ext, "KHR_materials_ior", false);
          if (x != null) ior = number(x, "ior", 1.5f);
          x = object(ext, "KHR_materials_transmission", false);
          if (x != null) {
            transmission = number(x, "transmissionFactor", 0);
            if (x.has("transmissionTexture"))
              throw new IOException("KHR_materials_transmission textures are not supported");
          }
          x = object(ext, "KHR_materials_emissive_strength", false);
          if (x != null) emissiveStrength = number(x, "emissiveStrength", 1);
        }
        AlphaMode alpha;
        try {
          String s = string(d, "alphaMode", false);
          alpha = AlphaMode.valueOf(s == null ? "OPAQUE" : s);
        } catch (IllegalArgumentException ex) {
          throw new IOException("invalid material alphaMode", ex);
        }
        out.add(
            new Material(
                name(d),
                base[0],
                base[1],
                base[2],
                base[3],
                baseTex,
                metallic,
                roughness,
                mrTex,
                normalTex,
                emissive[0],
                emissive[1],
                emissive[2],
                emissiveTex,
                alpha,
                number(d, "alphaCutoff", .5f),
                bool(d, "doubleSided", false),
                ior,
                transmission,
                emissiveStrength));
      }
      return List.copyOf(out);
    }

    private TextureInfo textureInfo(JsonObject owner, String property, int count)
        throws IOException {
      JsonObject d = object(owner, property, false);
      if (d == null) return null;
      int texture = integer(d, "index", -1);
      if (texture < 0 || texture >= count)
        throw new IOException(property + " has invalid texture index");
      return new TextureInfo(texture, integer(d, "texCoord", 0), number(d, "scale", 1));
    }

    private List<Mesh> decodeMeshes(int materialCount) throws IOException {
      JsonArray a = array(root, "meshes", false);
      if (a == null) return List.of();
      List<Mesh> out = new ArrayList<>();
      for (JsonElement e : a) {
        JsonObject d = e.getAsJsonObject();
        if (d.has("weights")) throw new IOException("morph target weights are not supported");
        List<Primitive> primitives = new ArrayList<>();
        for (JsonElement p : array(d, "primitives", true))
          primitives.add(decodePrimitive(p.getAsJsonObject(), materialCount));
        out.add(new Mesh(name(d), primitives));
      }
      return List.copyOf(out);
    }

    private Primitive decodePrimitive(JsonObject p, int materialCount) throws IOException {
      if (integer(p, "mode", 4) != 4)
        throw new IOException("only TRIANGLES mesh primitives are supported");
      rejectNonEmpty(p, "targets", "morph targets are not supported");
      JsonObject ext = object(p, "extensions", false);
      if (ext != null
          && (ext.has("KHR_draco_mesh_compression") || ext.has("EXT_meshopt_compression")))
        throw new IOException("compressed mesh primitives are not supported");
      JsonObject attrs = object(p, "attributes", true);
      int position = integer(attrs, "POSITION", -1);
      if (position < 0) throw new IOException("mesh primitive has no POSITION attribute");
      float[] positions = floatAttribute(position, 3, "POSITION", false);
      int count = positions.length / 3;
      float[]
          normals =
              attrs.has("NORMAL")
                  ? floatAttribute(attrs.get("NORMAL").getAsInt(), 3, "NORMAL", false)
                  : new float[0],
          tangents =
              attrs.has("TANGENT")
                  ? floatAttribute(attrs.get("TANGENT").getAsInt(), 4, "TANGENT", false)
                  : new float[0],
          colors =
              attrs.has("COLOR_0") ? colors(attrs.get("COLOR_0").getAsInt(), count) : new float[0],
          uvs =
              attrs.has("TEXCOORD_0")
                  ? floatAttribute(attrs.get("TEXCOORD_0").getAsInt(), 2, "TEXCOORD_0", true)
                  : new float[0];
      requireElements(normals, count, 3, "NORMAL");
      requireElements(tangents, count, 4, "TANGENT");
      requireElements(uvs, count, 2, "TEXCOORD_0");
      int[] indices = p.has("indices") ? indices(p.get("indices").getAsInt()) : sequence(count);
      if (indices.length == 0 || indices.length % 3 != 0)
        throw new IOException("TRIANGLES indices are not complete triangles");
      for (int index : indices)
        if (index < 0 || index >= count)
          throw new IOException("mesh index outside POSITION accessor");
      int material = integer(p, "material", -1);
      if (material < -1 || material >= materialCount)
        throw new IOException("primitive material index outside materials");
      return new Primitive(positions, indices, normals, tangents, colors, uvs, material);
    }

    private List<Node> decodeNodes(int meshCount) throws IOException {
      JsonArray a = array(root, "nodes", false);
      if (a == null) return List.of();
      List<Node> out = new ArrayList<>();
      for (JsonElement e : a) {
        JsonObject d = e.getAsJsonObject();
        if (d.has("skin") || d.has("weights"))
          throw new IOException("skinned or morphed nodes are not supported");
        int mesh = integer(d, "mesh", -1);
        if (mesh < -1 || mesh >= meshCount) throw new IOException("node mesh index outside meshes");
        out.add(new Node(name(d), mesh, nodeTransform(d), intArray(d, "children")));
      }
      return List.copyOf(out);
    }

    private Scene decodeScene(List<Node> nodes) throws IOException {
      JsonArray a = array(root, "scenes", true);
      int index = integer(root, "scene", a.size() == 1 ? 0 : -1);
      if (index < 0 || index >= a.size()) throw new IOException("glTF has no valid default scene");
      JsonObject d = a.get(index).getAsJsonObject();
      int[] roots = intArray(d, "nodes");
      for (int node : roots)
        if (node < 0 || node >= nodes.size())
          throw new IOException("scene root index outside nodes");
      return new Scene(name(d), roots);
    }

    private void validateHierarchy(List<Node> nodes, int[] roots) throws IOException {
      int[] parents = new int[nodes.size()];
      Arrays.fill(parents, -1);
      for (int parent = 0; parent < nodes.size(); parent++) {
        for (int child : nodes.get(parent).children) {
          if (child < 0 || child >= nodes.size())
            throw new IOException("child index outside nodes");
          if (parents[child] >= 0) throw new IOException("glTF node has multiple parents");
          parents[child] = parent;
        }
      }
      for (int root : roots) {
        if (parents[root] >= 0) throw new IOException("scene root node also has a parent");
      }
      byte[] state = new byte[nodes.size()];
      for (int root : roots) validateNode(root, nodes, state);
      for (int i = 0; i < nodes.size(); i++) if (state[i] == 0) validateNode(i, nodes, state);
    }

    private void validateNode(int index, List<Node> nodes, byte[] state) throws IOException {
      if (index < 0 || index >= nodes.size()) throw new IOException("child index outside nodes");
      if (state[index] == 1) throw new IOException("glTF node hierarchy contains a cycle");
      if (state[index] == 2) return;
      state[index] = 1;
      for (int child : nodes.get(index).children) validateNode(child, nodes, state);
      state[index] = 2;
    }

    private float[] floatAttribute(int index, int components, String semantic, boolean integers)
        throws IOException {
      Accessor a = accessor(index);
      if (a.components != components)
        throw new IOException(semantic + " accessor must have " + components + " components");
      if (a.componentType != 5126
          && (!integers || !a.normalized || (a.componentType != 5121 && a.componentType != 5123)))
        throw new IOException(semantic + " accessor has unsupported component type");
      float[] out = new float[a.count * components];
      for (int i = 0; i < a.count; i++) {
        for (int c = 0; c < components; c++) {
          float value = a.floatValue(i, c);
          if (!Float.isFinite(value))
            throw new IOException(semantic + " contains a non-finite value");
          out[i * components + c] = value;
        }
      }
      return out;
    }

    private float[] colors(int index, int count) throws IOException {
      Accessor a = accessor(index);
      if (a.components != 3 && a.components != 4)
        throw new IOException("COLOR_0 must be VEC3 or VEC4");
      if (a.componentType != 5126
          && ((a.componentType != 5121 && a.componentType != 5123) || !a.normalized))
        throw new IOException("COLOR_0 must use FLOAT or normalized unsigned integer components");
      if (a.count != count) throw new IOException("COLOR_0 count does not match POSITION");
      float[] out = new float[count * 4];
      for (int i = 0; i < count; i++) {
        for (int c = 0; c < a.components; c++) {
          float value = a.floatValue(i, c);
          if (!Float.isFinite(value)) throw new IOException("COLOR_0 contains a non-finite value");
          out[i * 4 + c] = value;
        }
        if (a.components == 3) out[i * 4 + 3] = 1;
      }
      return out;
    }

    private int[] indices(int index) throws IOException {
      Accessor a = accessor(index);
      if (a.components != 1
          || a.componentType != 5121 && a.componentType != 5123 && a.componentType != 5125)
        throw new IOException("indices must use unsigned byte, short, or int SCALAR components");
      int[] out = new int[a.count];
      for (int i = 0; i < out.length; i++) out[i] = a.indexValue(i);
      return out;
    }

    private Accessor accessor(int index) throws IOException {
      JsonArray accessors = array(root, "accessors", true);
      if (index < 0 || index >= accessors.size())
        throw new IOException("accessor index outside accessors: " + index);
      JsonObject d = accessors.get(index).getAsJsonObject();
      if (d.has("sparse")) throw new IOException("sparse accessors are not supported");
      int viewIndex = integer(d, "bufferView", -1);
      if (viewIndex < 0) throw new IOException("accessor without bufferView is not supported");
      JsonObject view = bufferView(viewIndex);
      JsonObject extensions = object(view, "extensions", false);
      if (extensions != null && extensions.has("EXT_meshopt_compression"))
        throw new IOException("meshopt-compressed bufferViews are not supported");
      int bufferIndex = integer(view, "buffer", -1);
      if (bufferIndex < 0 || bufferIndex >= buffers.size())
        throw new IOException("bufferView buffer outside buffers");
      int type = integer(d, "componentType", -1);
      int bytes = componentBytes(type);
      int components = components(string(d, "type", true));
      int count = integer(d, "count", -1);
      int viewOffset = integer(view, "byteOffset", 0);
      int offset = integer(d, "byteOffset", 0);
      int stride = integer(view, "byteStride", bytes * components);
      int viewLength = integer(view, "byteLength", -1);
      if (count < 0) throw new IOException("accessor has invalid count");
      if (stride < bytes * components || stride % bytes != 0)
        throw new IOException("invalid accessor byteStride");
      long end =
          (long) offset
              + (count == 0 ? 0 : (long) (count - 1) * stride + (long) bytes * components);
      if (viewOffset < 0
          || offset < 0
          || viewLength < 0
          || end > viewLength
          || (long) viewOffset + viewLength > buffers.get(bufferIndex).length)
        throw new IOException("accessor exceeds its bufferView");
      return new Accessor(
          buffers.get(bufferIndex),
          viewOffset + offset,
          count,
          components,
          type,
          stride,
          bool(d, "normalized", false));
    }

    private JsonObject bufferView(int index) throws IOException {
      JsonArray a = array(root, "bufferViews", true);
      if (index < 0 || index >= a.size())
        throw new IOException("bufferView index outside bufferViews");
      return a.get(index).getAsJsonObject();
    }

    private byte[] bufferViewBytes(int index) throws IOException {
      JsonObject d = bufferView(index);
      int buffer = integer(d, "buffer", -1);
      int offset = integer(d, "byteOffset", 0);
      int length = integer(d, "byteLength", -1);
      if (buffer < 0
          || buffer >= buffers.size()
          || offset < 0
          || length < 0
          || (long) offset + length > buffers.get(buffer).length)
        throw new IOException("image bufferView exceeds its buffer");
      return Arrays.copyOfRange(buffers.get(buffer), offset, offset + length);
    }
  }

  private record Accessor(
      byte[] buffer,
      int offset,
      int count,
      int components,
      int componentType,
      int stride,
      boolean normalized) {
    float floatValue(int element, int component) {
      int size = componentType == 5121 ? 1 : componentType == 5123 ? 2 : 4;
      int at = offset + element * stride + component * size;
      return switch (componentType) {
        case 5121 -> (buffer[at] & 255) / 255f;
        case 5123 ->
            Short.toUnsignedInt(
                    ByteBuffer.wrap(buffer, at, 2).order(ByteOrder.LITTLE_ENDIAN).getShort())
                / 65535f;
        case 5126 -> ByteBuffer.wrap(buffer, at, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
        default -> throw new IllegalStateException();
      };
    }

    int indexValue(int element) {
      int at = offset + element * stride;
      return switch (componentType) {
        case 5121 -> buffer[at] & 255;
        case 5123 ->
            Short.toUnsignedInt(
                ByteBuffer.wrap(buffer, at, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
        case 5125 -> ByteBuffer.wrap(buffer, at, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        default -> throw new IllegalStateException();
      };
    }
  }

  private static Wrap wrap(int value) throws IOException {
    return switch (value) {
      case 33071 -> Wrap.CLAMP_TO_EDGE;
      case 33648 -> Wrap.MIRRORED_REPEAT;
      case 10497 -> Wrap.REPEAT;
      default -> throw new IOException("invalid sampler wrap mode");
    };
  }

  private static String normalizeMime(String mime) throws IOException {
    if (mime == null) throw new IOException("image URI has no recognizable MIME type");
    String n = mime.toLowerCase(Locale.ROOT);
    return n.equals("image/jpg") ? "image/jpeg" : n;
  }

  private static String mimeFromUri(String uri) {
    String s = uri.toLowerCase(Locale.ROOT);
    int q = s.indexOf('?');
    if (q >= 0) s = s.substring(0, q);
    if (s.endsWith(".png")) return "image/png";
    return s.endsWith(".jpg") || s.endsWith(".jpeg") ? "image/jpeg" : null;
  }

  private static String dataMime(String uri) throws IOException {
    int semicolon = uri.indexOf(';');
    if (semicolon < 5) throw new IOException("data image URI has no MIME type");
    return uri.substring(5, semicolon);
  }

  private static byte[] decodeDataUri(String uri) throws IOException {
    int comma = uri.indexOf(',');
    if (comma < 0 || !uri.substring(0, comma).endsWith(";base64"))
      throw new IOException("only base64 data URIs are supported");
    try {
      return Base64.getDecoder().decode(uri.substring(comma + 1));
    } catch (IllegalArgumentException e) {
      throw new IOException("invalid base64 data URI", e);
    }
  }

  private static float[] nodeTransform(JsonObject node) throws IOException {
    if (node.has("matrix")) {
      if (node.has("translation") || node.has("rotation") || node.has("scale"))
        throw new IOException("node may not define both matrix and TRS");
      return vector(node, "matrix", 16, null);
    }
    float[] t = vector(node, "translation", 3, new float[] {0, 0, 0});
    float[] q = vector(node, "rotation", 4, new float[] {0, 0, 0, 1});
    float[] s = vector(node, "scale", 3, new float[] {1, 1, 1});
    float length = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
    if (!(length > 1e-12f)) throw new IOException("node rotation quaternion is zero");
    float x = q[0] / length;
    float y = q[1] / length;
    float z = q[2] / length;
    float w = q[3] / length;
    float[] m = identity();
    m[0] = (1 - 2 * (y * y + z * z)) * s[0];
    m[1] = (2 * (x * y + z * w)) * s[0];
    m[2] = (2 * (x * z - y * w)) * s[0];
    m[4] = (2 * (x * y - z * w)) * s[1];
    m[5] = (1 - 2 * (x * x + z * z)) * s[1];
    m[6] = (2 * (y * z + x * w)) * s[1];
    m[8] = (2 * (x * z + y * w)) * s[2];
    m[9] = (2 * (y * z - x * w)) * s[2];
    m[10] = (1 - 2 * (x * x + y * y)) * s[2];
    m[12] = t[0];
    m[13] = t[1];
    m[14] = t[2];
    return m;
  }

  private static float[] identity() {
    return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  }

  private static int[] sequence(int count) {
    int[] a = new int[count];
    for (int i = 0; i < count; i++) a[i] = i;
    return a;
  }

  private static void requireElements(float[] values, int count, int components, String semantic)
      throws IOException {
    if (values.length != 0 && values.length != count * components)
      throw new IOException(semantic + " count does not match POSITION");
  }

  private static int componentBytes(int type) throws IOException {
    return switch (type) {
      case 5121 -> 1;
      case 5123 -> 2;
      case 5125, 5126 -> 4;
      default -> throw new IOException("unsupported accessor componentType " + type);
    };
  }

  private static int components(String type) throws IOException {
    return switch (type) {
      case "SCALAR" -> 1;
      case "VEC2" -> 2;
      case "VEC3" -> 3;
      case "VEC4" -> 4;
      default -> throw new IOException("unsupported accessor type " + type);
    };
  }

  private static String name(JsonObject o) throws IOException {
    String n = string(o, "name", false);
    return n == null ? "" : n;
  }

  private static int[] intArray(JsonObject o, String name) throws IOException {
    JsonArray a = array(o, name, false);
    if (a == null) return new int[0];
    int[] out = new int[a.size()];
    for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsInt();
    return out;
  }

  private static float[] vector(JsonObject o, String name, int length, float[] defaults)
      throws IOException {
    if (!o.has(name)) {
      if (defaults == null) throw new IOException("missing " + name);
      return defaults;
    }
    JsonArray a = o.getAsJsonArray(name);
    if (a.size() != length) throw new IOException(name + " must have " + length + " components");
    float[] out = new float[length];
    for (int i = 0; i < length; i++) {
      out[i] = a.get(i).getAsFloat();
      if (!Float.isFinite(out[i])) throw new IOException(name + " must contain finite numbers");
    }
    return out;
  }

  private static JsonObject object(JsonObject o, String name, boolean required) throws IOException {
    JsonElement e = o.get(name);
    if (e == null) {
      if (required) throw new IOException("missing glTF object " + name);
      return null;
    }
    if (!e.isJsonObject()) throw new IOException(name + " must be an object");
    return e.getAsJsonObject();
  }

  private static JsonArray array(JsonObject o, String name, boolean required) throws IOException {
    JsonElement e = o.get(name);
    if (e == null) {
      if (required) throw new IOException("missing glTF array " + name);
      return null;
    }
    if (!e.isJsonArray()) throw new IOException(name + " must be an array");
    return e.getAsJsonArray();
  }

  private static String string(JsonObject o, String name, boolean required) throws IOException {
    JsonElement e = o.get(name);
    if (e == null) {
      if (required) throw new IOException("missing glTF string " + name);
      return null;
    }
    try {
      return e.getAsString();
    } catch (RuntimeException x) {
      throw new IOException(name + " must be a string", x);
    }
  }

  private static int integer(JsonObject o, String name, int defaults) throws IOException {
    if (!o.has(name)) return defaults;
    try {
      return o.get(name).getAsInt();
    } catch (RuntimeException e) {
      throw new IOException(name + " must be an integer", e);
    }
  }

  private static float number(JsonObject o, String name, float defaults) throws IOException {
    if (!o.has(name)) return defaults;
    try {
      float value = o.get(name).getAsFloat();
      if (!Float.isFinite(value)) throw new IOException(name + " must be finite");
      return value;
    } catch (RuntimeException e) {
      throw new IOException(name + " must be a number", e);
    }
  }

  private static boolean bool(JsonObject o, String name, boolean defaults) throws IOException {
    if (!o.has(name)) return defaults;
    try {
      return o.get(name).getAsBoolean();
    } catch (RuntimeException e) {
      throw new IOException(name + " must be a boolean", e);
    }
  }

  private static void rejectNonEmpty(JsonObject o, String name, String message) throws IOException {
    if (!o.has(name)) return;
    JsonElement e = o.get(name);
    if (e.isJsonArray() && e.getAsJsonArray().isEmpty()
        || e.isJsonObject() && e.getAsJsonObject().isEmpty()) return;
    throw new IOException(message);
  }
}
