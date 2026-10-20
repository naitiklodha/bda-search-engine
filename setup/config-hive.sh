#!/bin/bash
# WP1 step 7 (plan §5.7): Hive 4.2.1 on Tez, metastore in PostgreSQL, embedded beeline only (no HiveServer2).
set -euo pipefail
source ~/bda/env.sh
H=/home/mit/bda
mkdir -p $H/tmp/hive $H/logs/hive

echo "=== PostgreSQL JDBC driver ==="
V=42.7.8; J=postgresql-$V.jar
ls "$HIVE_HOME"/lib/postgresql-*.jar 2>/dev/null && echo "(Hive already bundles a PostgreSQL driver)"
if [ ! -f "$HIVE_HOME/lib/$J" ]; then
  B=https://repo1.maven.org/maven2/org/postgresql/postgresql/$V
  curl -fsSL --connect-timeout 15 -o "/tmp/$J" "$B/$J"
  [ "$(curl -fsSL "$B/$J.sha1")" = "$(sha1sum "/tmp/$J" | cut -d' ' -f1)" ] && echo "sha1 OK $J" || { echo "SHA1 MISMATCH $J"; exit 1; }
  mv "/tmp/$J" "$HIVE_HOME/lib/"
fi

echo "=== Tez client jars ==="
# Hive 4.2.1 bundles only tez-api; the rest (tez-mapreduce, tez-dag, tez-runtime-*) come from Tez 0.10.5 itself.
# Only Tez's own jars ($TEZ_HOME/*), not $TEZ_HOME/lib/*, to avoid older Hadoop jars clashing with 3.4.3.
echo "Hive bundles: $(cd "$HIVE_HOME/lib" && ls tez-*.jar | tr '\n' ' ')"
TEZCP="$TEZ_CONF_DIR:$TEZ_HOME/*"

PW=$(cat $H/secrets/hive-db.txt)
umask 077
cat > "$HIVE_HOME/conf/hive-site.xml" <<EOF
<?xml version="1.0"?>
<configuration>
  <property><name>javax.jdo.option.ConnectionURL</name><value>jdbc:postgresql://localhost:5432/metastore</value></property>
  <property><name>javax.jdo.option.ConnectionDriverName</name><value>org.postgresql.Driver</value></property>
  <property><name>javax.jdo.option.ConnectionUserName</name><value>hive</value></property>
  <property><name>javax.jdo.option.ConnectionPassword</name><value>$PW</value></property>
  <property><name>hive.metastore.warehouse.dir</name><value>/user/hive/warehouse</value></property>
  <property><name>hive.metastore.uris</name><value></value></property>
  <property><name>hive.metastore.event.db.notification.api.auth</name><value>false</value></property>
  <property><name>hive.server2.enable.doAs</name><value>false</value></property>
  <property><name>hive.execution.engine</name><value>tez</value></property>
  <property><name>hive.tez.container.size</name><value>1536</value></property>
  <property><name>tez.am.resource.memory.mb</name><value>1024</value></property>
  <property><name>hive.tez.exec.print.summary</name><value>true</value></property>
  <property><name>hive.exec.scratchdir</name><value>/tmp/hive</value></property>
  <property><name>hive.exec.local.scratchdir</name><value>$H/tmp/hive</value></property>
  <property><name>hive.downloaded.resources.dir</name><value>$H/tmp/hive/resources</value></property>
  <property><name>hive.querylog.location</name><value>$H/logs/hive</value></property>
  <property><name>hive.server2.logging.operation.log.location</name><value>$H/tmp/hive/operation_logs</value></property>
</configuration>
EOF
umask 022
cat > "$HIVE_HOME/conf/hive-env.sh" <<EOF
export HADOOP_HOME=$H/opt/hadoop
export HIVE_CONF_DIR=$H/opt/hive/conf
export TEZ_CONF_DIR=$H/opt/tez/conf
export HADOOP_CLASSPATH=$TEZCP:\${HADOOP_CLASSPATH:-}
# JDK 21: beeline's JLine tries its FFM terminal (compiled as a Java preview feature) and dies with
# "Unable to create a terminal". Skip FFM; JLine falls back to its other providers.
export HADOOP_CLIENT_OPTS="\${HADOOP_CLIENT_OPTS:-} -Dorg.jline.terminal.ffm=false"
EOF
ls -l "$HIVE_HOME/conf/hive-site.xml"

echo "=== schematool -initSchema ==="
if schematool -dbType postgres -info 2>/dev/null | grep -q 'Metastore schema version'; then
  echo "schema already initialised"
else
  schematool -dbType postgres -initSchema 2>&1 | grep -E 'Initialization script completed|schemaTool completed|Error|Exception' | head -5
fi
schematool -dbType postgres -info 2>&1 | grep -E 'schema version' | head -2
