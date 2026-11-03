# Codex history_base fixture

Minimized from a real Codex 0.165.0 backtrack captured on 2026-10-29.
The replacement keeps its thread ID but uses a new rollout ID in its filename.
Its header starts at ordinal 543 and inherits the old rollout only through
ordinal 542; the old rollout also contains discarded messages after that.

Unrelated records and private metadata were removed. Message text, item IDs,
thread/rollout IDs and cwd were replaced with neutral fixture values. Record
shapes, timestamps and ordinals are preserved; end_byte_offset was recalculated
after scrubbing. No legacy application code or fixtures were copied.
