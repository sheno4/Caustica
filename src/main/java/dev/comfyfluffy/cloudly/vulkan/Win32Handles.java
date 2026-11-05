/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.vulkan;

import org.lwjgl.system.JNI;
import org.lwjgl.system.windows.Kernel32;

/** Opaque Win32 imports retain handle ownership in the exporting process. */
final class Win32Handles {
    private static final long CLOSE_HANDLE = Kernel32.getLibrary().getFunctionAddress("CloseHandle");
    private Win32Handles() {}
    static void close(long handle) {
        if (handle != 0 && JNI.invokePI(handle, CLOSE_HANDLE) == 0) {
            throw new IllegalStateException("CloseHandle failed for exported Vulkan resource");
        }
    }
}
