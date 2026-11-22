#!/bin/sh
set -eu
umask 077
cd /work
# Constrain native allocation arenas under the address-space limit.
export MALLOC_ARENA_MAX=2
IFS= read -r source
IFS= read -r harness
printf '%s' "$source" | base64 -d > Solution.java
printf '%s' "$harness" | base64 -d > CodeDuelCase.java
unset source harness
mkdir classes
if codeduel-sandbox-limits javac -J-XX:+UseSerialGC -J-XX:ActiveProcessorCount=1 -J-Xms16m -J-Xmx96m -J-Xss512k \
    -J-XX:ReservedCodeCacheSize=32m -J-XX:CompressedClassSpaceSize=32m -J-XX:MaxMetaspaceSize=64m \
    -J-XX:-UsePerfData -J-Djava.io.tmpdir=/work \
    -proc:none --release 21 -encoding UTF-8 -Xmaxerrs 8 -d classes Solution.java CodeDuelCase.java 1>&2
then
    exec codeduel-sandbox-limits java -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xms16m -Xmx64m -Xss256k \
        -XX:ReservedCodeCacheSize=32m -XX:CompressedClassSpaceSize=32m -XX:MaxMetaspaceSize=64m \
        -XX:-UsePerfData -XX:+ExitOnOutOfMemoryError -XX:-EnableDynamicAgentLoading \
        -Djava.io.tmpdir=/work -Dfile.encoding=UTF-8 -cp classes CodeDuelCase
else
    status=$?
    case "$status" in 14|137) exit "$status" ;; *) exit 10 ;; esac
fi
