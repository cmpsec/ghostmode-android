package com.today.td.root;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;

/**
 * Runs from app_process after the user confirms an account removal.
 * The process is started only from a root shell and drops to the system user
 * so AccountManager accepts the removal.
 */
public final class PrivilegedEntry {
    public static void main(String[] args) {
        try {
            if (args.length >= 1 && "list".equals(args[0])) {
                listAccounts();
                return;
            }
            if (args.length < 2 || !"file".equals(args[0])) {
                System.out.println("RESULT:BAD_ARGS");
                System.exit(2);
                return;
            }
            String text = readFully(args[1]);
            try {
                Os.setuid(1000);
                Os.setgid(1000);
            } catch (ErrnoException error) {
                System.out.println("RESULT:SETUID:" + error.errno);
            }
            Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            if (context == null) {
                System.out.println("RESULT:NO_CONTEXT");
                System.exit(1);
                return;
            }
            String[] lines = text.split("\n", -1);
            String action = lines.length == 0 ? "" : lines[0];
            if ("remove-many".equals(action)) {
                AccountManager manager = AccountManager.get(context);
                int ok = 0;
                int fail = 0;
                for (int i = 1; i < lines.length; i++) {
                    String line = lines[i];
                    if (line.length() == 0) continue;
                    int tab = line.indexOf('\t');
                    if (tab <= 0 || tab >= line.length() - 1) {
                        fail++;
                        continue;
                    }
                    String name = line.substring(0, tab);
                    String type = line.substring(tab + 1);
                    try {
                        boolean removed = manager.removeAccountExplicitly(new Account(name, type));
                        System.out.println(removed ? "OK" : "FAIL");
                        if (removed) ok++;
                        else fail++;
                    } catch (Throwable error) {
                        fail++;
                        System.out.println("FAIL:" + error.getClass().getSimpleName() + ":" + error.getMessage());
                    }
                }
                System.out.println("RESULT:DONE:" + ok + ":" + fail);
                System.exit(ok > 0 ? 0 : 1);
            } else {
                System.out.println("RESULT:UNKNOWN");
                System.exit(2);
            }
        } catch (Throwable error) {
            System.out.println("RESULT:ERROR:" + error.getClass().getSimpleName() + ":" + error.getMessage());
            System.exit(1);
        }
    }

    private static void listAccounts() throws Exception {
        try {
            Os.setuid(1000);
            Os.setgid(1000);
        } catch (ErrnoException error) {
            System.out.println("RESULT:SETUID:" + error.errno);
        }
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        if (context == null) {
            System.out.println("RESULT:NO_CONTEXT");
            System.exit(1);
            return;
        }
        Account[] accounts = AccountManager.get(context).getAccounts();
        for (Account account : accounts) {
            if (account.name == null || account.type == null) continue;
            if (account.name.indexOf('\n') >= 0 || account.name.indexOf('\t') >= 0) continue;
            if (account.type.indexOf('\n') >= 0 || account.type.indexOf('\t') >= 0) continue;
            System.out.println(account.name + "\t" + account.type);
        }
        System.out.println("RESULT:LIST:" + accounts.length);
        System.exit(0);
    }

    private static String readFully(String path) throws Exception {
        FileInputStream in = new FileInputStream(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
