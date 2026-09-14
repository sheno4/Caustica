package dev.comfyfluffy.caustica.minecraft.client;

import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.WinBase;

import java.util.LinkedHashMap;
import java.util.Map;

/** Calls the GPU Trace SDK entry points of an already injected Windows profiler. */
final class NsightDebug {
    private static final String[] STATES = {"inactive", "active", "tracing", "draining", "errored"};
    private static boolean initialized;

    private NsightDebug() { }

    static Map<String, Object> execute(boolean start, long frameId) {
        var result = new LinkedHashMap<String, Object>();
        try (var stack = MemoryStack.stackPush()) {
            var error = stack.callocInt(1);
            long module = Platform.get() == Platform.WINDOWS
                    ? WinBase.GetModuleHandle(error, "WarpVizTarget.dll") : 0;
            result.put("injected", module != 0);
            if (module == 0) {
                if (start) throw new IllegalStateException("Nsight GPU Trace is not injected");
                return result;
            }
            if (!initialized) {
                long initialize = WinBase.GetProcAddress(error, module, "NGFX_GPUTrace_InitializeTraceActivityVulkan");
                if (initialize == 0) throw new IllegalStateException("Injected profiler lacks the Vulkan GPU Trace SDK initialization entry point");
                var parameters = stack.calloc(4);
                parameters.putInt(0, 0x10004);
                check(JNI.callPI(org.lwjgl.system.MemoryUtil.memAddress(parameters), initialize));
                initialized = true;
            }
            // The client's normal queue submissions activate tracing resources after SDK initialization.
            long getStatus = WinBase.GetProcAddress(error, module, "NGFX_GPUTrace_GetStatus");
            if (getStatus == 0) throw new IllegalStateException("Injected profiler lacks the GPU Trace SDK status entry point");
            // SDK 0.9.2 versions combine the native structure size with version one in the high word.
            var status = stack.calloc(8);
            status.putInt(0, 0x10008);
            check(JNI.callPI(org.lwjgl.system.MemoryUtil.memAddress(status), getStatus));
            if (start) {
                if (status.getInt(4) != 1) throw new IllegalStateException("Nsight GPU Trace must be active before starting");
                long startTrace = WinBase.GetProcAddress(error, module, "NGFX_GPUTrace_StartTraceVulkan");
                if (startTrace == 0) throw new IllegalStateException("Injected profiler lacks the Vulkan GPU Trace SDK start entry point");
                var parameters = stack.calloc(4);
                parameters.putInt(0, 0x10004);
                check(JNI.callPI(org.lwjgl.system.MemoryUtil.memAddress(parameters), startTrace));
                result.put("triggerFrameId", frameId);
                result.put("triggerWallTimeMs", System.currentTimeMillis());
                check(JNI.callPI(org.lwjgl.system.MemoryUtil.memAddress(status), getStatus));
            }
            result.put("status", status.getInt(4));
            result.put("state", STATES[status.getInt(4)]);
            result.put("result", 0);
            return result;
        }
    }

    private static void check(int result) {
        if (result != 0) throw new IllegalStateException("Nsight GPU Trace SDK returned " + result);
    }
}
