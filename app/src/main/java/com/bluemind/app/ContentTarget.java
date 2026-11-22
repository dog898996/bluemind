package com.bluemind.app;

/** The visible area and native action for an individual item. */
public final class ContentTarget {
    public enum Kind { SHORT, VIDEO, POST }

    public enum Surface { CARD, SHORT_VIEWER, VIDEO_VIEWER, POST_DETAIL }

    public enum Playback { PLAYING, PAUSED, UNKNOWN }

    public final Kind kind;
    public final Surface surface;
    public final Playback playback;
    public final String identity;
    public final UiNode bounds;
    public final UiNode action;
    public final boolean identityReliable;
    public final long progressMillis;

    public ContentTarget(Kind kind, Surface surface, Playback playback, String identity,
                         UiNode bounds, UiNode action) {
        this(kind, surface, playback, identity, bounds, action, true, -1);
    }

    public ContentTarget(Kind kind, Surface surface, Playback playback, String identity,
                         UiNode bounds, UiNode action, boolean identityReliable, long progressMillis) {
        this.kind = kind;
        this.surface = surface;
        this.playback = playback;
        this.identity = identity;
        this.bounds = bounds;
        this.action = action;
        this.identityReliable = identityReliable;
        this.progressMillis = progressMillis;
    }
}
