# BDA Search Engine — Windows Laptop Preflight Check

**Who runs this:** Claude Code (or Claude with computer use) on the user's Windows laptop.
**Goal:** Find out whether this laptop can run the planned single-node big-data stack, and what must change, *before* the real install.
**Output:** save a report as `bda-preflight-report.md` next to this file (template at the end), then give the user a 5-line summary in chat.

How to start it: open Claude Code in the folder containing this file and say
*"Follow bda-preflight-check.md — Phase 1 only."*
The same file works on any teammate's laptop.

**Time budget:** the user has about 24 hours *in total*, over 4 days, for the whole project. Keep Phase 1 under 15 minutes and Phase 2 under 60. Time-box every error: record it, apply the documented fallback, and move on. Phase 2's installs are kept and reused for the real setup.

---

## Rules for the agent

1. **Phase 1 is read-only.** Do not install, uninstall, enable Windows features, edit settings, or reboot. Temporary test files are fine if you delete them.
2. **Phase 2 installs software.** Do not start it until the user has read the Phase 1 report and explicitly says go.
3. **Never touch security settings** (Defender, firewall, UAC, BIOS/UEFI). If one blocks progress, report it and stop.
4. **Never type passwords.** If something asks for one (Linux account creation, `sudo`), hand that step to the user. Phase 2 is designed to need no `sudo`.
5. **Shell:** the Windows commands are PowerShell. If your shell is Bash (Git Bash), save each block to a `.ps1` file in `%TEMP%` and run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File <file>`. Don't inline PowerShell in Bash double quotes — Bash will eat the `$` variables.
6. **Linux scripts written from Windows must use LF line endings.** If you see `$'\r': command not found`, run `sed -i 's/\r$//' <script>`.
7. If a command fails, record the exact error and continue. One failed check shouldn't stop the run.
8. Commands marked **[admin]** need an elevated PowerShell. If you're not elevated, skip them and list them in the report for the user to run.

---

## What we're checking against (the plan)

| Item | Plan |
|---|---|
| VM layer | WSL2 + Ubuntu 24.04 LTS. No VirtualBox/VMware. |
| Memory | `.wslconfig`: memory 16 GB, swap 8 GB → 4 GB left for Windows. YARN gets 12 GB. |
| SSD | WSL distro, binaries, NameNode metadata, YARN/Spark scratch. Budget ~40 GB; want ≥ 50 GB free. |
| HDD | Separate VHDX on the HDD, attached with `wsl --mount --vhd`, ext4, mounted at `/data`, holding HDFS data and downloads. Budget ~40 GB at the current scope. |
| Java | JDK 21 for everything (Hive 4.2 requires it). JDK 17 is the fallback. |
| Stack | Hadoop 3.4.3, Hive 4.2.1 + Tez 0.10.5, HBase 2.6.6 (`-hadoop3` build), Spark 4.1.2, Pig 0.18.0 |
| Data | Simple English Wikipedia (~300 MB) and MS MARCO passages (~1 GB download). Full enwiki (~26.5 GB) is out of scope because of the time budget. |

Versions are current as of 16 Oct 2026. If an Apache link 404s, check that project's download page or `archive.apache.org/dist/`, and record the substitution.

---

## Phase 1 — Read-only checks (≈10 minutes)

### 1.1 Windows version, edition, architecture

```powershell
Get-CimInstance Win32_OperatingSystem | Select-Object Caption, Version, BuildNumber, OSArchitecture
```

- **Pass:** 64-bit x64, build ≥ 19041.
- Note whether the build is ≥ 22000 (Windows 11), which matters for `wsl --mount` in 1.5.
- **ARM64** (e.g. Snapdragon laptops): flag it. Everything needs aarch64 builds, so expect friction.
- **"Home" edition:** the Hyper-V cmdlets (`New-VHD`, `Optimize-VHD`) don't exist. Create and compact the VHDX with `diskpart` instead. That's a workaround, not a blocker.

### 1.2 CPU and RAM, including how much Windows itself uses

```powershell
Get-CimInstance Win32_Processor | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors
$os = Get-CimInstance Win32_OperatingSystem
$cs = Get-CimInstance Win32_ComputerSystem
[pscustomobject]@{
  InstalledGB = [math]::Round($cs.TotalPhysicalMemory/1GB,1)
  UsableGB    = [math]::Round($os.TotalVisibleMemorySize/1MB,1)
  InUseNowGB  = [math]::Round(($os.TotalVisibleMemorySize - $os.FreePhysicalMemory)/1MB,1)
}
Get-Process | Sort-Object WorkingSet64 -Descending |
  Select-Object -First 12 Name, @{n='MB';e={[math]::Round($_.WorkingSet64/1MB)}}
