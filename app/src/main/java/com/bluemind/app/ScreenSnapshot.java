package com.bluemind.app;

import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ScreenSnapshot {
    private static final int MAX_NODES = 1_100;
    private static final int MAX_DEPTH = 28;
    private static final int MAX_TEXT_LENGTH = 320;

    private ScreenSnapshot() {
    }

    static UiNode capture(AccessibilityNodeInfo root, Rect viewport) {
        return capture(root, viewport, new int[0], new int[]{MAX_NODES});
    }

    private static UiNode capture(AccessibilityNodeInfo node, Rect viewport, int[] path, int[] budget) {
        budget[0]--;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        boolean visible = node.isVisibleToUser() && Rect.intersects(bounds, viewport);
        List<UiNode> children = new ArrayList<>();
        if (path.length < MAX_DEPTH) {
            int childCount = node.getChildCount();
            for (int index = 0; index < childCount && budget[0] > 0; index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child == null) {
                    budget[0]--;
                    continue;
                }
                try {
                    if (child.isVisibleToUser()) {
                        int[] childPath = Arrays.copyOf(path, path.length + 1);
                        childPath[path.length] = index;
                        children.add(capture(child, viewport, childPath, budget));
                    } else {
                        budget[0]--;
                    }
                } finally {
                    recycle(child);
                }
            }
        }

        boolean password = node.isPassword();
        String uniqueId = Build.VERSION.SDK_INT >= 33 ? bounded(node.getUniqueId()) : "";
        String state = Build.VERSION.SDK_INT >= 30 && !password ? bounded(node.getStateDescription()) : "";
        return new UiNode(
                bounded(node.getViewIdResourceName()), bounded(node.getClassName()),
                password ? "" : bounded(node.getText()), password ? "" : bounded(node.getContentDescription()),
                uniqueId, bounds.left, bounds.top, bounds.right, bounds.bottom,
                visible, node.isClickable(), node.isScrollable(), path, children, state);
    }

    static AccessibilityNodeInfo resolve(AccessibilityNodeInfo root, int[] path) {
        if (path.length == 0) {
            return AccessibilityNodeInfo.obtain(root);
        }
        AccessibilityNodeInfo current = root;
        for (int index : path) {
            AccessibilityNodeInfo child = current.getChild(index);
            if (current != root) {
                recycle(current);
            }
            if (child == null) {
                return null;
            }
            current = child;
        }
        return current;
    }

    static Rect bounds(UiNode node) {
        return new Rect(node.left, node.top, node.right, node.bottom);
    }

    private static String bounded(CharSequence value) {
        if (value == null) {
            return "";
        }
        return value.subSequence(0, Math.min(value.length(), MAX_TEXT_LENGTH)).toString();
    }

    @SuppressWarnings("deprecation")
    static void recycle(AccessibilityNodeInfo node) {
        if (node != null && Build.VERSION.SDK_INT < 33) {
            node.recycle();
        }
    }
}
