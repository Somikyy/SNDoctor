/*
 * SNDoctor - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.sndoctor.report;

import network.somikyy.sndoctor.core.Finding;
import network.somikyy.sndoctor.core.Report;

import java.util.List;

/**
 * Renders a report as JSON, without a JSON library.
 *
 * <p>The shape is stable and documented in {@code docs/SPEC-SNDoctor.md} so other tooling
 * (a Telegram bot, a web dashboard, CI) can consume it.
 */
public final class JsonRenderer {

    private JsonRenderer() {
    }

    public static String render(Report report) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\n");
        sb.append("  \"tool\": \"SNDoctor\",\n");
        sb.append("  \"toolVersion\": ").append(str(report.toolVersion)).append(",\n");
        sb.append("  \"target\": ").append(str(network.somikyy.sndoctor.core.Analyzer.TARGET)).append(",\n");
        sb.append("  \"scannedPath\": ").append(str(report.scannedPath)).append(",\n");
        sb.append("  \"serverJava\": ").append(report.serverJava).append(",\n");
        sb.append("  \"serverVersion\": ").append(str(report.serverVersion)).append(",\n");
        sb.append("  \"durationMillis\": ").append(report.durationMillis).append(",\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"total\": ").append(report.results.size()).append(",\n");
        sb.append("    \"red\": ").append(report.count(Report.Verdict.RED)).append(",\n");
        sb.append("    \"yellow\": ").append(report.count(Report.Verdict.YELLOW)).append(",\n");
        sb.append("    \"green\": ").append(report.count(Report.Verdict.GREEN)).append(",\n");
        sb.append("    \"skipped\": ").append(report.count(Report.Verdict.SKIPPED)).append(",\n");
        sb.append("    \"needsReview\": ").append(report.securityCount()).append('\n');
        sb.append("  },\n");
        sb.append("  \"plugins\": [\n");

        for (int i = 0; i < report.results.size(); i++) {
            Report.PluginResult r = report.results.get(i);
            sb.append("    {\n");
            sb.append("      \"file\": ").append(str(r.facts.fileName)).append(",\n");
            sb.append("      \"name\": ").append(str(r.facts.displayName())).append(",\n");
            sb.append("      \"version\": ").append(str(r.facts.version)).append(",\n");
            sb.append("      \"main\": ").append(str(r.facts.mainClass)).append(",\n");
            sb.append("      \"apiVersion\": ").append(str(r.facts.apiVersion)).append(",\n");
            sb.append("      \"foliaSupported\": ").append(r.facts.foliaSupported).append(",\n");
            sb.append("      \"paperPlugin\": ").append(r.facts.hasPaperPluginYml).append(",\n");
            sb.append("      \"requiredJava\": ").append(r.facts.requiredJava()).append(",\n");
            sb.append("      \"classes\": ").append(r.facts.classCount).append(",\n");
            sb.append("      \"authors\": ").append(arr(r.facts.authors)).append(",\n");
            sb.append("      \"depend\": ").append(arr(r.facts.depend)).append(",\n");
            sb.append("      \"softDepend\": ").append(arr(r.facts.softDepend)).append(",\n");
            sb.append("      \"verdict\": ").append(str(r.verdict.name())).append(",\n");
            if (!r.error.isEmpty()) {
                sb.append("      \"error\": ").append(str(r.error)).append(",\n");
            }
            sb.append("      \"findings\": [\n");
            for (int j = 0; j < r.findings.size(); j++) {
                Finding f = r.findings.get(j);
                sb.append("        {\n");
                sb.append("          \"id\": ").append(str(f.id)).append(",\n");
                sb.append("          \"severity\": ").append(str(f.severity.name())).append(",\n");
                sb.append("          \"title\": ").append(str(f.text.titleEn)).append(",\n");
                sb.append("          \"titleRu\": ").append(str(f.text.titleRu)).append(",\n");
                sb.append("          \"why\": ").append(str(f.text.whyEn)).append(",\n");
                sb.append("          \"fix\": ").append(str(f.text.fixEn)).append(",\n");
                sb.append("          \"evidence\": ").append(arr(f.evidence)).append('\n');
                sb.append("        }").append(j < r.findings.size() - 1 ? "," : "").append('\n');
            }
            sb.append("      ]\n");
            sb.append("    }").append(i < report.results.size() - 1 ? "," : "").append('\n');
        }

        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String arr(List<String> items) {
        if (items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(str(items.get(i)));
        }
        return sb.append(']').toString();
    }

    /** JSON string literal with full escaping, including control characters. */
    static String str(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
