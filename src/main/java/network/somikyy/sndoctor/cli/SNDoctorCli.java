/*
 * SNDoctor - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.sndoctor.cli;

import network.somikyy.sndoctor.core.Messages;
import network.somikyy.sndoctor.core.Report;
import network.somikyy.sndoctor.core.ScanService;
import network.somikyy.sndoctor.report.JsonRenderer;
import network.somikyy.sndoctor.report.TextRenderer;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone entry point: {@code java -jar SNDoctor.jar <plugins-dir>}.
 *
 * <p>This mode exists because the moment you most need a compatibility report is the moment
 * your server refuses to start. It touches no Bukkit class and needs no server.
 */
public final class SNDoctorCli {

    public static void main(String[] args) {
        // Written in whatever the console actually decodes, not blindly in UTF-8: a Russian
        // Windows console is on cp866, and UTF-8 bytes arrive there as mojibake. Everything
        // printed through these streams goes through ConsoleText.fitTo first.
        Charset charset = ConsoleText.outputCharset();
        PrintStream out = ConsoleText.stream(System.out, charset);
        PrintStream err = ConsoleText.stream(System.err, charset);

        String dir = null;
        String jsonOut = null;
        String textOut = null;
        Path names = null;
        Path messagesFile = null;
        int serverJava = 0;
        boolean ru = true;
        boolean full = false;
        // Off by default on a plain Windows console, which prints the escape codes instead of
        // obeying them. --color forces it back on for terminals that handle ANSI but are not
        // recognised.
        boolean colour = ConsoleText.ansiSupported();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> {
                    printHelp(out);
                    return;
                }
                case "-v", "--version" -> {
                    out.println("SNDoctor " + ScanService.VERSION);
                    return;
                }
                case "--json" -> jsonOut = next(args, ++i, err, "--json");
                case "--out" -> textOut = next(args, ++i, err, "--out");
                case "--names" -> {
                    String v = next(args, ++i, err, "--names");
                    if (v != null) {
                        names = Path.of(v);
                    }
                }
                case "--messages" -> {
                    String v = next(args, ++i, err, "--messages");
                    if (v != null) {
                        messagesFile = Path.of(v);
                    }
                }
                case "--java" -> {
                    String v = next(args, ++i, err, "--java");
                    if (v != null) {
                        try {
                            serverJava = Integer.parseInt(v.trim());
                        } catch (NumberFormatException ex) {
                            err.println("--java: не число: " + v);
                            System.exit(64);
                        }
                    }
                }
                case "--lang" -> {
                    String v = next(args, ++i, err, "--lang");
                    ru = v == null || !v.equalsIgnoreCase("en");
                }
                case "--full" -> full = true;
                case "--no-color", "--no-colour" -> colour = false;
                case "--color", "--colour" -> colour = true;
                default -> {
                    if (a.startsWith("-")) {
                        err.println("Неизвестный флаг: " + a);
                        printHelp(err);
                        System.exit(64);
                    } else if (dir == null) {
                        dir = a;
                    }
                }
            }
        }

        if (dir == null) {
            // Convenience: run it from a server root and it finds plugins/ by itself.
            File guess = new File("plugins");
            dir = guess.isDirectory() ? guess.getPath() : ".";
        }
        File pluginsDir = new File(dir);
        if (!pluginsDir.isDirectory()) {
            err.println("Не папка: " + pluginsDir.getAbsolutePath());
            System.exit(66);
            return;
        }

        // --messages overrides the language actually being printed, so it is resolved after the
        // whole argument list is read: --messages before --lang must work the same as after.
        Messages messages = Messages.load(ru ? messagesFile : null, ru ? null : messagesFile);

        Report report = ScanService.scan(pluginsDir, serverJava, names, selfJarName(), messages);

        String text = new TextRenderer(ru, colour, full, messages).render(report);
        out.print(text);

        if (textOut != null) {
            writeFile(Path.of(textOut), new TextRenderer(ru, false, true, messages).render(report), err);
            out.println((ru ? "Полный отчёт: " : "Full report: ") + textOut);
        }
        if (jsonOut != null) {
            writeFile(Path.of(jsonOut), JsonRenderer.render(report), err);
            out.println((ru ? "JSON: " : "JSON: ") + jsonOut);
        }

        System.exit(report.exitCode());
    }

    private static String next(String[] args, int i, PrintStream err, String flag) {
        if (i >= args.length) {
            err.println(flag + ": не хватает значения");
            System.exit(64);
            return null;
        }
        return args[i];
    }

    private static void writeFile(Path path, String content, PrintStream err) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            err.println("Не удалось записать " + path + ": " + ex.getMessage());
        }
    }

    /** File name of the running SNDoctor jar, so a scan of plugins/ does not report itself. */
    private static String selfJarName() {
        try {
            Path self = Path.of(SNDoctorCli.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return self.getFileName().toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private static void printHelp(PrintStream out) {
        out.println("""
                SNDoctor {v} — проверка плагинов на совместимость с Minecraft 26.x

                Использование:
                  java -jar SNDoctor.jar [папка-с-плагинами] [флаги]

                Если папку не указать, берётся ./plugins, а если её нет — текущая папка.

                Флаги:
                  --java <N>      Java, на которой будет крутиться сервер (например 25).
                                  Без неё SNDoctor не может сказать «плагину нужна Java новее».
                  --json <файл>   сохранить машинный отчёт
                  --out <файл>    сохранить полный текстовый отчёт
                  --names <файл>  свой список Spigot-имён (дополняет встроенный)
                  --messages <файл>  свои тексты находок вместо встроенных, формат ключ=значение.
                                  Переопределяет тот язык, который выбран --lang. Можно указать
                                  только те ключи, которые правишь — остальные возьмутся из jar.
                  --lang ru|en    язык вывода, по умолчанию ru
                  --full          показывать и справочные находки, и все зелёные плагины
                  --no-color      без ANSI-цветов
                  --color         включить цвета принудительно. По умолчанию в обычной консоли
                                  Windows они выключены: PowerShell печатает сами коды вместо
                                  того, чтобы им подчиняться. В Windows Terminal и Git Bash
                                  цвет включается сам.
                  -v, --version   версия
                  -h, --help      эта справка

                Код возврата: 2 — есть красные, 1 — есть жёлтые, 0 — чисто.
                Полезно в CI: java -jar SNDoctor.jar plugins --java 25 || echo "не обновляемся"

                SNDoctor не запускает и не загружает код плагинов. Он читает jar как архив,
                а class-файлы — как байты.
                """.replace("{v}", ScanService.VERSION));
    }
}
