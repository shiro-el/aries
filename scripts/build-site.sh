#!/bin/sh
# Stage the GitHub Pages site in OUT (default _site): the page in site/ plus the
# screenshots, logo and font it shares with the README and the app, so each
# file has a single copy in the repository.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
out=${1:-"$root/_site"}
rm -rf "$out"
mkdir -p "$out/screenshots" "$out/brand" "$out/fonts"
cp -R "$root/site/." "$out/"
cp "$root"/assets/screenshots/*.webp "$out/screenshots/"
cp "$root/assets/aries/aries-icon.svg" "$root/assets/aries/aries-icon-small.svg" \
   "$root/assets/aries/png/aries-icon-flat-32.png" "$root/assets/aries/png/aries-icon-small-192.png" "$out/brand/"
cp "$root/android/app/src/main/res/font/jetbrains_mono.ttf" "$out/fonts/JetBrainsMono.ttf"
cp "$root/android/app/src/main/assets/licenses/JetBrainsMono-OFL.txt" "$out/fonts/OFL.txt"
echo "site staged in $out"
