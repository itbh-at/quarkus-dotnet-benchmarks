# Published benchmark results

Curated subset of reports from this benchmark suite, committed so they can be
shared via GitHub links. The full report set lives in the gitignored
`reports/` directory on the developer's machine — only files surfaced here
are intended for external links.

## Publishing a report

Reports are copied here manually. Suggested filename convention so things
sort chronologically and stay self-describing:

```
YYYY-MM-DD-<short-slug>.html
```

Examples:

```
2026-05-03-run-10-quarkus-container-preset.html
2026-05-03-compare-run-9-vs-10.html
```

After copying:

1. Add a row to the index below.
2. `git add benchmark-results/<filename>.html benchmark-results/README.md`
3. Commit and push.

## Index

<!-- Add one row per published report. Newest at top. -->

| Date       | File                                                                     | Configuration summary                                                           |
| ---------- | ------------------------------------------------------------------------ | ------------------------------------------------------------------------------- |
| 2026-05-02 | [2026-05-02-memory-constrained.html](2026-05-02-memory-constrained.html) | min heap 128 MiB, max heap 256 MiB, serial GC, eager to return memory to the OS |
| 2026-05-03 | [2026-05-03-fixed-heap-size.html](2026-05-03-fixed-heap-size.html)       | fixed heap at 512 MiB, parallel GC,  eager to return memory to the OS           |
