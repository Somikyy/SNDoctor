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

import java.util.Locale;

/**
 * Colour codes an admin might write in {@code messages.yml}, translated for the sink that is
 * about to print them.
 *
 * <p>The rule the SN line follows: an admin writes colours the way they already know how, and
 * the plugin works it out. Four notations are accepted inside the same string -
 * <ul>
 *   <li>{@code &c}, {@code &l}, {@code &r} - the codes everyone has typed since 2011
 *       ({@code §} works too, for text pasted out of another plugin's config);</li>
 *   <li>{@code &#7B2FFF} - the short HEX form, the one people actually ask for;</li>
 *   <li>{@code &x&7&B&2&F&F&F} - the long HEX form Spigot's own serializer emits, so a value
 *       copied out of a Spigot-era config keeps its colour;</li>
 *   <li>{@code <red>}, {@code <#7B2FFF>}, {@code <bold>} - MiniMessage tags, which the rest of
 *       the line uses, so a text moved between SN plugins keeps working.</li>
 * </ul>
 *
 * <p>A bracket the admin means as text is escaped the MiniMessage way - {@code \<red>} shows the
 * letters, {@code \\} shows one backslash - and both exits below read that the same, so the
 * player and the log never disagree about what was markup.
 *
 * <p><b>Why chat output is legacy {@code §} codes and not MiniMessage.</b> Every other plugin
 * of the line hands MiniMessage to Adventure. SNDoctor deliberately does not, and the reason is
 * the plugin's whole purpose: it gets dropped onto a server that is already broken, including
 * Spigot and old Paper builds, to explain why. Adventure - and therefore
 * {@code sendMessage(Component)} - does not exist on Spigot at all, so that call is a
 * {@code NoSuchMethodError} on exactly the servers where the report matters most.
 * {@code CommandSender.sendMessage(String)} with {@code §} codes is plain Bukkit API, present
 * everywhere since 2011, and it keeps the plugin's Bukkit surface at the ten methods the
 * self-test records. The price is real and is written down in the README: {@code <gradient>},
 * {@code <click>} and {@code <hover>} have no legacy equivalent, so they are dropped rather
 * than rendered - the text survives, the effect does not.
 *
 * <p>Dependency-free and Bukkit-free on purpose: the conversion is pure string work, which is
 * what lets the offline self-test assert on it without a server jar, and what lets the CLI -
 * which writes to a {@code PrintStream}, not to a player - use {@link #strip} from the same
 * class.
 */
public final class Colors {

    /** Hex digit -> MiniMessage colour name, in vanilla code order. */
    private static final String[] NAMED = {
        "black", "dark_blue", "dark_green", "dark_aqua",
        "dark_red", "dark_purple", "gold", "gray",
        "dark_gray", "blue", "green", "aqua",
        "red", "light_purple", "yellow", "white",
    };

    private static final String HEX_DIGITS = "0123456789abcdef";

    private static final char SECTION = '§';

    private Colors() {
    }

