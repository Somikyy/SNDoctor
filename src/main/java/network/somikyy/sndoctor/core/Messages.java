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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every string the user reads, kept out of the code and out in {@code messages.yml}.
 *
 * <p>Three layers, highest first: the admin's {@code plugins/SNDoctor/messages.yml}, the bundled
 * texts of the chosen language, the bundled texts of the other one. The last layer is what keeps
 * a half-finished translation costing the reader one sentence in the wrong language instead of a
 * raw key, and the first is what makes a deleted key harmless - the point of this file is that
 * an admin cannot break the plugin with it.
 *
 * <p><b>No localisation library and no Bukkit configuration API on purpose.</b> SNDoctor ships
 * zero runtime dependencies because it gets dropped onto servers that are already broken, and
 * its CLI mode runs with no Bukkit at all. The file is therefore read by {@link MiniYaml}, the
 * same parser that already reads every scanned {@code plugin.yml} - so the plugin, the CLI and
 * the offline self-test all parse the admin's file identically.
 *
 * <p>One consequence of that order is worth stating out loud, because it is what an admin trips
 * over: the file covers every key, so once it exists the language flag no longer chooses the
 * language - it only chose which bundle the file was seeded from. The file therefore carries the
 * language it was written in ({@link #declaredLanguage}), and {@link #languageMismatch} turns a
 * silent "why is the report still in Russian" into a line in the log.
 *
 * <p>Values carry colour codes in whatever notation the admin likes ({@link Colors}) and
 * {@code {name}} placeholders. {@code {prefix}} is a placeholder like any other, pointing at the
 * {@code prefix} key - which is the whole answer to "how do I take the plugin name out of every
 * message": empty that one value and it is gone from all of them.
 */
public final class Messages {

    /** The key whose value is pasted in wherever {@code {prefix}} appears. */
    private static final String PREFIX_KEY = "prefix";

    /** The key the bundles stamp with their own language, read back by {@link #languageMismatch}. */
    private static final String LANGUAGE_KEY = "language";

    private final Map<String, String> russian;
    private final Map<String, String> english;
    private final Map<String, String> overrides;

    private Messages(Map<String, String> russian, Map<String, String> english,
            Map<String, String> overrides) {
        this.russian = russian;
        this.english = english;
        this.overrides = overrides;
    }

    /** Only what is bundled in the jar. */
    public static Messages bundled() {
        return load(null);
    }

    /**
     * Bundled texts with the admin's file laid over the top.
     *
     * <p>Overrides may be partial: a file with one line replaces one text and leaves the rest
     * alone, so fixing a single awkward sentence does not mean maintaining a copy of all 126.
     *
     * @param messagesYml the admin's file, may be {@code null} or missing. A path ending in
     *                    {@code .txt} is read in the 26.8.1 {@code key=value} format instead,
     *                    so a CI job pinned to {@code --messages my-texts.txt} keeps working.
     */
    public static Messages load(Path messagesYml) {
        Map<String, String> ru = new LinkedHashMap<>();
        Map<String, String> en = new LinkedHashMap<>();
        readResource("/sndoctor/messages-ru.yml", ru);
        readResource("/sndoctor/messages-en.yml", en);
        Map<String, String> user = new LinkedHashMap<>();
        if (messagesYml != null && messagesYml.getFileName().toString()
                .toLowerCase(Locale.ROOT).endsWith(".txt")) {
            user.putAll(readLegacy(messagesYml));
        } else {
            String text = readFile(messagesYml);
            if (text != null) {
                collect(MiniYaml.parse(text), user);
            }
        }
        return new Messages(ru, en, user);
    }

    /**
     * The text for a key, or the key itself when it is missing everywhere, with
     * {@code {prefix}} already resolved.
     *
     * <p>Nothing here throws - a missing text is a documentation bug, not a reason to abandon
     * a scan.
     */
    public String get(String key, boolean russian) {
        return fill(lookup(key, russian), PREFIX_KEY, lookup(PREFIX_KEY, russian));
    }

    /**
     * The text for a key with {@code {name}} placeholders filled in.
     *
     * <p>A handful of findings quote numbers from the jar - the Java version it needs, how many
     * classes failed to parse. Those used to be string concatenation, which cannot survive being
     * moved into a text file, so the text carries named holes and the rule fills them. Names
     * rather than positions: a translator reordering a sentence must be able to move
     * {@code {java}} without counting arguments.
     *
     * @param placeholders name, value, name, value ... - a trailing odd element is ignored
     */
    public String get(String key, boolean russian, String... placeholders) {
        return fill(get(key, russian), placeholders);
    }

    /**
     * Replaces {@code {name}} placeholders in an already-resolved text. Separate from
     * {@link #get} so a caller can convert the colours of the template first and leave whatever
     * a plugin name or an exception text dropped into {@code {reason}} out of it.
     *
     * @param placeholders name, value, name, value ... - a trailing odd element is ignored
     */
    public static String fill(String text, String... placeholders) {
        String result = text;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            if (placeholders[i + 1] != null) {
                result = result.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
            }
        }
        return result;
    }

    /** True when the key exists in the given language. Used by the self-test. */
    public boolean has(String key, boolean russian) {
        return overrides.containsKey(key)
                || (russian ? this.russian : this.english).containsKey(key);
    }

    /** Every key present in either bundled language, sorted. Used by the self-test. */
    public Set<String> keys() {
        Set<String> all = new TreeSet<>(russian.keySet());
        all.addAll(english.keySet());
        return Collections.unmodifiableSet(all);
    }

    /**
     * The language the admin's file says it is written in, or {@code null} when it says nothing.
     *
     * <p>Only the admin's own file is asked. The bundles carry the stamp too, but their answer
     * is never in doubt, and reading it from them would turn every load into a match.
     */
    public String declaredLanguage() {
        String declared = overrides.get(LANGUAGE_KEY);
        return declared == null || declared.trim().isEmpty() ? null : declared.trim();
    }

    /**
     * A line for the log when {@code config.yml} asks for one language and {@code messages.yml}
     * is written in the other, or {@code null} when they agree.
     *
     * <p>Worth the code: switching {@code general.language} on a server that already has the file
     * changes nothing at all, and without this line the admin has no way to find out why.
     *
     * <p>Russian whatever the chosen language is, and word for word the same sentence in every
     * plugin of the line. The admin it is written for is the one who just changed the flag and is
     * about to search the line to find out why nothing happened - one answer to find is worth
     * more than a translated one, and here we cannot know which of the two languages they read.
     *
     * @param russian what {@code config.yml} asked for
     */
    public String languageMismatch(boolean russian) {
        String declared = declaredLanguage();
        String wanted = russian ? "ru" : "en";
        if (declared == null || declared.equalsIgnoreCase(wanted)) {
            return null;
        }
        return "В config.yml выбран язык " + wanted + ", а файл messages.yml написан на языке "
                + declared + " — плагин говорит на языке файла, потому что тексты берутся"
                + " из него. Переведите messages.yml или удалите его: файл создастся заново на"
                + " выбранном языке.";
    }

    private String lookup(String key, boolean preferRussian) {
        String value = overrides.get(key);
        if (value != null) {
            return value;
        }
        Map<String, String> first = preferRussian ? this.russian : this.english;
        Map<String, String> second = preferRussian ? this.english : this.russian;
        value = first.get(key);
        if (value == null) {
            value = second.get(key);
        }
        return value != null ? value : key;
    }

    // ---------------------------------------------------------------- installing

    /**
     * Puts {@code messages.yml} in the plugin folder if it is not there yet, carrying over the
     * admin's edits to the {@code messages-*.txt} files of 26.8.1.
     *
     * <p>The file is seeded from the bundled template of the active language and never touched
     * again - comments, section order and all. Migration rewrites values inside that template
     * rather than dumping a flat map, so an admin who edited three lines in the old format opens
     * the new file and finds their three lines in place, with the documentation around them.
     *
     * @return lines for the server log; empty when there was nothing to do
     */
    public static List<String> install(Path dataFolder, boolean russian) {
        List<String> log = new ArrayList<>();
        if (dataFolder == null) {
            return log;
        }
        Path target = dataFolder.resolve("messages.yml");
        if (Files.exists(target)) {
            return log;
        }
        String template = readResourceText(russian
                ? "/sndoctor/messages-ru.yml" : "/sndoctor/messages-en.yml");
        if (template == null) {
            return log;
        }
        Path legacy = dataFolder.resolve(russian ? "messages-ru.txt" : "messages-en.txt");
        Map<String, String> carried = readLegacy(legacy);
        if (!carried.isEmpty()) {
            template = applyOverrides(template, carried);
        }
        try {
            Files.createDirectories(dataFolder);
            Files.writeString(target, template, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.add(russian
                    ? "Не удалось создать messages.yml (" + e.getMessage()
                            + "). Плагин работает на встроенных текстах."
                    : "Could not create messages.yml (" + e.getMessage()
                            + "). The plugin is running on its built-in texts.");
            return log;
        }
        if (russian) {
            log.add("Создан файл messages.yml — все тексты плагина теперь правятся там.");
            if (!carried.isEmpty()) {
                log.add("Ваши правки из " + legacy.getFileName() + " перенесены в messages.yml"
                        + " — " + russianLines(carried.size())
                        + ". Старый файл больше не читается, его можно удалить.");
            }
        } else {
            log.add("Created messages.yml - every text the plugin prints is edited there now.");
            if (!carried.isEmpty()) {
                log.add("Your edits from " + legacy.getFileName() + " were carried into"
                        + " messages.yml - " + carried.size()
                        + (carried.size() == 1 ? " line" : " lines")
                        + ". The old file is no longer read; you can delete it.");
            }
        }
        return log;
    }

    /**
     * {@code "1 строка"}, {@code "3 строки"}, {@code "11 строк"}.
     *
     * <p>Three forms, not two: a plugin that reports «Ваши 1 строк» in the server log reads as
     * one nobody finished, and the first thing an admin sees on upgrade should not read that
     * way. The 11-14 exception is why a bare {@code n % 10} is not enough.
     */
    private static String russianLines(int count) {
        int tens = count % 100;
        int ones = count % 10;
        if (tens >= 11 && tens <= 14) {
            return count + " строк";
        }
        if (ones == 1) {
            return count + " строка";
        }
        return ones >= 2 && ones <= 4 ? count + " строки" : count + " строк";
    }

    /**
     * Rewrites the scalar values of {@code template} whose dotted key appears in
     * {@code values}, leaving every other line - comments included - byte for byte.
     *
     * <p>Indentation is tracked the same way {@link MiniYaml} tracks it, so the two agree on
     * what {@code rule.dep.vault.fix} means. A key in {@code values} that the template does not
     * have is dropped rather than appended: it is a key from a version that no longer exists.
     */
    static String applyOverrides(String template, Map<String, String> values) {
        StringBuilder out = new StringBuilder(template.length() + 256);
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        String[] lines = template.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) {
                out.append('\n');
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ")) {
                out.append(line);
                continue;
            }
            int colon = keyColon(trimmed);
            if (colon < 0) {
                out.append(line);
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            String key = unquote(trimmed.substring(0, colon).trim());
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            String fullKey = path.isEmpty() ? key : String.join(".", path) + "." + key;
            String value = trimmed.substring(colon + 1).trim();
            if (value.isEmpty()) {
                path.add(key);
                indents.add(indent);
                out.append(line);
                continue;
            }
            String replacement = values.get(fullKey);
            if (replacement == null) {
                out.append(line);
                continue;
            }
            out.append(line, 0, line.indexOf(trimmed))
                    .append(trimmed, 0, colon + 1)
                    .append(' ')
                    .append(quote(replacement));
        }
        return out.toString();
    }

    /** Wraps a value in YAML single quotes, doubling the apostrophes inside. */
    static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    // ---------------------------------------------------------------- loading

    /** Flattens a parsed file into key -> text, keeping blank values blank. */
    private static void collect(MiniYaml yaml, Map<String, String> out) {
        for (String key : yaml.paths()) {
            String value = yaml.getRaw(key);
            if (value != null) {
                out.put(key, value);
            } else if (yaml.isBlank(key)) {
                // "prefix:" with nothing after it is an admin deleting the text, not forgetting
                // to write one. The difference matters exactly here.
                out.put(key, "");
            }
        }
    }

    /** The 26.8.1 format: one {@code key=value} per line, split on the first equals sign. */
    private static Map<String, String> readLegacy(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null || !Files.isReadable(file)) {
            return out;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    // Same byte order mark MiniYaml drops, same reason: without this the admin's
                    // first migrated line turns into a key nobody will ever look up.
                    line = line.startsWith("\uFEFF") ? line.substring(1) : line;
                    first = false;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
                }
            }
        } catch (IOException ignored) {
            // A broken old file must not stop the new one from being written.
        }
        return out;
    }

    private static void readResource(String resource, Map<String, String> out) {
        String text = readResourceText(resource);
        if (text != null) {
            collect(MiniYaml.parse(text), out);
        }
    }

    private static String readResourceText(String resource) {
        try (InputStream in = Messages.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
            return text.toString();
        } catch (IOException e) {
            // A missing bundled file degrades to raw keys in the report; it must not stop a scan.
            return null;
        }
    }

    private static String readFile(Path file) {
        if (file == null || !Files.isReadable(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A broken override file must not stop a scan either.
            return null;
        }
    }

    // ------------------------------------------------------- template rewriting bits

    /** Same rule as {@link MiniYaml}: the colon that ends a key is followed by a space or EOL. */
    private static int keyColon(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ':' && (i == s.length() - 1 || s.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if (first == '\'' && last == '\'') {
                return s.substring(1, s.length() - 1).replace("''", "'");
            }
            if (first == '"' && last == '"') {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
