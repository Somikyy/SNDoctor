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

# ---------------------------------------------------------------- one conversion per line
# The in-server layer converts colours in exactly one place: Texts, which knows which sink it is
# rendering for. SNDoctorPlugin reaching for Colors itself is how the startup scan came to strip
# the colours out of lines that had already been converted for chat - a second pass that eats an
# ampersand the admin wrote and a dropped tag left in front of a code letter. Nothing here needs
# the class directly, so the grep is the rule.
echo "==> colour conversion stays in Texts"
if grep -n "Colors\." "$ROOT/src/main/java/network/somikyy/sndoctor/bukkit/SNDoctorPlugin.java"; then
  echo "  FAIL SNDoctorPlugin converts colours itself; render through Texts.chat / Texts.plain"
  FAILED=$((FAILED + 1))
else
  echo "  ok   the plugin renders text through Texts only, never through Colors directly"
fi

# ---------------------------------------------------------------- shipped config.yml
# config.yml is read by SNDoctor's own MiniYaml, not by Bukkit, and its header is a wall of
# box-drawing comments. A stray quote or colon in that banner would not raise anything - it
# would quietly hand back every default instead, and the admin's settings would be ignored
# with no error to go on. So the real loader is run against the real file.
echo "==> shipped config.yml"

# Probes are compiled into a COPY of the class output, never into it.
#
# They live in the same packages as the code they poke at, so they need the same classpath -
# but the CI check compares the offline classes against the same sources built with the real
# paper-api, and Gradle compiles only src/main/java. A probe left in build/offline/classes
# shows up as a difference and fails that comparison for no reason. It did exactly that once.
#
# A copy also keeps the classpath a single entry, which matters because ':' and ';' separators
# differ between Linux and Git Bash.
PROBE_CP="$WORK/probe-classes"
rm -rf "$PROBE_CP"
cp -r "$CLASSES" "$PROBE_CP"
# The compile-only Bukkit stubs go into the copy as well. A probe that pokes at bukkit/ cannot
# even load the class otherwise - SNDoctorPlugin extends JavaPlugin, and resolving a superclass
# happens before any of our code runs. These are the same stubs the offline build compiles
# against, they never reach the jar, and on a real server the server supplies the originals.
cp -r "$ROOT/build/offline/stubs/." "$PROBE_CP/"
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
javac -nowarn -encoding UTF-8 --release 17 -cp "$PROBE_CP" -d "$PROBE_CP" "$WORK/probe/ConfigProbe.java"
java -cp "$PROBE_CP" -Dfile.encoding=UTF-8 \
    network.somikyy.sndoctor.bukkit.ConfigProbe \
    "$ROOT/src/main/resources/config.yml" > "$WORK/config.txt"

expect() { # expect <description> <exact line the probe must have produced>, read from $EXPECT_FILE
  if grep -qxF -- "$2" "$EXPECT_FILE"; then
    echo "  ok   $1"
  else
    echo "  FAIL $1 (expected '$2')"
    FAILED=$((FAILED + 1))
  fi
}

EXPECT_FILE="$WORK/config.txt"
expect "banner does not break parsing: language" "language=ru"
expect "  update-check read"                     "update-check=true"
expect "  scan.on-start read"                    "scan.on-start=true"
expect "  scan.start-delay read as a number"     "scan.start-delay=100"
expect "  reports.json read"                     "reports.json=true"
expect "  reports.keep read as a number"         "reports.keep=20"

# ---------------------------------------------------------------- console encoding
# A Russian Windows console runs on cp866. Writing the report there as UTF-8 produces
# mojibake, and writing it as cp866 loses every character that page lacks - Java replaces
# each one with a question mark and says nothing. Both were happening: the first report a
# real admin ran came out unreadable.
#
# The property that matters: after fitting, the text must be FULLY encodable in the target
# charset, so the encoder never has to substitute anything behind our back.
echo "==> console encoding"
# Exit code is 2 by design (the fixtures contain red plugins), so do not let -e stop us.
java -Dstdout.encoding=IBM866 -jar "$JAR" "$PLUGINS" --java 21 > "$WORK/cp866.out" 2>&1 || true

cat > "$WORK/probe/ConsoleProbe.java" <<'EOF'
package network.somikyy.sndoctor.cli;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConsoleProbe {

    /** ESC[ - the start of every ANSI colour sequence, as an escape so no raw control
     *  byte has to survive living inside a shell script. */
    private static final String ANSI_CSI = "\u001B[";

    public static void main(String[] args) throws Exception {
        // Every decoration the renderer uses, plus a Cyrillic word. Compiled with
        // -encoding UTF-8, same as the real sources.
        String sample = "● ✗ — «q» • · → ─ "
                + "проверка";

        for (String name : new String[]{"IBM866", "windows-1251"}) {
            Charset cs = Charset.forName(name);
            String fitted = ConsoleText.fitTo(sample, cs);
            CharsetEncoder encoder = cs.newEncoder();
            System.out.println(name + ".encodable=" + encoder.canEncode(fitted));
            System.out.println(name + ".lossy=" + fitted.contains("?"));
        }
        System.out.println("utf8.untouched="
                + ConsoleText.fitTo(sample, Charset.forName("UTF-8")).equals(sample));

        // End to end: what the CLI actually wrote must decode back to readable Russian.
        String report = new String(Files.readAllBytes(Path.of(args[0])), Charset.forName("IBM866"));
        System.out.println("cli.readable="
                + report.contains("проверка"));
        System.out.println("cli.no-ansi=" + !report.contains(ANSI_CSI));
    }
}
EOF
javac -nowarn -encoding UTF-8 --release 17 -cp "$PROBE_CP" -d "$PROBE_CP" "$WORK/probe/ConsoleProbe.java"
java -cp "$PROBE_CP" network.somikyy.sndoctor.cli.ConsoleProbe "$WORK/cp866.out" > "$WORK/console.checks"

