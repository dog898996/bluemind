package com.bluemind.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A bounded, short-lived copy of an accessibility node, independent of Android APIs. */
public final class UiNode {
    public final String id;
    public final String className;
    public final String text;
    public final String description;
    public final String uniqueId;
    public final String state;
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;
    public final boolean visible;
    public final boolean clickable;
    public final boolean scrollable;
    public final int[] path;
    public final List<UiNode> children;

    public UiNode(String id, String className, String text, String description, String uniqueId,
                  int left, int top, int right, int bottom, boolean visible, boolean clickable,
                  boolean scrollable, int[] path, List<UiNode> children) {
        this(id, className, text, description, uniqueId, left, top, right, bottom,
                visible, clickable, scrollable, path, children, "");
    }

    public UiNode(String id, String className, String text, String description, String uniqueId,
                  int left, int top, int right, int bottom, boolean visible, boolean clickable,
                  boolean scrollable, int[] path, List<UiNode> children, String state) {
        this.id = string(id);
        this.className = string(className);
        this.text = string(text);
        this.description = string(description);
        this.uniqueId = string(uniqueId);
        this.state = string(state);
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.visible = visible;
        this.clickable = clickable;
        this.scrollable = scrollable;
        this.path = path == null ? new int[0] : path.clone();
        this.children = children == null || children.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(children));
    }

    private static String string(String value) {
        return value == null ? "" : value;
    }
}
