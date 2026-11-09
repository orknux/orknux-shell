#!/bin/sh
# Builds the Linux native binary in Docker: the shaded jar with JDK 25, then
# GraalVM's native-image over it. Leaves target/orknux-shell.
#
# The Oracle Linux 8 image on purpose: the binary is linked against the glibc
# of the machine that builds it, 8 is the oldest system JDK 25 is supported
# on, and a binary built there asks for nothing past glibc 2.17 - RHEL 7,
# Debian 8, Ubuntu 14.04 and everything since. CI builds in the same image; a
# change here is a change there.
set -eu
cd "$(dirname "$0")/.."
docker run --rm -v "$PWD:/src" -w /src -e HOME=/src/.cache/home eclipse-temurin:25-jdk \
  ./mvnw -B -q -Dmaven.repo.local=/src/.cache/m2 package -DskipTests
docker run --rm -v "$PWD:/src" -w /src ghcr.io/graalvm/native-image-community:25-ol8 \
  -jar target/orknux-shell.jar -o target/orknux-shell
ls -la target/orknux-shell
