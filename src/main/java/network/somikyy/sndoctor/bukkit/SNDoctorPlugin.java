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
import network.somikyy.sndoctor.core.Messages;
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

    /**
     * Replaced wholesale on reload for the same reason as {@link #config}.
     *
     * <p>Loaded once rather than per scan: the texts are needed by {@code /sndoctor version},
     * by the help output and by the permission refusal, none of which run a scan.
     */
    private volatile Messages messages = Messages.bundled();

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
            UpdateCheck.run(this, new Texts(messages, current.russian));
        }
        if (current.scanOnStart) {
            // Delayed so the automatic scan does not interleave with other plugins still
            // logging their own startup, and so every jar is on disk by the time we read it.
            getServer().getScheduler().runTaskLaterAsynchronously(this,
                    () -> performScan(null, current, new Texts(messages, current.russian)),
                    current.startDelayTicks);
        }
    }

    /**
     * Creates config.yml and messages.yml on first run and reads both; falls back to defaults
     * and to the bundled texts if either is unreadable.
     */
    private void loadConfiguration() {
        Path file = getDataFolder().toPath().resolve("config.yml");
        try {
            if (SNDoctorConfig.writeDefaultIfMissing(file)) {
                config = SNDoctorConfig.load(file);
            } else {
                getLogger().warning("config.yml не найден в jar, беру настройки по умолчанию.");
                config = SNDoctorConfig.defaults();
            }
        } catch (Exception ex) {
            getLogger().warning("Не удалось прочитать config.yml (" + ex.getMessage()
                    + "), беру настройки по умолчанию.");
            config = SNDoctorConfig.defaults();
        }
        // messages.yml is seeded from the language config.yml just asked for, so it has to be
        // done after the config is read, not before.
        for (String line : Messages.install(getDataFolder().toPath(), config.russian)) {
            getLogger().info(line);
        }
        messages = Messages.load(getDataFolder().toPath().resolve("messages.yml"));
        // Flipping general.language on a server that already has messages.yml changes nothing,
        // because the file covers every key. A warning is the difference between a setting and
        // a mystery - and it belongs here rather than in install(), which does not run once the
        // file exists, which is exactly when the flag stops working.
        String languageMismatch = messages.languageMismatch(config.russian);
        if (languageMismatch != null) {
            getLogger().warning(languageMismatch);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Read the volatiles once, at the top: a /sndoctor reload landing between two reads
        // would otherwise answer one command out of two different versions of the files.
        SNDoctorConfig current = config;
        String sub = args.length > 0 ? args[0].toLowerCase() : "scan";
        Texts texts = new Texts(messages, langFrom(args, current));

        if (!sender.hasPermission("sndoctor.use")) {
            texts.send(sender, "chat.no-permission");
            return true;
        }

        switch (sub) {
            case "scan", "full" -> {
                startScan(sender, current.forRequest(
                        sub.equals("full") || hasFlag(args, "--full"), texts.russian()), texts);
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("sndoctor.reload")) {
                    texts.send(sender, "chat.no-permission");
                    return true;
                }
                loadConfiguration();
                // Built again on purpose: the texts the confirmation is written in are the
                // ones that were just loaded, not the ones the command started with.
                new Texts(messages, langFrom(args, config)).send(sender, "chat.reload-done");
                return true;
            }
            case "version" -> {
                texts.send(sender, "chat.version.line",
                        "version", ScanService.VERSION, "target", Analyzer.TARGET);
                texts.send(sender, "chat.version.source");
                return true;
            }
            default -> {
                // Keys spelled out rather than composed in a loop: the self-test greps this
                // file for the keys it uses and checks every one of them exists, and a key
                // built out of pieces is one the grep cannot see.
                texts.send(sender, "chat.help.header", "version", ScanService.VERSION);
                texts.send(sender, "chat.help.scan");
                texts.send(sender, "chat.help.full");
                texts.send(sender, "chat.help.lang");
                texts.send(sender, "chat.help.reload");
                return true;
            }
        }
    }

    private void startScan(CommandSender sender, SNDoctorConfig settings, Texts texts) {
        if (scanning) {
            texts.send(sender, "chat.already-scanning");
            return;
        }
        texts.send(sender, "chat.scanning");
        // Reading dozens of jars is disk-bound work; keep it off the main thread.
        getServer().getScheduler().runTaskAsynchronously(this,
                () -> performScan(sender, settings, texts));
    }

    /**
     * The scan itself. Always called off the main thread.
     *
     * @param sender who asked, or {@code null} for the automatic scan at startup
     */
    private void performScan(CommandSender sender, SNDoctorConfig settings, Texts texts) {
        if (scanning) {
            return;
        }
        scanning = true;
        try {
            File pluginsDir = getDataFolder().getParentFile();
            Path data = getDataFolder().toPath();
            Path override = data.resolve("spigot-names.txt");
            Messages messages = texts.messages();
            Report report = ScanService.scan(
                    pluginsDir,
                    ScanService.currentJavaVersion(),
                    Files.isReadable(override) ? override : null,
                    selfFileName(),
                    messages);
            report.serverVersion = safeServerVersion();

            Path reportPath = settings.reportsEnabled ? writeReports(report, settings, messages) : null;

            if (sender == null) {
                // Startup scan: the console is the only audience, and it gets the short form.
                // Anyone who wants the detail has the file, or can run /sndoctor full.
                //
                // Rendered plain from the same keys rather than by taking the colours back out
                // of the chat summary. The two are not the same thing: a chat line is legacy
                // §-codes, and stripping it reads a second time text the first pass already
                // decided about - "&<gradient:#7B2FFF:#00E1FF>cотчёт" leaves "&cотчёт" in chat,
                // where the ampersand is text the player sees, and a second pass eats it as a
                // colour code. The raw value is converted once, for the sink that prints it.
                for (String line : summarise(report, texts, texts::plain)) {
                    // Trimmed: the chat summary indents its bullets, and a log line that starts
                    // with a space reads as a formatting bug in the console.
                    getLogger().info(line.trim());
                }
                if (reportPath != null) {
                    getLogger().info(texts.plain("chat.report-file", "path", reportPath.toString()));
                }
                scanning = false;
                return;
            }

            List<String> summary = summarise(report, texts, texts::chat);
            String full = new TextRenderer(settings.russian, false, settings.full, messages).render(report);
            getServer().getScheduler().runTask(this, () -> {
                for (String line : summary) {
                    sender.sendMessage(line);
                }
                if (reportPath != null) {
                    texts.send(sender, "chat.report-file", "path", reportPath.toString());
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
                texts.send(sender, "chat.scan-failed", "reason", ex.getMessage());
                scanning = false;
            });
        }
    }

    /**
     * A short, readable summary; the file gets everything.
     *
     * <p>The sink is a parameter rather than a fixed {@code texts.chat}: the same keys are what
     * the console sees at startup, and the console needs them rendered plain from the raw value
     * instead of stripped back out of the chat form. Static and package-private so the
     * self-test can render a real summary without a running server.
     */
    static List<String> summarise(Report report, Texts texts, Texts.Line line) {
        List<String> lines = new ArrayList<>();
        lines.add(line.of("chat.summary.header",
                "version", ScanService.VERSION, "target", Analyzer.TARGET));

        // The counters are four separate keys joined here rather than one key with four holes:
        // the review counter only appears when there is something to review, and a translator
        // must not have to keep the spacing of a line that changes shape.
        StringBuilder counts = new StringBuilder()
                .append(count(line, "chat.summary.red", report.count(Report.Verdict.RED)))
                .append("  ")
                .append(count(line, "chat.summary.yellow", report.count(Report.Verdict.YELLOW)))
                .append("  ")
                .append(count(line, "chat.summary.green", report.count(Report.Verdict.GREEN)));
        if (report.securityCount() > 0) {
            counts.append("  ")
                    .append(count(line, "chat.summary.review", report.securityCount()));
        }
        lines.add(counts.toString());

        List<Report.PluginResult> red = report.byVerdict(Report.Verdict.RED);
        if (!red.isEmpty()) {
            lines.add(line.of("chat.summary.red-header"));
            for (int i = 0; i < Math.min(10, red.size()); i++) {
                Report.PluginResult r = red.get(i);
                String reason = r.findings.isEmpty() ? "" : line.of("chat.summary.red-reason",
                        "title", r.findings.get(0).title(texts.russian()));
                lines.add(line.of("chat.summary.red-line",
                        "plugin", r.facts.displayName(), "reason", reason));
            }
            if (red.size() > 10) {
                lines.add(count(line, "chat.summary.red-more", red.size() - 10));
            }
        }
        if (red.isEmpty() && report.count(Report.Verdict.YELLOW) == 0) {
            lines.add(line.of("chat.summary.all-clear"));
        }
        return lines;
    }

    private static String count(Texts.Line line, String key, int value) {
        return line.of(key, "count", String.valueOf(value));
    }

    private Path writeReports(Report report, SNDoctorConfig settings, Messages messages) {
        try {
            Path dir = getDataFolder().toPath().resolve("reports");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(STAMP);
            Path text = dir.resolve("report-" + stamp + ".txt");
            Files.writeString(text, new TextRenderer(settings.russian, false, true, messages).render(report),
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
