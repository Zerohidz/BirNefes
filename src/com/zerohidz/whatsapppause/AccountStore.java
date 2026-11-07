package com.zerohidz.whatsapppause;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Which token the device sends. Pure Java so it is unit tested without a device.
 * The token lives in saved storage, which survives app updates. Without one, a new
 * registration under this install. Only an http test build has a built-in token: it
 * is saved on first use. The saved value "" means a token was rejected and must not
 * be used again.
 */
final class AccountStore {
    interface Saved { String get(); void put(String token); }
    interface Registrar { String register(String install) throws IOException; }
    // The service and the main screen share one process; one registration at a time
    // keeps a second register from revoking the token the first one just saved.
    private static final Object LOCK = new Object();
    private final Saved saved;
    private final String builtIn, install;

    AccountStore(Saved saved, String builtIn, String install) {
        this.saved = saved;
        this.builtIn = builtIn == null ? "" : builtIn;
        this.install = install;
    }
    String install() { return install; }

    String token(Registrar registrar) throws IOException {
        synchronized (LOCK) {
            String token = saved.get();
            if (token != null && !token.isEmpty()) return token;
            if (token == null && !builtIn.isEmpty()) { saved.put(builtIn); return builtIn; }
            token = registrar.register(install);
            if (token == null || token.isEmpty()) throw new IOException("Empty token");
            saved.put(token);
            return token;
        }
    }
    /** After a 401. Only the rejected token is dropped, never a newer one saved meanwhile. */
    void rejected(String token) {
        synchronized (LOCK) {
            if (token.equals(saved.get())) saved.put("");
        }
    }
    /** After a successful pair: the device now belongs to that account. */
    void replace(String token) { synchronized (LOCK) { saved.put(token); } }

    static String install(String androidId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((androidId + ":birnefes").getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
