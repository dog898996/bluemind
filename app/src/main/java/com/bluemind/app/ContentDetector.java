package com.bluemind.app;

import com.bluemind.app.ContentTarget.Kind;
import com.bluemind.app.ContentTarget.Playback;
import com.bluemind.app.ContentTarget.Surface;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps a platform's visible tree to viewer surfaces and independently coverable cards. */
public final class ContentDetector {
    private enum Role {
        OTHER, SHORT_PAGER, SHORT_PAGE, SHORT_PLAYER, WATCH, VIDEO_VIEWER, VIDEO_PLAYER,
        SHORT_CARD, VIDEO_CARD, POST_CARD, POST_DETAIL, SHORT_MEDIA, VIDEO_MEDIA, POST_MEDIA,
        PHOTO_POST, GRID, GRID_CELL, MESSAGE, EXCLUDED, METADATA, AUTHOR, TITLE, PLAY_CONTROL, PROGRESS
    }

    private static final Map<Platform, Map<String, Role>> ROLES = platformRoles();
    private static final int MAX_DEPTH = 35;
    private static final int MAX_NODES = 2000;
    private static final Pattern ITEM_LINK = Pattern.compile(
            "(?:instagram\\.com/(?:p|reels?|tv)/[\\w-]+|"
                    + "youtube\\.com/(?:shorts/[\\w-]+|watch\\?v=[\\w-]+|post/[\\w-]+)|"
                    + "youtu\\.be/[\\w-]+|tiktok\\.com/@[\\w.-]+/(?:video|photo)/\\d+|"
                    + "(?:twitter\\.com|x\\.com)/(?:i/web|[\\w-]+)/status/\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^(?:(\\d{1,3}):)?(\\d{1,3}):([0-5]\\d)(?=$|\\D)");
    private static final Pattern KOREAN_SECONDS = Pattern.compile(
            "^(?:(\\d{1,3})\\s*시간\\s*)?(?:(\\d{1,3})\\s*분\\s*)?(\\d{1,3})\\s*초");
    private static final Pattern DYNAMIC = Pattern.compile(
            "(?:\\b\\d[\\d,.]*\\s*[kmb]?\\s*(?:likes?|views?|comments?|replies|shares?|reposts?|seconds? ago|minutes? ago|hours? ago|days? ago)\\b"
                    + "|\\d[\\d,.]*\\s*(?:만|천)?\\s*(?:회|개|명|초 전|분 전|시간 전|일 전|조회수)|\\b\\d+:\\d\\d\\b)");
    private static final Pattern CAROUSEL_INDICATOR = Pattern.compile(
            "^(?:(?:photo|image|slide|사진|이미지)\\s*)?\\d+\\s*(?:/|of|중)\\s*\\d+(?:\\s*번째)?$");
    private static final Pattern NORMAL_SPACES = Pattern.compile("\\s+");

    private ContentDetector() {}

    public static boolean isShortPager(Platform platform, String resourceId) {
        return platform != null && role(platform, localId(resourceId)) == Role.SHORT_PAGER;
    }

    public static boolean isShortPager(Platform platform, String resourceId, String className) {
        return isShortPager(platform, resourceId)
                || hasShortPagerClass(platform, className);
    }

    private static boolean hasShortPagerClass(Platform platform, String className) {
        return platform == Platform.YOUTUBE && className != null
                && className.endsWith(".reel.internal.pager.ReelRecyclerView");
    }

    public static List<ContentTarget> detect(Platform platform, UiNode root, int width, int height) {
        return detect(platform, root, 0, 0, width, height);
    }

    public static List<ContentTarget> detect(Platform platform, UiNode root,
                                             int left, int top, int right, int bottom) {
        if (platform == null || root == null || right <= left || bottom <= top) {
            return Collections.emptyList();
        }
        Screen screen = new Screen(platform, left, top, right, bottom);
        screen.index(root, null, 0);
        ContentTarget shortViewer = screen.shortViewer();
        if (shortViewer != null) return Collections.singletonList(shortViewer);

        ContentTarget videoViewer = screen.videoViewer();
        List<ContentTarget> targets = new ArrayList<>();
        if (videoViewer != null) targets.add(videoViewer);
        targets.addAll(screen.cards(videoViewer));
        return targets;
    }

    private static final class Node {
        final UiNode value;
        final Node parent;
        final Role role;
        final String id;
        final String text;
        final String description;
        final String state;
        final boolean excluded;
        final boolean onScreen;
        // A depth-first interval gives inexpensive subtree membership and traversal.
        final int start;
        int end;

        Node(UiNode value, Node parent, Role role, String id, boolean onScreen, int index) {
            this.value = value;
            this.parent = parent;
            this.role = role;
            this.id = id;
            text = normalize(value.text);
            description = normalize(value.description);
            state = normalize(value.state);
            excluded = role == Role.EXCLUDED || parent != null && parent.excluded;
            this.onScreen = onScreen;
            start = index;
        }

        boolean contains(Node child) {
            return start <= child.start && end > child.start;
        }
    }

    private static final class Screen {
        final Platform platform;
        final int left;
        final int top;
        final int right;
        final int bottom;
        final int width;
        final int height;
        final List<Node> nodes = new ArrayList<>();

        Screen(Platform platform, int left, int top, int right, int bottom) {
            this.platform = platform;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.width = right - left;
            this.height = bottom - top;
        }

