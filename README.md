# XueXue v0.1 alpha

XueXue is a local-first Compose Multiplatform desktop workbench for reading,
notes, search, backlinks, and model-assisted work.

## Run

Requires JDK 26. The first PDF build also needs a C/C++ toolchain, GNU Make,
and `curl`. Gradle downloads the pinned MuPDF source archive, verifies its
checksum, and builds the project-owned JNI bridge with bundled MuPDF
dependencies. No MuPDF Java wrapper or system FreeType/OpenSSL/zlib development
package is required.

```bash
./gradlew run
```

The first screen lets you create or open a directory. Files remain the source
of truth. Layout state is stored in `.xuexue/workbench.properties`; Agent
sessions and PDF annotations are also stored under `.xuexue` and should be
backed up when they matter.

## Alpha scope

- Desktop workbench with activity rail, resizable tool panel, document tabs,
  contextual toolbar, agent panel, and status bar.
- Real directory tree, preview/pinned tabs, create Markdown file, refresh, and
  atomic autosave.
- Markdown source/edit/read modes, formatting commands, wiki links, tags,
  headings, backlinks, outline, and workspace text search.
- MuPDF-backed viewport-tiled PDF viewing with continuous/single/double-page
  modes, smooth anchored zoom, rotation, night reading, native text selection,
  links, search, annotations, persisted reading position, and progressive
  HTTP Range loading.
- Image viewing.
- Streaming OpenAI-compatible agent conversation with current-document context,
  cancellation, and workspace-local session persistence.
- Restores the last workspace, open tabs, active document, tool, and panel sizes.

See [docs/V0.1_ALPHA.md](docs/V0.1_ALPHA.md) for acceptance criteria and known
architecture gaps.

## Quality checks

```bash
./gradlew ktlintCheck
./gradlew detektMainDesktop
./gradlew detektTestDesktop
./gradlew desktopTest
./gradlew koverXmlReport
```

CI runs the same checks. Dependency versions are locked in `gradle.lockfile`.
See [CONTRIBUTING.md](CONTRIBUTING.md) for commit and review guidelines.
