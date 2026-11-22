package com.bluemind.app;

public final class UsageVisit {
    public final long whenMs;
    public final long durationMs;
    public final Platform platform;
    public final ContentTarget.Kind kind;

    public UsageVisit(long whenMs, long durationMs, Platform platform, ContentTarget.Kind kind) {
        this.whenMs = whenMs;
        this.durationMs = durationMs;
        this.platform = platform;
        this.kind = kind;
    }
}
