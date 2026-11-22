package com.bluemind.app;

import static com.bluemind.app.ContentTarget.Kind.POST;
import static com.bluemind.app.ContentTarget.Kind.SHORT;
import static com.bluemind.app.ContentTarget.Kind.VIDEO;
import static com.bluemind.app.ContentTarget.Playback.PAUSED;
import static com.bluemind.app.ContentTarget.Playback.PLAYING;
import static com.bluemind.app.ContentTarget.Playback.UNKNOWN;
import static com.bluemind.app.ContentTarget.Surface.CARD;
import static com.bluemind.app.ContentTarget.Surface.POST_DETAIL;
import static com.bluemind.app.ContentTarget.Surface.SHORT_VIEWER;
import static com.bluemind.app.ContentTarget.Surface.VIDEO_VIEWER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ContentDetectorTest {
    private static final int WIDTH = 400;
    private static final int HEIGHT = 800;

    @Test
    public void instagramFeedAutoPlayRemainsAConcealedCard() {
        UiNode autoplay = node("clips_video_container", 135, 675, text("pause_button", "일시중지", 160));
        UiNode post = node("row_feed", 115, 715, text("row_feed_text", "비가 온 날", 687), autoplay);

        ContentTarget result = only(Platform.INSTAGRAM, post);

        assertSame(post, result.bounds);
        assertEquals(POST, result.kind);
        assertEquals(CARD, result.surface);
        assertEquals(UNKNOWN, result.playback);
        assertNull(result.action);
    }

    @Test
    public void instagramSearchAndProfileGridPreserveNativeReelsAction() {
        UiNode search = view("search_input", "android.widget.EditText", "릴스", "", "",
                0, 12, WIDTH, 70, true, false);
        UiNode reel = view("clips_grid_item", "android.widget.ImageView", "", "릴스 동영상 · 노을", "",
                0, 120, 130, 258, true, false);
        UiNode post = view("", "android.widget.ImageView", "", "하늘님의 사진", "",
                133, 120, 267, 258, true, false);
        UiNode browse = view("explore_grid", "androidx.recyclerview.widget.RecyclerView", "", "", "",
                0, 80, WIDTH, 712, false, true, reel, post);
        UiNode profilePost = node("profile_grid_item", 320, 500, text("post_caption", "밤 산책", 350));
        UiNode profile = node("profile_media_grid", 300, 712, profilePost);

        List<ContentTarget> searchResults = detect(Platform.INSTAGRAM, search, browse);
        assertEquals(2, searchResults.size());
        assertEquals(SHORT, searchResults.get(0).kind);
        assertSame(reel, searchResults.get(0).action);
        assertEquals(POST, searchResults.get(1).kind);
        assertSame(profilePost, only(Platform.INSTAGRAM, profile).bounds);
    }

    @Test
    public void instagramDirectMessagesMaskSharedReelsAndPostsSeparatelyFromChatText() {
        UiNode body = text("direct_text_message", "릴스 보냈어!", 90);
        UiNode reel = view("direct_message_reel", "android.widget.ImageView", "", "친구님의 릴스", "",
                110, 150, 360, 340, true, false);
        UiNode post = node("direct_media_share", 360, 550, text("row_feed_text", "이 주의 사진", 460));
        UiNode directThread = node("direct_thread_fragment", 80, 710, body, reel, post);

        List<ContentTarget> results = detect(Platform.INSTAGRAM, directThread);
        assertEquals(2, results.size());
        assertEquals(SHORT, results.get(0).kind);
        assertSame(reel, results.get(0).action);
        assertEquals(POST, results.get(1).kind);
        assertNull(results.get(1).action);
    }

    @Test
    public void instagramSharedCanonicalReelUrlDisambiguatesGenericPreview() {
        UiNode reel = view("direct_media_share", "android.view.View", "", "https://www.instagram.com/reel/SampleA",
                "", 110, 150, 360, 340, true, false);
        UiNode thread = node("direct_thread_fragment", 80, 700, reel);

        ContentTarget result = only(Platform.INSTAGRAM, thread);
        assertEquals(SHORT, result.kind);
        assertSame(reel, result.action);
    }

    @Test
    public void instagramReelViewerCountsEvidenceWithoutTouchingThePager() {
        ContentTarget playing = only(Platform.INSTAGRAM, instagramViewer("파도와 빗소리", "일시 중지", "0:02"));
        ContentTarget stillPlaying = only(Platform.INSTAGRAM, instagramViewer("파도와 빗소리", "일시중지", "0:03"));
        ContentTarget paused = only(Platform.INSTAGRAM, instagramViewer("파도와 빗소리", "동영상 재생", "0:03"));
        ContentTarget different = only(Platform.INSTAGRAM, instagramViewer("창밖의 비", "일시중지", "0:01"));

        assertEquals(SHORT_VIEWER, playing.surface);
        assertEquals(PLAYING, playing.playback);
        assertEquals(PAUSED, paused.playback);
        assertEquals(2000L, playing.progressMillis);
        assertEquals(3000L, stillPlaying.progressMillis);
        assertEquals(playing.identity, stillPlaying.identity);
        assertEquals(playing.identity, paused.identity);
        assertNotEquals(playing.identity, different.identity);
        assertTrue(playing.identityReliable);
    }

    @Test
    public void reelPagerChoosesCurrentPageOverAPreloadedPage() {
        UiNode previous = node("clips_viewer_item", -580, 100,
                text("clips_caption", "이전 릴스", 15), button("pause_button", "일시중지", 55));
        UiNode current = node("clips_viewer_item", 100, 780,
                text("clips_caption", "지금 릴스", 620), button("play_button", "재생", 650));
        UiNode pager = node("clips_viewer_view_pager", 10, 780, previous, current);

        ContentTarget result = only(Platform.INSTAGRAM, pager);
        assertEquals(SHORT_VIEWER, result.surface);
        assertEquals(PAUSED, result.playback);
        assertSame(pager, result.bounds);
    }

    @Test
    public void reelViewportCanBeOffsetForSplitScreen() {
        UiNode controls = view("clips_play_pause_button", "android.widget.Button", "", "일시정지", "",
                390, 1170, 448, 1215, true, false);
        UiNode pager = view("clips_viewer_view_pager", "android.view.View", "", "", "",
                50, 820, 450, 1335, false, true, controls);
        UiNode root = view("root", "android.view.View", "", "", "",
                50, 810, 450, 1340, false, false, pager);

        List<ContentTarget> targets = ContentDetector.detect(Platform.INSTAGRAM, root,
                50, 810, 450, 1340);
        assertEquals(1, targets.size());
        assertSame(pager, targets.get(0).bounds);
        assertEquals(PLAYING, targets.get(0).playback);
    }

    @Test
    public void creatorAloneIsNotAReliableReelIdentity() {
        UiNode viewer = node("clips_viewer_view_pager", 20, 760, node("clips_viewer_item", 20, 760,
                text("clips_author_name", "@example", 570), button("pause_button", "Pause", 700)));

        ContentTarget result = only(Platform.INSTAGRAM, viewer);
        assertEquals(PLAYING, result.playback);
        assertFalse(result.identityReliable);
        assertTrue(ContentDetector.isShortPager(Platform.INSTAGRAM,
                "com.instagram.android:id/clips_viewer_view_pager"));
        assertFalse(ContentDetector.isShortPager(Platform.INSTAGRAM,
                "com.instagram.android:id/comment_recycler_view"));
    }

    @Test
    public void instagramReelsTabAndStoriesAloneAreNotCovered() {
        UiNode tab = button("clips_tab", "릴스", 740);
        UiNode story = node("reel_viewer", 20, 740, node("clips_video_container", 40, 690));
        assertTrue(detect(Platform.INSTAGRAM, tab, story).isEmpty());
    }

    @Test
    public void tiktokPhotosAndTextPostsDoNotJoinTheShortsSwipeExemption() {
        UiNode videoPage = node("video_feed_item", 60, 760, text("aweme_desc", "비 오는 저녁", 640),
                button("pause_button", "일시정지", 730));
        assertEquals(SHORT_VIEWER, only(Platform.TIKTOK, node("video_pager", 60, 760, videoPage)).surface);

        for (String postMarker : new String[]{"photo_mode_view_pager", "text_post_container"}) {
            UiNode photoPage = node("video_feed_item", 60, 760, node(postMarker, 80, 735));
            ContentTarget photo =
                    only(Platform.TIKTOK, node("video_pager", 60, 760, photoPage));
            assertEquals(POST, photo.kind);
            assertEquals(POST_DETAIL, photo.surface);
            assertEquals(UNKNOWN, photo.playback);
        }
    }

    @Test
    public void tiktokObfuscatedPagerNeedsMediaAndActionRail() {
        UiNode surface = view("xxr", "android.view.SurfaceView", "", "", "",
                0, 60, WIDTH, 760, false, false);
        UiNode like = button("lk9", "좋아요, 5개", 480);
        UiNode comment = button("ab8", "댓글", 530);
        UiNode share = button("c2b", "공유", 585);
        UiNode page = node("kl2", 60, 760, surface, like, comment, share);
        UiNode pager = view("z11", "androidx.viewpager.widget.ViewPager", "", "", "",
                0, 60, WIDTH, 760, false, true, page);
        assertEquals(SHORT_VIEWER, only(Platform.TIKTOK, pager).surface);

        UiNode controlsOnly = view("z11", "androidx.viewpager.widget.ViewPager", "", "", "",
                0, 60, WIDTH, 760, false, true, node("kl2", 60, 760, like, comment, share));
        assertTrue(detect(Platform.TIKTOK, controlsOnly).isEmpty());
    }

    @Test
    public void youtubeCustomPackageWatchAndRelatedCardsCanCoexist() {
        UiNode controls = button("custom.package:id/player_control_play_pause_replay_button", "일시중지", 260);
        UiNode progress = text("custom.package:id/player_control_current_time", "1분 2초 / 5분 23초", 282);
        UiNode watch = node("custom.package:id/watch_player", 40, 320,
                node("custom.package:id/player_view", 40, 320, controls, progress));
        UiNode title = text("custom.package:id/watch_metadata_title", "일상의 소리", 334);
        UiNode community = node("custom.package:id/backstage_post_renderer", 400, 585,
                text("custom.package:id/community_post_text", "새로운 커뮤니티 글", 430),
                node("custom.package:id/video_thumbnail", 460, 570));
        UiNode shortCard = node("custom.package:id/shorts_lockup", 590, 770);

        List<ContentTarget> targets = detect(Platform.YOUTUBE, watch, title, community, shortCard);

        assertEquals(3, targets.size());
        assertEquals(VIDEO_VIEWER, targets.get(0).surface);
        assertEquals(VIDEO, targets.get(0).kind);
        assertEquals(PLAYING, targets.get(0).playback);
        assertEquals(62000L, targets.get(0).progressMillis);
        assertEquals(POST, targets.get(1).kind);
        assertSame(community, targets.get(1).bounds);
        assertEquals(SHORT, targets.get(2).kind);
        assertEquals(CARD, targets.get(2).surface);
    }

    @Test
    public void youtubePlayControlOnAnInlineThumbnailDoesNotCreateAViewer() {
        UiNode inlinePlayer = node("video_card", 100, 450, node("player_view", 100, 360,
                button("player_control_play_pause_button", "Pause video", 300)));
        UiNode miniPlayer = node("player_view", 700, 760,
                button("player_control_play_pause_button", "Pause video", 725));

        ContentTarget result = only(Platform.YOUTUBE, inlinePlayer, miniPlayer);
        assertEquals(VIDEO, result.kind);
        assertEquals(CARD, result.surface);
        assertEquals(UNKNOWN, result.playback);
    }

    @Test
    public void youtubeShortsUseTheStructuralPagerEvenWithACustomId() {
        UiNode page = node("unrecognized.namespace:id/reel_player_page_container", 55, 775,
                text("shorts_title", "도시의 물방울", 650),
                view("mixed:id/play_pause_button", "android.widget.Button", "", "", "재생 중",
                        0, 700, 50, 750, true, false));
        String className = "com.google.android.libraries.youtube.reel.internal.pager.ReelRecyclerView";
        UiNode pager = view("unrecognized.namespace:id/renamed_id", className, "", "", "",
                0, 40, WIDTH, 775, false, true, page);
        assertTrue(ContentDetector.isShortPager(Platform.YOUTUBE, pager.id, className));
        ContentTarget result = only(Platform.YOUTUBE, pager);
        assertEquals(SHORT_VIEWER, result.surface);
        assertEquals(PLAYING, result.playback);
    }

    @Test
    public void xDetailAndReplyAreIndependentlyConcealed() {
        UiNode quote = node("quoted_tweet_container", 150, 300, text("tweet_text", "인용 글입니다", 165));
        UiNode detail = node("tweet_detail_tweet", 65, 370,
                text("tweet_text", "본문입니다", 100), quote);
        UiNode reply = node("tweet_row", 370, 565, text("tweet_text", "답글입니다", 415));

        List<ContentTarget> targets = detect(Platform.X, detail, reply);
        assertEquals(2, targets.size());
        assertEquals(POST_DETAIL, targets.get(0).surface);
        assertSame(detail, targets.get(0).bounds);
        assertEquals(CARD, targets.get(1).surface);
        assertSame(reply, targets.get(1).bounds);
    }

    @Test
    public void xActionGroupingCoversAnUnlabeledCardWithoutHidingNavigation() {
        UiNode unlabeled = node("", 85, 365, text("", "식물원에 비가 내리던 오후예요", 120),
                button("", "좋아요 12개", 285), button("", "답글", 285), button("", "공유", 285));
        UiNode search = view("search_bar", "android.widget.EditText", "게시물", "", "",
                0, 10, WIDTH, 70, true, false);
        ContentTarget result = only(Platform.X, search, unlabeled);
        assertEquals(POST, result.kind);
        assertSame(unlabeled, result.bounds);
    }

    @Test
    public void invisibleAndUnrelatedNodesDoNotCreateTargets() {
        UiNode hidden = new UiNode("clips_viewer_view_pager", "android.view.View", "", "", "",
                0, 40, WIDTH, 760, false, false, false, null, null);
        UiNode nonMedia = node("settings_item", 20, 100, text("", "릴스", 30));
        assertTrue(detect(Platform.INSTAGRAM, hidden, nonMedia).isEmpty());
    }

    private static UiNode instagramViewer(String caption, String playButton, String position) {
        String prefix = "com.instagram.android:id/";
        return node(prefix + "clips_viewer_view_pager", 75, 745,
                node(prefix + "clips_viewer_item", 75, 745,
                        text(prefix + "clips_caption", caption, 610),
                        text(prefix + "clips_author_name", "@sample", 570),
                        button(prefix + "clips_play_pause_button", playButton, 660),
                        text(prefix + "clips_progress_bar", position, 708)));
    }

    private static ContentTarget only(Platform platform, UiNode... children) {
        List<ContentTarget> results = detect(platform, children);
        assertEquals(1, results.size());
        return results.get(0);
    }

    private static List<ContentTarget> detect(Platform platform, UiNode... children) {
        return ContentDetector.detect(platform, node("root", 0, HEIGHT, children), WIDTH, HEIGHT);
    }

    private static UiNode node(String id, int top, int bottom, UiNode... children) {
        return view(id, "android.widget.FrameLayout", "", "", "", 0, top, WIDTH, bottom,
                false, false, children);
    }

    private static UiNode text(String id, String value, int top) {
        return view(id, "android.widget.TextView", value, "", "", 3, top, WIDTH - 9, top + 28,
                false, false);
    }

    private static UiNode button(String id, String label, int top) {
        return view(id, "android.widget.Button", "", label, "", WIDTH - 55, top, WIDTH, top + 36,
                true, false);
    }

    private static UiNode view(String id, String type, String text, String description, String state,
                               int left, int top, int right, int bottom, boolean clickable,
                               boolean scrollable, UiNode... children) {
        return new UiNode(id, type, text, description, "", left, top, right, bottom,
                true, clickable, scrollable, null, Arrays.asList(children), state);
    }
}
