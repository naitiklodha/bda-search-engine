# Start-up commands — BDA search engine

Run these in order whenever you sit down to work on the project, or after a restart.

> **Status (20 Oct):** setup (WP1) is finished; every step below works.

**Which window to use:**
- **Normal PowerShell:** Windows Terminal → PowerShell.
- **Admin PowerShell:** Start → type "PowerShell" → right-click → **Run as administrator**. The title bar should start with "Administrator:".

---

## 1. Prepare Windows (every time)

- Plug the laptop in.
- **Quit, don't just close**, these apps: right-click each tray icon (bottom-right, by the clock) → Exit/Quit.
  - ChatGPT
  - Copilot
  - WhatsApp
  - qBittorrent
  - Docker Desktop
- Close the Chrome tabs you don't need.

**Admin PowerShell:** stop the Windows databases. Skip this if you've set them to Manual.

```powershell
Stop-Service MySQL80, postgresql-x64-18 -ErrorAction SilentlyContinue
```

## 2. Quick health check (normal PowerShell)

```powershell
@(Get-Process rundll32 -ErrorAction SilentlyContinue).Count
```

The result should be small (under 10). If it's in the hundreds, the NVIDIA loop is back, for example after a driver update. Fix it in **Admin PowerShell**:

```powershell
Set-Service NvContainerLocalSystem -StartupType Disabled
Stop-Service NvContainerLocalSystem -Force
```

## 3. Make sure the data disk is ready

The data disk is `D:\BDA\hdfs.vhdx`, which Linux sees as `/data`. Windows attaches it when you log in, and Linux mounts it automatically. This command checks it and fixes it if needed: it re-attaches the disk itself, with **no admin window needed**. **Normal PowerShell:**

```powershell
wsl -d Ubuntu -- bash -l /home/mit/bda/bin/bda-mount.sh
```

- It should print **`OK: /data mounted (… free of 295G)`**.
- Only if it prints **`FAIL`**, attach the disk by hand in **Admin PowerShell**, then run the command above again:

```powershell
wsl --mount --vhd D:\BDA\hdfs.vhdx --type ext4 --name bdadata
```

(Step 4's start script also runs this check, so on a normal day you can go straight to step 4.)

## 4. Start the big-data services

In **normal PowerShell**, pick **one**:

```powershell
# Most days: HDFS + YARN + JobHistory (indexing, Pig, Hive, Spark, evaluation)
wsl -d Ubuntu -- bash -l /home/mit/bda/bin/bda-start.sh core
```

```powershell
# HBase days: the above plus HBase (loading HBase, the search page, the demo)
wsl -d Ubuntu -- bash -l /home/mit/bda/bin/bda-start.sh all
```

The script runs the step 3 check first and refuses to start if the data disk can't be mounted.

## 5. Check everything is up

```powershell
wsl -d Ubuntu -- bash -l /home/mit/bda/bin/bda-status.sh
```

The output should show all daemons running, the node in RUNNING state, and enough free space on `/` and `/data`.

**Web pages, in your Windows browser:**

| Page | Address |
|---|---|
| HDFS | http://localhost:9870 |
| YARN (running jobs) | http://localhost:8088 |
| JobHistory (finished jobs, counters) | http://localhost:19888 |
| HBase (on HBase days) | http://localhost:16010 |
| Search page (once built) | http://localhost:8090 |

## 6. Resume with Claude

Open Claude Code in this project folder and say **"Resume the BDA project"**. Claude keeps notes on where things stand.

---

## Shutting down at the end of a session (normal PowerShell)

```powershell
wsl -d Ubuntu -- bash -l /home/mit/bda/bin/bda-stop.sh all
wsl --shutdown
```

`wsl --shutdown` also detaches the data disk. Step 3 (or step 4) re-attaches it next time, automatically.

## Leaving a job running overnight

- Laptop plugged in, sleep **off**, lid open.
- **Don't** run the shut-down commands.
- Leave one terminal tab open running the line below. It keeps Linux awake and shows progress in the morning (close it with Ctrl+C).

```powershell
wsl -d Ubuntu -- bash -l /home/mit/bda/bin/bda-status.sh --watch
```
