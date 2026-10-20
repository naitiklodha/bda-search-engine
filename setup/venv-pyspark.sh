#!/bin/bash
# WP1 steps 10.3 + 11 (plan §5.9): Python venv, then PySpark on YARN with the venv's Python.
# pyspark itself is NOT pip-installed: spark-submit uses $SPARK_HOME/python (saves ~450 MB on C:).
# pytrec-eval-terrier instead of pytrec_eval (no Python 3.12 wheel for the latter; same `import pytrec_eval`).
set -euo pipefail
source ~/bda/env.sh
[ -x "$BDA/venv/bin/python" ] || python3 -m venv "$BDA/venv"
"$BDA/venv/bin/pip" install -q --no-cache-dir --upgrade pip
"$BDA/venv/bin/pip" install -q --no-cache-dir pandas matplotlib numpy scipy tqdm pytrec-eval-terrier
"$BDA/venv/bin/python" - <<'EOF'
import sys, numpy, pandas, scipy, matplotlib, tqdm, pytrec_eval
print("venv python", sys.version.split()[0], "| numpy", numpy.__version__, "| pandas", pandas.__version__,
      "| scipy", scipy.__version__, "| matplotlib", matplotlib.__version__, "| pytrec_eval OK")
EOF
du -sh "$BDA/venv"

mkdir -p "$BDA/repo/spark"
cat > "$BDA/repo/spark/hello.py" <<'EOF'
# WP1 step 10.3: proves PySpark runs on YARN with the project venv's Python on the executors.
import sys
from pyspark.sql import SparkSession
spark = SparkSession.builder.appName("bda-hello").getOrCreate()
n = spark.range(10**7).count()
execpy = spark.sparkContext.parallelize([0], 1).map(lambda _: __import__("sys").executable).collect()[0]
print(f"HELLO count={n} driver_python={sys.executable} executor_python={execpy}")
spark.stop()
EOF
start=$(date +%s)
spark-submit "$BDA/repo/spark/hello.py" 2>&1 | grep -E '^HELLO|Exception|Error' | head -5
echo "wall time: $(( $(date +%s) - start )) s"
