#!/bin/sh
# Builds the Linux native binary in Docker: the shaded jar with JDK 25, then
# GraalVM's native-image over it. Leaves target/orknux-shell.
set -eu
cd "$(dirname "$0")/.."
docker run --rm -v "$PWD:/src" -w /src -e HOME=/src/.cache/home eclipse-temurin:25-jdk \
  ./mvnw -B -q -Dmaven.repo.local=/src/.cache/m2 package -DskipTests
docker run --rm -v "$PWD:/src" -w /src ghcr.io/graalvm/native-image-community:25 \
  -jar target/orknux-shell.jar -o target/orknux-shell
ls -la target/orknux-shell
