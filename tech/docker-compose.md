# Docker Compose

## What it is

Docker Compose describes several containers (and the networks and volumes they share) in one YAML file, `docker-compose.yml`, and starts them all with a single command. Instead of typing a long `docker run` with ten options for every service, you write the options once and run `docker compose up`. Compose creates a private network so the containers can reach each other by service name.

## Why this repository uses it

The lesson needs five programs running together: PostgreSQL, RabbitMQ, an SFTP server, and the two services of the new path (the flow and the order service). Compose describes them in one file, starts the three that are infrastructure with `make up`, and starts the two application containers on demand with `make apps` using a **profile**. The named volumes keep the database and the broker's queues when a container restarts, which the lesson's restart demo depends on.

## Where it appears

| Path | What it does |
| --- | --- |
| `docker-compose.yml` | The five services, the volumes and the network. |
| `Makefile` | `make up` runs `docker compose up -d --wait postgres rabbit sftp`; `make apps` adds the profile; `make down` runs `docker compose --profile apps down -v`; `PSQL` uses `docker compose exec`. |
| `scripts/demo_restart.sh` | Runs `docker restart` on the broker and the order service. |
| `.github/workflows/ci.yml` | Brings the three infrastructure services up in CI. |

## How it works underneath

`docker compose up -d` reads the file, creates the network and volumes, pulls missing images, and starts one container per service. `-d` (detached) returns the terminal at once; `--wait` also waits until each service's health check passes. A **health check** is a command Docker runs inside the container on a timer; when it succeeds enough times the container is marked *healthy*. `docker compose exec service command` runs a command inside a running container. `docker compose down -v` stops everything and also deletes the volumes (and so the data). The top-level `name:` becomes the prefix of the container, volume and network names, so two projects do not collide. Values like `${POSTGRES_PORT:-55541}` are filled from the environment or from a `.env` file next to the compose file, with the text after `:-` as the default; `${VAR:?message}` makes Compose stop with the message when the variable is missing.

## Walkthrough of this repository's use

```yaml
# The partner's SFTP server, the broker, the database, and (profile "apps") the flow and the order service as containers.
# Local use only; every credential comes from .env and is a throwaway.
name: batch-integration-replace-a-file-drop

services:
  postgres:
    image: postgres:16
    ports:
      - "${POSTGRES_PORT:-55561}:5432"
    environment:
      POSTGRES_USER: ${POSTGRES_USER:-orderflow}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?copy .env.example to .env first}
      POSTGRES_DB: ${POSTGRES_DB:-orderflow}
    shm_size: 256mb
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
      interval: 10s
      timeout: 10s
      retries: 30
      start_period: 5s
    volumes:
      - orderflow-data:/var/lib/postgresql/data
```

