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
import network.somikyy.sndoctor.core.Report.Verdict;

import java.util.List;

/**
 * Renders a report as plain text.
 *
 * <p>The output is designed to be screenshot-able: an admin should be able to paste it into a
 * chat and have the answer be obvious from the first three lines. Colour is ANSI and optional,
 * because the same renderer feeds a file on disk.
 */
public final class TextRenderer {

    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String GREEN = "\u001B[32m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String GREY = "\u001B[90m";
    private static final String BOLD = "\u001B[1m";

    private final boolean ru;
    private final boolean colour;
    private final boolean full;

    public TextRenderer(boolean ru, boolean colour, boolean full) {
        this.ru = ru;
        this.colour = colour;
        this.full = full;
    }

    public String render(Report report) {
        StringBuilder sb = new StringBuilder(8192);

        sb.append(c(BOLD)).append("SNDoctor ").append(report.toolVersion).append(c(RESET))
                .append(ru ? "  —  проверка плагинов на совместимость с Minecraft "
                        : "  —  plugin compatibility check for Minecraft ")
                .append(network.somikyy.sndoctor.core.Analyzer.TARGET).append('\n');

        sb.append(c(GREY));
        sb.append(ru ? "Папка: " : "Directory: ").append(report.scannedPath).append('\n');
        sb.append(ru ? "Найдено jar: " : "Jars found: ").append(report.results.size());
        if (report.serverJava > 0) {
            sb.append(ru ? "   Java сервера: " : "   Server Java: ").append(report.serverJava);
        }
        if (!report.serverVersion.isEmpty()) {
            sb.append("   ").append(report.serverVersion);
        }
        sb.append(ru ? "   Время: " : "   Took: ").append(report.durationMillis).append(" ms");
        sb.append(c(RESET)).append("\n\n");

        // ---- summary line ---------------------------------------------------
        sb.append(pill(RED, report.count(Verdict.RED), ru ? "КРАСНЫЙ" : "RED")).append("   ");
        sb.append(pill(YELLOW, report.count(Verdict.YELLOW), ru ? "ЖЁЛТЫЙ" : "YELLOW")).append("   ");
        sb.append(pill(GREEN, report.count(Verdict.GREEN), ru ? "ЗЕЛЁНЫЙ" : "GREEN"));
        if (report.count(Verdict.SKIPPED) > 0) {
            sb.append("   ").append(pill(GREY, report.count(Verdict.SKIPPED),
                    ru ? "ПРОПУЩЕН" : "SKIPPED"));
        }
        if (report.securityCount() > 0) {
            sb.append("   ").append(pill(MAGENTA, report.securityCount(),
                    ru ? "НА ПРОВЕРКУ" : "REVIEW"));
        }
        sb.append("\n\n");

        section(sb, Verdict.RED, RED, report,
                ru ? "КРАСНЫЕ — не запустятся" : "RED — will not run");
        section(sb, Verdict.YELLOW, YELLOW, report,
                ru ? "ЖЁЛТЫЕ — загрузятся, но есть проблемы" : "YELLOW — loads with problems");
        securitySection(sb, report);
        section(sb, Verdict.SKIPPED, GREY, report,
                ru ? "ПРОПУЩЕНЫ" : "SKIPPED");

        List<Report.PluginResult> green = report.byVerdict(Verdict.GREEN);
        if (!green.isEmpty()) {
            sb.append(c(GREEN)).append(ru ? "ЗЕЛЁНЫЕ — проблем не найдено" : "GREEN — clean")
                    .append(c(RESET)).append(" (").append(green.size()).append(")\n");
            if (full) {
                for (Report.PluginResult r : green) {
                    sb.append("  ").append(r.facts.displayName());
                    if (!r.facts.version.isEmpty()) {
                        sb.append(' ').append(r.facts.version);
                    }
                    sb.append('\n');
                }
            } else {
                StringBuilder names = new StringBuilder();
                for (Report.PluginResult r : green) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(r.facts.displayName());
                }
                sb.append(c(GREY)).append("  ").append(wrap(names.toString(), 90, "  "))
                        .append(c(RESET)).append('\n');
            }
            sb.append('\n');
        }

        sb.append(c(GREY))
                .append(ru
                        ? "Правила SNDoctor опираются на первоисточники (анонсы PaperMC, javadoc, "
                          + "содержимое jar). Спорные и неподтверждённые изменения в набор не включены."
                        : "SNDoctor's rules are backed by primary sources (PaperMC announcements, "
                          + "javadocs, jar contents). Unverified claims are deliberately excluded.")
                .append(c(RESET)).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------- sections

    private void section(StringBuilder sb, Verdict verdict, String colourCode,
                         Report report, String title) {
        List<Report.PluginResult> list = report.byVerdict(verdict);
        if (list.isEmpty()) {
            return;
        }
        sb.append(c(colourCode)).append(c(BOLD)).append(title).append(c(RESET))
                .append(" (").append(list.size()).append(")\n");
        sb.append(c(GREY)).append("─".repeat(Math.min(70, title.length() + 20)))
                .append(c(RESET)).append('\n');

        for (Report.PluginResult r : list) {
            renderPlugin(sb, r, colourCode);
        }
        sb.append('\n');
    }

    private void securitySection(StringBuilder sb, Report report) {
        List<Report.PluginResult> list = report.results.stream()
                .filter(Report.PluginResult::hasSecurityFindings)
                .toList();
        if (list.isEmpty()) {
            return;
        }
        String title = ru
                ? "НА РУЧНУЮ ПРОВЕРКУ — это не приговор, это повод посмотреть"
                : "NEEDS A HUMAN LOOK — not a verdict, just worth checking";
        sb.append(c(MAGENTA)).append(c(BOLD)).append(title).append(c(RESET))
                .append(" (").append(list.size()).append(")\n");
        sb.append(c(GREY)).append("─".repeat(70)).append(c(RESET)).append('\n');
        for (Report.PluginResult r : list) {
            sb.append(c(MAGENTA)).append("● ").append(c(RESET))
                    .append(c(BOLD)).append(r.facts.displayName()).append(c(RESET))
                    .append(c(GREY)).append("  ").append(r.facts.fileName).append(c(RESET)).append('\n');
            for (Finding f : r.findings) {
                if (f.severity == Finding.Severity.SECURITY) {
                    renderFinding(sb, f);
                }
            }
        }
        sb.append('\n');
    }

    private void renderPlugin(StringBuilder sb, Report.PluginResult r, String colourCode) {
        sb.append(c(colourCode)).append("● ").append(c(RESET))
                .append(c(BOLD)).append(r.facts.displayName()).append(c(RESET));
        if (!r.facts.version.isEmpty()) {
            sb.append(' ').append(r.facts.version);
        }
        sb.append(c(GREY));
        sb.append("   ").append(r.facts.fileName);
        if (r.facts.requiredJava() > 0) {
            sb.append("   Java ").append(r.facts.requiredJava());
        }
        if (!r.facts.apiVersion.isEmpty()) {
            sb.append("   api-version ").append(r.facts.apiVersion);
        }
        sb.append(c(RESET)).append('\n');

        if (!r.error.isEmpty()) {
            sb.append("    ").append(ru ? "ошибка: " : "error: ").append(r.error).append('\n');
            return;
        }
        for (Finding f : r.findings) {
            if (f.severity == Finding.Severity.SECURITY) {
                continue; // shown in its own section
            }
            if (!full && f.severity == Finding.Severity.INFO) {
                continue;
            }
            renderFinding(sb, f);
        }
    }

    private void renderFinding(StringBuilder sb, Finding f) {
        String mark = switch (f.severity) {
            case BLOCKER -> "✗";
            case BREAKING -> "!";
            case WARN -> "•";
            case SECURITY -> "?";
            case INFO -> "·";
        };
        sb.append("    ").append(mark).append(' ').append(f.title(ru));
        String ev = f.evidenceLine(full ? 40 : 5);
        if (!ev.isEmpty()) {
            sb.append(c(GREY)).append("  [").append(ev).append(']').append(c(RESET));
        }
        sb.append('\n');
        sb.append(c(GREY)).append(wrap(f.why(ru), 92, "        ")).append(c(RESET)).append('\n');
        String fix = wrap(f.fix(ru), 88, "          ");
        sb.append("        → ").append(fix.substring(10)).append('\n');
    }

    // -------------------------------------------------------------- helpers

    private String pill(String colourCode, int n, String label) {
        return c(colourCode) + c(BOLD) + label + " " + n + c(RESET);
    }

    private String c(String code) {
        return colour ? code : "";
    }

    /** Word-wraps text, prefixing every line with {@code indent}. */
    static String wrap(String text, int width, String indent) {
        StringBuilder out = new StringBuilder();
        int lineLen = 0;
        out.append(indent);
        for (String word : text.split("\\s+")) {
            if (lineLen > 0 && lineLen + word.length() + 1 > width) {
                out.append('\n').append(indent);
                lineLen = 0;
            } else if (lineLen > 0) {
                out.append(' ');
                lineLen++;
            }
            out.append(word);
            lineLen += word.length();
        }
        return out.toString();
    }
}
