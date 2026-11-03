// Regression: a malformed/unsupported diagram must return control to the native
// source-code fallback instead of throwing and leaving an empty WebView.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const assets = path.resolve(__dirname, '../../main/assets/mermaid');
const sandbox = vm.createContext({});
vm.runInContext(fs.readFileSync(path.join(assets, 'pele-1.13.0.js'), 'utf8'), sandbox);
vm.runInContext(fs.readFileSync(path.join(assets, 'renderer.js'), 'utf8'), sandbox);
test('bad diagrams select source fallback and do not poison the next render', () => {
    for (const source of ['flowchart LR\nA[unterminated', 'unsupportedDiagram\nx --> y']) {
        assert.equal(sandbox.ariesMermaidSvg(source, 400), null);
    }
    assert.match(sandbox.ariesMermaidSvg('flowchart LR\nA --> B', 400).svg, /<svg/);
});
