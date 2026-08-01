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
import java.util.List;

/** One problem found in one plugin jar. */
public final class Finding {

    /** How badly this breaks the plugin. Order matters: highest severity first. */
    public enum Severity {
        /** Plugin will not load or will crash on 26.1+. */
        BLOCKER("КРАСНЫЙ", "BLOCKER"),
        /** Feature is gone or silently no longer works; plugin may still load. */
        BREAKING("СЛОМАНО", "BREAKING"),
        /** Deprecated, behaviour changed, or a risky dependency. */
        WARN("ВНИМАНИЕ", "WARN"),
        /** Worth knowing, not a problem by itself. */
        INFO("СПРАВКА", "INFO"),
        /** Needs a human look for safety reasons. Never counted as a compatibility problem. */
        SECURITY("БЕЗОПАСНОСТЬ", "SECURITY");

        public final String ru;
        public final String en;

        Severity(String ru, String en) {
            this.ru = ru;
            this.en = en;
        }
    }

    /** Bilingual text of a rule. */
    public static final class Text {
        public final String titleRu;
        public final String titleEn;
        public final String whyRu;
        public final String whyEn;
        public final String fixRu;
        public final String fixEn;

        public Text(String titleRu, String titleEn, String whyRu, String whyEn,
                    String fixRu, String fixEn) {
            this.titleRu = titleRu;
            this.titleEn = titleEn;
            this.whyRu = whyRu;
            this.whyEn = whyEn;
            this.fixRu = fixRu;
            this.fixEn = fixEn;
        }
    }

    public final String id;
    public final Severity severity;
    public final Text text;
    /** Concrete things found in the jar that triggered this rule. */
    public final List<String> evidence = new ArrayList<>();

    public Finding(String id, Severity severity, Text text) {
        this.id = id;
        this.severity = severity;
        this.text = text;
    }

    public Finding evidence(String item) {
        if (item != null && !item.isEmpty() && !evidence.contains(item)) {
            evidence.add(item);
        }
        return this;
    }

    public String title(boolean ru) {
        return ru ? text.titleRu : text.titleEn;
    }

    public String why(boolean ru) {
        return ru ? text.whyRu : text.whyEn;
    }

    public String fix(boolean ru) {
        return ru ? text.fixRu : text.fixEn;
    }

    /** Evidence trimmed for display, with an overflow marker. */
    public String evidenceLine(int max) {
        if (evidence.isEmpty()) {
            return "";
        }
        int shown = Math.min(max, evidence.size());
        String joined = String.join(", ", evidence.subList(0, shown));
        int rest = evidence.size() - shown;
        return rest > 0 ? joined + " (+" + rest + ")" : joined;
    }
}
