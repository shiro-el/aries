# Offline Mermaid renderer

Pele **1.13.0**, MIT; no runtime dependencies.
Upstream: https://github.com/obsidianmd/pele
Package: https://registry.npmjs.org/pele/-/pele-1.13.0.tgz
Vendored file: package/dist/pele.global.js, copied without modification.
SHA-256: 0b0a46c57a5a6971670c09f5ec4066705bd78505f90fd11adf6260325efa0910

Full upstream license (MIT plus its Lucide ISC notice): ../licenses/pele.txt.
The app uses render(source, options), then enableZoom in the full-screen viewer.
renderer.js is the Aries adapter; invalid or over-budget input returns null so
Compose can display the original code. Diagram source is passed as JSON data.
No CDN, network resources, injected script, native JavaScript bridge, or remote
images are enabled. HTTP(S)/mailto link gestures use the app URI handler.

Run the adapter regression check from the repository root:
node --test android/app/src/test/js/mermaid.test.cjs
