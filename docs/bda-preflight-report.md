# BDA Preflight Report — 2026-10-17

**Verdict:** GO WITH CHANGES
**One-line reason:** The hardware, disks and network are good enough. WSL2 was blocked because *Virtual Machine Platform* was off; that's now fixed (enabled and rebooted on 17 Oct). Windows uses 11.6 GB of RAM in a normal session, so it has to be trimmed during big-data work. The MS MARCO URL has also changed.
**Phase 2:** Hadoop MR-on-YARN, Spark (local and YARN) and HBase standalone all **pass on JDK 21 with no extra flags**. Start daemons with `setsid` or systemd, and give HBase's ZooKeeper a longer session timeout.

## Machine
- Windows: Microsoft Windows 11 Home Single Language, build 26200, Home edition, x64 (64-bit)
- CPU: 11th Gen Intel Core i5-1135G7 @ 2.40 GHz, 4 cores / 8 threads
- RAM: 20 GB installed (16 GB + 4 GB DDR4-3200), 19.7 GB visible to Windows. Windows uses ~11.6 GB in a normal session (user confirmed).
- SSD: C: (Kingston SA400M8 480 GB, SATA) — 78 of 446 GB free
- HDD: D: (Toshiba MQ04ABF100 1 TB) — 826 of 932 GB free
- Network: home Wi-Fi ("Chintan Shah 3", Realtek RTL8822CE 802.11ac), proxy: no

