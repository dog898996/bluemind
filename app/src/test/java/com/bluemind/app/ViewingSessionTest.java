package com.bluemind.app;

import com.bluemind.app.ContentTarget.Kind;
import com.bluemind.app.ContentTarget.Playback;
import com.bluemind.app.ContentTarget.Surface;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ViewingSessionTest {
    private static final String IG = "com.instagram.android";
    private static final UiNode PAGER = new UiNode("clips_viewer_view_pager", "Pager", "", "", "",
            0, 0, 360, 720, true, true, true, new int[]{0}, null);
    private final FakeUsage usage = new FakeUsage();
    private ViewingSession session;

    @Before
    public void newSession() {
        session = new ViewingSession(usage);
    }

    @Test
    public void feedAutoplayAndEntryDoNotCountBeforePlayback() {
        ContentTarget feed = new ContentTarget(Kind.SHORT, Surface.CARD, Playback.PLAYING, "A", PAGER, PAGER);
        assertEquals(1, frame(100, feed).size());
        assertEquals(1, frame(450, feed).size());
        assertEquals(0, usage.visits);

        session.nativeEntryOpened(feed, Collections.singletonList(feed), 500);
        assertTrue(frame(650, shortVideo("A", Playback.PAUSED)).isEmpty());
        assertTrue(frame(1_000, shortVideo("A", Playback.PAUSED)).isEmpty());
        assertEquals(0, usage.visits);

        assertTrue(frame(1_200, shortVideo("A", Playback.PLAYING)).isEmpty());
        assertEquals(0, usage.visits);
        assertTrue(frame(1_430, shortVideo("A", Playback.PLAYING)).isEmpty());
        assertEquals(1, usage.visits);
        frame(1_660, shortVideo("A", Playback.PLAYING));
        frame(1_890, shortVideo("A", Playback.PAUSED));
        frame(2_120, shortVideo("A", Playback.PLAYING));
        frame(2_340, shortVideo("A", Playback.PLAYING));
        session.leave();
        assertEquals(1, usage.visits);
        assertEquals(230, usage.duration);
    }

    @Test
    public void nativeSwipePlaysNextItemAndBlocksOnlyTheItemAfterTheCap() {
        usage.limit = 2;
        ContentTarget first = shortVideo("A", Playback.PLAYING);
        assertTrue(session.reveal(frame(100, first).get(0), 110, 110));
        frame(200, first);
        frame(400, first);
        assertEquals(1, usage.visits);
        session.onPagerEvent(IG, 42, 1, true, 600);
        ContentTarget next = shortVideo("B", Playback.PLAYING);
        assertTrue(frame(650, next).isEmpty());
        assertTrue(frame(850, next).isEmpty());
        assertEquals(2, usage.visits);
        assertTrue(frame(1_150, next).isEmpty());

        session.onPagerEvent(IG, 42, 2, true, 1_300);
        ViewingSession.Cover lock = frame(1_310, shortVideo("C", Playback.PLAYING)).get(0);
        assertTrue(lock.locked);
        assertFalse(session.reveal(lock, 1_315, 1_315));
        assertEquals(2, usage.visits);
        usage.limit = 3;
        assertFalse(frame(1_550, lock.target).get(0).locked);
    }

    @Test
    public void deepLinkThatReusesPagerDoesNotInheritApproval() {
        ContentTarget first = shortVideo("A", Playback.PLAYING);
        session.reveal(frame(100, first).get(0), 110, 110);
        frame(200, first);
        frame(400, first);
        assertEquals(1, usage.visits);
        assertFalse(frame(1_800, shortVideo("DM", Playback.PLAYING)).get(0).locked);
        assertEquals(1, usage.visits);
    }

    @Test
    public void firstConfirmedSwipeCanAdvanceAnUnnamedReel() {
        ContentTarget unnamed = new ContentTarget(Kind.SHORT, Surface.SHORT_VIEWER, Playback.PLAYING,
                "unknown", PAGER, null, false, -1);
        session.reveal(frame(100, unnamed).get(0), 110, 110);
        frame(200, unnamed);
        frame(400, unnamed);
        assertEquals(1, usage.visits);
        session.onPagerEvent(IG, 42, 1, true, 600);
        assertTrue(frame(650, unnamed).isEmpty());
        frame(850, unnamed);
        assertEquals(2, usage.visits);
        session.onPagerEvent(IG, 42, 1, true, 1_000);
        frame(1_010, unnamed);
        frame(1_215, unnamed);
        assertEquals(2, usage.visits);
    }

    @Test
    public void tiktokPhotoGetsItsOwnCoverInsideAnApprovedShortPager() {
        String app = "com.zhiliaoapp.musically";
        ContentTarget first = shortVideo("A", Playback.PLAYING);
        ContentTarget photo = new ContentTarget(Kind.POST, Surface.POST_DETAIL, Playback.UNKNOWN,
                "photo", PAGER, null);
        ContentTarget next = shortVideo("B", Playback.PLAYING);
        session.reveal(tiktok(100, app, first).get(0), 110, 110);
        tiktok(200, app, first);
        tiktok(400, app, first);
        session.onPagerEvent(app, 42, 1, true, 600);
        assertEquals(1, tiktok(610, app, photo).size());
        assertEquals(1, usage.visits);
        session.reveal(tiktok(620, app, photo).get(0), 625, 625);
        assertTrue(tiktok(630, app, photo).isEmpty());
        assertEquals(2, usage.visits);
        session.onPagerEvent(app, 42, 2, true, 900);
        assertTrue(tiktok(920, app, next).isEmpty());
        tiktok(1_120, app, next);
        assertEquals(3, usage.visits);
    }

    @Test
    public void videoSignalsNeverCountThroughAClosedCover() {
        ContentTarget unknown = shortVideo("motion", Playback.UNKNOWN);
        frame(100, unknown);
        assertNull(session.probe(Collections.singletonList(unknown)));
        session.reveal(frame(200, unknown).get(0), 210, 210);
        assertTrue(frame(250, unknown).isEmpty());
        assertEquals(0, usage.visits);
        ViewingSession.Probe probe = session.probe(Collections.singletonList(unknown));
        assertNotNull(probe);
        probe.motionObserved(400);
        frame(420, unknown);
        frame(650, unknown);
        assertEquals(1, usage.visits);
        session.leave();
        assertNull(session.probe(Collections.singletonList(unknown)));
    }

    @Test
    public void admittedPlaybackSurvivesOnlyTheMatchingNativeWindowHandoff() {
        ContentTarget card = new ContentTarget(Kind.SHORT, Surface.CARD, Playback.UNKNOWN, "A", PAGER, PAGER);
        frame(100, card);
        session.nativeEntryOpened(card, Collections.singletonList(card), 110);
        ContentTarget shortView = shortVideo("A", Playback.PLAYING);
        frame(350, shortView);
        frame(600, shortView);
        assertEquals(1, usage.visits);
        assertTrue(session.present(Platform.INSTAGRAM, IG, 43, Collections.singletonList(shortView),
                800, 800).isEmpty());
        assertEquals(1, usage.visits);
        assertEquals(1, session.present(Platform.INSTAGRAM, IG, 43,
                Collections.singletonList(shortVideo("B", Playback.PLAYING)), 900, 900).size());
    }

    @Test
    public void approvalDoesNotSurviveLeavingForeground() {
        ContentTarget video = shortVideo("A", Playback.PLAYING);
        session.reveal(frame(100, video).get(0), 110, 110);
        frame(150, video);
        session.leave();
        assertEquals(1, frame(400, video).size());
        assertEquals(0, usage.visits);
    }

    private List<ViewingSession.Cover> frame(long now, ContentTarget... targets) {
        return session.present(Platform.INSTAGRAM, IG, 42, Arrays.asList(targets), now, now);
    }

    private List<ViewingSession.Cover> tiktok(long now, String app, ContentTarget target) {
        return session.present(Platform.TIKTOK, app, 42, Collections.singletonList(target), now, now);
    }

    private static ContentTarget shortVideo(String id, Playback playback) {
        return new ContentTarget(Kind.SHORT, Surface.SHORT_VIEWER, playback, id, PAGER, null);
    }

    private static final class FakeUsage implements ViewingSession.Usage {
        int visits;
        int limit = Integer.MAX_VALUE;
        long duration;

        @Override
        public boolean blocked(long wallTime) {
            return visits >= limit;
        }

        @Override
        public long admit(Platform platform, Kind kind, long wallTime) {
            return blocked(wallTime) ? 0 : ++visits;
        }

        @Override
        public void addDuration(long visitId, long elapsed) {
            duration += elapsed;
        }
    }
}
