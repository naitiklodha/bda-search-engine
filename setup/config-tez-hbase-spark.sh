#!/bin/bash
# WP1 steps 5.3, 7 (Tez part), 8 (HBase config), 10.1 (Spark defaults). Profile B. No root needed.
set -euo pipefail
O=~/bda/opt; H=/home/mit/bda
link() { [ -d "$O/$2" ] && ln -sfn "$2" "$O/$1" && echo "link $1 -> $2"; }
link hive  apache-hive-4.2.1-bin
link tez   apache-tez-0.10.5-bin
link pig   pig-0.18.0
link maven apache-maven-3.9.16
source ~/bda/env.sh

echo "=== Tez ==="
mkdir -p "$TEZ_CONF_DIR"
cat > "$TEZ_CONF_DIR/tez-site.xml" <<EOF
<?xml version="1.0"?>
<configuration>
  <property><name>tez.lib.uris</name><value>\${fs.defaultFS}/apps/tez/tez.tar.gz</value></property>
  <property><name>tez.use.cluster.hadoop-libs</name><value>false</value></property>
  <property><name>tez.am.resource.memory.mb</name><value>1024</value></property>
  <property><name>tez.am.launch.cmd-opts</name><value>-Xmx819m</value></property>
</configuration>
EOF
hdfs dfs -mkdir -p /apps/tez
hdfs dfs -put -f "$TEZ_HOME/share/tez.tar.gz" /apps/tez/
hdfs dfs -ls -h /apps/tez | tail -1

echo "=== HBase (pseudo-distributed on HDFS, plan §5.6) ==="
mkdir -p $H/zookeeper $H/logs/hbase $H/tmp/hbase
cat > "$HBASE_HOME/conf/hbase-site.xml" <<EOF
<?xml version="1.0"?>
<configuration>
  <property><name>hbase.cluster.distributed</name><value>true</value></property>
  <property><name>hbase.rootdir</name><value>hdfs://localhost:9000/hbase</value></property>
  <property><name>hbase.zookeeper.quorum</name><value>localhost</value></property>
  <property><name>hbase.zookeeper.property.dataDir</name><value>$H/zookeeper</value></property>
  <property><name>zookeeper.session.timeout</name><value>90000</value></property>
  <property><name>hbase.zookeeper.property.tickTime</name><value>6000</value></property>
  <property><name>hbase.unsafe.stream.capability.enforce</name><value>false</value></property>
  <property><name>hbase.tmp.dir</name><value>$H/tmp/hbase</value></property>
</configuration>
EOF
grep -q 'BDA project' "$HBASE_HOME/conf/hbase-env.sh" || cat >> "$HBASE_HOME/conf/hbase-env.sh" <<EOF

# ---- BDA project (plan §5.6, Profile B) ----
export JAVA_HOME=$H/opt/jdk
export HBASE_MANAGES_ZK=true
export HBASE_HEAPSIZE=1g
export HBASE_LOG_DIR=$H/logs/hbase
export HBASE_PID_DIR=$H/run
export HBASE_DISABLE_HADOOP_CLASSPATH_LOOKUP="true"
EOF

echo "=== Spark (plan §5.8, Profile B; scratch on the data disk) ==="
mkdir -p /data/spark-tmp
cat > "$SPARK_HOME/conf/spark-defaults.conf" <<EOF
spark.master                     yarn
spark.submit.deployMode          client
spark.driver.memory              1536m
spark.executor.memory            2g
spark.executor.cores             2
spark.executor.instances         2
spark.sql.shuffle.partitions     8
spark.serializer                 org.apache.spark.serializer.KryoSerializer
spark.local.dir                  /data/spark-tmp
spark.pyspark.python             $H/venv/bin/python
spark.eventLog.enabled           true
spark.eventLog.dir               hdfs://localhost:9000/spark-logs
spark.yarn.jars                  hdfs://localhost:9000/apps/spark/jars/*.jar
EOF
# Upload Spark's jars once, so each spark-submit doesn't re-upload ~300 MB to HDFS.
hdfs dfs -mkdir -p /apps/spark/jars
hdfs dfs -put -f "$SPARK_HOME"/jars/*.jar /apps/spark/jars/
echo "spark jars in HDFS: $(hdfs dfs -count /apps/spark/jars | awk '{print $2" files, "$3" bytes"}')"
echo CONFIG_TEZ_HBASE_SPARK_OK
