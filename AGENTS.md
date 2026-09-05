# Repository guidance

- Comments and Javadocs must describe the current implementation and its active invariants only.
- Do not preserve implementation history, migration notes, completed phases, or superseded behavior in source comments. Git history owns that context.
- Prefer direct explanations of why the current code is required, especially API contracts, synchronization rules, units, and non-obvious constraints.
- Until release, modify only the English locale (`en_us.json`); leave every other locale unchanged.
- This project is under active development. Do not write defensive code. Prefer simple, direct code and suggest refactor if needed.
- No string matching tests against the code files or changes.
- See `docs\VISION.md`
