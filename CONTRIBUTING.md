# Contributing

Keep changes reviewable, testable, and reversible.

## Before opening a pull request

Run the same checks as CI:

```bash
./gradlew ktlintCheck detektMainDesktop detektTestDesktop desktopTest koverXmlReport
```

Do not update a static-analysis baseline to hide new findings. Refactor or add a
narrow suppression with a reason.

## Commit structure

- One behavior or refactor per commit.
- Add or update tests in the same commit as the behavior they cover.
- Keep mechanical formatting separate from behavior changes.
- Avoid commits that mix UI, persistence, platform I/O, and protocol changes.
- Use an imperative subject such as `Harden workspace path resolution`.

Large features should be split into a sequence that can build and pass tests at
every step. Do not rewrite shared branch history after review begins.
