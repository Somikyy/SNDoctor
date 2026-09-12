/*
 * SNDoctor - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.sndoctor.core;

import network.somikyy.sndoctor.core.Finding.Severity;
import network.somikyy.sndoctor.core.Finding.Text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Applies SNDoctor's rule set to the raw facts of one jar.
 *
 * <p><b>Rule policy.</b> Every rule in this class is backed by a primary source - a PaperMC
 * announcement, a Paper javadoc deprecation, or an observable property of the jar itself.
 * Claims that could not be verified against a primary source are deliberately absent; see
 * {@code docs/SPEC-SNDoctor.md}, section "Candidate rules". A compatibility scanner that
 * reports a change that never happened is worse than one that reports nothing.
 */
public final class Analyzer {

    /** Version epoch this rule set targets. */
    public static final String TARGET = "26.1+";

    private static final Pattern LEGACY_NMS_PACKAGE =
            Pattern.compile("^net/minecraft/server/v1_\\d+_R\\d+/.*");
    private static final Pattern VERSIONED_CRAFTBUKKIT =
            Pattern.compile("^org/bukkit/craftbukkit/v1_\\d+_R\\d+/.*");
    private static final Pattern VERSION_TOKEN =
            Pattern.compile(".*\\bv1_\\d+_R\\d+\\b.*");
    private static final Pattern RAW_IP_URL =
            Pattern.compile("^https?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?(/.*)?$");

    /** Package prefixes of well-known libraries, used by the masquerading-class heuristic. */
    private static final String[] KNOWN_LIBRARY_PREFIXES = {
            "org/apache/commons/", "com/google/common/", "com/google/gson/", "org/slf4j/",
            "org/json/", "io/netty/", "org/yaml/snakeyaml/", "net/kyori/", "org/apache/logging/",
            "com/mojang/", "org/objectweb/asm/", "okhttp3/", "com/zaxxer/hikari/"
    };

    private static final String[] SUSPICIOUS_URL_HOSTS = {
            "pastebin.com", "hastebin.com", "paste.ee", "transfer.sh", "anonfiles.com",
            "discord.com/api/webhooks", "discordapp.com/api/webhooks", "api.telegram.org",
            "ngrok.io", "trycloudflare.com", "0x0.st", "file.io"
    };

    private static final String[] SERVER_SECRET_MARKERS = {
            "rcon.password", "ops.json", "usercache.json", "server.properties",
            "eula.txt", "whitelist.json", "banned-ips.json"
    };

    private final Map<String, String> spigotNames;
    private final Messages messages;

    public Analyzer(Map<String, String> spigotNames, Messages messages) {
        this.spigotNames = spigotNames;
        this.messages = messages;
    }

    /**
     * Builds a finding, taking its three texts from the message catalogue by rule id.
     *
     * <p>The id is now the only thing a rule says about its own wording. Everything a user
     * reads lives in {@code messages.yml}, so fixing a translation is a text edit rather than a
     * rebuild, and adding a language is a new file rather than a patch to this class.
     */
    private Finding newFinding(String id, Severity severity, String... placeholders) {
        String title = "rule." + id + ".title";
        String why = "rule." + id + ".why";
        String fix = "rule." + id + ".fix";
        return new Finding(id, severity, new Text(
                text(title, true, placeholders), text(title, false, placeholders),
                text(why, true, placeholders), text(why, false, placeholders),
                text(fix, true, placeholders), text(fix, false, placeholders)));
    }

    /**
     * One catalogue text, with colour codes removed.
     *
     * <p>Finding texts end up in three places that render no markup at all - the report file,
     * the CLI stdout and the JSON - so an {@code &c} an admin pasted into a rule text has to be
     * taken out here rather than printed at the reader. The report paints itself in ANSI; the
     * catalogue only supplies words.
     */
    private String text(String key, boolean russian, String... placeholders) {
        return Colors.strip(messages.get(key, russian, placeholders));
    }

    // ------------------------------------------------------------- rule data

    /** Loads the bundled name table, optionally merged with a user-supplied override file. */
    public static Analyzer create(Path override, Messages messages) {
        Map<String, String> names = new LinkedHashMap<>();
        try (InputStream in = Analyzer.class.getResourceAsStream("/sndoctor/spigot-names.txt")) {
            if (in != null) {
                readNames(new InputStreamReader(in, StandardCharsets.UTF_8), names);
            }
        } catch (IOException ignored) {
            // bundled resource missing - the other rules still work
        }
        if (override != null && Files.isReadable(override)) {
            try (BufferedReader r = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                readNames(r, names);
            } catch (IOException ignored) {
                // a bad override file must not stop a scan
            }
        }
        return new Analyzer(names, messages);
    }

