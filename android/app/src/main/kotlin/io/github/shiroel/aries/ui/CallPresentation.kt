package io.github.shiroel.aries.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.shiroel.aries.R
import io.github.shiroel.aries.core.CallAnswerState
import io.github.shiroel.aries.core.CallReplyOutcome

@StringRes
internal fun CallAnswerState.labelResource(): Int = when (this) {
    CallAnswerState.CLAIMED -> R.string.call_answer_claimed
    CallAnswerState.DELIVERED -> R.string.call_answer_delivered
    CallAnswerState.FAILED -> R.string.call_answer_failed
    CallAnswerState.UNCONFIRMED -> R.string.call_answer_unconfirmed
}

@StringRes
internal fun CallReplyOutcome.labelResource(): Int = when (this) {
    CallReplyOutcome.ANSWERED -> R.string.call_reply_answered
    CallReplyOutcome.NO_CALL -> R.string.call_reply_no_call
    CallReplyOutcome.NO_CHOICE -> R.string.call_reply_no_choice
    CallReplyOutcome.NOT_ANSWERABLE -> R.string.call_unconfirmed
    CallReplyOutcome.NO_BEACON -> R.string.call_reply_no_beacon
    else -> R.string.call_reply_unknown
}

@Composable
internal fun replyNoteText(note: ReplyNote): String = when (note) {
    is ReplyNote.NotSent -> stringResource(R.string.call_reply_not_sent, note.detail)
    is ReplyNote.Outcome -> stringResource(note.outcome.labelResource())
}
