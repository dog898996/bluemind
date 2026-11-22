package com.bluemind.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps the choice to reveal an item separate from evidence that media has played. */
final class ViewingSession {
    private static final long ENTRY_TIMEOUT_MS = 2_500;
    private static final long SHORT_TRANSITION_MS = 1_150;
    private static final long PLAY_CONFIRM_MS = 180;
    private static final long PROGRESS_CONFIRM_MS = 1_500;
    private static final long MAX_SAMPLE_GAP_MS = 1_200;
    private static final long WRITE_INTERVAL_MS = 1_500;

    interface Usage {
        boolean blocked(long wallTime);
        long admit(Platform platform, ContentTarget.Kind kind, long wallTime);
        void addDuration(long visitId, long duration);
    }

    static final class Cover {
        final String key;
        final ContentTarget target;
        final boolean locked;

        Cover(String key, ContentTarget target, boolean locked) {
            this.key = key;
            this.target = target;
            this.locked = locked;
        }
    }

    static final class Probe {
        final Object token;
        final UiNode bounds;
        private final Exposure exposure;

        Probe(Exposure exposure, UiNode bounds) {
            this.exposure = exposure;
            token = exposure;
            this.bounds = bounds;
        }

        void motionObserved(long elapsed) {
            exposure.lastMotionAt = elapsed;
        }
    }

    private final Usage usage;
    private final Map<String, Exposure> exposures = new HashMap<>();
    private Platform platform;
    private String packageName = "";
    private int windowId = -1;
    private String approvedPager;
    private long lastPagerMove = -1;
    private long lastShortAt = -1;
    private int pageIndex = -1;
    private long pageSerial;
    private PendingEntry pending;
    private Exposure focused;
    private long focusedAt;

    ViewingSession(Usage usage) {
        this.usage = usage;
    }

    void onPagerEvent(String sourcePackage, int sourceWindowId, int index,
                      boolean moved, long elapsed) {
        if (!sourcePackage.equals(packageName) || sourceWindowId != windowId) {
            return;
        }
        boolean newPage = index >= 0 && (pageIndex >= 0 ? pageIndex != index : moved);
        if (approvedPager != null && (moved || newPage)) {
            lastPagerMove = elapsed;
            if (newPage) {
                pageSerial++;
            }
        }
        if (index >= 0) {
            pageIndex = index;
        }
    }

