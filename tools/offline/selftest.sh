#!/usr/bin/env bash
# SNDoctor self-test.
#
# Builds synthetic plugin jars that contain exactly the byte patterns each rule looks for,
# runs the scanner over them, and asserts on the JSON output. No network, no JUnit.
#
# Needs nothing beyond a JDK, bash and perl - all three come with Git for Windows and with
# any Linux runner, so this suite runs unchanged on the developer's box and in CI.
#
# Why synthetic jars: a rule that "looks right" in code is worthless. The only way to know
# the constant-pool reader really sees "EntityPlayer" is to compile a class that references
# it and check that the finding appears.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="$ROOT/build/offline/jar/SNDoctor-offline.jar"
WORK="$ROOT/build/selftest"
FIX="$WORK/fixtures"
PLUGINS="$WORK/plugins"

if [[ ! -f "$JAR" ]]; then
  echo "build first: tools/offline/build-offline.sh" >&2
  exit 1
fi

rm -rf "$WORK"
mkdir -p "$FIX/src" "$FIX/stub-src" "$FIX/stubs" "$FIX/classes" "$PLUGINS"

# ---------------------------------------------------------------- fake server API
# These stand in for classes the real server provides. They are compiled so the fixture
# plugins can reference them, then thrown away - only the fixture classes are packed,
# so the jars carry the references without carrying the definitions. Exactly like a real
# plugin jar.
mk() { mkdir -p "$(dirname "$FIX/stub-src/$1")"; cat > "$FIX/stub-src/$1"; }

mk net/minecraft/server/level/EntityPlayer.java <<'EOF'
package net.minecraft.server.level;
public class EntityPlayer { public String name; }
EOF
mk net/minecraft/network/protocol/game/PacketPlayOutChat.java <<'EOF'
package net.minecraft.network.protocol.game;
public class PacketPlayOutChat { }
EOF
mk net/minecraft/nbt/NBTTagCompound.java <<'EOF'
package net.minecraft.nbt;
public class NBTTagCompound { }
EOF
mk org/bukkit/craftbukkit/v1_20_R3/CraftPlayer.java <<'EOF'
package org.bukkit.craftbukkit.v1_20_R3;
public class CraftPlayer { }
EOF
mk org/bukkit/conversations/Conversation.java <<'EOF'
package org.bukkit.conversations;
public class Conversation { public void begin() { } }
EOF
mk org/bukkit/metadata/MetadataValue.java <<'EOF'
package org.bukkit.metadata;
public interface MetadataValue { }
EOF
mk org/bukkit/event/player/PlayerSpawnLocationEvent.java <<'EOF'
package org.bukkit.event.player;
public class PlayerSpawnLocationEvent { }
EOF
mk io/papermc/paper/entity/TeleportFlag.java <<'EOF'
package io.papermc.paper.entity;
public interface TeleportFlag {
    enum EntityState implements TeleportFlag { RETAIN_PASSENGERS, RETAIN_OPEN_INVENTORY }
}
EOF
mk com/comphenix/protocol/ProtocolManager.java <<'EOF'
package com.comphenix.protocol;
public interface ProtocolManager { }
EOF

# Compiled from inside $FIX with relative paths: javac is a native binary under Git Bash
# and cannot resolve MSYS-style /d/... lines written into an @argfile. Same reason as in
# build-offline.sh.
( cd "$FIX" && find stub-src -name '*.java' > stub-sources.txt \
    && javac -nowarn -encoding UTF-8 --release 17 -d stubs "@stub-sources.txt" )

# ---------------------------------------------------------------- fixture plugins
src() { mkdir -p "$(dirname "$FIX/src/$1")"; cat > "$FIX/src/$1"; }

src fixture/legacy/LegacyMain.java <<'EOF'
package fixture.legacy;

import net.minecraft.server.level.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import org.bukkit.craftbukkit.v1_20_R3.CraftPlayer;

public class LegacyMain {
    private static final String NMS_VERSION = "v1_20_R3";
    public EntityPlayer handle;
    public NBTTagCompound tag;
    public CraftPlayer craft;
    public String version() { return "org.bukkit.craftbukkit." + NMS_VERSION + ".CraftServer"; }
}
EOF

