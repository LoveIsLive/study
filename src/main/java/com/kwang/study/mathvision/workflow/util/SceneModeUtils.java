package com.kwang.study.mathvision.workflow.util;

public final class SceneModeUtils {

    public static final String MODE_2D = "2d";
    public static final String MODE_3D = "3d";

    private SceneModeUtils() {
    }

    public static String normalize(String sceneMode) {
        return MODE_3D.equalsIgnoreCase(sceneMode != null ? sceneMode.trim() : null)
                ? MODE_3D
                : MODE_2D;
    }

    public static boolean isThreeD(String sceneMode) {
        return MODE_3D.equals(normalize(sceneMode));
    }
}
