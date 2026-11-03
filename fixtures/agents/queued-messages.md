# Mid-turn input fixtures (ticket 145)

These fixtures reproduce observed record schemas with invented message text,
identifiers, timestamps and paths. No private transcript text or image is included.

- `claude-code/mid-turn-messages.jsonl`: Claude Code 2.1.317 queue enqueue,
  remove (`absorbed_mid_turn`, `commandUuid`, `deliveryId`), human-origin
  `queued_command` attachment (`prompt`, `source_uuid`, `delivery_id`,
  `humanTurn`), followed by a user record containing only a tool result.
- `claude-code/repeated-queued-messages.jsonl`: two identical inputs; only
  the first has been removed and delivered. Text equality cannot deduplicate
  user submissions.
- `claude-code/queued-media.jsonl`: prompt content blocks, multiline pasted
  text, base64 image, image-only prompt without origin (older shape), duplicate
  attachment and internal peer notification. The installed Claude attachment
  builder confirms that pasted images are embedded in `prompt` as content
  blocks; queue-operation content is text-only or absent. Image bytes are
  synthetic, suitable for parser tests rather than image-decoder tests.
- `claude-code/queued-rewritten-prompt.jsonl`: `promptRewritten` delivery
  followed by removal using the original enqueue content.
- `codex/sessions/mid-turn-steer.rollout.jsonl`: Codex 0.165 rollout shape
  observed for steering during an active turn: `response_item` user content
  and `event_msg.item_completed.UserMessage` between tool call and output.
  Its shape is the same as ordinary delivered input, including `local_image`
  references and the corresponding response item's inline image. Rollouts
  inspected did not expose an undelivered input queue state.

The delivery record's outer timestamp determines conversation placement; the
queued command's inner timestamp can still be the earlier submission time.
