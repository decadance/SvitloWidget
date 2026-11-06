#!/usr/bin/env bash
# Publishes a signed GitHub-build APK to the update server on the Mac mini
# (same server and tunnel as Sleep Hub: https://beta.decadance.me/svitlograf/).
#
#   scripts/publish-update.sh <app-github-release.apk> <notes.json>
#
# notes.json: {"uk": "...", "en": "..."} — what's new, shown in the app's update card.
# Installed apps pick it up within ~6 hours or when opened.
#
# SVITLO_UPDATES_HOST=local  — run on the Mac mini itself (copies instead of scp).
set -euo pipefail

apk=${1:?usage: publish-update.sh <apk> <notes.json>}
notes=${2:?usage: publish-update.sh <apk> <notes.json>}
host=${SVITLO_UPDATES_HOST:-decadance@192.168.1.251}
key=${SVITLO_UPDATES_KEY:-$HOME/.ssh/id_ed25519_macmini}
remote_dir='services/updates/svitlograf'
keep=3
# Release key fingerprint: an APK signed with anything else can't update installed apps.
expected_cert=70e55de55644bdea02fe57a7841bdbdabfbce1dac05b3535c9dcf895e4470d32

root=$(cd "$(dirname "$0")/.." && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $sdk ]]; then
  for d in "$HOME/Library/Android/sdk" /d/Android/Sdk "$HOME/AppData/Local/Android/Sdk" "$HOME/Android/Sdk"; do
    [[ -d $d/build-tools ]] && { sdk=$d; break; }
  done
fi
[[ -d $sdk/build-tools ]] || { echo "Android SDK not found; set ANDROID_HOME" >&2; exit 1; }
bt=$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)
apksigner=$bt/apksigner; [[ -f $apksigner ]] || apksigner=$bt/apksigner.bat
py=$(command -v python3 || command -v python) || { echo "Python not found" >&2; exit 1; }
badging=$("$bt/aapt2" dump badging "$apk" | tr -d '\r')
code=$(sed -n "s/^package:.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging")
name=$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" <<<"$badging")
[[ -n $code && -n $name ]] || { echo "Cannot read the version from $apk" >&2; exit 1; }
grep -q "REQUEST_INSTALL_PACKAGES" <<<"$badging" || { echo "Not a GitHub build (no self-update permission): $apk" >&2; exit 1; }
cert=$("$apksigner" verify --print-certs "$apk" | tr -d '\r' | sed -n 's/.*SHA-256 digest: //p' | head -1)
[[ $cert == "$expected_cert" ]] || { echo "Signed with the wrong key ($cert)" >&2; exit 1; }

grep -q CONTACT_EMAIL "$root/web/privacy.html" && { echo "Put your contact e-mail into web/privacy.html first" >&2; exit 1; }

file="svitlograf-$name.apk"
bytes=$(wc -c <"$apk" | tr -d ' ')
if command -v sha256sum >/dev/null; then sha=$(sha256sum "$apk" | cut -d' ' -f1); else sha=$(shasum -a 256 "$apk" | cut -d' ' -f1); fi

manifest=$(mktemp)
"$py" - "$code" "$name" "$file" "$bytes" "$sha" "$notes" >"$manifest" <<'PY'
import datetime, io, json, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", newline="\n")
code, name, file, size, sha, notes = sys.argv[1:]
n = json.load(open(notes, encoding="utf-8"))
assert isinstance(n, dict) and "en" in n, "notes.json needs at least an \"en\" entry"
print(json.dumps({
    "versionCode": int(code),
    "versionName": name,
    "file": file,
    "bytes": int(size),
    "sha256": sha,
    "notes": n,
    "publishedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
}, ensure_ascii=False, indent=2))
PY

echo "Publishing $file ($((bytes / 1048576)) MB, versionCode $code)…"
# APK first, then the manifest that points to it; APKs beyond the last $keep go.
finish="cd ~/$remote_dir && chmod 644 *.part && mv $file.part $file && mv latest.json.part latest.json && mv privacy.html.part privacy.html \
  && ls -t svitlograf-*.apk | tail -n +$((keep + 1)) | xargs rm -f && ls -l"
if [[ $host == local ]]; then
  mkdir -p ~/"$remote_dir"
  cp "$apk" ~/"$remote_dir/$file.part"
  cp "$manifest" ~/"$remote_dir/latest.json.part"
  cp "$root/web/privacy.html" ~/"$remote_dir/privacy.html.part"
  bash -c "$finish"
else
  ssh_() { ssh -i "$key" -o BatchMode=yes "$host" "$@"; }
  ssh_ "mkdir -p ~/$remote_dir"
  scp -q -i "$key" "$apk" "$host:$remote_dir/$file.part"
  scp -q -i "$key" "$manifest" "$host:$remote_dir/latest.json.part"
  scp -q -i "$key" "$root/web/privacy.html" "$host:$remote_dir/privacy.html.part"
  ssh_ "$finish"
fi
rm -f "$manifest"
echo "Published $name ($code): https://beta.decadance.me/svitlograf/latest.json"
