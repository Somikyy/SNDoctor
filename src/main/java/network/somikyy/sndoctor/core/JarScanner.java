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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Opens plugin jars and collects raw facts. Never loads or executes plugin code:
 * every jar is read as a zip archive and every class as a byte stream.
 */
public final class JarScanner {

    /** Hard cap on collected string literals per jar, so a huge jar cannot exhaust memory. */
    private static final int MAX_STRINGS = 20_000;

    private JarScanner() {
    }

    /** Lists plugin jars in a directory, sorted by name. Sub-directories are not searched. */
    public static List<File> listJars(File directory) {
        File[] files = directory.listFiles();
        List<File> jars = new ArrayList<>();
        if (files == null) {
            return jars;
        }
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                jars.add(f);
            }
        }
        jars.sort(Comparator.comparing(f -> f.getName().toLowerCase()));
        return jars;
    }

    /**
     * Scans a single jar.
     *
     * @throws IOException if the file is not a readable zip archive
     */
    public static JarFacts scan(File jarFile) throws IOException {
        JarFacts facts = new JarFacts();
        facts.fileName = jarFile.getName();
        facts.fileSizeBytes = jarFile.length();

        try (ZipFile zip = new ZipFile(jarFile)) {
            // Pass 1: descriptor
            readDescriptor(zip, facts);

            // Pass 2: classes
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                facts.classCount++;
                try (InputStream in = zip.getInputStream(entry)) {
                    ClassFileReader.ClassFacts cf = ClassFileReader.read(in);
                    if (cf.majorVersion > facts.maxClassMajor) {
                        facts.maxClassMajor = cf.majorVersion;
                    }
                    String owner = cf.ownName.isEmpty()
                            ? entry.getName().substring(0, entry.getName().length() - 6)
                            : cf.ownName;
                    facts.ownClasses.add(owner);
                    for (String ref : cf.referencedClasses) {
                        if (facts.referencedClasses.add(ref)) {
                            facts.firstReferrer.put(ref, owner);
                        }
                    }
                    facts.memberRefs.addAll(cf.memberRefs);
                    if (facts.stringConstants.size() < MAX_STRINGS) {
                        for (String s : cf.stringConstants) {
                            if (facts.stringConstants.size() >= MAX_STRINGS) {
                                break;
                            }
                            facts.stringConstants.add(s);
                        }
                    }
                } catch (IOException | RuntimeException ex) {
                    // A class we cannot parse is a data point, not a crash.
                    facts.unreadableClasses++;
                }
            }
        }

        if (facts.pluginName.isEmpty()) {
            facts.pluginName = facts.displayName();
        }
        return facts;
    }

    private static void readDescriptor(ZipFile zip, JarFacts facts) {
        ZipEntry paperYml = zip.getEntry("paper-plugin.yml");
        ZipEntry bukkitYml = zip.getEntry("plugin.yml");
        facts.hasPaperPluginYml = paperYml != null;
        facts.hasPluginYml = bukkitYml != null;

        // A Paper plugin may ship both; paper-plugin.yml wins at load time.
        ZipEntry chosen = paperYml != null ? paperYml : bukkitYml;
        if (chosen == null) {
            facts.descriptorError = "no plugin.yml / paper-plugin.yml";
            return;
        }
        try (InputStream in = zip.getInputStream(chosen)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            MiniYaml yaml = MiniYaml.parse(text);
            facts.pluginName = yaml.get("name", "");
            facts.version = yaml.get("version", "");
            facts.mainClass = yaml.get("main", "");
            facts.apiVersion = yaml.get("api-version", "");
            facts.foliaSupported = yaml.getBoolean("folia-supported", false);
            facts.authors.addAll(yaml.getList("authors"));
            if (facts.authors.isEmpty()) {
                facts.authors.addAll(yaml.getList("author"));
            }
            facts.depend.addAll(yaml.getList("depend"));
            facts.softDepend.addAll(yaml.getList("softdepend"));
            facts.libraries.addAll(yaml.getList("libraries"));
        } catch (IOException | RuntimeException ex) {
            facts.descriptorError = "descriptor unreadable: " + ex.getClass().getSimpleName();
        }
    }
}
