#!/bin/sh
# Builds the signed release APK and AAB for a release tag, checks them, and
# collects the files a GitHub Release ships into OUT_DIR.
#
#   scripts/build-release.sh v0.1.0 dist/
#
# Needs the upload key in ARIES_UPLOAD_KEYSTORE, ARIES_UPLOAD_STORE_PASSWORD,
# ARIES_UPLOAD_KEY_ALIAS, ARIES_UPLOAD_KEY_PASSWORD (or a properties file in
# ARIES_UPLOAD_KEYSTORE_PROPERTIES), ANDROID_HOME, cargo-ndk and cargo-about.
# Extra arguments go to Gradle (e.g. -Paries.abis=x86_64 for a quick local run).
set -eu

tag=${1:?usage: build-release.sh TAG OUT_DIR [gradle args...]}
out=${2:?usage: build-release.sh TAG OUT_DIR [gradle args...]}
shift 2
root=$(cd "$(dirname "$0")/.." && pwd)

if [ -z "${ARIES_UPLOAD_KEYSTORE:-}${ARIES_UPLOAD_KEYSTORE_PROPERTIES:-}" ]; then
  echo "build-release: no upload key (ARIES_UPLOAD_KEYSTORE or ARIES_UPLOAD_KEYSTORE_PROPERTIES)" >&2
  exit 1
fi

eval "$(sh "$root/scripts/release-version.sh" "$tag")"
echo "build-release: $tag -> versionName $name, versionCode $code, prerelease $prerelease"

(cd "$root/android" && ./gradlew --no-daemon \
  -Paries.versionName="$name" -Paries.versionCode="$code" "$@" \
  :app:assembleRelease :app:bundleRelease)

build_tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n1)
apk="$root/android/app/build/outputs/apk/release/app-release.apk"
aab="$root/android/app/build/outputs/bundle/release/app-release.aab"
[ -f "$apk" ] || { echo "build-release: no signed APK (unsigned build?)" >&2; exit 1; }

"$build_tools/apksigner" verify --print-certs "$apk"
"$JAVA_HOME/bin/jarsigner" -verify "$aab" >/dev/null || { echo "build-release: AAB is not signed" >&2; exit 1; }

manifest=$(mktemp)
"$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" manifest print "$apk" >"$manifest"
python3 -I "$root/scripts/check-play-policy.py" "$manifest"
rm -f "$manifest"

mkdir -p "$out"
cp "$apk" "$out/aries-$name.apk"
cp "$aab" "$out/aries-$name.aab"
cp "$root/android/app/build/generated/notices/licenses/rust-crates.txt" "$out/third-party-notices-rust.txt"
cp "$root/android/app/build/reports/licensee/androidRelease/artifacts.json" "$out/third-party-notices-android.json"
(cd "$out" && sha256sum "aries-$name.apk" >SHA256SUMS)

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  { echo "name=$name"; echo "code=$code"; echo "prerelease=$prerelease"; } >>"$GITHUB_OUTPUT"
fi
echo "build-release: wrote $out"
