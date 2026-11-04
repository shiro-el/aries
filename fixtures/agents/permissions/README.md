# Permission menu fixtures

Representative plain-text screens for Claude Code Write and Codex command/file
approval menus. Reconstructed from the menu contracts observed in the local
Claude Code/Codex integration research (Codex 0.165.0); neutral paths and content.
These are synthetic fixtures, not full captured sessions.

The core API test reads them through a real isolated herdr-core Terminal and
checks actual PTY bytes. Selection glyphs are deliberately included. Codex
persistent approvals advertise different shortcuts: `p` for commands and `a`
for files. Unknown or incomplete menu shapes must keep the Terminal fallback.
