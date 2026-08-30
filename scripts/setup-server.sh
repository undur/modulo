#!/usr/bin/env bash
#
# setup-server.sh — stand up the full deployment stack on a fresh server:
#
#   wotaskd      :1085   app lifecycle daemon — launches/watches app instances
#   JavaMonitor  :56789  its web UI — where you add apps and instances
#   modulo       :80/443 the front-facing HTTPS server / reverse proxy
#
# Standalone: run it from anywhere on your workstation — it fetches the
# sources itself, builds locally, and installs remotely over ssh
# (assumes git, maven, a local JDK, and root ssh to the server). The
# script doubles as the setup guide — read it top to bottom; it is the
# same process docs/SETUP.md describes.
#
# Usage: ./setup-server.sh <server-hostname> <acme-email>
set -euo pipefail

SERVER_HOST="${1:?usage: setup-server.sh <server-hostname> <acme-email>}"
ACME_EMAIL="${2:?usage: setup-server.sh <server-hostname> <acme-email>}"
SERVER="root@${SERVER_HOST}"

WORKDIR="${MODULO_SETUP_DIR:-${HOME}/.modulo-setup}"    # sources + build cache live here between runs
JVM="/opt/jdk-26/bin/java"                              # installed on the server if missing (section 2)

### 1 — Fetch the sources and build the three bundles ####################
# This whole section disappears the day prebuilt release archives exist —
# it then becomes a single download-and-unpack. Until that day: clone (or
# update) the two repos and build.
#
# -Dlaunch.jvm bakes the server's JVM path into each bundle's launcher;
# without it the launcher execs a bare `java`, which is not on PATH in
# systemd's spartan environment, and the app dies instantly. (Ask us
# how we know.)

mkdir -p "${WORKDIR}"
clone_or_update() {  # $1 = repo url, $2 = directory
  if [ -d "${WORKDIR}/$2/.git" ]; then git -C "${WORKDIR}/$2" pull -q
  else git clone -q "$1" "${WORKDIR}/$2"; fi
}
clone_or_update https://github.com/undur/modulo.git                  modulo
clone_or_update https://github.com/undur/wonder-slim-deployment.git  wonder-slim-deployment

MODULO_REPO="${WORKDIR}/modulo"
STACK_REPO="${WORKDIR}/wonder-slim-deployment"

( cd "${STACK_REPO}/sjip-core"        && mvn -q -DskipTests clean install )
( cd "${STACK_REPO}/wotaskd"          && mvn -q -DskipTests clean package "-Dlaunch.jvm=${JVM}" )
( cd "${STACK_REPO}/JavaMonitor"      && mvn -q -DskipTests clean package "-Dlaunch.jvm=${JVM}" )
( cd "${MODULO_REPO}/modulo-frontend" && mvn -q clean install )
( cd "${MODULO_REPO}/modulo-core"     && mvn -q clean install )
( cd "${MODULO_REPO}/modulo-runner"   && mvn -q clean package "-Dlaunch.jvm=${JVM}" )

### 2 — Server layout ####################################################
# The stack owns /opt/webobjects. Apps you deploy later conventionally
# get per-app homes elsewhere (we use /rebbi/<domain>/{wo,conf,log}) —
# the include pattern in modulo.toml below picks their site files up.
# A missing JVM is installed (Oracle JDK 26, matching the arch).

ssh "${SERVER}" "
  set -e
  if ! test -x ${JVM}; then
    case \$(uname -m) in x86_64) ARCH=x64;; aarch64) ARCH=aarch64;; *) echo 'unsupported arch' >&2; exit 1;; esac
    echo \"Installing Oracle JDK 26 (\${ARCH})...\"
    curl -fsSL \"https://download.oracle.com/java/26/latest/jdk-26_linux-\${ARCH}_bin.tar.gz\" -o /tmp/jdk.tgz
    mkdir -p /opt/jdk-26 && tar -xzf /tmp/jdk.tgz -C /opt/jdk-26 --strip-components=1 && rm /tmp/jdk.tgz
  fi
  test -x ${JVM} || { echo 'ERROR: JVM install failed' >&2; exit 1; }
  id webobjects >/dev/null 2>&1 || useradd --system --create-home webobjects
  mkdir -p /opt/webobjects/apps /opt/webobjects/conf /opt/webobjects/log/access /opt/webobjects/acme
"

### 3 — Upload the bundles ###############################################

scp -q -r "${STACK_REPO}/wotaskd/target/wotaskd.woa"           "${SERVER}:/opt/webobjects/apps/"
scp -q -r "${STACK_REPO}/JavaMonitor/target/JavaMonitor.woa"   "${SERVER}:/opt/webobjects/apps/"
scp -q -r "${MODULO_REPO}/modulo-runner/target/modulo-runner.woa" "${SERVER}:/opt/webobjects/apps/"