    /**
     * Rewrites every accepted notation into legacy {@code §} codes, for in-game chat.
     *
     * <p>The wrinkle that costs {@link Colors} of the other SN plugins a whole paragraph -
     * that a legacy colour code clears bold and italic while a MiniMessage tag does not -
     * disappears here: the output *is* legacy, so the server applies exactly the semantics the
     * person who typed {@code &c&lЖирный &aОбычный} expects. Closing tags are the only place
     * the two models genuinely disagree, and {@code </red>} becomes {@code §r}: legacy has no
     * way to end one colour and restore the previous one, and resetting is the closer of the
     * two wrong answers - it ends what the admin meant to end.
     *
     * <p>One defence the MiniMessage-emitting {@code Colors} of the other SN plugins needs and
     * this one does not: there a converted code is a tag, so a backslash standing right in
     * front of it - {@code "путь\&cтекст"} - would escape the tag the converter just wrote and
     * the player would read {@code <red>} instead of seeing red; that one doubles the backslash
     * before writing the tag. Here a converted code is {@code §c}, which nothing escapes, so
     * the same value reaches chat as a backslash followed by red text and needs no defence.
     */
    public static String toLegacy(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()
                    && (text.charAt(i + 1) == '<' || text.charAt(i + 1) == '\\')) {
                // MiniMessage's own escape, which turns up here because texts move between SN
                // plugins: "\<" means "show the bracket, do not open a tag". Legacy chat has no
                // escape of its own - the string goes to sendMessage as it stands - so the
                // backslash is consumed and what it protected is printed. That is also what
                // makes the two exits agree: strip does exactly the same thing, so the player
                // and the log both end up reading "<red>" as the word it is.
                out.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '&' || c == SECTION) {
                int width = codeWidth(text, i);
                if (width > 0) {
                    appendCode(out, text, i, width);
                    i += width;
                    continue;
                }
                // A lone ampersand in ordinary text - "Том & Джерри" must survive untouched.
                out.append(c);
                i++;
                continue;
            }
            if (c == '<') {
                // A recognised tag is taken whole, arguments and all, so nothing inside it is
                // read as a colour code. The case that forces the rule:
                // <click:open_url:https://site/?a=1&b=2> - the "&b" is half a query string, and
                // reading it as a colour would break both the link and the tag around it. Only
                // tags the list below knows are protected, so "<5 и &a>" is still not a tag and
                // the code inside it is still a code.
                int close = tagEnd(text, i);
                if (close > i) {
                    String inner = text.substring(i + 1, close);
                    if (inner.equals("newline") || inner.equals("br")) {
                        // A legacy chat string still breaks on '\n', so the tag has a real
                        // legacy form - it just is not a colour code.
                        out.append('\n');
                        i = close + 1;
                        continue;
                    }
                    String legacy = tagToLegacy(inner);
                    if (legacy != null) {
                        out.append(legacy);
                        i = close + 1;
                        continue;
                    }
                    if (isKnownTag(inner)) {
                        // Known markup with no legacy equivalent: <gradient>, <click>, <hover>.
                        // Dropped, not printed - a player reading "<click:run_command:/sndoctor>"
                        // in chat is worse off than one reading the sentence without the link.
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * The same text with every colour instruction removed - for sinks that render no markup at
     * all: the report file, the CLI stdout, the server log.
     *
     * <p>Unknown tags are left alone on purpose. {@code <файл>} inside a usage string is not
     * markup, it is the word the admin wrote, and eating it would be worse than leaving it.
     * For the same reason the search for a tag's closing bracket is bounded ({@link #tagEnd}):
     * a bare {@code <} in a sentence must not swallow the words up to the next {@code >}
     * somewhere further along the line.
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()
                    && (text.charAt(i + 1) == '<' || text.charAt(i + 1) == '\\')) {
                // The escape is consumed and what it protected is kept, the same way toLegacy
                // handles it: a value written as "\<red>текст" says the brackets are the
                // admin's own text, so the log and chat both show them.
                out.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '&' || c == SECTION) {
                int width = codeWidth(text, i);
                if (width > 0) {
                    i += width;
                    continue;
                }
            }
            if (c == '<') {
                // Recognised tags are taken whole here too, so an ampersand inside a tag
                // argument - the "&b" of a query string in <click:open_url:...> - is part of
                // the argument being dropped and is never mistaken for a colour code.
                int close = tagEnd(text, i);
                if (close > i) {
                    String inner = text.substring(i + 1, close);
                    if (inner.equals("newline") || inner.equals("br")) {
                        out.append('\n');
                        i = close + 1;
                        continue;
                    }
                    if (isKnownTag(inner)) {
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Index of the {@code >} that closes the tag opened at {@code i}, or {@code -1}.
     *
     * <p>Not {@code indexOf('>')}, because a MiniMessage tag argument may be quoted and may
     * therefore contain a whole tag of its own: in
     * {@code <hover:show_text:'<red>подсказка'>слово} the first {@code >} belongs to the
     * tooltip, not to the hover. Taking it ends the tag early, and since {@code hover} is
     * dropped rather than rendered the leftover {@code подсказка'>} lands in chat as text the
     * admin never wrote as text. Quotes are skipped over here, with a backslash escaping the
     * character after it the way MiniMessage's own parser does.
     *
     * <p>A quote only opens an argument when it stands right after the {@code :} that begins
     * one - the same rule MiniMessage uses, and the reason {@code <don't>} is still read as the
     * word in brackets it obviously is rather than as an unterminated string.
     *
     * <p>The search is also bounded: a second {@code <} ends it with -1. Without the bound an
     * unclosed bracket pairs with the closing bracket of a real tag further along and the words
     * in between vanish, as long as the span between them happens to start with a tag name the
     * list below knows. {@code "<hover:show_text:подсказка <red>слово"} - an admin one
     * {@code >} short - loses the whole clause and leaves "слово". Fuzzing the bounded scan
     * against the unbounded one over 400 000 random values put the shortest such loss at
     * {@code "а{k#а<c:<>x"}.
     *
     * <p>The bound is a second {@code <} and nothing else. Stopping at whitespace too would be a
     * tighter net and was tried, but it costs a tag people really write:
     * {@code <click:run_command:/sndoctor scan>} carries an unquoted space and would stop being
     * recognised - and since {@code click} has no legacy form, not recognising it means printing
     * it at a player instead of dropping it.
     */
    private static int tagEnd(String text, int i) {
        char quote = 0;
        for (int at = i + 1; at < text.length(); at++) {
            char c = text.charAt(at);
            if (quote != 0) {
                if (c == '\\') {
                    at++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if ((c == '\'' || c == '"') && text.charAt(at - 1) == ':') {
                quote = c;
            } else if (c == '>') {
                return at;
            } else if (c == '<') {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Appends the legacy form of the {@code width}-character code starting at {@code i}.
     *
     * <p>Both HEX notations collapse into Spigot's {@code §x§R§R§G§G§B§B}, which is what the
     * server's own legacy deserializer understands; the short {@code &#RRGGBB} form exists in
     * configs but never on the wire.
     */
    private static void appendCode(StringBuilder out, String text, int i, int width) {
        switch (width) {
            case 8 -> appendHex(out, lower(text, i + 2, 6));
            case 14 -> appendHex(out, readLongHex(text, i));
            default -> out.append(SECTION).append(Character.toLowerCase(text.charAt(i + 1)));
        }
    }

    private static void appendHex(StringBuilder out, String hex) {
        out.append(SECTION).append('x');
        for (int i = 0; i < hex.length(); i++) {
            out.append(SECTION).append(hex.charAt(i));
        }
    }

    /** Length of the legacy/HEX code starting at {@code i}, or 0 when there is none. */
    private static int codeWidth(String text, int i) {
        if (i + 1 >= text.length()) {
            return 0;
        }
        if (text.charAt(i + 1) == '#' && isHexRun(text, i + 2, 6)) {
            return 8;
        }
        if (readLongHex(text, i) != null) {
            return 14;
        }
        char code = Character.toLowerCase(text.charAt(i + 1));
        return HEX_DIGITS.indexOf(code) >= 0 || decoration(code) != null || code == 'r' ? 2 : 0;
    }

    /**
     * Reads Spigot's long HEX form {@code &x&7&B&2&F&F&F} at {@code i}, or {@code null}.
     *
     * <p>The markers may be mixed - {@code §x&7&B...} turns up in configs that went through a
     * half-finished search-and-replace - so each pair is checked for "a marker" rather than for
     * the same marker that opened the run.
     */
    private static String readLongHex(String text, int i) {
        if (i + 13 >= text.length() || Character.toLowerCase(text.charAt(i + 1)) != 'x') {
            return null;
        }
        StringBuilder hex = new StringBuilder(6);
        for (int pair = 0; pair < 6; pair++) {
            int at = i + 2 + pair * 2;
            char marker = text.charAt(at);
            char digit = Character.toLowerCase(text.charAt(at + 1));
            if ((marker != '&' && marker != SECTION) || HEX_DIGITS.indexOf(digit) < 0) {
                return null;
            }
            hex.append(digit);
        }
        return hex.toString();
    }

    private static boolean isHexRun(String text, int from, int length) {
        if (from < 0 || from + length > text.length()) {
            return false;
        }
        for (int i = from; i < from + length; i++) {
            if (HEX_DIGITS.indexOf(Character.toLowerCase(text.charAt(i))) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String lower(String text, int from, int length) {
        return text.substring(from, from + length).toLowerCase(Locale.ROOT);
    }

    private static String decoration(char code) {
        switch (code) {
            case 'k':
                return "obfuscated";
            case 'l':
                return "bold";
            case 'm':
                return "strikethrough";
            case 'n':
                return "underlined";
            case 'o':
                return "italic";
            default:
                return null;
        }
    }

    /**
     * The legacy code a MiniMessage tag maps to, or {@code null} when it has no legacy form.
     *
     * <p>Aliases are included because MiniMessage accepts them and an admin copying a line out
     * of another SN plugin should not have to know which spelling they used.
     */
    private static String tagToLegacy(String inner) {
        boolean closing = inner.startsWith("/");
        String tag = (closing ? inner.substring(1) : inner).toLowerCase(Locale.ROOT);
        if (tag.indexOf(':') >= 0) {
            // <gradient:...>, <click:...> - parameterised tags never have a legacy form.
            return null;
        }
        String code = codeForTag(tag);
        if (code == null) {
            return null;
        }
        return closing ? SECTION + "r" : code;
    }

    private static String codeForTag(String tag) {
        if (tag.startsWith("#")) {
            return tag.length() == 7 && isHexRun(tag, 1, 6)
                    ? hexCode(tag.substring(1)) : null;
        }
        for (int i = 0; i < NAMED.length; i++) {
            if (NAMED[i].equals(tag)) {
                return String.valueOf(SECTION) + HEX_DIGITS.charAt(i);
            }
        }
        switch (tag) {
            case "reset":
                return SECTION + "r";
            case "bold":
            case "b":
                return SECTION + "l";
            case "italic":
            case "i":
            case "em":
                return SECTION + "o";
            case "underlined":
            case "u":
                return SECTION + "n";
            case "strikethrough":
            case "st":
                return SECTION + "m";
            case "obfuscated":
            case "obf":
                return SECTION + "k";
            default:
                return null;
        }
    }

    private static String hexCode(String hex) {
        StringBuilder out = new StringBuilder(14);
        appendHex(out, hex.toLowerCase(Locale.ROOT));
        return out.toString();
    }

    /**
     * True for MiniMessage tags the converters should swallow. Listing them rather than eating
     * every {@code <...>} is the whole point: the list is what separates markup from a word in
     * angle brackets that the admin meant to be read.
     */
    private static boolean isKnownTag(String inner) {
        String tag = inner.startsWith("/") ? inner.substring(1) : inner;
        // <!italic> is the same tag as <italic>, turned off. Without this the negation form
        // would not be recognised as markup at all, and it would reach the report and the
        // console as the literal characters "<!italic>".
        if (tag.startsWith("!")) {
            tag = tag.substring(1);
        }
        int colon = tag.indexOf(':');
        if (colon >= 0) {
            tag = tag.substring(0, colon);
        }
        tag = tag.toLowerCase(Locale.ROOT);
        if (tag.startsWith("#")) {
            return tag.length() == 7 && isHexRun(tag, 1, 6);
        }
        for (String name : NAMED) {
            if (name.equals(tag)) {
                return true;
            }
        }
        switch (tag) {
            case "reset":
            case "bold":
            case "b":
            case "italic":
            case "i":
            case "em":
            case "underlined":
            case "u":
            case "strikethrough":
            case "st":
            case "obfuscated":
            case "obf":
            case "color":
            case "colour":
            case "c":
            case "gradient":
            case "rainbow":
            case "transition":
            case "shadow_color":
            case "font":
            case "click":
            case "hover":
            case "insert":
            case "insertion":
            case "key":
            case "lang":
            case "tr":
            case "translate":
            case "selector":
            case "sel":
            case "score":
            case "nbt":
            case "data":
            case "pride":
                return true;
            default:
                return false;
        }
    }
}
