# syntax=docker/dockerfile:1

# Compile the shell to a native executable.
#
# GraalVM rather than a JRE at runtime: the server's own footprint should be
# noise next to the commands it runs, and a native image starts in
# milliseconds with tens of megabytes where the JVM took hundreds. The price
# is paid here, once, in compile minutes.
FROM ghcr.io/graalvm/native-image-community:25 AS build

WORKDIR /build

# The pom first, on its own layer. Dependencies change far less often than code
# does, so resolving them again on every source edit is wasted minutes.
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./

RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY src src

# Tests are not run here: a container build is the wrong place to find out one
# fails. CI runs them as their own job, and this image is only built once they
# pass.
RUN ./mvnw -B -ntp -Pnative package -DskipTests

# The runtime: a node image, deliberately.
#
# A coding harness is only as useful as the toolchain behind its shell, and
# this base hands agents node and npm along with the POSIX sh the contract
# requires. It is also what DesktopCommander needs — bundled below, so a
# deployment that wants DC's own stdio surface has it in the same container
# (`desktop-commander` on the PATH) rather than a second image to run.
FROM node:22-slim AS runtime

# git, because a coding harness without version control is half a harness — an
# agent clones, branches, diffs and commits through the shell — and the slim
# base leaves it out. ca-certificates so `git clone https://…` and `npm` can
# verify what they fetch. The MCP client is told git is here; see the
# initialize instructions and the start_process description.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g @wonderwhy-er/desktop-commander && npm cache clean --force

# Not root. The container is the real boundary around the process tools — a
# command an agent runs can see whatever this container can — so the container
# is kept small and unprivileged: a system user with a home for the toolchain
# to write its caches under.
RUN groupadd --system orknux && useradd --system --gid orknux --create-home orknux

WORKDIR /app

COPY --from=build --chown=orknux:orknux /build/target/orknux-shell ./orknux-shell

USER orknux

EXPOSE 8090

ENTRYPOINT ["/app/orknux-shell"]
