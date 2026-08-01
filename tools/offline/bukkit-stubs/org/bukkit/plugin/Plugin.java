package org.bukkit.plugin;

import org.bukkit.Server;

import java.io.File;
import java.util.logging.Logger;

/** Compile-only stub. The real interface is provided by the server at runtime. */
public interface Plugin {
    File getDataFolder();
    Logger getLogger();
    Server getServer();
}
