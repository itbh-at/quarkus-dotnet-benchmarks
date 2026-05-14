# Published benchmark results

Curated subset of reports from this benchmark suite, committed so they can be
shared via GitHub links. The full report set lives in the gitignored
`reports/` directory on the developer's machine — only files surfaced here
are intended for external links.

## Viewing a report

GitHub doesn't render HTML inline (clicking the file shows source code).
Open a published report via [raw.githack][rgh] — a free CDN that serves
repo HTML with the correct `Content-Type`:

```
https://raw.githack.com/itbh-at/quarkus-dotnet-benchmarks/main/benchmark-results/<filename>.html
```

For a stable link pinned to a specific commit (cached forever, immutable),
use `rawcdn.githack.com` with a commit SHA:

```
https://rawcdn.githack.com/itbh-at/quarkus-dotnet-benchmarks/<sha>/benchmark-results/<filename>.html
```

[rgh]: https://raw.githack.com/

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

| Date       | File                                                                                                                                                                                                      | Configuration summary                                                                                                                         |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-05-14 | [2026-05-14-compare-run-11-vs-14-semeru-scc-tuning.html](https://raw.githack.com/itbh-at/quarkus-dotnet-benchmarks/main/benchmark-results/2026-05-14-compare-run-11-vs-14-semeru-scc-tuning.html)     | Comparison: run 11 (basic SCC) vs run 14 (tuned SCC: -Xscmx128M -Xtune:virtualized), Semeru OpenJ9 25, Quarkus 3.35.2 + tree-shaking |
| 2026-05-14 | [2026-05-14-openj9-semeru-quarkus-3.35-tree-shaking-tuned-scc.html](https://raw.githack.com/itbh-at/quarkus-dotnet-benchmarks/main/benchmark-results/2026-05-14-openj9-semeru-quarkus-3.35-tree-shaking-tuned-scc.html) | Quarkus 3.35.2 + tree-shaking, IBM Semeru OpenJ9 25, SCC with -Xscmx128M -Xtune:virtualized, optthruput GC, -Xms128m -Xmx256m, 7 iter |
| 2026-05-14 | [2026-05-14-compare-tree-shaking-on-vs-off.html](https://raw.githack.com/itbh-at/quarkus-dotnet-benchmarks/main/benchmark-results/2026-05-14-compare-tree-shaking-on-vs-off.html)                         | Comparison: run 13 (no tree-shaking) vs run 12 (tree-shaking), Temurin 25 + Leyden AOT, SerialGC, -Xms128m -Xmx256m — isolates tree-shaking contribution |
| 2026-05-13 | [2026-05-13-temurin-leyden-quarkus-3.35-no-tree-shaking.html](https://raw.githack.com/itbh-at/quarkus-dotnet-benchmarks/main/benchmark-results/2026-05-13-temurin-leyden-quarkus-3.35-no-tree-shaking.html) | Quarkus 3.35.2 NO tree-shaking (baseline for tree-shake delta), Temurin 25 + Leyden AOT, SerialGC, -Xms128m -Xmx256m, 7 iter |
| 2026-05-13 | [2026-05-13-temurin-leyden-quarkus-3.35-tree-shaking.html](https://raw.githack.com/itbh-at/quarkus-dotnet-benchmarks/main/benchmark-results/2026-05-13-temurin-leyden-quarkus-3.35-tree-shaking.html)       | Quarkus 3.35.2 + tree-shaking, Temurin 25 + Leyden AOT, SerialGC, -Xms128m -Xmx256m, 7 iter — quarkus-jvm, quarkus-leyden, quarkus-virtual, quarkus-virtual-leyden |
| 2026-05-12 | [2026-05-12-openj9-semeru-quarkus-3.35-tree-shaking.html](https://raw.githack.com/itbh-at/quarkus-dotnet-benchmarks/main/benchmark-results/2026-05-12-openj9-semeru-quarkus-3.35-tree-shaking.html)       | Quarkus 3.35.2 + tree-shaking, IBM Semeru OpenJ9 25 + SCC, -Xms128m -Xmx256m, optthruput GC, 7 iter — quarkus-jvm, quarkus-semeru, quarkus-virtual, quarkus-virtual-semeru |
| 2026-05-02 | [2026-05-02-memory-constrained.html](https://raw.githack.com/itbh-at/quarkus-dotnet-benchmarks/main/benchmark-results/2026-05-02-memory-constrained.html)                                                 | min heap 128 MiB, max heap 256 MiB, serial GC, eager to return memory to the OS                                                              |
| 2026-05-03 | [2026-05-03-fixed-heap-size.html](https://raw.githack.com/itbh-at/quarkus-dotnet-benchmarks/main/benchmark-results/2026-05-03-fixed-heap-size.html)                                                       | fixed heap at 512 MiB, parallel GC, eager to return memory to the OS                                                                         |
