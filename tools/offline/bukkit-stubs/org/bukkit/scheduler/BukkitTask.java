package org.bukkit.scheduler;

/** Compile-only stub. The real interface is provided by the server at runtime. */
public interface BukkitTask {
    int getTaskId();
    void cancel();
}