    List<Cover> present(Platform nextPlatform, String nextPackage, int nextWindow,
                        List<ContentTarget> targets, long elapsed, long wallTime) {
        switchWindow(nextPlatform, nextPackage, nextWindow);
        if (pending != null && elapsed > pending.expiresAt) {
            pending = null;
        }

        ContentTarget shortViewer = null;
        for (ContentTarget target : targets) {
            if (target.surface == ContentTarget.Surface.SHORT_VIEWER) {
                shortViewer = target;
                break;
            }
        }
        if (shortViewer != null) {
            if (approvedPager != null && !approvedPager.equals(pagerKey(shortViewer))) {
                resetPager();
            }
            lastShortAt = elapsed;
        } else if (inTikTokPhotoPage(targets)) {
            lastShortAt = elapsed;
        } else if (!targets.isEmpty() || lastShortAt < 0 || elapsed - lastShortAt > SHORT_TRANSITION_MS) {
            resetPager();
        }

        if (pending != null && pending.resolvedKey != null && !targets.isEmpty()) {
            boolean sameViewerPresent = false;
            for (ContentTarget target : targets) {
                if (isMediaViewer(target) && pending.resolvedKey.equals(key(target))) {
                    sameViewerPresent = true;
                    break;
                }
            }
            if (!sameViewerPresent) {
                pending = null;
            }
        }

        List<Cover> covers = new ArrayList<>();
        Set<String> present = new HashSet<>();
        Exposure nextFocus = null;
        boolean locked = false;
        boolean checkedLimit = false;
        for (ContentTarget target : targets) {
            String key = key(target);
            if (!present.add(key)) {
                continue;
            }
            Exposure exposure = exposures.get(key);
            boolean playableViewer = isMediaViewer(target);
            if (exposure == null && pending != null && playableViewer && pending.accepts(target.kind, key)) {
                exposure = pending.resolve(key, elapsed);
                exposures.put(key, exposure);
                if (target.surface == ContentTarget.Surface.SHORT_VIEWER) {
                    approveShort(target);
                }
            }
            if (exposure == null && target.surface == ContentTarget.Surface.SHORT_VIEWER
                    && approvedPager != null) {
                if (lastPagerMove >= 0 && elapsed - lastPagerMove <= SHORT_TRANSITION_MS) {
                    exposure = new Exposure(elapsed);
                    exposures.put(key, exposure);
                    approveShort(target);
                    pending = null;
                } else {
                    resetPager();
                }
            }

            if (exposure == null || exposure.visitId == 0) {
                if (!checkedLimit) {
                    locked = usage.blocked(wallTime);
                    checkedLimit = true;
                }
                if (locked && exposure != null) {
                    exposures.remove(key);
                    pending = null;
                    exposure = null;
                    if (target.surface == ContentTarget.Surface.SHORT_VIEWER) {
                        resetPager();
                    }
                }
            }
            if (exposure == null) {
                covers.add(new Cover(key, target, locked));
                continue;
            }

            boolean playingOrReading = target.kind == ContentTarget.Kind.POST || exposure.observe(target, elapsed);
            if (playingOrReading && exposure.visitId == 0) {
                exposure.visitId = usage.admit(platform, target.kind, wallTime);
                // An admission can use the last slot. Check again for later items on the same screen.
                checkedLimit = false;
                if (exposure.visitId == 0) {
                    exposures.remove(key);
                    pending = null;
                    if (target.surface == ContentTarget.Surface.SHORT_VIEWER) {
                        resetPager();
                    }
                    covers.add(new Cover(key, target, true));
                    continue;
                }
            }
            exposure.lastSeen = elapsed;
            if (playingOrReading && exposure.visitId != 0
                    && (nextFocus == null || exposure.approvedAt > nextFocus.approvedAt)) {
                nextFocus = exposure;
            }
        }

        updateFocus(nextFocus, elapsed);
        Iterator<Map.Entry<String, Exposure>> iterator = exposures.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Exposure> entry = iterator.next();
            Exposure exposure = entry.getValue();
            boolean shortGap = shortViewer == null && targets.isEmpty()
                    && approvedPager != null && elapsed - exposure.lastSeen <= SHORT_TRANSITION_MS;
            if (!present.contains(entry.getKey()) && !shortGap) {
                flush(exposure);
                iterator.remove();
            }
        }
        return covers;
    }

    /** The view has been rechecked immediately before this call. */
    boolean reveal(Cover cover, long elapsed, long wallTime) {
        ContentTarget target = cover.target;
        if (usage.blocked(wallTime)) {
            return false;
        }
        Exposure exposure = new Exposure(elapsed);
        if (target.kind == ContentTarget.Kind.POST) {
            exposure.visitId = usage.admit(platform, target.kind, wallTime);
            if (exposure.visitId == 0) {
                return false;
            }
        } else if (target.surface == ContentTarget.Surface.SHORT_VIEWER) {
            approveShort(target);
            lastShortAt = elapsed;
        }
        exposures.put(cover.key, exposure);
        return true;
    }

    void nativeEntryOpened(ContentTarget target, List<ContentTarget> visibleTargets, long elapsed) {
        String priorViewer = "";
        for (ContentTarget visibleTarget : visibleTargets) {
            if (isMediaViewer(visibleTarget)) {
                priorViewer = key(visibleTarget);
                break;
            }
        }
        pending = new PendingEntry(target.kind, priorViewer, elapsed + ENTRY_TIMEOUT_MS);
    }

    Probe probe(List<ContentTarget> targets) {
        for (ContentTarget target : targets) {
            if (isMediaViewer(target) && target.playback == ContentTarget.Playback.UNKNOWN
                    && target.progressMillis < 0) {
                Exposure exposure = exposures.get(key(target));
                if (exposure != null) {
                    return new Probe(exposure, target.bounds);
                }
            }
        }
        return null;
    }

    void routeChanged() {
        clearExposures();
    }

    void leave() {
        clearExposures();
        platform = null;
        packageName = "";
        windowId = -1;
        pending = null;
    }

    private void switchWindow(Platform newPlatform, String newPackage, int newWindow) {
        if (newPlatform == platform && newPackage.equals(packageName) && newWindow == windowId) {
            return;
        }
        if (newPlatform != platform || !newPackage.equals(packageName)) {
            pending = null;
        }
        clearExposures();
        platform = newPlatform;
        packageName = newPackage;
        windowId = newWindow;
    }

    private void clearExposures() {
        for (Exposure exposure : exposures.values()) {
            flush(exposure);
        }
        exposures.clear();
        focused = null;
        resetPager();
        lastShortAt = -1;
        pageIndex = -1;
        pageSerial = 0;
    }

    private String key(ContentTarget target) {
        String location = "";
        if (target.surface == ContentTarget.Surface.CARD) {
            location = Arrays.toString(target.bounds.path);
        } else if ((target.surface == ContentTarget.Surface.SHORT_VIEWER
                || target.surface == ContentTarget.Surface.POST_DETAIL) && !target.identityReliable) {
            location = Long.toString(pageSerial);
        }
        return target.surface + ":" + target.kind + ":" + target.identity + ":" + location;
    }

    private void approveShort(ContentTarget target) {
        approvedPager = pagerKey(target);
        lastPagerMove = -1;
    }

    private void resetPager() {
        approvedPager = null;
        lastPagerMove = -1;
    }

    private boolean inTikTokPhotoPage(List<ContentTarget> targets) {
        return platform == Platform.TIKTOK && approvedPager != null && targets.size() == 1
                && targets.get(0).surface == ContentTarget.Surface.POST_DETAIL
                && approvedPager.equals(pagerKey(targets.get(0)));
    }

    private static String pagerKey(ContentTarget target) {
        return target.bounds.id.isEmpty() ? target.bounds.className + Arrays.toString(target.bounds.path)
                : target.bounds.id;
    }

    private static boolean isMediaViewer(ContentTarget target) {
        return target.surface == ContentTarget.Surface.SHORT_VIEWER
                || target.surface == ContentTarget.Surface.VIDEO_VIEWER;
    }

    private void updateFocus(Exposure next, long now) {
        if (focused != null && focused == next) {
            long delta = now - focusedAt;
            if (delta > 0 && delta <= MAX_SAMPLE_GAP_MS) {
                focused.unwrittenDuration += delta;
                if (focused.unwrittenDuration >= WRITE_INTERVAL_MS) {
                    flush(focused);
                }
            }
        } else if (focused != null) {
            flush(focused);
        }
        focused = next;
        focusedAt = now;
    }

    private void flush(Exposure exposure) {
        if (exposure.visitId != 0 && exposure.unwrittenDuration > 0) {
            long delta = exposure.unwrittenDuration;
            exposure.unwrittenDuration = 0;
            usage.addDuration(exposure.visitId, delta);
        }
    }

    private static final class PendingEntry {
        final ContentTarget.Kind kind;
        final String priorViewer;
        final long expiresAt;
        String resolvedKey;
        Exposure exposure;

        PendingEntry(ContentTarget.Kind kind, String priorViewer, long expiresAt) {
            this.kind = kind;
            this.priorViewer = priorViewer;
            this.expiresAt = expiresAt;
        }

        boolean accepts(ContentTarget.Kind targetKind, String viewerKey) {
            return kind == targetKind && (resolvedKey == null
                    ? !priorViewer.equals(viewerKey) : resolvedKey.equals(viewerKey));
        }

        Exposure resolve(String viewerKey, long elapsed) {
            if (exposure == null) {
                resolvedKey = viewerKey;
                exposure = new Exposure(elapsed);
            }
            return exposure;
        }
    }

    private static final class Exposure {
        final long approvedAt;
        long lastSeen;
        long visitId;
        long unwrittenDuration;
        long playingSince = -1;
        long lastProgress = -1;
        long lastAdvanceAt = -1;
        long lastMotionAt = -1;

        Exposure(long now) {
            approvedAt = now;
            lastSeen = now;
        }

        boolean observe(ContentTarget target, long now) {
            long position = target.progressMillis;
            long delta = position - lastProgress;
            long gap = now - lastSeen;
            if (target.playback == ContentTarget.Playback.PAUSED) {
                lastAdvanceAt = -1;
                lastMotionAt = -1;
            } else if (lastProgress >= 0 && position >= 0 && gap >= 0 && gap <= MAX_SAMPLE_GAP_MS
                    && delta > 0 && delta <= gap * 3 + 1_000) {
                lastAdvanceAt = now;
            } else if (position >= 0 && lastProgress > position) {
                lastAdvanceAt = -1;
            }
            lastProgress = position;
            boolean advancing = lastAdvanceAt >= 0 && now - lastAdvanceAt <= PROGRESS_CONFIRM_MS;
            boolean moving = lastMotionAt >= 0 && now - lastMotionAt <= 1_250;
            boolean playing = target.playback == ContentTarget.Playback.PLAYING || advancing || moving;
            if (!playing || gap > MAX_SAMPLE_GAP_MS) {
                playingSince = -1;
                return false;
            }
            if (playingSince < 0) {
                playingSince = now;
            }
            return now - playingSince >= PLAY_CONFIRM_MS;
        }
    }
}