```

- If `VmmemWSL` or `vmmem` is in the list, that's WSL's own memory. Subtract it to get Windows' usage.
- Ask the user whether what's open right now is their normal working setup (browser tabs, IDE). The number that matters is Windows' usage *during a normal work session*.

### 1.3 Virtualization enabled?

```powershell
Get-CimInstance Win32_ComputerSystem | Select-Object HypervisorPresent
Get-CimInstance Win32_Processor | Select-Object VirtualizationFirmwareEnabled, SecondLevelAddressTranslationExtensions
```

- **Pass:** `HypervisorPresent = True` (a hypervisor is already running), **or** `VirtualizationFirmwareEnabled = True`.
- When `HypervisorPresent` is True, `VirtualizationFirmwareEnabled` can read False. That's normal.
- **Fail:** both are False. Virtualization (Intel VT-x / AMD SVM) is off in BIOS/UEFI, and the user has to turn it on. Do not attempt it.

### 1.4 Disks: which drive is the SSD, which is the HDD, free space

```powershell
$pd = Get-PhysicalDisk
Get-Partition | Where-Object DriveLetter | ForEach-Object {
  $p = $_
  $d = $pd | Where-Object DeviceId -eq "$($p.DiskNumber)"
  $v = Get-Volume -DriveLetter $p.DriveLetter
  [pscustomobject]@{
    Drive = $p.DriveLetter; Disk = $d.FriendlyName; Media = $d.MediaType; Bus = $d.BusType
    FS = $v.FileSystem; FreeGB = [math]::Round($v.SizeRemaining/1GB); SizeGB = [math]::Round($v.Size/1GB)
  }
} | Format-Table -AutoSize
```

- If `MediaType` says `Unspecified`, use `BusType` and the model name: NVMe means SSD. If it's still unclear, ask the user.
- The VHDX must not sit in a compressed or EFS-encrypted folder.

### 1.5 WSL state and `wsl --mount --vhd` support

```powershell
$env:WSL_UTF8 = "1"
wsl --version
wsl --status
wsl --list --verbose
"Supports --mount --vhd: " + ((wsl --help 2>&1 | Out-String) -match '--vhd')
Get-ChildItem HKCU:\Software\Microsoft\Windows\CurrentVersion\Lxss -ErrorAction SilentlyContinue |
  ForEach-Object { Get-ItemProperty $_.PSPath } | Select-Object DistributionName, Version, BasePath
"wslconfig exists: " + (Test-Path "$env:USERPROFILE\.wslconfig")
Get-Content "$env:USERPROFILE\.wslconfig" -ErrorAction SilentlyContinue
```

**[admin]** feature state:

```powershell
Get-WindowsOptionalFeature -Online |
  Where-Object FeatureName -in 'Microsoft-Windows-Subsystem-Linux','VirtualMachinePlatform' |
  Select-Object FeatureName, State
```

How to read the output:

- **`wsl --version`:** if it prints version numbers, this is the Store version of WSL (good). If it errors or prints help text, it's the old inbox WSL or WSL isn't installed.
- **`--list --verbose`:** every distro should show VERSION 2.
- **`--vhd`:** must be supported. That needs Windows 11 (build 22000+) or the Store version of WSL. If it's missing, `wsl --update` fixes it in Phase 2.
- **`BasePath`:** shows which drive existing distros live on. It should be the SSD.
- **`.wslconfig`:** record its current contents, if it exists.

### 1.6 Software that competes for RAM or the hypervisor

```powershell
$keys = 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*'
Get-ItemProperty $keys -ErrorAction SilentlyContinue |
  Where-Object { $_.DisplayName -match 'VirtualBox|VMware|Docker Desktop|Java|JDK|Temurin|MySQL|PostgreSQL|Python' } |
  Select-Object DisplayName, DisplayVersion | Sort-Object DisplayName
