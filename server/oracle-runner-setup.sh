#!/bin/bash
# GovJob Radar — turns a fresh Oracle Cloud Ubuntu server (in India) into a GitHub Actions
# runner for this repo, so the nightly crawl runs from an Indian IP address.
#
# HOW TO USE: Oracle Cloud > Create compute instance > Show advanced options > Management >
# Initialization script > "Paste cloud-init script" > paste this whole file, after replacing
# PASTE_TOKEN_HERE with the token from GitHub (repo > Settings > Actions > Runners >
# New self-hosted runner > Linux > ARM64 — the value after "--token"). The token works for 1 hour.
#
# Everything runs by itself at first boot (about 5–10 minutes). Log: /var/log/govjob-runner-setup.log

TOKEN="PASTE_TOKEN_HERE"
REPO_URL="https://github.com/raviiist/govjob-radar"
RUNNER_NAME="oracle-india"

exec > /var/log/govjob-runner-setup.log 2>&1
set -eux
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y curl jq git tar python3 python3-venv python3-pip python3-dev build-essential \
    libxml2-dev libxslt1-dev libicu-dev ca-certificates

# 2 GB swap: keeps the small free server stable while installing Python packages
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# Indian time on the server (logs read naturally); the workflow schedule itself stays in UTC
timedatectl set-timezone Asia/Kolkata || true

id runner >/dev/null 2>&1 || useradd -m -s /bin/bash runner

case "$(uname -m)" in
  aarch64) RA=arm64 ;;
  x86_64)  RA=x64 ;;
  *) echo "unsupported CPU $(uname -m)"; exit 1 ;;
esac
VER=$(curl -fsSL https://api.github.com/repos/actions/runner/releases/latest | jq -r .tag_name | sed 's/^v//')

mkdir -p /home/runner/actions-runner
cd /home/runner/actions-runner
curl -fsSL -o runner.tgz "https://github.com/actions/runner/releases/download/v${VER}/actions-runner-linux-${RA}-${VER}.tar.gz"
tar xzf runner.tgz && rm runner.tgz
./bin/installdependencies.sh || true
chown -R runner:runner /home/runner/actions-runner

sudo -u runner ./config.sh --unattended --url "$REPO_URL" --token "$TOKEN" \
    --name "$RUNNER_NAME" --labels india --replace
./svc.sh install runner
./svc.sh start

echo "GovJob Radar runner installed (actions runner ${VER}, ${RA})"
