package org.bukkit.scheduler;

import org.bukkit.plugin.Plugin;

/** Compile-only stub. The real interface is provided by the server at runtime. */
public interface BukkitScheduler {
    Object runTask(Plugin plugin, Runnable task);
    Object runTaskAsynchronously(Plugin plugin, Runnable task);
    Object runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delayTicks);
}
