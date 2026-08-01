package org.bukkit.command;

/** Compile-only stub. The real class is provided by the server at runtime. */
public interface CommandSender {
    void sendMessage(String message);
    boolean hasPermission(String name);
    String getName();
}
