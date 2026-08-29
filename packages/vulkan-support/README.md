# Caustica Vulkan support

`caustica-vulkan-support` is the optional implementation companion for extensions which record raw Vulkan
work through the public pass API. It provides VMA-backed 2D images with descriptor-heap ownership,
extension-owned samplers, compute shader objects, push-data dispatch, and compute synchronization helpers.

The package depends only on `caustica-api` and LWJGL types exposed by that API. It does not depend on the
renderer implementation and does not create queues, command buffers, descriptor sets, pipeline layouts, or
ray-tracing objects. Pass command buffers already have the renderer's single resource heap and sampler heap
bound when an extension records.

Published coordinates are
`dev.comfyfluffy.caustica:caustica-vulkan-support:<api-version>`.
