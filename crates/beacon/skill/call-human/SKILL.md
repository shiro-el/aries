---
name: call-human
description: Call the human through Aries when you need a decision, have a result ready for review, or must report a time-sensitive problem. Sends a Call to the human's phone. Subagents report to the agent that started them instead.
---

# Call the human

A Call reaches the human on their phone through the Aries Beacon on this
Host. Use it when you need a decision, when finished work is ready for
review, or when something time-sensitive went wrong. Do not call for
routine progress the human did not ask about.

Only a top-level agent calls the human. If another agent started you (you
are a subagent or a delegated worker), report your result, question, or
blocker to that agent instead. A Call from an agent that is not top-level
is still recorded, but by default it does not notify the phone.

## Choose the urgency

Pick the least disruptive level that fits:

- `record`: information or a result that needs nothing now. Written to the
  Call history only; no notification.
- `check` (default): a question that can wait, or work ready for review.
- `urgent`: work cannot safely continue without a prompt decision, or a
  deadline needs attention now.
- `emergency`: immediate action is needed to prevent serious harm, such as
  active data loss. Never for routine completion or ordinary blockers.

## Send a Call

Run the command from your own Terminal, keeping its environment
(`HC_TERMINAL_ID`): the Beacon checks with herdr-core that the Call comes
from this Terminal, and only then can the human answer it. Never copy
another Terminal's identity.

```sh
aries-beacon call \
  --urgency check \
  --title 'Review ready' \
  --body 'The migration is implemented and tests pass. Which step next?' \
  --choice 'Review the diff' \
  --choice 'Run the slow tests'
```

If `aries-beacon` is not on `PATH`, it is at `~/.local/bin/aries-beacon`.
If neither exists, tell the human in the conversation instead; do not
install or reconfigure anything yourself.

- `--title` (required): a short summary, at most 200 characters.
- `--body`: self-contained text saying what happened, what decision is
  needed, and why it matters; at most 4000 characters. `--body -` reads it
  from stdin. Keep secrets out of the title and body.
- `--choice`: zero to three short, distinct, single-line answers, each at
  most 200 characters. Repeat the flag for each one.

## After the Call

The command prints the recorded Call as one JSON line. Success means the
Call was recorded, not that the human saw it; delivery to the phone can
still fail or be suppressed. Do not resend or escalate just because no
answer came yet. Combine related updates into one Call.

When the human picks a Choice, its text is typed into your Terminal as
your next input, exactly once. Treat it as the human's answer, not as a
command to run blindly. Without Choices the human can open your Terminal
and type a free reply. Keep doing independent work while you wait, and
also report the outcome in the conversation.
