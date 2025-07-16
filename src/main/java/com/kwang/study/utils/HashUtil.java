package com.kwang.study.utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashUtil {
    public static final MessageDigest md;

    static {
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    public static String sha256Hash(byte[] input) {
        byte[] hashBytes = md.digest(input);
        return bytesToHex(hashBytes);
    }

    public static MessageDigest sha256() {
        return md;
    }

    public static String bytesToHex(byte[] input) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : input) {
            String hex = Integer.toHexString(0xff & b);
            if(hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}