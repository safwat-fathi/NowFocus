#!/bin/sh
# Prints an nginx snippet that takes the client IP from Cloudflare's CF-Connecting-IP header, but ONLY when the
# TCP peer is a Cloudflare address. A request that hits the origin directly keeps its real peer IP, so a spoofed
# header is ignored. ONLY for a box whose nginx has no real_ip_header yet (`grep -rn real_ip /etc/nginx`): nginx
# rejects a second one. Production already has it in nginx.conf. Usage (on the VPS):
#   ./cloudflare-realip.sh | sudo tee /etc/nginx/conf.d/cloudflare-realip.conf && sudo nginx -t && sudo systemctl reload nginx
# Re-run now and then; Cloudflare's ranges change rarely. Put the file in http{} (conf.d is included there).
# The vhost that relies on it is deploy/api.nowfocus.online.conf.
set -eu
echo "# generated $(date -u +%Y-%m-%dT%H:%MZ) from https://www.cloudflare.com/ips"
for f in ips-v4 ips-v6; do
  curl -fsS "https://www.cloudflare.com/$f" | sed -e '$a\' | while read -r cidr; do
    [ -n "$cidr" ] && echo "set_real_ip_from $cidr;"
  done
done
echo "real_ip_header CF-Connecting-IP;"