EXPECT_FILE="$WORK/console.checks"
expect "cp866 output is fully encodable"        "IBM866.encodable=true"
expect "  nothing silently became a '?'"        "IBM866.lossy=false"
expect "cp1251 output is fully encodable"       "windows-1251.encodable=true"
expect "  nothing silently became a '?'"        "windows-1251.lossy=false"
expect "UTF-8 output is left alone"             "utf8.untouched=true"
expect "report written to a cp866 console reads back as Russian" "cli.readable=true"
expect "  and carries no ANSI escapes when redirected"           "cli.no-ansi=true"

# ---------------------------------------------------------------- messages and colours
# Texts live in messages.yml instead of in Java literals, which means a rule can now be added
# with no text at all and nothing would complain at compile time - the report would just print
# the raw key at the reader. So the code is the source of truth here: every id that Analyzer
# can emit must have all three texts in both languages, and nothing else may sit in the file
# pretending to be a rule.
#
# All of it is asserted by a Java probe rather than by grep. The file is YAML now, and a grep
# that walks indentation is a second, worse parser: what has to be true is what MiniYaml and
# Messages actually load, including the admin's overrides and the .txt migration, neither of
# which a grep can see at all.
echo "==> messages and colours"
ANALYZER="$ROOT/src/main/java/network/somikyy/sndoctor/core/Analyzer.java"
grep -o 'newFinding("[^"]*"' "$ANALYZER" | sed 's/newFinding("//; s/"$//' | sort -u > "$WORK/rule-ids.txt"

# The other direction, and the one that actually bites: the code asks for its keys by string
# literal, so a typo there compiles, ships, and prints "chat.summray.red" at a player - or
# "ui.fotoer" in the middle of the report an admin opens. Every chat.* / update.* / ui.* key
# any layer mentions must exist in both languages.
#
# The whole source tree, not a list of packages: the report labels used to be grepped in
# neither direction because only bukkit/ was listed here, and naming packages is how that
# happens again the next time one is added.
grep -rhoE '"(chat|update|ui)\.[a-z][a-z.-]*[a-z]"' "$ROOT/src/main/java" \
  | tr -d '"' | sort -u > "$WORK/used-keys.txt"

cat > "$WORK/probe/MessagesProbe.java" <<'EOF'
package network.somikyy.sndoctor.core;

import network.somikyy.sndoctor.report.TextRenderer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asserts the messages contract against the real bundled files and the real loader.
 *
 * <p>Prints one "name=true" line per check, or "name=false" plus what came out instead, so the
 * shell can assert on exact lines and still print something useful when one of them is not true.
 */
public class MessagesProbe {

    private static final Pattern HOLE = Pattern.compile("\\{[a-z]+}");

