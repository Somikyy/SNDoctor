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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately small YAML reader for {@code plugin.yml} / {@code paper-plugin.yml}.
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

    private final Map<String, String> scalars = new LinkedHashMap<>();
    private final Map<String, List<String>> lists = new LinkedHashMap<>();

    private MiniYaml() {
    }

    public static MiniYaml parse(String text) {
        MiniYaml yaml = new MiniYaml();
        yaml.doParse(text);
        return yaml;
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
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
