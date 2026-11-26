set -euo pipefail
out="${1:?Usage: build-public-site.sh OUT_DIR}"

rm -rf "$out"
mkdir -p "$out"
cp infra/cloudflare/_headers "$out/_headers"
if [[ -d public-site ]]; then
  cp -R public-site/. "$out/"
  rm -f "$out/README.md"
fi
if [[ ! -f "$out/index.html" ]]; then
  cp infra/cloudflare/placeholder.html "$out/index.html"
fi

if [[ -n "${PORTAL_URL:-}" ]]; then
  grep -rl "__PORTAL_URL__" "$out" | xargs -r sed -i "s#__PORTAL_URL__#${PORTAL_URL}#g"
else
  echo "::warning::PORTAL_URL is not known, so the public site's links to the portal point nowhere"
fi
echo "The public site: $(find "$out" -type f | wc -l) files"