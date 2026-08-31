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
# Usage: ./setup-server.sh <server-hostname> <acme-email> [jdk-dist] [jdk-version]
#
#   jdk-dist     openjdk (default — Oracle's free build; "oracle" is an alias) or temurin
#   jdk-version  a major version number, or "latest" (the default)
#
# Options (environment variables):
#   STACK_PASSWORD=...        wotaskd/JavaMonitor password (default: generated)
#   MODULO_SETUP_DIR=...      where sources + build cache live (default ~/.modulo-setup)
set -euo pipefail

SERVER_HOST="${1:?usage: setup-server.sh <server-hostname> <acme-email> [jdk-dist] [jdk-version]}"
ACME_EMAIL="${2:?usage: setup-server.sh <server-hostname> <acme-email> [jdk-dist] [jdk-version]}"
SERVER="root@${SERVER_HOST}"

WORKDIR="${MODULO_SETUP_DIR:-${HOME}/.modulo-setup}"
JDK_DIST="${3:-${JDK_DIST:-openjdk}}"
[ "${JDK_DIST}" = "oracle" ] && JDK_DIST="openjdk"
JDK_VERSION="${4:-${JDK_VERSION:-latest}}"

# "latest" resolves to the newest GA major — both distributions track the
# same release train, so Adoptium's release index answers for either.
if [ "${JDK_VERSION}" = "latest" ]; then
  JDK_VERSION="$(curl -fsSL 'https://api.adoptium.net/v3/info/available_releases' | grep -o '"most_recent_feature_release": *[0-9]*' | grep -o '[0-9]*$')"
  echo "JDK: ${JDK_DIST} ${JDK_VERSION} (latest GA)"
fi
JVM="/opt/jdk-${JDK_VERSION}/bin/java"                  # installed on the server if missing (section 2)

# The stack password guards wotaskd's config channel and the JavaMonitor
# UI. It is stored and transmitted as the classic WO salted-MD5 hash —
# only you and the closing banner ever see the plaintext.
STACK_PASSWORD="${STACK_PASSWORD:-$(openssl rand -base64 12 | tr -d '/+=')}"
STACK_HASH="$(python3 - "$STACK_PASSWORD" << 'HASHEOF'
import hashlib, secrets, sys
salt = "".join(secrets.choice("0123456789ABCDEF") for _ in range(4))
digest = hashlib.md5(b"X#@!" + sys.argv[1].encode() + salt.encode()).digest()
out = salt
for byte in digest:
    b = byte - 256 if byte > 127 else byte
    mashed = 127 - b if b < 0 else b
    out += "0123456789ABCDEF"[mashed // 16] + "0123456789ABCDEF"[mashed % 16]
print(out)
HASHEOF
)"

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

### 2 — Install a JDK on the server, if missing ##########################
# Matching the machine's arch and your chosen distribution/version
# (JDK_DIST/JDK_VERSION above).

ssh "${SERVER}" "
  set -e
  if ! test -x ${JVM}; then
    case \$(uname -m) in x86_64) ARCH=x64;; aarch64) ARCH=aarch64;; *) echo 'unsupported arch' >&2; exit 1;; esac
    echo \"Installing ${JDK_DIST} JDK ${JDK_VERSION} (\${ARCH})...\"
    case '${JDK_DIST}' in
      openjdk) URL=\"https://download.oracle.com/java/${JDK_VERSION}/latest/jdk-${JDK_VERSION}_linux-\${ARCH}_bin.tar.gz\";;
      temurin) URL=\"https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/linux/\${ARCH}/jdk/hotspot/normal/eclipse\";;
      *) echo 'JDK_DIST must be openjdk or temurin' >&2; exit 1;;
    esac
    curl -fsSL \"\${URL}\" -o /tmp/jdk.tgz
    mkdir -p /opt/jdk-${JDK_VERSION} && tar -xzf /tmp/jdk.tgz -C /opt/jdk-${JDK_VERSION} --strip-components=1 && rm /tmp/jdk.tgz
  fi
  test -x ${JVM} || { echo 'ERROR: JVM install failed' >&2; exit 1; }
"

### 3 — Server layout ####################################################
# The stack owns /opt: the JDK sits in /opt/jdk-<version>, everything
# else under /opt/webobjects — one folder per job, listed below, with
# modulo.toml joining at its root in section 5. Everything runs as the
# unprivileged webobjects user. Apps you deploy later conventionally get
# per-app homes elsewhere (we use /rebbi/<domain>/{wo,conf,log}) — the
# include pattern in modulo.toml picks their site files up.

ssh "${SERVER}" "
  id webobjects >/dev/null 2>&1 || useradd --system --create-home webobjects
  mkdir -p /opt/webobjects/apps        # the stack's own .woa bundles: wotaskd, JavaMonitor, modulo-runner
  mkdir -p /opt/webobjects/conf        # SiteConfig.xml — wotaskd's config store
  mkdir -p /opt/webobjects/log/access  # one log per service; per-site access logs in access/
  mkdir -p /opt/webobjects/acme        # ACME account key + issued certificates
"

### 4 — Upload the bundles ###############################################
# Existing bundles are moved aside first, so re-running the script is a
# stack upgrade rather than an error.

ssh "${SERVER}" 'for APP in wotaskd JavaMonitor modulo-runner; do
  if [ -d "/opt/webobjects/apps/${APP}.woa" ]; then mv "/opt/webobjects/apps/${APP}.woa" "/opt/webobjects/apps/x-${APP}.woa-prev-$(date +%Y%m%d-%H%M%S)"; fi
