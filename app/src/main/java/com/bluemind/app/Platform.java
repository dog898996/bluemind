package com.bluemind.app;

public enum Platform {
    INSTAGRAM("인스타그램"),
    TIKTOK("틱톡"),
    YOUTUBE("유튜브"),
    X("X");

    public final String label;

    Platform(String label) {
        this.label = label;
    }

    public static Platform fromPackage(String packageName) {
        if (packageName == null) return null;
        return switch (packageName) {
            case "com.instagram.android", "com.instagram.lite" -> INSTAGRAM;
            case "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
                    "com.zhiliaoapp.musically.go", "com.ss.android.ugc.tiktok.lite" -> TIKTOK;
            case "com.google.android.youtube", "app.revanced.android.youtube",
                    "app.rvx.android.youtube", "app.morphe.android.youtube", "com.revanced.android.youtube",
                    "com.google.android.youtube.revanced" -> YOUTUBE;
            case "com.twitter.android" -> X;
            default -> null;
        };
    }
}