```

- **Docker Desktop** shares WSL's memory pool. Flag it: quit Docker while working, or budget for it.
- **VirtualBox/VMware** can coexist with WSL2 on recent versions, but they're unnecessary here. Just note them.
- **Windows-side Java/Python/MySQL** are irrelevant, because everything runs inside WSL. Just note them.

### 1.7 Network reachability (important on campus Wi-Fi)

```powershell
$targets = @(
  @('dumps.wikimedia.org',443), @('dlcdn.apache.org',443), @('downloads.apache.org',443), @('archive.apache.org',443),
  @('msmarco.blob.core.windows.net',443), @('microsoft.github.io',443),
  @('pypi.org',443), @('files.pythonhosted.org',443), @('repo1.maven.org',443),
  @('github.com',443), @('objects.githubusercontent.com',443), @('release-assets.githubusercontent.com',443),
  @('api.adoptium.net',443), @('archive.ubuntu.com',80), @('academictorrents.com',443)
)
foreach ($t in $targets) {
  $ok = Test-NetConnection -ComputerName $t[0] -Port $t[1] -InformationLevel Quiet -WarningAction SilentlyContinue
  '{0,-38} {1,-4} {2}' -f $t[0], $t[1], $ok
}
netsh winhttp show proxy
Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' |
  Select-Object ProxyEnable, ProxyServer, AutoConfigURL