done'

scp -q -r "${STACK_REPO}/wotaskd/target/wotaskd.woa"           "${SERVER}:/opt/webobjects/apps/"
scp -q -r "${STACK_REPO}/JavaMonitor/target/JavaMonitor.woa"   "${SERVER}:/opt/webobjects/apps/"
scp -q -r "${MODULO_REPO}/modulo-runner/target/modulo-runner.woa" "${SERVER}:/opt/webobjects/apps/"

### 5 — modulo's config: one TOML file ###################################
# Note: top-level keys (`include`) must come before the first [table] —
# that's TOML's rule, not modulo's. The first site maps the server's own
# hostname to modulo's built-in admin/status app, so the moment the
# stack is up you get a real HTTPS site: the dashboard.

ADMIN_PASSWORD="$(openssl rand -base64 15 | tr -d '/+=')"

# Both config files are written only when absent — a re-run never
# clobbers a configured server. (If you re-run with a NEW stack
# password on an old server, update SiteConfig.xml and modulo.toml's
# [wotaskd] password yourself, or delete them first.)
if ssh "${SERVER}" "test -e /opt/webobjects/modulo.toml"; then
  echo "modulo.toml exists — keeping it (admin password unchanged)"
  ADMIN_PASSWORD="(unchanged — see the server's modulo.toml)"
else
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
password = "${STACK_HASH}"   # the stack password's hash — wotaskd's config channel expects the hashed form

[acme]
email   = "${ACME_EMAIL}"
storage = "/opt/webobjects/acme"
# directory = "letsencrypt-staging"   # uncomment while experimenting — production rate-limits failures

[[sites]]
hostnames = [ "${SERVER_HOST}" ]
app = "Modulo"           # modulo's own admin pages, served through itself
EOF
fi

### 6 — wotaskd's config: an initial SiteConfig.xml ######################
# wotaskd's config store, carrying the stack password (hashed). The same
# password guards JavaMonitor's UI — log in there with the PLAINTEXT
# from the closing banner. Written only when absent, same rule as above.

if ssh "${SERVER}" "test -e /opt/webobjects/conf/SiteConfig.xml"; then
  echo "SiteConfig.xml exists — keeping it (stack password unchanged)"
  STACK_PASSWORD="(unchanged — the existing SiteConfig's password applies)"
else
ssh "${SERVER}" "cat > /opt/webobjects/conf/SiteConfig.xml" << EOF
<SiteConfig type="NSDictionary">
	<applicationArray type="NSArray">
	</applicationArray>
	<hostArray type="NSArray">
	</hostArray>
	<instanceArray type="NSArray">
	</instanceArray>
	<site type="NSDictionary">
		<password type="NSString">${STACK_HASH}</password>
		<viewRefreshEnabled type="NSString">YES</viewRefreshEnabled>
		<viewRefreshRate type="NSNumber">60</viewRefreshRate>
	</site>
</SiteConfig>
EOF
fi

### 7 — systemd units ####################################################
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

### 8 — Log rotation, ownership, start ###################################
# (modulo rotates its per-site access logs itself; this covers the
# three service logs)

ssh "${SERVER}" '
  set -e
  printf "/opt/webobjects/log/*.log {\n\tdaily\n\trotate 14\n\tcompress\n\tdelaycompress\n\tmissingok\n\tnotifempty\n\tcopytruncate\n}\n" > /etc/logrotate.d/modulo
  chown -R webobjects:webobjects /opt/webobjects
  systemctl daemon-reload
  systemctl enable wotaskd javamonitor modulo
  systemctl restart wotaskd javamonitor modulo
'

### 9 — Firewall: expose only ssh, 80 and 443 ############################
# Cloud images often ship with NO firewall (Hetzner does), which would
# leave wotaskd and JavaMonitor — which manage your apps — answering
# the whole internet. Applied only when no ruleset exists yet, so an
# already-firewalled server is left untouched.

ssh "${SERVER}" '
  if nft list ruleset 2>/dev/null | grep -q .; then
    echo "firewall: existing ruleset found — leaving it alone"
  else
    cat > /etc/nftables.conf << "NFTEOF"
#!/usr/sbin/nft -f
flush ruleset
table inet filter {
	chain input {
		type filter hook input priority 0; policy drop;
		iif "lo" accept
		ct state established,related accept
		ip protocol icmp accept
		ip6 nexthdr ipv6-icmp accept
		tcp dport { 22, 80, 443 } accept
	}
	chain forward { type filter hook forward priority 0; policy drop; }
	chain output { type filter hook output priority 0; policy accept; }
}
NFTEOF
    nft -f /etc/nftables.conf && systemctl enable --now nftables
    echo "firewall: ssh/80/443 only"
  fi
'

### 10 — What you have now ###############################################

cat << EOF

Stack is up on ${SERVER_HOST}:

  https://${SERVER_HOST}/          modulo admin (any username, password: ${ADMIN_PASSWORD})
                                   — real certificate arrives seconds after first start
  http://${SERVER_HOST}:56789/     JavaMonitor — add apps and instances here
                                   (password: ${STACK_PASSWORD} — also guards wotaskd's config channel)
  tail -f via: ssh ${SERVER} 'tail -f /opt/webobjects/log/modulo.log'

Adding an app: deploy its .woa, add it in JavaMonitor, then drop
/rebbi/<domain>/conf/site.toml with its hostnames and POST /reload —
docs/SETUP.md Part 3 has the recipes.
EOF
