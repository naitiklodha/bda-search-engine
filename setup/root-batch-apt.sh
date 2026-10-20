#!/bin/bash
# Root batch 3 (needs approval): system packages + Hive metastore database (plan WP1 step 4, §5.7).
# --no-install-recommends keeps C: small (skips the C compiler / python3-dev that would otherwise come along).
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q --no-install-recommends postgresql python3-venv unzip
systemctl enable --now postgresql
systemctl is-active postgresql

# Metastore DB: role 'hive' with a generated password, stored for mit only (mode 600). Nobody types it.
SECRETS=/home/mit/bda/secrets
install -d -o mit -g mit -m 700 "$SECRETS"
if [ ! -s "$SECRETS/hive-db.txt" ]; then
  umask 077; head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' > "$SECRETS/hive-db.txt"
  chown mit:mit "$SECRETS/hive-db.txt"; chmod 600 "$SECRETS/hive-db.txt"
fi
PW=$(cat "$SECRETS/hive-db.txt")
sudo -u postgres psql -v ON_ERROR_STOP=1 -q <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'hive') THEN CREATE ROLE hive LOGIN PASSWORD '$PW';
  ELSE ALTER ROLE hive PASSWORD '$PW'; END IF;
END \$\$;
SQL
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='metastore'" | grep -q 1 \
  || sudo -u postgres createdb -O hive metastore
sudo -u postgres psql -tAc "SELECT datname, pg_get_userbyid(datdba) FROM pg_database WHERE datname='metastore'"
psql --version
python3 -m venv --help >/dev/null && echo "python3-venv OK"
df -h / | tail -1