src fixture/modern/ModernMain.java <<'EOF'
package fixture.modern;

public class ModernMain {
    public String hello() { return "nothing scary here"; }
}
EOF

src fixture/deprecated/DeprecatedMain.java <<'EOF'
package fixture.deprecated;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.conversations.Conversation;
import org.bukkit.event.player.PlayerSpawnLocationEvent;
import org.bukkit.metadata.MetadataValue;

public class DeprecatedMain {
    public Conversation conversation;
    public MetadataValue meta;
    public PlayerSpawnLocationEvent event;
    public TeleportFlag.EntityState flag = TeleportFlag.EntityState.RETAIN_PASSENGERS;
}
EOF

src fixture/deps/DepsMain.java <<'EOF'
package fixture.deps;

import com.comphenix.protocol.ProtocolManager;

public class DepsMain {
    public ProtocolManager manager;
}
EOF

src fixture/sus/SusMain.java <<'EOF'
package fixture.sus;

import java.io.IOException;

public class SusMain {
    private static final String ENDPOINT = "http://185.199.108.153:8080/collect";
    private static final String SECRET_FILE = "rcon.password";

    public void run() throws IOException {
        Runtime.getRuntime().exec("uname -a");
    }
    public String endpoint() { return ENDPOINT + SECRET_FILE; }
}
EOF

# One class squatting inside a well-known library package - the masquerading heuristic.
src org/apache/commons/lang3/MutableUtilities.java <<'EOF'
package org.apache.commons.lang3;

public final class MutableUtilities {
    public static void init() { }
}
EOF

( cd "$FIX" && find src -name '*.java' > sources.txt \
    && javac -nowarn -encoding UTF-8 --release 17 -cp stubs -d classes "@sources.txt" )

pack() { # pack <jar-name> <plugin.yml-or-NONE> <class-dir-glob...>
  local name="$1"; shift
  local yml="$1"; shift
  local stage="$WORK/stage-$name"
  rm -rf "$stage"; mkdir -p "$stage"
  for path in "$@"; do
    mkdir -p "$stage/$(dirname "$path")"
    cp -r "$FIX/classes/$path" "$stage/$path"
  done
  if [[ "$yml" != "NONE" ]]; then
    printf '%s\n' "$yml" > "$stage/plugin.yml"
  fi
  (cd "$stage" && jar --create --file "$PLUGINS/$name.jar" .)
}

pack LegacyPlugin "name: LegacyPlugin
version: '1.0'
main: fixture.legacy.LegacyMain" fixture/legacy

pack ModernPlugin "name: ModernPlugin
version: '2.3.1'
main: fixture.modern.ModernMain
api-version: '1.21'
folia-supported: true" fixture/modern

pack DeprecatedPlugin "name: DeprecatedPlugin
version: '0.9'
main: fixture.deprecated.DeprecatedMain
api-version: '1.21'" fixture/deprecated

pack DepsPlugin "name: DepsPlugin
version: '1.2'
main: fixture.deps.DepsMain
api-version: '1.20'
depend: [ProtocolLib]" fixture/deps

pack SusPlugin "name: SusPlugin
version: '1.0'
main: fixture.sus.SusMain
api-version: '1.21'" fixture/sus org/apache/commons/lang3

pack PlainLibrary NONE fixture/modern

pack BrokenMain "name: BrokenMain
version: '1.0'
main: fixture.does.not.Exist
api-version: '1.21'" fixture/modern

# A file that is not a zip at all.
printf 'this is not a jar' > "$PLUGINS/Corrupt.jar"

# ---------------------------------------------------------------- run and assert
echo "==> scanning $(ls "$PLUGINS" | wc -l) fixture jars"
set +e
java -jar "$JAR" "$PLUGINS" --java 21 --no-color --json "$WORK/report.json" --out "$WORK/report.txt" > "$WORK/console.txt"
EXIT=$?
set -e