    public static void main(String[] args) throws Exception {
        List<String> ruleIds = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        List<String> usedKeys = Files.readAllLines(Path.of(args[2]), StandardCharsets.UTF_8);
        Messages m = Messages.bundled();

        List<String> unknown = new ArrayList<>();
        for (String key : usedKeys) {
            if (!key.isBlank() && (!m.has(key.trim(), true) || !m.has(key.trim(), false))) {
                unknown.add(key.trim());
            }
        }
        check("chat.every-key-the-plugin-asks-for-exists",
                !usedKeys.isEmpty() && unknown.isEmpty(),
                usedKeys.size() + " used, unknown=" + unknown);

        // The grep that feeds this list once covered only the bukkit package, so every ui.*
        // label of the report was checked in neither direction. Assert on the shape of the
        // list itself, or narrowing that grep again would go unnoticed exactly as it did then.
        long uiKeys = usedKeys.stream().filter(k -> k.startsWith("ui.")).count();
        long chatKeys = usedKeys.stream().filter(k -> k.startsWith("chat.")).count();
        check("chat.report-labels-are-checked", uiKeys > 0 && chatKeys > 0,
                "ui=" + uiKeys + " chat=" + chatKeys);

        check("bundle.not-empty", !m.keys().isEmpty(), String.valueOf(m.keys().size()));

        List<String> missing = new ArrayList<>();
        for (String key : m.keys()) {
            if (!m.has(key, true)) {
                missing.add("ru:" + key);
            }
            if (!m.has(key, false)) {
                missing.add("en:" + key);
            }
        }
        check("bundle.parity", missing.isEmpty(), missing.toString());

        // Every rule Analyzer can emit has all three texts, in both languages.
        List<String> noText = new ArrayList<>();
        for (String id : ruleIds) {
            if (id.isBlank()) {
                continue;
            }
            for (String part : new String[]{"title", "why", "fix"}) {
                String key = "rule." + id.trim() + "." + part;
                if (!m.has(key, true)) {
                    noText.add("ru:" + key);
                }
                if (!m.has(key, false)) {
                    noText.add("en:" + key);
                }
            }
        }
        check("rules.every-id-has-texts", noText.isEmpty(), noText.toString());

        // Keys nobody asks for are worse than useless: they read as coverage that is not there.
        Set<String> known = new TreeSet<>();
        for (String id : ruleIds) {
            known.add(id.trim());
        }
        List<String> orphans = new ArrayList<>();
        for (String key : m.keys()) {
            if (!key.startsWith("rule.")) {
                continue;
            }
            if (!known.contains(key.substring("rule.".length(), key.lastIndexOf('.')))) {
                orphans.add(key);
            }
        }
        check("rules.no-orphan-texts", orphans.isEmpty(), orphans.toString());

        // A translator who drops a {hole} produces a sentence missing its number, and nothing
        // else would catch it. Both languages must therefore use the same set.
        List<String> holeDrift = new ArrayList<>();
        for (String key : m.keys()) {
            if (!holes(m.get(key, true)).equals(holes(m.get(key, false)))) {
                holeDrift.add(key);
            }
        }
        check("bundle.same-placeholders", holeDrift.isEmpty(), holeDrift.toString());

        // ---- the prefix contract. One key, resolved on every lookup, and emptying it is the
        // supported way to take the plugin name out of every chat line - that is the whole
        // reason the key exists, so it gets fixtures rather than a comment.
        check("prefix.shipped", m.has("prefix", true) && m.has("prefix", false), "");
        List<String> unresolved = new ArrayList<>();
        for (String key : m.keys()) {
            if (m.get(key, true).contains("{prefix}") || m.get(key, false).contains("{prefix}")) {
                unresolved.add(key);
            }
        }
        check("prefix.always-resolved", unresolved.isEmpty(), unresolved.toString());
        check("prefix.in-front",
                m.get("chat.no-permission", true).startsWith(m.get("prefix", true)),
                m.get("chat.no-permission", true));
        // Report texts must not carry it: the report prints its own header and is not chat.
        check("prefix.not-in-report", !m.get("ui.header", true).contains(m.get("prefix", true)),
                m.get("ui.header", true));

        Path folder = Files.createTempDirectory("sndoctor-msg");
        try {
            Path file = folder.resolve("messages.yml");
            Files.writeString(file,
                    "prefix: ''\nchat:\n  no-permission: 'нельзя'\n", StandardCharsets.UTF_8);
            Messages edited = Messages.load(file);
            check("override.empty-prefix-stays-empty", edited.get("prefix", true).isEmpty(),
                    "[" + edited.get("prefix", true) + "]");
            check("override.wins", edited.get("chat.no-permission", true).equals("нельзя"),
                    edited.get("chat.no-permission", true));
            check("override.untouched-key-falls-back",
                    edited.get("ui.error", true).equals(m.get("ui.error", true)),
                    edited.get("ui.error", true));
            check("override.partial-file-keeps-the-rest",
                    edited.get("rule.dep.vault.title", true)
                            .equals(m.get("rule.dep.vault.title", true)),
                    edited.get("rule.dep.vault.title", true));

            // Migration off the 26.8.1 .txt format: the admin's lines survive, and so does the
            // documentation around them - the file must stay readable, not become a dump.
            Path fresh = folder.resolve("fresh");
            Files.createDirectories(fresh);
            Files.writeString(fresh.resolve("messages-ru.txt"),
                    "# комментарий\nui.error=сломалось:\n"
                            + "rule.dep.vault.title=Зависит от Vault (правка админа)\n",
                    StandardCharsets.UTF_8);
            List<String> log = Messages.install(fresh, true);
            check("install.reports-what-it-did", log.size() == 2, log.toString());
            String written = Files.readString(fresh.resolve("messages.yml"),
                    StandardCharsets.UTF_8);
            check("install.keeps-comments", written.contains("подписи отчёта"), "");
            check("install.keeps-header", written.contains("ЦВЕТА"), "");
            check("install.migrated-value-landed", written.contains("'сломалось:'"), "");
            check("install.migrated-nested-value-landed",
                    written.contains("'Зависит от Vault (правка админа)'"), "");
            Messages migrated = Messages.load(fresh.resolve("messages.yml"));
            check("install.migrated-text-is-what-loads",
                    migrated.get("ui.error", true).equals("сломалось:"),
                    migrated.get("ui.error", true));
            check("install.second-run-leaves-the-file-alone",
                    Messages.install(fresh, true).isEmpty(), "");

            // «Ваши 1 строк» in the server log reads as a plugin nobody finished, and this is
            // the first line an admin sees on upgrade. Three forms, and the 11-14 exception is
            // why a bare n % 10 is not enough - so all three get a fixture.
            String one = migrationLine(folder, "one", 1);
            String few = migrationLine(folder, "few", 3);
            String many = migrationLine(folder, "many", 11);
            check("install.plural-one", one.contains("1 строка"), one);
            check("install.plural-few", few.contains("3 строки"), few);
            check("install.plural-eleven",
                    many.contains("11 строк") && !many.contains("11 строки"), many);

            // ---- the language stamp. Once messages.yml exists it covers every key, so the
            // language flag no longer chooses the language - it only chose which bundle the
            // file was seeded from. An admin who flips it to en and sees no change has nothing
            // to go on, which is what the stamp and the warning are for.
            check("language.bundles-are-stamped",
                    m.get("language", true).equals("ru") && m.get("language", false).equals("en"),
                    m.get("language", true) + "/" + m.get("language", false));
            check("language.stamp-lands-in-the-written-file",
                    written.contains("language: ru"), "");
            Messages stamped = Messages.load(fresh.resolve("messages.yml"));
            check("language.declared-is-read-back", "ru".equals(stamped.declaredLanguage()),
                    String.valueOf(stamped.declaredLanguage()));
            check("language.no-complaint-when-they-agree",
                    stamped.languageMismatch(true) == null,
                    String.valueOf(stamped.languageMismatch(true)));
            String complaint = stamped.languageMismatch(false);
            check("language.mismatch-is-reported",
                    complaint != null && complaint.contains("messages.yml"),
                    String.valueOf(complaint));
            // A one-key override file carries no stamp, and accusing it of being in the wrong
            // language would turn a working setup into a warning nobody can silence.
            check("language.unstamped-file-is-never-accused",
                    edited.languageMismatch(false) == null && edited.declaredLanguage() == null,
                    String.valueOf(edited.languageMismatch(false)));

            // A byte order mark. Notepad and half of the Windows editors write one, and the
            // README tells the admin to write a file that starts with a key - so the mark lands
            // on the very line whose loss is invisible. Same bytes twice, once with and once
            // without, because "it works for me" here is always the file without the mark.
            String oneKey = "ui:\n  footer: 'моя подпись'\n";
            Path plain = folder.resolve("plain.yml");
            Path withBom = folder.resolve("bom.yml");
            Files.writeString(plain, oneKey, StandardCharsets.UTF_8);
            Files.writeString(withBom, "\uFEFF" + oneKey, StandardCharsets.UTF_8);
            check("bom.without-it-overrides",
                    Messages.load(plain).get("ui.footer", true).equals("моя подпись"),
                    Messages.load(plain).get("ui.footer", true));
            check("bom.first-key-still-overrides",
                    Messages.load(withBom).get("ui.footer", true).equals("моя подпись"),
                    Messages.load(withBom).get("ui.footer", true));
            check("bom.rest-of-the-file-untouched",
                    Messages.load(withBom).get("ui.error", true).equals(m.get("ui.error", true)),
                    Messages.load(withBom).get("ui.error", true));

            // The same mark on the 26.8.1 file being migrated away from.
            Path bomLegacy = folder.resolve("bom-legacy");
            Files.createDirectories(bomLegacy);
            Files.writeString(bomLegacy.resolve("messages-ru.txt"),
                    "\uFEFFui.error=сломалось:\n", StandardCharsets.UTF_8);
            Messages.install(bomLegacy, true);
            check("bom.legacy-first-line-migrates",
                    Messages.load(bomLegacy.resolve("messages.yml")).get("ui.error", true)
                            .equals("сломалось:"),
                    Messages.load(bomLegacy.resolve("messages.yml")).get("ui.error", true));

            // An apostrophe is an ordinary thing to write in Russian; it must not end the value.
            Path quoted = folder.resolve("quoted.yml");
            Files.writeString(quoted, "ui:\n  error: 'server-jar''ы:'\n", StandardCharsets.UTF_8);
            check("yaml.doubled-apostrophe",
                    Messages.load(quoted).get("ui.error", true).equals("server-jar'ы:"),
                    Messages.load(quoted).get("ui.error", true));

            // The 26.8.1 --messages file still works, so a pinned CI job does not break.
            Path oldFormat = folder.resolve("my-texts.txt");
            Files.writeString(oldFormat, "ui.error=oops:\n", StandardCharsets.UTF_8);
            check("legacy-txt-override-still-read",
                    Messages.load(oldFormat).get("ui.error", false).equals("oops:"),
                    Messages.load(oldFormat).get("ui.error", false));

            // What the report actually renders: a colour pasted into a text never reaches it.
            Path coloured = folder.resolve("coloured.yml");
            Files.writeString(coloured,
                    "ui:\n  error: '&cошибка:'\n  header: '<red>проверка</red>'\n",
                    StandardCharsets.UTF_8);
            Messages painted = Messages.load(coloured);
            String report = new TextRenderer(true, false, true, painted)
                    .render(ScanService.scan(new File(args[1]), 21, null, null, painted));
            check("report.no-raw-codes",
                    !report.contains("&c") && !report.contains("<red>"), "");
            check("report.words-survive", report.contains("проверка"), "");
        } finally {
            try (java.util.stream.Stream<Path> walk = Files.walk(folder)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }

        // ---- colours. Every notation an admin might already have in their fingers, rendered
        // the way chat will see it: legacy § codes, because SNDoctor does not use Adventure.
        eq("colors.legacy-code", "§cпривет", Colors.toLegacy("&cпривет"));
        eq("colors.section-sign", "§aок", Colors.toLegacy("§aок"));
        eq("colors.short-hex", "§x§7§b§2§f§f§fтекст", Colors.toLegacy("&#7B2FFFтекст"));
        eq("colors.long-spigot-hex", "§x§7§b§2§f§f§fтекст",
                Colors.toLegacy("&x&7&B&2&F&F&Fтекст"));
        eq("colors.mixed-markers", "§x§7§b§2§f§f§fтекст",
                Colors.toLegacy("§x&7&B&2&F&F&Fтекст"));
        eq("colors.decoration-and-reset", "§lжирный§rобычный",
                Colors.toLegacy("&lжирный&rобычный"));
        eq("colors.lone-ampersand-is-text", "Том & Джерри", Colors.toLegacy("Том & Джерри"));
        eq("colors.unknown-code-is-text", "&q", Colors.toLegacy("&q"));
        eq("colors.minimessage-colour", "§ca", Colors.toLegacy("<red>a"));
        eq("colors.minimessage-hex", "§x§7§b§2§f§f§fa", Colors.toLegacy("<#7B2FFF>a"));
        eq("colors.minimessage-decoration", "§la", Colors.toLegacy("<bold>a"));
        eq("colors.closing-tag-resets", "§ca§r", Colors.toLegacy("<red>a</red>"));
        // No legacy form exists for these, so the effect is dropped and the words survive.
        eq("colors.gradient-dropped-text-kept", "СН",
                Colors.toLegacy("<gradient:#7B2FFF:#00E1FF>СН</gradient>"));
        eq("colors.click-dropped-text-kept", "/sndoctor scan",
                Colors.toLegacy("<click:run_command:/sndoctor scan>/sndoctor scan</click>"));
        eq("colors.word-in-brackets-survives", "<файл>", Colors.toLegacy("<файл>"));
        // A tag argument may be quoted and may itself contain a tag. Scanning to the first '>'
        // ends the hover early and spills the tooltip into chat as text; the README promises
        // the opposite - the effect goes, the body text stays.
        eq("colors.hover-with-a-tag-inside-its-argument", "текст",
                Colors.toLegacy("<hover:show_text:'<red>подсказка'>текст</hover>"));
        eq("colors.hover-argument-does-not-eat-the-rest", "§cкрасный",
                Colors.toLegacy("<hover:show_text:'<red>т'>&cкрасный"));
        // ... and the quote rule only applies where an argument begins, so an apostrophe in an
        // ordinary word in brackets still does not turn the rest of the line into an argument.
        eq("colors.apostrophe-in-brackets-is-still-text", "<don't> §cкрасный",
                Colors.toLegacy("<don't> &cкрасный"));
        eq("colors.mixed-in-one-value", "§cкрасный §x§0§0§e§1§f§fголубой §lжирный",
                Colors.toLegacy("&cкрасный <#00E1FF>голубой &lжирный"));

        // An ampersand inside a tag argument belongs to the argument. A query string is the
        // case that makes this matter: "&b=2" read as a colour breaks the link and the tag
        // around it. Here the tag is dropped rather than kept - legacy chat has no <click> -
        // so what these pin is that nothing inside a recognised tag is ever read as a code.
        eq("colors.query-string-inside-a-tag", "ссылка",
                Colors.toLegacy("<click:open_url:https://site/?a=1&b=2>ссылка</click>"));
        eq("strip.query-string-inside-a-tag", "ссылка",
                Colors.strip("<click:open_url:https://site/?a=1&b=2>ссылка</click>"));
        eq("colors.code-outside-the-tag-is-still-a-code", "§cссылка",
                Colors.toLegacy("<click:open_url:https://site/?a=1&b=2>&cссылка</click>"));
        // Only tags the list knows are protected, so a sentence in angle brackets is still a
        // sentence and the code inside it is still a code.
        eq("colors.word-in-brackets-does-not-protect-a-code", "<5 и §a>",
                Colors.toLegacy("<5 и &a>"));

        // MiniMessage's escape. A text moved here from another SN plugin can carry "\<",
        // meaning "show the bracket, do not open a tag". Legacy chat has no escape of its own,
        // so both exits consume the backslash and keep what it protected - and the player and
        // the log therefore read the same thing, which is the whole point of the pair.
        eq("colors.escaped-bracket-is-not-a-tag", "<red>текст",
                Colors.toLegacy("\\<red>текст"));
        eq("strip.escaped-bracket-is-not-a-tag", "<red>текст",
                Colors.strip("\\<red>текст"));
        eq("colors.escaped-backslash-is-one-backslash", "C:\\путь",
                Colors.toLegacy("C:\\\\путь"));
        eq("strip.escaped-backslash-is-one-backslash", "C:\\путь",
                Colors.strip("C:\\\\путь"));
        // A backslash standing right in front of a converted code needs no defence here: the
        // code is "§c" and nothing escapes a section sign. The MiniMessage-emitting plugins of
        // the line double the backslash at exactly this point, because there the code is a tag
        // and the backslash in front of it would swallow it.
        eq("colors.backslash-before-a-code-stays-text", "путь\\§cтекст",
                Colors.toLegacy("путь\\&cтекст"));
        eq("strip.backslash-before-a-code-stays-text", "путь\\текст",
                Colors.strip("путь\\&cтекст"));

        // The plain-text path: every code out, words in angle brackets kept.
        eq("strip.codes", "привет мир", Colors.strip("&cпривет &#7B2FFFмир"));
        eq("strip.long-hex", "мир", Colors.strip("&x&7&B&2&F&F&Fмир"));
        eq("strip.section-sign", "ок", Colors.strip("§aок"));
        eq("strip.known-tags", "ок", Colors.strip("<green>ок</green>"));
        eq("strip.hex-tag", "ок", Colors.strip("<#7B2FFF>ок</#7B2FFF>"));
        eq("strip.word-in-brackets-survives", "<файл>", Colors.strip("<файл>"));
        eq("strip.newline-becomes-newline", "а\nб", Colors.strip("а<newline>б"));
        eq("strip.hover-with-a-tag-inside-its-argument", "текст",
                Colors.strip("<hover:show_text:'<red>подсказка'>текст</hover>"));
        eq("strip.apostrophe-in-brackets-is-still-text", "<don't> красный",
                Colors.strip("<don't> &cкрасный"));
        // <!italic> is <italic> turned off. Without the rule the negation form would reach the
        // report and the console as those literal characters instead of being dropped.
        eq("strip.negation-tag-is-a-tag", "текст", Colors.strip("<!italic>текст"));

        // ---- the bound on the search for a tag's closing bracket. Without it the search runs
        // to the next '>' anywhere, so an unclosed bracket pairs with the bracket of a real tag
        // further along and everything between them disappears. Here that is a whole clause of
        // the admin's sentence, lost because they forgot one '>'.
        eq("bound.unclosed-tag-does-not-eat-the-sentence",
                "<hover:show_text:подсказка §cслово",
                Colors.toLegacy("<hover:show_text:подсказка <red>слово"));
        eq("bound.unclosed-tag-does-not-eat-the-sentence-when-stripped",
                "<hover:show_text:подсказка слово",
                Colors.strip("<hover:show_text:подсказка <red>слово"));
        // The shortest loss found by fuzzing the bounded scan against the unbounded one over
        // 400 000 random values - the unbounded scan ate "<c:<>" here. Nobody would have
        // thought to write this down, which is why the search was random.
        eq("bound.fuzz-witness", "а{k#а<c:<>x", Colors.strip("а{k#а<c:<>x"));
        eq("bound.two-brackets-in-a-row-are-text", "Z<<c:{/{", Colors.strip("Z<<c:{/{&c"));
        eq("bound.a-lone-bracket-keeps-the-sentence", "цена < 5 рублей",
                Colors.strip("цена < 5 <red>рублей</red>"));
        // The bound is a second '<' and nothing else. Stopping at whitespace as well was tried
        // and breaks this, which admins really write - and since <click> has no legacy form,
        // failing to recognise it means printing it at a player instead of dropping it.
        eq("bound.whitespace-is-not-a-bound", "скан",
                Colors.toLegacy("<click:run_command:/sndoctor scan>скан</click>"));

        // ---- why the chain below is a test construction and not a description of production.
        // Converting a value once is right; reading the result a second time is not, and these
        // two say exactly how it goes wrong. A dropped tag can leave a bare ampersand standing
        // in front of a code letter, and a consumed escape leaves a bracket that reads as a tag
        // the next time round. Both are why the startup scan renders its console lines from the
        // raw value (SummaryProbe, further down this file), and both are why '&', its twin '§'
        // and the backslash are out of invariant 1's alphabet.
        eq("chain.a-dropped-tag-joins-an-ampersand-to-a-code-letter", "&cтекст",
                Colors.strip("&<gradient:#7B2FFF:#00E1FF>cтекст"));
        eq("chain.so-the-chat-line-cannot-be-read-again", "текст",
                Colors.strip(Colors.toLegacy("&<gradient:#7B2FFF:#00E1FF>cтекст")));
        eq("chain.an-escaped-bracket-is-a-bracket", "<c>", Colors.strip("\\<c>"));
        eq("chain.but-reading-it-again-makes-it-a-tag", "",
                Colors.strip(Colors.toLegacy("\\<c>")));

        // ---- the two exits, fuzzed against each other. The same messages.yml value reaches a
        // player through toLegacy and the server log through strip (Texts.chat and Texts.plain,
        // same key), so the two must agree on which spans are markup. Five fixed seeds rather
        // than one, and a seed rather than the clock, so a failure is reproducible rather than
        // a story about a build that once went red.
        //
        // The alphabet carries the backslash and both quote characters on purpose: without them
        // the escape branch of the two exits and the quoting branch of tagEnd get no random
        // input at all, and those are the two branches the last round of defects was in.
        //
        // What this does not catch, stated plainly: both exits share tagEnd, so a bug in the
        // bound moves them together and a comparison of the two cannot see it - the fixtures
        // above are the net for that, and they were found by fuzzing against a copy of the old
        // scan rather than by this loop. What this does catch is the two drifting apart, which
        // is the failure an admin reports as "chat and the log say different things".
        //
        // Invariant 1: converting the colours and then stripping them leaves exactly what
        // stripping the original leaves. Three characters are missing from this alphabet -
        // '&', '§' and the backslash - and the four fixtures just above are the reason: each
        // breaks the chain rather than either exit, and the chain is something only this loop
        // does. Nothing in production reads a converted line back; the startup scan's console
        // line did until 26.9.0, and that was a defect, not a licence for one. The markers and
        // the backslash are fuzzed by invariant 2 below, which compares the two exits without
        // feeding one into the other.
        String tagAlphabet = "<>#/:{}'\" абвАБ019cfklrx";
        String worst = null;
        int inputs = 0;
        for (long seed : new long[] {20260912L, 1L, 7L, 99L, 4242L}) {
            java.util.Random random = new java.util.Random(seed);
            for (int round = 0; round < 40_000 && worst == null; round++) {
                String raw = sample(random, tagAlphabet);
                inputs++;
                if (!Colors.strip(raw).equals(Colors.strip(Colors.toLegacy(raw)))) {
                    worst = raw;
                }
            }
        }
        check("fuzz.exits-cut-the-same-spans", worst == null,
                inputs + " inputs, first disagreement: " + worst);

        // Invariant 2, on the full alphabet - markers, backslash and both quotes included: the
        // words themselves come out identical. "The words" are the characters toLegacy can
        // never emit - it emits only '§' and ASCII code letters - so the Cyrillic of the
        // sentence is the part no artefact of a second reading can touch, and the player and
        // the log must see it character for character the same.
        String fullAlphabet = "<>&§#!/:{}'\"\\ абвАБ019cfklrx";
        worst = null;
        inputs = 0;
        for (long seed : new long[] {20260912L, 1L, 7L, 99L, 4242L}) {
            java.util.Random random = new java.util.Random(seed);
            for (int round = 0; round < 40_000 && worst == null; round++) {
                String raw = sample(random, fullAlphabet);
                inputs++;
                if (!words(Colors.strip(raw)).equals(words(Colors.toLegacy(raw)))) {
                    worst = raw;
                }
            }
        }
        check("fuzz.chat-and-log-read-the-same", worst == null,
                inputs + " inputs, first disagreement: " + worst);
    }

    /** One random value for the fuzz: short, because the interesting collisions are short. */
    private static String sample(java.util.Random random, String alphabet) {
        StringBuilder out = new StringBuilder();
        int length = 1 + random.nextInt(12);
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    /** The sentence without anything a converter could have emitted: everything outside ASCII. */
    private static String words(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 127 && c != '§') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Migrates {@code count} lines of the 26.8.1 format in a folder of its own and returns the
     * log line that reports it, so the Russian plural can be asserted for several counts.
     */
    private static String migrationLine(Path parent, String name, int count) throws Exception {
        Path folder = parent.resolve(name);
        Files.createDirectories(folder);
        StringBuilder legacy = new StringBuilder();
        for (int i = 0; i < count; i++) {
            legacy.append("ui.key").append(i).append("=текст ").append(i).append('\n');
        }
        Files.writeString(folder.resolve("messages-ru.txt"), legacy.toString(),
                StandardCharsets.UTF_8);
        List<String> log = Messages.install(folder, true);
        return log.size() > 1 ? log.get(1) : "no migration line: " + log;
    }

    private static Set<String> holes(String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = HOLE.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static void eq(String name, String expected, String actual) {
        check(name, expected.equals(actual), actual);
    }

    private static void check(String name, boolean ok, String detail) {
        System.out.println(ok ? name + "=true" : name + "=false actual=[" + detail + "]");
    }
}
EOF
javac -nowarn -encoding UTF-8 --release 17 -cp "$PROBE_CP" -d "$PROBE_CP" "$WORK/probe/MessagesProbe.java"
java -cp "$PROBE_CP" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
    network.somikyy.sndoctor.core.MessagesProbe \
    "$WORK/rule-ids.txt" "$PLUGINS" "$WORK/used-keys.txt" \
    > "$WORK/messages.checks"

EXPECT_FILE="$WORK/messages.checks"

# Like expect, but prints what the probe produced instead: a bare boolean is a poor bug report
# when the thing that broke is a colour conversion.
expectp() { # expectp <description> <exact line the probe must have produced>
  if grep -qxF -- "$2" "$EXPECT_FILE"; then
    echo "  ok   $1"
  else
    echo "  FAIL $1"
    grep -F -- "${2%%=*}=" "$EXPECT_FILE" | sed 's/^/         /'
    FAILED=$((FAILED + 1))
  fi
}

expectp "bundled catalogue loads"                      "bundle.not-empty=true"
expectp "  ru and en have the same keys"               "bundle.parity=true"
expectp "  ru and en use the same {placeholders}"      "bundle.same-placeholders=true"
expectp "every rule id has title/why/fix in both languages" "rules.every-id-has-texts=true"
expectp "  no texts for rules that do not exist"       "rules.no-orphan-texts=true"
expectp "every chat/ui key the code asks for exists"   "chat.every-key-the-plugin-asks-for-exists=true"
expectp "  and the report labels are among them"       "chat.report-labels-are-checked=true"

expectp "prefix ships in both languages"               "prefix.shipped=true"
expectp "  no {prefix} survives a lookup"              "prefix.always-resolved=true"
expectp "  and it really is in front of a chat line"   "prefix.in-front=true"
expectp "  report texts do not carry it"               "prefix.not-in-report=true"
expectp "emptied prefix stays empty"                   "override.empty-prefix-stays-empty=true"
expectp "  an override wins over the bundle"           "override.wins=true"
expectp "  an untouched key falls back to the bundle"  "override.untouched-key-falls-back=true"
expectp "  a two-line file keeps the other texts"      "override.partial-file-keeps-the-rest=true"

expectp "install writes messages.yml and says so"      "install.reports-what-it-did=true"
expectp "  the written file keeps its comments"        "install.keeps-comments=true"
expectp "  and its colour documentation"               "install.keeps-header=true"
expectp "  a 26.8.1 edit is carried into it"           "install.migrated-value-landed=true"
expectp "  including a nested one"                     "install.migrated-nested-value-landed=true"
expectp "  and that is what the plugin then reads"     "install.migrated-text-is-what-loads=true"
expectp "  a second start does not touch the file"     "install.second-run-leaves-the-file-alone=true"
expectp "  the count reads as Russian: 1 строка"       "install.plural-one=true"
expectp "    3 строки"                                 "install.plural-few=true"
expectp "    11 строк, not 11 строки"                  "install.plural-eleven=true"

expectp "both bundles say which language they are"     "language.bundles-are-stamped=true"
expectp "  and the stamp lands in the created file"    "language.stamp-lands-in-the-written-file=true"
expectp "  where it is read back"                      "language.declared-is-read-back=true"
expectp "  no complaint while config and file agree"   "language.no-complaint-when-they-agree=true"
expectp "  a silent language flag is reported instead" "language.mismatch-is-reported=true"
expectp "  a partial override file is never accused"   "language.unstamped-file-is-never-accused=true"
expectp "a one-key override file works"                "bom.without-it-overrides=true"
expectp "  and still works saved with a BOM"           "bom.first-key-still-overrides=true"
expectp "  the other keys still come from the jar"     "bom.rest-of-the-file-untouched=true"
expectp "  a BOM on the 26.8.1 file migrates too"      "bom.legacy-first-line-migrates=true"
expectp "a doubled apostrophe unescapes"               "yaml.doubled-apostrophe=true"
expectp "a 26.8.1 --messages .txt file still works"    "legacy-txt-override-still-read=true"

expectp "legacy code reaches chat as a section sign"   "colors.legacy-code=true"
expectp "  a section sign passes through"              "colors.section-sign=true"
expectp "  HEX, short form"                            "colors.short-hex=true"
expectp "  HEX, long Spigot form"                      "colors.long-spigot-hex=true"
expectp "  HEX with mixed & and § markers"             "colors.mixed-markers=true"
expectp "  decoration and reset"                       "colors.decoration-and-reset=true"
expectp "  a lone ampersand is text"                   "colors.lone-ampersand-is-text=true"
expectp "  an unknown code is text"                    "colors.unknown-code-is-text=true"
expectp "  MiniMessage colour tag"                     "colors.minimessage-colour=true"
expectp "  MiniMessage hex tag"                        "colors.minimessage-hex=true"
expectp "  MiniMessage decoration tag"                 "colors.minimessage-decoration=true"
expectp "  a closing tag resets"                       "colors.closing-tag-resets=true"
expectp "  gradient dropped, text kept"                "colors.gradient-dropped-text-kept=true"
expectp "  click dropped, text kept"                   "colors.click-dropped-text-kept=true"
expectp "  a word in angle brackets survives"          "colors.word-in-brackets-survives=true"
expectp "  a tag inside a quoted argument, dropped whole" "colors.hover-with-a-tag-inside-its-argument=true"
expectp "    and the rest of the line is untouched"    "colors.hover-argument-does-not-eat-the-rest=true"
expectp "    an apostrophe in brackets is still text"  "colors.apostrophe-in-brackets-is-still-text=true"
expectp "  all notations mixed in one value"           "colors.mixed-in-one-value=true"
expectp "  a query string inside a tag is not a colour code" "colors.query-string-inside-a-tag=true"
expectp "    nor when the text is stripped instead"    "strip.query-string-inside-a-tag=true"
expectp "    but a code outside the tag still converts" "colors.code-outside-the-tag-is-still-a-code=true"
expectp "    and a word in brackets protects nothing"  "colors.word-in-brackets-does-not-protect-a-code=true"
expectp "  an escaped bracket reaches chat as a bracket" "colors.escaped-bracket-is-not-a-tag=true"
expectp "    and the log shows the same thing"         "strip.escaped-bracket-is-not-a-tag=true"
expectp "    a doubled backslash is one backslash"     "colors.escaped-backslash-is-one-backslash=true"
expectp "      in the log too"                         "strip.escaped-backslash-is-one-backslash=true"
expectp "    a backslash in front of a code is just text" "colors.backslash-before-a-code-stays-text=true"
expectp "      in the log too"                         "strip.backslash-before-a-code-stays-text=true"

expectp "plain-text path: codes stripped"              "strip.codes=true"
expectp "  long HEX stripped too"                      "strip.long-hex=true"
expectp "  section sign stripped"                      "strip.section-sign=true"
expectp "  known tags stripped"                        "strip.known-tags=true"
expectp "  hex tag stripped"                           "strip.hex-tag=true"
expectp "  a word in angle brackets survives"          "strip.word-in-brackets-survives=true"
expectp "  <newline> becomes a newline"                "strip.newline-becomes-newline=true"
expectp "  a tag inside a quoted argument, dropped whole" "strip.hover-with-a-tag-inside-its-argument=true"
expectp "    an apostrophe in brackets is still text"  "strip.apostrophe-in-brackets-is-still-text=true"
expectp "an unclosed tag does not eat the sentence"    "bound.unclosed-tag-does-not-eat-the-sentence=true"
expectp "  nor when the text is stripped instead"      "bound.unclosed-tag-does-not-eat-the-sentence-when-stripped=true"
expectp "  the shortest input the fuzz found"          "bound.fuzz-witness=true"
expectp "  two brackets in a row are text"             "bound.two-brackets-in-a-row-are-text=true"
expectp "  a lone bracket keeps the sentence"          "bound.a-lone-bracket-keeps-the-sentence=true"
expectp "  but whitespace does not end a tag"          "bound.whitespace-is-not-a-bound=true"
expectp "a dropped tag can join an ampersand to a code letter" "chain.a-dropped-tag-joins-an-ampersand-to-a-code-letter=true"
expectp "  so a chat line read a second time loses it" "chain.so-the-chat-line-cannot-be-read-again=true"
expectp "  an escaped bracket is a bracket"            "chain.an-escaped-bracket-is-a-bracket=true"
expectp "  and read a second time it is a tag"         "chain.but-reading-it-again-makes-it-a-tag=true"
expectp "200000 random values, five seeds: both exits cut the same spans" "fuzz.exits-cut-the-same-spans=true"
expectp "200000 more with backslash and quotes: chat and the log read the same" "fuzz.chat-and-log-read-the-same=true"

expectp "a colour pasted into a report text never reaches the report" "report.no-raw-codes=true"
expectp "  but the words do"                           "report.words-survive=true"

# ---------------------------------------------------------------- the startup scan's console
# The automatic scan at startup logs the same summary a player gets, minus the colours. It used
# to get there by stripping the chat lines - reading, a second time, the §-codes the conversion
# had just written. That loses an ampersand the admin wrote whenever a dropped tag leaves it
# standing in front of a code letter: "&<gradient:...>cотчёт" is "&cотчёт" in chat, where the
# ampersand is a character the player sees, and "отчёт" after a second pass.
#
# The lines are rendered from the raw value now, through Texts.plain. This probe asserts it on
# the real path: the real SNDoctorPlugin.summarise, over the real fixture jars, with the witness
# text in a messages.yml of its own.
echo "==> startup scan console lines"
cat > "$WORK/probe/SummaryProbe.java" <<'EOF'
package network.somikyy.sndoctor.bukkit;

import network.somikyy.sndoctor.core.Colors;
import network.somikyy.sndoctor.core.Messages;
import network.somikyy.sndoctor.core.Report;
import network.somikyy.sndoctor.core.ScanService;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SummaryProbe {

    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[0]);
        Files.createDirectories(folder);
        Path file = folder.resolve("messages.yml");
        // The header an admin wrote: an ampersand of their own, then a tag with no legacy form.
        // Dropping the tag leaves the two halves of what looks like a colour code side by side.
        Files.writeString(file,
                "chat:\n  summary:\n    header: '&<gradient:#7B2FFF:#00E1FF>cотчёт {version}'\n",
                StandardCharsets.UTF_8);
        Messages messages = Messages.load(file);
        Texts texts = new Texts(messages, true);
        Report report = ScanService.scan(new File(args[1]), 21, null, null, messages);

        String console = SNDoctorPlugin.summarise(report, texts, texts::plain).get(0);
        String chat = SNDoctorPlugin.summarise(report, texts, texts::chat).get(0);

        System.out.println("summary.console-renders-the-raw-value=" + console.contains("&cотчёт"));
        System.out.println("summary.console-carries-no-codes=" + (console.indexOf('§') < 0));
        System.out.println("summary.chat-carries-the-ampersand=" + chat.contains("&cотчёт"));
        // The reason the console line may not be built out of the chat line, as an assertion
        // rather than as a claim in a comment.
        System.out.println("summary.stripping-the-chat-line-would-lose-it="
                + !Colors.strip(chat).contains("&cотчёт"));
    }
}
EOF
javac -nowarn -encoding UTF-8 --release 17 -cp "$PROBE_CP" -d "$PROBE_CP" "$WORK/probe/SummaryProbe.java"
java -cp "$PROBE_CP" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
    network.somikyy.sndoctor.bukkit.SummaryProbe \
    "$WORK/summary" "$PLUGINS" > "$WORK/summary.checks"

EXPECT_FILE="$WORK/summary.checks"
expectp "the startup console line is rendered from the raw value" "summary.console-renders-the-raw-value=true"
expectp "  and carries no § codes"                     "summary.console-carries-no-codes=true"
expectp "  the chat line keeps the ampersand the admin wrote" "summary.chat-carries-the-ampersand=true"
expectp "  stripping that chat line instead would eat it"     "summary.stripping-the-chat-line-would-lose-it=true"
echo
if [[ $FAILED -eq 0 ]]; then
  echo "ALL ASSERTIONS PASSED (cli exit code was $EXIT, expected 2)"
  [[ $EXIT -eq 2 ]] || { echo "but exit code was wrong"; exit 1; }
else
  echo "$FAILED assertion(s) failed"
  exit 1
fi
