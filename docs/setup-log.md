# Setup log (WP1)

Machine: Windows 11 Home build 26200, i5-1135G7 (4C/8T), 20 GB RAM (19.7 GB visible); C: Kingston SATA SSD; D: Toshiba 1 TB HDD. WSL 2.6.3, distro `Ubuntu` 24.04.4 LTS, user `mit`, systemd on. Preflight: `bda-preflight-report.md`.

## Memory profile
- Lean-Windows checklist applied on 17 Oct. Windows measured at **8.03 GB** in use (Claude app 2.5 GB included; NVIDIA helper processes and WhatsApp excluded).
- That falls in the 6.8–8.5 GB band → **Profile B**.
- `C:\Users\HP\.wslconfig`: `memory=10GB`, `swap=8GB`, `processors=6`. Verified inside WSL: 6 cores, 9.7 GiB RAM, 8 GiB swap.
- YARN `memory-mb` = 6144; MapReduce map 1024 / reduce 2048 / AM 1024 MB; Spark driver 1536m, 2 executors × 2g × 2 cores; HBase heap 1g.

## Storage
- **C: limit 10 GB (user decision).** Only software, the venv, the Maven cache, NameNode metadata and logs live on C:, inside WSL's `ext4.vhdx`.
- Everything big lives on the data disk `D:\BDA\hdfs.vhdx` (300 GB dynamic, ext4, label `bdadata`, mounted at `/data`):
  - HDFS blocks: `/data/hdfs/dn`
  - YARN local dirs + container logs: `/data/yarn/*`
  - Spark scratch: `/data/spark-tmp`
  - raw downloads and kept tarballs: `/data/raw`
- At the end of setup (20 Oct): WSL root used 8.3 GB, `/data` used 12 GB.
  - `~/bda/opt` 3.9 GB, venv 391 MB, `~/.m2` 180 MB.
  - `~/smoke` 409 MB is left over from the preflight and gets deleted at project end.

## Versions
```
openjdk version "21.0.12.1" 2026-08-18 LTS (Temurin-21.0.12.1+1)
Hadoop 3.4.3
Hive 4.2.1 (metastore schema 4.2.0, PostgreSQL 16.15)
Tez 0.10.5
HBase 2.6.6 (-hadoop3 build)
Spark 4.1.2
Apache Pig 0.18.0
Apache Maven 3.9.16
Python 3.12.3 (venv: numpy 2.5.4, pandas 3.0.6, scipy 1.18.1, matplotlib 3.11.2, pytrec-eval-terrier 0.5.11)
lucene-analysis-common 9.12.3 (Porter stemmer only)
```
Install dirs are versionless symlinks under `~/bda/opt` (`jdk`, `hadoop`, `hive`, `tez`, `hbase`, `spark`, `pig`, `maven`); the environment is in `~/bda/env.sh`.

Checksums verified:
- Hadoop sha512, Hive sha256, Tez sha512, Maven sha512.
- Pig: **md5 only**, because that's all the Pig project publishes.
- PostgreSQL JDBC 42.7.8: sha1 from Maven Central.

## Verification (all on JDK 21, no `--add-opens` flags needed)
| Check | Result |
|---|---|
| Detach check (5 Hadoop daemons alive 30 s later, from a new `wsl.exe` call) | ✅ no SIGHUP in any log |
| MapReduce Pi on YARN | ✅ 3.14, 24 s |
| JobHistory (19888), HDFS (9870), YARN (8088) from the Windows browser | ✅ all 200 |
| Hive 4.2.1 on Tez: create / insert / select / drop | ✅ DAG finished in 6.03 s (30 s incl. embedded HS2 start) |
| HBase pseudo-distributed on HDFS: create / put / scan / disable / drop | ✅ master ready; no module errors or SIGHUP |
| Pig 0.18 `-x mapreduce` group/sum | ✅ `a 4`, `b 2`; 27 s. The JDK 8 fallback isn't needed. |
| SparkPi on YARN | ✅ 3.14169, 26 s |
| PySpark on YARN with the venv Python (`spark/hello.py`) | ✅ driver and executors both on `~/bda/venv/bin/python`; 39 s |
| Our shaded jar on YARN (`bda.tools.TokenWordCount`) | ✅ 20 s |
| Data disk survives `wsl --terminate` and `wsl --shutdown` | ✅ `bda-mount.sh` re-attaches it via the Windows task; no admin needed |

