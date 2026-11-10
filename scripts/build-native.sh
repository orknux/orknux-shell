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
# Java Sound's library (voice mode) cannot be linked into a native image, so the
# GraalVM JDK's own copy is carried inside it as a resource and written out on
# first use - one file to install, as before. CI does the same.
docker run --rm -v "$PWD:/src" -w /src --entrypoint sh ghcr.io/graalvm/native-image-community:25-ol8 -c '
  set -e
  mkdir -p target/native-sound/io/mszymanski/orknux/shell/sound
  cp "$JAVA_HOME/lib/libjsound.so" target/native-sound/io/mszymanski/orknux/shell/sound/
  native-image -cp target/orknux-shell.jar:target/native-sound io.mszymanski.orknux.shell.MainKt -o target/orknux-shell'
ls -la target/orknux-shell
