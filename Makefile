# make up | db | apps | partner | status | reconcile | demo-* | test | lint | down   (make help lists all)
MVN := ./mvnw -q -B
JAR := target/replace-a-file-drop-0.1.0.jar
NIGHT ?= 1

POSTGRES_USER ?= orderflow
POSTGRES_DB ?= orderflow

-include .env
export

PSQL := docker compose exec -T postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB) -v ON_ERROR_STOP=1 -q
APP := java -jar $(JAR)

.PHONY: help env up db apps build partner partner-again legacy-load reconcile status queries reset purge demo-week \
        demo-restart demo-cutover logs health lint format test test-integration test-e2e audit check docker-build \
        down clean

help: ## list the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | sort

env:
	@[ -f .env ] || { cp .env.example .env; echo "created .env from .env.example"; }

up: env ## start PostgreSQL, RabbitMQ and the SFTP server
	docker compose up -d --wait postgres rabbit sftp

db: ## apply sql/001_schema.sql and the migrations (history of reconciliations)
	$(PSQL) < sql/001_schema.sql
	@for file in sql/migrations/*.sql; do echo "applying $$file"; $(PSQL) < $$file; done

$(JAR): pom.xml $(shell find src/main sql -type f)
	$(MVN) -DskipTests package

build: $(JAR) ## build the jar

apps: ## build the image and start the flow and the order service as containers (profile apps)
	docker compose --profile apps up -d --build flow order-service

partner: build ## drop a file of 10,000 orders on the SFTP server (NIGHT=1; the seed is the night number)
	$(APP) --command=partner --night=$(NIGHT) --seed=$(NIGHT)

partner-again: build ## resend the same file under a new name: no new order may appear
	$(APP) --command=partner --night=$(NIGHT) --seed=$(NIGHT) --name=orders-$(NIGHT)-again.csv

legacy-load: build ## the stand-in for the nightly batch: load the files it has not loaded yet into orders_batch
	$(APP) --command=legacy-load

reconcile: build ## the morning report: orders only in batch, only in events, different, same
	$(APP) --command=reconcile

status: build ## files on SFTP, queue depths, rows per path, ids remembered
	$(APP) --command=status

queries: ## run sql/002_queries.sql
	$(PSQL) < sql/002_queries.sql

reset: ## forget both paths' orders, the receiver's memory and the loaded files, and empty the partner's folder
	$(PSQL) < sql/reset.sql
	docker compose exec -T sftp sh -c 'rm -f /home/partner/upload/*.csv'

purge: ## empty the broker's queues
	docker compose exec -T rabbit rabbitmqctl purge_queue retail
	docker compose exec -T rabbit rabbitmqctl purge_queue wholesale
	docker compose exec -T rabbit rabbitmqctl purge_queue dead-letter
	docker compose exec -T rabbit rabbitmqctl purge_queue rejected

demo-week: build ## steps 3 and 5: a week of files through both paths, reconciled; a planted difference is caught
	sh scripts/demo_week.sh

demo-restart: build ## step 4: restart the broker and then the order service in the middle of a file; nothing lost or doubled
	sh scripts/demo_restart.sh

demo-cutover: build ## step 6: the retirement condition as a number, from the recorded reconciliations
	sh scripts/demo_cutover.sh

logs: ## the flow's and the order service's logs (needs make apps)
	docker compose logs --tail=40 flow order-service

health: build ## exit 0 when the database, the broker and the SFTP folder are reachable
	$(APP) --command=health

lint: ## spotless (google-java-format) check and the compiler's -Xlint:all -Werror
	$(MVN) spotless:check compile

format: ## apply google-java-format
	$(MVN) spotless:apply

test: ## unit and architecture tests with coverage (no services needed); the domain and application stay at 95%
	$(MVN) verify -DskipITs

test-integration: ## the adapters against the running services (make up and make db first)
	$(MVN) test-compile failsafe:integration-test failsafe:verify -Dfailsafe.groups=integration

test-e2e: ## the lesson's scenario in one JVM against the running services (make up and make db first)
	$(MVN) test-compile failsafe:integration-test failsafe:verify -Dfailsafe.groups=e2e

audit: ## check the dependencies against the OSS Index vulnerability database
	$(MVN) org.sonatype.ossindex.maven:ossindex-maven-plugin:audit

check: lint test ## everything that needs no services

docker-build: ## build the container image
	docker build -t batch-integration-replace-a-file-drop:dev .

down: ## stop everything and delete the data
	docker compose --profile apps down -v

clean: down
	rm -rf target data logs