        void index(UiNode value, Node parent, int depth) {
            if (value == null || depth > MAX_DEPTH || nodes.size() >= MAX_NODES) return;
            String id = localId(value.id);
            Role marker = hasShortPagerClass(platform, value.className)
                    ? Role.SHORT_PAGER : role(platform, id);
            Node node = new Node(value, parent, marker,
                    id, value.visible && visibleWidth(value) > 0 && visibleHeight(value) > 0, nodes.size());
            nodes.add(node);
            for (UiNode child : value.children) index(child, node, depth + 1);
            node.end = nodes.size();
        }

        ContentTarget shortViewer() {
            Node pager = bestShort(Role.SHORT_PAGER);
            if (pager == null) pager = bestShort(Role.SHORT_PAGE);
            if (pager == null) pager = bestShort(Role.SHORT_PLAYER);
            if (pager == null && platform == Platform.TIKTOK) pager = tiktokPager();
            if (pager == null) return null;

            Node page = activePage(pager);
            if (platform == Platform.TIKTOK && tiktokPost(page)) {
                return target(page, pager.value, Kind.POST, Surface.POST_DETAIL, null, null);
            }
            return target(page, pager.value, Kind.SHORT, Surface.SHORT_VIEWER, null, null);
        }

        Node bestShort(Role marker) {
            Node chosen = null;
            long bestScore = 0;
            for (Node node : nodes) {
                if (!node.onScreen || node.excluded || node.role != marker
                        || insideCard(node) || visibleWidth(node.value) * 2 < width
                        || visibleHeight(node.value) * 2 < height) continue;
                if (marker == Role.SHORT_PLAYER && !shortPlayerStandalone(node)) continue;

                long score = centerScore(node.value);
                if (score > bestScore) {
                    chosen = node;
                    bestScore = score;
                }
            }
            return chosen;
        }

        boolean shortPlayerStandalone(Node node) {
            // A video in a home-feed card can also be tall. Standalone players have
            // a clipped page/pager or a full viewer's own action and caption hierarchy.
            return hasAncestor(node, Role.SHORT_PAGE, Role.SHORT_PAGER)
                    || visibleHeight(node.value) * 10 >= height * 7
                    && !hasAncestor(node, Role.GRID, Role.MESSAGE, Role.POST_MEDIA, Role.SHORT_MEDIA)
                    && (platform != Platform.INSTAGRAM || node.id.startsWith("clips_viewer_")
                            || hasRole(node, Role.METADATA));
        }

        Node tiktokPager() {
            // TikTok commonly exposes short, version-specific resource names. Require a
            // full-width vertical pager with video or photo evidence and its own action rail.
            for (Node node : nodes) {
                if (!node.onScreen || node.excluded || insideCard(node)
                        || !node.value.scrollable || visibleWidth(node.value) < width * 4 / 5
                        || visibleHeight(node.value) < height * 2 / 3
                        || !node.value.className.contains("ViewPager")) continue;
                int actions = 0;
                boolean hasMedia = false;
                for (int i = node.start + 1; i < node.end; i++) {
                    Node part = nodes.get(i);
                    if (!part.onScreen || part.excluded) continue;
                    if (control(part)) actions |= postAction(part);
                    hasMedia |= part.role == Role.PHOTO_POST || part.role == Role.SHORT_PLAYER
                            || videoClass(part);
                }
                if (hasMedia && Integer.bitCount(actions) >= 3) return node;
            }
            return null;
        }

        boolean tiktokPost(Node page) {
            for (int i = page.start; i < page.end; i++) {
                Node node = nodes.get(i);
                if (!node.onScreen || node.excluded) continue;
                if (node.role == Role.PHOTO_POST || node.role == Role.POST_DETAIL
                        || node.role == Role.POST_CARD || exact(node.description,
                                "photo mode", "사진 모드", "photo post", "사진 게시물", "텍스트 게시물")) {
                    return true;
                }
            }
            return false;
        }

        Node activePage(Node pager) {
            if (pager.role != Role.SHORT_PAGER && pager.role != Role.OTHER) return pager;
            Node choice = null;
            long bestScore = 0;
            for (int i = pager.start + 1; i < pager.end; i++) {
                Node node = nodes.get(i);
                if (!node.onScreen || node.excluded
                        || node.role != Role.SHORT_PAGE && node.role != Role.SHORT_PLAYER
                        && node.parent != pager
                        || visibleWidth(node.value) * 2 < visibleWidth(pager.value)
                        || visibleHeight(node.value) * 2 < visibleHeight(pager.value)) continue;
                if (node.role == Role.PHOTO_POST || node.role == Role.PROGRESS) continue;
                // Prefer the page container because the media sibling only has player pixels.
                long score = centerScore(node.value);
                if (node.role == Role.SHORT_PAGE) score += (long) width * height / 4;
                if (node.role == Role.SHORT_PLAYER && node.parent != pager) score /= 2;
                if (score > bestScore) {
                    choice = node;
                    bestScore = score;
                }
            }
            return choice == null ? pager : choice;
        }

