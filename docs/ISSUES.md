# Open rendering issues

- Intermittent RR device loss remains unresolved. The recorded GPU fault does not identify an offending shader or allocation; successful bounded reruns do not establish a fix.
- Direct remote-session resize captures reproduce duplicated scene/hand/hotbar regions under REBLUR/SR after restoring to 854×480, particularly the first SR restore after RR cycles. Three additional diagnostic runs reproduced it, including with Khronos validation enabled and no reported validation error. Nearby independent UI, reconstructed-color and SDR display-color captures lack the duplicated hand/HUD content seen in the final PNG. Final Minecraft composition versus screenshot readback remains unresolved; these captures are not all from one frame. Remote window controls allow testing without computer use.
- The SR diagnostic overlay has dark, poorly legible bars. Same-frame raw captures show the bars in reconstructed-color but not the denoised trace-color input, before Minecraft UI composition. Legibility remains unresolved after the indicator-axis and SR exposure corrections.

See [refactor validation](REFACTOR_VALIDATION.md) for reproduction conditions, evidence, and the remaining interactive and visual checks.
