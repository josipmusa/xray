package com.xray.parse;

import com.xray.model.Enums;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class EdgeId {

    private EdgeId() {}

    public static String of(Enums.EdgeType type, String fromId, String toId, String extra) {
        String payload = type + "|" + fromId + "|" + toId + "|" + (extra == null ? "" : extra);
        return "e:" + sha256Hex(payload).substring(0, 16); // short, deterministic
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
