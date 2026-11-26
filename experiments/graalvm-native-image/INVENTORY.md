# Dynamic-feature inventory

Everything the AOT build must handle that the JVM build gets for free.
Generated from the current source tree; re-run the greps after refactors.

## kotlinx.serialization (`@Serializable` types)

```
- `PiAgentRuntime`
- `DocumentModel`
- `WorkbenchModels`
- `AgentClient`
- `HostComputeDetector`
- `WorkspaceService`
- `DocumentAnalysisSettings`
- `PdfReaderBackend`
- `DocumentAnalysisEngine`
- `PdfOutlineRepository`
- `PdfLongDocumentBenchmarkTest`
- `PdfOcrExternalEvaluationTest`
```

## JNI surface (`MuPdfNative` externals + bridge)

```
- `openDocument`
- `needsPassword`
- `authenticatePassword`
- `createProgressiveStream`
- `provideProgressiveRange`
- `progressiveRequestedRange`
- `progressiveBytesLoaded`
- `openDocumentWithProgressiveStream`
- `dropProgressiveStream`
- `closeDocument`
- `countPages`
- `pageBounds`
- `loadDisplayList`
- `loadOverlayDisplayList`
- `loadProgressiveDisplayList`
- `loadProgressiveOverlayDisplayList`
- `displayListBounds`
- `dropDisplayList`
- `createCookie`
- `abortCookie`
- `dropCookie`
- `renderRgba`
- `renderRgbaNative`
- `freeRasterBuffer`
- `private nativeZeroCopyProbe`
- `extractText`
- `extractStructuredText`
- `loadOutline`
- `exportOutline`
- `createTextPage`
- `dropTextPage`
- `copySelection`
- `highlightSelection`
- `loadLinks`
- `search`
- `emptyStore`
- `private initialize`
```

## Reflection

Two `User32`-related reflective lookups in `WindowCorners.kt` (window
corner shaping); everything else is plain Kotlin. Native Image can call
into WinAPI via JNI/JNA-free direct invocation if converted to JNI, or the
two lookups can be registered in `reflect-config.json`.

## Resources loaded at runtime

- `xuexue/native/xuexue_mupdf.dll` (extracted to a runtime dir)
- Compose resources dir (`compose.application.resources.dir`)
- `.xuexue/` workspace state (user data, not bundled)
