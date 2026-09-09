# Open rendering issues

- Intermittent RR device loss remains unresolved. The recorded GPU fault does not identify an offending shader or allocation; successful bounded reruns do not establish a fix.
- Direct remote-session resize captures reproduced duplicated scene/hand/hotbar regions under REBLUR/SR after restoring to 854×480. It appeared in the first run's first SR restore and persisted through its settled capture; subsequent inspected restores and a repeated raw-capture run did not reproduce it. The cause remains unresolved. Remote window controls now allow testing without computer use.
- The SR diagnostic overlay has dark, poorly legible bars. Same-frame raw captures show the bars in reconstructed-color but not the denoised trace-color input, before Minecraft UI composition. Legibility remains unresolved after the indicator-axis and SR exposure corrections.

See [refactor validation](REFACTOR_VALIDATION.md) for reproduction conditions, evidence, and the remaining interactive and visual checks.
