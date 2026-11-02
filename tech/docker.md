# Docker

## What it is

Docker is a tool that runs programs in **containers**. A container is a running program packaged together with everything it needs (its own copy of the files, libraries and settings), isolated from the rest of the computer so it behaves the same on any machine. It is lighter than a virtual machine: containers share the host's operating-system kernel (on a Mac, Docker Desktop runs a small hidden Linux virtual machine and runs the containers inside it).

Words to know:

- An **image** is the frozen template: a stack of read-only file layers plus the command to run. You **pull** images from a registry (Docker Hub is the public one) or **build** them from a `Dockerfile`.
- A **container** is an image that is running (or stopped). You can start many containers from one image.
- A **volume** is storage managed by Docker that survives when the container is removed; without a volume, data written inside a container is lost with it.
- A **port mapping** `5432:5432` connects a port on your computer to a port inside the container. A **port** is a numbered doorway on a computer: a program listens on a port and other programs connect to it.
- A **Dockerfile** is the recipe for building an image, one instruction per line.

## Why this repository uses it

The lesson needs a real PostgreSQL, a real RabbitMQ and a real SFTP server, none of which a reader should have to install. Running them in containers means a reader installs only Docker, and `make down` leaves no trace. The repository also contains a `Dockerfile` that packages the program itself as one image, which runs as the flow, as the order service, or as a command-line tool, depending on its arguments.

## Where it appears

| Path | What it does with Docker |
| --- | --- |
| `Dockerfile` | Builds the one image of the program: multi-stage, non-root, pinned base images. |
| `.dockerignore` | Keeps `.git`, `target`, data and the `.env` file out of the build. |
| `docker-compose.yml` | Starts PostgreSQL (see the Docker Compose note). |
| `Makefile` | `make docker-build` builds the image; `make up`/`down` run Compose. |

## How it works underneath

`docker build` reads the Dockerfile from top to bottom. Each instruction creates a **layer**. Docker caches layers: if an instruction and everything before it are unchanged, the cached layer is reused, so putting rarely changing steps first (copy the dependency list, download dependencies) and often changing steps last (copy the source) makes rebuilds fast. A **multi-stage build** uses several `FROM` lines: the first stage has the heavy tools (the full JDK and Maven) and builds the program; the last stage starts from a small image and copies in only the finished result with `COPY --from=build`, so the final image is small and has no compilers. `USER` switches to a non-root user so that a break-in to the program does not own the container. `ENTRYPOINT` is the fixed command; `CMD` is its default arguments, which `docker run image --other-args` replaces.

A `.dockerignore` file lists files that must not be sent to the build (like `.gitignore` for the build context). It keeps the image small, speeds up the build and keeps secrets such as `.env` out of the image.

## Walkthrough of this repository's use

### `Dockerfile`

```dockerfile
# One image for every program of the project: the profile (flow, order-service) or --command picks which one runs.
#   docker run --rm --network host --env-file .env batch-integration-replace-a-file-drop:dev --command=health
FROM eclipse-temurin:21.0.5_11-jdk-jammy AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
COPY sql sql
RUN ./mvnw -q -B -DskipTests package
```

Lines 1 and 2 are comments, including the command to run the finished image: `--network host` shares the host's network so the program reaches the services on `localhost`; `--env-file .env` passes the variables; `--command=health` is the program's own argument. Line 3 starts the **build stage**: `FROM eclipse-temurin:21.0.5_11-jdk-jammy AS build` is the Eclipse Temurin JDK 21 on Ubuntu 22.04 ("jammy"), named `build`. Line 4 sets the working folder `/build` for the next instructions. Line 5 copies the `.mvn` folder (the wrapper's settings), and line 6 copies `mvnw` and `pom.xml`. Line 7, `RUN ./mvnw -q -B dependency:go-offline`, downloads every dependency now; because only the pom has been copied, this layer is reused until the pom changes. Line 8 copies the source, line 9 copies the `sql` folder (the build needs `reconcile.sql` and `reconcile_details.sql`, which the pom copies into the jar), and line 10 builds the jar skipping tests.

```dockerfile
FROM eclipse-temurin:21.0.5_11-jre-jammy
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /build/target/replace-a-file-drop-0.1.0.jar app.jar
RUN mkdir -p data/inbox && chown -R app data
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
CMD ["--command=health"]
```

Line 12 starts the **runtime stage** from a JRE image (only the Java runtime, no compiler). Line 13 creates a system user `app` with fixed id 10001 and a home folder. Line 14 sets `/app`. Line 15 copies the jar out of the build stage as `app.jar`. Line 16 creates the folders `data/inbox` (where the SFTP adapter keeps its local copy) and gives them to `app` (`chown -R` changes the owner of the folder and everything in it). Line 17, `USER app`, drops root. Line 18 is the fixed command: `java -XX:MaxRAMPercentage=70 -jar app.jar` (the first flag lets the JVM use up to 70 percent of the container's memory limit). Line 19 sets the default argument `--command=health`. The compose file overrides it with `--command=serve` for the two service containers and sets the profile with an environment variable.

### `.dockerignore`

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

Each line is a path that is not sent to the build: the git history, the build output (`target`), generated data and logs, the docs, the `.env` file with the database password, an editor state folder, and a generated folder.

## Try it yourself

1. Check Docker works: `docker version` shows a Client and a Server section.
2. Build the image: `make docker-build`. Expect lines ending with `naming to docker.io/library/batch-integration-replace-a-file-drop:dev`.
3. See the layers: `docker history batch-integration-replace-a-file-drop:dev | head` shows one row per instruction.
4. Check the user: `docker run --rm --entrypoint id batch-integration-replace-a-file-drop:dev` prints `uid=10001(app)`.
5. See that `.env` is not inside: `docker run --rm --entrypoint ls batch-integration-replace-a-file-drop:dev -a /app` shows `app.jar data` and no `.env`.

## Mistakes people make

- **Copying everything first.** `COPY . .` before installing dependencies invalidates the cache on every source change. Copy the dependency descriptors, install, then copy the source.
- **Running as root.** The default user in most images is root. This Dockerfile creates a user and switches with `USER`.
- **Unpinned base images.** `FROM java` changes under you. Pin a tag (`21.0.5_11-jdk-jammy`).
- **Secrets in the image.** Anything copied in or set with `ENV` is readable by anyone with the image. Secrets come from the environment at run time.
- **Storing data inside the container.** It disappears with the container; use a volume.

## Check yourself

1. Why two `FROM` lines? *A heavy build stage and a small runtime stage; only the jar is copied across.*
2. Why copy `pom.xml` before `src`? *So the dependency download layer is cached until the pom changes.*
3. What does `USER app` achieve? *The program runs without root rights.*
4. What is `.dockerignore` for? *To keep files out of the build context (history, secrets, build output).*
5. What is the difference between `ENTRYPOINT` and `CMD`? *`ENTRYPOINT` is the fixed command; `CMD` supplies default arguments that `docker run` can replace.*

## Further reading

- Dockerfile reference: https://docs.docker.com/reference/dockerfile/
- Multi-stage builds: https://docs.docker.com/build/building/multi-stage/
- Build cache: https://docs.docker.com/build/cache/
- `.dockerignore`: https://docs.docker.com/build/concepts/context/#dockerignore-files
