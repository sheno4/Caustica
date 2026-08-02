# Repository guidance

- Keep planning documents and `todos.md` local-only. Do not add them to Git.
- Comments and Javadocs must describe the current implementation and its active invariants only.
- Do not preserve implementation history, migration notes, completed phases, or superseded behavior in source comments. Git history owns that context.
- Do not reference internal plan steps, phase labels, milestone IDs, or numbered design-document sections from source comments.
- Prefer direct explanations of why the current code is required, especially API contracts, synchronization rules, units, and non-obvious constraints.
- Until release, modify only the English locale (`en_us.json`); leave every other locale unchanged.
- This project is under active development. Do not write defensive code. Prefer simple, direct code and suggest refactor if needed.
