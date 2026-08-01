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
import java.nio.file.Path;
import java.util.List;

/**
 * The one entry point both front-ends use. Contains no Bukkit types, so the CLI can run on a
 * server that will not start - which is exactly when SNDoctor is most useful.
 */
public final class ScanService {

    public static final String VERSION = "26.8.1";

    private ScanService() {
    }

    /**
     * Scans a directory of plugin jars.
     *
     * @param pluginsDir   directory to scan
     * @param serverJava   Java feature version of the target server, or 0 if unknown
     * @param nameOverride optional user-supplied Spigot name table, may be {@code null}
     * @param selfFileName file name of SNDoctor itself, excluded from the scan; may be {@code null}
     */
    public static Report scan(File pluginsDir, int serverJava, Path nameOverride, String selfFileName) {
        long started = System.currentTimeMillis();
        Analyzer analyzer = Analyzer.create(nameOverride);

        Report report = new Report();
        report.toolVersion = VERSION;
        report.scannedPath = pluginsDir.getAbsolutePath();
        report.serverJava = serverJava;

        List<File> jars = JarScanner.listJars(pluginsDir);
        for (File jar : jars) {
            if (selfFileName != null && jar.getName().equals(selfFileName)) {
                continue;
            }
            try {
                JarFacts facts = JarScanner.scan(jar);
                report.results.add(new Report.PluginResult(facts, analyzer.analyze(facts, serverJava), null));
            } catch (Exception ex) {
                JarFacts stub = new JarFacts();
                stub.fileName = jar.getName();
                stub.fileSizeBytes = jar.length();
                report.results.add(new Report.PluginResult(stub, List.of(),
                        ex.getClass().getSimpleName() + ": " + ex.getMessage()));
            }
        }

        report.results.sort((a, b) -> {
            int byVerdict = Integer.compare(a.verdict.ordinal(), b.verdict.ordinal());
            return byVerdict != 0 ? byVerdict
                    : a.facts.displayName().compareToIgnoreCase(b.facts.displayName());
        });
        report.durationMillis = System.currentTimeMillis() - started;
        return report;
    }

    /** Java feature version of the currently running JVM, e.g. 21. */
    public static int currentJavaVersion() {
        String spec = System.getProperty("java.specification.version", "");
        try {
            if (spec.startsWith("1.")) {
                return Integer.parseInt(spec.substring(2));
            }
            return Integer.parseInt(spec);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
