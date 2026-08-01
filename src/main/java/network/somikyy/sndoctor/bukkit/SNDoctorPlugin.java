/*
 * SNDoctor - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.sndoctor.bukkit;

import network.somikyy.sndoctor.core.Analyzer;
import network.somikyy.sndoctor.core.Report;
import network.somikyy.sndoctor.core.ScanService;
import network.somikyy.sndoctor.report.JsonRenderer;
import network.somikyy.sndoctor.report.TextRenderer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * In-server front end: {@code /sndoctor}.
 *
 * <p>The scan runs off the main thread and never loads plugin classes, so it is safe to run on
 * a live server. Chat gets the summary; the full report goes to disk.
 */
public final class SNDoctorPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private volatile boolean scanning;

    /** Replaced wholesale on reload, so a scan in flight keeps the settings it started with. */
    private volatile SNDoctorConfig config = SNDoctorConfig.defaults();

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Не удалось создать папку данных: " + getDataFolder());
        }
        loadConfiguration();

        if (getCommand("sndoctor") != null) {
            getCommand("sndoctor").setExecutor(this);
            getCommand("sndoctor").setTabCompleter(this);
        }

        getLogger().info("SNDoctor " + ScanService.VERSION + " готов. Цель проверки: "
                + Analyzer.TARGET + ". Команда: /sndoctor scan");
        getLogger().info("Линейка SN — бесплатные плагины с открытым кодом: t.me/somikyy");

        SNDoctorConfig current = config;
        if (current.updateCheck) {
            UpdateCheck.run(this, current.russian);
        }
        if (current.scanOnStart) {
            // Delayed so the automatic scan does not interleave with other plugins still
            // logging their own startup, and so every jar is on disk by the time we read it.
            getServer().getScheduler().runTaskLaterAsynchronously(this,
                    () -> performScan(null, current), current.startDelayTicks);
        }
    }

    /** Creates config.yml on first run and reads it; falls back to defaults if it is unreadable. */
    private void loadConfiguration() {
        Path file = getDataFolder().toPath().resolve("config.yml");
        try {
            if (SNDoctorConfig.writeDefaultIfMissing(file)) {
                config = SNDoctorConfig.load(file);
                return;
            }
            getLogger().warning("config.yml не найден в jar, беру настройки по умолчанию.");
        } catch (Exception ex) {
            getLogger().warning("Не удалось прочитать config.yml (" + ex.getMessage()
                    + "), беру настройки по умолчанию.");
        }
        config = SNDoctorConfig.defaults();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("sndoctor.use")) {
            sender.sendMessage("§cНет прав.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "scan";

        switch (sub) {
            case "scan", "full" -> {
                // Read the volatile once: a /sndoctor reload between the two reads would
                // otherwise mix settings from two different versions of the file.
                SNDoctorConfig current = config;
                startScan(sender, current.forRequest(
                        sub.equals("full") || hasFlag(args, "--full"), langFrom(args, current)));
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("sndoctor.reload")) {
                    sender.sendMessage("§cНет прав.");
                    return true;
                }
                loadConfiguration();
                sender.sendMessage("§aConfig.yml перечитан.");
                return true;
            }
            case "version" -> {
                sender.sendMessage("§bSNDoctor §f" + ScanService.VERSION
                        + " §7— цель: " + Analyzer.TARGET);
                sender.sendMessage("§7Открытый код: §fgithub.com/Somikyy/SNDoctor");
                return true;
            }
            default -> {
                sender.sendMessage("§bSNDoctor §7" + ScanService.VERSION);
                sender.sendMessage("§f/sndoctor scan §7— краткий отчёт в чат, полный в файл");
                sender.sendMessage("§f/sndoctor full §7— показать и справочные находки");
                sender.sendMessage("§f/sndoctor scan --lang en §7— отчёт на английском");
                sender.sendMessage("§f/sndoctor reload §7— перечитать config.yml");
                return true;
            }
        }
    }

    private void startScan(CommandSender sender, SNDoctorConfig settings) {
        if (scanning) {
            sender.sendMessage("§eПроверка уже идёт, подожди.");
            return;
        }
        sender.sendMessage("§7Проверяю плагины…");
        // Reading dozens of jars is disk-bound work; keep it off the main thread.
        getServer().getScheduler().runTaskAsynchronously(this, () -> performScan(sender, settings));
    }

    /**
     * The scan itself. Always called off the main thread.
     *
     * @param sender who asked, or {@code null} for the automatic scan at startup
     */
    private void performScan(CommandSender sender, SNDoctorConfig settings) {
        if (scanning) {
            return;
        }
        scanning = true;
        try {
            File pluginsDir = getDataFolder().getParentFile();
            Path override = getDataFolder().toPath().resolve("spigot-names.txt");
            Report report = ScanService.scan(
                    pluginsDir,
                    ScanService.currentJavaVersion(),
                    Files.isReadable(override) ? override : null,
                    selfFileName());
            report.serverVersion = safeServerVersion();

            Path reportPath = settings.reportsEnabled ? writeReports(report, settings) : null;
            List<String> summary = summarise(report, settings.russian);

            if (sender == null) {
                // Startup scan: the console is the only audience, and it gets the short form.
                // Anyone who wants the detail has the file, or can run /sndoctor full.
                for (String line : summary) {
                    getLogger().info(strip(line));
                }
                if (reportPath != null) {
                    getLogger().info("Полный отчёт: " + reportPath);
                }
                scanning = false;
                return;
            }

            String full = new TextRenderer(settings.russian, false, settings.full).render(report);
            getServer().getScheduler().runTask(this, () -> {
                for (String line : summary) {
                    sender.sendMessage(line);
                }
                if (reportPath != null) {
                    sender.sendMessage("§7Полный отчёт: §f" + reportPath);
                }
                getLogger().info(System.lineSeparator() + full);
                scanning = false;
            });
        } catch (Exception ex) {
            getLogger().warning("SNDoctor scan failed: " + ex);
            if (sender == null) {
                scanning = false;
                return;
            }
            getServer().getScheduler().runTask(this, () -> {
                sender.sendMessage("§cОшибка проверки: " + ex.getMessage());
                scanning = false;
            });
        }
    }

    /** Chat gets a short, readable summary; the file gets everything. */
    private List<String> summarise(Report report, boolean ru) {
        List<String> lines = new ArrayList<>();
        lines.add("§bSNDoctor §7" + ScanService.VERSION + " §8· §7цель " + Analyzer.TARGET);
        lines.add("§cКрасных §f" + report.count(Report.Verdict.RED)
                + "  §eЖёлтых §f" + report.count(Report.Verdict.YELLOW)
                + "  §aЗелёных §f" + report.count(Report.Verdict.GREEN)
                + (report.securityCount() > 0
                ? "  §dНа проверку §f" + report.securityCount() : ""));

        List<Report.PluginResult> red = report.byVerdict(Report.Verdict.RED);
        if (!red.isEmpty()) {
            lines.add("§cНе запустятся:");
            for (int i = 0; i < Math.min(10, red.size()); i++) {
                Report.PluginResult r = red.get(i);
                String reason = r.findings.isEmpty() ? "" : " §8— §7" + r.findings.get(0).title(ru);
                lines.add(" §7• §f" + r.facts.displayName() + reason);
            }
            if (red.size() > 10) {
                lines.add(" §8…и ещё " + (red.size() - 10) + " — смотри файл отчёта");
            }
        }
        if (red.isEmpty() && report.count(Report.Verdict.YELLOW) == 0) {
            lines.add("§aКритичных проблем не найдено.");
        }
        return lines;
    }

    private Path writeReports(Report report, SNDoctorConfig settings) {
        try {
            Path dir = getDataFolder().toPath().resolve("reports");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(STAMP);
            Path text = dir.resolve("report-" + stamp + ".txt");
            Files.writeString(text, new TextRenderer(settings.russian, false, true).render(report),
                    StandardCharsets.UTF_8);
            if (settings.reportsJson) {
                Files.writeString(dir.resolve("report-" + stamp + ".json"),
                        JsonRenderer.render(report), StandardCharsets.UTF_8);
            }
            pruneReports(dir, settings.reportsKeep);
            return text;
        } catch (Exception ex) {
            getLogger().warning("Не удалось записать отчёт: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Keeps the newest {@code keep} scans and deletes the rest.
     *
     * <p>Two files per scan add up quietly, and nobody goes looking in a plugin data folder
     * until it is already large. File names are timestamps, so lexicographic order is
     * chronological order and no file attributes have to be read.
     */
    private void pruneReports(Path dir, int keep) {
        if (keep <= 0) {
            return;
        }
        try (Stream<Path> listing = Files.list(dir)) {
            List<Path> files = listing
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("report-"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            Set<String> kept = new HashSet<>();
            for (Path file : files) {
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String scan = dot > 0 ? name.substring(0, dot) : name;
                if (kept.size() < keep) {
                    kept.add(scan);
                }
                if (!kept.contains(scan)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (Exception ex) {
            getLogger().warning("Не удалось почистить старые отчёты: " + ex.getMessage());
        }
    }

    private String safeServerVersion() {
        try {
            return getServer().getVersion();
        } catch (Throwable t) {
            return "";
        }
    }

    private String selfFileName() {
        try {
            return Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getFileName().toString();
        } catch (Exception ex) {
            return null;
        }
    }

    /** Console has no colour codes to render, so drop the section signs rather than print them. */
    private static String strip(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '§' && i + 1 < line.length()) {
                i++;
            } else {
                out.append(line.charAt(i));
            }
        }
        return out.toString().trim();
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (a.equalsIgnoreCase(flag)) {
                return true;
            }
        }
        return false;
    }

    /** {@code --lang} on the command wins over config.yml, for this one run only. */
    private static boolean langFrom(String[] args, SNDoctorConfig fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equalsIgnoreCase("--lang")) {
                return !args[i + 1].equalsIgnoreCase("en");
            }
        }
        return fallback.russian;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            for (String option : new String[]{"scan", "full", "version", "reload"}) {
                if (option.startsWith(prefix)) {
                    out.add(option);
                }
            }
        } else if (args[args.length - 2].equalsIgnoreCase("--lang")) {
            out.add("ru");
            out.add("en");
        } else {
            out.add("--lang");
            out.add("--full");
        }
        return out;
    }
}
