package org.bukkit.command;

import java.util.List;

/** Compile-only stub. The real interface is provided by the server at runtime. */
public interface TabCompleter {
    List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args);
}
