package org.bukkit.plugin.java;

import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.logging.Logger;

/** Compile-only stub. The real class is provided by the server at runtime. */
public abstract class JavaPlugin implements Plugin {
    public void onEnable() { }
    public void onDisable() { }
    @Override public File getDataFolder() { return null; }
    @Override public Logger getLogger() { return null; }
    @Override public Server getServer() { return null; }
    public PluginCommand getCommand(String name) { return null; }
}