```

- Record which network the laptop is on (campus, home, or hotspot).
- **If a proxy is set**, WSL's `apt`, `pip`, `curl` and Maven will all need proxy configuration. Flag it.
- Torrents can be blocked even when port 443 is open. The full Wikipedia dump can come from a mirror instead, so this isn't a blocker.

### 1.8 Download speed, and is MS MARCO still downloadable?

This pulls only the first 50 MB of each file and discards it. Replace `<EMAIL>` with the user's email (ask if unknown), because Wikimedia's User-Agent policy wants contact info.

```powershell
$ua = 'BDA-preflight/1.0 (student project; contact: <EMAIL>)'
curl.exe -s -L -A $ua -r 0-52428799 -o NUL -w "wikimedia  HTTP %{http_code}  %{speed_download} bytes/s\n" "https://dumps.wikimedia.org/simplewiki/latest/simplewiki-latest-pages-articles.xml.bz2"
curl.exe -s -L -r 0-52428799 -o NUL -w "msmarco    HTTP %{http_code}  %{speed_download} bytes/s\n" "https://msmarco.blob.core.windows.net/msmarcoranking/collection.tar.gz"
curl.exe -s -L -r 0-52428799 -o NUL -w "apache     HTTP %{http_code}  %{speed_download} bytes/s\n" "https://dlcdn.apache.org/spark/spark-4.1.2/spark-4.1.2-bin-hadoop3.tgz"
```

- **HTTP 206 or 200** means OK. **403/404 on MS MARCO** means access has changed: flag it. The fallback is `ir_datasets`, or emailing ms-marco@microsoft.com.
- Convert to MB/s by dividing by 1,048,576.
- Estimate the MS MARCO download time (about 1 GB). For reference only, also estimate full enwiki: `26.5 × 1024 ÷ MB/s ÷ 3600` hours.

### 1.9 Inside WSL (only if a WSL2 distro already exists)

Use the distro name from 1.5 in place of `<DISTRO>`, and the HDD's drive letter (lowercase) in place of `<hdd>`.

```powershell
wsl -d <DISTRO> -- bash -lc 'head -2 /etc/os-release; echo cores=$(nproc); free -g | head -2; df -h / | tail -1; echo pid1=$(ps -p 1 -o comm=); cat /etc/wsl.conf 2>/dev/null; ls /usr/lib/jvm 2>/dev/null; command -v java python3 mvn git curl'
```

Small-file write test. This shows why HDFS must not live on `/mnt/<drive>`:

```powershell
wsl -d <DISTRO> -- bash -lc 'mkdir -p ~/bda_io_t && time (for i in $(seq 2000); do echo x > ~/bda_io_t/f$i; done); rm -rf ~/bda_io_t'
wsl -d <DISTRO> -- bash -lc 'mkdir -p /mnt/<hdd>/bda_io_t && time (for i in $(seq 2000); do echo x > /mnt/<hdd>/bda_io_t/f$i; done); rm -rf /mnt/<hdd>/bda_io_t'
```

- Compare the two `real` times. The `/mnt` path is expected to be several times slower, which confirms the plan to use a VHDX.
- `free -g` shows the memory WSL currently gets. Without a `.wslconfig`, that's half of RAM.
- `pid1=systemd` means systemd is on. It's needed later to run MySQL/PostgreSQL as a service. It isn't needed for Phase 2.
- **If no distro exists**, skip this section and note "WSL not set up yet".

### 1.10 Power

```powershell
powercfg /getactivescheme
Get-CimInstance Win32_Battery | Select-Object EstimatedChargeRemaining, BatteryStatus
```

Just note these. Long jobs and Phase 2 should run plugged in.

---

## Phase 1 verdict rules

| Check | ✅ GO | ⚠️ GO WITH CHANGES | ❌ BLOCKED |
|---|---|---|---|
| OS | x64, build ≥ 19041 | ARM64 (expect friction) | 32-bit, or build < 19041 |
| Virtualization | Enabled or hypervisor present | — | Off in firmware (user must enable) |
| RAM installed | ≥ 20 GB | 16–19 GB: WSL 12 GB, YARN 8 GB. Under 16 GB: simplewiki-scale only, WSL 8 GB, YARN 6 GB | — |
| Windows' normal usage | ≤ 4 GB | Higher: WSL memory = installed − (Windows usage + 1), rounded down, minimum 12 GB if possible | — |
| SSD free | ≥ 50 GB | 40–49 GB: cap scratch at 20 GB | < 40 GB: free up space first |
| HDD free | ≥ 100 GB | 40–99 GB | < 40 GB: no room for HDFS data off the SSD |
| `wsl --mount --vhd` | Supported | Not supported: `wsl --update` in Phase 2 | — |
| Edition | Pro / Education / Enterprise | Home: use `diskpart` instead of Hyper-V cmdlets | — |
| Network | All reachable, no proxy | Some blocked or proxy set: use another network or configure the proxy | Both Wikimedia and Apache unreachable |
| MS MARCO | HTTP 200/206 | 403/404: use the fallback | — |
| Download speed | ≥ 2 MB/s | 0.5–2 MB/s: start downloads first thing. < 0.5 MB/s: use a different network | — |
| Docker Desktop | Not installed | Installed: quit it while working | — |

**Overall verdict:** BLOCKED if any check is ❌. Otherwise GO WITH CHANGES if any is ⚠️. Otherwise GO.

**Recommended numbers to put in the report:**

- `.wslconfig` memory: from the RAM rows above (target 16 GB). Swap: 8 GB. Processors: logical processors − 2.
- YARN `yarn.nodemanager.resource.memory-mb`: WSL memory − 4 GB, in MB.
- VHDX: on the HDD, dynamically expanding. Maximum size = the smaller of 300 GB and (HDD free − 100 GB).

**Stop here.** Write the report, summarise it in chat, and ask whether to run Phase 2.

---

## Phase 2 — Smoke test (ONLY after the user says go; ≈30–60 min)

**Purpose:** prove that the risky combinations actually run on JDK 21 on this machine:

- Hadoop 3.4.3 with MapReduce on YARN
- Spark 4.1.2, both locally and on YARN
- HBase 2.6.6

Hive is left out because Hive 4.2 *requires* JDK 21, so it's the lowest-risk piece; it gets tested during the real setup.

**Design:**

- Everything lives in `~/smoke` inside WSL.
- No `sudo` and no system packages. The JDK comes as a tarball.
- Hadoop daemons start with `--daemon`, so no SSH server is needed.
- Total download is about 1.5 GB.

### 2.1 WSL and Ubuntu (only if missing or outdated) [admin]

```powershell
wsl --update
wsl --install -d Ubuntu-24.04
```

- If Windows needs a reboot, **ask the user first**.
- On first launch, Ubuntu asks for a Linux username and password. **The user does that step**, not you.
- Don't create a `.wslconfig` here. The default of half the RAM is enough for the smoke test.

All Linux steps below are bash scripts. Save them under `~/smoke/` inside the distro with LF line endings, and run each one with:
`wsl -d Ubuntu-24.04 -- bash -lc 'bash ~/smoke/<script>.sh'`

### 2.2 `env.sh` (sourced by every other script)

```bash
export SMOKE="$HOME/smoke"
export JAVA_HOME="$SMOKE/jdk-21"
export HADOOP_HOME="$SMOKE/hadoop-3.4.3"
export HADOOP_CONF_DIR="$HADOOP_HOME/etc/hadoop"
export HBASE_HOME="$SMOKE/hbase-2.6.6-hadoop3"
export SPARK_HOME="$SMOKE/spark-4.1.2-bin-hadoop3"
export PATH="$JAVA_HOME/bin:$HADOOP_HOME/bin:$PATH"
```

### 2.3 `download.sh`

```bash
set -euo pipefail
mkdir -p ~/smoke && source ~/smoke/env.sh && cd "$SMOKE"

