#!/bin/bash
# Run a Hive script in one embedded session and turn its LOCAL DIRECTORY outputs into charts/data/*.tsv.
# Usage: run-hive.sh hive/<script>.sql [corpus=wiki]
source ~/bda/env.sh
R=$BDA/repo
SQL=${1:?usage: run-hive.sh hive/<script>.sql [corpus]}
C=${2:-wiki}
OUTDIR=$BDA/tmp/hive-out/$(basename "$SQL" .sql)-$C
rm -rf "$OUTDIR"; mkdir -p "$OUTDIR" "$R/charts/data"
LOG=$BDA/logs/hive-$(basename "$SQL" .sql)-$C.log
start=$(date +%s)
# Hive 4.2.1's beeline under a pseudo-terminal only echoes a -f script without running it, but -e works:
# pass the file's statements (comment lines removed) with -e. --hivevar substitution didn't reach the
# -e text ("cannot recognize input near '$' '{' 'C'"), so ${OUTDIR} and ${C} are filled in here.
SQLTEXT=$(grep -v '^[[:space:]]*--' "$R/$SQL" | sed "s#\${OUTDIR}#$OUTDIR#g; s#\${C}#$C#g")
bash "$BDA/bin/bda-beeline.sh" -e "$SQLTEXT" > "$LOG" 2>&1; rc=$?
echo "hive $SQL ($C): exit=$rc  wall=$(( $(date +%s) - start )) s   log=$LOG"
grep -E 'Error:|FAILED|Exception' "$LOG" | grep -v WARN | head -5
grep -E 'DAG finished successfully' "$LOG" | sed 's/^.*Status: /  /' | head -20
for d in "$OUTDIR"/*/; do
  [ -d "$d" ] || continue
  name=$(basename "$d")
  cat "$d"/0* 2>/dev/null > "$R/charts/data/$name.tsv"
  echo "  charts/data/$name.tsv: $(wc -l < "$R/charts/data/$name.tsv") rows"
done
