# XueXue v0.1 alpha

XueXue is a local-first Compose Multiplatform desktop workbench for reading,
notes, search, backlinks, and model-assisted work.

## Run

Requires JDK 26.

```bash
./gradlew run
```

The first screen lets you create or open a directory. Files remain the source
of truth. Workspace UI state is stored in `.xuexue/workbench.properties`.

## Alpha scope

- Desktop workbench with activity rail, resizable tool panel, document tabs,
  contextual toolbar, agent panel, and status bar.
- Real directory tree, preview/pinned tabs, create Markdown file, refresh, and
  atomic autosave.
- Markdown source/edit/read modes, formatting commands, wiki links, tags,
  headings, backlinks, outline, and workspace text search.
- Embedded paged PDF viewing with zoom, page navigation, and persisted page.
- Image viewing.
- Streaming OpenAI-compatible agent conversation with current-document context,
  cancellation, and workspace-local session persistence.
- Restores the last workspace, open tabs, active document, tool, and panel sizes.

See [docs/V0.1_ALPHA.md](docs/V0.1_ALPHA.md) for acceptance criteria and known
architecture gaps.
