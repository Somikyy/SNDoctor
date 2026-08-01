#!/usr/bin/env bash
# Prints the method references the compiled code emits, with their full JVM descriptors.
#
# Exists because a compile-only stub whose signature differs from the real Bukkit one compiles
# perfectly and then throws NoSuchMethodError on a live server: the return type is part of the
# descriptor, so `Object runTaskAsynchronously(...)` and `BukkitTask runTaskAsynchronously(...)`
# are two different methods as far as the JVM is concerned, and only one of them exists.
#
# References are read out of the constant pool (javap -v) rather than the disassembly, because
# javap omits the owner for calls to the current class - and inherited calls like getDataFolder()
# look exactly like that, which is precisely where a wrong stub would hide.
#
# Two checks use this:
#   * the self-test compares the default output against the recorded bukkit-api-surface.txt,
#     which catches an edited stub without needing the network;
#   * CI compares --all output of the offline build against the same sources built with the
#     real paper-api. That is what actually ties the stubs to reality; it needs both builds to
#     use the same JDK, otherwise javac version differences show up as noise.
#
# Usage: api-surface.sh <classes-dir> [--all]
#
#   default  org.bukkit-owned references only - small and stable, recorded in git
#   --all    every method reference, for the offline-versus-real-API diff in CI
set -euo pipefail

CLASSES="${1:?usage: api-surface.sh <classes-dir> [--all]}"
MODE="${2:-}"

refs() {
    find "$CLASSES" -name '*.class' -print0 \
        | xargs -0 javap -v \
        | grep -oE '= (Method|InterfaceMethod)ref .*// .*' \
        | sed -E 's|^.*// ||'
}

if [[ "$MODE" == "--all" ]]; then
    refs | sort -u
else
    refs | grep -E '^org/bukkit/' | sort -u
fi
