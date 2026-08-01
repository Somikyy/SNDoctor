package org.bukkit;

import org.bukkit.scheduler.BukkitScheduler;

/** Compile-only stub. The real interface is provided by the server at runtime. */
public interface Server {
    String getVersion();
    String getBukkitVersion();
    BukkitScheduler getScheduler();
}
