# BDA project environment (bda-project-plan.md §5.2). Sourced by ~/.bashrc and by every bda script.
export BDA=$HOME/bda
export JAVA_HOME=$BDA/opt/jdk
export HADOOP_HOME=$BDA/opt/hadoop
export HADOOP_CONF_DIR=$HADOOP_HOME/etc/hadoop
export HIVE_HOME=$BDA/opt/hive
export TEZ_HOME=$BDA/opt/tez
export TEZ_CONF_DIR=$TEZ_HOME/conf
export HBASE_HOME=$BDA/opt/hbase
export SPARK_HOME=$BDA/opt/spark
export PIG_HOME=$BDA/opt/pig
export MAVEN_HOME=$BDA/opt/maven
export PYSPARK_PYTHON=$BDA/venv/bin/python
export PATH=$JAVA_HOME/bin:$HADOOP_HOME/bin:$HADOOP_HOME/sbin:$HIVE_HOME/bin:$HBASE_HOME/bin:$SPARK_HOME/bin:$PIG_HOME/bin:$MAVEN_HOME/bin:$PATH
