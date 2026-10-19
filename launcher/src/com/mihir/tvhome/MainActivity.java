package com.mihir.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV (tvOS) style home screen: the photo fills the top half like a top shelf, round glass
 * buttons and a small clock sit top-right, and the dock and every other app sit in rows of wide
 * icons on a frosted shelf below. The focused icon lifts, catches a light sweep and shows its name.
 * Plain framework views only (no AndroidX), no network, no background work.
 */
public class MainActivity extends Activity {

    // Dock, pinned order, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps, pinnedApps;
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    // Every focusable row below the photo, top to bottom (dock, recent, grid rows, settings button).
    private final List<List<View>> cardRows = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private ImageView wallBack, wallFront;
    // Frosted shelf: a blurred copy of the photo, clipped to start where the dock begins.
    private FrameLayout frost;
    private ImageView frostImg;
    private Drawable frostCurrent;
    private List<String> wallpapers = new ArrayList<>();
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    private View clockBlock, shelfTint, inputsBtn;
    private final java.util.Map<String, Integer> edgeCache = new HashMap<>();
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { openStartTarget(); }
    };
    private PopupWindow settingsPopup;
    private TvInputManager.TvInputCallback inputCallback;
    private final Map<String, Integer> inputStates = new HashMap<>();
    private boolean resumed;
    private TextView greeting, clock, ampm, date, wifiCaption;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow menuPopup;
    // Layout as shown (dock first), so a moved app can be re-placed and the order saved.
    private final List<String> order = new ArrayList<>();
    private final Map<String, View> cardByPkg = new HashMap<>();
    private int dockSize;
    private String movingPkg;
    private android.animation.ObjectAnimator wiggle;
    private TextView hint;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = getResources().getStringArray(R.array.dock_apps);
        pinnedApps = getResources().getStringArray(R.array.pinned_apps);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        hidden.add("com.android.tv.settings"); // the Settings button already opens it
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = getResources().getInteger(R.integer.columns);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        applyLook();
        buildApps();
        watchInputs();
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        // Just booted: honour "When the TV turns on" too.
        if (b == null && SystemClock.elapsedRealtime() < 5 * 60_000) handler.postDelayed(this::openStartTarget, 1500);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        resumed = true;
        updateClock();
        updateWifi();
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        if (CleanerService.isRunning()) CleanerService.abort();
        // New photo on return if the current one has been up for the full interval.
        long every = wallpaperInterval(), shown = SystemClock.elapsedRealtime() - wallChangedAt;
        if (wallIndex < 0 || (every > 0 && shown >= every)) nextWallpaper();
        else if (every > 0) scheduleWallpaper(every - shown);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        unregisterReceiver(tick);
        resumed = false;
        if (menuPopup != null) menuPopup.dismiss();
        if (settingsPopup != null) settingsPopup.dismiss();
        if (movingPkg != null) endMove();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        unregisterReceiver(screenOn);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null && inputCallback != null) tim.unregisterCallback(inputCallback);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (menuPopup != null) menuPopup.dismiss();
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Light scrim at the very top so the clock and buttons stay readable on bright photos.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x1A000000, 0x00000000, 0x00000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // Frosted shelf behind the app rows. It is moved (not resized) as the rows scroll, and the
        // blurred photo inside is moved the opposite way so it stays lined up with the real one.
        frost = new FadeTopFrame(this, dp(80));
        frostImg = wallpaperLayer();
        frost.addView(frostImg, new FrameLayout.LayoutParams(-1, -1));
        shelfTint = new View(this);
        frost.addView(shelfTint, new FrameLayout.LayoutParams(-1, -1));
        root.addView(frost, new FrameLayout.LayoutParams(-1, -1));

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> positionFrost());
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Top shelf: the top half of the screen is just the photo, with the header in its corner.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, Math.round(screenH * 0.52f)));
        hero.addView(buildHeader(), lp(-2, -2, Gravity.END | Gravity.TOP, 0, dp(24), dp(40), 0));
        clockBlock = buildClock();
        hero.addView(clockBlock, lp(dp(420), -2, Gravity.START | Gravity.TOP, dp(48), dp(36), 0, 0));
        hero.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> positionFrost());

        // The dock sits in a rounded glass tray over the photo, like tvOS's top row.
        dockRow = row();
        // Side padding fits an end icon's 16% focus growth inside the glass, with room to spare.
        dockRow.setPadding(dp(30), dp(18), dp(30), dp(18));
        GradientDrawable tray = new GradientDrawable();
        tray.setCornerRadius(dp(28));
        tray.setColor(0x38FFFFFF);
        tray.setStroke(Math.max(1, dp(0.5f)), 0x24FFFFFF);
        dockRow.setBackground(tray);
        content.addView(dockRow, llp(-1, -2, dp(74), dp(10), dp(74), dp(34)));
        dockRow.addOnLayoutChangeListener((v, l, t, r, b2, ol, ot, or, ob) -> positionFrost());

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(104), 0, dp(104), dp(60)));

        // Shown while moving an app, like tvOS's jiggle mode.
        hint = text(12, "sans-serif-medium", Color.WHITE);
        hint.setText("◀ ▶ ▲ ▼  move     OK  done");
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(18), dp(8), dp(18), dp(8));
        GradientDrawable hb = new GradientDrawable();
        hb.setCornerRadius(dp(18));
        hb.setColor(0xD91C1C1E);
        hb.setStroke(Math.max(1, dp(0.5f)), 0x33FFFFFF);
        hint.setBackground(hb);
        hint.setVisibility(View.GONE);
        root.addView(hint, lp(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(24), 0, 0));
        return root;
    }

    private void positionFrost() {
        if (frost == null || hero == null) return;
        // The frosted area starts just under the dock tray; above it the photo stays sharp.
        float top = Math.max(0, dockRow.getBottom() - dp(60) - scroll.getScrollY());
        frost.setTranslationY(top);
        frostImg.setTranslationY(-top);
    }

    /** One frosted capsule in the top-right holding the controls, like tvOS's Control Center pill. */
    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);
        View capsule = new View(this);
        GradientDrawable cb = new GradientDrawable();
        cb.setCornerRadius(dp(27));
        cb.setColor(0x47000000);
        cb.setStroke(Math.max(1, dp(0.5f)), 0x2EFFFFFF);
        capsule.setBackground(cb);
        // Sized to the buttons after layout (a match_parent child would stretch a wrap_content frame).
        header.addView(capsule, new FrameLayout.LayoutParams(0, dp(54)));

        LinearLayout right = new LinearLayout(this);
        right.setClipChildren(false);
        right.setClipToPadding(false);
        right.setPadding(dp(4), dp(6), dp(4), 0);
        View search = glassButton(R.drawable.ic_mic, "Search", right);
        search.setOnClickListener(v -> voiceSearch());
        inputsBtn = glassButton(R.drawable.ic_inputs, "Inputs", right);
        inputsBtn.setOnClickListener(v -> showInputs(v));
        View wifi = glassButton(R.drawable.ic_wifi, "Wi-Fi", right);
        wifiCaption = (TextView) wifi.getTag();
        wifi.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        View settings = glassButton(R.drawable.ic_settings, "Settings", right);
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        header.addView(right, new FrameLayout.LayoutParams(-2, -2));
        right.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (capsule.getLayoutParams().width != r - l) {
                capsule.getLayoutParams().width = r - l;
                capsule.post(capsule::requestLayout);
            }
        });
        return header;
    }

    private void voiceSearch() {
        if (!launch(new Intent(Intent.ACTION_ASSIST))) launch(new Intent("android.search.action.GLOBAL_SEARCH"));
    }

    /** Greeting, large thin clock and date in the top-left corner of the photo. */
    private View buildClock() {
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(10, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(10, "sans-serif-medium", 0x99FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        left.addView(date);
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        return left;
    }

    /** A round frosted icon button with its name shown underneath while focused (tvOS style). */
    private View glassButton(int icon, String caption, LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setPadding(dp(11), dp(11), dp(11), dp(11));
        b.setColorFilter(Color.WHITE);
        b.setFocusable(true);
        b.setClickable(true);
        b.setId(View.generateViewId());
        b.setContentDescription(caption);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.TRANSPARENT);
        b.setBackground(bg);
        b.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        box.addView(b, new LinearLayout.LayoutParams(dp(42), dp(42)));

        TextView label = text(10, "sans-serif-medium", Color.WHITE);
        label.setText(caption);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setShadowLayer(dp(6), 0, dp(1), 0xB3000000);
        label.setAlpha(0f);
        box.addView(label, llp(-1, -2, 0, dp(8), 0, 0));
        b.setTag(label);

        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFF5F5F7 : Color.TRANSPARENT);
            b.setColorFilter(has ? 0xFF1C1C1E : Color.WHITE);
            v.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).translationZ(has ? dp(8) : 0)
                    .setInterpolator(new DecelerateInterpolator(2f)).setDuration(200).start();
            label.animate().alpha(has ? 1f : 0f).setDuration(180).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); }
        });
        parent.addView(box, new LinearLayout.LayoutParams(dp(56), -2));
        pills.add(b);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        Set<String> userHidden = userHidden();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || userHidden.contains(ai.packageName)
                    || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }

        // A layout arranged on the TV (Move / Add to Dock) wins over config.xml; new apps go last.
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String savedDock = prefs.getString("dock", null), savedGrid = prefs.getString("grid", null);
        String[] dockOrder = savedDock != null ? savedDock.split(",") : dockApps;
        String[] gridOrder = savedGrid != null ? savedGrid.split(",") : pinnedApps;
        Map<String, App> all = new HashMap<>(byPkg);
        List<App> dock = new ArrayList<>();
        for (String p : dockOrder) if (byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        List<App> rest = new ArrayList<>();
        for (String p : gridOrder) if (byPkg.containsKey(p)) rest.add(byPkg.remove(p));
        List<App> others = new ArrayList<>(byPkg.values());
        Collections.sort(others, (x, y) -> x.label.compareToIgnoreCase(y.label));
        rest.addAll(others);

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        cardRows.clear();
        cardByPkg.clear();
        order.clear();
        for (App a : dock) order.add(a.pkg);
        for (App a : rest) order.add(a.pkg);
        dockSize = dock.size();

        for (int i = 0; i < dock.size(); i++) dockRow.addView(tile(dock.get(i), true, dockCards), cell(i));
        padRow(dockRow, dock.size());
        if (!dockCards.isEmpty()) cardRows.add(dockCards);

        // Recently used apps (not already in the dock), newest first.
        List<App> recent = new ArrayList<>();
        if (pref("recent", true)) for (String p : recentPackages()) {
            App a = all.get(p);
            if (a != null && order.indexOf(p) >= dockSize && recent.size() < cols) recent.add(a);
        }
        if (recent.size() >= 2) {
            grid.addView(sectionLabel("RECENTLY USED"), llp(-2, -2, dp(4), 0, 0, dp(14)));
            LinearLayout r = row();
            List<View> cards = new ArrayList<>();
            for (int i = 0; i < recent.size(); i++) r.addView(tile(recent.get(i), false, cards), cell(i));
            padRow(r, recent.size());
            grid.addView(r, llp(-1, -2, 0, 0, 0, dp(26)));
            cardRows.add(cards);
            grid.addView(sectionLabel("ALL APPS"), llp(-2, -2, dp(4), 0, 0, dp(14)));
        }

        LinearLayout row = null;
        List<View> cards = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = row();
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
                cards = new ArrayList<>();
                cardRows.add(cards);
            }
            row.addView(tile(rest.get(i), false, cards), cell(i % cols));
        }
        if (row != null) padRow(row, rest.size() % cols);

        // Home Settings, at the very bottom like tvOS's Settings app is never far away.
        LinearLayout settingsRow = row();
        settingsRow.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView settingsBtn = text(12, "sans-serif-medium", 0xE6FFFFFF);
        settingsBtn.setText("⚙   Home Settings");
        settingsBtn.setGravity(Gravity.CENTER);
        settingsBtn.setPadding(dp(20), 0, dp(20), 0);
        settingsBtn.setFocusable(true);
        settingsBtn.setClickable(true);
        settingsBtn.setId(View.generateViewId());
        GradientDrawable sb = new GradientDrawable();
        sb.setCornerRadius(dp(18));
        sb.setColor(0x26FFFFFF);
        sb.setStroke(Math.max(1, dp(0.5f)), 0x33FFFFFF);
        settingsBtn.setBackground(sb);
        settingsBtn.setOnFocusChangeListener((v, has) -> {
            sb.setColor(has ? 0xFFF5F5F7 : 0x26FFFFFF);
            settingsBtn.setTextColor(has ? 0xFF1C1C1E : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(180).start();
            if (has) { lastFocusedId = v.getId(); scrollToRow(v); }
        });
        settingsBtn.setOnClickListener(v -> showSettings());
        settingsRow.addView(settingsBtn, new LinearLayout.LayoutParams(-2, dp(36)));
        grid.addView(settingsRow, llp(-1, -2, 0, dp(6), 0, dp(10)));
        List<View> last1 = new ArrayList<>();
        last1.add(settingsBtn);
        cardRows.add(last1);
        wireFocus();

        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null) last.requestFocus();
        else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    private LinearLayout.LayoutParams cell(int col) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(col == 0 ? 0 : dp(11), 0, col == cols - 1 ? 0 : dp(11), 0);
        return p;
    }

    /** Fill a short row with blanks so its icons keep the same width as a full row's. */
    private void padRow(LinearLayout row, int used) {
        for (int i = used; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = cell(i);
            p.height = 1;
            row.addView(new View(this), p);
        }
    }

    /** A wide rounded icon (tvOS proportions). Focus lifts it and sweeps a highlight across. */
    private View tile(App app, boolean inDock, List<View> sink) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
            }
        });
        // Banners are 16:9 and the tile 5:3: show the whole banner on its own edge colour, never cropped.
        int edge = app.isBanner ? edgeColor(app.pkg, app.art) : 0;
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                app.isBanner ? new int[]{edge, edge}
                        : new int[]{darken(app.color, 0.55f), darken(app.color, 0.35f)});
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setPadding(dp(24), dp(16), dp(24), dp(16));
        }
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));

        // Glass: a soft gloss on the top half, plus a diagonal light band that sweeps across on focus.
        View gloss = new View(this);
        gloss.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x33FFFFFF, 0x0DFFFFFF, 0x00FFFFFF}));
        gloss.setAlpha(0f);
        card.addView(gloss, new FrameLayout.LayoutParams(-1, -1));
        View sweep = new View(this);
        sweep.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF}));
        sweep.setRotation(18f);
        sweep.setAlpha(0f);
        card.addView(sweep, new FrameLayout.LayoutParams(dp(56), -1, Gravity.START));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        // No name under the focused icon (tvOS doesn't show one); screen readers still get it.
        card.setContentDescription(app.label);

        card.setOnClickListener(v -> openApp(app));
        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.16f : 1f).scaleY(has ? 1.16f : 1f)
                    .translationY(has ? -dp(4) : 0).translationZ(has ? dp(18) : 0)
                    .setInterpolator(new DecelerateInterpolator(2.2f))
                    .setDuration(has ? 260 : 180).start();
            gloss.animate().alpha(has ? 1f : 0f).setDuration(200).start();
            if (!has) { sweep.animate().cancel(); sweep.setAlpha(0f); return; }
            sweep.setAlpha(1f);
            sweep.setTranslationX(-dp(80));
            sweep.animate().translationX(v.getWidth() + dp(20)).setStartDelay(90).setDuration(650)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .withEndAction(() -> sweep.setAlpha(0f)).start();
            lastFocusedId = v.getId();
            if (inDock) scroll.smoothScrollTo(0, 0);
            else scrollToRow(box);
        });

        card.setOnLongClickListener(v -> { showAppMenu(card, app); return true; });
        cardByPkg.put(app.pkg, card);
        sink.add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press to the nearest column; row ends and the top bar don't wrap. */
    private void wireFocus() {
        int topId = pills.get(0).getId();
        List<View> first = cardRows.isEmpty() ? null : cardRows.get(0);
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(first != null ? first.get(Math.min(first.size() - 1, first.size() / 2)).getId() : p.getId());
        }
        for (int r = 0; r < cardRows.size(); r++) {
            List<View> row = cardRows.get(r);
            for (int c = 0; c < row.size(); c++) {
                View v = row.get(c);
                v.setNextFocusLeftId(c == 0 ? v.getId() : row.get(c - 1).getId());
                v.setNextFocusRightId(c == row.size() - 1 ? v.getId() : row.get(c + 1).getId());
                v.setNextFocusUpId(r == 0 ? topId : nearest(cardRows.get(r - 1), c, row.size()).getId());
                v.setNextFocusDownId(r == cardRows.size() - 1 ? v.getId() : nearest(cardRows.get(r + 1), c, row.size()).getId());
            }
        }
    }

    /** The view in {@code to} under column {@code c} of a row with {@code width} items (centred for short rows). */
    private static View nearest(List<View> to, int c, int width) {
        if (to.size() == 1 || width == 1) return to.get(width == 1 ? to.size() / 2 : 0);
        return to.get(Math.min(c, to.size() - 1));
    }

    /** Scroll just enough to keep the focused row (and its name) on screen, like tvOS. */
    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int top = grid.getTop() + row.getTop();
        int bottom = grid.getTop() + row.getBottom();
        int screenH = scroll.getHeight();
        int y = scroll.getScrollY();
        int target = y;
        if (bottom + dp(36) > y + screenH) target = bottom + dp(36) - screenH;
        else if (top - dp(48) < y) target = top - dp(48);
        int max = Math.max(0, content.getHeight() - screenH);
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    private void showInputs(View anchor) {
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(inputRow(inputLabel(t), () -> openInput(t.getId())));
        }
        if (isInstalled(liveTvPkg)) rows.add(inputRow("Live TV", () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("All sources…", () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(inputRow("Settings", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        showMenu(anchor, rows, Gravity.END, dp(10));
    }

    /** Long-press OK (or Menu) on an app: the tvOS-style context menu. */
    private void showAppMenu(View card, App app) {
        List<TextView> rows = new ArrayList<>();
        boolean inDock = order.indexOf(app.pkg) < dockSize;
        rows.add(inputRow("Move", () -> startMove(app.pkg)));
        if (inDock) rows.add(inputRow("Remove from Dock", () -> setInDock(app.pkg, false)));
        else if (dockSize < cols) rows.add(inputRow("Add to Dock", () -> setInDock(app.pkg, true)));
        rows.add(inputRow("App info", () -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", app.pkg, null)))));
        rows.add(inputRow("Hide", () -> setHidden(app.pkg, true)));
        if (isLocked(app.pkg)) rows.add(inputRow("Unlock", () -> verifyPin("Enter PIN to unlock " + app.label, () -> setLocked(app.pkg, false))));
        else rows.add(inputRow("Lock with PIN", () -> {
            if (hasPin()) setLocked(app.pkg, true);
            else choosePin(() -> setLocked(app.pkg, true));
        }));
        if (!isSystemApp(app.pkg)) rows.add(inputRow("Uninstall", () -> launch(new Intent(Intent.ACTION_DELETE,
                android.net.Uri.fromParts("package", app.pkg, null)))));
        showMenu(card, rows, Gravity.START, dp(14));
    }

    private void showMenu(View anchor, List<TextView> rows, int gravity, int gap) {
        if (menuPopup != null) menuPopup.dismiss();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(0xEB2C2C2E);
        bg.setStroke(Math.max(1, dp(0.5f)), 0x33FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> {
            menuPopup = null;
            if (movingPkg == null) anchor.requestFocus();
        });
        menuPopup = pw;
        // Open upwards when the menu would run off the bottom of the screen.
        list.measure(View.MeasureSpec.makeMeasureSpec(dp(200), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        boolean below = at[1] + anchor.getHeight() + gap + list.getMeasuredHeight() <= screenH;
        pw.showAsDropDown(anchor, 0, below ? gap : -(anchor.getHeight() + gap + list.getMeasuredHeight()), gravity);
        rows.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- arranging apps

    private boolean isSystemApp(String pkg) {
        try {
            return (getPackageManager().getApplicationInfo(pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private void setInDock(String pkg, boolean dock) {
        int i = order.indexOf(pkg);
        if (i < 0) return;
        order.remove(i);
        if (dock) { order.add(dockSize, pkg); dockSize++; }
        else { dockSize--; order.add(dockSize, pkg); }
        saveLayout(pkg);
    }

    /** Persist the on-screen order, rebuild, and keep focus on the app that moved. */
    private void saveLayout(String focusPkg) {
        getPreferences(MODE_PRIVATE).edit()
                .putString("dock", android.text.TextUtils.join(",", order.subList(0, dockSize)))
                .putString("grid", android.text.TextUtils.join(",", order.subList(dockSize, order.size())))
                .apply();
        lastFocusedId = View.NO_ID;
        buildApps();
        View c = cardByPkg.get(focusPkg);
        if (c != null) c.requestFocus();
        if (movingPkg != null) startWiggle();
    }

    private void startMove(String pkg) {
        movingPkg = pkg;
        hint.setText("◀ ▶ ▲ ▼  move     OK  done");
        hint.setVisibility(View.VISIBLE);
        hint.setAlpha(0f);
        hint.animate().alpha(1f).setDuration(200).start();
        View c = cardByPkg.get(pkg);
        if (c != null) c.requestFocus();
        startWiggle();
    }

    private void startWiggle() {
        if (wiggle != null) wiggle.cancel();
        View c = cardByPkg.get(movingPkg);
        if (c == null) return;
        wiggle = android.animation.ObjectAnimator.ofFloat(c, View.ROTATION, -1.6f, 1.6f);
        wiggle.setDuration(130);
        wiggle.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        wiggle.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        wiggle.start();
    }

    private void endMove() {
        if (wiggle != null) wiggle.cancel();
        View c = cardByPkg.get(movingPkg);
        if (c != null) c.setRotation(0f);
        movingPkg = null;
        hint.animate().alpha(0f).setDuration(200).withEndAction(() -> hint.setVisibility(View.GONE)).start();
    }

    /**
     * Arrow keys while moving. Left/Right shift the app within the dock or within the grid; Up/Down
     * move it a row, and crossing between grid and dock grows or shrinks the dock (max one row).
     */
    private void moveBy(int keyCode) {
        int i = order.indexOf(movingPkg), n = order.size();
        if (i < 0) return;
        boolean inDock = i < dockSize;
        int gridPos = i - dockSize;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (inDock ? i == 0 : gridPos == 0) return;
                Collections.swap(order, i, i - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (inDock ? i == dockSize - 1 : i == n - 1) return;
                Collections.swap(order, i, i + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (inDock) return;
                order.remove(i);
                if (gridPos >= cols) {
                    order.add(i - cols, movingPkg);
                } else {
                    // Into the dock at this column; a full dock pushes its last app down to the grid.
                    order.add(Math.min(gridPos, dockSize), movingPkg);
                    if (dockSize < cols) dockSize++;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (inDock) {
                    order.remove(i);
                    dockSize--;
                    order.add(Math.min(dockSize + i, order.size()), movingPkg);
                } else {
                    if (i + cols >= n) return;
                    order.remove(i);
                    order.add(i + cols, movingPkg);
                }
                break;
            default: return;
        }
        saveLayout(movingPkg);
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Date now = new Date();
        int h = Integer.parseInt(new SimpleDateFormat("H", Locale.US).format(now));
        greeting.setText(h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING");
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
    }

    private void updateWifi() {
        String name = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
            }
        } catch (SecurityException ignored) { }
        // With Location off, Android hides the SSID and network id, so ask ConnectivityManager instead.
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (nc == null) name = "Offline";
        else if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && name.equals("Wi-Fi")) name = "Online";
        wifiCaption.setText(name);
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Decode the next photo (and its frosted copy) off the main thread, then crossfade them in. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        wallpapers = Wallpapers.list(this);
        if (wallpapers.isEmpty()) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.size();
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String src = wallpapers.get(wallIndex);
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        new Thread(() -> {
            Bitmap bm = Wallpapers.load(this, src, w, h), blurred = bm == null ? null : frosted(bm);
            runOnUiThread(() -> showWallpaper(bm, blurred));
        }).start();
        if (wallpaperInterval() > 0) scheduleWallpaper(wallpaperInterval());
    }

    /** Cheap strong blur without RenderScript: halve with filtering down to ~60px wide, then scale back up. */
    private static Bitmap frosted(Bitmap src) {
        Bitmap b = src;
        while (b.getWidth() > 60) {
            Bitmap half = Bitmap.createScaledBitmap(b, Math.max(1, b.getWidth() / 2), Math.max(1, b.getHeight() / 2), true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap up = Bitmap.createScaledBitmap(b, src.getWidth() / 8, src.getHeight() / 8, true);
        if (b != src) b.recycle();
        return up;
    }

    private void showWallpaper(Bitmap bm, Bitmap blurred) {
        if (bm == null || isDestroyed()) return;
        int fade = firstWallpaper ? 600 : 2000;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(fade).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
        if (blurred != null) {
            Drawable next = new android.graphics.drawable.BitmapDrawable(getResources(), blurred);
            if (frostCurrent == null) {
                frostImg.setImageDrawable(next);
            } else {
                android.graphics.drawable.TransitionDrawable t =
                        new android.graphics.drawable.TransitionDrawable(new Drawable[]{frostCurrent, next});
                t.setCrossFadeEnabled(true);
                frostImg.setImageDrawable(t);
                t.startTransition(fade);
            }
            frostCurrent = next;
        }
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the art's vivid pixels; used to tint the background behind icon-only (non-banner) apps. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        for (int i = 0; i < hero.getChildCount(); i++) {
            View header = hero.getChildAt(i);
            header.setAlpha(0f);
            header.setTranslationY(-dp(12));
            header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        }
        frost.setAlpha(0f);
        frost.animate().alpha(1f).setDuration(500).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- inputs: auto-switch

    private void openInput(String id) {
        Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!launch(i)) openPackage(inputPickerPkg);
    }

    /** Switch to an HDMI input when its device wakes up (e.g. a console or set-top box), while Home is showing. */
    private void watchInputs() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return;
        for (TvInputInfo t : tim.getTvInputList())
            if (t.isPassthroughInput()) inputStates.put(t.getId(), tim.getInputState(t.getId()));
        inputCallback = new TvInputManager.TvInputCallback() {
            @Override public void onInputStateChanged(String id, int state) {
                Integer before = inputStates.put(id, state);
                if (state == TvInputManager.INPUT_STATE_CONNECTED && before != null
                        && before != TvInputManager.INPUT_STATE_CONNECTED
                        && resumed && pref("auto_hdmi", true)) openInput(id);
            }
        };
        tim.registerCallback(inputCallback, handler);
    }

    // ---------------------------------------------------------------- recent, hidden, preferences

    private boolean pref(String key, boolean def) {
        return getPreferences(MODE_PRIVATE).getBoolean(key, def);
    }

    private Set<String> userHidden() {
        return new HashSet<>(getPreferences(MODE_PRIVATE).getStringSet("hidden_user", new HashSet<>()));
    }

    private void setHidden(String pkg, boolean hide) {
        Set<String> h = userHidden();
        if (hide) h.add(pkg); else h.remove(pkg);
        getPreferences(MODE_PRIVATE).edit().putStringSet("hidden_user", h).apply();
        lastFocusedId = View.NO_ID;
        buildApps();
    }

    private boolean hasUsageAccess() {
        android.app.AppOpsManager ops = (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
        return ops != null && ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
    }

    /** Packages opened in the last two weeks, most recent first (empty without usage access). */
    private List<String> recentPackages() {
        List<String> out = new ArrayList<>();
        if (!hasUsageAccess()) return out;
        android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        Map<String, android.app.usage.UsageStats> stats = usm.queryAndAggregateUsageStats(now - 14L * 24 * 3600 * 1000, now);
        List<android.app.usage.UsageStats> list = new ArrayList<>(stats.values());
        Collections.sort(list, (x, y) -> Long.compare(y.getLastTimeUsed(), x.getLastTimeUsed()));
        for (android.app.usage.UsageStats u : list)
            if (u.getLastTimeUsed() > 0 && u.getTotalTimeInForeground() > 5000) out.add(u.getPackageName());
        return out;
    }

    private TextView sectionLabel(String t) {
        TextView l = text(10, "sans-serif-medium", 0x99FFFFFF);
        l.setText(t);
        l.setLetterSpacing(0.22f);
        return l;
    }

    private long wallpaperInterval() {
        return getPreferences(MODE_PRIVATE).getInt("wall_min", 10) * 60_000L;
    }

    /** Apply the look settings: clock on/off and how dark the frosted shelf is. */
    private void applyLook() {
        clockBlock.setVisibility(pref("clock", true) ? View.VISIBLE : View.INVISIBLE);
        int[][] shelves = {
                {0x4D0C0C10, 0x730A0A0C, 0x99050506},   // light
                {0x8C0C0C10, 0xB30A0A0C, 0xD9050506},   // medium
                {0xBF0C0C10, 0xD90A0A0C, 0xF2050506}};  // dark
        int shelf = Math.max(0, Math.min(2, getPreferences(MODE_PRIVATE).getInt("shelf", 1)));
        shelfTint.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, shelves[shelf]));
    }

    // ---------------------------------------------------------------- memory

    private String memoryLabel() {
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return (mi.availMem >> 20) + " MB free";
    }

    /**
     * Greenify-style clean-up: force-stop the apps you used in the last day (never this launcher, Live TV,
     * the keyboard or Settings itself) through CleanerService, which presses Force stop in each app's
     * App info page. The first time, it opens Settings › Accessibility so you can switch the service on.
     */
    private void startCleanup() {
        if (settingsPopup != null) settingsPopup.dismiss();
        if (!CleanerService.isEnabled(this)) {
            launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            showHint("Turn on “Home: Free up memory”, then press Free up memory again", 6000);
            return;
        }
        List<String[]> apps = cleanupTargets();
        if (apps.isEmpty()) { showHint("Nothing to stop · " + memoryLabel(), 3000); return; }
        boolean started = CleanerService.run(apps, n -> handler.postDelayed(() ->
                showHint("Stopped " + n + " app" + (n == 1 ? "" : "s") + " · " + memoryLabel(), 4000), 900));
        if (!started) showHint("Clean-up is starting up, try again in a moment", 3000);
    }

    private List<String[]> cleanupTargets() {
        Set<String> keep = new HashSet<>();
        keep.add(getPackageName());
        keep.add(liveTvPkg);
        keep.add(inputPickerPkg);
        keep.add("com.android.tv.settings");
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null) keep.add(ime.split("/")[0]);
        PackageManager pm = getPackageManager();
        Map<String, String> launchable = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0))
            launchable.put(ri.activityInfo.packageName, String.valueOf(ri.activityInfo.applicationInfo.loadLabel(pm)));
        List<String[]> out = new ArrayList<>();
        if (!hasUsageAccess()) return out;
        android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        for (android.app.usage.UsageStats u : usm.queryAndAggregateUsageStats(now - 24L * 3600 * 1000, now).values()) {
            String p = u.getPackageName();
            if (u.getLastTimeUsed() > now - 24L * 3600 * 1000 && launchable.containsKey(p) && !keep.contains(p))
                out.add(new String[]{p, launchable.get(p)});
        }
        return out;
    }

    private void showHint(String text, long ms) {
        if (movingPkg != null) return;
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        hint.setAlpha(0f);
        hint.animate().alpha(1f).setDuration(200).start();
        handler.postDelayed(() -> {
            if (movingPkg == null) hint.animate().alpha(0f).setDuration(300).withEndAction(() -> hint.setVisibility(View.GONE)).start();
        }, ms);
    }

    // ---------------------------------------------------------------- start on input

    private void openStartTarget() {
        String t = getPreferences(MODE_PRIVATE).getString("start_on", "");
        if (t.isEmpty()) return;
        if (t.equals("live")) openPackage(liveTvPkg);
        else openInput(t);
    }

    /** Home, each HDMI/AV input, then Live TV. */
    private List<String[]> startTargets() {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"", "Home"});
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) for (TvInputInfo t : tim.getTvInputList())
            if (t.isPassthroughInput() && !t.isHidden(this)) out.add(new String[]{t.getId(), inputLabel(t)});
        Collections.sort(out.subList(1, out.size()), (x, y) -> x[1].compareToIgnoreCase(y[1]));
        if (isInstalled(liveTvPkg)) out.add(new String[]{"live", "Live TV"});
        return out;
    }

    private String startTargetLabel() {
        String cur = getPreferences(MODE_PRIVATE).getString("start_on", "");
        for (String[] t : startTargets()) if (t[0].equals(cur)) return t[1];
        return "Home";
    }

    private void cycleStartTarget() {
        String cur = getPreferences(MODE_PRIVATE).getString("start_on", "");
        List<String[]> ts = startTargets();
        int i = 0;
        while (i < ts.size() && !ts.get(i)[0].equals(cur)) i++;
        getPreferences(MODE_PRIVATE).edit().putString("start_on", ts.get((i + 1) % ts.size())[0]).apply();
    }

    // ---------------------------------------------------------------- app lock

    private void openApp(App app) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (isLocked(app.pkg) && hasPin()) verifyPin("Enter PIN to open " + app.label, () -> launch(i));
        else launch(i);
    }

    private boolean isLocked(String pkg) {
        return getPreferences(MODE_PRIVATE).getStringSet("locked", new HashSet<>()).contains(pkg);
    }

    private void setLocked(String pkg, boolean lock) {
        Set<String> l = new HashSet<>(getPreferences(MODE_PRIVATE).getStringSet("locked", new HashSet<>()));
        if (lock) l.add(pkg); else l.remove(pkg);
        getPreferences(MODE_PRIVATE).edit().putStringSet("locked", l).apply();
        lastFocusedId = View.NO_ID;
        buildApps();
        View c = cardByPkg.get(pkg);
        if (c != null) c.requestFocus();
    }

    private boolean hasPin() {
        return getPreferences(MODE_PRIVATE).getString("pin", null) != null;
    }

    private String hashPin(String pin) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((getPackageName() + ":" + pin).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void verifyPin(String title, Runnable ok) {
        String want = getPreferences(MODE_PRIVATE).getString("pin", null);
        if (want == null) { ok.run(); return; }
        showPinPad(title, pin -> {
            if (!hashPin(pin).equals(want)) return false;
            handler.post(ok);
            return true;
        });
    }

    /** Choose a PIN, then confirm it; {@code then} runs once it is saved. */
    private void choosePin(Runnable then) {
        showPinPad("Choose a 4-digit PIN", first -> {
            handler.post(() -> showPinPad("Enter it again to confirm", second -> {
                if (!second.equals(first)) return false;
                getPreferences(MODE_PRIVATE).edit().putString("pin", hashPin(first)).apply();
                if (then != null) handler.post(then);
                return true;
            }));
            return true;
        });
    }

    /**
     * A centred number pad (D-pad or the remote's number keys). {@code accept} gets each 4-digit entry;
     * returning false shakes the dots and clears them for another try.
     */
    private void showPinPad(String title, java.util.function.Predicate<String> accept) {
        if (menuPopup != null) menuPopup.dismiss();
        if (settingsPopup != null) settingsPopup.dismiss();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(28), dp(24), dp(28), dp(24));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(26));
        bg.setColor(0xF52C2C2E);
        bg.setStroke(Math.max(1, dp(0.5f)), 0x33FFFFFF);
        box.setBackground(bg);
        TextView t = text(15, "sans-serif-medium", Color.WHITE);
        t.setText(title);
        t.setGravity(Gravity.CENTER);
        box.addView(t, llp(-2, -2, 0, 0, 0, dp(12)));
        TextView dots = text(22, "sans-serif", Color.WHITE);
        dots.setLetterSpacing(0.4f);
        box.addView(dots, llp(-2, -2, 0, 0, 0, dp(16)));
        StringBuilder entry = new StringBuilder();
        // Fixed width: three 52dp keys with margins, plus padding (wrap_content measured too narrow).
        PopupWindow pw = new PopupWindow(box, dp(3 * 64 + 56), -2, true);
        Runnable refresh = () -> {
            StringBuilder d = new StringBuilder();
            for (int i = 0; i < 4; i++) d.append(i < entry.length() ? "●" : "○");
            dots.setText(d);
        };
        refresh.run();
        java.util.function.Consumer<String> press = k -> {
            if (k.equals("⌫")) { if (entry.length() > 0) entry.setLength(entry.length() - 1); refresh.run(); return; }
            if (k.equals("✕")) { pw.dismiss(); return; }
            if (entry.length() >= 4) return;
            entry.append(k);
            refresh.run();
            if (entry.length() < 4) return;
            String pin = entry.toString();
            if (accept.test(pin)) { pw.dismiss(); return; }
            entry.setLength(0);
            dots.animate().translationX(dp(10)).setDuration(60).withEndAction(() ->
                    dots.animate().translationX(-dp(10)).setDuration(60).withEndAction(() ->
                            dots.animate().translationX(0).setDuration(60).withEndAction(refresh).start()).start()).start();
        };
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"✕", "0", "⌫"}};
        View first = null;
        for (String[] rowKeys : keys) {
            LinearLayout r = new LinearLayout(this);
            for (String k : rowKeys) {
                TextView b = text(18, "sans-serif", Color.WHITE);
                b.setText(k);
                b.setGravity(Gravity.CENTER);
                b.setFocusable(true);
                b.setClickable(true);
                GradientDrawable kb = new GradientDrawable();
                kb.setShape(GradientDrawable.OVAL);
                kb.setColor(0x26FFFFFF);
                b.setBackground(kb);
                b.setOnFocusChangeListener((v, has) -> {
                    kb.setColor(has ? 0xFFF5F5F7 : 0x26FFFFFF);
                    b.setTextColor(has ? 0xFF1C1C1E : Color.WHITE);
                });
                b.setOnClickListener(v -> press.accept(k));
                // The remote's number keys and Delete work too.
                b.setOnKeyListener((v, code, e) -> {
                    if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) { press.accept(String.valueOf(code - KeyEvent.KEYCODE_0)); return true; }
                    if (code == KeyEvent.KEYCODE_DEL) { press.accept("⌫"); return true; }
                    return false;
                });
                r.addView(b, llp(dp(52), dp(52), dp(6), dp(6), dp(6), dp(6)));
                if (first == null) first = b;
            }
            box.addView(r);
        }
        pw.setElevation(dp(24));
        pw.setOnDismissListener(() -> {
            View back = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
            if (back != null) back.requestFocus();
        });
        pw.showAtLocation(scroll, Gravity.CENTER, 0, 0);
        first.requestFocus();
    }

    /** The banner's left/right edge colour, so a whole (uncropped) banner blends into its tile. */
    private int edgeColor(String pkg, Drawable d) {
        Integer cached = edgeCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF1C1C1E;
        try {
            Bitmap bm = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 32, 18);
            d.draw(new Canvas(bm));
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < 18; y++) for (int x : new int[]{0, 1, 30, 31}) {
                int px = bm.getPixel(x, y);
                if (Color.alpha(px) < 128) continue;
                r += Color.red(px); g += Color.green(px); b += Color.blue(px); n++;
            }
            bm.recycle();
            if (n > 0) color = Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
        } catch (RuntimeException ignored) { }
        edgeCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- Home Settings panel

    /** A tvOS-style side panel of on/off settings; OK changes the focused one. */
    private void showSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(28), dp(24), dp(24));
        panel.setBackgroundColor(0xFF1C1C1E);
        TextView title = text(22, "sans-serif-medium", Color.WHITE);
        title.setText("Home Settings");
        panel.addView(title, llp(-2, -2, dp(8), 0, 0, dp(18)));

        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        int[] mins = {5, 10, 30, 60, 0};
        String[] shelfNames = {"Light", "Medium", "Dark"};
        List<View> rows = new ArrayList<>();
        rows.add(settingRow("Clock & greeting", () -> pref("clock", true) ? "On" : "Off", () -> {
            prefs.edit().putBoolean("clock", !pref("clock", true)).apply();
            applyLook();
        }));
        rows.add(settingRow("Recently used row", () -> !pref("recent", true) ? "Off" : hasUsageAccess() ? "On" : "On · no access", () -> {
            prefs.edit().putBoolean("recent", !pref("recent", true)).apply();
            buildApps();
        }));
        rows.add(settingRow("Switch to HDMI when a device turns on", () -> pref("auto_hdmi", true) ? "On" : "Off", () ->
                prefs.edit().putBoolean("auto_hdmi", !pref("auto_hdmi", true)).apply()));
        rows.add(settingRow("Change wallpaper", () -> {
            int m = prefs.getInt("wall_min", 10);
            return m == 0 ? "Never" : m == 60 ? "Every hour" : "Every " + m + " min";
        }, () -> {
            int m = prefs.getInt("wall_min", 10), i = 0;
            while (i < mins.length && mins[i] != m) i++;
            int next = mins[(i + 1) % mins.length];
            prefs.edit().putInt("wall_min", next).apply();
            handler.removeCallbacks(rotateWallpaper);
            if (next > 0) scheduleWallpaper(next * 60_000L);
        }));
        rows.add(settingRow("Wallpapers", () -> {
            int m = Wallpapers.mode(this), mine = Wallpapers.mine().size();
            return m == Wallpapers.BUILT_IN ? "Built-in" : (m == Wallpapers.MINE ? "My photos" : "Built-in + mine") + " (" + mine + ")";
        }, () -> {
            prefs.edit().putInt("wall_source", (Wallpapers.mode(this) + 1) % 3).putInt("wallpaper", -1).apply();
            wallIndex = -1;
            nextWallpaper();
        }));
        rows.add(settingRow("Next wallpaper", () -> "", this::nextWallpaper));
        rows.add(settingRow("When the TV turns on", this::startTargetLabel, this::cycleStartTarget));
        rows.add(settingRow("App lock PIN", () -> hasPin() ? "Change" : "Not set", () -> {
            if (hasPin()) verifyPin("Enter current PIN", () -> choosePin(null));
            else choosePin(null);
        }));
        rows.add(settingRow("Preview screensaver", () -> "", () -> {
            Intent dream = new Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator");
            if (!launch(dream)) launch(new Intent(Settings.ACTION_DREAM_SETTINGS));
        }));
        rows.add(settingRow("Shelf", () -> shelfNames[Math.max(0, Math.min(2, prefs.getInt("shelf", 1)))], () -> {
            prefs.edit().putInt("shelf", (prefs.getInt("shelf", 1) + 1) % 3).apply();
            applyLook();
        }));
        rows.add(settingRow("Free up memory (force stop apps)",
                () -> CleanerService.isEnabled(this) ? memoryLabel() : "Set up", this::startCleanup));
        rows.add(settingRow("Hidden apps", () -> String.valueOf(userHidden().size()), this::showHiddenApps));
        rows.add(settingRow("Reset app order", () -> "", () -> {
            prefs.edit().remove("dock").remove("grid").apply();
            lastFocusedId = View.NO_ID;
            buildApps();
        }));
        for (View r : rows) panel.addView(r, llp(-1, dp(40), 0, 0, 0, dp(2)));

        TextView foot = text(10, "sans-serif", 0x80FFFFFF);
        foot.setText("Long-press OK on any app to move, dock, hide, lock or uninstall it.\nYour photos: put them in Pictures/Wallpapers (LocalSend or USB).");
        panel.addView(foot, llp(-1, -2, dp(8), dp(14), dp(8), 0));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setBackgroundColor(0xFF1C1C1E);
        sv.addView(panel);
        showPanel(sv, rows.get(0));
    }

    private void showHiddenApps() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(28), dp(24), dp(24));
        panel.setBackgroundColor(0xFF1C1C1E);
        TextView title = text(22, "sans-serif-medium", Color.WHITE);
        title.setText("Hidden apps");
        panel.addView(title, llp(-2, -2, dp(8), 0, 0, dp(18)));
        PackageManager pm = getPackageManager();
        View first = null;
        for (String pkg : userHidden()) {
            String label = pkg;
            try { label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); }
            catch (PackageManager.NameNotFoundException ignored) { }
            View r = settingRow(label, () -> "Show", () -> {
                setHidden(pkg, false);
                if (settingsPopup != null) settingsPopup.dismiss();
                showHiddenApps();
            });
            panel.addView(r, llp(-1, dp(44), 0, 0, 0, dp(4)));
            if (first == null) first = r;
        }
        if (first == null) {
            first = settingRow("No hidden apps", () -> "", () -> { if (settingsPopup != null) settingsPopup.dismiss(); });
            panel.addView(first, llp(-1, dp(44), 0, 0, 0, dp(4)));
        }
        showPanel(panel, first);
    }

    private void showPanel(View panel, View focus) {
        if (settingsPopup != null) settingsPopup.dismiss();
        View anchor = scroll;
        PopupWindow pw = new PopupWindow(panel, dp(360), -1, true);
        pw.setElevation(dp(24));
        pw.setAnimationStyle(android.R.style.Animation_Dialog);
        pw.setOnDismissListener(() -> {
            if (settingsPopup == pw) settingsPopup = null;
            View back = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
            if (back != null) back.requestFocus();
        });
        settingsPopup = pw;
        pw.showAtLocation(anchor, Gravity.END | Gravity.TOP, 0, 0);
        focus.requestFocus();
    }

    /** Label on the left, current value on the right; OK runs the action and refreshes the value. */
    private View settingRow(String label, java.util.function.Supplier<String> value, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        r.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        TextView l = text(13, "sans-serif", Color.WHITE);
        l.setText(label);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = text(12, "sans-serif", 0x99FFFFFF);
        v.setText(value.get());
        v.setSingleLine(true);
        r.addView(v, llp(-2, -2, dp(10), 0, 0, 0));
        r.setOnFocusChangeListener((x, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            l.setTextColor(has ? 0xFF111111 : Color.WHITE);
            v.setTextColor(has ? 0xFF555555 : 0x99FFFFFF);
        });
        r.setOnClickListener(x -> { action.run(); v.setText(value.get()); });
        r.setTag(v);
        return r;
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        // Visible through the LEANBACK_LAUNCHER <queries> intent, so config needn't match the manifest.
        if (getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null) return true;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (movingPkg != null && menuPopup == null) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                    || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down) moveBy(k);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK) {
                if (!down) endMove();
                return true;
            }
        }
        // Menu key: the app menu on a focused app, otherwise the Inputs dropdown.
        if (k == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && !pills.isEmpty()) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof App) showAppMenu(f, (App) f.getTag());
            else showInputs(inputsBtn);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 5:3 container, the tvOS app icon shape. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 3 / 5, MeasureSpec.EXACTLY));
        }
    }

    /** Draws its children with the top {@code fade} pixels faded in, so the frosted area has no hard edge. */
    static final class FadeTopFrame extends FrameLayout {
        private final int fade;
        private final android.graphics.Paint mask = new android.graphics.Paint();
        FadeTopFrame(Context c, int fade) {
            super(c);
            this.fade = fade;
            mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
            mask.setShader(new android.graphics.LinearGradient(0, 0, 0, fade, 0x00000000, 0xFF000000,
                    android.graphics.Shader.TileMode.CLAMP));
        }
        @Override protected void dispatchDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            // Below the fade band: draw normally. Inside it: draw into a small layer and mask it.
            c.save();
            c.clipRect(0, fade, w, h);
            super.dispatchDraw(c);
            c.restore();
            int sc = c.saveLayer(0, 0, w, fade, null);
            super.dispatchDraw(c);
            c.drawRect(0, 0, w, fade, mask);
            c.restoreToCount(sc);
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
