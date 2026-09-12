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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A deliberately small YAML reader - for the {@code plugin.yml} of the jars being scanned, and
 * for SNDoctor's own {@code config.yml} and {@code messages.yml}.
 *
 * <p>SNDoctor must run standalone (CLI mode) on a server that will not start, so it cannot
 * rely on the SnakeYAML that Bukkit normally provides, and it must not ship a shaded copy.
 * A plugin descriptor only ever needs a tiny subset of YAML, so that subset is what this
 * parses:
 *
 * <ul>
 *   <li>top-level {@code key: value} scalars</li>
 *   <li>block lists ({@code key:} followed by indented {@code - item} lines)</li>
 *   <li>inline lists ({@code key: [a, b]})</li>
 *   <li>nested maps, flattened to dotted keys ({@code commands.foo.description})</li>
 *   <li>{@code #} comments, quoted scalars, blank lines</li>
 * </ul>
 *
 * <p>Anything more exotic is ignored rather than treated as an error: a descriptor SNDoctor
 * cannot fully parse must still produce a useful report about the rest of the jar.
 */
public final class MiniYaml {

    /** Written as an escape, not pasted in: the character itself is invisible in an editor. */
    private static final char BOM = '\uFEFF';

    private final Map<String, String> scalars = new LinkedHashMap<>();
    private final Map<String, List<String>> lists = new LinkedHashMap<>();

    /** Every dotted key ever seen, section headers included, in file order. */
    private final Set<String> paths = new LinkedHashSet<>();

    private MiniYaml() {
    }

    public static MiniYaml parse(String text) {
        MiniYaml yaml = new MiniYaml();
        yaml.doParse(stripBom(text));
        return yaml;
    }

    /**
     * Drops a leading byte order mark, because Windows editors add one and nothing downstream
     * would ever mention it.
     *
     * <p>U+FEFF is not whitespace, so {@code trim()} keeps it and the first key of the file
     * silently becomes the mark followed by the key - a key nobody asks for. The file still
     * parses, the plugin still starts, and the admin's first override is quietly ignored. The
     * shipped template opens with a comment and so never hit this, but a partial override
     * file - the form the README recommends - starts with a key on line 1, which is exactly
     * where the mark lands. Handled here rather than at each call site: the same parser reads
     * {@code messages.yml}, {@code config.yml} and every scanned {@code plugin.yml}.
     */
    private static String stripBom(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == BOM
                ? text.substring(1) : text;
    }

    /** Scalar value for a dotted key, or {@code null}. */
    public String get(String key) {
        return scalars.get(key);
    }

    public String get(String key, String fallback) {
        String v = scalars.get(key);
        return v == null ? fallback : v;
    }

    public boolean getBoolean(String key, boolean fallback) {
        String v = scalars.get(key);
        if (v == null) {
            return fallback;
        }
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equals("1");
    }

    /** List value for a dotted key; a scalar at that key is returned as a single-element list. */
    public List<String> getList(String key) {
        List<String> l = lists.get(key);
        if (l != null) {
            return l;
        }
        String s = scalars.get(key);
        if (s != null && !s.isEmpty()) {
            List<String> single = new ArrayList<>();
            single.add(s);
            return single;
        }
        return List.of();
    }

    public boolean has(String key) {
        return scalars.containsKey(key) || lists.containsKey(key);
    }

    /**
     * The scalar exactly as written, or {@code null} when the key carries no scalar at all.
     *
     * <p>Distinct from {@link #get(String)} only in intent, and the intent is the whole reason
     * both exist: for a setting a blank value means "I did not fill this in" and the default is
     * the right answer, while for a message it means the opposite - {@code prefix: ''} is an
     * admin deliberately deleting the plugin name from every line, and answering that with the
     * default would be the plugin arguing with them.
     */
    public String getRaw(String key) {
        return scalars.get(key);
    }

    /**
     * True when the key was written with no value at all ({@code prefix:}) rather than with a
     * blank one - and is a leaf, not a section header. YAML calls that null; for messages it
     * means the same thing as {@code ''}.
     */
    public boolean isBlank(String key) {
        return paths.contains(key) && !scalars.containsKey(key) && !lists.containsKey(key)
                && !hasChildren(key);
    }

    /** Every dotted key in the file, section headers included, in the order they were written. */
    public Set<String> paths() {
        return Collections.unmodifiableSet(paths);
    }

    private boolean hasChildren(String key) {
        String head = key + ".";
        for (String path : paths) {
            if (path.startsWith(head)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- parsing

    private void doParse(String text) {
        // path[i] holds the key owning indentation level i
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        String listOwner = null;
        int listIndent = -1;

        for (String rawLine : text.split("\r?\n", -1)) {
            String line = stripComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            int indent = indentOf(line);
            String trimmed = line.trim();

            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                if (listOwner != null && indent >= listIndent) {
                    String item = unquote(trimmed.length() > 1 ? trimmed.substring(1).trim() : "");
                    if (!item.isEmpty()) {
                        lists.computeIfAbsent(listOwner, k -> new ArrayList<>()).add(item);
                    }
                }
                continue;
            }

            int colon = findKeyColon(trimmed);
            if (colon < 0) {
                continue; // not a mapping line we understand
            }
            String key = unquote(trimmed.substring(0, colon).trim());
            String value = trimmed.substring(colon + 1).trim();
            if (key.isEmpty()) {
                continue;
            }

            // pop deeper-or-equal levels off the path
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            String fullKey = path.isEmpty() ? key : String.join(".", path) + "." + key;
            paths.add(fullKey);

            if (value.isEmpty()) {
                // either a nested map or the header of a block list
                path.add(key);
                indents.add(indent);
                listOwner = fullKey;
                listIndent = indent;
            } else if (value.startsWith("[") && value.endsWith("]")) {
                List<String> items = new ArrayList<>();
                String inner = value.substring(1, value.length() - 1).trim();
                if (!inner.isEmpty()) {
                    for (String part : inner.split(",")) {
                        String item = unquote(part.trim());
                        if (!item.isEmpty()) {
                            items.add(item);
                        }
                    }
                }
                lists.put(fullKey, items);
                listOwner = null;
            } else {
                scalars.put(fullKey, unquote(value));
                listOwner = null;
            }
        }
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** Finds the mapping colon, ignoring colons inside quotes. */
    private static int findKeyColon(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ':') {
                // "key:" or "key: value" - a colon inside a bare scalar (e.g. a URL) has no
                // trailing space and is not at end of line
                if (i == s.length() - 1 || s.charAt(i + 1) == ' ') {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if (first == '\'' && last == '\'') {
                // YAML's single-quote escape. It matters since messages.yml arrived: «server-jar'ы»
                // is an ordinary thing for a translator to write, and losing the apostrophe - or
                // worse, ending the value early - would be a bug with no visible cause.
                return s.substring(1, s.length() - 1).replace("''", "'");
            }
            if (first == '"' && last == '"') {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