    private static void readNames(java.io.Reader reader, Map<String, String> out) throws IOException {
        BufferedReader br = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq > 0) {
                out.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
            }
        }
    }

    public int nameTableSize() {
        return spigotNames.size();
    }

    // -------------------------------------------------------------- analysis

    /**
     * Runs every rule against one jar.
     *
     * @param serverJava Java feature version of the server that will run the plugin,
     *                   or 0 when unknown (CLI mode without {@code --java}).
     */
    public List<Finding> analyze(JarFacts f, int serverJava) {
        List<Finding> out = new ArrayList<>();

        checkSpigotMappings(f, out);
        checkLegacyNmsPackage(f, out);
        checkVersionedCraftBukkit(f, out);
        checkVersionReflection(f, out);
        checkUnversionedCraftBukkit(f, out);

        checkConversationApi(f, out);
        checkMetadataApi(f, out);
        checkPlayerSpawnLocationEvent(f, out);
        checkTeleportFlag(f, out);
        checkGameModeChangeCause(f, out);
        checkLegacyMaterial(f, out);

        checkDescriptor(f, out);
        checkJavaVersion(f, serverJava, out);
        checkFolia(f, out);
        checkRuntimeLibraries(f, out);
        checkUnreadableClasses(f, out);

        checkProtocolLib(f, out);
        checkVault(f, out);

        checkRuntimeExec(f, out);
        checkDynamicClassLoading(f, out);
        checkSuspiciousUrls(f, out);
        checkServerSecrets(f, out);
        checkMasqueradingClasses(f, out);

        out.sort((a, b) -> Integer.compare(a.severity.ordinal(), b.severity.ordinal()));
        return out;
    }

    // ---- mapping rules ----------------------------------------------------

    private void checkSpigotMappings(JarFacts f, List<Finding> out) {
        Finding finding = newFinding("nms.spigot-mappings", Severity.BLOCKER);

        for (String cls : f.referencedClasses) {
            if (!cls.startsWith("net/minecraft/")) {
                continue;
            }
            String simple = simpleName(cls);
            String mojang = spigotNames.get(simple);
            if (mojang != null) {
                finding.evidence(simple + " → " + mojang);
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkLegacyNmsPackage(JarFacts f, List<Finding> out) {
        Finding finding = newFinding("nms.legacy-versioned-package", Severity.BLOCKER);
        for (String cls : f.referencedClasses) {
            if (LEGACY_NMS_PACKAGE.matcher(cls).matches()) {
                finding.evidence(packageOf(cls));
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkVersionedCraftBukkit(JarFacts f, List<Finding> out) {
        Finding finding = newFinding("craftbukkit.versioned", Severity.BLOCKER);
        for (String cls : f.referencedClasses) {
            if (VERSIONED_CRAFTBUKKIT.matcher(cls).matches()) {
                finding.evidence(packageOf(cls));
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkVersionReflection(JarFacts f, List<Finding> out) {
        Finding finding = newFinding("nms.version-reflection", Severity.WARN);
        for (String s : f.stringConstants) {
            if (VERSION_TOKEN.matcher(s).matches()) {
                finding.evidence(trim(s, 60));
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkUnversionedCraftBukkit(JarFacts f, List<Finding> out) {
        Finding finding = newFinding("craftbukkit.direct", Severity.INFO);
        for (String cls : f.referencedClasses) {
            if (cls.startsWith("org/bukkit/craftbukkit/")
                    && !VERSIONED_CRAFTBUKKIT.matcher(cls).matches()) {
                finding.evidence(simpleName(cls));
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    // ---- API deprecation / removal rules ---------------------------------

    private void checkConversationApi(JarFacts f, List<Finding> out) {
        Finding finding = newFinding("api.conversation", Severity.WARN);
        for (String cls : f.referencedClasses) {
            if (cls.startsWith("org/bukkit/conversations/")) {
                finding.evidence(simpleName(cls));
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkMetadataApi(JarFacts f, List<Finding> out) {
        Finding finding = newFinding("api.metadata", Severity.WARN);
        for (String cls : f.referencedClasses) {
            if (cls.startsWith("org/bukkit/metadata/")) {
                finding.evidence(simpleName(cls));
            }
        }
        for (String ref : f.memberRefs) {
            if (ref.endsWith("#setMetadata") || ref.endsWith("#getMetadata")
                    || ref.endsWith("#removeMetadata")) {
                finding.evidence(ref);
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkPlayerSpawnLocationEvent(JarFacts f, List<Finding> out) {
        if (f.referencedClasses.contains("org/bukkit/event/player/PlayerSpawnLocationEvent")) {
            out.add(newFinding("api.player-spawn-location-event", Severity.WARN)
                    .evidence("PlayerSpawnLocationEvent"));
        }
    }

    private void checkTeleportFlag(JarFacts f, List<Finding> out) {
        Finding finding = newFinding("api.teleport-flag-entitystate", Severity.BREAKING);
        for (String cls : f.referencedClasses) {
            if (cls.startsWith("io/papermc/paper/entity/TeleportFlag$EntityState")) {
                finding.evidence("TeleportFlag.EntityState");
            }
        }
        for (String ref : f.memberRefs) {
            if (ref.startsWith("io/papermc/paper/entity/TeleportFlag$EntityState#")) {
                finding.evidence(ref.substring(ref.indexOf('#') + 1));
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkGameModeChangeCause(JarFacts f, List<Finding> out) {
        boolean uses = f.referencedClasses.stream()
                .anyMatch(c -> c.startsWith("org/bukkit/event/player/PlayerGameModeChangeEvent"));
        if (uses) {
            out.add(newFinding("api.gamemode-change-cause", Severity.INFO)
                    .evidence("PlayerGameModeChangeEvent"));
        }
    }

    private void checkLegacyMaterial(JarFacts f, List<Finding> out) {
        Finding finding = newFinding("api.legacy-material", Severity.WARN);
        for (String cls : f.referencedClasses) {
            if (cls.startsWith("org/bukkit/material/")) {
                finding.evidence(simpleName(cls));
            }
        }
        for (String ref : f.memberRefs) {
            if (ref.equals("org/bukkit/Material#getId") || ref.equals("org/bukkit/Material#getMaterial")
                    || ref.endsWith("ItemStack#getDurability") || ref.endsWith("ItemStack#setDurability")) {
                finding.evidence(ref);
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    // ---- descriptor / environment rules ----------------------------------

    private void checkDescriptor(JarFacts f, List<Finding> out) {
        if (!f.hasPluginYml && !f.hasPaperPluginYml) {
            out.add(newFinding("meta.not-a-plugin", Severity.INFO)
                    .evidence(f.fileName));
            return;
        }

        if (!f.mainClass.isEmpty()) {
            String internal = f.mainClass.replace('.', '/');
            if (!f.ownClasses.contains(internal)) {
                out.add(newFinding("meta.main-class-missing", Severity.BLOCKER)
                        .evidence(f.mainClass));
            }
        }

        if (f.hasPaperPluginYml) {
            out.add(newFinding("meta.paper-plugin", Severity.INFO)
                    .evidence("paper-plugin.yml"));
            return; // api-version rules below apply to Bukkit descriptors
        }

        if (f.apiVersion.isEmpty()) {
            out.add(newFinding("meta.no-api-version", Severity.WARN)
                    .evidence("plugin.yml"));
        } else {
            double v = parseApiVersion(f.apiVersion);
            if (v > 0 && v < 1.13) {
                out.add(newFinding("meta.ancient-api-version", Severity.WARN,
                        "api", f.apiVersion)
                        .evidence("api-version: " + f.apiVersion));
            }
        }
    }

    private void checkJavaVersion(JarFacts f, int serverJava, List<Finding> out) {
        int required = f.requiredJava();
        if (required <= 0) {
            return;
        }
        if (serverJava > 0 && required > serverJava) {
            out.add(newFinding("java.too-new", Severity.BLOCKER,
                    "java", String.valueOf(required), "server", String.valueOf(serverJava))
                    .evidence("class major " + f.maxClassMajor));
        } else {
            out.add(newFinding("java.required", Severity.INFO,
                    "java", String.valueOf(required))
                    .evidence("class major " + f.maxClassMajor));
        }
    }

    private void checkFolia(JarFacts f, List<Finding> out) {
        if (f.foliaSupported) {
            out.add(newFinding("folia.supported", Severity.INFO)
                    .evidence("folia-supported: true"));
        }
    }

    private void checkRuntimeLibraries(JarFacts f, List<Finding> out) {
        if (!f.libraries.isEmpty()) {
            Finding finding = newFinding("meta.runtime-libraries", Severity.INFO);
            for (String lib : f.libraries) {
                finding.evidence(lib);
            }
            out.add(finding);
        }
    }

    private void checkUnreadableClasses(JarFacts f, List<Finding> out) {
        if (f.unreadableClasses > 0) {
            out.add(newFinding("scan.unreadable-classes", Severity.WARN,
                    "bad", String.valueOf(f.unreadableClasses),
                    "total", String.valueOf(f.classCount))
                    .evidence(f.unreadableClasses + " class(es)"));
        }
    }

    // ---- dependency rules -------------------------------------------------

    private void checkProtocolLib(JarFacts f, List<Finding> out) {
        boolean referenced = f.referencedClasses.stream().anyMatch(c -> c.startsWith("com/comphenix/protocol/"));
        boolean declared = containsIgnoreCase(f.depend, "ProtocolLib") || containsIgnoreCase(f.softDepend, "ProtocolLib");
        if (referenced || declared) {
            Finding finding = newFinding("dep.protocollib", Severity.WARN);
            finding.evidence(declared ? "объявлен в plugin.yml" : "используется в коде");
            out.add(finding);
        }
    }

    private void checkVault(JarFacts f, List<Finding> out) {
        boolean referenced = f.referencedClasses.stream().anyMatch(c -> c.startsWith("net/milkbowl/vault/"));
        boolean declared = containsIgnoreCase(f.depend, "Vault") || containsIgnoreCase(f.softDepend, "Vault");
        if (referenced || declared) {
            out.add(newFinding("dep.vault", Severity.INFO)
                    .evidence(declared ? "объявлен в plugin.yml" : "используется в коде"));
        }
    }

    // ---- safety heuristics ------------------------------------------------
    // These never affect the compatibility verdict. They mean "a human should look",
    // not "this is malware".

    private void checkRuntimeExec(JarFacts f, List<Finding> out) {
        Finding finding = newFinding("sec.process-execution", Severity.SECURITY);
        for (String ref : f.memberRefs) {
            if (ref.equals("java/lang/Runtime#exec") || ref.startsWith("java/lang/ProcessBuilder#")) {
                finding.evidence(ref);
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkDynamicClassLoading(JarFacts f, List<Finding> out) {
        Finding finding = newFinding("sec.dynamic-class-loading", Severity.SECURITY);
        for (String cls : f.referencedClasses) {
            if (cls.equals("java/net/URLClassLoader")) {
                finding.evidence("URLClassLoader");
            }
        }
        for (String ref : f.memberRefs) {
            if (ref.endsWith("#defineClass")) {
                finding.evidence(ref);
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkSuspiciousUrls(JarFacts f, List<Finding> out) {
        Finding finding = newFinding("sec.suspicious-endpoints", Severity.SECURITY);
        for (String s : f.stringConstants) {
            String lower = s.toLowerCase();
            if (RAW_IP_URL.matcher(s).matches()) {
                finding.evidence(trim(s, 70));
                continue;
            }
            for (String host : SUSPICIOUS_URL_HOSTS) {
                if (lower.contains(host)) {
                    finding.evidence(trim(s, 70));
                    break;
                }
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    private void checkServerSecrets(JarFacts f, List<Finding> out) {
        Finding finding = newFinding("sec.server-files", Severity.SECURITY);
        for (String s : f.stringConstants) {
            for (String marker : SERVER_SECRET_MARKERS) {
                if (s.contains(marker)) {
                    finding.evidence(marker);
                    break;
                }
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    /**
     * Flags a handful of classes squatting inside a well-known library package.
     *
     * <p>A jar that shades a library carries hundreds of its classes. A jar that carries one
     * or two classes under, say, {@code org/apache/commons/lang3/} is not shading anything -
     * something is wearing that package as a costume. This is exactly the shape of the
     * backdoor the Russian admin community dissected in May 2026.
     */
    private void checkMasqueradingClasses(JarFacts f, List<Finding> out) {
        Finding finding = newFinding("sec.masquerading-package", Severity.SECURITY);

        for (String prefix : KNOWN_LIBRARY_PREFIXES) {
            List<String> hits = new ArrayList<>();
            for (String own : f.ownClasses) {
                if (own.startsWith(prefix)) {
                    hits.add(own);
                }
            }
            if (!hits.isEmpty() && hits.size() <= 4) {
                for (String hit : hits) {
                    finding.evidence(hit.replace('/', '.'));
                }
            }
        }
        if (!finding.evidence.isEmpty()) {
            out.add(finding);
        }
    }

    // ------------------------------------------------------------- helpers

    private static boolean containsIgnoreCase(List<String> list, String needle) {
        for (String s : list) {
            if (s.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String simpleName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        String tail = slash < 0 ? internalName : internalName.substring(slash + 1);
        int dollar = tail.indexOf('$');
        return dollar < 0 ? tail : tail.substring(0, dollar);
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash).replace('/', '.');
    }

    private static String trim(String s, int max) {
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }

    /** "1.21" -> 1.21, "1.13" -> 1.13; 0 when unparseable. */
    private static double parseApiVersion(String raw) {
        try {
            String[] parts = raw.split("\\.");
            if (parts.length >= 2) {
                return Double.parseDouble(parts[0] + "." + parts[1]);
            }
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
