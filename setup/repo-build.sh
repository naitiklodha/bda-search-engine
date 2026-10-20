#!/bin/bash
# WP1 step 12 + WP3: create the repo layout (plan §7.5), copy sources in, pin Lucene 9.x, build + test.
set -uo pipefail
source ~/bda/env.sh
SRC=$(dirname "$(readlink -f "$0")")/repo-src
[ -d "$SRC" ] || SRC=/mnt/c/Users/HP/AppData/Local/Temp/claude/C--Users-HP-Documents-NMIMS-NMIMS-Semester-11-Big-Data-Analytics-Big-Data-Project/d4ee2dfc-0299-46b8-8bd6-b13afbc54b90/scratchpad/repo-src
R=$BDA/repo
mkdir -p "$R"/{java,pig,hive,spark,eval/runs,charts/data,bin,setup,docs,report}
(cd "$SRC" && find . -type f) | while read -r f; do
  mkdir -p "$R/$(dirname "$f")"; tr -d '\r' < "$SRC/$f" > "$R/$f"
done
LV=$(curl -fsSL https://repo1.maven.org/maven2/org/apache/lucene/lucene-analysis-common/maven-metadata.xml \
     | grep -oE '<version>9\.[0-9]+\.[0-9]+</version>' | sed -E 's#</?version>##g' | sort -V | tail -1)
sed -i "s/@LUCENE_VERSION@/$LV/" "$R/java/pom.xml"
echo "lucene-analysis-common pinned to $LV (newest 9.x)"
cd "$R/java"
start=$(date +%s)
mvn -B -ntp -Dstyle.color=never package > "$BDA/logs/mvn-package.log" 2>&1; rc=$?
echo "mvn package exit=$rc ($(( $(date +%s) - start )) s)"
grep -E 'Tests run:|FAIL|ERROR|BUILD' "$BDA/logs/mvn-package.log" | grep -v '^\[INFO\] *$' | head -20
ls -la target/*.jar 2>/dev/null
echo "jar has Porter stemmer: $(unzip -l target/bda-search-1.0.jar 2>/dev/null | grep -c 'org/tartarus/snowball/ext/PorterStemmer.class')"
echo "jar has Hadoop classes (must be 0): $(unzip -l target/bda-search-1.0.jar 2>/dev/null | grep -c 'org/apache/hadoop/')"
du -sh ~/.m2 2>/dev/null
