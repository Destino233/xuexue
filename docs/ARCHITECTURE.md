# Architecture

XueXue is a local-first Compose Multiplatform workbench. Workspace files are the
content authority; platform services perform guarded I/O; Compose renders state
and dispatches commands.

## Module boundaries

- `commonMain/core`: Markdown analysis and editing model, editor commands,
  completion, indexes, Agent policy, and platform-neutral UI decisions.
- `desktopMain/platform`: guarded workspace I/O, persistence, MuPDF document
  actors/display-list rendering through the project-owned JNI bridge, and the
  OpenAI-compatible transport. The bridge links MuPDF's native C core directly;
  the official legacy Java wrapper is not an application dependency.
- `desktopMain/ui`: Compose views grouped by workbench, document, tool-window,
  Agent, PDF, and dialog surfaces. Markdown parsing/edit transforms remain in
  `commonMain/core`, outside Compose rendering code.
- `WorkbenchState`: application facade and cross-feature coordinator. Mutable
  layout and Agent state are owned by `WorkbenchLayoutState` and
  `AgentRuntimeState`; Agent orchestration and background tasks live in
  dedicated controllers. File transactions, document persistence, PDF state,
  and remote PDF transfer are isolated in `WorkbenchFileController`,
  `WorkbenchDocumentController`, `WorkbenchPdfController`, and
  `WorkbenchRemotePdfController`.

## Concurrency

Compose state is updated on the UI scope. Blocking file operations, atomic
writes, workspace scans, index builds, PDF operations, and network work run on
`Dispatchers.IO`. Save operations capture immutable content/index snapshots,
then publish results back to Compose state. Application shutdown awaits dirty
document saves before exiting.

## PDF pipeline

MuPDF 1.27.2 is accessed through the project-owned JNI bridge. One document
actor is the only owner of each `fz_document`; it creates cached display lists,
structured text, links, and separate native annotation/widget display lists.
A fixed worker pool reserves one CPU for the UI and renders display-list tiles
with thread-local cloned MuPDF contexts. Every native raster task owns an
abortable `fz_cookie`.

The reader composes CPU-generated RGBA tiles through Compose/Skia. It schedules
only tiles intersecting the window plus one prefetch ring, retains the previous
texture during interactive zoom, and submits a new discrete LOD after a short
settle delay with hysteresis. Page transforms, night filtering, search hits,
text selections, links, application highlights, and native overlays are
composited without invalidating static page content.

PDF memory has a 224 MiB managed budget: 96 MiB for the MuPDF store, 64 MiB for
raster cache, 48 MiB for texture cache, and 16 MiB of pipeline headroom. Cache
eviction prioritizes visible, adjacent, then background content. Metrics cover
first viewport completion, visible-tile P95, cancellation and cache-hit rates,
frame drops, peak cache bytes, and texture preparation bandwidth.

Remote PDFs use a sparse 64 KiB block cache and custom progressive `fz_stream`.
HTTP Range requests are cancellable and coalesced; `FZ_ERROR_TRYLATER` plus the
MuPDF 1.27 `fz_cookie.incomplete` signal allow incomplete display lists to be
shown and refined. The first and final blocks are prioritized, progress is
visible in the reader, and manifests preserve partial data for retry/resume.
Servers without Range support fall back to a cancellable full download.

Native libraries and MuPDF licensing are packaged as Compose application
resources for Linux, macOS, and Windows on x64/arm64 targets. Runtime loading
uses `compose.application.resources.dir`; development and tests fall back to
`java.library.path`.

## Workspace boundary

The selected root is stored in canonical form. Every relative path is
normalized, checked against the canonical root, and rejected if an existing
path component is a symbolic link. Scans and indexes use `NOFOLLOW_LINKS`, skip
`.xuexue`, and never traverse linked directories.

## Persistence

See [V0.1_ALPHA.md](V0.1_ALPHA.md) for the storage contract. PDF page, zoom,
view mode, rotation, color mode, side panel, and continuous/paged scroll offsets
are rebuildable navigation state. Agent transcripts and PDF annotations are
durable application data.

## Pi Agent

The current Agent vertical slice is the migration starting point for a full
Kotlin port of Pi's headless runtime. Module boundaries, parity milestones,
plugin hosts, package compatibility, and acceptance gates are defined in
[PI_AGENT_PORTING_PLAN.md](PI_AGENT_PORTING_PLAN.md).
