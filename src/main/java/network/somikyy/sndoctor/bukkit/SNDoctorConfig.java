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

import network.somikyy.sndoctor.core.MiniYaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Settings read from {@code plugins/SNDoctor/config.yml}.
 *
 * <p>Read with SNDoctor's own {@link MiniYaml} rather than Bukkit's configuration API. Bukkit
 * would work here, but the whole point of this plugin is that it can be dropped onto a broken
 * server and carries nothing of its own: reusing the parser that is already in the jar keeps
 * the Bukkit surface at four classes and means the file is parsed the same way in every mode.
 *
 * <p>Every field is final and every value is clamped at load time, so nothing downstream has
 * to defend against a hand-edited config.
 */
final class SNDoctorConfig {

    /** Report language. Russian is the default for the whole SN suite. */
    final boolean russian;
    final boolean updateCheck;
    final boolean scanOnStart;
    final long startDelayTicks;
    final boolean full;
    final boolean reportsEnabled;
    final boolean reportsJson;
    final int reportsKeep;

    private SNDoctorConfig(boolean russian, boolean updateCheck, boolean scanOnStart,
                           long startDelayTicks, boolean full, boolean reportsEnabled,
                           boolean reportsJson, int reportsKeep) {
        this.russian = russian;
        this.updateCheck = updateCheck;
        this.scanOnStart = scanOnStart;
        this.startDelayTicks = startDelayTicks;
        this.full = full;
        this.reportsEnabled = reportsEnabled;
        this.reportsJson = reportsJson;
        this.reportsKeep = reportsKeep;
    }

    static SNDoctorConfig defaults() {
        return new SNDoctorConfig(true, true, true, 100L, false, true, true, 20);
    }

    /**
     * A copy carrying the flags of a single command ({@code full}, {@code --lang}).
     *
     * <p>Per-run overrides are folded into a value object instead of being threaded through
     * every method, so a scan cannot see settings change under it if someone reloads mid-run.
     */
    SNDoctorConfig forRequest(boolean fullFlag, boolean russianFlag) {
        return new SNDoctorConfig(russianFlag, updateCheck, scanOnStart, startDelayTicks,
                full || fullFlag, reportsEnabled, reportsJson, reportsKeep);
    }

    static SNDoctorConfig load(Path file) throws Exception {
        MiniYaml yaml = MiniYaml.parse(Files.readString(file, StandardCharsets.UTF_8));
        return new SNDoctorConfig(
                !"en".equalsIgnoreCase(yaml.get("general.language", "ru")),
                yaml.getBoolean("general.update-check", true),
                yaml.getBoolean("scan.on-start", true),
                // Upper bound is ten minutes: a larger delay means the automatic scan silently
                // never happens, which looks like a broken plugin rather than a typo.
                number(yaml.get("scan.start-delay"), 100L, 0L, 12_000L),
                yaml.getBoolean("scan.full", false),
                yaml.getBoolean("reports.enabled", true),
                yaml.getBoolean("reports.json", true),
                (int) number(yaml.get("reports.keep"), 20L, 0L, 10_000L));
    }

    /**
     * Copies the bundled config.yml next to the plugin on first run.
     *
     * <p>Done by hand instead of {@code saveDefaultConfig()} so that the plugin never touches
     * Bukkit's configuration API - see the class comment.
     *
     * @return true if the file exists afterwards
     */
    static boolean writeDefaultIfMissing(Path file) throws Exception {
        if (Files.exists(file)) {
            return true;
        }
        Files.createDirectories(file.getParent());
        try (InputStream in = SNDoctorConfig.class.getResourceAsStream("/config.yml")) {
            if (in == null) {
                return false;
            }
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    private static long number(String raw, long fallback, long min, long max) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Long.parseLong(raw.trim())));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
