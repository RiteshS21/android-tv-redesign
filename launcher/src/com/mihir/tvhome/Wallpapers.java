package com.mihir.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Environment;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Wallpaper photos shared by the home screen and the Aerial screensaver: the built-in ones in
 * assets/wallpapers and/or the user's own in /sdcard/Pictures/Wallpapers (send them with LocalSend
 * or copy them from a USB drive). Entries are "asset:<path>" or "file:<path>".
 */
final class Wallpapers {
    static final File USER_DIR = new File(Environment.getExternalStorageDirectory(), "Pictures/Wallpapers");
    static final int BOTH = 0, BUILT_IN = 1, MINE = 2;
    /** MainActivity.getPreferences() stores under the activity's class name. */
    static final String PREFS = "MainActivity";

    private Wallpapers() { }

    static int mode(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("wall_source", BOTH);
    }

    static List<String> mine() {
        List<String> out = new ArrayList<>();
        File[] files = USER_DIR.listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            String n = f.getName().toLowerCase();
            if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")))
                out.add("file:" + f.getAbsolutePath());
        }
        return out;
    }

    static List<String> list(Context c) {
        List<String> out = new ArrayList<>();
        int mode = mode(c);
        if (mode != BUILT_IN) out.addAll(mine());
        if (mode != MINE || out.isEmpty()) {
            try {
                for (String a : c.getAssets().list("wallpapers")) out.add("asset:wallpapers/" + a);
            } catch (java.io.IOException ignored) { }
        }
        return out;
    }

    // Place and photographer for each built-in photo (from WALLPAPER-CREDITS.md), shown on the screensaver.
    private static final String[] PLACES = {
            "Lake Louise, Canada · Chensiyuan",
            "Austnesfjorden, Lofoten, Norway · Simo Räsänen",
            "Baleia Franca, Santa Catarina, Brazil · Claudio Seelig",
            "Lago do Cuniã, Rondônia, Brazil · RodrigoErse",
            "Spullersee, Vorarlberg, Austria",
            "Paczków, Poland · Jacek Halicki",
            "Stromboli, Italy · Carsten Steger",
            "Flatirons, Boulder, Colorado · Tyler Cipriani",
            "Manneporte, Étretat, France · Jörg Braukmann",
            "Kjeungskjær Lighthouse, Norway · Gordon Leggett",
            "Eilean Donan Castle, Scotland · Diliff",
            "Dzükou Valley, Nagaland, India · Samudra Bikash Hazarika",
    };

    /** "Place · Photographer" for a built-in photo (assets/wallpapers/NN.jpg), else null. */
    static String caption(String src) {
        if (!src.startsWith("asset:wallpapers/")) return null;
        try {
            int n = Integer.parseInt(src.substring(17, 19));
            return n >= 1 && n <= PLACES.length ? PLACES[n - 1] : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Decode for a screen of about maxW x maxH: downsampled, RGB_565, and upright for phone photos. */
    static Bitmap load(Context c, String src, int maxW, int maxH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            decode(c, src, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxW && o.outHeight / (sample * 2) >= maxH) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888; // 565 bands visibly in skies
            Bitmap bm = decode(c, src, o);
            if (bm == null || !src.startsWith("file:")) return bm;
            int orientation = new ExifInterface(src.substring(5))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int deg = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (deg == 0) return bm;
            Matrix m = new Matrix();
            m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (r != bm) bm.recycle();
            return r;
        } catch (java.io.IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap decode(Context c, String src, BitmapFactory.Options o) throws java.io.IOException {
        if (src.startsWith("asset:")) {
            try (InputStream in = c.getAssets().open(src.substring(6))) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        }
        return BitmapFactory.decodeFile(src.substring(5), o);
    }
}
