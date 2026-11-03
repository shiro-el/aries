// Stands in for OpenCode around the Beacon's OpenCode plugin, for tests:
// loads the plugin like OpenCode does and feeds it the steps of a JSON-lines
// script, then stays alive like the TUI. Reads its input raw, key by key,
// like OpenCode's TUI.
//
// Usage: node plugin-harness.mjs PLUGIN SCRIPT
// With HARNESS_INPUT set, every chunk of input is appended there as a line
// of hex bytes ("32", "6e 65 78 74 0d"), so tests see the keys that arrived.
// Steps (one JSON object per line):
//   {"hook": "chat.message", "input": {...}}   call a hook
//   {"event": "<type>", "props": {...}}        deliver an event (the shape
//                                               plugin-and-hc-events.jsonl records)
//   {"wait": "key"}                             wait for any input
//   {"wait": "line"}                            wait for input up to an enter
//   {"sleep": MS}
import fs from "node:fs";
import { pathToFileURL } from "node:url";

const [pluginPath, scriptPath] = process.argv.slice(2);
const mod = await import(pathToFileURL(pluginPath).href);
const hooks = {};
for (const make of Object.values(mod)) {
  if (typeof make === "function") Object.assign(hooks, await make({}));
}

const chunks = [];
let wake = null;
if (process.stdin.isTTY) process.stdin.setRawMode(true);
process.stdin.on("data", (chunk) => {
  if (process.env.HARNESS_INPUT) {
    const hex = [...chunk].map((b) => b.toString(16).padStart(2, "0")).join(" ");
    fs.appendFileSync(process.env.HARNESS_INPUT, hex + "\n");
  }
  chunks.push(chunk);
  wake?.();
});
async function nextChunk() {
  while (chunks.length === 0) await new Promise((r) => (wake = r));
  return chunks.shift();
}

const steps = fs.readFileSync(scriptPath, "utf8").split("\n").filter((l) => l.trim());
for (const line of steps) {
  const step = JSON.parse(line);
  if (step.hook) await hooks[step.hook]?.(step.input ?? {}, {});
  else if (step.event) await hooks.event?.({ event: { type: step.event, properties: step.props ?? {} } });
  else if (step.say) process.stdout.write(step.say + "\n");
  else if (step.wait === "key") await nextChunk();
  else if (step.wait) while (!/[\r\n]/.test((await nextChunk()).toString())) {}
  else if (step.sleep) await new Promise((r) => setTimeout(r, step.sleep));
}
process.stdout.write("harness: done\n");
setInterval(() => {}, 1 << 30);
