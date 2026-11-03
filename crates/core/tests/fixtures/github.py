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
if args == ["repo", "view", "--json", "nameWithOwner,url"]:
    print(json.dumps({"nameWithOwner": "octo/project", "url": "https://github.example.com/octo/project"}))
elif args == ["api", "--hostname", "github.example.com", "--method", "GET", "search/issues", "-f", 'q="a b 한글" repo:octo/project is:pr is:closed', "-f", "per_page=20", "-f", "page=2", "-f", "sort=updated", "-f", "order=desc"]:
    print(json.dumps({"total_count": 1200, "incomplete_results": False, "items": [
        {"number": 42, "title": "한국어 PR", "state": "closed", "user": {"login": "octocat"}, "created_at": "2026-11-04T00:00:00Z", "body": "**Review**", "pull_request": {}}
    ]}))
elif args == ["api", "--hostname", "github.example.com", "--method", "GET", "repos/octo/project/issues/42"]:
    print(json.dumps({"number": 42, "title": "Issue title", "state": "open", "user": None,
        "created_at": "2026-11-04T00:00:00Z", "body": "# Hello\n```rust\nlet n = 1;\n```"}))
elif args == ["api", "--hostname", "github.example.com", "--method", "GET", "repos/octo/project/issues/42/comments", "-f", "per_page=100", "--paginate", "--slurp"]:
    print(json.dumps([
        [{"id": 1, "body": "**first**", "user": {"login": "octocat"}, "created_at": "2026-11-04T00:00:00Z"}],
        [{"id": 2, "body": "한국어 댓글", "user": None, "created_at": "2026-11-04T00:01:00Z"}]
    ]))
elif len(args) == 7 and args[0] in ("issue", "pr") and args[1:6] == ["comment", "42", "--repo", "github.example.com/octo/project", "--body"]:
    assert args[6] == "--flag '한글'\n$(exit 99); `exit 98` **markdown**"
    print("https://github.example.com/octo/project/issues/42#issuecomment-1")
elif args in (["issue", "close", "42", "--repo", "github.example.com/octo/project"], ["issue", "reopen", "42", "--repo", "github.example.com/octo/project"]):
    pass
else:
    sys.exit(91)
