package org.bukkit.command;

/** Compile-only stub. The real interface is provided by the server at runtime. */
public interface CommandExecutor {
    boolean onCommand(CommandSender sender, Command command, String label, String[] args);
}
