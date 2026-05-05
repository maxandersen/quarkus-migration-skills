# Test Harness

JUnit 5 test suite that runs migration skills against real Spring Boot / Jakarta EE projects, scores the results, and generates skill improvement reviews — all tracked over time.

## Prerequisites

- **Java 21+** — `java -version`
- **Maven 3.9+** — `mvn -version`
- **[pi](https://github.com/badlogic/pi-mono) CLI** — `pi --version`
- **git** — for cloning external test projects
- At least one **AI provider** configured in pi (see [Provider Setup](#provider-setup) below)

## Provider Setup

The test harness calls `pi` to run migrations. Pi needs credentials for whichever AI provider/model you want to test. You only need **one** provider configured — pick whichever you have access to.

### Option 1: Interactive Login (Subscriptions)

If you have a subscription (Claude Pro/Max, ChatGPT Plus, GitHub Copilot, Google Gemini CLI, etc.), use pi's interactive login once:

```bash
pi
# then type: /login
# select your provider and follow the OAuth flow
```

Tokens are stored in `~/.pi/agent/auth.json` and auto-refresh.

### Option 2: API Keys

Set an environment variable for your provider before running tests:

```bash
# Anthropic
export ANTHROPIC_API_KEY=sk-ant-...

# OpenAI
export OPENAI_API_KEY=sk-...

# Google Gemini
export GEMINI_API_KEY=...

# Mistral
export MISTRAL_API_KEY=...

# xAI (Grok)
export XAI_API_KEY=...

# OpenRouter (access to many models)
export OPENROUTER_API_KEY=...
```

### Option 3: Cloud Providers

**Google Vertex AI:**
```bash
gcloud auth application-default login
export GOOGLE_CLOUD_PROJECT=your-project
export GOOGLE_CLOUD_LOCATION=us-central1  # optional, defaults to us-central1
```

**Amazon Bedrock:**
```bash
export AWS_PROFILE=your-profile
# or
export AWS_ACCESS_KEY_ID=AKIA...
export AWS_SECRET_ACCESS_KEY=...
export AWS_REGION=us-east-1
```

**Azure OpenAI:**
```bash
export AZURE_OPENAI_API_KEY=...
export AZURE_OPENAI_BASE_URL=https://your-resource.openai.azure.com
```

### Option 4: Custom Providers (Ollama, etc.)

Pi supports custom providers via `models.json` or extensions. See [pi docs on custom providers](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/docs/custom-provider.md).

### Verifying Your Setup

Check which providers and models are available:

```bash
# List all available models
pi --list-models

# Search for specific models
pi --list-models sonnet
pi --list-models gemini
pi --list-models gpt

# Quick test — should produce a response
pi --print "Say hello"
```

The output shows provider name and model ID — use these as `provider/model` for the `ai.model` property:

```
provider          model                          context  max-out
anthropic         claude-sonnet-4-5-20250514     200K     64K
google            gemini-2.5-pro                 1M       64K
openai            gpt-4o                         128K     16K
```

## Running Tests

```bash
cd tests/

# Run all in-repo test projects with default model
mvn test

# Select the agent to be used. Default is: pi
mvn test -Dai.cmd=pi

# Run a specific project
mvn test -Dai.project=spring-rest-api

# Set provider only (uses provider's default model)
mvn test -Dai.provider=anthropic
mvn test -Dai.provider=google
mvn test -Dai.provider=openai

# Set model only (pi picks the provider via fuzzy matching)
mvn test -Dai.model=claude-sonnet-4-5-20250514
mvn test -Dai.model=gemini-2.5-pro

# Set both provider and model explicitly (recommended for CI)
mvn test -Dai.provider=anthropic -Dai.model=claude-sonnet-4-5-20250514
mvn test -Dai.provider=google -Dai.model=gemini-2.5-pro
mvn test -Dai.provider=openai -Dai.model=gpt-4o
mvn test -Dai.provider=vertex-anthropic -Dai.model=claude-sonnet-4-5@20250929
mvn test -Dai.provider=amazon-bedrock -Dai.model=us.anthropic.claude-sonnet-4-20250514-v1:0
mvn test -Dai.provider=openrouter -Dai.model=anthropic/claude-sonnet-4-5

# Use compatibility migration strategy instead of full
mvn test -Dai.strategy=compatibility

# Override timeout (seconds)
mvn test -Dai.project=spring-petclinic -Dai.timeout=900

# Combine options
mvn test -Dai.project=spring-jpa-crud -Dai.provider=anthropic -Dai.model=claude-sonnet-4-5-20250514 -Dai.timeout=600
```

### Configuration Properties

All configuration via `-D` flags:

| Property | Default | Description |
|----------|---------|-------------|
| `ai.provider` | *(pi default)* | Provider name (e.g. `anthropic`, `google`, `openai`, `vertex-anthropic`) |
| `ai.model` | *(pi default)* | Model ID (e.g. `claude-sonnet-4-5-20250514`, `gemini-2.5-pro`) |
| `ai.strategy` | `full` | Migration strategy: `full` or `compatibility` |
| `ai.timeout` | `300` | Timeout per project in seconds |
| `ai.cmd` | `pi` | Path to pi binary (if not on PATH) |
| `ai.project` | *(all)* | Run only this project name |
| `ai.skill` | *(from project.yaml)* | Skill to use: a local name (e.g. `spring-boot-to-quarkus`) or a GitHub URL |
| `ai.skill.branch` | *(parsed from URL)* | Explicit branch — only needed when the branch name contains `/` and the URL has a subpath |

### Selecting a skill

`ai.skill` accepts a local skill name or a GitHub URL pasted directly from the browser:

```bash
# Local skill by name (looked up in skills/)
mvn test -Dai.skill=jakarta-ee-to-quarkus

# Remote skill — paste the GitHub URL as-is
mvn test -Dai.skill=https://github.com/org/repo/tree/main/skills/custom-skill

# Remote skill on a feature branch (branch name has no slashes — URL is unambiguous)
mvn test -Dai.skill=https://github.com/org/repo/tree/new-feature-branch/skills/custom-skill

# Remote skill when branch name contains '/' — add ai.skill.branch to resolve ambiguity
mvn test -Dai.skill=https://github.com/org/repo/tree/branch/with/slashes/new-feature-skill \
         -Dai.skill.branch=branch/with/slashes
```

Remote clones are cached in `target/skills/` and cleaned with `mvn clean`.

You can set `ai.provider`, `ai.model`, or both:
- **Neither** — pi uses its configured default provider and model
- **Provider only** (`-Dai.provider=anthropic`) — uses that provider's default model
- **Model only** (`-Dpi.model=claude-sonnet-4-5-20250514`) — pi fuzzy-matches the provider (may be ambiguous if multiple providers offer the same model)
- **Both** (`-Dpi.provider=anthropic -Dpi.model=claude-sonnet-4-5-20250514`) — explicit, recommended for CI

### Provider and Model Names

The provider and model values come from `pi --list-models` output:

```
provider          model
anthropic         claude-sonnet-4-5-20250514
google            gemini-2.5-pro
openai            gpt-4o
vertex-anthropic  claude-sonnet-4-5@20250929
amazon-bedrock    us.anthropic.claude-sonnet-4-20250514-v1:0
azure-openai      gpt-4o
openrouter        anthropic/claude-sonnet-4-5
copilot           claude-sonnet-4
ollama            llama3
```

## What Happens During a Test Run

Each test project goes through these phases:

1. **Prepare** — copies local source or clones external repo into `target/workdirs/<project>/`
2. **Migrate** — runs `pi` with the migration skill against the project (output streams to console)
3. **Check** — runs verification checks (builds, tests pass, no Spring deps, has Quarkus, starts up)
4. **Review** — forks the migration session and asks pi to review the skill and suggest improvements (separate session, separate cost)
5. **Record** — appends results to `results/history.jsonl`

## Test Output

During the run, you'll see live-streamed output:

```
┌── turn
│ 🤖 assistant:
I'll migrate this Spring Boot project to Quarkus...
│    [tokens: 5000, cost: $0.0150]
│ 🔧 read: pom.xml
│ 🔧 edit: pom.xml (4 edits)
│ 🔧 bash: ./mvnw compile
└── turn end
```

## Run Artifacts

Artifacts are stored in two locations:

**`target/runs/`** — run logs and reviews, named `<project>_<model>_<strategy>.*`:

| File | Description |
|------|-------------|
| `<run>.json.log` | Raw JSON streaming output (every event from pi) |
| `<run>.pretty.md` | Human-readable log (what you see in the console) |
| `<run>.session.jsonl` | Pi session file (can be resumed with `pi --session <path>`) |
| `<run>.review.md` | Skill review with improvement suggestions |

Example filenames:
```
target/runs/
├── spring-rest-api_claude-sonnet-4-5-20250514_full.json.log
├── spring-rest-api_claude-sonnet-4-5-20250514_full.pretty.md
├── spring-rest-api_claude-sonnet-4-5-20250514_full.session.jsonl
└── spring-rest-api_claude-sonnet-4-5-20250514_full.review.md
```

**`target/workdirs/<project>/`** — the migrated project source code (pom.xml, src/, etc.)

You can resume a migration session to inspect or continue:

```bash
pi --session target/runs/spring-rest-api_claude-sonnet-4-5-20250514_full.session.jsonl
```

## Test Projects

### In-Repo (self-contained, no external dependencies)

| Project | Description | Complexity | Checks |
|---------|-------------|-----------|--------|
| `spring-rest-api` | REST controller + service + validation, no DB | Trivial | builds, tests-pass, no-spring-deps, has-quarkus, starts-up |
| `spring-jpa-crud` | CRUD with JPA, H2, Spring Data, custom queries | Low | builds, tests-pass, no-spring-deps, has-quarkus, starts-up |

### External (cloned at runtime)

| Project | Description | Complexity | Checks |
|---------|-------------|-----------|--------|
| `spring-petclinic` | Classic PetClinic with Thymeleaf, JPA, caching | Medium | builds, tests-pass, no-spring-deps, has-quarkus, starts-up, no-thymeleaf |
| `spring-petclinic-rest` | REST-only PetClinic, no templates | Medium | builds, tests-pass, no-spring-deps, has-quarkus, starts-up |

## Checks

| Check | What it verifies |
|-------|-----------------|
| `builds` | `./mvnw compile` succeeds |
| `tests-pass` | `./mvnw test` succeeds |
| `no-spring-deps` | No `org.springframework` in `pom.xml` |
| `has-quarkus` | `io.quarkus` present in `pom.xml` |
| `starts-up` | App starts and responds to HTTP (port 18080) |
| `no-thymeleaf` | No Thymeleaf references remain in code or pom |

## Results Tracking

Results are appended to `target/runs/history.jsonl` — one JSON line per run:

```json
{
  "project": "spring-rest-api",
  "date": "2026-04-11T08:30:00Z",
  "model": "vertex-anthropic/claude-sonnet-4-5@20250929",
  "strategy": "full",
  "skill": "spring-boot-to-quarkus",
  "duration_seconds": 196,
  "usage": {"total_tokens": 321222, "total_cost": 0.3216, "api_calls": 22},
  "checks": {"builds": true, "tests-pass": true, "no-spring-deps": true, "has-quarkus": true, "starts-up": true},
  "score": "5/5",
  "review": {"tokens": 376929, "cost": 0.466, "summary": "The skill performed well..."}
}
```

Compare runs across models by grepping the history:

```bash
# See all runs
cat target/runs/history.jsonl | python3 -m json.tool --json-lines

# Compare scores across models
grep '"score"' target/runs/history.jsonl
```

All run artifacts live under `target/` and are cleaned with `mvn clean`.

## HTML Report

Generate a dashboard from all recorded runs:

```bash
# Generate report from default location
./report.sh

# Opens at target/runs/report.html
open target/runs/report.html
```

The report shows:

- **Summary stats** — total runs, perfect scores, tokens, cost, time
- **Score trends** — per project/model/strategy with visual score progression (3/5 → 4/5 → 5/5)
- **All runs detail** — expandable migration log and skill review for each run
- **Check pass rates** — bar chart showing how often each check passes across all runs
- **Cost comparison** — bar chart comparing costs across configurations

Re-run `./report.sh` after each test to update. The report is a single self-contained HTML file with no external dependencies.

## Adding a Test Project

### In-repo project (checked in, self-contained)

1. Create `tests/projects/<name>/source/` with the full Maven project
2. Make sure it builds and tests pass as a Spring Boot / Jakarta EE app
3. Create `tests/projects/<name>/project.yaml`:

```yaml
name: my-project
description: What migration patterns this tests
type: spring-boot
skill: spring-boot-to-quarkus
source: local
timeout: 300
checks:
  - builds
  - tests-pass
  - no-spring-deps
  - has-quarkus
  - starts-up
```

### External project (cloned from git)

```yaml
name: my-external-project
description: What migration patterns this tests
type: spring-boot
skill: spring-boot-to-quarkus
source: https://github.com/org/repo
ref: main
timeout: 600
checks:
  - builds
  - tests-pass
  - no-spring-deps
  - has-quarkus
```

## Troubleshooting

### "No API key found" or authentication errors

Make sure your provider is configured. Run `pi --list-models` — if it shows models for your provider, credentials are working.

### pi hangs with no output

Pi requires a pseudo-TTY. The test harness handles this via `script -q /dev/null` on macOS/Linux. If you see hangs, check that the `script` command is available.

### Tests timeout

Increase the timeout: `-Dpi.timeout=900`. Complex projects like petclinic may need 10-15 minutes.

### Maven wrapper not found

Some test projects don't ship `mvnw`. The migration agent usually creates it, but if checks fail with "mvnw not found", the agent didn't get to that step (likely timed out).

### Port conflict on starts-up check

The `starts-up` check uses port 18080. If another process is using it, the check will fail. Kill any stale Quarkus dev processes:

```bash
lsof -i :18080 | grep LISTEN
kill <pid>
```
