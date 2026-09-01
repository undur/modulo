#!/usr/bin/env bash
#
# setup-server-apache-mod_webobjects.sh — the classic WebObjects
# deployment, as it has been done since the last century:
#
#   Apache + mod_WebObjects  :80/443  TLS (certbot), vhosts, the compiled adaptor module
#   wotaskd                  :1085    app lifecycle daemon
#   JavaMonitor              :56789   its web UI
#
# REFERENCE DRAFT, published for comparison. This is the deployment
# the pure stack (setup-server.sh) replaces. Read it side by side and
# notice what appears here that doesn't exist there: a C toolchain, an
# Apache module build, split-installed webserver resources that must be
# refreshed on every deploy, per-vhost rewrite maintenance, and a
# second certificate machinery with its own timer. And what doesn't
# appear here because it can't: WebSocket proxying (mod_WebObjects
# predates WebSockets), hot config reload, automatic certificates for
# new sites.
#
# Usage: ./setup-server-apache-mod_webobjects.sh <server-hostname> <acme-email>
set -euo pipefail

SERVER_HOST="${1:?usage: setup-server-apache-mod_webobjects.sh <server-hostname> <acme-email>}"
CERTBOT_EMAIL="${2:?usage: setup-server-apache-mod_webobjects.sh <server-hostname> <acme-email>}"
SERVER="root@${SERVER_HOST}"

WORKDIR="${MODULO_SETUP_DIR:-${HOME}/.modulo-setup}"
JDK_VERSION="${JDK_VERSION:-latest}"
if [ "${JDK_VERSION}" = "latest" ]; then
  JDK_VERSION="$(curl -fsSL 'https://api.adoptium.net/v3/info/available_releases' | grep -o '"most_recent_feature_release": *[0-9]*' | grep -o '[0-9]*$')"
fi
JVM="/opt/jdk-${JDK_VERSION}/bin/java"

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

### 1 — Fetch the sources and build wotaskd + JavaMonitor ################
# Two bundles instead of three — no modulo here. The adaptor is
# compiled C, built on the server in section 5.

mkdir -p "${WORKDIR}"
clone_or_update() {
  if [ -d "${WORKDIR}/$2/.git" ]; then git -C "${WORKDIR}/$2" pull -q
  else git clone -q "$1" "${WORKDIR}/$2"; fi
}
clone_or_update https://github.com/undur/wonder-slim-deployment.git  wonder-slim-deployment

STACK_REPO="${WORKDIR}/wonder-slim-deployment"

( cd "${STACK_REPO}/sjip-core"   && mvn -q -DskipTests clean install )
( cd "${STACK_REPO}/wotaskd"     && mvn -q -DskipTests clean package "-Dlaunch.jvm=${JVM}" )
( cd "${STACK_REPO}/JavaMonitor" && mvn -q -DskipTests clean package "-Dlaunch.jvm=${JVM}" )

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

### 3 — Server layout, wotaskd and JavaMonitor ###########################

ssh "${SERVER}" "
  id webobjects >/dev/null 2>&1 || useradd --system --create-home webobjects
  mkdir -p /opt/webobjects/apps /opt/webobjects/conf /opt/webobjects/log
"

ssh "${SERVER}" 'for APP in wotaskd JavaMonitor; do
  if [ -d "/opt/webobjects/apps/${APP}.woa" ]; then mv "/opt/webobjects/apps/${APP}.woa" "/opt/webobjects/apps/x-${APP}.woa-prev-$(date +%Y%m%d-%H%M%S)"; fi
done'

scp -q -r "${STACK_REPO}/wotaskd/target/wotaskd.woa"         "${SERVER}:/opt/webobjects/apps/"
scp -q -r "${STACK_REPO}/JavaMonitor/target/JavaMonitor.woa" "${SERVER}:/opt/webobjects/apps/"

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

ssh "${SERVER}" "systemctl daemon-reload && systemctl enable wotaskd javamonitor && systemctl restart wotaskd javamonitor"

### 4 — Apache and a C toolchain #########################################
# The adaptor is an Apache module, so the web server needs its
# development headers and a compiler. On a production box.

ssh "${SERVER}" "
  export DEBIAN_FRONTEND=noninteractive
  apt-get -qq update
  apt-get -qq install -y apache2 apache2-dev gcc make git >/dev/null
  a2enmod -q rewrite headers ssl >/dev/null
