package com.nightshift.model.enums;

import java.util.Arrays;
import java.util.List;

/** Declared most severe first; {@link #andAbove()} relies on that order. */
public enum Severity {
    BLOCKER,
    CRITICAL,
    MAJOR,
    MINOR,
    TRIVIAL;

    /** This severity and every more severe one, e.g. MAJOR → [BLOCKER, CRITICAL, MAJOR]. */
    public List<Severity> andAbove() {
        return List.copyOf(Arrays.asList(values()).subList(0, ordinal() + 1));
    }
}
