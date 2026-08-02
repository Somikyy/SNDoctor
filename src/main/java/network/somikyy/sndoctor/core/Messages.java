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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every string the user reads, kept out of the code.
 *
 * <p>Texts used to be Java literals, which meant a typo in a translation needed a rebuild and a
 * third language needed a programmer. They now live in {@code sndoctor/messages-<lang>.txt}
 * inside the jar, overridable from disk - the same arrangement the Spigot name table already
 * uses, and for the same reason.
 *
 * <p><b>No localisation library on purpose.</b> SNDoctor ships zero runtime dependencies
 * because it gets dropped onto servers that are already broken, and its CLI mode runs with no
 * Bukkit at all - so neither a shaded library nor Bukkit's own configuration API is available.
 * The format is therefore the plainest thing that works: {@code key=value}, one line each,
 * everything after the first {@code =} taken literally. Texts are full of quotes, apostrophes
 * and colons, and a format with no escaping rules is a format nobody can get wrong.
 */
public final class Messages {

    private final Map<String, String> russian;
    private final Map<String, String> english;

    private Messages(Map<String, String> russian, Map<String, String> english) {
        this.russian = russian;
        this.english = english;
    }

    /** Only what is bundled in the jar. */
    public static Messages bundled() {
        return load(null, null);
    }

    /**
     * Bundled texts, with user files laid over the top.
     *
     * <p>Overrides may be partial: a file with one line replaces one text and leaves the rest
     * alone, so fixing a single awkward sentence does not mean maintaining a copy of all 84.
     *
     * @param russianOverride optional file overriding the Russian texts, may be {@code null}
     * @param englishOverride optional file overriding the English texts, may be {@code null}
     */
    public static Messages load(Path russianOverride, Path englishOverride) {
        Map<String, String> ru = new LinkedHashMap<>();
        Map<String, String> en = new LinkedHashMap<>();
        readResource("/sndoctor/messages-ru.txt", ru);
        readResource("/sndoctor/messages-en.txt", en);
        readOverride(russianOverride, ru);
        readOverride(englishOverride, en);
        return new Messages(ru, en);
    }

    /**
     * The text for a key, or the key itself when it is missing.
     *
     * <p>Falls back to the other language before giving up: an incomplete translation should
     * cost the reader one sentence in the wrong language, not a report full of blanks. Nothing
     * here throws - a missing text is a documentation bug, not a reason to abandon a scan.
     */
    public String get(String key, boolean russian) {
        Map<String, String> first = russian ? this.russian : this.english;
        Map<String, String> second = russian ? this.english : this.russian;
        String value = first.get(key);
        if (value == null) {
            value = second.get(key);
        }
        return value != null ? value : key;
    }

    /**
     * The text for a key with {@code {name}} placeholders filled in.
     *
     * <p>A handful of findings quote numbers from the jar - the Java version it needs, how many
     * classes failed to parse. Those used to be string concatenation, which cannot survive being
     * moved into a text file, so the text now carries named holes and the rule fills them.
     * Names rather than positions: a translator reordering a sentence must be able to move
     * {@code {java}} without counting arguments.
     *
     * @param placeholders name, value, name, value ... - a trailing odd element is ignored
     */
    public String get(String key, boolean russian, String... placeholders) {
        String text = get(key, russian);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return text;
    }

    /** True when the key exists in the given language. Used by the self-test. */
    public boolean has(String key, boolean russian) {
        return (russian ? this.russian : this.english).containsKey(key);
    }

    /** Every key present in either language, sorted. Used by the self-test. */
    public Set<String> keys() {
        Set<String> all = new TreeSet<>(russian.keySet());
        all.addAll(english.keySet());
        return Collections.unmodifiableSet(all);
    }

    // ---------------------------------------------------------------- loading

    private static void readResource(String resource, Map<String, String> out) {
        try (InputStream in = Messages.class.getResourceAsStream(resource)) {
            if (in != null) {
                read(new InputStreamReader(in, StandardCharsets.UTF_8), out);
            }
        } catch (IOException ignored) {
            // A missing bundled file degrades to keys in the report; it must not stop a scan.
        }
    }

    private static void readOverride(Path file, Map<String, String> out) {
        if (file == null || !Files.isReadable(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            read(reader, out);
        } catch (IOException ignored) {
            // A broken override file must not stop a scan either.
        }
    }

    private static void read(Reader reader, Map<String, String> out) throws IOException {
        BufferedReader br = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            // Split on the FIRST '=' only: values contain them, keys never do.
            if (eq > 0) {
                out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        }
    }
}
