//! Read-only PR resources through the Host's gh, using its pinned repository identity.
use crate::{
    CoreError, GitDiff, GithubRepository, HostConnection, error::protocol, parse_git_diff,
};
use serde::{Deserialize, de::DeserializeOwned};

#[derive(Debug, Clone, uniffi::Record)]
pub struct GithubPrFile {
    pub path: String,
    pub previous_path: Option<String>,
    pub status: String,
    pub additions: u32,
    pub deletions: u32,
    /// GitHub omits patches for binary and some large files. None is not an empty diff.
    pub patch: Option<GitDiff>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GithubPrFiles {
    pub files: Vec<GithubPrFile>,
    /// The files endpoint has a hard cap of 3,000; report reaching it conservatively.
    pub limited: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GithubPrCommit {
    pub sha: String,
    pub message: String,
    pub author: String,
    pub created_at: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GithubPrCommits {
    pub commits: Vec<GithubPrCommit>,
    /// The PR commits endpoint returns at most 250 commits.
    pub limited: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GithubPrCheck {
    pub name: String,
    /// Check-run status, or legacy commit-status state. A null conclusion stays pending.
    pub status: String,
    pub conclusion: Option<String>,
    pub description: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GithubPrChecks {
    pub head_sha: String,
    pub checks: Vec<GithubPrCheck>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GithubPrReview {
    pub id: u64,
    pub author: String,
    pub state: String,
    pub body: String,
    pub submitted_at: String,
}

#[derive(Deserialize)]
struct Author {
    login: String,
}

fn pull_endpoint(repo: &GithubRepository, number: u32) -> Result<String, CoreError> {
    if number == 0 {
        return Err(protocol("invalid GitHub pull request number"));
    }
    Ok(format!("repos/{}/pulls/{number}", repo.name_with_owner))
}

impl HostConnection {
    async fn github_pr_pages<T: DeserializeOwned>(
        &self,
        repo: &GithubRepository,
        endpoint: String,
    ) -> Result<Vec<T>, CoreError> {
        let bytes = self
            .github_api(repo, endpoint, vec!["per_page=100".into()], true)
            .await?;
        serde_json::from_slice(&bytes).map_err(|_| protocol("invalid GitHub PR resource JSON"))
    }
}

#[uniffi::export]
impl HostConnection {
    pub async fn github_pr_reviews(
        &self,
        repo: GithubRepository,
        number: u32,
    ) -> Result<Vec<GithubPrReview>, CoreError> {
        #[derive(Deserialize)]
        struct Review {
            id: u64,
            user: Option<Author>,
            state: String,
            body: Option<String>,
            submitted_at: Option<String>,
        }
        let pages: Vec<Vec<Review>> = self
            .github_pr_pages(&repo, format!("{}/reviews", pull_endpoint(&repo, number)?))
            .await?;
        Ok(pages
            .into_iter()
            .flatten()
            .map(|r| GithubPrReview {
                id: r.id,
                author: r.user.map(|a| a.login).unwrap_or_default(),
                state: r.state,
                body: r.body.unwrap_or_default(),
                submitted_at: r.submitted_at.unwrap_or_default(),
            })
            .collect())
    }

    /// Resolve the PR head on each refresh, never the currently checked-out branch.
    /// Check runs and legacy commit statuses are both needed for a complete CI view.
    pub async fn github_pr_checks(
        &self,
        repo: GithubRepository,
        number: u32,
    ) -> Result<GithubPrChecks, CoreError> {
        #[derive(Deserialize)]
        struct Head {
            sha: String,
        }
        #[derive(Deserialize)]
        struct Pull {
            number: u32,
            head: Head,
        }
        let bytes = self
            .github_api(&repo, pull_endpoint(&repo, number)?, vec![], false)
            .await?;
        let pull: Pull =
            serde_json::from_slice(&bytes).map_err(|_| protocol("invalid GitHub PR head JSON"))?;
        if pull.number != number
            || !matches!(pull.head.sha.len(), 40 | 64)
            || !pull.head.sha.bytes().all(|c| c.is_ascii_hexdigit())
        {
            return Err(protocol("invalid GitHub PR head"));
        }
        #[derive(Deserialize)]
        struct Check {
            name: String,
            status: String,
            conclusion: Option<String>,
        }
        #[derive(Deserialize)]
        struct CheckPage {
            check_runs: Vec<Check>,
        }
        #[derive(Deserialize)]
        struct Status {
            context: String,
            state: String,
            description: Option<String>,
        }
        #[derive(Deserialize)]
        struct StatusPage {
            statuses: Vec<Status>,
        }
        let endpoint = format!("repos/{}/commits/{}", repo.name_with_owner, pull.head.sha);
        let runs: Vec<CheckPage> = self
            .github_pr_pages(&repo, format!("{endpoint}/check-runs"))
            .await?;
        let statuses: Vec<StatusPage> = self
            .github_pr_pages(&repo, format!("{endpoint}/status"))
            .await?;
        let mut checks: Vec<_> = runs
            .into_iter()
            .flat_map(|p| p.check_runs)
            .map(|c| GithubPrCheck {
                name: c.name,
                status: c.status,
                conclusion: c.conclusion,
                description: String::new(),
            })
            .collect();
        checks.extend(
            statuses
                .into_iter()
                .flat_map(|p| p.statuses)
                .map(|s| GithubPrCheck {
                    name: s.context,
                    status: s.state,
                    conclusion: None,
                    description: s.description.unwrap_or_default(),
                }),
        );
        Ok(GithubPrChecks {
            head_sha: pull.head.sha,
            checks,
        })
    }

    pub async fn github_pr_commits(
        &self,
        repo: GithubRepository,
        number: u32,
    ) -> Result<GithubPrCommits, CoreError> {
        #[derive(Deserialize)]
        struct GitAuthor {
            name: String,
            date: String,
        }
        #[derive(Deserialize)]
        struct CommitBody {
            message: String,
            author: Option<GitAuthor>,
        }
        #[derive(Deserialize)]
        struct Commit {
            sha: String,
            author: Option<Author>,
            commit: CommitBody,
        }
        let pages: Vec<Vec<Commit>> = self
            .github_pr_pages(&repo, format!("{}/commits", pull_endpoint(&repo, number)?))
            .await?;
        let commits: Vec<_> = pages
            .into_iter()
            .flatten()
            .map(|c| GithubPrCommit {
                sha: c.sha,
                message: c.commit.message,
                author: c.author.map(|a| a.login).unwrap_or_else(|| {
                    c.commit
                        .author
                        .as_ref()
                        .map(|a| a.name.clone())
                        .unwrap_or_default()
                }),
                created_at: c.commit.author.map(|a| a.date).unwrap_or_default(),
            })
            .collect();
        Ok(GithubPrCommits {
            limited: commits.len() >= 250,
            commits,
        })
    }

    pub async fn github_pr_files(
        &self,
        repo: GithubRepository,
        number: u32,
    ) -> Result<GithubPrFiles, CoreError> {
        #[derive(Deserialize)]
        struct File {
            filename: String,
            previous_filename: Option<String>,
            status: String,
            additions: u32,
            deletions: u32,
            patch: Option<String>,
        }
        let pages: Vec<Vec<File>> = self
            .github_pr_pages(&repo, format!("{}/files", pull_endpoint(&repo, number)?))
            .await?;
        let files: Vec<_> = pages
            .into_iter()
            .flatten()
            .map(|f| GithubPrFile {
                path: f.filename,
                previous_path: f.previous_filename,
                status: f.status,
                additions: f.additions,
                deletions: f.deletions,
                patch: f.patch.map(|p| parse_git_diff(&p, false)),
            })
            .collect();
        Ok(GithubPrFiles {
            limited: files.len() >= 3000,
            files,
        })
    }
}