FAILED=0
# The report is checked with Perl and core JSON::PP rather than Python: perl ships inside
# Git for Windows itself, so `bash tools/offline/verify.sh` runs on the machine the plugin
# is developed on without installing anything. A gate that only works in CI is not a gate.
assert() { # assert <description> <perl expression returning true/false>
  local desc="$1"; shift
  if perl - "$WORK/report.json" "$@" <<'PL'
use strict;
use warnings;
use JSON::PP;

my ($file, $expr) = @ARGV;
open my $fh, '<:raw', $file or die "cannot open $file: $!\n";
my $R = decode_json(do { local $/; <$fh> });
my %P = map { $_->{name} => $_ } @{ $R->{plugins} };

sub p       { $P{ $_[0] } or die "no such plugin in report: $_[0]\n" }
sub verdict { p( $_[0] )->{verdict} }
sub has     { my ($n, $id) = @_; scalar grep { $_->{id} eq $id } @{ p($n)->{findings} } }
sub all_sev { my ($n, $s) = @_; !grep { $_->{severity} ne $s } @{ p($n)->{findings} } }

my $ok = eval $expr;
if ($@) { print STDERR "  !! bad assertion [$expr]: $@"; exit 2 }
exit( $ok ? 0 : 1 );
PL
  then
    echo "  ok   $desc"
  else
    echo "  FAIL $desc"
    FAILED=$((FAILED + 1))
  fi
}

echo "==> assertions"
assert "LegacyPlugin is RED"                     "verdict('LegacyPlugin') eq 'RED'"
assert "  detects Spigot mappings"               "has('LegacyPlugin', 'nms.spigot-mappings')"
assert "  detects versioned CraftBukkit"         "has('LegacyPlugin', 'craftbukkit.versioned')"
assert "  detects vX_Y_RZ version reflection"    "has('LegacyPlugin', 'nms.version-reflection')"
assert "  detects missing api-version"           "has('LegacyPlugin', 'meta.no-api-version')"

assert "ModernPlugin is GREEN"                   "verdict('ModernPlugin') eq 'GREEN'"
assert "  no blocker/breaking/warn findings"     "all_sev('ModernPlugin', 'INFO')"
assert "  Folia flag picked up"                  "p('ModernPlugin')->{foliaSupported}"
assert "  version parsed from plugin.yml"        "p('ModernPlugin')->{version} eq '2.3.1'"

assert "DeprecatedPlugin is YELLOW"              "verdict('DeprecatedPlugin') eq 'YELLOW'"
assert "  Conversation API"                      "has('DeprecatedPlugin', 'api.conversation')"
assert "  Metadata API"                          "has('DeprecatedPlugin', 'api.metadata')"
assert "  PlayerSpawnLocationEvent"              "has('DeprecatedPlugin', 'api.player-spawn-location-event')"
assert "  TeleportFlag.EntityState"              "has('DeprecatedPlugin', 'api.teleport-flag-entitystate')"

assert "DepsPlugin flags ProtocolLib"            "has('DepsPlugin', 'dep.protocollib')"

assert "SusPlugin: process execution"            "has('SusPlugin', 'sec.process-execution')"
assert "SusPlugin: raw-IP endpoint"              "has('SusPlugin', 'sec.suspicious-endpoints')"
assert "SusPlugin: server secret file"           "has('SusPlugin', 'sec.server-files')"
assert "SusPlugin: masquerading package"         "has('SusPlugin', 'sec.masquerading-package')"
assert "SusPlugin verdict unaffected by security" "verdict('SusPlugin') eq 'GREEN'"

assert "PlainLibrary detected as not-a-plugin"   "has('PlainLibrary', 'meta.not-a-plugin')"
assert "BrokenMain missing main class"           "has('BrokenMain', 'meta.main-class-missing')"
assert "BrokenMain is RED"                       "verdict('BrokenMain') eq 'RED'"
assert "Corrupt.jar skipped, not fatal"          "verdict('Corrupt') eq 'SKIPPED'"

assert 'exit code reflects worst verdict'        '$R->{summary}{red} >= 1'

CLASSES="$ROOT/build/offline/classes"

