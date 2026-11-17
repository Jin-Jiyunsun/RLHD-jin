#!/bin/bash
# Usage: wb.sh <rounds> <spec>...   (spec: name,dir,foam,lace,waves,wakes,boats[,ref]; dir "HD" is the repo's, "V:x" is vars/x)
S="$(cd "$(dirname "$0")" && pwd)"
HD="$(cygpath -w /c/Users/Jin/IdeaProjects/RLHD-jin/src/main/resources/rs117/hd)"
V="$(cygpath -w "$S/vars")"
rounds=$1; shift
BS=''
specs=(); for s in "$@"; do s="${s//,HD,/,$HD,}"; s="${s//,V:/,$V/}"; specs+=("$s"); done
mkdir -p "$S/out"; cd "$S/out"
/c/Users/Jin/.jdks/corretto-11.0.31/bin/java -cp "$(cat "$S/cp.txt")" "$S/WaterBench.java" $rounds "$(cygpath -w "$S/../wavesim/w16_ubo.f32")" "$(cygpath -w "$S/water_foam.jpg")" "${specs[@]}" 2>&1 | grep -v "SLF4J" | grep -v "INFO in" | grep -v "^WARNING" | grep -v "logback" | grep -v "^[[:space:]]*at " | grep -v "Caused by" | grep -v "common frames" | grep -v "^$" | cut -c1-300
