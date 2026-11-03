`herdr-app` documents (the value of `doc.get {name: "herdr-app"}`), shaped
after herdr-app f6fe216 (`src/mirror.rs`, `src/layout.rs`, Apache-2.0).
`$name` stands for the address of the Terminal a test starts with that name.

- `normal.json`: two Spaces; rows/columns/stack/floating panes, a mod block
  (not a Pane), an empty Tab, and a Pane whose Terminal is gone (`t999`).
- `broken.json`: known shape, wrong types (`tabs` is not a list).
- `unknown-format.json`: an object without `spaces`, as a future format might be.
