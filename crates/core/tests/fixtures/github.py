#!/usr/bin/python3
"""External gh boundary: reject wrong argv rather than contact any service."""
import json
import os
import sys

args = sys.argv[1:]
scenario = os.environ.get("GH_TEST_SCENARIO")
if scenario == "auth":
    sys.exit(4)
if scenario == "failure":
    sys.exit(1)
if scenario == "bad_json":
    print("{incomplete")
    sys.exit(0)
if args[0] == "api" and scenario == "pr_bad_json":
    print('{"private-content-not-for-error":')
    sys.exit(0)
if args[0] == "api" and scenario == "pr_empty" and args[5] != "repos/octo/project/pulls/42":
    if args[5].endswith("/check-runs"):
        print('[{"check_runs": []}]')
    elif args[5].endswith("/status"):
        print('[{"statuses": []}]')
    else:
        print('[[]]')
    sys.exit(0)
if args == ["repo", "view", "--json", "nameWithOwner,url"]:
    print(json.dumps({"nameWithOwner": "octo/project", "url": "https://github.example.com/octo/project"}))
elif args == ["api", "--hostname", "github.example.com", "--method", "GET", "search/issues", "-f", 'q="a b 한글" repo:octo/project is:pr is:closed', "-f", "per_page=20", "-f", "page=2", "-f", "sort=updated", "-f", "order=desc"]:
    print(json.dumps({"total_count": 1200, "incomplete_results": False, "items": [
        {"number": 42, "title": "한국어 PR", "state": "closed", "user": {"login": "octocat"}, "created_at": "2026-11-04T00:00:00Z", "body": "**Review**", "pull_request": {}}
    ]}))
elif args == ["api", "--hostname", "github.example.com", "--method", "GET", "repos/octo/project/issues/42"]:
    print(json.dumps({"number": 42, "title": "Issue title", "state": "open", "user": None,
        "created_at": "2026-11-04T00:00:00Z", "body": "# Hello\n```rust\nlet n = 1;\n```",
        **({"pull_request": {}} if scenario in ("pr_detail_draft", "pr_detail_merged") else {})}))
elif args == ["api", "--hostname", "github.example.com", "--method", "GET", "repos/octo/project/issues/42/comments", "-f", "per_page=100", "--paginate", "--slurp"]:
    print(json.dumps([
        [{"id": 1, "body": "**first**", "user": {"login": "octocat"}, "created_at": "2026-11-04T00:00:00Z"}],
        [{"id": 2, "body": "한국어 댓글", "user": None, "created_at": "2026-11-04T00:01:00Z"}]
    ]))
elif args == ["api", "--hostname", "github.example.com", "--method", "GET", "repos/octo/project/pulls/42/files", "-f", "per_page=100", "--paginate", "--slurp"]:
    print(json.dumps([
        [{"filename": "새 이름.rs", "previous_filename": "old.rs", "status": "renamed", "additions": 1, "deletions": 1,
          "patch": "@@ -7 +7 @@\n-old\n+새 코드"}],
        [{"filename": "image.png", "status": "added", "additions": 0, "deletions": 0}]
    ]))
elif args == ["api", "--hostname", "github.example.com", "--method", "GET", "repos/octo/project/pulls/42/commits", "-f", "per_page=100", "--paginate", "--slurp"]:
    print(json.dumps([
        [{"sha": "a" * 40, "author": {"login": "octocat"}, "commit": {"message": "Fix parser\n\n한국어 설명", "author": {"name": "Git name", "date": "2026-11-04T00:00:00Z"}}}],
        [{"sha": "b" * 40, "author": None, "commit": {"message": "Other commit", "author": {"name": "Git author", "date": "2026-11-04T00:01:00Z"}}}]
    ]))
elif args == ["api", "--hostname", "github.example.com", "--method", "GET", "repos/octo/project/pulls/42"]:
    print(json.dumps({"number": 42, "head": {"sha": "c" * 40, "ref": "feature/한글"}, "base": {"ref": "main"},
        "draft": scenario == "pr_detail_draft", "merged": scenario == "pr_detail_merged"}))
elif args == ["api", "--hostname", "github.example.com", "--method", "GET", "repos/octo/project/commits/" + "c" * 40 + "/check-runs", "-f", "per_page=100", "--paginate", "--slurp"]:
    print(json.dumps([
        {"total_count": 2, "check_runs": [{"name": "Android CI", "status": "completed", "conclusion": "failure"}]},
        {"total_count": 2, "check_runs": [{"name": "Kotlin lint", "status": "in_progress", "conclusion": None}]}
    ]))
elif args == ["api", "--hostname", "github.example.com", "--method", "GET", "repos/octo/project/commits/" + "c" * 40 + "/status", "-f", "per_page=100", "--paginate", "--slurp"]:
    print(json.dumps([
        {"total_count": 2, "statuses": [{"context": "external/build", "state": "pending", "description": None}]},
        {"total_count": 2, "statuses": [{"context": "external/audit", "state": "success", "description": "All good"}]}
    ]))
elif args == ["api", "--hostname", "github.example.com", "--method", "GET", "repos/octo/project/pulls/42/reviews", "-f", "per_page=100", "--paginate", "--slurp"]:
    print(json.dumps([
        [{"id": 9, "user": {"login": "octocat"}, "state": "CHANGES_REQUESTED", "body": "**Fix** this\n```rust\nlet n = 1;\n```", "submitted_at": "2026-11-04T00:00:00Z"}],
        [{"id": 10, "user": None, "state": "DISMISSED", "body": "Old review", "submitted_at": "2026-11-04T00:01:00Z"},
         {"id": 11, "user": None, "state": "PENDING", "body": None, "submitted_at": None}]
    ]))
elif len(args) == 7 and args[0] in ("issue", "pr") and args[1:6] == ["comment", "42", "--repo", "github.example.com/octo/project", "--body"]:
    assert args[6] == "--flag '한글'\n$(exit 99); `exit 98` **markdown**"
    print("https://github.example.com/octo/project/issues/42#issuecomment-1")
elif args in (["issue", "close", "42", "--repo", "github.example.com/octo/project"], ["issue", "reopen", "42", "--repo", "github.example.com/octo/project"]):
    pass
else:
    sys.exit(91)
