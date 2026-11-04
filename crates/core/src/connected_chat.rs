//! Short Chat requests over the already authenticated core connection.
use crate::{ChatSnapshot, CoreError, HostConnection, chat, error::protocol};
use aries_protocol::answer::{Answer, AnswerReply};

#[uniffi::export]
impl HostConnection {
    /// Reads one live Beacon snapshot without another SSH session or a follow stream.
    pub async fn read_chat(&self, terminal: String) -> Result<ChatSnapshot, CoreError> {
        let output = self
            .machine_exec(
                vec![
                    "/bin/sh".into(),
                    "-c".into(),
                    chat::snapshot_command(&terminal, None, false),
                ],
                None,
                20,
            )
            .await?;
        if output.timed_out || output.truncated || output.code != Some(0) {
            return Err(protocol("incomplete Chat snapshot"));
        }
        let text =
            std::str::from_utf8(&output.stdout).map_err(|_| protocol("invalid Chat output"))?;
        let mut model = chat::ChatModel::new(None);
        for line in text.lines() {
            model.apply_text(line);
        }
        Ok(model.snapshot())
    }

    /// Answers once on this connection. The Beacon still revalidates the live menu.
    /// An incomplete reply is uncertain and must never be retried automatically.
    pub async fn answer_question(
        &self,
        terminal: String,
        question_id: String,
        picks: Vec<Vec<u32>>,
    ) -> Result<(), CoreError> {
        let answer = Answer {
            question: question_id,
            picks: picks
                .into_iter()
                .map(|p| p.into_iter().map(|n| n as usize).collect())
                .collect(),
            free_text: vec![],
        };
        let output = self
            .machine_exec(
                vec![
                    "/bin/sh".into(),
                    "-c".into(),
                    chat::answer_command(&terminal, &answer),
                ],
                None,
                20,
            )
            .await?;
        if output.truncated || output.timed_out {
            return Err(CoreError::AnswerRefused {
                reason: crate::AnswerProblem::Failed,
                detail: "incomplete answer reply".into(),
            });
        }
        let reply = String::from_utf8_lossy(&output.stdout)
            .lines()
            .filter_map(|line| AnswerReply::parse(line).ok())
            .next_back()
            .ok_or_else(|| CoreError::AnswerRefused {
                reason: crate::AnswerProblem::Failed,
                detail: "no answer reply".into(),
            })?;
        chat::decode_answer(reply)
    }
}
