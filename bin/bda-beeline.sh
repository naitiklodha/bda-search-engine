#!/bin/bash
# Embedded Hive (beeline -u jdbc:hive2://) that also works from scripts.
# Hive 4.2.1's beeline refuses to start without a real terminal ("Unable to create a terminal"),
# and JLine's dumb-terminal switch doesn't help, so run it inside a pseudo-terminal via script(1).
# Usage: bda-beeline.sh [beeline args...]   e.g.  bda-beeline.sh -f hive/stats.sql
#                                                  bda-beeline.sh --outputformat=tsv2 -e "select 1"
set -o pipefail
source ~/bda/env.sh
cmd="beeline -u jdbc:hive2://"
for a in "$@"; do cmd+=" $(printf '%q' "$a")"; done
script -q -e -c "$cmd" /dev/null < /dev/null | tr -d '\r'
