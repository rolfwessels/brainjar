Status: done

## What to build

Add a `--export-md` CLI command that writes each Book's Pages as a human-readable markdown file.

For every Book in the store (optionally filtered by shelf), sort its Pages by `chunkIndex`, concatenate their content, and write the result to `<output-dir>/<shelf>/<date>-<title>.md`. The default output directory is `~/.recall/export/`, matching the `--export-kg` convention.

CLI signature: `./brainjar --export-md [--shelf <name>] [<output-path>]`

## Acceptance criteria

- [ ] `./brainjar --export-md` writes one `.md` file per Book under `~/.recall/export/<shelf>/`
- [ ] Filename format is `<YYYY-MM-DD>-<title>.md` using the Book's `lastModified` date
- [ ] File content is the Book's Pages concatenated in `chunkIndex` order, separated by a blank line
- [ ] `--shelf <name>` limits export to that shelf only
- [ ] An optional path argument overrides the default output directory
- [ ] Prints a summary line: how many files were written and where
- [ ] Unit tests cover the Page-grouping, ordering, and filename-generation logic
- [ ] Integration test exercises the CLI command end-to-end

## Blocked by

None — can start immediately

## Implementation notes

Added `BookExporter` in `brainjar.recall.export` — pure Spring component with static helpers for grouping Pages by Book, sorting by chunkIndex, and building `<date>-<title>` filenames. Wired into `RecallCommand` as `EXPORT_MD` command alongside the existing `EXPORT_KG` pattern. Path args come before `--shelf` (same convention as `--mine`). 8 unit tests in `BookExporterTest`, 2 parse tests added to `RecallCommandTest`.
