// Scripted OpenAI-compatible chat-completions server, so tests can run the
// real Pi without a model credential. The reply depends on a keyword in the
// last user message. Usage: node fake-llm.mjs [PORT]; prints
// "fake-llm ready on <port>" once listening (PORT 0 or none: any free port).
import http from "node:http";

let n = 0;

function lastUserText(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.role !== "user") continue;
    if (typeof m.content === "string") return m.content;
    return (m.content || []).filter((c) => c.type === "text").map((c) => c.text).join(" ");
  }
  return "";
}

function plan(body) {
  const msgs = body.messages || [];
  const last = msgs[msgs.length - 1] || {};
  const text = lastUserText(msgs).toLowerCase();
  if (last.role === "tool") {
    const res = typeof last.content === "string" ? last.content : JSON.stringify(last.content);
    return { text: `Tool finished. Result starts: ${res.slice(0, 60).replace(/\n/g, " ")}` };
  }
  if (text.includes("fail500")) return { status: 500 };
  if (text.includes("think")) return { reasoning: "Let me think step by step about this.", text: "Thought about it: 42." };
  if (text.includes("dobash")) return { tool: { name: "bash", args: { command: "echo hello-from-bash" } } };
  if (text.includes("dowrite")) return { tool: { name: "write", args: { path: "notes.txt", content: "line one\nline two\n" } } };
  if (text.includes("doedit")) return { tool: { name: "edit", args: { path: "notes.txt", edits: [{ oldText: "line two", newText: "line TWO edited" }] } } };
  return { text: `Echo: ${text.slice(0, 80)}` };
}

const server = http.createServer(async (req, res) => {
  let raw = "";
  for await (const c of req) raw += c;
  if (req.method === "GET") {
    res.writeHead(200, { "content-type": "application/json" });
    res.end('{"data":[{"id":"fake-1"}]}');
    return;
  }
  let body = {};
  try {
    body = JSON.parse(raw || "{}");
  } catch {}
  const p = plan(body);
  if (p.status) {
    res.writeHead(p.status, { "content-type": "application/json" });
    res.end('{"error":{"message":"fake upstream failure","type":"server_error"}}');
    return;
  }
  res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
  const id = `chatcmpl-${++n}`;
  const chunk = (delta, finish = null) =>
    res.write(`data: ${JSON.stringify({ id, object: "chat.completion.chunk", created: 0, model: "fake-1", choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`);
  chunk({ role: "assistant" });
  if (p.reasoning) chunk({ reasoning_content: p.reasoning });
  if (p.text) chunk({ content: p.text });
  if (p.tool) {
    chunk({ tool_calls: [{ index: 0, id: `call_${n}`, type: "function", function: { name: p.tool.name, arguments: "" } }] });
    chunk({ tool_calls: [{ index: 0, function: { arguments: JSON.stringify(p.tool.args) } }] });
    chunk({}, "tool_calls");
  } else chunk({}, "stop");
  res.write(`data: ${JSON.stringify({ id, object: "chat.completion.chunk", choices: [], usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 } })}\n\n`);
  res.write("data: [DONE]\n\n");
  res.end();
});

server.listen(Number(process.argv[2] || 0), "127.0.0.1", () => {
  console.log(`fake-llm ready on ${server.address().port}`);
});
