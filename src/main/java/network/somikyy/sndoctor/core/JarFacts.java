/*
 * SNDoctor - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.sndoctor.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything SNDoctor knows about one plugin jar after scanning, before any rule runs. */
public final class JarFacts {

    /** File name of the jar, e.g. {@code NametagEdit-4.5.23.jar}. */
    public String fileName = "";
    public long fileSizeBytes;

    // ---- descriptor -------------------------------------------------------
    public boolean hasPluginYml;
    public boolean hasPaperPluginYml;
    /** Declared plugin name, or the file name if the descriptor is missing. */
    public String pluginName = "";
    public String version = "";
    public String mainClass = "";
    public String apiVersion = "";
    public boolean foliaSupported;
    public final List<String> authors = new ArrayList<>();
    public final List<String> depend = new ArrayList<>();
    public final List<String> softDepend = new ArrayList<>();
    public final List<String> libraries = new ArrayList<>();
    /** Set when the descriptor could not be parsed at all. */
    public String descriptorError = "";

    // ---- bytecode ---------------------------------------------------------
    public int classCount;
    /** Highest class file major version found; 0 if no classes were readable. */
    public int maxClassMajor;
    /** Class file entries that could not be parsed (obfuscated/corrupt/newer format). */
    public int unreadableClasses;
    /** Internal names of classes contained in this jar. */
    public final Set<String> ownClasses = new LinkedHashSet<>();
    /** Internal names of every class referenced by this jar. */
    public final Set<String> referencedClasses = new LinkedHashSet<>();
    /** {@code owner#member} references. */
    public final Set<String> memberRefs = new LinkedHashSet<>();
    /** String literals, capped to keep memory bounded on huge jars. */
    public final List<String> stringConstants = new ArrayList<>();
    /** Which of our own classes referenced a given interesting class - for reporting context. */
    public final Map<String, String> firstReferrer = new LinkedHashMap<>();

    /** Java feature version required by the newest class in this jar (e.g. 17, 21, 25). */
    public int requiredJava() {
        return maxClassMajor >= 44 ? maxClassMajor - 44 : 0;
    }

    /** Root package of the declared main class, e.g. {@code com.example} -> {@code com/example}. */
    public String mainPackagePath() {
        if (mainClass.isEmpty()) {
            return "";
        }
        String internal = mainClass.replace('.', '/');
        int lastSlash = internal.lastIndexOf('/');
        return lastSlash < 0 ? "" : internal.substring(0, lastSlash);
    }

    public String displayName() {
        if (!pluginName.isEmpty()) {
            return pluginName;
        }
        return fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }
}
