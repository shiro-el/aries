//! GitHub stays on the Host: no tokens, shell commands, or implicit current repository.
use crate::{
    CoreError, HostConnection,
    error::{protocol, storage},
    on_runtime,
};
use serde::Deserialize;
use serde_json::json;
use sha2::{Digest, Sha256};

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum GithubKind {
    Issue,
    PullRequest,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum GithubState {
    Open,
    Closed,
    All,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GithubItem {
    pub number: u32,
    pub title: String,
    pub state: String,
    pub author: String,
    pub created_at: String,
    pub body: String,
    pub kind: GithubKind,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GithubPage {
    pub items: Vec<GithubItem>,
    pub has_more: bool,
    pub limited: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GithubComment {
    pub id: u64,
    pub body: String,
    pub author: String,
    pub created_at: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GithubDetail {
    pub item: GithubItem,
    pub comments: Vec<GithubComment>,
    pub head_branch: Option<String>,
    pub base_branch: Option<String>,
}

#[derive(Deserialize)]
struct Author {
    login: String,
}

#[derive(Deserialize)]
struct RawItem {
    number: u32,
    title: String,
    state: String,
    user: Option<Author>,
    created_at: String,
    body: Option<String>,
    pull_request: Option<serde_json::Value>,
    #[serde(default)]
    draft: bool,
}

impl RawItem {
    fn into_item(self) -> Result<GithubItem, CoreError> {
        if self.number == 0 || !matches!(self.state.as_str(), "open" | "closed") {
            return Err(protocol("invalid GitHub item"));
        }
        let merged = self
            .pull_request
            .as_ref()
            .is_some_and(|p| p["merged_at"].is_string());
        Ok(GithubItem {
            number: self.number,
            title: self.title,
            state: if merged {
                "merged".into()
            } else if self.state == "open" && self.draft && self.pull_request.is_some() {
                "draft".into()
            } else {
                self.state
            },
            author: self.user.map_or_else(String::new, |u| u.login),
            created_at: self.created_at,
            body: self.body.unwrap_or_default(),
            kind: if self.pull_request.is_some() {
                GithubKind::PullRequest
            } else {
                GithubKind::Issue
            },
        })
    }
}

impl GithubRepository {
    fn validate(&self) -> Result<(), CoreError> {
        let parts: Vec<_> = self.name_with_owner.split('/').collect();
        if parts.len() != 2
            || parts.iter().any(|p| {
                p.is_empty()
                    || *p == "."
                    || *p == ".."
                    || !p
                        .bytes()
                        .all(|c| c.is_ascii_alphanumeric() || b"-_.".contains(&c))
            })
            || self.hostname.is_empty()
            || !self
                .hostname
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || b"-.".contains(&c))
        {
            return Err(protocol("invalid GitHub repository"));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GithubRepository {
    pub cwd: String,
    pub on: Option<String>,
    pub name_with_owner: String,
    pub hostname: String,
}

fn failure(kind: &str, detail: &str) -> CoreError {
    CoreError::Rpc {
        kind: kind.into(),
        detail: detail.into(),
    }
}

impl HostConnection {
    fn github_draft_path(
        &self,
        repo: &GithubRepository,
        number: u32,
    ) -> Result<std::path::PathBuf, CoreError> {
        repo.validate()?;
        if number == 0 {
            return Err(protocol("invalid GitHub issue number"));
        }
        let key = serde_json::to_vec(&(&repo.on, &repo.hostname, &repo.name_with_owner, number))
            .map_err(storage)?;
        let hash: String = Sha256::digest(key)
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        let name = format!("{hash}.txt");
        Ok(self
            .last_known
            .store
            .host_dir(&self.host_id)
            .join("github-drafts")
            .join(name))
    }

    pub(crate) async fn github_api(
        &self,
        repo: &GithubRepository,
        endpoint: String,
        fields: Vec<String>,
        paginate: bool,
    ) -> Result<Vec<u8>, CoreError> {
        repo.validate()?;
        let mut args = vec![
            "api".into(),
            "--hostname".into(),
            repo.hostname.clone(),
            "--method".into(),
            "GET".into(),
            endpoint,
        ];
        for field in fields {
            args.extend(["-f".into(), field]);
        }
        if paginate {
            args.extend(["--paginate".into(), "--slurp".into()]);
        }
        self.github_exec(repo, args).await
    }

    /// Bounded and never retried, including mutations whose outcome may be unknown.
    /// Do not return gh stdout/stderr in errors: they may contain private content.
    pub(crate) async fn github_exec(
        &self,
        repo: &GithubRepository,
        args: Vec<String>,
    ) -> Result<Vec<u8>, CoreError> {
        let mut argv = vec!["gh".into()];
        argv.extend(args);
        let out = self
            .machine_exec_on(argv, Some(repo.cwd.clone()), 20, repo.on.clone())
            .await
            .map_err(|e| match e {
                CoreError::Rpc { ref kind, .. } if kind == "spawn_failed" => {
                    failure("github_missing", "Install gh on this Host")
                }
                other => other,
            })?;
        if out.timed_out || out.signal.is_some() {
            return Err(failure(
                "github_unknown",
                "Request interrupted; check GitHub before repeating a write",
            ));
        }
        if out.code == Some(4) {
            return Err(failure("github_auth", "Run gh auth login on this Host"));
        }
        if out.code != Some(0) {
            return Err(failure(
                "github_failed",
                "GitHub request failed; check repository access and Host connectivity",
            ));
        }
        if out.truncated {
            return Err(failure(
                "github_truncated",
                "GitHub response exceeded the Host output limit",
            ));
        }
        Ok(out.stdout)
    }
}

#[uniffi::export]
impl HostConnection {
    /// Local per-Host drafts share the Host-removal lock, preventing late edits
    /// from recreating a removed Host's data. No draft is sent automatically.
    pub fn github_load_draft(
        &self,
        repo: GithubRepository,
        number: u32,
    ) -> Result<String, CoreError> {
        let hosts = self.last_known.hosts.lock().expect("hosts lock");
        if !hosts.iter().any(|h| h.id == self.host_id) {
            return Err(CoreError::UnknownHost {
                id: self.host_id.clone(),
            });
        }
        let path = self.github_draft_path(&repo, number)?;
        match std::fs::read_to_string(path) {
            Ok(body) => Ok(body),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(String::new()),
            Err(e) => Err(storage(e)),
        }
    }

    pub fn github_save_draft(
        &self,
        repo: GithubRepository,
        number: u32,
        body: String,
    ) -> Result<(), CoreError> {
        if body.len() > 65536 {
            return Err(protocol("GitHub draft exceeds 64 KiB"));
        }
        let hosts = self.last_known.hosts.lock().expect("hosts lock");
        if !hosts.iter().any(|h| h.id == self.host_id) {
            return Err(CoreError::UnknownHost {
                id: self.host_id.clone(),
            });
        }
        let path = self.github_draft_path(&repo, number)?;
        if body.is_empty() {
            return match std::fs::remove_file(path) {
                Ok(()) => Ok(()),
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
                Err(e) => Err(storage(e)),
            };
        }
        std::fs::create_dir_all(path.parent().expect("draft parent")).map_err(storage)?;
        let temp = path.with_extension("tmp");
        std::fs::write(&temp, body).map_err(storage)?;
        std::fs::rename(temp, path).map_err(storage)
    }

    /// Pin foreground cwd and forwarded machine once, so later navigation/writes
    /// cannot accidentally target the Terminal's new folder.
    pub async fn github_repository(&self, address: String) -> Result<GithubRepository, CoreError> {
        let on = address.rsplit_once('/').map(|(m, _)| m.to_owned());
        let client = self.client.clone();
        let terminal = on_runtime(async move {
            client
                .call("terminal.get", json!({"terminal": address}))
                .await
        })
        .await?;
        let cwd = terminal["foreground"]["cwd"]
            .as_str()
            .filter(|s| !s.is_empty())
            .or_else(|| terminal["cwd"].as_str().filter(|s| !s.is_empty()))
            .ok_or_else(|| protocol("Terminal has no working folder"))?
            .to_owned();
        let mut repo = GithubRepository {
            cwd,
            on,
            name_with_owner: String::new(),
            hostname: String::new(),
        };
        let bytes = self
            .github_exec(
                &repo,
                ["repo", "view", "--json", "nameWithOwner,url"]
                    .map(str::to_owned)
                    .to_vec(),
            )
            .await?;
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct Response {
            name_with_owner: String,
            url: String,
        }
        let response: Response =
            serde_json::from_slice(&bytes).map_err(|_| protocol("invalid gh repository JSON"))?;
        repo.name_with_owner = response.name_with_owner;
        repo.hostname = response
            .url
            .strip_prefix("https://")
            .and_then(|s| s.split('/').next())
            .filter(|s| !s.is_empty())
            .ok_or_else(|| protocol("invalid GitHub repository URL"))?
            .into();
        repo.validate()?;
        Ok(repo)
    }

    /// GitHub Search returns at most 1,000 results; tell the UI to narrow the
    /// search rather than silently treating that limit as the end of the repo.
    pub async fn github_list(
        &self,
        repo: GithubRepository,
        kind: GithubKind,
        state: GithubState,
        search: String,
        page: u32,
    ) -> Result<GithubPage, CoreError> {
        if !(1..=50).contains(&page) || search.len() > 256 {
            return Err(protocol("invalid GitHub search page"));
        }
        let mut query = String::new();
        if !search.trim().is_empty() {
            // Literal text keeps user-supplied repo:/is: qualifiers inside this repository.
            query = format!(
                "\"{}\" ",
                search.trim().replace('\\', "\\\\").replace('"', "\\\"")
            );
        }
        query.push_str(&format!(
            "repo:{} is:{}",
            repo.name_with_owner,
            if kind == GithubKind::Issue {
                "issue"
            } else {
                "pr"
            }
        ));
        match state {
            GithubState::Open => query.push_str(" is:open"),
            GithubState::Closed => query.push_str(" is:closed"),
            GithubState::All => {}
        }
        let bytes = self
            .github_api(
                &repo,
                "search/issues".into(),
                vec![
                    format!("q={query}"),
                    "per_page=20".into(),
                    format!("page={page}"),
                    "sort=updated".into(),
                    "order=desc".into(),
                ],
                false,
            )
            .await?;
        #[derive(Deserialize)]
        struct Response {
            total_count: u64,
            incomplete_results: bool,
            items: Vec<RawItem>,
        }
        let raw: Response =
            serde_json::from_slice(&bytes).map_err(|_| protocol("invalid gh search JSON"))?;
        Ok(GithubPage {
            items: raw
                .items
                .into_iter()
                .map(RawItem::into_item)
                .collect::<Result<_, _>>()?,
            has_more: u64::from(page) * 20 < raw.total_count.min(1000),
            limited: raw.incomplete_results || raw.total_count > 1000,
        })
    }

    /// The Issues REST endpoint also serves PR conversations. Fetch all comment
    /// pages; a timeout/output cap is an explicit failure, never a partial thread.
    pub async fn github_detail(
        &self,
        repo: GithubRepository,
        number: u32,
    ) -> Result<GithubDetail, CoreError> {
        if number == 0 {
            return Err(protocol("invalid GitHub issue number"));
        }
        let endpoint = format!("repos/{}/issues/{number}", repo.name_with_owner);
        let bytes = self
            .github_api(&repo, endpoint.clone(), vec![], false)
            .await?;
        let raw: RawItem =
            serde_json::from_slice(&bytes).map_err(|_| protocol("invalid gh detail JSON"))?;
        let mut item = raw.into_item()?;
        if item.number != number {
            return Err(protocol("GitHub returned a different issue"));
        }
        // Issues carry the conversation, but branch/draft/merge metadata is
        // available only from the PR endpoint. Never infer it from the checkout.
        let (head_branch, base_branch) = if item.kind == GithubKind::PullRequest {
            #[derive(Deserialize)]
            struct Branch {
                r#ref: String,
            }
            #[derive(Deserialize)]
            struct Pull {
                number: u32,
                head: Branch,
                base: Branch,
                draft: bool,
                merged: bool,
            }
            let bytes = self
                .github_api(
                    &repo,
                    format!("repos/{}/pulls/{number}", repo.name_with_owner),
                    vec![],
                    false,
                )
                .await?;
            let pull: Pull = serde_json::from_slice(&bytes)
                .map_err(|_| protocol("invalid GitHub PR metadata JSON"))?;
            if pull.number != number || pull.head.r#ref.is_empty() || pull.base.r#ref.is_empty() {
                return Err(protocol("invalid GitHub PR metadata"));
            }
            if pull.merged {
                item.state = "merged".into();
            } else if item.state == "open" && pull.draft {
                item.state = "draft".into();
            }
            (Some(pull.head.r#ref), Some(pull.base.r#ref))
        } else {
            (None, None)
        };
        let bytes = self
            .github_api(
                &repo,
                format!("{endpoint}/comments"),
                vec!["per_page=100".into()],
                true,
            )
            .await?;
        #[derive(Deserialize)]
        struct Comment {
            id: u64,
            body: String,
            user: Option<Author>,
            created_at: String,
        }
        let pages: Vec<Vec<Comment>> =
            serde_json::from_slice(&bytes).map_err(|_| protocol("invalid gh comment JSON"))?;
        Ok(GithubDetail {
            item,
            head_branch,
            base_branch,
            comments: pages
                .into_iter()
                .flatten()
                .map(|c| GithubComment {
                    id: c.id,
                    body: c.body,
                    author: c.user.map_or_else(String::new, |u| u.login),
                    created_at: c.created_at,
                })
                .collect(),
        })
    }

    /// Explicit --body avoids editors/prompts and is one literal argv element.
    /// A write is issued exactly once; its result is never retried automatically.
    pub async fn github_comment(
        &self,
        repo: GithubRepository,
        kind: GithubKind,
        number: u32,
        body: String,
    ) -> Result<(), CoreError> {
        repo.validate()?;
        if number == 0 || body.trim().is_empty() || body.len() > 65536 {
            return Err(protocol("invalid GitHub comment"));
        }
        self.github_exec(
            &repo,
            vec![
                if kind == GithubKind::Issue {
                    "issue".into()
                } else {
                    "pr".into()
                },
                "comment".into(),
                number.to_string(),
                "--repo".into(),
                format!("{}/{}", repo.hostname, repo.name_with_owner),
                "--body".into(),
                body,
            ],
        )
        .await?;
        Ok(())
    }

    /// Only Issues can be closed/reopened here. PR lifecycle/review/merge is outside this API.
    pub async fn github_set_issue_closed(
        &self,
        repo: GithubRepository,
        number: u32,
        closed: bool,
    ) -> Result<(), CoreError> {
        repo.validate()?;
        if number == 0 {
            return Err(protocol("invalid GitHub issue number"));
        }
        self.github_exec(
            &repo,
            vec![
                "issue".into(),
                if closed {
                    "close".into()
                } else {
                    "reopen".into()
                },
                number.to_string(),
                "--repo".into(),
                format!("{}/{}", repo.hostname, repo.name_with_owner),
            ],
        )
        .await?;
        Ok(())
    }
}
