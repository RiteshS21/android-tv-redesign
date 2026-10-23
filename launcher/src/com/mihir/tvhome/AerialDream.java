package com.mihir.tvhome;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Apple TV "Aerial"-style screensaver: the wallpaper photos drift slowly (pan and zoom), crossfading
 * every 40 seconds, with a large thin clock in the corner, the photo's place bottom-right and what's
 * playing above it. Set as the TV's screensaver at install.
 */
public class AerialDream extends DreamService {
    static final long PHOTO_MS = 40_000, FADE_MS = 3_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private ImageView front, back;
    private TextView clock, date, place, npTitle, npSub;
    private LinearLayout nowPlaying;
    private ImageView npArt;
    private NowPlaying media;
    private List<String> photos;
    private int index;

    private final Runnable next = this::nextPhoto;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean is24 = android.text.format.DateFormat.is24HourFormat(AerialDream.this);
            Date now = new Date();
            clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now));
            date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
            handler.postDelayed(this, 20_000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = photoView();
        front = photoView();
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));
        root.addView(front, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BL_TR,
                new int[]{0x99000000, 0x00000000, 0x00000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        // Home's Dim and Dark themes dim the screensaver photos too (same prefs file as MainActivity.getPreferences).
        if (getSharedPreferences("MainActivity", MODE_PRIVATE).getInt("theme", 0) % 3 != 0) {
            View dim = new View(this);
            dim.setBackgroundColor(0x80000000);
            root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        }

        LinearLayout corner = new LinearLayout(this);
        corner.setOrientation(LinearLayout.VERTICAL);
        clock = text(64, "sans-serif-thin", Color.WHITE);
        date = text(11, "sans-serif-medium", 0xB3FFFFFF);
        date.setLetterSpacing(0.22f);
        corner.addView(clock);
        corner.addView(date);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(56), 0, 0, dp(48));
        root.addView(corner, lp);

        // Bottom-right: what's playing, then the photo's place, like Apple's aerial captions.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        nowPlaying = new LinearLayout(this);
        nowPlaying.setGravity(Gravity.CENTER_VERTICAL);
        npArt = new ImageView(this);
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setCornerRadius(dp(8));
        artBg.setColor(0x33FFFFFF);
        npArt.setBackground(artBg);
        npArt.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        npArt.setClipToOutline(true);
        nowPlaying.addView(npArt, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        npTitle = text(14, "sans-serif-medium", Color.WHITE);
        npSub = text(11, "sans-serif", 0xB3FFFFFF);
        for (TextView t : new TextView[]{npTitle, npSub}) {
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setMaxWidth(dp(320));
            texts.addView(t);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.setMargins(dp(12), 0, 0, 0);
        nowPlaying.addView(texts, tlp);
        nowPlaying.setVisibility(View.GONE);
        right.addView(nowPlaying);
        place = text(11, "sans-serif-medium", 0xB3FFFFFF);
        place.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.setMargins(0, dp(14), 0, 0);
        right.addView(place, plp);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        rlp.setMargins(0, 0, dp(56), dp(52));
        root.addView(right, rlp);
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        photos = Wallpapers.list(this);
        index = photos.isEmpty() ? 0 : random.nextInt(photos.size());
        tick.run();
        nextPhoto();
        media = new NowPlaying(this, this::updateMedia);
        media.start();
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacksAndMessages(null);
        if (media != null) media.stop();
        super.onDreamingStopped();
    }

    private void nextPhoto() {
        handler.removeCallbacks(next);
        if (photos == null || photos.isEmpty()) return;
        String src = photos.get(index++ % photos.size());
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        new Thread(() -> {
            Bitmap bm = Wallpapers.load(this, src, w, h);
            handler.post(() -> show(bm, Wallpapers.caption(src)));
        }).start();
        handler.postDelayed(next, PHOTO_MS);
    }

    /** Crossfade the new photo in, then let it drift: a slow zoom with a gentle sideways pan. */
    private void show(Bitmap bm, String caption) {
        if (bm == null || front == null) return;
        place.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(() -> {
            place.setText(caption == null ? "" : caption);
            place.animate().alpha(1f).setDuration(FADE_MS / 2).start();
        }).start();
        ImageView old = front;
        front = back;
        back = old;
        FrameLayout root = (FrameLayout) front.getParent();
        root.removeView(front);
        root.addView(front, 1, new FrameLayout.LayoutParams(-1, -1));
        front.setImageBitmap(bm);
        front.setAlpha(0f);
        boolean zoomIn = random.nextBoolean();
        float pan = dp(28) * (random.nextBoolean() ? 1 : -1);
        front.setScaleX(zoomIn ? 1.04f : 1.16f);
        front.setScaleY(zoomIn ? 1.04f : 1.16f);
        front.setTranslationX(-pan);
        front.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> back.setImageDrawable(null)).start();
        front.animate().scaleX(zoomIn ? 1.16f : 1.04f).scaleY(zoomIn ? 1.16f : 1.04f).translationX(pan)
                .setDuration(PHOTO_MS + FADE_MS).setInterpolator(new LinearInterpolator()).start();
    }

    private void updateMedia() {
        android.media.session.MediaController c = media.current();
        nowPlaying.setVisibility(c == null ? View.GONE : View.VISIBLE);
        if (c == null) return;
        npTitle.setText(NowPlaying.title(c));
        npSub.setText(NowPlaying.subtitle(this, c));
        Bitmap art = NowPlaying.art(c);
        npArt.setImageBitmap(art);
        npArt.setVisibility(art != null ? View.VISIBLE : View.GONE);
    }

    private ImageView photoView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
