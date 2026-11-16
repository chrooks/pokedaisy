#!/usr/bin/env bash
# Install the CI debug APK of the pushed HEAD on a device over adb, keeping its data (`make thor-deploy`).
#
# Needs CI to sign debug builds with the repo's fixed debug key (ci.yml's "Debug signing key"): then
# `adb install -r` updates the app in place. A build signed by another key is refused, never uninstalled.
#
#   THOR_ADB=<host:port>   adb connect target (Tailscale / Wi-Fi adb); unset = the one device adb already sees
#   REPO=<owner/name>      where CI ran (default: the origin remote's)
#   SHA=<commit>           which build (default: HEAD, which must be pushed)
set -euo pipefail

PKG=com.pokedaisy.app
ARTIFACT=pokedaisy-debug-apk
ADB=${ADB:-adb}
REPO=${REPO:-$(gh repo view "$(git remote get-url origin)" --json nameWithOwner -q .nameWithOwner)}
SHA=${SHA:-$(git rev-parse HEAD)}
WAIT_STEP=15        # seconds between "is the game on screen?" checks
WAIT_MAX=3600       # give up after an hour of play

say() { printf '>> %s\n' "$*"; }
die() { printf '!! %s\n' "$*" >&2; exit 1; }

git branch -r --contains "$SHA" | grep -q . || die "$SHA isn't pushed: CI never saw it"

# 1. The CI run for this commit (a PR's run carries the APK artifact).
say "CI for ${SHA:0:7} on $REPO"
run_id=""
for _ in $(seq 1 40); do
  run_id=$(gh run list -R "$REPO" --workflow CI --commit "$SHA" --json databaseId -q '.[0].databaseId' 2>/dev/null || true)
  [ -n "$run_id" ] && break
  sleep 15
done
[ -n "$run_id" ] || die "no CI run for $SHA after 10 minutes"
gh run watch "$run_id" -R "$REPO" --exit-status --interval 30 >/dev/null \
  || die "CI run $run_id failed: gh run view $run_id -R $REPO --log-failed"
say "CI run $run_id passed"

# 2. The APK.
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
gh run download "$run_id" -R "$REPO" -n "$ARTIFACT" -D "$dir"
apk="$dir/app-debug.apk"
[ -f "$apk" ] || die "artifact $ARTIFACT had no app-debug.apk"

# 3. The device.
if [ -n "${THOR_ADB:-}" ]; then
  "$ADB" connect "$THOR_ADB" >/dev/null
  export ANDROID_SERIAL="$THOR_ADB"
fi
"$ADB" get-state >/dev/null 2>&1 || die "no device: set THOR_ADB=<host:port> or plug it in"
model=$("$ADB" shell getprop ro.product.model | tr -d '\r')
say "device: $model"

# 4. Never while PokeDaisy is on screen: installing kills the app, and a stop mid-save corrupts the save.
on_screen() {
  "$ADB" shell dumpsys activity activities 2>/dev/null \
    | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity:' | grep -q "$PKG/"
}
waited=0
while on_screen; do
  [ "$waited" -ge "$WAIT_MAX" ] && die "PokeDaisy stayed on screen for an hour: leave it, then rerun"
  [ "$waited" -eq 0 ] && say "PokeDaisy is on screen: waiting until it isn't (checking every ${WAIT_STEP}s)"
  sleep "$WAIT_STEP"; waited=$((waited + WAIT_STEP))
done

# 5. Install in place.
before=$("$ADB" shell dumpsys package "$PKG" | grep -m1 lastUpdateTime | tr -d '\r' || true)
out=$("$ADB" install -r "$apk" 2>&1) || {
  printf '%s\n' "$out" >&2
  case "$out" in
    *UPDATE_INCOMPATIBLE*) die "the installed build has another signing key: do the one-time backup / uninstall / restore swap" ;;
    *) die "adb install failed" ;;
  esac
}
info=$("$ADB" shell dumpsys package "$PKG" | grep -E -m3 'versionName|versionCode|lastUpdateTime' | tr -d '\r' | sed 's/^ */   /')
say "installed ${SHA:0:7}:"
printf '%s\n' "$info"
[ -n "$before" ] && say "was:${before#*lastUpdateTime}"
