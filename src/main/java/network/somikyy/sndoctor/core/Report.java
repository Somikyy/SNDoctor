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

import java.util.ArrayList;
import java.util.List;

/** The result of one scan: every plugin, its findings, and its verdict. */
public final class Report {

    /** Traffic-light verdict for a single plugin. */
    public enum Verdict {
        RED("КРАСНЫЙ", "RED", "не запустится на " + Analyzer.TARGET, "will not run on " + Analyzer.TARGET),
        YELLOW("ЖЁЛТЫЙ", "YELLOW", "запустится, но что-то сломано или устарело",
                "loads, but something is broken or deprecated"),
        GREEN("ЗЕЛЁНЫЙ", "GREEN", "проблем не найдено", "no problems found"),
        SKIPPED("ПРОПУЩЕН", "SKIPPED", "не удалось прочитать файл", "file could not be read");

        public final String ru;
        public final String en;
        public final String hintRu;
        public final String hintEn;

        Verdict(String ru, String en, String hintRu, String hintEn) {
            this.ru = ru;
            this.en = en;
            this.hintRu = hintRu;
            this.hintEn = hintEn;
        }
    }

    /** One plugin's result. */
    public static final class PluginResult {
        public final JarFacts facts;
        public final List<Finding> findings;
        public final Verdict verdict;
        /** Set when the jar itself could not be opened. */
        public final String error;

        public PluginResult(JarFacts facts, List<Finding> findings, String error) {
            this.facts = facts;
            this.findings = findings;
            this.error = error == null ? "" : error;
            this.verdict = computeVerdict(findings, this.error);
        }

        /**
         * Security findings deliberately do not affect the verdict: "needs a human look" is a
         * different axis from "will it run", and mixing them would make the traffic light lie.
         */
        private static Verdict computeVerdict(List<Finding> findings, String error) {
            if (!error.isEmpty()) {
                return Verdict.SKIPPED;
            }
            boolean yellow = false;
            for (Finding f : findings) {
                switch (f.severity) {
                    case BLOCKER -> {
                        return Verdict.RED;
                    }
                    case BREAKING, WARN -> yellow = true;
                    default -> {
                    }
                }
            }
            return yellow ? Verdict.YELLOW : Verdict.GREEN;
        }

        public boolean hasSecurityFindings() {
            return findings.stream().anyMatch(f -> f.severity == Finding.Severity.SECURITY);
        }
    }

    public final List<PluginResult> results = new ArrayList<>();
    /** Directory that was scanned. */
    public String scannedPath = "";
    /** Java feature version of the target server, or 0 if unknown. */
    public int serverJava;
    /** Server version string when running as a plugin, empty in CLI mode. */
    public String serverVersion = "";
    public long durationMillis;
    public String toolVersion = "";

    public int count(Verdict v) {
        return (int) results.stream().filter(r -> r.verdict == v).count();
    }

    public int securityCount() {
        return (int) results.stream().filter(PluginResult::hasSecurityFindings).count();
    }

    public List<PluginResult> byVerdict(Verdict v) {
        return results.stream().filter(r -> r.verdict == v).toList();
    }

    /** Process exit code for CLI mode: 2 = any red, 1 = any yellow, 0 = clean. */
    public int exitCode() {
        if (count(Verdict.RED) > 0) {
            return 2;
        }
        if (count(Verdict.YELLOW) > 0) {
            return 1;
        }
        return 0;
    }
}