# JDK 21 (Temurin) — no sudo needed
curl -fL --retry 3 -o jdk21.tar.gz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
mkdir -p jdk-21 && tar -xzf jdk21.tar.gz -C jdk-21 --strip-components=1
java -version

# Hadoop 3.4.3
curl -fLO --retry 3 "https://dlcdn.apache.org/hadoop/common/hadoop-3.4.3/hadoop-3.4.3.tar.gz"
curl -fLO --retry 3 "https://downloads.apache.org/hadoop/common/hadoop-3.4.3/hadoop-3.4.3.tar.gz.sha512"
sha512sum -c hadoop-3.4.3.tar.gz.sha512
tar -xzf hadoop-3.4.3.tar.gz

# HBase 2.6.6 (hadoop3 build)
curl -fLO --retry 3 "https://dlcdn.apache.org/hbase/2.6.6/hbase-2.6.6-hadoop3-bin.tar.gz"
tar -xzf hbase-2.6.6-hadoop3-bin.tar.gz

# Spark 4.1.2
curl -fLO --retry 3 "https://dlcdn.apache.org/spark/spark-4.1.2/spark-4.1.2-bin-hadoop3.tgz"
tar -xzf spark-4.1.2-bin-hadoop3.tgz
ls
```

- Fallback mirror for any 404: replace `https://dlcdn.apache.org/` with `https://archive.apache.org/dist/`.
- If `sha512sum -c` can't parse a checksum file, compare the hashes by eye and note it.
- If an extracted folder name differs from `env.sh` (check the `ls` output), fix `env.sh`.

### 2.4 `config-hadoop.sh` (single-node, smoke-test sizing)

```bash
set -euo pipefail
source ~/smoke/env.sh
echo "export JAVA_HOME=$JAVA_HOME" >> "$HADOOP_CONF_DIR/hadoop-env.sh"

cat > "$HADOOP_CONF_DIR/core-site.xml" <<EOF
<configuration>
  <property><name>fs.defaultFS</name><value>hdfs://localhost:9000</value></property>
  <property><name>hadoop.tmp.dir</name><value>$SMOKE/tmp</value></property>
</configuration>
EOF

cat > "$HADOOP_CONF_DIR/hdfs-site.xml" <<EOF
<configuration>
  <property><name>dfs.replication</name><value>1</value></property>
</configuration>
EOF

cat > "$HADOOP_CONF_DIR/mapred-site.xml" <<EOF
<configuration>
  <property><name>mapreduce.framework.name</name><value>yarn</value></property>
  <property><name>mapreduce.application.classpath</name><value>$HADOOP_HOME/share/hadoop/mapreduce/*:$HADOOP_HOME/share/hadoop/mapreduce/lib/*</value></property>
  <property><name>yarn.app.mapreduce.am.env</name><value>HADOOP_MAPRED_HOME=$HADOOP_HOME</value></property>
  <property><name>mapreduce.map.env</name><value>HADOOP_MAPRED_HOME=$HADOOP_HOME</value></property>
  <property><name>mapreduce.reduce.env</name><value>HADOOP_MAPRED_HOME=$HADOOP_HOME</value></property>
</configuration>
EOF

cat > "$HADOOP_CONF_DIR/yarn-site.xml" <<EOF
<configuration>
  <property><name>yarn.nodemanager.aux-services</name><value>mapreduce_shuffle</value></property>
  <property><name>yarn.nodemanager.env-whitelist</name><value>JAVA_HOME,HADOOP_COMMON_HOME,HADOOP_HDFS_HOME,HADOOP_CONF_DIR,CLASSPATH_PREPEND_DISTCACHE,HADOOP_YARN_HOME,HADOOP_HOME,PATH,LANG,TZ,HADOOP_MAPRED_HOME</value></property>
  <property><name>yarn.nodemanager.resource.memory-mb</name><value>6144</value></property>
  <property><name>yarn.nodemanager.vmem-check-enabled</name><value>false</value></property>
</configuration>
EOF
echo "config written"
```

