package lt.vsid.tinystatus;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Atsinaujinimas is GitHub laidos (V prasymas 2026-09-11).
 *
 * DU ATSKIRI ZINGSNIAI (V): "Check for updates" tik paziuri ir pasako, ar yra
 * naujesne; siusti ir diegti - tik paspaudus "Update". Tikrinimas nereiskia
 * diegimo.
 *
 * ISMATUOTA Galaxy Watch 8 zondu (2026-09-11, versijos 288 -> 292):
 *  - Wear OS turi "Install unknown apps" leidimo langa - pirma karta jis
 *    atsidaro, naudotojas ijungia jungikli;
 *  - kol programele NE pati savo idiegeja (idiegta per adb), Android klausia
 *    "Do you want to update this app?";
 *  - ir TADA klausia, jei manifeste nera UPDATE_PACKAGES_WITHOUT_USER_ACTION
 *    (289 -> 290 klause, nors idiegeja jau buvo TinyStatus);
 *  - su tuo leidimu ir savo idiegeja - diegia be klausimo (291 -> 292).
 *
 * APSAUGA yra pasirasymo raktas, ne GitHub: Android ant esamos programeles
 * idiegia tik tuo paciu raktu pasirasyta APK, o mes tai patikrinam dar pries
 * diegima. Adresas irasytas kode - tik slibbinas/TinyStatus.
 */
public final class TsAtnaujink {

    private static final String TAG = "TINYSTATUS";
    private static final String API =
            "https://api.github.com/repos/slibbinas/TinyStatus/releases/latest";
    private static final int LAUKIAM_MS = 15000;

    public interface Eiga {
        void zingsnis(String tekstas);
    }

    /** Rasta naujesne laida. */
    static final class Laida {
        final String vardas;
        final long kodas;
        final String url;
        final long dydis;

        Laida(String vardas, long kodas, String url, long dydis) {
            this.vardas = vardas;
            this.kodas = kodas;
            this.url = url;
            this.dydis = dydis;
        }
    }

    private TsAtnaujink() {
    }

    static long versija(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static String versijosVardas(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /**
     * Po diegimo (jau naujas procesas): ar versija pakilo. Diegimas uzmusa sena
     * procesa, tad sekmes zinute gali ir neateiti - irodymas yra pati versija.
     */
    static String poDiegimo(Context c) {
        SharedPreferences p = TsSaltinis.prefs(c);
        long buvo = p.getLong("upd.from", 0);
        if (buvo == 0) {
            return null;
        }
        long dabar = versija(c);
        Log.i(TAG, "atnaujinimas: po diegimo " + buvo + " -> " + dabar
                + ", busena " + p.getString("upd.last", ""));
        p.edit().remove("upd.from").apply();
        return dabar > buvo
                ? c.getString(R.string.upd_done, versijosVardas(c))
                : c.getString(R.string.upd_not_done);
    }

    /**
     * Tik PAZIURETI, ar GitHub'e yra naujesne laida. Nieko nesiuncia ir
     * nediegia. Paleisti GIJOJE. Grazina laida, jei ji naujesne, kitaip null.
     */
    static Laida tikrink(Context c, Eiga e) {
        try {
            JSONObject r = new JSONObject(gauk(API));
            String tag = r.optString("tag_name");
            JSONArray priedai = r.optJSONArray("assets");
            String url = null;
            long dydis = -1;
            for (int i = 0; priedai != null && i < priedai.length(); i++) {
                JSONObject x = priedai.getJSONObject(i);
                if (x.optString("name").endsWith(".apk")) {
                    url = x.optString("browser_download_url");
                    dydis = x.optLong("size", -1);
                    break;
                }
            }
            long kodas = kodasIsZymes(tag);
            long mano = versija(c);
            Log.i(TAG, "atnaujinimas: GitHub " + tag + " (" + kodas + "), idiegta " + mano
                    + (url == null ? ", APK nera" : ""));
            if (url == null || kodas <= mano) {
                e.zingsnis(c.getString(R.string.upd_uptodate, versijosVardas(c)));
                return null;
            }
            // "yra nauja" pasako pats mygtukas ("Update 0.1.xxx"); cia - kas
            // idiegta dabar, kad butu su kuo palyginti.
            e.zingsnis(c.getString(R.string.upd_version, versijosVardas(c)));
            return new Laida(tag, kodas, url, dydis);
        } catch (Exception ex) {
            Log.w(TAG, "atnaujinimas: patikrinti nepavyko: " + ex);
            e.zingsnis(c.getString(R.string.upd_no_check));
            return null;
        }
    }

    /** Laidos zyme "0.1.293" - versionCode yra paskutinis skaicius (build.sh). */
    private static long kodasIsZymes(String tag) {
        try {
            String[] d = tag.split("\\.");
            return Long.parseLong(d[d.length - 1]);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Naudotojas paspaude "Update". Paleisti GIJOJE. Grazina true, jei
     * diegimas perduotas sistemai; false - galima bandyti dar karta.
     */
    static boolean diek(Activity a, Laida l, Eiga e) {
        try {
            return diekVidus(a, l, e);
        } catch (Exception ex) {
            Log.w(TAG, "atnaujinimas: " + ex, ex);
            e.zingsnis(a.getString(R.string.upd_failed));
            return false;
        }
    }

    private static boolean diekVidus(Activity a, Laida l, Eiga e) throws Exception {
        PackageManager pm = a.getPackageManager();

        // Leidimas - pirma, kad nesiustume veltui.
        if (!pm.canRequestPackageInstalls()) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())));
                Log.i(TAG, "atnaujinimas: leidimo nera, atidarytas leidimo langas");
                e.zingsnis(a.getString(R.string.upd_allow));
            } catch (Exception ex) {
                Log.w(TAG, "atnaujinimas: leidimo lango nera: " + ex);
                e.zingsnis(a.getString(R.string.upd_failed));
            }
            return false;
        }

