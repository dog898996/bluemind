package com.bluemind.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.EnumMap;
import java.util.Map;

public final class PackageSelectionTest {
    private static final String BLUEMIND_PACKAGE = "com.bluemind.app";
    private static final String MOD_PACKAGE = "app.custom.player3";
    private final Map<Platform, String> selections = new EnumMap<>(Platform.class);

    @Test
    public void acceptsInstalledReplacementPackageNameAndWhitespace() {
        assertEquals(MOD_PACKAGE, validate(Platform.YOUTUBE, " " + MOD_PACKAGE + " "));
        assertEquals("com.instagram.android", validate(Platform.INSTAGRAM, "com.instagram.android"));
    }

    @Test
    public void emptySelectionRestoresAutomaticMatching() {
        selections.put(Platform.YOUTUBE, MOD_PACKAGE);
        assertEquals("", validate(Platform.YOUTUBE, "  "));
    }

    @Test
    public void preservesSamePlatformButRejectsCrossPlatformDuplicateSelection() {
        selections.put(Platform.YOUTUBE, MOD_PACKAGE);
        assertEquals(MOD_PACKAGE, validate(Platform.YOUTUBE, MOD_PACKAGE));
        assertThrows(IllegalArgumentException.class, () -> validate(Platform.X, MOD_PACKAGE));
    }

    @Test
    public void rejectsOtherPlatformsOfficialPackageAndSelfPackage() {
        assertThrows(IllegalArgumentException.class,
                () -> validate(Platform.YOUTUBE, "com.instagram.android"));
        assertThrows(IllegalArgumentException.class,
                () -> validate(Platform.YOUTUBE, BLUEMIND_PACKAGE));
    }

    @Test
    public void rejectsInvalidPackageName() {
        assertThrows(IllegalArgumentException.class,
                () -> validate(Platform.YOUTUBE, "https://www.youtube.com"));
        assertThrows(IllegalArgumentException.class,
                () -> validate(Platform.YOUTUBE, "com..youtube"));
    }

    private String validate(Platform platform, String name) {
        return MindStore.validatePackage(platform, name, BLUEMIND_PACKAGE, selections::get);
    }
}