## Issues & fixes (the report's "ecosystem drift" material)
1. **NVIDIA helper flood (Windows).** After Virtual Machine Platform was enabled, the `NvContainerLocalSystem` service crash-looped every 10–20 s. Each restart left behind a stuck SYSTEM `rundll32 …\NvStreamSrv\rxdiag.dll` (about 270 per hour, roughly 1.2 GB after 3 h).
   - Fix: the user disabled the service. GPU driver unaffected.
2. **Data-disk mount.**
   - WSL's own `--name bdadata` mount under `/mnt/wsl` is lost whenever the distro idles out and restarts.
   - A `[boot] command` calling `schtasks.exe` fails with "Exec format error": interop isn't up yet at boot.
   - Fix: `/etc/fstab` systemd automount by **label** (`LABEL=bdadata /data … x-systemd.automount,nofail`), plus `bin/bda-mount.sh`. If the disk is detached, the script runs the elevated Windows task `bda-mount-vhdx` itself.
3. **Linux device letters swap when the WSL VM restarts.** The data disk was `sde`, then `sdd`. The guarded `mkfs` script refused to run when `sde` turned out to be the 1 TB root disk. Disks are now found by size, label or UUID, never by letter.
4. **Daemons die with SIGHUP when the `wsl.exe` call returns** (found in the preflight). Every daemon and long job starts under `setsid` (`bda-start.sh`, `bda-run.sh`).
5. **HBase pseudo-distributed needs SSH.** `bin/zookeepers.sh` always uses ssh and has no localhost special case. Fix: start ZooKeeper, Master and RegionServer one by one with `hbase-daemon.sh` under `setsid`. There's no SSH server.
6. **Hive 4.2.1 beeline on JDK 21.** JLine's FFM terminal is compiled as a Java *preview* feature (`UnsupportedClassVersionError … 65.65535`), and beeline insists on a real terminal ("Unable to create a terminal").
   - `-Dorg.jline.terminal.dumb=true` does not help.
   - Fix: `-Dorg.jline.terminal.ffm=false`, plus `bin/bda-beeline.sh`, which runs beeline inside a pseudo-terminal (`script -q`).
7. **Hive bundles only `tez-api`.** The first Tez query failed with `ClassNotFoundException: org.apache.tez.mapreduce.hadoop.InputSplitInfo`. Fix: add `$TEZ_HOME/*` (Tez 0.10.5's own jars, not `lib/*`) to `HADOOP_CLASSPATH` in `hive-env.sh`.
8. **Checksum formats differ by project:** Hive `.sha256`, Pig only `.md5`.
9. `pytrec_eval` has no Python 3.12 wheel, so `pytrec-eval-terrier` is used instead (same `import pytrec_eval`).
10. Windows port 8080 is taken by the `PEMHTTPD-x64` service (from the Windows PostgreSQL install), so the QueryServer will use **port 8090**.
11. One GitHub CDN IP (`185.199.109.133`) doesn't answer from WSL on the home network (preflight). JDK downloads use `curl --resolve` with the other three IPs.
12. Tuning:
    - `yarn.scheduler.capacity.maximum-am-resource-percent` = 0.5, so a Hive/Tez AM and a Spark AM can run together.
    - Spark's jars are pre-uploaded to `hdfs:///apps/spark/jars` (`spark.yarn.jars`), so submits don't re-upload 420 MB each time.