        e.zingsnis(a.getString(R.string.upd_downloading));
        File apk = new File(a.getCacheDir(), "update.apk");
        long gauta = atsisiusk(l.url, apk);
        Log.i(TAG, "atnaujinimas: atsiusta " + gauta + " B, laukta " + l.dydis + ", " + l.url);
        if (l.dydis > 0 && gauta != l.dydis) {
            e.zingsnis(a.getString(R.string.upd_failed));
            return false;
        }

        // Ar tai TinyStatus, naujesne ir tuo paciu raktu. Android svetimo rakto
        // vis tiek neidiegtu, bet geriau tai zinoti pries diegima.
        PackageInfo naujas = pm.getPackageArchiveInfo(apk.getPath(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (naujas == null || !a.getPackageName().equals(naujas.packageName)) {
            Log.w(TAG, "atnaujinimas: ne TinyStatus APK");
            e.zingsnis(a.getString(R.string.upd_failed));
            return false;
        }
        PackageInfo esamas = pm.getPackageInfo(a.getPackageName(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        long mano = versija(a);
        boolean raktas = tasPatsRaktas(esamas, naujas);
        Log.i(TAG, "atnaujinimas: APK versija " + naujas.getLongVersionCode() + ", idiegta "
                + mano + ", raktas sutampa " + raktas);
        if (!raktas || naujas.getLongVersionCode() <= mano) {
            e.zingsnis(a.getString(R.string.upd_failed));
            return false;
        }

        PackageInstaller pi = pm.getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(a.getPackageName());
        sp.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) {
            // Veikia tik su UPDATE_PACKAGES_WITHOUT_USER_ACTION manifeste ir kai
            // TinyStatus pati yra savo idiegeja - kitaip Android paklaus.
            sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            InputStream in = new FileInputStream(apk);
            OutputStream out = s.openWrite("tinystatus.apk", 0, apk.length());
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            s.fsync(out);
            out.close();
            in.close();
            // commit(), ne apply(): po diegimo procesas mirsta, o sita reiksme
            // turi ji isgyventi.
            TsSaltinis.prefs(a).edit().putLong("upd.from", mano)
                    .putString("upd.last", "committed").commit();
            PendingIntent p = PendingIntent.getBroadcast(a, id, new Intent(a, Busena.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            e.zingsnis(a.getString(R.string.upd_installing, naujas.versionName));
            Log.i(TAG, "atnaujinimas: sesija " + id + " commit");
            s.commit(p.getIntentSender());
        } finally {
            s.close();
        }
        if (!apk.delete()) {
            Log.w(TAG, "atnaujinimas: update.apk istrinti nepavyko");
        }
        return true;
    }

    private static boolean tasPatsRaktas(PackageInfo a, PackageInfo b) {
        if (a.signingInfo == null || b.signingInfo == null) {
            return false;
        }
        Signature[] x = a.signingInfo.getApkContentsSigners();
        Signature[] y = b.signingInfo.getApkContentsSigners();
        return x != null && y != null && x.length > 0 && Arrays.equals(x, y);
    }

    private static HttpURLConnection jungtis(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(LAUKIAM_MS);
        h.setReadTimeout(LAUKIAM_MS);
        // GitHub API be User-Agent atsako 403.
        h.setRequestProperty("User-Agent", "TinyStatus");
        return h;
    }

    private static String gauk(String url) throws Exception {
        HttpURLConnection h = jungtis(url);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (h.getResponseCode() != 200) {
                throw new Exception("GitHub HTTP " + h.getResponseCode());
            }
            InputStream in = h.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
            in.close();
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            h.disconnect();
        }
    }

    /** Laidos priedas nukreipia i kita GitHub serveri - https i https seka pats. */
    private static long atsisiusk(String url, File f) throws Exception {
        HttpURLConnection h = jungtis(url);
        try {
            if (h.getResponseCode() != 200) {
                throw new Exception("Download HTTP " + h.getResponseCode());
            }
            InputStream in = h.getInputStream();
            OutputStream out = new FileOutputStream(f);
            byte[] buf = new byte[65536];
            long viso = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                viso += n;
            }
            out.close();
            in.close();
            return viso;
        } finally {
            h.disconnect();
        }
    }

    /**
     * Diegimo busena is sistemos. PENDING_USER_ACTION reiskia, kad Android
     * nori patvirtinimo - tada rodom jo langa (programele tuo metu ekrane,
     * tad fono veiklos draudimas netrukdo).
     */
    public static class Busena extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Log.i(TAG, "atnaujinimas: busena " + st + " " + msg);
            TsSaltinis.prefs(c).edit().putString("upd.last", st + " " + msg).commit();
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @SuppressWarnings("deprecation")
                Intent patvirtink = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (patvirtink == null) {
                    return;
                }
                patvirtink.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(patvirtink);
                    Log.i(TAG, "atnaujinimas: rodomas patvirtinimo langas");
                } catch (Exception ex) {
                    Log.w(TAG, "atnaujinimas: patvirtinimo lango atidaryti nepavyko: " + ex);
                }
            }
        }
    }
}
