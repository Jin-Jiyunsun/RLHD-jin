#!/bin/bash
# Compile-check the worktree's shaders. Usage: run.sh
S="$(cd "$(dirname "$0")" && pwd)"
W=/c/Users/Jin/IdeaProjects/RLHD-jin
JH="/c/Users/Jin/.jdks/corretto-11.0.31"
CP="$(cat "$S/cp.txt")"
"$JH/bin/java" -cp "$CP" "$S/ShaderCheck.java" "$(cygpath -w "$W/src/main/resources/rs117/hd")" 2>&1 | grep -v "SLF4J\|^WARNING" | cut -c1-400
