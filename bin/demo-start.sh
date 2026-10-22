#!/bin/bash
# Demo start (plan WP12 / S7): every service up, then the browser tabs open. One command, from cold.
# From Windows PowerShell:  wsl -d Ubuntu -- bash -l /home/mit/bda/bin/demo-start.sh
source ~/bda/env.sh
t0=$(date +%s)
bash "$BDA/bin/bda-start.sh" all || exit 1
J=$BDA/repo/java/target/bda-search-1.0.jar
if unzip -l "$J" 2>/dev/null | grep -q 'bda/serve/QueryServer.class'; then          # search page (WP10)
  if ! curl -s -o /dev/null http://localhost:8090/; then
    mkdir -p "$BDA/tmp" && hdfs dfs -get -f /bda/wiki/index/meta/meta.tsv "$BDA/tmp/wiki-meta.tsv"
    # --add-opens: JDK 21 + HBase client (WP9 build-log: java.nio.Bits.unaligned not accessible otherwise).
    # Classpath = the minimal HBase client set (`hbase mapredcp`, as the WP9 loaders used) + Hadoop's own jars.
    # The full `hbase classpath` crashes on JDK 21: a lib jar registers an InetAddressResolverProvider
    # (shaded dnsjava) whose class isn't on the path -> ServiceConfigurationError.
    bash "$BDA/bin/bda-run.sh" queryserver java -Xmx1g --add-opens java.base/java.nio=ALL-UNNAMED \
      -cp "$J:$(hbase mapredcp 2>/dev/null):$HBASE_HOME/conf:$(hadoop classpath)" bda.serve.QueryServer --port 8090
    for i in $(seq 60); do curl -s -o /dev/null http://localhost:8090/ && break; sleep 2; done
    grep -m1 'QueryServer ready' "$BDA/logs/queryserver.log" || { echo "QueryServer did not come up:"; tail -15 "$BDA/logs/queryserver.log"; }
  fi
  PAGES="http://localhost:8090/"
else
  echo "(search page not built yet: arrives with WP10)"
  PAGES=""
fi
echo "=== demo ready in $(( $(date +%s) - t0 )) s ==="
[ -n "${NO_BROWSER:-}" ] && { echo "(NO_BROWSER set: not opening tabs)"; exit 0; }
for url in $PAGES "http://localhost:9870/explorer.html#/bda" "http://localhost:8088/cluster" \
           "http://localhost:19888/jobhistory" "http://localhost:16010/master-status"; do
  (cd /mnt/c && /mnt/c/Windows/System32/cmd.exe /c start "" "$url" >/dev/null 2>&1)
done
echo "browser tabs opened: search page (if built), HDFS, YARN, JobHistory, HBase"
