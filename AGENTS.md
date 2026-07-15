# XueXue Repository Guide

## Kotlin and Compose style

- Use the repository `.editorconfig` and ktlint's official Kotlin style.
- Use four spaces, LF line endings, trailing commas in multiline calls, and
  keep chained calls and function parameters on separate lines when they wrap.
- Keep lines below detekt's default 120-character limit. The ktlint line-length
  rule is disabled, but detekt still enforces this limit.
- Compose functions use PascalCase by convention (`WorkbenchApp`, `ToolPanel`,
  `DocumentWorkspace`). UI files use a file-level naming suppression for this
  Compose convention; do not disable function-name checking for non-UI code.
- Prefer existing Compose Material 3 components and Material icons. Icon-only
  actions need a meaningful content description and a tooltip when the action
  is not obvious.

## UI structure

- Keep the workbench split into a continuous outer chrome area and one central
  content surface. The outer area contains the header, activity/tool icons,
  docked auxiliary panels, and status/navigation chrome.
- Do not add hard divider lines between outer-chrome regions. Use spacing,
  tonal contrast, selection fills, or elevation instead.
- Preserve the central content surface as the visually primary area. Keep
  docked tool panels and Agent panels in outer-chrome colors; floating and
  document-embedded panels may use a surface color.

## Verification

Run the same checks used by CI before handing off a change:

```bash
./gradlew ktlintFormat
./gradlew ktlintCheck detektMainDesktop detektTestDesktop desktopTest koverXmlReport
```

The project compiles with JVM toolchain 26. Use Java 26 explicitly when running
the desktop application if the shell default is older:

```bash
JAVA_HOME=/usr/lib/jvm/java-26-openjdk \
PATH=/usr/lib/jvm/java-26-openjdk/bin:$PATH ./gradlew run
```

Do not regenerate or expand a static-analysis baseline to hide new findings.
Fix the code or add a narrow, documented suppression only when a rule is not
applicable (for example, a generic naming rule applied to a Compose function).