### 2.5 `hadoop-test.sh`

```bash
set -uo pipefail
source ~/smoke/env.sh
EX="$HADOOP_HOME/share/hadoop/mapreduce/hadoop-mapreduce-examples-3.4.3.jar"

hdfs namenode -format -force -nonInteractive
hdfs --daemon start namenode
hdfs --daemon start datanode
hdfs dfsadmin -safemode wait
yarn --daemon start resourcemanager
yarn --daemon start nodemanager
for i in $(seq 30); do yarn node -list 2>/dev/null | grep -q RUNNING && break; sleep 2; done
jps

hdfs dfs -mkdir -p /user/$USER/input
hdfs dfs -put -f "$HADOOP_CONF_DIR"/*.xml /user/$USER/input
hadoop jar "$EX" pi 4 1000
hadoop jar "$EX" wordcount /user/$USER/input /user/$USER/wc-out
hdfs dfs -cat /user/$USER/wc-out/part-r-00000 | head -5

echo "--- JDK module errors in logs (empty = good) ---"
grep -rlE 'InaccessibleObjectException|IllegalAccessError|does not "opens"' "$HADOOP_HOME/logs" 2>/dev/null | head
```

**Pass criteria:**

- `jps` lists NameNode, DataNode, ResourceManager and NodeManager.
- The Pi job prints `Estimated value of Pi is 3.14…`.
- Wordcount prints lines of output.
- The grep at the end prints nothing.

**If it fails with JDK module errors**, retry once with JVM flags:

1. Append the following to `$HADOOP_CONF_DIR/hadoop-env.sh`:
   ```bash
   export HADOOP_OPTS="$HADOOP_OPTS --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED"
   ```
2. If the error is inside a task container (under `logs/userlogs`), also add those flags to `mapreduce.map.java.opts`, `mapreduce.reduce.java.opts` and `yarn.app.mapreduce.am.command-opts` in `mapred-site.xml`.
3. Stop the daemons, start them again, and rerun the jobs.
4. Record exactly which flags were needed.

**Time-box:** if JDK 21 isn't working after 20 minutes, stop debugging and switch to JDK 17. On JDK 17 the real setup uses Hive 4.1.0 instead of 4.2.1.

**If it still fails**, test JDK 17:

1. Download the same Adoptium URL with `17` instead of `21` into `~/smoke/jdk-17`.
2. Point `JAVA_HOME` at it in both `env.sh` and `hadoop-env.sh`.
3. Rerun, and report the results for both JDKs.

### 2.6 `spark-test.sh` (run while YARN from 2.5 is still up)

```bash
set -uo pipefail
source ~/smoke/env.sh
cd "$SPARK_HOME"
echo "--- local ---"
bin/run-example SparkPi 100 2>&1 | grep -E 'Pi is roughly|Exception' | head
echo "--- on YARN ---"
EXJAR=$(ls examples/jars/spark-examples_*.jar | head -1)
bin/spark-submit --master yarn --deploy-mode client --class org.apache.spark.examples.SparkPi "$EXJAR" 50 2>&1 | grep -E 'Pi is roughly|Exception' | head
```

**Pass:** `Pi is roughly 3.14…` appears in both modes.

### 2.7 `hbase-test.sh` (standalone)

