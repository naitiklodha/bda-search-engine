#!/bin/bash
# WP1 step 6.1: Hadoop config for memory Profile B (WSL 10 GB, YARN 6144 MB), plan §5.5.
# Storage decision (17 Oct): scratch (YARN local dirs, container logs) on the data disk (D:), not C:.
set -euo pipefail
O=~/bda/opt
# versionless symlinks for whatever is extracted so far
link() { [ -d "$O/$2" ] && ln -sfn "$2" "$O/$1" && echo "link $1 -> $2" || echo "skip $1 ($2 not there yet)"; }
link jdk    jdk-21
link hadoop hadoop-3.4.3
link hbase  hbase-2.6.6-hadoop3
link spark  spark-4.1.2-bin-hadoop3
link hive   apache-hive-4.2.1-bin
link tez    apache-tez-0.10.5-bin
link pig    pig-0.18.0
link maven  apache-maven-3.9.16
grep -q 'bda/env.sh' ~/.bashrc || echo '[ -f ~/bda/env.sh ] && source ~/bda/env.sh' >> ~/.bashrc
source ~/bda/env.sh
H=/home/mit/bda
mkdir -p $H/hdfs/nn $H/tmp $H/logs/hadoop $H/run

grep -q 'BDA project' "$HADOOP_CONF_DIR/hadoop-env.sh" || cat >> "$HADOOP_CONF_DIR/hadoop-env.sh" <<EOF

# ---- BDA project (plan §5.5) ----
export JAVA_HOME=$H/opt/jdk
export HADOOP_HEAPSIZE_MAX=1g
export HADOOP_STOP_TIMEOUT=30
export HADOOP_LOG_DIR=$H/logs/hadoop
export HADOOP_PID_DIR=$H/run
EOF

cat > "$HADOOP_CONF_DIR/core-site.xml" <<EOF
<?xml version="1.0"?>
<configuration>
  <property><name>fs.defaultFS</name><value>hdfs://localhost:9000</value></property>
  <property><name>hadoop.tmp.dir</name><value>$H/tmp</value></property>
  <property><name>hadoop.proxyuser.mit.hosts</name><value>*</value></property>
  <property><name>hadoop.proxyuser.mit.groups</name><value>*</value></property>
</configuration>
EOF

cat > "$HADOOP_CONF_DIR/hdfs-site.xml" <<EOF
<?xml version="1.0"?>
<configuration>
  <property><name>dfs.replication</name><value>1</value></property>
  <property><name>dfs.namenode.name.dir</name><value>file://$H/hdfs/nn</value></property>
  <property><name>dfs.datanode.data.dir</name><value>file:///data/hdfs/dn</value></property>
</configuration>
EOF

cat > "$HADOOP_CONF_DIR/yarn-site.xml" <<EOF
<?xml version="1.0"?>
<configuration>
  <property><name>yarn.nodemanager.aux-services</name><value>mapreduce_shuffle</value></property>
  <property><name>yarn.nodemanager.env-whitelist</name><value>JAVA_HOME,HADOOP_COMMON_HOME,HADOOP_HDFS_HOME,HADOOP_CONF_DIR,CLASSPATH_PREPEND_DISTCACHE,HADOOP_YARN_HOME,HADOOP_HOME,PATH,LANG,TZ,HADOOP_MAPRED_HOME</value></property>
  <property><name>yarn.nodemanager.resource.memory-mb</name><value>6144</value></property>
  <property><name>yarn.scheduler.maximum-allocation-mb</name><value>6144</value></property>
  <property><name>yarn.scheduler.minimum-allocation-mb</name><value>512</value></property>
  <property><name>yarn.nodemanager.resource.cpu-vcores</name><value>6</value></property>
  <property><name>yarn.scheduler.maximum-allocation-vcores</name><value>6</value></property>
  <property><name>yarn.nodemanager.local-dirs</name><value>/data/yarn/local</value></property>
  <property><name>yarn.nodemanager.log-dirs</name><value>/data/yarn/userlogs</value></property>
  <property><name>yarn.nodemanager.vmem-check-enabled</name><value>false</value></property>
</configuration>
EOF

cat > "$HADOOP_CONF_DIR/mapred-site.xml" <<EOF
<?xml version="1.0"?>
<configuration>
  <property><name>mapreduce.framework.name</name><value>yarn</value></property>
  <property><name>mapreduce.application.classpath</name><value>$H/opt/hadoop/share/hadoop/mapreduce/*:$H/opt/hadoop/share/hadoop/mapreduce/lib/*</value></property>
  <property><name>yarn.app.mapreduce.am.env</name><value>HADOOP_MAPRED_HOME=$H/opt/hadoop</value></property>
  <property><name>mapreduce.map.env</name><value>HADOOP_MAPRED_HOME=$H/opt/hadoop</value></property>
  <property><name>mapreduce.reduce.env</name><value>HADOOP_MAPRED_HOME=$H/opt/hadoop</value></property>
  <property><name>mapreduce.map.memory.mb</name><value>1024</value></property>
  <property><name>mapreduce.map.java.opts</name><value>-Xmx819m</value></property>
  <property><name>mapreduce.reduce.memory.mb</name><value>2048</value></property>
  <property><name>mapreduce.reduce.java.opts</name><value>-Xmx1638m</value></property>
  <property><name>yarn.app.mapreduce.am.resource.mb</name><value>1024</value></property>
  <property><name>yarn.app.mapreduce.am.command-opts</name><value>-Xmx819m</value></property>
  <property><name>mapreduce.task.io.sort.mb</name><value>256</value></property>
</configuration>
EOF

# Let a second application master run alongside the first (e.g. a Hive/Tez session + a Spark job).
sed -i 's#<value>0.1</value>#<value>0.5</value>#' "$HADOOP_CONF_DIR/capacity-scheduler.xml"
grep -A1 'maximum-am-resource-percent' "$HADOOP_CONF_DIR/capacity-scheduler.xml" | grep value

if [ -d "$H/hdfs/nn/current" ]; then
  echo "--- NameNode already formatted; not touching it ---"
else
  echo "--- config written; formatting a fresh NameNode in $H/hdfs/nn ---"
  hdfs namenode -format -nonInteractive 2>&1 | grep -E 'successfully formatted|ERROR|Exception' || true
fi
