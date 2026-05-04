.DEFAULT_GOAL := help
SHELL := /bin/bash

# General Variables
date=$(shell date +'%y.%m.%d.%H.%M')
project := Brain jar
container := dev
docker-file-check := /.dockerenv
docker-warning := ""
RED=\033[0;31m
GREEN=\033[0;32m
NC=\033[0m # No Color
versionPrefix := 0.2
version := $(versionPrefix).$(shell git rev-list HEAD --count)
git-short-hash := $(shell git rev-parse --short=8 HEAD)
version-suffix := ''
registry := rolfwessels/brain-jar

release := release
ifeq ($(env), dev)
	release := debug
	version-suffix:= ""
endif

ifdef GITHUB_BASE_REF
	current-branch :=  $(patsubst refs/heads/%,%,${GITHUB_HEAD_REF})
else ifdef GITHUB_REF
	current-branch :=  $(patsubst refs/heads/%,%,${GITHUB_REF})
else
	current-branch :=  $(shell git rev-parse --abbrev-ref HEAD)
endif

ifeq ($(current-branch), main)
	docker-tags := -t $(registry):latest -t $(registry):v$(version) -t $(registry):$(git-short-hash)
	version-full := $(version)
else
	version := $(versionPrefix).$(shell git rev-list main --count).$(shell git rev-list main..HEAD --count)
	version-full := $(version)-preview
	docker-tags := -t $(registry):$(git-short-hash) -t $(registry):v$(version-full) -t $(registry):latest
endif

# Docker Warning
ifeq ("$(wildcard $(docker-file-check))","")
	docker-warning = "⚠️  WARNING: Can't find /.dockerenv - it's strongly recommended that you run this from within the docker container."
endif

# Targets
help:
	@echo "The following commands can be used for building & running & deploying the $(project) container"
	@echo "---------------------------------------------------------------------------------------------"
	@echo "Targets:"
	@echo "  Docker Targets (run from local machine)"
	@echo "   - up          : brings up the container & attach to the default container ($(container))"
	@echo "   - down        : stops the container"
	@echo "   - build       : (re)builds the container"
	@echo ""
	@echo "  Service Targets (should only be run inside the docker container)"
	@echo "   - version               : Set current version number $(project)"
	@echo "   - start                 : Run the $(project)"
	@echo "   - test                  : Test the $(project)"
	@echo "   - publish               : Publish the $(project)"
	@echo "   - docker-login          : Login to docker registry"
	@echo "   - docker-build          : Build the docker image"
	@echo "   - docker-push           : Push the docker image"
	@echo "   - docker-pull-short-tag : Pull the docker image based in git short hash"
	@echo "   - docker-tag-env        : Tag the docker image based in the environment"
	@echo "   - docker-publish        : Publish the docker image"
	@echo "   - deploy                : Deploy the $(project)"
	@echo "   - push-memory           : stop perry, backup, rsync ~/.recall -> server, start perry"
	@echo "   - pull-memory           : rsync server recall -> local ~/.recall"
	@echo "   - backup-local-memory   : zip ~/.recall -> temp/perry-backups/ (gitignored)"
	@echo "   - backup-remote-memory  : zip server recall -> /opt/bot/perry/backups/"
	@echo "   - watchtower-update     : POST to Watchtower HTTP API (needs WATCHTOWER_BEARER_TOKEN)"
	@echo "   - update-packages       : Update the packages"
	@echo "   - pr-review             : Generate PR review summary"

	
	@echo ""
	@echo "Options:"
	@echo " - env    : sets the environment - supported environments are: dev | prod"
	@echo ""
	@echo "Examples:"
	@echo " - Start Docker Container              : make up"
	@echo " - Rebuild Docker Container            : make build"
	@echo " - Rebuild & Start Docker Container    : make build up"
	@echo " - publish and deploy                  : make publish deploy env=dev"

up:
	@echo "Starting containers..."
	@docker compose up -d
	@echo "Attaching shell..."
	@docker compose exec $(container) zsh

down:
	@echo "Stopping containers..."
	@docker compose down

build: down
	@echo "Building containers..."
	@docker compose build

version:
	@echo -e "Setting version number ${GREEN}v${version}${NC} "
	@echo '{ "version": "${version}" }' > src/main/resources/version.json

start: 
	@echo -e "Starting the $(release) release of $(project)"
	@set -a; [ -f .env ] && . ./.env; set +a; ./gradlew bootRun

test: 
	@echo -e "Testing ${GREEN}v${version}${NC}"
	@./gradlew test

publish: 
	@echo -e "Building the ${GREEN}v${version}${NC}-$(release) release of $(project)"
	@./gradlew bootJar -Pversion=$(version-full)

docker-login:
	echo -e "Login to docker $(registry)"; \
	read -p "Username: " docker_username; \
	read -s -p "Password (input hidden): " docker_password; echo ""; \
	echo "$$docker_password" | docker login --username "$$docker_username" --password-stdin; \


docker-build:
	@echo -e "Building branch ${RED}$(current-branch)${NC} to ${GREEN}$(docker-tags)${NC} with ${GREEN}$(version-full)${NC}"
	@docker build -f Dockerfile.deploy --build-arg VERSION=$(version-full) ${docker-tags} .

docker-push:
	@echo -e "Pushing to ${GREEN}$(docker-tags)${NC}"
	@docker push --all-tags $(registry)

docker-clean:
	@echo -e "Cleaning up local images for $(registry)"
	@docker images --format '{{.Repository}}:{{.Tag}}' \
	  | grep "^$(registry):" \
	  | grep -v ':<none>$$' \
	  | xargs -r docker rmi -f

docker-pull-short-tag:
	@echo -e "Pulling ${GREEN}$(registry):$(git-short-hash)${NC}"
	@docker pull "$(registry):$(git-short-hash)" 

docker-tag-env: env-check
	@echo -e "Tagging release ${GREEN}$(env)${NC}"
	@docker tag "$(registry):$(git-short-hash)" "$(registry):$(env)"	
	@docker images | grep "$(registry)"

docker-publish: docker-build docker-login docker-push docker-clean
	@echo -e "Done"

# Perry server: perry.bot.sels.co.za — recall at /opt/bot/perry/recall, backups at /opt/bot/perry/backups

# Not the same as backup-remote-memory alone: that only zips the server recall dir (Perry may be writing).
# Here: stop -> same zip as backup-remote-memory -> rsync local -> start.
stop-remote-perry:
	@echo "Stopping perry on perry.bot.sels.co.za ..."
	@ssh perry.bot.sels.co.za 'cd /opt/bot/perry && docker compose stop perry'

push-memory: stop-remote-perry
	@$(MAKE) -s backup-remote-memory
	@echo "Rsync ~/.recall -> server ..."
	@rsync -avz --delete "$$HOME/.recall/" perry.bot.sels.co.za:/opt/bot/perry/recall/
	@echo "Starting perry ..."
	@ssh perry.bot.sels.co.za 'cd /opt/bot/perry && docker compose up -d'

pull-memory:
	@echo "Rsync server recall -> ~/.recall ..."
	@rsync -avz perry.bot.sels.co.za:/opt/bot/perry/recall/ "$$HOME/.recall/"

backup-local-memory:
	@echo "Zip ~/.recall -> temp/perry-backups/ ..."
	@mkdir -p temp/perry-backups
	@(cd "$$HOME/.recall" && zip -qr "$(CURDIR)/temp/perry-backups/perry-recall-local-`date +%y%m%d-%H%M`.zip" .)

backup-remote-memory:
	@echo "Zip /opt/bot/perry/recall -> /opt/bot/perry/backups/ ..."
	@ssh perry.bot.sels.co.za 'mkdir -p /opt/bot/perry/backups && cd /opt/bot/perry/recall && zip -qr /opt/bot/perry/backups/perry-recall-backup-`date +%y%m%d-%H%M`.zip .'

watchtower-update:
	@set -a; [ -f .env ] && . ./.env; set +a; \
	if [ -z "$$WATCHTOWER_BEARER_TOKEN" ]; then \
	  echo "Set WATCHTOWER_BEARER_TOKEN in .env"; exit 1; \
	fi; \
	curl -sS -f -H "Authorization: Bearer $$WATCHTOWER_BEARER_TOKEN" -X POST http://watchtower.bot.sels.co.za/v1/update && echo ""

deploy: docker-build docker-push watchtower-update
	@echo -e "Deployed ${GREEN}v${version}${NC} of $(release)"

update-packages:
	@echo "Checking for dependency updates..."
	@./gradlew dependencyUpdates
	@echo "Update check complete."

pr-review:
	@echo "## PR Review Summary" > PR_REVIEW.md
	@echo "" >> PR_REVIEW.md
	@echo "### Branch" >> PR_REVIEW.md
	@echo "Current: $(current-branch)" >> PR_REVIEW.md
	@echo "Base: main" >> PR_REVIEW.md
	@echo "" >> PR_REVIEW.md
	@echo "### File Statistics" >> PR_REVIEW.md
	@git diff --stat origin/main...HEAD >> PR_REVIEW.md 2>/dev/null || echo "No diff available" >> PR_REVIEW.md
	@echo "" >> PR_REVIEW.md
	@echo "### Commits" >> PR_REVIEW.md
	@git log origin/main..HEAD --oneline >> PR_REVIEW.md 2>/dev/null || echo "No commits" >> PR_REVIEW.md
	@echo "" >> PR_REVIEW.md
	@echo "### Full Diff" >> PR_REVIEW.md
	@echo '```diff' >> PR_REVIEW.md
	@git diff origin/main...HEAD >> PR_REVIEW.md 2>/dev/null || echo "No diff available" >> PR_REVIEW.md
	@echo '```' >> PR_REVIEW.md
	@echo "PR_REVIEW.md generated."

docker-check:
	$(call assert-file-exists,$(docker-file-check), This step should only be run from Docker. Please run `make up` first.)

env-check:
	$(call check_defined, env, No environment set. Supported environments are: [ dev | prod ]. Please set the env variable. e.g. `make env=dev publish`)

check_defined = \
    $(strip $(foreach 1,$1, \
    	$(call __check_defined,$1,$(strip $(value 2)))))
__check_defined = \
    $(if $(value $1),, \
    	$(error Undefined $1$(if $2, ($2))))

define assert
  $(if $1,,$(error Assertion failed: $2))
endef

define assert_warn
  $(if $1,,$(warn Assertion failed: $2))
endef

define assert-file-exists
  $(call assert,$(wildcard $1),$1 does not exist. $2)
endef
