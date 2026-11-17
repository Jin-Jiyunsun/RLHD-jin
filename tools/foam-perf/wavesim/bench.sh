#!/bin/bash
# Interleave the shaders several times and keep each one's best time. Usage: bench.sh <span> <rounds> <shader>...
# Run from this folder. Needs ../shadercheck/cp.txt (see the README).
S="$(cd "$(dirname "$0")/.." && pwd)"
span=$1; rounds=$2; shift 2
args=(); for r in $(seq $rounds); do args+=("$@"); done
/c/Users/Jin/.jdks/corretto-17.0.19/bin/java -cp "$(cat $S/shadercheck/cp.txt)" LaceBench.java $span "${args[@]}" 2>&1 | grep -v WARN | awk '{t=$2; n=$1; if(!(n in best)||t<best[n]){best[n]=t}; all[n]=all[n]" "t; if(!(n in seen)){order[++k]=n; seen[n]=1}} END{for(i=1;i<=k;i++) printf "%-34s best %.3f   (%s)\n", order[i], best[order[i]], all[order[i]]}'