        ContentTarget videoViewer() {
            if (platform != Platform.YOUTUBE) return null;
            Node best = null;
            Node title = visibleTitle();
            long bestScore = 0;
            for (Node node : nodes) {
                if (!node.onScreen || node.excluded || insideCard(node)
                        || node.role != Role.VIDEO_VIEWER && node.role != Role.VIDEO_PLAYER
                        || visibleWidth(node.value) * 2 < width
                        || visibleHeight(node.value) * 100 < height * 12) continue;
                boolean watchScreen = node.role == Role.VIDEO_VIEWER || title != null
                        || hasAncestor(node, Role.VIDEO_VIEWER, Role.WATCH);
                if (!watchScreen && !hasRole(node, Role.PLAY_CONTROL)) continue;
                if (hasAncestor(node, Role.VIDEO_MEDIA, Role.SHORT_MEDIA, Role.GRID)) continue;
                long score = visibleArea(node.value);
                if (node.role == Role.VIDEO_PLAYER) score += (long) width * height;
                if (score > bestScore) {
                    bestScore = score;
                    best = node;
                }
            }
            if (best == null) return null;
            Node envelope = best;
            for (Node ancestor = best.parent; ancestor != null; ancestor = ancestor.parent) {
                if (ancestor.role == Role.VIDEO_VIEWER
                        && visibleHeight(ancestor.value) <= visibleHeight(best.value) * 2) {
                    envelope = ancestor;
                    break;
                }
            }
            return target(envelope, envelope.value, Kind.VIDEO, Surface.VIDEO_VIEWER, null, title);
        }

        Node visibleTitle() {
            for (Node node : nodes) {
                if (node.role == Role.TITLE && node.onScreen && !insideCard(node)) return node;
            }
            return null;
        }

        List<ContentTarget> cards(ContentTarget videoViewer) {
            Map<Node, Card> candidates = new IdentityHashMap<>();
            for (Node node : nodes) {
                if (!coverable(node) || videoViewer != null && overlaps(node.value, videoViewer.bounds)) continue;
                Kind kind = switch (node.role) {
                    case SHORT_CARD, SHORT_MEDIA -> Kind.SHORT;
                    case VIDEO_CARD, VIDEO_MEDIA -> Kind.VIDEO;
                    case POST_CARD, POST_DETAIL, PHOTO_POST, POST_MEDIA, GRID_CELL -> Kind.POST;
                    default -> labeledMedia(node);
                };
                if (kind == null) continue;
                Node item = cardContainer(node);
                if (!coverable(item) || videoViewer != null && overlaps(item.value, videoViewer.bounds)) continue;
                if (platform == Platform.YOUTUBE
                        && (item.role == Role.POST_CARD || item.role == Role.POST_DETAIL)) {
                    kind = Kind.POST;
                } else if (node.role != Role.POST_DETAIL && (shortLabel(node.description)
                        || hasAncestor(node, Role.MESSAGE) && sharedReelLink(node.value.description))) {
                    kind = Kind.SHORT;
                }
                addCandidate(candidates, item, node, kind);
            }
            addActionGroupedPosts(candidates, videoViewer);

            List<Card> ordered = new ArrayList<>(candidates.values());
            ordered.sort(Comparator.comparingInt(card -> card.node.start));
            List<Card> selected = new ArrayList<>();
            List<ContentTarget> result = new ArrayList<>();
            for (Card card : ordered) {
                boolean contained = false;
                for (Card previous : selected) {
                    if (previous.node.contains(card.node)
                            || nearlyContains(previous.node.value, card.node.value)) {
                        contained = true;
                        break;
                    }
                }
                if (contained) continue;
                selected.add(card);
                Surface surface = card.node.role == Role.POST_DETAIL
                        ? Surface.POST_DETAIL : Surface.CARD;
                UiNode action = card.kind == Kind.POST ? null : nativeAction(card);
                result.add(target(card.node, card.node.value, card.kind, surface, action, null));
            }
            return result;
        }

        void addCandidate(Map<Node, Card> candidates, Node item, Node anchor, Kind kind) {
            Card previous = candidates.get(item);
            if (previous == null || priority(kind) > priority(previous.kind)
                    || kind == previous.kind && anchor.value.clickable && !previous.anchor.value.clickable) {
                candidates.put(item, new Card(item, anchor, kind));
            }
        }

        void addActionGroupedPosts(Map<Node, Card> candidates, ContentTarget videoViewer) {
            if (platform != Platform.X && platform != Platform.INSTAGRAM) return;
            Set<Node> inspected = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Node node : nodes) {
                if (!node.onScreen || !control(node) || postAction(node) == 0) continue;
                Node item = node.parent;
                for (int depth = 0; item != null && item.parent != null && depth < 5;
                        depth++, item = item.parent) {
                    if (item.value.scrollable || item.role == Role.GRID || item.role == Role.MESSAGE) break;
                    if (candidates.containsKey(item)) break;
                    if (!coverable(item) || visibleHeight(item.value) < height / 16
                            || visibleWidth(item.value) < width * 3 / 5
                            || visibleHeight(item.value) > height * 4 / 5
                            || videoViewer != null && overlaps(item.value, videoViewer.bounds)) continue;
                    if (!inspected.add(item)) continue;
                    int actions = 0;
                    boolean content = false;
                    for (int i = item.start + 1; i < item.end; i++) {
                        Node part = nodes.get(i);
                        if (!part.onScreen || part.excluded) continue;
                        if (control(part)) actions |= postAction(part);
                        if (!control(part) && (part.text.length() > 12
                                || part.role == Role.METADATA || part.role == Role.POST_MEDIA)) content = true;
                    }
                    if (content && Integer.bitCount(actions) >= 3) {
                        Node container = cardContainer(item);
                        if (coverable(container)
                                && (videoViewer == null || !overlaps(container.value, videoViewer.bounds))) {
                            addCandidate(candidates, container, item, Kind.POST);
                        }
                        break;
                    }
                }
            }
        }