```bash
set -uo pipefail
source ~/smoke/env.sh
cd "$HBASE_HOME"
echo "export JAVA_HOME=$JAVA_HOME" >> conf/hbase-env.sh
echo 'export HBASE_DISABLE_HADOOP_CLASSPATH_LOOKUP="true"' >> conf/hbase-env.sh
cat > conf/hbase-site.xml <<EOF
<configuration>
  <property><name>hbase.cluster.distributed</name><value>false</value></property>
  <property><name>hbase.rootdir</name><value>file://$SMOKE/hbase-data</value></property>
  <property><name>hbase.zookeeper.property.dataDir</name><value>$SMOKE/zk-data</value></property>
  <property><name>hbase.unsafe.stream.capability.enforce</name><value>false</value></property>
</configuration>
EOF
bin/start-hbase.sh
printf "create 'smoke','cf'\nput 'smoke','r1','cf:a','v1'\nscan 'smoke'\ndisable 'smoke'\ndrop 'smoke'\n" | bin/hbase shell -n
grep -lE 'InaccessibleObjectException|IllegalAccessError' logs/* 2>/dev/null | head
```

- **Pass:** the scan shows `r1  column=cf:a, … value=v1`, and the grep prints nothing.
- **If it fails**, check `logs/` and try the same `--add-opens` flags via `HBASE_OPTS` in `conf/hbase-env.sh`.

### 2.8 Optional: VHDX attach test [admin]

This proves the HDD storage plan works on this machine. It uses a 1 GB throwaway disk. Replace `X` with the HDD drive letter.

```powershell
$vhd = "X:\bda-mount-test.vhdx"
"create vdisk file=`"$vhd`" maximum=1024 type=expandable" | Out-File -Encoding ascii "$env:TEMP\mkvhd.txt"
diskpart /s "$env:TEMP\mkvhd.txt"
wsl -d Ubuntu-24.04 -- lsblk
wsl --mount --vhd $vhd --bare
wsl -d Ubuntu-24.04 -- lsblk
wsl --unmount $vhd
Remove-Item $vhd
```

- **Pass:** a new ~1 GB device appears in the second `lsblk` output.
- **Do not format anything** in this test.
- Note for the report: `wsl --mount` doesn't survive a reboot or `wsl --shutdown`. The real setup will need a startup task to re-attach the disk.

### 2.9 `teardown.sh`

```bash
source ~/smoke/env.sh
"$HBASE_HOME/bin/stop-hbase.sh"
yarn --daemon stop nodemanager; yarn --daemon stop resourcemanager
hdfs --daemon stop datanode;   hdfs --daemon stop namenode
jps
```

**Do not delete `~/smoke`** (about 3 GB). Because of the time budget, the real setup builds on these installs instead of downloading everything again.

---

## Report template — save as `bda-preflight-report.md`

```markdown
# BDA Preflight Report — <date>

**Verdict:** GO / GO WITH CHANGES / BLOCKED
**One-line reason:** <...>

## Machine
- Windows: <caption>, build <n>, <edition>, <arch>
- CPU: <name>, <cores> cores / <threads> threads
- RAM: <installed> GB installed; Windows uses ~<x> GB in a normal session
- SSD: <drive> — <free> of <size> GB free
- HDD: <drive> — <free> of <size> GB free
- Network: <campus/home/hotspot>, proxy: <yes/no>

## Results
| # | Check | Result | Status | Action needed |
|---|---|---|---|---|
| 1.1 | OS | | | |
| ... | | | | |

## Recommended settings
- .wslconfig → memory=<n>GB, swap=8GB, processors=<n>
- YARN memory → <n> MB
- VHDX → <drive>:\<path>\hdfs.vhdx, dynamic, max <n> GB

## Downloads
| Source | HTTP | MB/s |
|---|---|---|
- MS MARCO (~1 GB) estimate: <n> minutes
- Full enwiki (26.5 GB), for reference: <n> hours

## Phase 2 smoke test (if run)
| Component | JDK | Result | Extra flags needed | Notes |
|---|---|---|---|---|
| Hadoop MR on YARN | 21 | | | |
| Spark local / YARN | 21 | | | |
| HBase standalone | 21 | | | |
| VHDX attach | — | | | |

## Skipped (needs admin — user to run)
- ...

## Surprises / anything the plan didn't anticipate
- ...
```
