# (Experimental) Quarkus Migration Skills

**This is an experimental project.**
**It is not yet ready for production use.**

AI agent skills for migrating Java applications to [Quarkus](https://quarkus.io/), with a test harness to measure and track migration quality over time.

## Skills

| Skill | Description |
|-------|-------------|
| [spring-boot-to-quarkus](skills/spring-boot-to-quarkus/) | Migrate Spring Boot applications to Quarkus |
| [jakarta-ee-to-quarkus](skills/jakarta-ee-to-quarkus/) | Migrate Jakarta EE applications to Quarkus |

Each skill supports two migration strategies:
- **Full migration** — rewrite to idiomatic Quarkus (JAX-RS, CDI, Panache, Qute, etc.)
- **Compatibility migration** — use Quarkus Spring compatibility extensions (`quarkus-spring-web`, `quarkus-spring-data-jpa`, etc.) for faster migration with less rewriting

## Install skills

Install using `skills` CLI:

```bash
npx skills install maxandersen/quarkus-migration-skills
```

Pick your favourite agent and try it out by opening a chat on a Spring or Jakarta EE project with it and saying:

```
Migrate this project to Quarkus
```

NOTE: This is not a full-featured skill. It is a proof of concept to demonstrate the potential of AI agents for migrating Java applications to Quarkus.

## Test Harness

JUnit 5 test suite that runs migrations against real projects and scores the results.
See [tests/README.md](tests/README.md) for full details.

```bash
cd tests/

# Run all test projects with default model
mvn test

# Run a specific project
mvn test -Dai.project=spring-rest-api

# Run with a specific provider/model
mvn test -Dai.provider=anthropic -Dai.model=claude-sonnet-4-5-20250514

# Compare across models
mvn test -Dai.provider=anthropic -Dai.model=claude-sonnet-4-5-20250514
mvn test -Dai.provider=google -Dai.model=gemini-2.5-pro
mvn test -Dai.provider=openai -Dai.model=o3
```

## Results

Results are appended to `tests/target/runs/history.jsonl` with token usage, cost, and per-check results. Run artifacts (logs, session, review) are stored alongside as `<run>.*` files.

```bash
# Generate HTML dashboard with score trends, cost comparison, and reviews
cd tests && ./report.sh && open target/runs/report.html
```

## Repository Structure

```
skills/
├── spring-boot-to-quarkus/    # Spring Boot → Quarkus migration skill
├── jakarta-ee-to-quarkus/     # Jakarta EE → Quarkus migration skill
└── shared/references/         # Common mapping tables (deps, annotations, config)

tests/
├── pom.xml                    # Maven project for JUnit test harness
├── src/test/java/             # Test classes (MigrationTest, PiRunner, etc.)
├── projects/                  # Test project definitions
│   ├── spring-rest-api/       # Minimal REST API (in-repo)
│   ├── spring-jpa-crud/       # JPA CRUD app (in-repo)
│   ├── spring-petclinic/      # Classic petclinic (cloned)
│   └── spring-petclinic-rest/ # REST-only petclinic (cloned)
└── README.md
# After running tests:
tests/target/
├── runs/                      # All run artifacts
│   ├── history.jsonl          # Score history (appended per run)
│   ├── <run>.json.log         # Raw JSON streaming output
│   ├── <run>.pretty.md        # Human-readable log
│   ├── <run>.session.jsonl    # Pi session (resumable)
│   └── <run>.review.md        # Skill improvement review
└── workdirs/                  # Migrated project source
```

## Contributing

1. Improve a skill's SKILL.md with better instructions
2. Run the test harness to verify scores don't regress
3. Add new test projects to cover more migration patterns