        Kind labeledMedia(Node node) {
            boolean shared = hasAncestor(node, Role.MESSAGE);
            boolean grid = hasAncestor(node, Role.GRID);
            boolean visual = imageLike(node)
                    || node.value.clickable && (node.value.children.isEmpty() || shared);
            if (!visual || visibleWidth(node.value) < width / 10 || visibleHeight(node.value) < height / 18) {
                return null;
            }
            String label = node.description;
            if ((grid || shared || imageLike(node)) && shortLabel(label)) return Kind.SHORT;
            if ((grid || shared) && videoLabel(label)) {
                return platform == Platform.YOUTUBE ? Kind.VIDEO : Kind.POST;
            }
            if (shared) {
                if (sharedReelLink(node.value.description)) return Kind.SHORT;
                if (postLabel(label) || ITEM_LINK.matcher(node.value.description).find()) return Kind.POST;
            }
            if (platform == Platform.X && node.value.clickable && postLabel(label)
                    && visibleWidth(node.value) >= width / 2) return Kind.POST;
            return grid && (postLabel(label) || imageLike(node)
                    && visibleWidth(node.value) >= width * 13 / 100) ? Kind.POST : null;
        }

        Node cardContainer(Node node) {
            Node nearestClickable = node.value.clickable ? node : null;
            for (Node ancestor = node.parent; ancestor != null && ancestor.parent != null;
                    ancestor = ancestor.parent) {
                if (ancestor.role == Role.POST_DETAIL || ancestor.role == Role.POST_CARD
                        || ancestor.role == Role.VIDEO_CARD || ancestor.role == Role.SHORT_CARD) return ancestor;
                if (ancestor.value.scrollable || ancestor.role == Role.GRID
                        || ancestor.role == Role.MESSAGE || ancestor.excluded
                        || visibleHeight(ancestor.value) > height * 4 / 5) break;
                if (ancestor.value.clickable && nearestClickable == null) nearestClickable = ancestor;
            }
            return nearestClickable == null ? node : nearestClickable;
        }

        UiNode nativeAction(Card card) {
            if (card.anchor.value.clickable && postAction(card.anchor) == 0) return card.anchor.value;
            for (Node ancestor = card.anchor.parent; ancestor != null && card.node.contains(ancestor);
                    ancestor = ancestor.parent) {
                if (ancestor.value.clickable) return ancestor.value;
            }
            Node chosen = null;
            long largest = 0;
            for (int i = card.node.start; i < card.node.end; i++) {
                Node node = nodes.get(i);
                if (!node.onScreen || !node.value.clickable || node.excluded) continue;
                long area = visibleArea(node.value);
                if (area > largest && postAction(node) == 0
                        && node.role != Role.METADATA && node.role != Role.AUTHOR) {
                    chosen = node;
                    largest = area;
                }
            }
            return chosen != null ? chosen.value : card.node.value;
        }

        ContentTarget target(Node node, UiNode bounds, Kind kind, Surface surface, UiNode action,
                             Node extraTitle) {
            TreeSet<String> signature = new TreeSet<>();
            TreeSet<String> authors = new TreeSet<>();
            if (extraTitle != null) addSignature(signature, extraTitle.text, extraTitle.description);
            Playback playback = Playback.UNKNOWN;
            long progress = -1;
            String itemLink = "";
            boolean viewer = surface == Surface.VIDEO_VIEWER || surface == Surface.SHORT_VIEWER;
            for (int i = node.start; i < node.end; i++) {
                Node signal = nodes.get(i);
                if (!signal.onScreen || signal.excluded) continue;
                if (signal != node && isCard(signal.role)
                        && viewer) {
                    i = signal.end - 1;
                    continue;
                }
                if (signal.role == Role.METADATA || signal.role == Role.TITLE
                        || !viewer && (signal == node || !control(signal) && !signal.value.clickable
                        && signal.role != Role.PROGRESS && signal.role != Role.PLAY_CONTROL)) {
                    addSignature(signature, signal.text, signal.description);
                }
                if (signal.role == Role.AUTHOR && viewer) {
                    addSignature(authors, signal.text, signal.description);
                }
                if (signal == node || signal.role == Role.METADATA || signal.role == Role.TITLE
                        || signal.role == Role.POST_MEDIA || signal.role == Role.SHORT_MEDIA) {
                    Matcher link = ITEM_LINK.matcher(signal.value.description + " " + signal.value.text);
                    if (link.find()) itemLink = link.group();
                }
                if (!viewer) continue;
                Playback observed = playback(signal);
                if (observed == Playback.PAUSED || playback == Playback.UNKNOWN) playback = observed;
                if (signal.role == Role.PROGRESS || signal.value.className.endsWith("SeekBar")) {
                    long observedProgress = progress(signal);
                    if (observedProgress >= 0) progress = observedProgress;
                }
            }

            boolean reliable = !itemLink.isEmpty() || !signature.isEmpty();
            String identity = platform.name() + "|" + kind + "|";
            if (!itemLink.isEmpty()) identity += itemLink;
            else if (reliable) identity += String.join("\n", signature) + "|" + String.join("\n", authors);
            else if (viewer) identity += "viewer";
            else identity += node.id + "|" + node.value.uniqueId + "|" + Arrays.toString(node.value.path);
            return new ContentTarget(kind, surface, playback, digest(identity), bounds,
                    action, reliable, progress);
        }

