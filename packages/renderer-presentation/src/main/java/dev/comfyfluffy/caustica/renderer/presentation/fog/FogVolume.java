package dev.comfyfluffy.caustica.renderer.presentation.fog;

import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.program.ShaderDefinition;
import dev.comfyfluffy.caustica.api.program.ShaderSource;
import dev.comfyfluffy.caustica.api.program.VolumeId;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.view.SpatialMedium;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.renderer.presentation.gen.FogVolumeData;
import dev.comfyfluffy.caustica.renderer.presentation.gen.FogVolumeData.Float4;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteOrder;

/** Prepares immutable spatial-field and frame-parameter bindings before a view is captured. */
public final class FogVolume implements AutoCloseable {
    public interface Binding { }
    public interface Instance { }
    public static final ShaderDataType<Binding> BINDING_DATA = ShaderDataType.create("fog spatial binding");
    public static final ShaderDataType<Instance> INSTANCE_DATA = ShaderDataType.create("fog spatial instance");
    private final GpuDevice gpu;
    private final ResourceFactory resources;
    private FogField uploaded;
    private VmaMappedBuffer fieldBuffer;
    private ResourceOwner fieldOwner;
    private SpatialMedium<Binding, Instance> current;

    public FogVolume(GpuDevice gpu, ResourceFactory resources) {
        this.gpu = gpu;
        this.resources = resources;
    }

    public static ShaderDefinition definition() {
        return ShaderSource.classpath(FogVolume.class, "/caustica/shaders/common")
                .definition("caustica_fog_medium", "FogVolumeModel");
    }

    /** The returned binding is borrowed until the next capture; frame acceptance retains its own claims. */
    public SpatialMedium<Binding, Instance> capture(VolumeId<Binding, Instance> implementation,
            FogFrame frame, OptionValues options, double originX, double originY, double originZ,
            double metersPerSceneUnit) {
        if (frame == null || !options.get(FogPass.ENABLED) || options.get(FogPass.DENSITY) == 0.0f) {
            releaseCurrent();
            return null;
        }
        if (uploaded != frame.field()) upload(frame.field());
        VmaMappedBuffer parameters = VmaMappedBuffer.create(gpu, FogVolumeData.BYTE_SIZE,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Fog captured parameters");
        ResourceOwner dependency = fieldOwner.retain();
        ResourceOwner owner;
        try {
            data(frame, options.get(FogPass.DENSITY), originX, originY, originZ, metersPerSceneUnit,
                    fieldBuffer.deviceAddressAt(0).value()).write(parameters.mapped().order(ByteOrder.nativeOrder()));
            parameters.flush(0, parameters.byteSize());
            owner = resources.create(() -> new ResourceLifetime(parameters::close, dependency::close).close());
        } catch (RuntimeException | Error failure) {
            new ResourceLifetime(parameters::close, dependency::close).close();
            throw failure;
        }
        SpatialMedium<Binding, Instance> replacement;
        try (owner) {
            replacement = new SpatialMedium<>(implementation,
                    BINDING_DATA.data(parameters.deviceAddressAt(0).value(), owner), INSTANCE_DATA.data(0),
                    originX, originY, originZ);
        }
        releaseCurrent();
        current = replacement;
        return current;
    }

    static FogVolumeData data(FogFrame frame, float density, double originX, double originY,
            double originZ, double metersPerSceneUnit, long fieldAddress) {
        FogField field = frame.field();
        return new FogVolumeData(fieldAddress, 0,
                new Float4((float) (field.originX() - originX), (float) (field.originY() - originY),
                        (float) (field.originZ() - originZ), field.spacing()),
                new Float4(field.sizeX(), field.sizeY(), field.sizeZ(), 0),
                new Float4(wrapped(originX), wrapped(originY), wrapped(originZ), frame.windPhase()),
                new Float4(0.003f * density * frame.timeDensity() * (float) metersPerSceneUnit,
                        (float) (frame.layerHeight() - originY), frame.heightFalloff(),
                        256.0f / (float) metersPerSceneUnit),
                vector(frame.lightDirection()), vector(frame.lightRadiance()), vector(frame.ambientRadiance()));
    }

    private void upload(FogField field) {
        float[] voxels = field.voxels();
        for (int i = 3; i < voxels.length; i += 4) voxels[i] = (float) (voxels[i] - field.originY());
        VmaMappedBuffer replacement = VmaMappedBuffer.create(gpu, (long) voxels.length * Float.BYTES,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Fog spatial field");
        ResourceOwner owner;
        try {
            replacement.mapped().order(ByteOrder.nativeOrder()).asFloatBuffer().put(voxels);
            replacement.flush(0, replacement.byteSize());
            owner = resources.create(replacement::close);
        } catch (RuntimeException | Error failure) {
            replacement.close();
            throw failure;
        }
        if (fieldOwner != null) fieldOwner.close();
        fieldOwner = owner;
        fieldBuffer = replacement;
        uploaded = field;
    }

    private void releaseCurrent() {
        if (current != null) {
            new ResourceLifetime(current.bindingData()::close, current.instanceData()::close).close();
            current = null;
        }
    }

    private static float wrapped(double coordinate) {
        return (float) (coordinate - Math.floor(coordinate / 4096.0) * 4096.0);
    }

    private static Float4 vector(float[] vector) {
        return new Float4(vector[0], vector[1], vector[2], 0);
    }

    @Override public void close() {
        new ResourceLifetime(this::releaseCurrent, () -> { if (fieldOwner != null) fieldOwner.close(); }).close();
    }
}
