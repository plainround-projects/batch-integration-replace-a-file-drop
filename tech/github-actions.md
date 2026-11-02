# GitHub Actions

## What it is

GitHub Actions is GitHub's built-in automation. When something happens in a repository (a push, a pull request), GitHub starts a fresh virtual machine, checks out the code and runs the steps described in a YAML file under `.github/workflows/`. This is **continuous integration** (CI): every change is built and tested by a machine the same way, so a broken change is noticed immediately and not weeks later.

Vocabulary: a **workflow** is one YAML file; it has **triggers** (`on:`), and one or more **jobs**; a job runs on a **runner** (the virtual machine, here `ubuntu-latest`) and is a list of **steps**; a step either runs a shell command (`run:`) or uses a ready-made **action** (`uses:`), a reusable step published by someone, referenced as `owner/name@version`.

## Why this repository uses it

The delivery rule says CI must run format, lint, tests and the audit on every push. The workflow here does exactly that and nothing else: two jobs, one that needs no services and one that starts PostgreSQL, RabbitMQ and an SFTP server and runs the integration and end-to-end tests.

## Where it appears

| Path | What it does |
| --- | --- |
| `.github/workflows/ci.yml` | The only workflow. |

## How it works underneath

Jobs run in parallel by default, each on its own clean runner, so they share nothing. Inside a job the steps run in order and stop at the first failure (unless the step says `if: always()`). `actions/checkout` copies the repository into the runner; `actions/setup-java` installs a JDK and, with `cache: maven`, saves and restores the `~/.m2` folder between runs so downloads are fast. The workflow does not repeat commands: it calls the same `make` targets a person runs locally, so what passes in CI is what passes on a laptop. Nothing here needs a secret.

## Walkthrough of this repository's use

```yaml
name: ci

on: [push, pull_request]

jobs:
  check:
    name: Format, compile with -Werror, unit and architecture tests with coverage, dependency audit
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven
      - run: make lint
      - run: make test
      - run: make audit
```

`name: ci` is the label shown in GitHub's list. `on: [push, pull_request]` triggers the workflow for every push and every pull request (a proposed change). `jobs:` holds the jobs; this one is called `check`. Its `name` is the longer text GitHub displays. `runs-on: ubuntu-latest` picks the runner. `steps:` is the list; each item starts with a dash. `uses: actions/checkout@v4` copies the repository. `uses: actions/setup-java@v4` with `distribution: temurin`, `java-version: "21"` and `cache: maven` installs Eclipse Temurin JDK 21 and caches Maven's downloads. The last three steps are `run:` steps: `make lint`, `make test`, `make audit`, in that order; the first failing step fails the job.

```yaml
  integration:
    name: Integration and end-to-end tests (PostgreSQL, RabbitMQ and an SFTP server from the compose file)
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven
      - run: make up
      - run: make db
      - run: make test-integration test-e2e
      - if: always()
        run: make down
```

The second job, `integration`, has a `timeout-minutes: 45` so a hang cannot run for hours. It repeats checkout and Java setup, then `make up` (start PostgreSQL, RabbitMQ and the SFTP server from the compose file), `make db` (load the schema and the migration), `make test-integration test-e2e` (two targets in one command: make runs them in order) and finally `make down` with `if: always()`, a condition that makes the step run even when an earlier step failed, so the container is always removed.

## Try it yourself

1. Read the workflow as GitHub sees it: `cat .github/workflows/ci.yml`.
2. Run the same commands locally in the same order: `make lint && make test && make audit`.
3. Check the YAML parses: `python3 -c "import yaml; print(list(yaml.safe_load(open('.github/workflows/ci.yml'))['jobs']))"` prints `['check', 'integration']` (needs PyYAML installed; otherwise skip this step).
4. Run the integration job's commands: `make up db test-integration test-e2e down`.
5. After pushing to GitHub, open the repository's Actions tab and compare the log of each step with your local output.

## Mistakes people make

- **A workflow that differs from the local commands.** Then CI and your laptop disagree. Call `make` targets.
- **Unpinned action versions.** `@main` can change; pin a major version (`@v4`).
- **No cleanup on failure.** `if: always()` makes the `make down` step run even after a failure.
- **Secrets in the file.** Use GitHub's encrypted secrets; this workflow needs none.
- **Timeouts.** A hung job runs for up to six hours unless `timeout-minutes` is set.

## Check yourself

1. What starts the workflow? *A push or a pull request.*
2. Why does CI call `make`? *So it runs the same commands as a person does locally.*
3. What does `if: always()` do? *Runs the step even if an earlier one failed.*
4. What does `cache: maven` save? *The downloaded dependencies, between runs.*
5. Do the two jobs share files? *No, each runs on a clean runner.*

## Further reading

- GitHub Actions documentation: https://docs.github.com/en/actions
- Workflow syntax: https://docs.github.com/en/actions/writing-workflows/workflow-syntax-for-github-actions
- `actions/checkout` v4: https://github.com/actions/checkout
- `actions/setup-java` v4: https://github.com/actions/setup-java