        boolean hasRole(Node node, Role wanted) {
            for (int i = node.start; i < node.end; i++) {
                Node descendant = nodes.get(i);
                if (descendant.onScreen && !descendant.excluded && descendant.role == wanted) return true;
            }
            return false;
        }

        boolean coverable(Node node) {
            return node.parent != null && !node.excluded && node.onScreen && !node.value.scrollable
                    && !node.value.className.endsWith("EditText") && visibleWidth(node.value) >= width / 20
                    && visibleHeight(node.value) >= height / 85;
        }

        long centerScore(UiNode node) {
            int offset = Math.abs(node.top + node.bottom - top - bottom);
            long area = visibleArea(node);
            return area * (height + 1L) / (height + 1L + offset);
        }

        int visibleWidth(UiNode node) {
            return Math.max(0, Math.min(right, node.right) - Math.max(left, node.left));
        }

        int visibleHeight(UiNode node) {
            return Math.max(0, Math.min(bottom, node.bottom) - Math.max(top, node.top));
        }

        long visibleArea(UiNode node) {
            return (long) visibleWidth(node) * visibleHeight(node);
        }
    }

    private record Card(Node node, Node anchor, Kind kind) {}

    private static int priority(Kind kind) {
        return switch (kind) {
            case SHORT -> 3;
            case VIDEO -> 2;
            case POST -> 1;
        };
    }

    private static boolean hasAncestor(Node node, Role first, Role... others) {
        for (Node parent = node.parent; parent != null; parent = parent.parent) {
            if (parent.role == first) return true;
            for (Role role : others) {
                if (parent.role == role) return true;
            }
        }
        return false;
    }

    private static boolean insideCard(Node node) {
        return hasAncestor(node, Role.POST_CARD, Role.VIDEO_CARD, Role.SHORT_CARD);
    }

    private static boolean isCard(Role role) {
        return role == Role.POST_CARD || role == Role.VIDEO_CARD || role == Role.SHORT_CARD;
    }

    private static boolean overlaps(UiNode a, UiNode b) {
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
    }

    private static boolean nearlyContains(UiNode a, UiNode b) {
        int overlapWidth = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        int overlapHeight = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        return overlapWidth > 0 && overlapHeight > 0
                && (long) overlapWidth * overlapHeight * 20
                >= (long) (b.right - b.left) * (b.bottom - b.top) * 19;
    }

    private static boolean imageLike(Node node) {
        String className = node.value.className;
        return className.endsWith("ImageView") || className.endsWith("ImageButton")
                || videoClass(node);
    }

    private static boolean videoClass(Node node) {
        String className = node.value.className;
        return className.endsWith("TextureView") || className.endsWith("SurfaceView")
                || className.endsWith("VideoView");
    }

    private static boolean control(Node node) {
        return node.value.clickable || node.value.className.endsWith("Button");
    }

    private static boolean shortLabel(String label) {
        return label.equals("릴스") || label.equals("쇼츠") || label.equals("shorts") || label.equals("reel")
                || label.startsWith("reel by ") || label.startsWith("short by ")
                || label.startsWith("watch reel") || label.startsWith("play reel")
                || label.startsWith("shared a reel") || label.startsWith("posted a reel")
                || label.startsWith("릴스 보기") || label.startsWith("릴스 동영상")
                || label.startsWith("쇼츠 동영상") || label.contains("님의 릴스")
                || label.startsWith("릴스를 공유") || label.startsWith("shorts,");
    }

    private static boolean videoLabel(String label) {
        return label.equals("동영상") || label.equals("video") || label.startsWith("video by ")
                || label.startsWith("동영상 보기") || label.startsWith("play video")
                || label.contains("님의 동영상");
    }

    private static boolean sharedReelLink(String description) {
        Matcher link = ITEM_LINK.matcher(description);
        if (!link.find()) return false;
        String uri = link.group().toLowerCase(Locale.ROOT);
        return uri.startsWith("instagram.com/reel")
                || uri.startsWith("tiktok.com/") && uri.contains("/video/");
    }

    private static boolean postLabel(String label) {
        return label.equals("게시물") || label.equals("post") || label.startsWith("post by ")
                || label.startsWith("tweet by ") || label.startsWith("shared a post")
                || label.startsWith("shared a photo") || label.startsWith("photo by ")
                || label.contains("님의 게시물") || label.contains("님의 트윗")
                || label.contains("게시물을 공유") || label.contains("님의 사진");
    }

    private static int postAction(Node node) {
        int known = switch (node.id) {
            case "row_feed_button_like" -> 1;
            case "row_feed_button_comment" -> 2;
            case "row_feed_button_share" -> 4;
            case "row_feed_button_repost" -> 8;
            default -> 0;
        };
        if (known != 0) return known;
        String label = node.description.isEmpty() ? node.text : node.description;
        if (phrase(label, "like", "unlike", "favorite", "좋아요", "좋아요 취소")) return 1;
        if (phrase(label, "comment", "comments", "reply", "read or add comments", "답글", "댓글")) return 2;
        if (phrase(label, "share", "send", "공유", "보내기")) return 4;
        if (phrase(label, "retweet", "repost", "리포스트", "재게시")) return 8;
        return 0;
    }

    private static boolean phrase(String label, String... phrases) {
        for (String phrase : phrases) {
            if (label.equals(phrase) || label.startsWith(phrase + ",")
                    || label.startsWith(phrase + ".") || label.startsWith(phrase + " ")) return true;
        }
        return false;
    }

    private static Playback playback(Node node) {
        if (node.role != Role.PLAY_CONTROL && !control(node)
                && node.role != Role.SHORT_PLAYER && node.role != Role.VIDEO_PLAYER && !videoClass(node)) {
            return Playback.UNKNOWN;
        }
        if (pausedState(node.state)) return Playback.PAUSED;
        if (playingState(node.state)) return Playback.PLAYING;
        if (pauseAction(node.description) || pauseAction(node.text)) return Playback.PLAYING;
        if ((node.role == Role.SHORT_PLAYER || node.role == Role.VIDEO_PLAYER || videoClass(node))
                && playingState(node.description)) return Playback.PLAYING;
        if (playAction(node.description) || playAction(node.text)
                || pausedState(node.description) || pausedState(node.text)) return Playback.PAUSED;
        return Playback.UNKNOWN;
    }

    private static boolean playAction(String label) {
        return exact(label, "play", "play video", "resume video", "replay", "재생", "동영상 재생", "다시 재생");
    }

    private static boolean pauseAction(String label) {
        return exact(label, "pause", "pause video", "일시중지", "일시 중지", "일시정지", "일시 정지",
                "동영상 일시중지", "동영상 일시 중지", "동영상 일시정지", "재생 일시정지", "재생 일시중지");
    }

    private static boolean pausedState(String label) {
        return exact(label, "paused", "buffering", "not playing", "일시중지됨", "일시정지됨",
                "일시중지 상태", "일시정지 상태", "버퍼링", "버퍼링 중");
    }

    private static boolean playingState(String label) {
        return exact(label, "playing", "video playing", "재생 중", "재생중", "동영상 재생 중");
    }

    private static boolean exact(String text, String... labels) {
        for (String label : labels) {
            if (text.equals(label)) return true;
        }
        return false;
    }

    private static long progress(Node node) {
        for (String label : new String[]{node.state, node.description, node.text}) {
            Matcher time = TIMESTAMP.matcher(label);
            if (time.find()) return elapsed(time);
            time = KOREAN_SECONDS.matcher(label);
            if (time.find()) return elapsed(time);
        }
        return -1;
    }

    private static long elapsed(Matcher time) {
        return 3600000L * integer(time.group(1)) + 60000L * integer(time.group(2))
                + 1000L * integer(time.group(3));
    }

    private static int integer(String value) {
        return value == null ? 0 : Integer.parseInt(value);
    }

    private static void addSignature(TreeSet<String> signature, String... labels) {
        if (signature.size() >= 18) return;
        for (String label : labels) {
            if (label.length() < 2 || playAction(label) || pauseAction(label)
                    || pausedState(label) || playingState(label) || shortLabel(label)
                    || CAROUSEL_INDICATOR.matcher(label).matches()
                    || exact(label, "게시물", "동영상", "video", "post", "photo", "사진",
                            "photo mode", "photo post", "text post", "사진 모드", "사진 게시물", "텍스트 게시물")) {
                continue;
            }
            String stable = DYNAMIC.matcher(label).replaceAll("").trim();
            if (stable.length() > 240) stable = stable.substring(0, 240);
            if (!stable.isEmpty()) signature.add(stable);
        }
    }

    private static String normalize(String value) {
        if (value.isEmpty()) return "";
        String clipped = value.length() > 800 ? value.substring(0, 800) : value;
        return NORMAL_SPACES.matcher(clipped).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray();
            char[] result = new char[32];
            for (int i = 0; i < result.length / 2; i++) {
                result[i * 2] = hex[(bytes[i] & 255) >>> 4];
                result[i * 2 + 1] = hex[bytes[i] & 15];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String localId(String id) {
        if (id == null || id.isEmpty()) return "";
        return id.substring(id.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }

    private static Role role(Platform platform, String id) {
        return ROLES.get(platform).getOrDefault(id, Role.OTHER);
    }

    private static Map<Platform, Map<String, Role>> platformRoles() {
        Map<Platform, Map<String, Role>> platforms = new EnumMap<>(Platform.class);
        platforms.put(Platform.INSTAGRAM, instagram());
        platforms.put(Platform.TIKTOK, tiktok());
        platforms.put(Platform.YOUTUBE, youtube());
        platforms.put(Platform.X, x());
        for (Map<String, Role> roles : platforms.values()) {
            ids(roles, Role.PLAY_CONTROL, "play_pause_button", "play_button", "pause_button",
                    "video_play_pause", "media_play_pause");
            ids(roles, Role.PROGRESS, "video_progress_bar", "video_seek_bar", "time_bar",
                    "player_progress_bar", "playback_position", "current_time");
        }
        return platforms;
    }

    private static Map<String, Role> instagram() {
        Map<String, Role> roles = new HashMap<>();
        ids(roles, Role.SHORT_PAGER, "clips_viewer_view_pager", "clips_viewer_pager", "clips_recycler_view");
        ids(roles, Role.SHORT_PAGE, "clips_viewer_root", "clips_viewer_container", "clips_viewer_video_layout",
                "clips_viewer_item", "clips_video_page", "clips_item_view", "clips_viewer_page");
        ids(roles, Role.SHORT_PLAYER, "clips_viewer_video", "clips_viewer_video_container",
                "clips_viewer_video_player_container", "clips_video_container", "clips_video_player",
                "clips_video_view");
        ids(roles, Role.SHORT_CARD, "clips_grid_item", "clips_grid_item_container", "reel_preview",
                "clips_pivot_item", "row_feed_reel", "clips_share_preview", "direct_message_reel");
        ids(roles, Role.SHORT_MEDIA, "clips_grid_item_media_cover", "clips_thumbnail", "clips_badge",
                "reels_badge", "direct_message_reel_media", "reel_thumbnail", "clips_media_thumbnail",
                "watch_more_reels");
        ids(roles, Role.POST_CARD, "row_feed", "row_feed_container", "row_feed_post", "feed_item",
                "feed_item_container", "feed_post", "organic_feed_item", "row_explore_feed",
                "direct_media_share", "direct_message_media_share", "media_share_item",
                "layout_media_card", "post_item", "explore_grid_item", "profile_grid_item");
        ids(roles, Role.POST_DETAIL, "media_detail_post", "post_detail", "media_detail_row");
        ids(roles, Role.POST_MEDIA, "row_feed_media_container", "row_feed_photo", "row_feed_video",
                "row_feed_media", "row_feed_photo_imageview", "carousel_media", "media_set_photo", "media_thumbnail",
                "media_set_video", "direct_media_thumbnail", "direct_message_media",
                "direct_message_media_preview", "direct_message_media_image", "post_media");
        ids(roles, Role.GRID, "explore_grid", "explore_grid_recycler_view", "explore_feed",
                "media_grid", "media_grid_recycler_view", "profile_grid", "profile_media_grid",
                "clips_grid", "clips_profile_grid", "saved_media_grid", "tagged_media_grid");
        ids(roles, Role.GRID_CELL, "image_grid_item", "image_grid_item_thumbnail",
                "layout_grid_image", "thumbnail_image", "grid_image");
        ids(roles, Role.MESSAGE, "direct_message_content", "direct_message_content_container",
                "direct_thread_fragment", "direct_thread_recycler_view", "direct_row_message",
                "direct_thread_message", "direct_messages_list");
        ids(roles, Role.EXCLUDED, "reel_viewer", "reel_viewer_fragment", "stories_tray",
                "reel_viewer_root", "reel_viewer_container", "story_viewer", "story_tray",
                "navigation_bar", "tab_bar", "action_bar_root", "direct_message_avatar",
                "direct_text_message", "direct_message_input", "row_feed_photo_profile_imageview");
        ids(roles, Role.AUTHOR, "clips_author_name", "clips_username", "clips_attribution_name", "clips_author");
        ids(roles, Role.METADATA, "clips_caption", "clips_caption_text", "clips_video_caption",
                "clips_caption_component", "clips_video_title", "clips_description",
                "row_feed_text", "row_feed_subtitle", "row_feed_username", "post_caption");
        ids(roles, Role.PLAY_CONTROL, "clips_play_pause_button", "clips_play_pause", "clips_play_button");
        ids(roles, Role.PROGRESS, "clips_progress_bar", "clips_seek_bar", "clips_viewer_progress_bar");
        return roles;
    }

    private static Map<String, Role> tiktok() {
        Map<String, Role> roles = new HashMap<>();
        ids(roles, Role.SHORT_PAGER, "for_you_feed", "video_pager", "fyp_container",
                "feed_view_pager", "full_screen_video_pager", "video_feed_pager",
                "view_pager_feed", "aweme_pager", "feed_pager", "detail_feed_pager");
        ids(roles, Role.SHORT_PAGE, "video_feed_item", "feed_video_item", "aweme_item",
                "full_screen_video", "video_item_layout", "detail_feed_item");
        ids(roles, Role.SHORT_PLAYER, "video_player", "feed_video_player");
        ids(roles, Role.SHORT_CARD, "video_grid_item", "video_card", "search_video_item",
                "search_video_card", "video_share_card", "profile_video_item", "im_video_share");
        ids(roles, Role.SHORT_MEDIA, "video_cover", "video_thumbnail", "aweme_cover", "share_video_cover");
        ids(roles, Role.PHOTO_POST, "photo_mode", "photo_mode_container", "photo_mode_view_pager",
                "photo_carousel", "text_mode_container", "text_post_container", "text_post_layout",
                "photo_post_layout", "picture_content", "post_photo_container");
        ids(roles, Role.POST_CARD, "photo_post", "photo_post_card", "text_post", "text_post_card",
                "search_post_card", "share_post_card", "post_item", "feed_post");
        ids(roles, Role.POST_DETAIL, "photo_post_detail", "text_post_detail", "post_detail");
        ids(roles, Role.POST_MEDIA, "post_image", "post_text", "photo_post_cover", "text_post_cover");
        ids(roles, Role.GRID, "profile_video_grid", "video_grid", "search_results_grid", "aweme_grid",
                "favorites_grid", "profile_grid");
        ids(roles, Role.MESSAGE, "message_list", "conversation_list", "message_container", "chat_recycle_view");
        ids(roles, Role.EXCLUDED, "bottom_navigation", "navigation_bar", "tab_bar",
                "search_bar", "story_tray", "story_viewer");
        ids(roles, Role.AUTHOR, "author_nickname", "user_name", "nickname");
        ids(roles, Role.METADATA, "video_title", "video_caption", "aweme_desc", "aweme_title",
                "desc", "video_description");
        return roles;
    }

    private static Map<String, Role> youtube() {
        Map<String, Role> roles = new HashMap<>();
        ids(roles, Role.SHORT_PAGER, "reel_recycler", "reel_recycler_view", "shorts_recycler_view",
                "shorts_view_pager", "shorts_pager", "reel_watch_pager");
        ids(roles, Role.SHORT_PAGE, "reel_watch_player", "reel_player_page", "reel_player_page_container",
                "reel_watch_fragment_root", "reel_watch_root", "reel_watch_layout", "shorts_watch_page",
                "shorts_player_layout");
        ids(roles, Role.SHORT_PLAYER, "reel_player_overlay", "shorts_player", "reel_player");
        ids(roles, Role.WATCH, "watch_layout", "watch_flexy", "watch_root", "watch_container");
        ids(roles, Role.VIDEO_VIEWER, "watch_player", "watch_player_container",
                "watch_player_view", "player_fragment_container", "watch_player_layout");
        ids(roles, Role.VIDEO_PLAYER, "player_view", "player_view_core", "player_layout", "video_surface");
        ids(roles, Role.VIDEO_CARD, "video_card", "video_item", "video_list_item", "compact_video",
                "compact_video_renderer", "video_renderer", "video_with_context_renderer",
                "video_description_card", "video_with_context", "related_video", "playlist_video",
                "playlist_video_renderer", "grid_video_renderer", "rich_grid_video");
        ids(roles, Role.VIDEO_MEDIA, "video_thumbnail", "video_thumbnail_container",
                "thumbnail_container", "thumbnail", "compact_video_thumbnail");
        ids(roles, Role.SHORT_CARD, "reel_item", "shorts_item", "reel_item_renderer",
                "shorts_video_cell", "shorts_lockup", "shorts_lockup_view_model", "shorts_grid_item");
        ids(roles, Role.SHORT_MEDIA, "shorts_thumbnail", "reel_thumbnail", "shorts_badge");
        ids(roles, Role.POST_CARD, "community_post", "community_post_card", "backstage_post",
                "backstage_post_renderer", "post_renderer", "post_thread_renderer",
                "community_post_renderer", "post_item", "post_card");
        ids(roles, Role.POST_DETAIL, "post_detail", "community_post_detail", "backstage_post_detail");
        ids(roles, Role.POST_MEDIA, "community_post_text", "community_post_image",
                "backstage_post_image", "post_content");
        ids(roles, Role.GRID, "shorts_grid", "reel_shelf", "shorts_shelf",
                "video_grid", "rich_grid", "channel_video_grid");
        ids(roles, Role.EXCLUDED, "miniplayer_button", "miniplayer", "mini_player", "player_minimized",
                "toolbar", "navigation_bar", "bottom_navigation", "pivot_bar", "comment_section_header");
        ids(roles, Role.AUTHOR, "reel_channel_name", "reel_watch_channel", "shorts_channel_name");
        ids(roles, Role.METADATA, "shorts_title", "reel_title", "reel_description",
                "reel_watch_player_title", "video_title",
                "video_title_view", "video_content_title", "community_post_author",
                "backstage_post_text", "shorts_video_title");
        ids(roles, Role.TITLE, "watch_metadata_title", "watch_title", "watch_video_title",
                "watch_metadata_title_text");
        ids(roles, Role.PLAY_CONTROL, "player_control_play_pause_replay_button",
                "player_control_play_pause_button", "reel_watch_player_play_pause_button",
                "reel_play_pause_button", "shorts_play_pause_button");
        ids(roles, Role.PROGRESS, "player_control_seekbar", "player_control_current_time",
                "reel_watch_player_progress", "shorts_progress_bar", "seek_bar");
        return roles;
    }

    private static Map<String, Role> x() {
        Map<String, Role> roles = new HashMap<>();
        ids(roles, Role.POST_CARD, "tweet", "tweet_item", "tweet_row", "row_tweet", "tweet_cell",
                "timeline_tweet", "timeline_tweet_row", "tweet_container", "post_card",
                "tweet_view", "tweet_item_view", "tweet_layout", "quoted_tweet",
                "quoted_tweet_container", "shared_tweet", "status_item", "tweet_card",
                "dm_tweet_preview", "tweet_summary");
        ids(roles, Role.POST_DETAIL, "tweet_detail_tweet", "tweet_detail_row", "focused_tweet",
                "status_detail_post", "tweet_detail_post");
        ids(roles, Role.POST_MEDIA, "tweet_text", "tweet_body", "tweet_content",
                "tweet_media", "tweet_media_container", "status_text", "tweet_text_view");
        ids(roles, Role.MESSAGE, "dm_message", "dm_message_row", "direct_message", "message_container");
        ids(roles, Role.EXCLUDED, "tweet_composer", "composer_edit_text", "compose_button",
                "bottom_navigation", "toolbar", "search_bar");
        ids(roles, Role.METADATA, "tweet_author", "tweet_author_name", "screenname", "screen_name");
        return roles;
    }

    private static void ids(Map<String, Role> roleMap, Role role, String... ids) {
        for (String id : ids) roleMap.put(id, role);
    }
}
