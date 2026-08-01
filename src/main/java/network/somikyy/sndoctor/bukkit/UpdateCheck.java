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

import network.somikyy.sndoctor.core.ScanService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version check against GitHub Releases.
 *
 * <p>Fully asynchronous and fully optional - {@code general.update-check: false} means the
 * plugin never opens a socket. Every failure is swallowed: a tool an admin installs because
 * the server is already broken has no business adding warnings of its own to that console.
 *
 * <p>Uses the JDK HTTP client, so this stays true to the zero-runtime-dependency rule.
 */
final class UpdateCheck {

    private static final String API =
            "https://api.github.com/repos/Somikyy/SNDoctor/releases/latest";

    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");

    private UpdateCheck() {
    }

    /** Runs off the main thread and reports through the plugin logger, or says nothing. */
    static void run(SNDoctorPlugin plugin, boolean russian) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String latest = fetch();
            if (latest == null || !isNewer(latest, ScanService.VERSION)) {
                return;
            }
            if (russian) {
                plugin.getLogger().info("Доступна новая версия SNDoctor: " + latest
                        + " (у тебя " + ScanService.VERSION + ")"
                        + " — https://github.com/Somikyy/SNDoctor/releases");
            } else {
                plugin.getLogger().info("A newer SNDoctor is available: " + latest
                        + " (you have " + ScanService.VERSION + ")"
                        + " - https://github.com/Somikyy/SNDoctor/releases");
            }
        });
    }

    private static String fetch() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            // GitHub rejects requests without a User-Agent.
            HttpRequest request = HttpRequest.newBuilder(URI.create(API))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "SNDoctor/" + ScanService.VERSION)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            Matcher matcher = TAG.matcher(response.body());
            return matcher.find() ? matcher.group(1).trim() : null;
        } catch (Exception | LinkageError ignored) {
            // No network, no releases yet, GitHub down, DNS blocked - all the same answer.
            return null;
        }
    }

    /**
     * Numeric comparison rather than {@code !equals}, so a local build that is ahead of the
     * latest release does not nag on every start.
     */
    static boolean isNewer(String candidate, String current) {
        String[] a = candidate.split("[^0-9]+");
        String[] b = current.split("[^0-9]+");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int left = part(a, i);
            int right = part(b, i);
            if (left != right) {
                return left > right;
            }
        }
        return false;
    }

    private static int part(String[] parts, int index) {
        if (index >= parts.length || parts[index].isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