The comments say what the file is for. Line 3, `name: batch-integration-replace-a-file-drop`, prefixes every resource Compose creates. `services:` (line 5) lists the containers; `postgres:` is the first. `image: postgres:16` uses the official PostgreSQL 16 image. `ports` publishes container port 5432 on host port `${POSTGRES_PORT:-55561}` (default 55561, an unusual number so it does not collide with a PostgreSQL you may already run; `${NAME:-default}` means the variable's value or the default). `environment` sets the variables the image reads at first start to create the user, password and database; `POSTGRES_PASSWORD` uses `${POSTGRES_PASSWORD:?copy .env.example to .env first}`, so Compose refuses to start without a password and prints that message. `shm_size: 256mb` enlarges the shared memory PostgreSQL uses. `healthcheck`: `test` runs `pg_isready` inside the container (`CMD-SHELL` runs the text in a shell; `$${POSTGRES_USER}` has two dollars so Compose passes a single one to the shell inside the container instead of expanding it itself; `-h 127.0.0.1` checks over the network socket, which is only open once PostgreSQL has finished its start-up). It checks every 10 seconds (`interval`), allows 10 seconds per check (`timeout`), gives up after 30 failures (`retries`) and ignores failures for the first 5 seconds (`start_period`). `volumes` mounts the named volume `orderflow-data` at PostgreSQL's data folder.

```yaml
  rabbit:
    image: rabbitmq:3.13-management
    hostname: rabbit
    ports:
      - "${RABBIT_PORT:-56721}:5672"
      - "${RABBIT_MANAGEMENT_PORT:-56722}:15672"
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBIT_USER:-orderflow}
      RABBITMQ_DEFAULT_PASS: ${RABBIT_PASSWORD:?copy .env.example to .env first}
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 15s
      retries: 30
      start_period: 20s
    volumes:
      - rabbit-data:/var/lib/rabbitmq

  sftp:
    image: atmoz/sftp:alpine
    # user:password:uid:gid:folder - the folder "upload" is created inside the user's home and is writable.
    command: ["partner:${SFTP_PASSWORD:?copy .env.example to .env first}:::upload"]
    ports:
      - "${SFTP_PORT:-56222}:22"
    # No volume on the upload folder: a named volume mounts root-owned and the partner user could not write to it.
    # The folder lives in the container's own layer, which survives `docker restart`.
```

The broker and the SFTP server are explained in the RabbitMQ and SFTP notes. In short: `rabbitmq:3.13-management` with a stable `hostname` so its data survives restarts, ports 56721 and 56722, a health check `rabbitmq-diagnostics -q ping`, and the volume `rabbit-data`; and `atmoz/sftp:alpine` with the user `partner`, a writable folder `upload` and port 56222, deliberately without a volume.

```yaml
  # The two services of the new path, as containers built from this repository (make apps). The same image runs both;
  # the profile decides which one. restart: unless-stopped brings them back after `docker restart` or a crash.
  flow:
    build: .
    profiles: ["apps"]
    restart: unless-stopped
    depends_on:
      postgres: {condition: service_healthy}
      rabbit: {condition: service_healthy}
      sftp: {condition: service_started}
    environment: &app-environment
      SPRING_PROFILES_ACTIVE: flow
      DB_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-orderflow}
      DB_USER: ${POSTGRES_USER:-orderflow}
      DB_PASSWORD: ${POSTGRES_PASSWORD}
      RABBIT_HOST: rabbit
      RABBIT_PORT: "5672"
      RABBIT_USER: ${RABBIT_USER:-orderflow}
      RABBIT_PASSWORD: ${RABBIT_PASSWORD}
      SFTP_HOST: sftp
      SFTP_PORT: "22"
      SFTP_USER: partner
      SFTP_PASSWORD: ${SFTP_PASSWORD}
    command: ["--command=serve"]
```

The first application container. The comment says what it is: the two services of the new path, built from this repository, the same image running both, the profile choosing which. `build: .` builds the image from the `Dockerfile` in this folder. `profiles: ["apps"]` means Compose starts this service only when the profile is requested (`--profile apps`); plain `docker compose up` ignores it. `restart: unless-stopped` brings the container back after a crash or `docker restart`, and also after the Docker engine itself restarts (this matters: after a reboot the service containers start again by themselves, and will compete with a test run for the same SFTP folder and queues; stop them before testing). `depends_on` with `condition: service_healthy` waits for PostgreSQL and RabbitMQ to be healthy before starting (for the SFTP server it only waits for the container to have started). In `environment`, `&app-environment` defines a YAML **anchor**: a name for this block of settings so it can be reused. `SPRING_PROFILES_ACTIVE: flow` makes Spring Boot activate the `flow` profile. The `DB_*`, `RABBIT_*` and `SFTP_*` variables override the defaults of `application.yml` with the addresses *inside* the compose network: the host names are the service names (`postgres`, `rabbit`, `sftp`) and the ports are the container ports (5432, 5672, 22). `command: ["--command=serve"]` replaces the Dockerfile's default argument, so the container runs as a long-lived service instead of the health check.

```yaml
  order-service:
    build: .
    profiles: ["apps"]
    restart: unless-stopped
    depends_on:
      postgres: {condition: service_healthy}
      rabbit: {condition: service_healthy}
      sftp: {condition: service_started}
    environment:
      <<: *app-environment
      SPRING_PROFILES_ACTIVE: order-service
    command: ["--command=serve"]
```

The order service reuses the same settings: `<<: *app-environment` is a YAML **merge key** that copies every entry of the anchored block, and the next line, `SPRING_PROFILES_ACTIVE: order-service`, overrides the profile. So the two containers are the same image with one different setting.

```yaml
volumes:
  orderflow-data:
  rabbit-data:

networks:
  default:
    ipam:
      config:
        - subnet: 10.231.6.0/24
```

The top-level `volumes:` declares the two named volumes (an empty value means "use defaults"). The `networks` block configures the project's default network with a fixed address range (`ipam`, IP address management: `subnet: 10.231.6.0/24`). A fixed range avoids the "all predefined address pools have been fully subnetted" error when many projects run at once; each repository in this track uses its own range.

## Try it yourself

1. Start the infrastructure: `make up`. Expect `Healthy` for the postgres and rabbit containers and `Started` for sftp.
2. See the state: `docker compose ps` shows `healthy` in the STATUS column for the first two.
3. Start the application containers: `make apps`, then `docker compose ps` lists `flow` and `order-service`; `docker compose --profile apps ps` shows them too.
4. Check the merged result: `docker compose --profile apps config | grep -A3 SPRING_PROFILES_ACTIVE` shows one `flow` and one `order-service`.
5. Stop and delete: `make down`; `docker volume ls | grep -E "orderflow|rabbit"` prints nothing.

## Mistakes people make

- **Port already in use.** Two stacks that publish the same host port cannot both start. Each repository in this track uses its own high port.
- **Expecting data to persist after `down -v`.** The `-v` deletes volumes. That is the intent for a lesson database.
- **A health check that passes too early.** Checking only that the process runs, not that it accepts connections, lets `--wait` return before the database is usable.
- **Putting passwords in the file.** Here the password comes from `.env` through `${POSTGRES_PASSWORD:?...}`.
- **`localhost` inside a container.** It means the container itself. Containers reach each other by service name.

Specific to this repository: leaving the `apps` containers running while running the integration or end-to-end tests. They have `restart: unless-stopped`, so they come back by themselves after a reboot. Stop them with `docker compose --profile apps stop flow order-service` before testing.

## Check yourself

1. What does `${POSTGRES_PORT:-55561}` mean? *The variable's value, or 55561 if it is not set.*
2. What does `profiles: ["apps"]` do? *The service starts only when the profile is requested.*
3. What is `&app-environment` with `<<: *app-environment`? *A YAML anchor and a merge key: the second service copies the first one's settings and changes one.*
4. Why `$${POSTGRES_USER}` in the health check? *The double dollar passes a single dollar so the shell inside the container expands it.*
5. What does `restart: unless-stopped` do? *Restarts the container after a crash, `docker restart` or an engine restart, unless you stopped it yourself.*

## Further reading

- Compose file reference (Compose Specification): https://docs.docker.com/reference/compose-file/
- `docker compose up`: https://docs.docker.com/reference/cli/docker/compose/up/
- Interpolation (`${VAR:-default}`): https://docs.docker.com/reference/compose-file/interpolation/
- Healthchecks: https://docs.docker.com/reference/compose-file/services/#healthcheck
