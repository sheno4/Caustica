package dev.comfyfluffy.caustica.api.pass;

import java.util.List;

public interface PassContext {
    int levelCount(ImagePyramid pyramid);

    ImageExtent extent(ImageRef image);

    void dispatch(ComputeProgram program, List<DispatchImage> images, byte[] pushConstants,
                  int groupCountX, int groupCountY, int groupCountZ);

    default void dispatch2D(ComputeProgram program, List<DispatchImage> images,
                            byte[] pushConstants, ImageRef destination) {
        ImageExtent extent = extent(destination);
        dispatch(program, images, pushConstants,
                (extent.width() + program.localSizeX() - 1) / program.localSizeX(),
                (extent.height() + program.localSizeY() - 1) / program.localSizeY(), 1);
    }
}
