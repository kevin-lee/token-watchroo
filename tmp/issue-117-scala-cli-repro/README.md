# scala-cli reproducer for scala-native/scala-native#5393

`Repro.scala` is a standalone Scala Native 0.5.13 program with the settings of the token-watchroo proof run: `release-fast`, `commix`, `LTO none`, multithreading on. It copies the shape of the storm suites with plain threads and no libraries. The comment at the top of the file lists the threads.

## Build

```
./build.sh trap host            # bin/repro-trap-host
./build.sh conditional host     # bin/repro-conditional-host
./build.sh trap x86_64          # bin/repro-trap-x86_64, cross-built on arm64 for Rosetta 2
./build.sh conditional x86_64
```

- `SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS` (`1` or `0`) at link time picks the mode. scala-cli has no option for it.
- Each arm has its own workspace under `work/`, because a change of the variable alone does not relink.
- `build.sh` checks the binary with `nm` (`scalanative_gc_safepoint_poll_trampoline` and `scalanative_GC_yieldpoint_trap` exist only in trap mode) and fails if the mode is wrong. The first version of the check used `nm | grep -q` under `pipefail`. When `grep` exited early, `nm` got a broken pipe and the pipeline failed, so a trap binary looked conditional. The check now reads the `nm` output into a variable first.
- The Scala Native linker can fail once with `java.nio.file.ClosedFileSystemException` while it reads jars that scala-cli has just downloaded. `build.sh` then tries once more.
- The x86_64 build uses clang wrappers in `work/clang-x86_64` that add `-target x86_64-apple-macos14`.
- scala-cli downloads `scala-native-cli` 0.5.13 on the first build. In the Claude Code sandbox the JVM cannot download, so the build runs outside it.

## Run

```
./run.sh bin/repro-trap-x86_64 50 20 120    # 50 runs, 20 s each, 120 s timeout
```

Exit 0 is a pass, 132 is `SIGILL`, 3 is a corrupted array. A run that is alive at the timeout is a hang: the script samples it with `sample` and kills it. Logs and samples go to `logs/`.

