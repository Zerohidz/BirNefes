package com.zerohidz.whatsapppause;

import java.io.IOException;

public final class AccountStoreTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    private static final class Memory implements AccountStore.Saved {
        String value;
        Memory(String value) { this.value = value; }
        public String get() { return value; }
        public void put(String token) { value = token; }
    }
    private static final class Server implements AccountStore.Registrar {
        int calls; String lastInstall;
        public String register(String install) { calls++; lastInstall = install; return "registered-" + calls; }
    }
    public static void main(String[] args) throws Exception {
        String install = AccountStore.install("abc123");
        check(install.matches("[0-9a-f]{64}"), "install is 64 lowercase hex");
        // printf abc123:birnefes | sha256sum
        check(install.equals("f9e49380dd0148ba0966ed5f7cc1eccea6ba60bacb4dd5ecb791f705e9c330a1"), "install hashes ANDROID_ID + :birnefes");
        check(!install.equals(AccountStore.install("abc124")), "install differs per device");

        // Saved token wins over the built-in one.
        Server server = new Server();
        AccountStore saved = new AccountStore(new Memory("saved"), "builtin", install);
        check(saved.token(server).equals("saved"), "saved token first");
        check(server.calls == 0, "no register with a saved token");

        // A personal build keeps its built-in token while nothing is saved.
        Memory memory = new Memory(null);
        AccountStore personal = new AccountStore(memory, "builtin", install);
        check(personal.token(server).equals("builtin"), "built-in token second");
        check(memory.value == null && server.calls == 0, "built-in token is not copied or registered");

        // A rejected built-in token is never used again; the install registers instead.
        personal.rejected("builtin");
        check("".equals(memory.value), "rejected built-in leaves the empty marker");
        check(personal.token(server).equals("registered-1"), "register after built-in rejected");
        check(server.lastInstall.equals(install), "register sends the install");
        check(memory.value.equals("registered-1"), "registered token saved");
        check(personal.token(server).equals("registered-1") && server.calls == 1, "saved registration reused");

        // A public build registers on first need and saves the result.
        server = new Server();
        memory = new Memory(null);
        AccountStore open = new AccountStore(memory, "", install);
        check(open.token(server).equals("registered-1") && server.calls == 1, "public build registers once");
        check(open.token(server).equals("registered-1") && server.calls == 1, "public build reuses saved token");

        // 401 drops only the rejected token, then the same install registers again.
        open.rejected("registered-1");
        check(open.token(server).equals("registered-2") && server.calls == 2, "re-register after 401");
        // A late 401 for an older token must not drop the newer one.
        open.rejected("registered-1");
        check(memory.value.equals("registered-2"), "stale 401 keeps the current token");

        // Pairing replaces the token, which then wins over the built-in one.
        memory = new Memory(null);
        AccountStore paired = new AccountStore(memory, "builtin", install);
        paired.replace("paired");
        check(paired.token(server).equals("paired"), "paired token used");
        paired.rejected("builtin");
        check(memory.value.equals("paired"), "built-in 401 after pair keeps paired token");

        // Offline register: the error reaches the caller and nothing is saved.
        memory = new Memory(null);
        AccountStore offline = new AccountStore(memory, "", install);
        boolean failed = false;
        try { offline.token(i -> { throw new IOException("offline"); }); } catch (IOException expected) { failed = true; }
        check(failed && memory.value == null, "offline register fails without saving");
        failed = false;
        try { offline.token(i -> ""); } catch (IOException expected) { failed = true; }
        check(failed && memory.value == null, "empty token rejected");

        System.out.println("AccountStoreTest: " + checks + " checks passed");
    }
}
