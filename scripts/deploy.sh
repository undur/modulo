#!/usr/bin/env bash
# Deploy modulo to a standard-layout server.
#
# Usage: deploy.sh <server-hostname>
#   e.g. deploy.sh hz1.rebbi.is
#        deploy.sh linode-4.rebbi.is
#
# Steps:
#   1. Local: install fresh modulo-frontend
#   2. Local: install fresh modulo-core (depends on modulo-frontend)
#   3. Local: clean+package fresh modulo-runner (pinned to the newest /opt/jdk-* on the server, or $JVM_PATH)
#   4. Server: move the existing .woa aside
#   5. scp: upload the new .woa
#   6. Server: chmod 777 the new .woa
#   7. Server: restart the modulo service
set -euo pipefail

SERVER="root@${1:?usage: deploy.sh <server-hostname>   (hz1.rebbi.is | linode-4.rebbi.is)}"
REMOTE_APPS_DIR="/opt/webobjects/apps"
SERVICE="modulo"
# The bundles bake their JVM path in at package time (config.txt), so it has to
# be the path on the *target*: the newest /opt/jdk-<version> installed there,
# unless JVM_PATH is given explicitly (JVM_PATH=/opt/jdk-26/bin/java deploy.sh …).
if [ -z "${JVM_PATH:-}" ]; then
	JVM_PATH="$(ssh "${SERVER}" "ls -d /opt/jdk-*/bin/java 2>/dev/null | sort -V | tail -n 1")"
	[ -n "${JVM_PATH}" ] || { echo "No /opt/jdk-*/bin/java found on ${SERVER}; pass JVM_PATH explicitly" >&2; exit 1; }
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WOA_LOCAL="${REPO_ROOT}/modulo-runner/target/modulo-runner.woa"
LIVE="${REMOTE_APPS_DIR}/modulo-runner.woa"
STAMP="$(date +%Y%m%d-%H%M%S)"
ASIDE="${REMOTE_APPS_DIR}/modulo-runner.woa.prev-${STAMP}"

cd "${REPO_ROOT}"

echo "==> [1/7] Building & installing modulo-frontend"
( cd modulo-frontend && mvn -q -DskipTests clean install )

echo "==> [2/7] Building & installing modulo-core"
( cd modulo-core && mvn -q -DskipTests clean install )

echo "==> [3/7] Building & packaging modulo-runner (jvm=${JVM_PATH})"
( cd modulo-runner && mvn -q -DskipTests clean package "-Dlaunch.jvm=${JVM_PATH}" )

if [ ! -d "${WOA_LOCAL}" ]; then
	echo "Build did not produce ${WOA_LOCAL}" >&2
	exit 1
fi

echo "==> [4/7] Moving existing remote .woa aside to ${ASIDE} (pruning older backups)"
ssh "${SERVER}" "if [ -e '${LIVE}' ]; then mv '${LIVE}' '${ASIDE}'; fi
	ls -d '${REMOTE_APPS_DIR}'/modulo-runner.woa.prev-* 2>/dev/null | sort | head -n -1 | xargs -r rm -rf"

echo "==> [5/7] Uploading new .woa to ${SERVER}:${LIVE}"
scp -q -r "${WOA_LOCAL}" "${SERVER}:${LIVE}"

echo "==> [6/7] chmod 777 ${LIVE}"
ssh "${SERVER}" "chmod -R 777 '${LIVE}'"

echo "==> [7/7] Restarting ${SERVICE}"
ssh "${SERVER}" "service ${SERVICE} stop && service ${SERVICE} start"

echo "==> Done. Previous bundle preserved at ${ASIDE} on the server."
