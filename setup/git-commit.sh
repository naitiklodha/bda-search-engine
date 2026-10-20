#!/bin/bash
# Put scripts + docs under version control and commit WP1-WP3 (plan §7.5: commit after every WP).
set -euo pipefail
source ~/bda/env.sh
SP=/mnt/c/Users/HP/AppData/Local/Temp/claude/C--Users-HP-Documents-NMIMS-NMIMS-Semester-11-Big-Data-Analytics-Big-Data-Project/d4ee2dfc-0299-46b8-8bd6-b13afbc54b90/scratchpad
WIN="/mnt/c/Users/HP/Documents/NMIMS/NMIMS Semester 11/Big Data Analytics/Big Data Project"
R=$BDA/repo
cd "$R"

# refresh docs + .gitignore from the scratchpad copy
for f in docs/setup-log.md docs/build-log.md .gitignore; do tr -d '\r' < "$SP/repo-src/$f" > "$R/$f"; done
# planning docs from the Windows project folder
for f in bda-project-plan.md bda-preflight-check.md bda-preflight-report.md startup-commands.md; do
  tr -d '\r' < "$WIN/$f" > "$R/docs/$f"
done

# service scripts: repo/bin becomes the single copy; ~/bda/bin points at it
if [ -d "$BDA/bin" ] && [ ! -L "$BDA/bin" ]; then
  cp -a "$BDA/bin/." "$R/bin/" && rm -rf "$BDA/bin" && ln -s repo/bin "$BDA/bin"
fi
# setup scripts (no secrets in them) + env.sh
cp -a "$BDA/setup/." "$R/setup/"
cp "$BDA/env.sh" "$R/setup/env.sh"
grep -rl "$(cat "$BDA/secrets/hive-db.txt")" "$R" && { echo "ABORT: secret found in repo"; exit 1; } || echo "secret check: clean"

[ -d .git ] || git init -q -b main
git config user.name "Mit Shah"
git config user.email "cool.mit005@gmail.com"
git add -A
git commit -q -F - <<'MSG'
WP1-WP3: environment, data acquisition, tokeniser

- Service scripts: bda-start/stop/status/run/mount/beeline (setsid; disk auto-mount)
- Setup scripts for the Profile B stack (Hadoop, Hive+Tez+PostgreSQL, HBase, Spark, Pig)
- Maven project with the shaded jar, Tokenizer (11 tests), TokenizeQueries, TokenizerBench, TokenWordCount
- Fixed 1,000-query MS MARCO sample (seed 42), spark/hello.py
- docs: plan, preflight, startup commands, setup-log, build-log

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
git log --oneline | head -3
git show --stat --oneline HEAD | tail -5
ls -la "$BDA/bin"