"

### 5 — Build mod_WebObjects #############################################
# Clone Project Wonder (the adaptor sources live there), configure the
# build for your platform, compile, install with apxs. The make.config
# edit and the exact flags vary by OS and Apache version — when this
# step fails, and it does, the WOCommunity wiki is the reference.
# There is no binary distribution: every server compiles its own.

ssh "${SERVER}" "
  set -e
  test -d /usr/local/src/wonder || git clone -q --depth 1 https://github.com/wocommunity/wonder.git /usr/local/src/wonder
  cd /usr/local/src/wonder/Utilities/Adaptors
  # Edit make.config for your platform first: ADAPTOR_OS = LINUX, ADAPTORS = Apache2.4
  sed -i 's/^ADAPTOR_OS.*/ADAPTOR_OS = LINUX/; s/^ADAPTORS.*/ADAPTORS = Apache2.4/' make.config
  make CC=gcc
  apxs -i -a -n WebObjects Apache2.4/mod_WebObjects.la
"

# Tell the module where wotaskd publishes the adaptor config, and which
# URL prefix belongs to WO
ssh "${SERVER}" "cat > /etc/apache2/conf-available/webobjects.conf" << EOF
LoadModule WebObjects_module /usr/lib/apache2/modules/mod_WebObjects.so
WebObjectsConfig http://localhost:1085 10
WebObjectsAlias /Apps/WebObjects
EOF
ssh "${SERVER}" "a2enconf -q webobjects"

### 6 — The split install ################################################
# Apps' WebServerResources must live in Apache's document root, because
# the adaptor only forwards dynamic requests — static resources are
# served from disk. This is not a one-time step: every app you deploy,
# and every deploy of every app, must copy its WebServerResources (and
# every framework's) into the docroot again, or the app serves
# unstyled pages. Deployment scripts grow an rsync for this and it
# drifts anyway.

ssh "${SERVER}" "mkdir -p /var/www/html/WebObjects"
# Per app, per deploy:
#   rsync -a MyApp.woa/Contents/WebServerResources /var/www/html/WebObjects/MyApp.woa/Contents/
#   rsync -a MyApp.woa/Contents/Frameworks/*/WebServerResources ... (each framework)

### 7 — Virtual hosts and rewrites #######################################
# One vhost per site, friendly URLs as RewriteRules, maintained in
# Apache config, applied by reload. Adding a hostname to a site means
# editing here AND running certbot below.

ssh "${SERVER}" "cat > /etc/apache2/sites-available/${SERVER_HOST}.conf" << EOF
<VirtualHost *:80>
	ServerName ${SERVER_HOST}
	DocumentRoot /var/www/html

	# RewriteEngine On
	# RewriteRule ^/$ /Apps/WebObjects/MyApp.woa/ [PT]
</VirtualHost>
EOF

ssh "${SERVER}" "a2ensite -q ${SERVER_HOST} && systemctl reload apache2"

### 8 — certbot ##########################################################
# The certificate machinery: one certbot run per hostname, now and for
# every hostname you ever add. Renewal is certbot's own timer with its
# own failure modes, monitored separately from everything above. A
# wildcard needs the DNS-01 challenge and a DNS-provider plugin — a
# separate rabbit hole.

ssh "${SERVER}" "
  export DEBIAN_FRONTEND=noninteractive
  apt-get -qq install -y certbot python3-certbot-apache >/dev/null
  certbot --apache --non-interactive --agree-tos -m '${CERTBOT_EMAIL}' --redirect -d '${SERVER_HOST}'
  systemctl list-timers certbot.timer --no-pager | head -2
"

### 9 — Log rotation, ownership ##########################################

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
# The classic. Apache with a compiled adaptor module on 80/443, certbot
# certificates, wotaskd and JavaMonitor managing instances. What every
# future site costs: a vhost file, its rewrites, a certbot run, a
# reload. What every future deploy costs: the split-install refresh.
# What you don't get at any price: WebSockets through the adaptor, hot
# config reload, or a certificate that appears because you added a
# hostname to a file.

echo "
Classic stack is up on ${SERVER_HOST}:

  https://${SERVER_HOST}/           Apache + mod_WebObjects (certbot certificate)
  http://${SERVER_HOST}:56789/      JavaMonitor — add apps and instances here
                                    (password: ${STACK_PASSWORD})
"
