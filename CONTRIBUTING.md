## Development notes (VS Code / Cursor)

### Java extension stability (JDT LS)

Please **do not commit machine-specific absolute paths** to Java in `.vscode/settings.json` (examples: `/Users/...`, `/home/...`, `C:\...`).

These paths can prevent the Java language server from starting, and VS Code/Cursor may appear stuck at **“Activating extension…”**.

- **Shared (repo) settings**: keep them portable (no absolute `java.*.home` values).
- **Personal settings**: put them in `.vscode/settings.local.json` (ignored by git).

CI will fail PRs that reintroduce non-portable Java paths into `.vscode/settings.json`.

