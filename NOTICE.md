# AC8 Cloudly Runtime

Derived from Caustica's Cloudly implementation, commit
`39baae0a751e2ca57d8c504427afd3493fef998b`, by ComfyFluffy and contributors.
The standalone host, native program bindings, resource lifetime management and
interop adaptations remain under LGPL-3.0-or-later. See LICENSE.md, COPYING and
COPYING.LESSER. This library is linked separately by RTX Remix for Minecraft;
it does not change that application's Apache-2.0 license.

The corresponding library source and build scripts are included under
META-INF/cloudly-source in the library JAR. The library can be rebuilt and
replaced independently of the application.

Game-derived shaders, textures and parameter templates are separate private
inputs. They are not covered by this library's LGPL grant and are not included
in its public source archive.

External-memory synchronization follows the shared-image and semaphore model
documented by KhronosGroup/Vulkan-Samples, samples/extensions/open_gl_interop
(Apache-2.0); no source from that sample is vendored here.
