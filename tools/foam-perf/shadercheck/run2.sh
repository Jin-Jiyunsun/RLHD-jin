#!/bin/bash
# Compile-check the worktree's shaders. Usage: run.sh
S="$(cd "$(dirname "$0")" && pwd)"
W="${1:-/c/Users/Jin/IdeaProjects/RLHD-jin/src/main/resources/rs117/hd}"
JH="/c/Users/Jin/.jdks/corretto-11.0.31"
CP="$(cat "$S/cp.txt")"
"$JH/bin/java" -cp "$CP" "$S/ShaderCheck.java" "$(cygpath -w "$W")" 2>&1 | grep -v "SLF4J\|^WARNING" | cut -c1-400
