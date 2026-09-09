# Open rendering issues

- Intermittent RR device loss remains unresolved. The recorded GPU fault does not identify an offending shader or allocation; successful bounded reruns do not establish a fix.
- Restoring a maximized window to its default 854×480 size previously overlaid the rendered scene with game texture content, as reported by the user. The five-cycle maximize/restore regression is pending because computer-use captures show a white client area while debug screenshots show the rendered scene.
- The SR diagnostic overlay has dark, poorly legible bars in inspected captures. Its legibility remains unresolved after the indicator-axis and SR exposure corrections.

See [refactor validation](REFACTOR_VALIDATION.md) for reproduction conditions, evidence, and the remaining interactive and visual checks.
