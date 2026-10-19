package com.mihir.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Greenify-style "force stop" without root or ADB: for each app, open its App info page in the TV's
 * Settings and press Force stop, then OK.
 *
 * Safeguards:
 *  - res/xml/cleaner.xml limits the service to com.android.tv.settings, so Android never shows it any
 *    other app's screen or keystrokes. The launcher has no INTERNET permission, so nothing can leave the TV.
 *  - It only acts during a clean-up the user starts from Home Settings, with a time limit.
 *  - It presses a button only when the screen is exactly what it expects: the App info page titled with
 *    the app's own name and its "Force stop" row, then the confirmation headed "Force stop" for that same
 *    app and its "OK" button. Anything else (a different app, a different language, a moved button) is
 *    skipped, never guessed at.
 */
public class CleanerService extends AccessibilityService {
    private static final String SETTINGS = "com.android.tv.settings";
    private static final String FORCE_STOP = "Force stop", OK = "OK";
    private static final long STEP_TIMEOUT_MS = 5_000, RUN_TIMEOUT_MS = 120_000;

    private static CleanerService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String[]> queue = new ArrayDeque<>();
    private String[] current; // {package, label}
    private int step;         // 0: App info page, 1: confirmation, 2: done with this app
    private int stopped;
    private IntConsumer onDone;

    private final Runnable tryStep = this::tryStep;
    private final Runnable skip = this::next;
    private final Runnable giveUp = this::finish;

    /** True once the user has switched the service on in Settings › Accessibility. */
    static boolean isEnabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(new ComponentName(c, CleanerService.class).flattenToShortString())
                || on != null && on.contains(new ComponentName(c, CleanerService.class).flattenToString());
    }

    static boolean isRunning() {
        return instance != null && instance.current != null;
    }

    /** Force-stop each {package, label}; {@code done} gets how many were stopped. False if the service isn't on. */
    static boolean run(List<String[]> apps, IntConsumer done) {
        CleanerService s = instance;
        if (s == null || s.current != null) return false;
        s.queue.clear();
        s.queue.addAll(apps);
        s.stopped = 0;
        s.onDone = done;
        s.handler.postDelayed(s.giveUp, RUN_TIMEOUT_MS);
        s.next();
        return true;
    }

    static void abort() {
        if (instance != null && instance.current != null) {
            instance.queue.clear();
            instance.finish();
        }
    }

    @Override protected void onServiceConnected() { instance = this; }

    @Override public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onInterrupt() { }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (current == null) return; // idle: ignore everything
        handler.removeCallbacks(tryStep);
        handler.postDelayed(tryStep, 250); // let the screen settle
    }

    private void next() {
        handler.removeCallbacks(skip);
        handler.removeCallbacks(tryStep);
        current = queue.poll();
        if (current == null) { finish(); return; }
        step = 0;
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", current[0], null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(i);
        } catch (RuntimeException ex) {
            handler.post(skip);
            return;
        }
        handler.postDelayed(skip, STEP_TIMEOUT_MS);
    }

    private void tryStep() {
        if (current == null) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !SETTINGS.contentEquals(root.getPackageName())) return;
        String label = current[1];
        if (step == 0) {
            if (!label.equals(text(root, SETTINGS + ":id/decor_title"))) return; // not this app's page (yet)
            AccessibilityNodeInfo row = clickableWithText(root, "android:id/title", FORCE_STOP);
            if (row == null) return;
            if (!row.isEnabled()) { next(); return; } // already stopped
            step = 1;
            restartTimeout();
            row.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } else if (step == 1) {
            if (!label.equals(text(root, SETTINGS + ":id/guidance_breadcrumb"))
                    || !FORCE_STOP.equals(text(root, SETTINGS + ":id/guidance_title"))) return;
            AccessibilityNodeInfo ok = clickableWithText(root, SETTINGS + ":id/guidedactions_item_title", OK);
            if (ok == null || !ok.isEnabled()) return;
            step = 2;
            stopped++;
            ok.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            handler.removeCallbacks(skip);
            handler.postDelayed(skip, 700);
        }
    }

    private void restartTimeout() {
        handler.removeCallbacks(skip);
        handler.postDelayed(skip, STEP_TIMEOUT_MS);
    }

    private void finish() {
        handler.removeCallbacks(skip);
        handler.removeCallbacks(tryStep);
        handler.removeCallbacks(giveUp);
        boolean wasRunning = current != null || onDone != null;
        current = null;
        queue.clear();
        IntConsumer done = onDone;
        onDone = null;
        if (!wasRunning) return;
        // Back to the launcher, which shows the result.
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        if (done != null) done.accept(stopped);
    }

    private static String text(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(viewId);
        if (l == null || l.size() != 1 || l.get(0).getText() == null) return null;
        return l.get(0).getText().toString();
    }

    /** The clickable row around the one text view with this id and exactly this text, or null. */
    private static AccessibilityNodeInfo clickableWithText(AccessibilityNodeInfo root, String viewId, String want) {
        AccessibilityNodeInfo match = null;
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(viewId)) {
            if (n.getText() != null && want.equals(n.getText().toString())) {
                if (match != null) return null; // ambiguous: don't guess
                match = n;
            }
        }
        for (AccessibilityNodeInfo n = match; n != null; n = n.getParent()) if (n.isClickable()) return n;
        return null;
    }
}