## Results
| # | Check | Result | Status | Action needed |
|---|---|---|---|---|
| 1.1 | OS | Win 11, build 26200 (≥ 22000), x64 | ✅ | — |
| 1.1 | Edition | Home Single Language | ⚠️ | No Hyper-V cmdlets. Use `diskpart` to create and compact the VHDX. |
| 1.2 | CPU | i5-1135G7, 4C/8T | ✅ | processors = 6 in `.wslconfig` |
| 1.2 | RAM installed | 20 GB physical, but 19.7 GB visible (the script's "InstalledGB"), so it falls in the 16–19 band | ⚠️ | Plan for WSL ≤ 12 GB, not 16 GB |
| 1.2 | Windows' normal usage | 11.6 GB in use (Claude 2.7, Chrome 1.9, svchost 1.6, ChatGPT 1.2, Memory Compression 1.0 GB). Memory is already under pressure: 15.8 of 21.5 GB committed. | ⚠️ | Formula as-is: 19.7 − (11.6 + 1) = **7 GB**, which is below the 12 GB minimum. Run a lean Windows during work (see Recommended settings). |
| 1.3 | Virtualization | HypervisorPresent = True. It's running because of VBS / Memory Integrity (HVCI), which needs VT-x to be on in firmware. `VirtualizationFirmwareEnabled = False` is the expected reading when a hypervisor is running. | ✅ | None. Don't touch VBS/HVCI. |
| 1.4 | SSD free | C: 78 GB free (≥ 50) | ✅ | — |
| 1.4 | HDD free | D: 826 GB free (≥ 100) | ✅ | D:\ is not compressed or EFS-encrypted |
| 1.5 | WSL version | Store WSL 2.6.3.0, kernel 6.6.87.2-1 | ✅ | — |
| 1.5 | `wsl --mount --vhd` | Supported | ✅ | — |
| 1.5 | Distros | `Ubuntu` (v2, stopped, release unknown) and `docker-desktop` (v2). Both BasePaths are on C: (the SSD). No `.wslconfig`. | ✅ | — |
| 1.5 | **WSL2 can actually start** | At first: **No.** *VirtualMachinePlatform* was Disabled, and starting Ubuntu failed with `HCS_E_SERVICE_NOT_AVAILABLE`. **Fixed on 17 Oct:** the user ran `dism /enable-feature /featurename:VirtualMachinePlatform` as admin and rebooted at 15:30. The feature is now Enabled and `wsl --status` no longer reports WSL2 as unsupported. (The first "done" report was premature: no reboot had happened and the feature was still Disabled.) | ✅ fixed | — |
| 1.6 | Docker Desktop | 4.83.0 installed, not running | ⚠️ | Keep it quit while working. It shares WSL's memory pool. |
| 1.6 | Other software | VirtualBox 7.2.14 (VBoxSDS stopped). Windows-side Java 8u381 and JDK 17.0.8, Python 3.11.6 and 3.13.3, **MySQL Server 8.0.40 and PostgreSQL 18 running as auto-start services** | ℹ️ | Irrelevant to the stack, which runs inside WSL. Consider stopping MySQL80/PostgreSQL while working (about 0.2 GB). |
| 1.7 | Network | All 15 hosts reachable on 443/80. WinHTTP proxy: direct. IE proxy: off, no PAC. | ✅ | — |
| 1.8 | MS MARCO | `msmarco.blob.core.windows.net` returns **HTTP 409 `PublicAccessNotPermitted`**. The Microsoft-hosted endpoint `msmarco.z22.web.core.windows.net/msmarcoranking/collection.tar.gz` works: **HTTP 206**, 1,035,009,698 bytes, Last-Modified 2023-11-09. | ⚠️ | Use the `z22.web` URL (substitution recorded here). The `ir_datasets` fallback is still available. |
| 1.8 | Download speed | Phase 1 (Windows): Wikimedia 2.13 MB/s, MS MARCO mirror 1.95 MB/s, Apache dlcdn 1.33 MB/s. **Phase 2, inside WSL, with qBittorrent off: 2.8–3.6 MB/s** (Apache dlcdn 3.0–3.3, archive.apache.org 2.8, GitHub/Adoptium 3.6). | ✅ (was ⚠️) | Keep qBittorrent off during project downloads. It was using ~2.6 MB/s down and 1.4 MB/s up during Phase 1. |
| 1.9 | Inside WSL (rerun after the fix) | `Ubuntu` = **Ubuntu 24.04.4 LTS**. 8 cores, 9 GB RAM (the default half, since there's no `.wslconfig`). Root ext4 is 1007 GB virtual with 2.8 GB used. pid1 = systemd (`/etc/wsl.conf` has `systemd=true`, default user `mit`). No JVM, no `java`, no `mvn`. `python3`, `git` and `curl` are present. **2,000 small files: ext4 0.37 s vs `/mnt/d` 5.96 s (~16× slower).** | ✅ | The existing distro already matches the plan, so Phase 2 uses `-d Ubuntu` and skips installing `Ubuntu-24.04`. HDFS goes in the VHDX, not `/mnt/d`. |
| 1.10 | Power | Balanced plan, battery 100%, on AC power | ✅ | Stay plugged in for Phase 2 and long jobs |

## Recommended settings
How the WSL memory number was worked out (rule: installed − (Windows usage + 1), rounded down):

| Windows usage during work | `.wslconfig` memory | YARN `memory-mb` | Notes |
|---|---|---|---|
| 11.6 GB (as now) | 7 GB | 3072 | Too small for Hive/Tez + Spark. Avoid this. |
| **~8.5 GB (lean: quit ChatGPT 1.2, Copilot 0.3, WhatsApp 0.3 + WebView2 ~0.5, qBittorrent, and stop MySQL80/PostgreSQL)** | **10 GB** | **6144** | **Recommended.** Plenty for simplewiki + MS MARCO scope. |
| ≤ 6.7 GB (very lean: also close most Chrome tabs and other apps) | 12 GB | 8192 | The plan's 16–19 GB tier. Only if this is realistic during heavy jobs. |

- .wslconfig → memory=10GB, swap=8GB, processors=6
- YARN memory → 6144 MB (raise to 8192 only if you move to memory=12GB)
- **Update on 17 Oct:** the user plans to switch off most background apps during real runs, so the 12 GB / 8192 MB row is likely reachable. Before writing `.wslconfig`, measure Windows' RAM use with only the project setup open, and choose 12 GB if it's ≤ 6.7 GB, otherwise 10 GB.
- VHDX → `D:\BDA\hdfs.vhdx`, dynamic, max 300 GB (min of 300 and 826 − 100 = 726). Create it with `diskpart`, because this is the Home edition.

## Downloads
| Source | HTTP | MB/s |
|---|---|---|
| dumps.wikimedia.org (simplewiki, User-Agent with contact email, user-approved) | 206 | 2.13 |
| msmarco.blob.core.windows.net (original URL) | **409** PublicAccessNotPermitted | — |
| msmarco.z22.web.core.windows.net (alternate; 10 MB sample) | 206 | 1.95 |
| dlcdn.apache.org (Spark 4.1.2 tgz) | 206 | 1.33 |

- MS MARCO (~1 GB; 1,035,009,698 bytes) estimate: **~9 minutes** at 1.95 MB/s
- Simple English Wikipedia (~300 MB): ~2–3 minutes
- Phase 2 downloads (~1.5 GB, mostly Apache): ~20 minutes at 1.33 MB/s
- Full enwiki (26.5 GB), for reference: **~3.5 hours** at 2.13 MB/s

## Phase 2 smoke test (if run)
Run on 17 Oct 2026, 10:10–10:33 UTC (~25 min), in the existing `Ubuntu` distro (24.04.4), so 2.1 was not needed. JDK = **Temurin 21.0.12.1+1**. Everything is in `~/smoke` (4.6 GB including tarballs; **kept for the real setup**). `~/smoke/run.sh <state|hadoop|spark|hbase-retry|teardown>` re-runs any step.

| Component | JDK | Result | Extra flags needed | Notes |
|---|---|---|---|---|
| Hadoop 3.4.3 MR on YARN | 21 | ✅ PASS | **None** | `jps`: NameNode, DataNode, ResourceManager, NodeManager. Pi = `3.14000000000000000000`, and wordcount wrote output. No `InaccessibleObjectException`/`IllegalAccessError`, and no WARN/ERROR lines. sha512 of the tarball: OK. |
| Spark 4.1.2 local / YARN | 21 | ✅ PASS / ✅ PASS | **None** | Local: `Pi is roughly 3.14226`. On YARN (client mode): `Pi is roughly 3.14233`. No exceptions. |
| HBase 2.6.6 standalone | 21 | ✅ PASS (on the 2nd clean start) | **None** | create → put → `scan` shows `r1 column=cf:a … value=v1` → disable → drop. No module errors. The checklist's script fails as written: the shell runs ~10 s after start, before the master is ready (`PleaseHoldException: Master is initializing`). The master needed **~19 s**. |
| VHDX attach (2.8) | — | ✅ PASS | — | Run by the user in an elevated PowerShell. A new `sde 1G disk` appeared in `lsblk` after `wsl --mount --vhd --bare`. Unmount and delete both worked. This proves the D: storage plan works. |
| JDK 17 fallback | — | Not needed | — | JDK 21 works for all three, so the plan stays on **Hive 4.2.1**. |

**Phase 2 findings that change the real setup:**
1. **Daemons die when the `wsl.exe` call that started them exits.** All four Hadoop daemons logged `RECEIVED SIGNAL 1: SIGHUP` at the exact second `hadoop-test.sh` returned. Hadoop's JVM installs its own HUP handler, which defeats the `nohup` inside `--daemon start`. HBase reloads its config on HUP and the master died. **Fix (verified):** start daemons with `setsid` (e.g. `setsid hdfs --daemon start namenode`), or as systemd services (systemd is already on). With `setsid`, HDFS and YARN survived the caller exiting.
2. **HBase aborted under load:** its in-process ZooKeeper session expired (`have not heard from server in 6669ms` → `ABORTING master … received expired from ZooKeeper`). This happened at 10:31, while Spark local + Spark-on-YARN + HDFS/YARN all ran on 4 cores, with Windows already holding ~11.6 GB. It isn't a JDK issue, because HBase had already passed. For the real setup: raise `zookeeper.session.timeout` to 60000–90000 ms (and `hbase.zookeeper.property.tickTime` to 6000 so ZK allows it), don't run heavy Spark jobs while HBase is loading, and apply the Windows RAM trimming above.
3. **NodeManager didn't stop within 5 s** at teardown and was `kill -9`'d by the stop script. That's harmless for the smoke test, but give it longer in the real setup's stop script.
4. **HBase 2.6.6 is no longer on `dlcdn`.** 2.6.7 superseded it, so 2.6.6 came from `archive.apache.org/dist/` (substitution recorded). For the real setup, consider **HBase 2.6.7**, the current patch release on the CDN mirror. Hadoop 3.4.3 and Spark 4.1.2 are still on dlcdn.
5. **One GitHub CDN IP (`185.199.109.133`) doesn't answer from WSL on this network**, so the JDK download hung in `SYN-SENT`. The other three (`.108/.110/.111.133`) work. The workaround used was `curl --connect-timeout 15 --resolve release-assets.githubusercontent.com:443:185.199.108.133,185.199.110.133,185.199.111.133`. WSL's NAT network has no IPv6 (`curl -6` fails instantly), while Windows has it.

## Skipped (needs admin — user to run)
This session was **not elevated**, even though "Admin Mode" was requested. The user ran all three admin steps below in an elevated PowerShell. **Nothing admin-related is outstanding.**

- ✅ *Done on 17 Oct:* confirm the feature state. Result: `VirtualMachinePlatform` = **Enabled**. `Microsoft-Windows-Subsystem-Linux` = Disabled, which is fine because that component is only needed for WSL1.
  ```powershell
  Get-WindowsOptionalFeature -Online |
    Where-Object FeatureName -in 'Microsoft-Windows-Subsystem-Linux','VirtualMachinePlatform' |
    Select-Object FeatureName, State
  ```
- ✅ *Done on 17 Oct:* **2.8 VHDX attach test**: **PASS**. `diskpart` created the 1 GB expandable VHDX on D:. `wsl --mount --vhd --bare` succeeded, and the second `lsblk` showed a new **`sde 1G disk`** (before it: sda 388.6M, sdb 186M, sdc 3G [SWAP], sdd 1T /). `wsl --unmount` and `Remove-Item` both succeeded. Nothing was formatted.
  ```powershell
  $vhd = "D:\bda-mount-test.vhdx"
  "create vdisk file=`"$vhd`" maximum=1024 type=expandable" | Out-File -Encoding ascii "$env:TEMP\mkvhd.txt"
  diskpart /s "$env:TEMP\mkvhd.txt"
  wsl -d Ubuntu -- lsblk
  wsl --mount --vhd $vhd --bare
  wsl -d Ubuntu -- lsblk
  wsl --unmount $vhd
  Remove-Item $vhd
  ```
  Reminder: `wsl --mount` doesn't survive a reboot or `wsl --shutdown`, so the real setup needs a startup task to re-attach the disk.
- ✅ *Done on 17 Oct:* enable Virtual Machine Platform, then reboot.
  ```powershell
  dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
  ```
  (`wsl.exe --install --no-distribution` does the same job.) After rebooting, check that `wsl --status` no longer says WSL2 isn't supported, then rerun check 1.9 against `Ubuntu`.

## Surprises / anything the plan didn't anticipate
- **Virtual Machine Platform is disabled even though WSL2 distros already exist** (Ubuntu, docker-desktop). Someone turned it off after WSL was set up, probably to speed up VirtualBox. Turning it back on will make VirtualBox run on the Hyper-V backend, which is slower. That's acceptable, because the plan doesn't use VirtualBox.
- **The MS MARCO blob storage account now refuses public access** (HTTP 409, not the 403/404 the checklist expected). The `msmarco.z22.web.core.windows.net` endpoint serves the same `collection.tar.gz`.
- **qBittorrent was transferring data** in the background (~2.6 MB/s down, 1.4 MB/s up). It was paused for the speed test. Keep it paused during project downloads, or Apache downloads will slow down even more.
- **Windows-side MySQL 8.0 and PostgreSQL 18 run as auto-start services.** They don't conflict inside WSL. But Windows tools connecting to `localhost:3306` or `localhost:5432` will reach these instead of a metastore DB inside WSL.
- The hypervisor is present because of **VBS/Memory Integrity**, not Hyper-V. Leave it alone (security setting).
- The existing `Ubuntu` distro's release is unknown, because it can't start. Phase 2's `wsl --install -d Ubuntu-24.04` creates a separate `Ubuntu-24.04` distro, so the old one can stay as it is.
- Disk bus shows as `RAID` (Intel RST/VMD controller). The media type was still reported correctly (SSD/HDD), so this has no impact.
- RAM is a mismatched 16 GB + 4 GB pair, and 0.3 GB is reserved for the iGPU, so usable RAM sits just under the 20 GB "GO" line.
- **Running Linux commands from Windows PowerShell 5.1:** `wsl.exe` re-wraps its arguments in `bash -c "…"`, so any `"`, `|`, `\` or `$?` inside an inline `bash -lc '…'` gets re-parsed. One inline `sed 's/\r$//'` turned into `s/r$//` and silently cut the trailing "r" off `resourcemanager`/`nodemanager` in the scripts. **For the real setup, always put Linux steps in `.sh` files and call `wsl -d Ubuntu -- bash -l ~/path/script.sh`.** Rule 5 of the checklist should cover PowerShell too, not just Git Bash.
- `~/smoke` is 4.6 GB, not ~3 GB, because the downloaded tarballs (~1.7 GB) are kept. They can be deleted once the real setup has extracted what it needs.
