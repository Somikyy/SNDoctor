package org.bukkit.scheduler;

import org.bukkit.plugin.Plugin;

/**
 * Compile-only stub. The real interface is provided by the server at runtime.
 *
 * <p>THE RETURN TYPE IS PART OF THE SIGNATURE. A JVM method descriptor includes it, so a stub
 * declaring {@code Object} where the server declares {@code BukkitTask} compiles cleanly and
 * then throws NoSuchMethodError on a real server: the jar asks for a method nobody has. Every
 * stub in this directory must copy the real Bukkit signature exactly, return type included.
 *
 * <p>This is not left to care alone. {@code tools/offline/bukkit-api-surface.txt} records the
 * exact descriptors this project emits, the self-test checks the offline build against it, and
 * CI checks it against a build made with the real paper-api. Drift fails the build.
 */
public interface BukkitScheduler {
    BukkitTask runTask(Plugin plugin, Runnable task);
    BukkitTask runTaskAsynchronously(Plugin plugin, Runnable task);
    BukkitTask runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delayTicks);
}