### 4 — modulo's config: one TOML file ###################################
# Note: top-level keys (`include`) must come before the first [table] —
# that's TOML's rule, not modulo's. The first site maps the server's own
# hostname to modulo's built-in admin/status app, so the moment the
# stack is up you get a real HTTPS site: the dashboard.

ADMIN_PASSWORD="$(openssl rand -base64 15 | tr -d '/+=')"

ssh "${SERVER}" "cat > /opt/webobjects/modulo.toml" << EOF
# modulo root config. [frontend]/[admin]/[wotaskd] apply at startup;
# sites/acme hot-reload via: curl -X POST -u :password https://host/reload

include = [ "/rebbi/*/conf/site.toml" ]   # per-app site files, picked up as apps arrive

[frontend]
httpPort     = 80        # must be reachable from the internet — ACME challenges arrive here
httpsPort    = 443
accessLogDir = "/opt/webobjects/log/access"

[admin]
password = "${ADMIN_PASSWORD}"

[wotaskd]
host     = "${SERVER_HOST}"
port     = 1085
password = "none"        # fresh wotaskd has no password; if you set one in JavaMonitor, mirror it here

[acme]
email   = "${ACME_EMAIL}"
storage = "/opt/webobjects/acme"
# directory = "letsencrypt-staging"   # uncomment while experimenting — production rate-limits failures

[[sites]]
hostnames = [ "${SERVER_HOST}" ]
app = "Modulo"           # modulo's own admin pages, served through itself
EOF

### 5 — systemd units ####################################################
# JavaMonitor runs as its own service, NOT as a wotaskd-managed app —
# the watcher shouldn't depend on the thing it watches. Modulo binds
# 80/443 unprivileged via AmbientCapabilities.

ssh "${SERVER}" 'cat > /etc/systemd/system/wotaskd.service' << EOF
[Unit]
Description=wotaskd
After=network.target
[Service]
User=webobjects
ExecStart=/opt/webobjects/apps/wotaskd.woa/wotaskd -WOPort 1085 -WOHost ${SERVER_HOST} -DWODeploymentConfigurationDirectory=/opt/webobjects/conf -Xms32m -Xmx64m >> /opt/webobjects/log/wotaskd.log 2>&1
Restart=on-failure
RestartSec=5
[Install]
WantedBy=multi-user.target
EOF

ssh "${SERVER}" 'cat > /etc/systemd/system/javamonitor.service' << EOF
[Unit]
Description=javamonitor
After=wotaskd.service
[Service]
User=webobjects
ExecStart=/opt/webobjects/apps/JavaMonitor.woa/JavaMonitor -WOPort 56789 -WOHost ${SERVER_HOST} -DWODeploymentConfigurationDirectory=/opt/webobjects/conf -Xms32m -Xmx64m >> /opt/webobjects/log/javamonitor.log 2>&1
Restart=on-failure
RestartSec=5
[Install]
WantedBy=multi-user.target
EOF

ssh "${SERVER}" 'cat > /etc/systemd/system/modulo.service' << 'EOF'
[Unit]
Description=modulo
After=wotaskd.service
[Service]
User=webobjects
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
NoNewPrivileges=yes
ExecStart=/opt/webobjects/apps/modulo-runner.woa/modulo-runner -WOPort 45678 -Xms128m -Xmx256m >> /opt/webobjects/log/modulo.log 2>&1
Restart=on-failure
RestartSec=5
[Install]
WantedBy=multi-user.target
EOF

### 6 — Log rotation, ownership, start ###################################
# (modulo rotates its per-site access logs itself; this covers the
# three service logs)

ssh "${SERVER}" '
  set -e
  printf "/opt/webobjects/log/*.log {\n\tdaily\n\trotate 14\n\tcompress\n\tdelaycompress\n\tmissingok\n\tnotifempty\n\tcopytruncate\n}\n" > /etc/logrotate.d/modulo
  chown -R webobjects:webobjects /opt/webobjects
  systemctl daemon-reload
  systemctl enable --now wotaskd javamonitor modulo
'

### 7 — What you have now ################################################

cat << EOF

Stack is up on ${SERVER_HOST}:

  https://${SERVER_HOST}/          modulo admin (any username, password: ${ADMIN_PASSWORD})
                                   — real certificate arrives seconds after first start
  http://${SERVER_HOST}:56789/     JavaMonitor — add apps and instances here
  tail -f via: ssh ${SERVER} 'tail -f /opt/webobjects/log/modulo.log'

Firewall: expose 80/443 to the world (80 is required for ACME);
keep 1085 (wotaskd) and 56789 (JavaMonitor) restricted.

Adding an app: deploy its .woa, add it in JavaMonitor, then drop
/rebbi/<domain>/conf/site.toml with its hostnames and POST /reload —
docs/SETUP.md Part 3 has the recipes.
EOF
