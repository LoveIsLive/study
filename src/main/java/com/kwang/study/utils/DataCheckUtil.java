package com.kwang.study.utils;

import org.springframework.util.Assert;

import java.util.Set;

public class DataCheckUtil {

    private static final Set<Character> permissionsValidChars = Set.of('-', 'r', 'w', 'x');

    public static boolean checkPermissions(String permissions) {
        if (permissions == null) {
            return true;
        }
        if (permissions.length() != 9) {
            return false;
        }
        for (int i = 0; i < permissions.length(); i++) {
            if (!permissionsValidChars.contains(permissions.charAt(i))) return false;
        }
        return true;
    }


}
