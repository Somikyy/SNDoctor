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

import network.somikyy.sndoctor.core.Colors;
import network.somikyy.sndoctor.core.Messages;
import org.bukkit.command.CommandSender;

/**
 * Bridge from {@link Messages} (the texts of {@code messages.yml}) to the two sinks the
 * in-server side has: player chat and the server log.
 *
 * <p>Chat gets legacy {@code §} codes through {@link Colors#toLegacy} rather than an Adventure
 * component - the reason is written down in {@link Colors}, and it is that SNDoctor has to keep
 * working on the Spigot and old-Paper servers it exists to diagnose. The practical consequence
 * for this class is that everything it produces is a plain {@link String}, so the plugin's whole
 * Bukkit surface stays {@code sendMessage(String)}.
 *
 * <p>Both paths convert the admin's colour codes first and substitute placeholders second. The
 * order is the point: SNDoctor prints names taken out of other people's jars, and a plugin
 * calling itself {@code &cFree Ranks} must land in chat as those characters rather than as a
 * colour instruction of its own.
 */
final class Texts {

    private final Messages messages;
    private final boolean russian;

    Texts(Messages messages, boolean russian) {
        this.messages = messages;
        this.russian = russian;
    }

    /**
     * One rendered line, bound to the sink that is about to print it: {@link #chat} for a
     * player, {@link #plain} for the console and the log.
     *
     * <p>It exists because the scan summary has two audiences - whoever asked for the scan, and
     * the console at startup - and each has to be rendered from the raw value. Rendering one of
     * them by taking the colours back out of the other looks like the same thing and is not:
     * a chat line is legacy {@code §} codes, and reading it a second time reads as a code
     * whatever a dropped tag left standing next to a code letter.
     */
    @FunctionalInterface
    interface Line {
        String of(String key, String... placeholders);
    }

    /** One chat line, colours rendered as {@code §} codes. */
    String chat(String key, String... placeholders) {
        return Messages.fill(Colors.toLegacy(messages.get(key, russian)), placeholders);
    }

    /**
     * The same line with the colours taken out - for the server log, which renders none.
     *
     * <p>The prefix stays: the log is fed by the same keys as chat, and one text an admin can
     * edit in one place is worth more than a second set of log-only keys that would drift.
     */
    String plain(String key, String... placeholders) {
        return Messages.fill(Colors.strip(messages.get(key, russian)), placeholders);
    }

    void send(CommandSender to, String key, String... placeholders) {
        to.sendMessage(chat(key, placeholders));
    }

    /** Language of these texts, for the report renderers that still take a flag. */
    boolean russian() {
        return russian;
    }

    /**
     * The catalogue behind these texts, for the renderers that read it directly.
     *
     * <p>Handing out the same instance rather than re-reading the field is what keeps a scan
     * consistent: a {@code /sndoctor reload} landing mid-scan must not put the chat summary and
     * the report file on two different versions of the file.
     */
    Messages messages() {
        return messages;
    }
}