# ---------------------------------------------------------------- Bukkit API surface
# The offline build compiles against hand-written stubs, and a stub whose signature differs
# from the real Bukkit one is invisible at compile time: the return type is part of the JVM
# method descriptor, so `Object runTaskAsynchronously(...)` compiles cleanly and then dies on
# a live server with NoSuchMethodError. That shipped once. Never again silently.
#
# This is the offline half of the guard: the descriptors the build emits must equal the ones
# recorded in git. CI runs the other half, comparing the same sources built against the real
# paper-api - that is what ties the recorded file to reality.
echo "==> Bukkit API surface"
bash "$ROOT/tools/offline/api-surface.sh" "$CLASSES" > "$WORK/api-surface.txt"
if diff -u "$ROOT/tools/offline/bukkit-api-surface.txt" "$WORK/api-surface.txt" \
        > "$WORK/api-surface.diff" 2>&1; then
  echo "  ok   emitted Bukkit descriptors match tools/offline/bukkit-api-surface.txt"
else
  echo "  FAIL emitted Bukkit descriptors drifted from the recorded surface:"
  sed 's/^/         /' "$WORK/api-surface.diff"
  echo "         If the change is deliberate, re-record it:"
  echo "         bash tools/offline/api-surface.sh build/offline/classes > tools/offline/bukkit-api-surface.txt"
  FAILED=$((FAILED + 1))
fi

# ---------------------------------------------------------------- shipped config.yml
# config.yml is read by SNDoctor's own MiniYaml, not by Bukkit, and its header is a wall of
# box-drawing comments. A stray quote or colon in that banner would not raise anything - it
# would quietly hand back every default instead, and the admin's settings would be ignored
# with no error to go on. So the real loader is run against the real file.
echo "==> shipped config.yml"
mkdir -p "$WORK/probe"
cat > "$WORK/probe/ConfigProbe.java" <<'EOF'
package network.somikyy.sndoctor.bukkit;

import java.nio.file.Path;

public class ConfigProbe {
    public static void main(String[] args) throws Exception {
        SNDoctorConfig c = SNDoctorConfig.load(Path.of(args[0]));
        System.out.println("language=" + (c.russian ? "ru" : "en"));
        System.out.println("update-check=" + c.updateCheck);
        System.out.println("scan.on-start=" + c.scanOnStart);
        System.out.println("scan.start-delay=" + c.startDelayTicks);
        System.out.println("reports.json=" + c.reportsJson);
        System.out.println("reports.keep=" + c.reportsKeep);
    }
}
EOF
# Compiled straight into the offline class output so the classpath stays a single entry -
# ':' and ';' separators differ between Linux and Git Bash and are not worth the trouble.
javac -nowarn -encoding UTF-8 --release 17 -cp "$CLASSES" -d "$CLASSES" "$WORK/probe/ConfigProbe.java"
java -cp "$CLASSES" -Dfile.encoding=UTF-8 \
    network.somikyy.sndoctor.bukkit.ConfigProbe \
    "$ROOT/src/main/resources/config.yml" > "$WORK/config.txt"

expect() { # expect <description> <exact line the loader must have produced>
  if grep -qxF -- "$2" "$WORK/config.txt"; then
    echo "  ok   $1"
  else
    echo "  FAIL $1 (expected '$2')"
    FAILED=$((FAILED + 1))
  fi
}

expect "banner does not break parsing: language" "language=ru"
expect "  update-check read"                     "update-check=true"
expect "  scan.on-start read"                    "scan.on-start=true"
expect "  scan.start-delay read as a number"     "scan.start-delay=100"
expect "  reports.json read"                     "reports.json=true"
expect "  reports.keep read as a number"         "reports.keep=20"

echo
if [[ $FAILED -eq 0 ]]; then
  echo "ALL ASSERTIONS PASSED (cli exit code was $EXIT, expected 2)"
  [[ $EXIT -eq 2 ]] || { echo "but exit code was wrong"; exit 1; }
else
  echo "$FAILED assertion(s) failed"
  exit 1
fi
