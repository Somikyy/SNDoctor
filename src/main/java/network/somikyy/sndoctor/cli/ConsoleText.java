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

import java.io.Console;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Makes the report survive the console it is printed to.
 *
 * <p>Two Windows problems, both of which have to be solved here rather than by telling admins to
 * type magic words before running the tool:
 *
 * <ul>
 *   <li>The console is usually on a legacy code page (cp866 for a Russian Windows). Text written
 *       as UTF-8 arrives as mojibake, and text written as cp866 loses every character that page
 *       does not have - Java silently turns each one into a question mark.</li>
 *   <li>Windows PowerShell does not process ANSI escape sequences unless something enabled them,
 *       so colour codes are printed literally as {@code <-[1m} and bury the report.</li>
 * </ul>
 */
final class ConsoleText {

    /**
     * What to print when the target encoding has no room for a character.
     *
     * <p>Chosen to keep the column width identical wherever the character sits in a fixed
     * layout, so the report does not lose its alignment on the way down to ASCII.
     */
    private static final String[][] FALLBACKS = {
            {"─", "-"},    // ─ horizontal rule
            {"●", "*"},    // ● plugin marker
            {"✗", "x"},    // ✗ blocker
            {"•", "-"},    // • warning
            {"·", "."},    // · informational
            {"→", ">"},    // → the "do this" line
            {"—", "-"},    // — em dash
            {"–", "-"},    // – en dash
            {"…", "..."},  // … ellipsis
            {"«", "\""},   // «
            {"»", "\""},   // »
            {"“", "\""},   // “
            {"”", "\""},   // ”
            {"‘", "'"},    // ‘
            {"’", "'"},    // ’
    };

    private ConsoleText() {
    }

    /**
     * A stream that encodes for this console and rewrites what it cannot encode.
     *
     * <p>Applied at the boundary rather than inside the renderer, so it covers every string on
     * its way out - including finding texts written by rule authors, which is where the em
     * dashes and guillemets live.
     */
    static PrintStream stream(OutputStream raw, Charset charset) {
        return new PrintStream(raw, true, charset) {
            @Override
            public void print(String s) {
                super.print(s == null ? "null" : fitTo(s, charset));
            }

            @Override
            public void println(String s) {
                super.println(s == null ? "null" : fitTo(s, charset));
            }
        };
    }

    /**
     * The encoding the console will actually decode our bytes with.
     *
     * <p>Not simply UTF-8: writing UTF-8 into a cp866 console is exactly how "иероглифы в
     * консоли" happens. When output is redirected to a file or a pipe there is no console to
     * ask, and UTF-8 is the right answer again.
     */
    static Charset outputCharset() {
        // Set by the JVM when stdout is a console; the plain name is JDK 19+, the sun.* one is
        // what a JDK 17 sets. Either way it beats guessing.
        for (String property : new String[]{"stdout.encoding", "sun.stdout.encoding"}) {
            String name = System.getProperty(property);
            if (name != null && !name.isBlank()) {
                try {
                    return Charset.forName(name.trim());
                } catch (Exception ignored) {
                    // Unknown or unsupported name: keep looking.
                }
            }
        }
        Console console = System.console();
        if (console != null) {
            try {
                return console.charset();
            } catch (Throwable ignored) {
                // Older JDK without Console.charset(); fall through.
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Rewrites the parts of {@code text} the encoding cannot carry.
     *
     * <p>Without this, a cp866 console turns ●, ✗, → and every em dash into question marks. The
     * report stays readable, but it looks broken - and a tool that looks broken is not a tool
     * anyone trusts with the question "is my server safe to upgrade".
     */
    static String fitTo(String text, Charset charset) {
        CharsetEncoder encoder = charset.newEncoder();
        if (encoder.canEncode(text)) {
            return text;    // the UTF-8 case, which is every sane console and every file
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (encoder.canEncode(ch)) {
                out.append(ch);
            } else {
                out.append(fallbackFor(ch));
            }
        }
        return out.toString();
    }

    private static String fallbackFor(char ch) {
        for (String[] pair : FALLBACKS) {
            if (pair[0].charAt(0) == ch) {
                return pair[1];
            }
        }
        return "?";
    }

    /**
     * Whether writing ANSI colour to this console produces colour rather than visible garbage.
     *
     * <p>On Windows the answer is no by default. The console has supported VT sequences since
     * Windows 10, but a process has to turn them on, and Windows PowerShell running in the
     * classic console does not - the escapes end up on screen verbatim. So colour is enabled
     * only for terminals known to handle it, and {@code --color} forces it for anyone whose
     * terminal is fine and is not on this list.
     */
    static boolean ansiSupported() {
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        if (System.console() == null) {
            return false;   // redirected into a file or a pipe: colour would be noise
        }
        String os = System.getProperty("os.name", "");
        if (!os.toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return true;
        }
        return System.getenv("WT_SESSION") != null                    // Windows Terminal
                || System.getenv("TERM") != null                      // mintty, Git Bash, Cygwin
                || System.getenv("ANSICON") != null
                || "ON".equalsIgnoreCase(System.getenv("ConEmuANSI"));
    }
}
