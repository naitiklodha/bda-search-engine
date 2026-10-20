#!/bin/bash
# Embedded Hive (beeline -u jdbc:hive2://) that also works from scripts.
# Hive 4.2.1's beeline refuses to start without a real terminal ("Unable to create a terminal"),
# and JLine's dumb-terminal switch doesn't help, so run it inside a pseudo-terminal via script(1).
# Use -e, not -f: under the pseudo-terminal, -f only echoes the script without running it
# (bin/run-hive.sh passes a .sql file's statements with -e).
# Usage: bda-beeline.sh [beeline args...]   e.g.  bda-beeline.sh --outputformat=tsv2 -e "select 1"
set -o pipefail
source ~/bda/env.sh
cmd="beeline -u jdbc:hive2://"
for a in "$@"; do cmd+=" $(printf '%q' "$a")"; done
# Strip CRs and terminal control sequences (JLine switches screen modes, which can hide later console output).
script -q -e -c "$cmd" /dev/null < /dev/null | tr -d '\r' | sed -u 's/\x1b\[[0-9;?]*[a-zA-Z]//g; s/\x1b[=>]//g'
