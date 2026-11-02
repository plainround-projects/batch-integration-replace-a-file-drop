# Environment files (`.env` and `.env.example`)

## What it is

An **environment variable** is a named piece of text that the operating system gives to every program it starts (`NAME=value`). Programs use them for settings that differ between machines or must stay secret: a database address, a password, a port. A **`.env` file** is a plain text file of `NAME=value` lines kept next to the project. It is not an operating-system feature; tools such as Docker Compose and `make` read it and pass the values on.

Two files exist in this repository: `.env.example` is committed to git and lists every variable with a harmless sample value; `.env` is the real one, created from the example on first use, and is **not** committed (the `.gitignore` excludes it), so passwords never reach the repository.

## Why this repository uses it

The database password and ports must not be in the code, yet a reader must be able to start everything with no manual setup. `make up` runs `make env`, which copies the example to `.env` if it is missing; Compose and the Makefile then read it.

## Where it appears

| Path | What it does |
| --- | --- |
| `.env.example` | The template, committed. |
| `.env` | The copy used locally, ignored by git and by the Docker build. |
| `Makefile` | `env` creates `.env`; `-include .env` and `export` hand the variables to every command. |
| `docker-compose.yml` | Reads the variables with `${NAME:-default}` and `${NAME:?message}`. |
| `.dockerignore` | Keeps `.env` out of the image. |
| `src/main/resources/application.yml` | Reads `DB_URL`, `DB_USER`, `DB_PASSWORD`, `RABBIT_*`, `SFTP_*` and `ORDERFLOW_*` variables. |

## How it works underneath

When `make` starts, `-include .env` reads the file as if it were part of the Makefile, so each line becomes a make variable; `export` then marks them all for the environment of every command `make` runs. `docker compose` reads `.env` itself, from the folder of the compose file, to fill `${...}` references. The Java program never reads `.env`; it reads the process environment that `make` prepared, and Spring Boot maps `DB_PASSWORD` to the setting through `${DB_PASSWORD:}` in `application.yml`.

## Walkthrough of this repository's use

The sample file's contents are not reproduced in these notes: it holds throwaway sample values for the database credentials, and the notes show where each variable is *used* instead.

The copy step:

```make
up: env ## start PostgreSQL, RabbitMQ and the SFTP server
	docker compose up -d --wait postgres rabbit sftp
```

`[ -f .env ]` tests that the file exists; `||` runs the block when it does not; `cp .env.example .env` copies the template; the message tells the reader.

How make passes the values on:

```make
PSQL := docker compose exec -T postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB) -v ON_ERROR_STOP=1 -q
APP := java -jar $(JAR)
```

How Compose consumes them, with a default for the port and a mandatory password:

```yaml
    ports:
      - "${POSTGRES_PORT:-55561}:5432"
    environment:
      POSTGRES_USER: ${POSTGRES_USER:-orderflow}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?copy .env.example to .env first}
      POSTGRES_DB: ${POSTGRES_DB:-orderflow}
```

How the Java program consumes them:

```yaml
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:55561/orderflow}
    username: ${DB_USER:orderflow}
    password: ${DB_PASSWORD:}
    hikari:
      maximum-pool-size: 5
      connection-timeout: 20000
  rabbitmq:
```

How the Docker build refuses to copy the file:

```
.git
target
data
logs
docs
.env
.omc
rejects.txt
```

## Try it yourself

1. Create `.env`: `make env` prints `created .env from .env.example` the first time.
2. See which variables Compose will use: `docker compose config | head -20` (the password appears in the output; do not paste it anywhere).
3. Check git ignores it: `git check-ignore -v .env` prints the `.gitignore` rule.
4. Override one variable for one command: `POSTGRES_PORT=55999 docker compose config | grep 55999`.
5. Prove the program reads the environment, not the file: `ORDERFLOW_POLL_SECONDS=1 make health` still works; `env -i java -jar target/replace-a-file-drop-0.1.0.jar --command=health` fails to connect because the variables are missing.

## Mistakes people make

- **Committing `.env`.** It would publish the password. The `.gitignore` entry prevents it.
- **Editing `.env.example` with real secrets.** It is committed; keep only sample values.
- **Spaces around `=`** (`NAME = value`): not valid in an env file.
- **Expecting a running container to notice a changed `.env`.** Restart the stack.
- **Quoting differences.** Compose and make treat quotes differently; this repository uses plain values.

## Check yourself

1. Which of the two files is committed? *`.env.example`.*
2. What creates `.env`? *The `env` target of the Makefile, run by `make up`.*
3. Does the Java program read `.env`? *No; it reads environment variables prepared by make or Docker.*
4. What does `${POSTGRES_PASSWORD:?message}` do when the variable is missing? *Compose stops and prints the message.*
5. Why is `.env` in `.dockerignore`? *So the password is not copied into the image.*

## Further reading

- Docker Compose environment files: https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/
- GNU make `include` and `export`: https://www.gnu.org/software/make/manual/make.html#Include
- The Twelve-Factor App, config: https://12factor.net/config
