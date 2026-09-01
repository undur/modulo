#!/usr/bin/env bash
#
# setup-server-apache-modulo.sh — the hybrid deployment: Apache owns
# ports 80/443 (TLS via certbot, virtual hosts, static files) and
# modulo stands behind it as the WO-aware reverse proxy — replacing
# mod_WebObjects without compiling an Apache module.
#
#   Apache       :80/443  TLS, vhosts, redirects, static files, certbot
#   modulo       :1400    WO-aware proxying (plain-proxy mode)
#   wotaskd      :1085    app lifecycle daemon
#   JavaMonitor  :56789   its web UI
#
# REFERENCE DRAFT, published for comparison: this is what the stack
# looks like when Apache stays in the picture. The field-tested path is
# setup-server.sh — the pure stack, where modulo owns the front end and
# the Apache/certbot sections below simply don't exist.
#
# Usage: ./setup-server-apache-modulo.sh <server-hostname> <acme-email>
set -euo pipefail

SERVER_HOST="${1:?usage: setup-server-apache-modulo.sh <server-hostname> <acme-email>}"
CERTBOT_EMAIL="${2:?usage: setup-server-apache-modulo.sh <server-hostname> <acme-email>}"
SERVER="root@${SERVER_HOST}"

WORKDIR="${MODULO_SETUP_DIR:-${HOME}/.modulo-setup}"
JDK_VERSION="${JDK_VERSION:-latest}"
if [ "${JDK_VERSION}" = "latest" ]; then
  JDK_VERSION="$(curl -fsSL 'https://api.adoptium.net/v3/info/available_releases' | grep -o '"most_recent_feature_release": *[0-9]*' | grep -o '[0-9]*$')"
fi
JVM="/opt/jdk-${JDK_VERSION}/bin/java"

# The stack password guards wotaskd's config channel and the JavaMonitor
# UI — same mechanism as the pure stack.
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
# Identical to the pure stack: the same three bundles are built either
# way — the difference is only who answers ports 80 and 443.

mkdir -p "${WORKDIR}"
clone_or_update() {
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

ssh "${SERVER}" "
  set -e
  if ! test -x ${JVM}; then
    case \$(uname -m) in x86_64) ARCH=x64;; aarch64) ARCH=aarch64;; *) echo 'unsupported arch' >&2; exit 1;; esac
    curl -fsSL \"https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/linux/\${ARCH}/jdk/hotspot/normal/eclipse\" -o /tmp/jdk.tgz
    mkdir -p /opt/jdk-${JDK_VERSION} && tar -xzf /tmp/jdk.tgz -C /opt/jdk-${JDK_VERSION} --strip-components=1 && rm /tmp/jdk.tgz
  fi
  test -x ${JVM} || { echo 'ERROR: JVM install failed' >&2; exit 1; }
"

### 3 — Server layout ####################################################
# Same layout as the pure stack, minus the acme/ directory — Apache and
# certbot own certificates here, in /etc/letsencrypt.

ssh "${SERVER}" "
  id webobjects >/dev/null 2>&1 || useradd --system --create-home webobjects
  mkdir -p /opt/webobjects/apps /opt/webobjects/conf /opt/webobjects/log
"

### 4 — Upload the bundles ###############################################

ssh "${SERVER}" 'for APP in wotaskd JavaMonitor modulo-runner; do
  if [ -d "/opt/webobjects/apps/${APP}.woa" ]; then mv "/opt/webobjects/apps/${APP}.woa" "/opt/webobjects/apps/x-${APP}.woa-prev-$(date +%Y%m%d-%H%M%S)"; fi
done'

scp -q -r "${STACK_REPO}/wotaskd/target/wotaskd.woa"              "${SERVER}:/opt/webobjects/apps/"
scp -q -r "${STACK_REPO}/JavaMonitor/target/JavaMonitor.woa"      "${SERVER}:/opt/webobjects/apps/"
scp -q -r "${MODULO_REPO}/modulo-runner/target/modulo-runner.woa" "${SERVER}:/opt/webobjects/apps/"

### 5 — modulo's config: plain-proxy mode ################################
# No [frontend], no [acme], no [[sites]] — Apache owns all of that.
# modulo serves only its WO-aware proxy connector on port 1400: instance
# routing, session affinity, failover, WebSocket tunneling. TLS,
# hostnames, redirects and certificates are Apache's problem now.

if ! ssh "${SERVER}" "test -e /opt/webobjects/modulo.toml"; then
ssh "${SERVER}" "cat > /opt/webobjects/modulo.toml" << EOF
# Plain-proxy mode: Apache fronts, modulo proxies WO traffic on :1400

[wotaskd]
host     = "${SERVER_HOST}"
port     = 1085
password = "${STACK_HASH}"
EOF
fi

# wotaskd's config store, carrying the stack password — identical to
# the pure stack.
if ! ssh "${SERVER}" "test -e /opt/webobjects/conf/SiteConfig.xml"; then
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

### 6 — systemd units ####################################################
# One difference from the pure stack: modulo binds only :1400, so it
# needs no AmbientCapabilities — nothing here touches a privileged port.

