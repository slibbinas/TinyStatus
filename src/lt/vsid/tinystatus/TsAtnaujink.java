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
 * ZONDAS: ar programele gali atsinaujinti pati - is GitHub laidos.
 *
 * Tai dar NE funkcija, o klausimas su atsakymu (V prasymas 2026-09-11). Kol
 * nezinom, ka Wear OS leidzia, rasyti grazu atnaujinimo langa neverta - lygiai
 * taip pat pradetas ir BambuStatus.
 *
 * KA TIKRINAM, is eiles (kiekvienas zingsnis - i ekrana ir zurnala):
 *   1. ar pasiekiam GitHub API ir atsisiunciam APK;
 *   2. ar tai TinyStatus, naujesnis ir pasirasytas TUO PACIU raktu;
 *   3. ar laikrodis turi "Install unknown apps" leidima ir jo langa;
 *   4. ar PackageInstaller idiegia - su patvirtinimo langu ar be jo.
 *
 * KODEL BE PATVIRTINIMO GALI PAVYKTI TIK NUO ANTRO KARTO: Android 12+
 * USER_ACTION_NOT_REQUIRED leidzia tik tada, kai programele pati yra jos
 * idiegejas. Per adb idiegta TinyStatus tokia nera - pirmas atnaujinimas
 * paklaus, o po jo idiegejas jau bus ji pati.
 *
 * ZONDO ISIMTIS: jei `files/update.apk` yra (ikeltas per adb), jis naudojamas
 * vietoj GitHub. Taip diegimas patikrinamas su versija, kurios viesai nera -
 * nieko neskelbiant. Po diegimo failas istrinamas.
 */
public final class TsAtnaujink {

    private static final String TAG = "TINYSTATUS";
    private static final String API =
            "https://api.github.com/repos/slibbinas/TinyStatus/releases/latest";
    private static final int LAUKIAM_MS = 15000;

    public interface Eiga {
        void zingsnis(String tekstas);
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
     * Po diegimo (naujas procesas): ar versija pakilo. Diegimas uzmusa sena
     * procesa, tad sekmes zinute gali ir neateiti - irodymas yra pati versija.
     */
    static String poDiegimo(Context c) {
        SharedPreferences p = TsSaltinis.prefs(c);
        long buvo = p.getLong("upd.from", 0);
        if (buvo == 0) {
            return null;
        }
        long dabar = versija(c);
        String paskutinis = p.getString("upd.last", "");
        p.edit().remove("upd.from").apply();
        Log.i(TAG, "atnaujinimas: po diegimo " + buvo + " -> " + dabar + ", busena " + paskutinis);
        return dabar > buvo
                ? "Updated " + buvo + " -> " + dabar
                : "Not updated (" + paskutinis + ")";
    }

    /** Paleisti GIJOJE: tinklas ir failai blokuoja. */
    static void zonduok(Activity a, Eiga e) {
        try {
            vykdyk(a, e);
        } catch (Exception ex) {
            Log.w(TAG, "atnaujinimas: " + ex, ex);
            e.zingsnis("Error\n" + ex.getClass().getSimpleName() + "\n" + ex.getMessage());
        }
    }

    private static void vykdyk(Activity a, Eiga e) throws Exception {
        PackageManager pm = a.getPackageManager();
        long mano = versija(a);

        // 1. Kur APK.
        File failas = new File(a.getExternalFilesDir(null), "update.apk");
        File apk;
        String kilme;
        if (failas.isFile()) {
            apk = failas;
            kilme = "failas";
            e.zingsnis("1/4 Local update.apk");
        } else {
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
            if (url == null) {
                e.zingsnis("1/4 GitHub " + tag + "\nno APK in the release");
                return;
            }
            e.zingsnis("1/4 GitHub " + tag + "\ndownloading…");
            apk = new File(a.getCacheDir(), "update.apk");
            long gauta = atsisiusk(url, apk);
            Log.i(TAG, "atnaujinimas: atsiusta " + gauta + " B, laukta " + dydis + ", " + url);
            if (dydis > 0 && gauta != dydis) {
                e.zingsnis("2/4 Download broken\n" + gauta + " of " + dydis + " B");
                return;
            }
            kilme = "GitHub " + tag;
        }

        // 2. Ar tai TinyStatus, ar naujesnis, ar tas pats raktas. Android
        // svetimo rakto vis tiek neidiegtu, bet pasakyti verta pries diegima.
        PackageInfo naujas = pm.getPackageArchiveInfo(apk.getPath(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (naujas == null || !a.getPackageName().equals(naujas.packageName)) {
            e.zingsnis("2/4 Not a TinyStatus APK");
            return;
        }
        PackageInfo esamas = pm.getPackageInfo(a.getPackageName(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        boolean raktas = tasPatsRaktas(esamas, naujas);
        long jo = naujas.getLongVersionCode();
        Log.i(TAG, "atnaujinimas: " + kilme + " versija " + jo + ", idiegta " + mano
                + ", raktas sutampa " + raktas);
        if (!raktas) {
            e.zingsnis("2/4 Different signing key\nnot installing");
            return;
        }
        if (jo <= mano) {
            e.zingsnis("2/4 " + naujas.versionName + " is not newer\nDownload OK, up to date");
            return;
        }
        e.zingsnis("2/4 " + naujas.versionName + " is newer, same key");

        // 3. Leidimas diegti.
        boolean gali = pm.canRequestPackageInstalls();
        Log.i(TAG, "atnaujinimas: canRequestPackageInstalls=" + gali);
        if (!gali) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())));
                Log.i(TAG, "atnaujinimas: leidimo langas atidarytas");
                e.zingsnis("3/4 Allow installing,\nthen tap again");
            } catch (Exception ex) {
                Log.w(TAG, "atnaujinimas: leidimo lango nera: " + ex);
                e.zingsnis("3/4 No permission screen\non this watch");
            }
            return;
        }

        // 4. Diegimas.
        PackageInstaller pi = pm.getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(a.getPackageName());
        sp.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) {
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
            e.zingsnis("4/4 Installing " + naujas.versionName + "…");
            Log.i(TAG, "atnaujinimas: sesija " + id + " commit");
            s.commit(p.getIntentSender());
        } finally {
            s.close();
        }
        if (apk == failas && !failas.delete()) {
            Log.w(TAG, "atnaujinimas: update.apk istrinti nepavyko");
        }
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
