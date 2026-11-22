package com.bluemind.app;

import java.util.Objects;

public final class Rule {
    public enum Mode { LIMIT, REMIND }

    public final long id;
    public final int hours;
    public final int count;
    public final Mode mode;

    public Rule(long id, int hours, int count, Mode mode) {
        if (hours < 1 || count < 1) {
            throw new IllegalArgumentException("Rule duration and count must be positive.");
        }
        this.id = id;
        this.hours = hours;
        this.count = count;
        this.mode = Objects.requireNonNull(mode);
    }
}