ssh "${SERVER}" "cat > /etc/systemd/system/wotaskd.service" << EOF
[Unit]
Description=wotaskd
After=network.target
[Service]
User=webobjects
ExecStart=/opt/webobjects/apps/wotaskd.woa/wotaskd -WOPort 1085 -WOHost ${SERVER_HOST} -DWODeploymentConfigurationDirectory=/opt/webobjects/conf -Xms32m -Xmx512m
StandardOutput=append:/opt/webobjects/log/wotaskd.log
StandardError=inherit
Restart=on-failure
[Install]
WantedBy=multi-user.target
EOF

ssh "${SERVER}" "cat > /etc/systemd/system/javamonitor.service" << EOF
[Unit]
Description=javamonitor
After=network.target wotaskd.service
[Service]
User=webobjects
ExecStart=/opt/webobjects/apps/JavaMonitor.woa/JavaMonitor -WOPort 56789 -WOHost ${SERVER_HOST} -DWODeploymentConfigurationDirectory=/opt/webobjects/conf -Xms32m -Xmx512m
StandardOutput=append:/opt/webobjects/log/javamonitor.log
StandardError=inherit
Restart=on-failure
[Install]
WantedBy=multi-user.target
EOF

ssh "${SERVER}" "cat > /etc/systemd/system/modulo.service" << EOF
[Unit]
Description=modulo (plain proxy behind Apache)
After=network.target
[Service]
User=webobjects
ExecStart=/opt/webobjects/apps/modulo-runner.woa/modulo-runner -WOPort 45678 -Xms128m -Xmx256m
StandardOutput=append:/opt/webobjects/log/modulo.log
StandardError=inherit
Restart=on-failure
[Install]
WantedBy=multi-user.target
EOF

ssh "${SERVER}" "systemctl daemon-reload && systemctl enable wotaskd javamonitor modulo && systemctl restart wotaskd javamonitor modulo"

### 7 — Apache ###########################################################
# The part the pure stack doesn't have. Apache terminates TLS and
# forwards WO traffic to modulo's proxy port. Friendly URLs are
# per-vhost RewriteRules into adaptor URL space — maintained here, in
# Apache config files, per site (the pure stack keeps them in the
# site's own TOML, hot-reloadable).

ssh "${SERVER}" "
  export DEBIAN_FRONTEND=noninteractive
  apt-get -qq update && apt-get -qq install -y apache2 >/dev/null
  a2enmod -q proxy proxy_http rewrite headers ssl >/dev/null
"

ssh "${SERVER}" "cat > /etc/apache2/sites-available/${SERVER_HOST}.conf" << EOF
<VirtualHost *:80>
	ServerName ${SERVER_HOST}
	ProxyPreserveHost On

	# WO adaptor URLs go to modulo, which routes them to app instances
	ProxyPass        /Apps/WebObjects/ http://127.0.0.1:1400/Apps/WebObjects/
	ProxyPassReverse /Apps/WebObjects/ http://127.0.0.1:1400/Apps/WebObjects/

	# Friendly URLs: one RewriteRule per mapping, per vhost. Example:
	# RewriteEngine On
	# RewriteRule ^/$ /Apps/WebObjects/MyApp.woa/ [PT]
</VirtualHost>
EOF

ssh "${SERVER}" "a2ensite -q ${SERVER_HOST} && systemctl reload apache2"

### 8 — certbot ##########################################################
# Certificates, the Apache way. Each hostname is a certbot invocation;
# each NEW hostname, forever, is another certbot invocation before it
# can serve HTTPS. Renewal runs from certbot's systemd timer — a second
# certificate machinery to monitor, apart from the web server. (The
# pure stack: add the hostname to a site file, POST /reload, done.)

ssh "${SERVER}" "
  export DEBIAN_FRONTEND=noninteractive
  apt-get -qq install -y certbot python3-certbot-apache >/dev/null
  certbot --apache --non-interactive --agree-tos -m '${CERTBOT_EMAIL}' --redirect -d '${SERVER_HOST}'
  systemctl list-timers certbot.timer --no-pager | head -2
"

### 9 — Log rotation, ownership, start ###################################

ssh "${SERVER}" "cat > /etc/logrotate.d/webobjects" << 'EOF'
/opt/webobjects/log/*.log {
	daily
	rotate 14
	compress
	delaycompress
	missingok
	notifempty
	copytruncate
}
EOF

ssh "${SERVER}" "chown -R webobjects:webobjects /opt/webobjects"

### 10 — What you have now ###############################################
# Apache on 80/443 with a certbot certificate for the server hostname,
# forwarding WO traffic to modulo's proxy on :1400. Compared to the
# pure stack you now also own: Apache's config language, per-vhost
# rewrite maintenance, certbot's renewal timer, and one certbot run per
# new hostname. What you kept from modulo: WO-aware instance routing,
# session affinity, failover and WebSocket tunneling — without
# compiling mod_WebObjects.

echo "
Stack is up behind Apache on ${SERVER_HOST}:

  https://${SERVER_HOST}/           Apache (certbot certificate)
  http://${SERVER_HOST}:56789/      JavaMonitor — add apps and instances here
                                    (password: ${STACK_PASSWORD})

Adding a site: a vhost conf + a2ensite + certbot -d <hostname> + reload.
"
