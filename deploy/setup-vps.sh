#!/usr/bin/env bash
# One-time setup of the IERailMetrics VPS for continuous deployment (see docs/deploy.md). Run as root ON the VPS:
#
#   scp deploy/deploy.sh deploy/setup-vps.sh root@<vps>:/tmp/
#   ssh root@<vps> 'bash /tmp/setup-vps.sh "<contents of ierailmetrics_deploy.pub>"'
#
# It finds the systemd service that runs the jar today, moves the jar under /opt/ierailmetrics/releases, repoints
# the service at current.jar with a drop-in (the original unit file is not edited), installs deploy.sh and authorizes
# the deploy key as a forced command. Safe to run again. Nothing is restarted until the very end, and if the service
# does not come back healthy the previous jar path is restored.
#
# Overridable: SERVICE (unit name), APP_DIR, HEALTH_URL.
set -euo pipefail

pubkey="${1:-}"
[[ "$pubkey" == ssh-ed25519\ * ]] || { echo "usage: setup-vps.sh \"ssh-ed25519 AAAA... comment\"" >&2; exit 2; }
[[ $EUID -eq 0 ]] || { echo "run as root" >&2; exit 2; }

here="$(cd "$(dirname "$0")" && pwd)"
app_dir="${APP_DIR:-/opt/ierailmetrics}"

# 1. Which service runs the jar?
service="${SERVICE:-}"
if [[ -z "$service" ]]; then
  mapfile -t found < <(systemctl list-unit-files --type=service --no-legend | awk '{print $1}' | grep -i -E 'ierail|irishrail|raildelay' | sed 's/\.service$//')
  if ((${#found[@]} != 1)); then
    echo "could not pick the service automatically (found: ${found[*]:-none}); rerun with SERVICE=<unit name>" >&2
    exit 1
  fi
  service="${found[0]}"
fi
echo "service: $service"

exec_line="$(systemctl show -p ExecStart --value "$service" | grep -o 'argv\[\]=[^;]*' | head -n 1 | sed 's/^argv\[\]=//')"
jar="$(grep -o -E '\S+\.jar' <<<"$exec_line" | head -n 1)"
[[ -n "$jar" && -f "$jar" ]] || { echo "could not find the jar in ExecStart of $service: '$exec_line'" >&2; exit 1; }
echo "current jar: $jar"

# 2. Release layout.
mkdir -p "$app_dir/releases"
if [[ "$(readlink -f "$jar")" != "$(readlink -f "$app_dir/current.jar" 2>/dev/null || true)" ]]; then
  cp -f "$jar" "$app_dir/releases/ierailmetrics-initial.jar"
  ln -sfn "$app_dir/releases/ierailmetrics-initial.jar" "$app_dir/current.jar"
fi

# 3. Drop-in: same command line, jar replaced by current.jar.
new_exec="${exec_line//$jar/$app_dir/current.jar}"
dropin="/etc/systemd/system/$service.service.d"
mkdir -p "$dropin"
printf '[Service]\nExecStart=\nExecStart=%s\n' "$new_exec" > "$dropin/current-jar.conf"
systemctl daemon-reload

# 4. Script and key.
health_url="${HEALTH_URL:-}"
install -m 755 "$here/deploy.sh" "$app_dir/deploy.sh"
sed -i "s|^  local service=.*|  local service=\"\${SERVICE:-$service}\"|" "$app_dir/deploy.sh"
[[ -n "$health_url" ]] && sed -i "s|^  local health_url=.*|  local health_url=\"\${HEALTH_URL:-$health_url}\"|" "$app_dir/deploy.sh"
touch /var/log/ierailmetrics-deploy.log

install -m 700 -d /root/.ssh
touch /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys
key_body="$(awk '{print $2}' <<<"$pubkey")"
if ! grep -q -F "$key_body" /root/.ssh/authorized_keys; then
  printf 'command="%s/deploy.sh",no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding %s\n' "$app_dir" "$pubkey" >> /root/.ssh/authorized_keys
fi

# 5. Restart on the new path and confirm.
systemctl restart "$service"
url="$(grep -o -E 'HEALTH_URL:-[^}]*' "$app_dir/deploy.sh" | head -n 1 | sed 's/HEALTH_URL:-//')"
for _ in $(seq 1 40); do
  if curl -fsS -o /dev/null --max-time 5 "$url"; then echo "OK: $service is healthy on $url via $app_dir/current.jar"; exit 0; fi
  sleep 3
done
echo "service did not become healthy on $url; restoring the original unit" >&2
rm -f "$dropin/current-jar.conf"; rmdir "$dropin" 2>/dev/null || true
systemctl daemon-reload; systemctl restart "$service" || true
exit 1
