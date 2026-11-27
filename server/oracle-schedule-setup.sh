#!/bin/bash
# GovJob Radar — run ONCE on the Oracle server (as root):  sudo bash oracle-schedule-setup.sh YOUR_TOKEN
#
# 1. Makes the server start the GitHub jobs on time (GitHub's own timer is often hours late):
#      nightly crawl every day at 02:11 IST, publish every 30 minutes.
#    YOUR_TOKEN = a GitHub fine-grained token for raviiist/govjob-radar with "Actions: Read and write".
# 2. Installs the system libraries the headless browser needs (to read JavaScript websites).
set -euo pipefail
TOKEN="${1:-}"
[ -n "$TOKEN" ] || { echo "Usage: sudo bash oracle-schedule-setup.sh YOUR_GITHUB_TOKEN"; exit 1; }
REPO="raviiist/govjob-radar"

mkdir -p /etc/govjob
printf '%s' "$TOKEN" > /etc/govjob/token
chmod 600 /etc/govjob/token

cat > /usr/local/bin/govjob-dispatch <<'SCRIPT'
#!/bin/bash
# govjob-dispatch <workflow file>  — asks GitHub to start a workflow now.
WF="$1"
CODE=$(curl -s -o /tmp/govjob-dispatch.out -w "%{http_code}" -X POST \
  -H "Authorization: Bearer $(cat /etc/govjob/token)" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/raviiist/govjob-radar/actions/workflows/${WF}/dispatches" -d '{"ref":"main"}')
echo "$(date '+%F %T') ${WF} -> HTTP ${CODE} $( [ "$CODE" = 204 ] || head -c 300 /tmp/govjob-dispatch.out)" >> /var/log/govjob-dispatch.log
SCRIPT
chmod 755 /usr/local/bin/govjob-dispatch

timedatectl set-timezone Asia/Kolkata || true
cat > /etc/cron.d/govjob <<'CRON'
# GovJob Radar schedule (server time = IST)
SHELL=/bin/bash
11 2 * * *    root /usr/local/bin/govjob-dispatch nightly.yml
*/30 * * * *  root /usr/local/bin/govjob-dispatch publish.yml
CRON
chmod 644 /etc/cron.d/govjob
systemctl restart cron || true

# Test the token right away
/usr/local/bin/govjob-dispatch publish.yml
tail -1 /var/log/govjob-dispatch.log

# System libraries for the headless browser (Chromium)
apt-get update -qq
python3 -m venv /opt/pwdeps && /opt/pwdeps/bin/pip install -q playwright
/opt/pwdeps/bin/python -m playwright install-deps chromium
echo "DONE. If the line above says 'HTTP 204', the schedule works."
