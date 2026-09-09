package lt.vsid.tinystatus;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bendras duomenu sluoksnis: spausdintuvu sarasas, skaitymas, atmintine.
 *
 * Juo naudojasi trys: ekranas (MainActivity), fono sargas (TsSargas) ir
 * komplikaciju teikejas (TsKompl). Teikejas atmintine TIK skaito - jo
 * atsakymas vyksta pagrindineje gijoje, ir tinklo ten buti negali.
 *
 * SPAUSDINTUVAI. Du rezimai, ne misinys (viena tiesa apie tai, kiek ju yra):
 *   - auto (numatyta): VIENAS spausdintuvas, randamas pagal vardus HOSTS.
 *     Spausdintuvas skelbia tik mDNS A irasa "tinymaker" - jokio serviso,
 *     tad "autodiscovery" ir yra tie vardai per marsrutizatoriaus DNS.
 *     Antras spausdintuvas turetu ta pati varda, todel keli - tik pagal IP.
 *   - rankinis: sarasas pr.0..pr.n-1 su IP (ir neprivalomu vardu).
 * Indeksas n visur zemiau yra spausdintuvo numeris; auto rezime jis 0.
 *
 * VARDAS. ".local" is programeles NEVEIKIA: Android ta zona laiko mDNS ir per
 * iprasta DNS jos neklausia (UnknownHostException), o marsrutizatorius atsako
 * i "tinymaker.lan" ir "tinymaker". Laikom kandidatu sarasa ir isimenam ta,
 * kuris suveike - taip isvengiam ir kietai irasyto IP, kuris pasikeistu per
 * DHCP.
 */
public final class TsSaltinis {

    private static final String TAG = "TINYSTATUS";
    public static final String PREFS = "tinystatus";
    static final int CONNECT_MS = 2500;
    static final int READ_MS = 3500;
    private static final int WIFI_MS = 4000;
    public static final int MAX_PR = 4;

    /** Bandom is eiles; pirmas atsiliepes isimenamas. */
    static final String[] HOSTS = {"tinymaker.lan", "tinymaker", "tinymaker.local"};

    private TsSaltinis() {
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ spausdintuvai

    public static boolean auto(Context c) {
        return prefs(c).getBoolean("auto", true);
    }

    /** Kiek spausdintuvu sarase. Auto rezime visada 1. */
    public static int skaicius(Context c) {
        return auto(c) ? 1 : Math.min(MAX_PR, prefs(c).getInt("pr.n", 0));
    }

    /** Vardas ekranui: irasytas, kitaip IP, auto rezime - "TinyMaker". */
    public static String vardas(Context c, int n) {
        if (auto(c)) {
            return "TinyMaker";
        }
        String v = prefs(c).getString("pr." + n + ".name", "");
        return v.isEmpty() ? prefs(c).getString("pr." + n + ".ip", "?") : v;
    }

    public static String ip(Context c, int n) {
        return prefs(c).getString("pr." + n + ".ip", "");
    }

    /** Prideda arba pakeicia spausdintuva; n >= skaicius reiskia nauja. */
    public static void irasykSpausdintuva(Context c, int n, String ip, String vardas) {
        SharedPreferences p = prefs(c);
        int kiek = p.getInt("pr.n", 0);
        if (n >= kiek) {
            n = kiek;
            kiek = Math.min(MAX_PR, kiek + 1);
        }
        p.edit().putInt("pr.n", kiek)
                .putString("pr." + n + ".ip", ip)
                .putString("pr." + n + ".name", vardas == null ? "" : vardas)
                .remove("pr." + n + ".host")
                .apply();
        isvalykAtmintine(c, n);
    }

    /** Isima spausdintuva ir sustumia likusius, kad numeriai liktu be tarpu. */
    public static void isimkSpausdintuva(Context c, int n) {
        SharedPreferences p = prefs(c);
        int kiek = p.getInt("pr.n", 0);
        if (n < 0 || n >= kiek) {
            return;
        }
        SharedPreferences.Editor e = p.edit();
        for (int i = n; i < kiek - 1; i++) {
            e.putString("pr." + i + ".ip", p.getString("pr." + (i + 1) + ".ip", ""));
            e.putString("pr." + i + ".name", p.getString("pr." + (i + 1) + ".name", ""));
            e.putString("c." + i + ".raw", p.getString("c." + (i + 1) + ".raw", null));
            e.putLong("c." + i + ".ts", p.getLong("c." + (i + 1) + ".ts", 0));
        }
        int pask = kiek - 1;
        e.remove("pr." + pask + ".ip").remove("pr." + pask + ".name")
                .remove("c." + pask + ".raw").remove("c." + pask + ".ts")
                .putInt("pr.n", pask);
        int sel = p.getInt("pr.sel", 0);
        if (sel >= pask) {
            e.putInt("pr.sel", Math.max(0, pask - 1));
        }
        e.apply();
        // Pranesimu busena senu numeriu pagrindu butu klaidinga - nuvalom visa.
        TsPranesimas.nuvalyk(c);
    }

    /** Kuris spausdintuvas rodomas, kai niekas nespausdina. */
    public static int pasirinktas(Context c) {
        int kiek = skaicius(c);
        int sel = prefs(c).getInt("pr.sel", 0);
        return (kiek == 0) ? 0 : Math.min(sel, kiek - 1);
    }

    /**
     * Kuri rodyti ciferblate ir atidarius programele: spausdinanti (jei keli -
     * su sviežiausia atmintine), kitaip pasirinkta. Tai vienintele taisykle,
     * kuriai nereikia atskiro nustatymo.
     */
    public static int rodomas(Context c) {
        int kiek = skaicius(c);
        int geriausias = -1;
        long naujausias = 0;
        for (int n = 0; n < kiek; n++) {
            TsBusena b = atmintineje(c, n);
            if (b != null && b.busy && b.at > naujausias) {
                naujausias = b.at;
                geriausias = n;
            }
        }
        return geriausias >= 0 ? geriausias : pasirinktas(c);
    }

    /** Vardai, kuriuos bandyti: zinomas veikiantis pirmas, po jo likusieji. */
    static String[] hosts(Context c, int n) {
        if (!auto(c)) {
            String ip = ip(c, n);
            return ip.isEmpty() ? new String[0] : new String[]{ip};
        }
        String good = prefs(c).getString("pr.0.host", null);
        if (good == null) {
            return HOSTS;
        }
        String[] order = new String[HOSTS.length];
        order[0] = good;
        int i = 1;
        for (String h : HOSTS) {
            if (!h.equals(good)) {
                order[i++] = h;
            }
        }
        return order;
    }

    // ------------------------------------------------------------ skaitymas

    /** Uzklausa per nurodyta tinkla; null - per numatytaji. */
    static String get(Network net, String url) throws Exception {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection)
                (net != null ? net.openConnection(u) : u.openConnection());
        try {
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestProperty("Connection", "close");
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("HTTP " + code);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int k;
            while ((k = in.read(buf)) > 0) {
                out.write(buf, 0, k);
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /**
     * Nuskaito spausdintuva n per tinkla net. Pavykus iraso i atmintine ir
     * grazina busena; nepavykus - null (atmintine lieka, kokia buvo).
     */
    public static TsBusena skaityk(Context c, int n, Network net) {
        for (String host : hosts(c, n)) {
            try {
                String body = get(net, "http://" + host + "/api/status");
                long now = System.currentTimeMillis();
                TsBusena b = TsBusena.parse(body, now);
                if (b == null) {
                    Log.w(TAG, host + ": atsakymas ne /api/status");
                    continue;
                }
                SharedPreferences p = prefs(c);
                SharedPreferences.Editor e = p.edit()
                        .putString("c." + n + ".raw", body)
                        .putLong("c." + n + ".ts", now);
                if (auto(c) && !host.equals(p.getString("pr.0.host", null))) {
                    Log.i(TAG, "vardas veikia: " + host);
                    e.putString("pr.0.host", host);
                }
                // Spausdintuvo IP is jo paties: auto rezime tai leidzia zmogui
                // ji pamatyti (ir irasyti ranka, jei DNS nustotu veikti).
                if (!b.ip.isEmpty()) {
                    e.putString("pr." + n + ".seen", b.ip);
                }
                e.apply();
                return b;
            } catch (Exception ex) {
                Log.w(TAG, host + " nepavyko: " + ex);
            }
        }
        return null;
    }

    /** Paskutine matyta busena arba null. */
    public static TsBusena atmintineje(Context c, int n) {
        SharedPreferences p = prefs(c);
        return TsBusena.parse(p.getString("c." + n + ".raw", null), p.getLong("c." + n + ".ts", 0));
    }

    public static long atmintinesLaikas(Context c, int n) {
        return prefs(c).getLong("c." + n + ".ts", 0);
    }

    static void isvalykAtmintine(Context c, int n) {
        prefs(c).edit().remove("c." + n + ".raw").remove("c." + n + ".ts").apply();
    }

    /**
     * Dervos slenksciai is /api/config: warn (numatyta 5 ml) ir stop (2 ml).
     * Atnaujinami ne dazniau nei kas para - jie keiciasi retai, o kiekvienas
     * skaitymas yra dar viena uzklausa ESP32, kuris ir taip uzimtas.
     */
    public static void konfig(Context c, int n, Network net) {
        SharedPreferences p = prefs(c);
        long amzius = System.currentTimeMillis() - p.getLong("pr." + n + ".cfgTs", 0);
        if (amzius < 24L * 3600 * 1000) {
            return;
        }
        for (String host : hosts(c, n)) {
            try {
                JSONObject j = new JSONObject(get(net, "http://" + host + "/api/config"));
                p.edit().putFloat("pr." + n + ".warn", (float) j.optDouble("lowResinWarnMl", 5))
                        .putFloat("pr." + n + ".stop", (float) j.optDouble("lowResinMl", 2))
                        .putLong("pr." + n + ".cfgTs", System.currentTimeMillis())
                        .apply();
                Log.i(TAG, "slenksciai: warn " + j.optDouble("lowResinWarnMl", 5)
                        + " stop " + j.optDouble("lowResinMl", 2));
                return;
            } catch (Exception ex) {
                Log.w(TAG, host + " config nepavyko: " + ex);
            }
        }
    }

    public static float warnMl(Context c, int n) {
        return prefs(c).getFloat("pr." + n + ".warn", 5f);
    }

    public static float stopMl(Context c, int n) {
        return prefs(c).getFloat("pr." + n + ".stop", 2f);
    }

    // ------------------------------------------------------------ tinklas

    /**
     * Wi-Fi tinklas, jei jis JAU aktyvus - be jokio prasymo ji ijungti. Cia
     * skirtumas nuo wifi(): ana priverčia radija pakilti, si tik pasiziuri.
     */
    public static Network jauWifi(Context c) {
        ConnectivityManager cm =
                (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return null;
        }
        try {
            Network n = cm.getActiveNetwork();
            if (n == null) {
                return null;
            }
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) ? n : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Numatytasis tinklas, jei jis NE Wi-Fi (telefonas per Bluetooth). Per ji
     * spausdintuvas pasiekiamas tik tada, kai telefonas pats yra namu tinkle -
     * ar taip yra, pasako zondas (zr. zonduok) ir vėliava "bt".
     */
    public static Network numatytasis(Context c) {
        ConnectivityManager cm =
                (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return null;
        }
        try {
            return cm.getActiveNetwork();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Ar zondas parode, kad spausdintuva pasiekiam per telefona. */
    public static boolean perTelefona(Context c) {
        return prefs(c).getBoolean("bt", false);
    }

    /**
     * Zondas: ar spausdintuvas pasiekiamas per numatytaji tinkla BE Wi-Fi
     * prasymo. Rezultatas rasomas i "bt" ir zurnala. Kviesti is gijos.
     *
     * Kodel tai svarbu: Wear OS, kai telefonas salia, Wi-Fi radija laiko
     * isjungta, ir kiekvienas jo kelimas kainuoja 3-6 s radijo darbo (energijos
     * auditas 2026-09-09). Jei spausdintuva pasiekiam per telefona, fone
     * radijo kelti nereikia isvis.
     */
    public static boolean zonduok(Context c) {
        ConnectivityManager cm =
                (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = numatytasis(c);
        String transportai = "nera";
        if (cm != null && n != null) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null) {
                transportai = (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "WIFI " : "")
                        + (nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ? "BLUETOOTH " : "")
                        + (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "CELLULAR " : "");
            }
        }
        boolean wifi = transportai.contains("WIFI");
        boolean pavyko = false;
        int kiek = skaicius(c);
        for (int i = 0; i < kiek && !pavyko; i++) {
            for (String host : hosts(c, i)) {
                try {
                    String body = get(n, "http://" + host + "/api/status");
                    pavyko = TsBusena.parse(body, 0) != null;
                    Log.i(TAG, "zondas: " + host + " per [" + transportai.trim() + "] -> "
                            + (pavyko ? "atsake" : "ne status"));
                    if (pavyko) {
                        break;
                    }
                } catch (Exception e) {
                    Log.i(TAG, "zondas: " + host + " per [" + transportai.trim() + "] -> " + e);
                }
            }
        }
        // Jei numatytasis jau Wi-Fi, zondas nieko neirodo apie telefona -
        // vėliavos neliečiam.
        if (!wifi) {
            prefs(c).edit().putBoolean("bt", pavyko).putLong("btTs", System.currentTimeMillis()).apply();
            Log.i(TAG, "zondas: per telefona " + (pavyko ? "VEIKIA" : "neveikia"));
        } else {
            Log.i(TAG, "zondas: numatytasis jau Wi-Fi, apie telefona nieko nesakom");
        }
        return pavyko;
    }

    /**
     * Wi-Fi tinklas su priverstiniu radijo kelimu. Laukiam trumpai ir su riba.
     * Klausytojas nuimamas is karto - tinklas lieka gyvas tik tol, kol kas
     * nors ji laiko; ilgesniam laikymui zr. TsSargas.
     */
    public static Network wifi(Context c) {
        ConnectivityManager cm =
                (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return null;
        }
        final Network[] rastas = new Network[1];
        final CountDownLatch laukiam = new CountDownLatch(1);
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                rastas[0] = n;
                laukiam.countDown();
            }

            @Override
            public void onUnavailable() {
                laukiam.countDown();
            }
        };
        try {
            cm.requestNetwork(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb, WIFI_MS);
            laukiam.await(WIFI_MS + 500L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.w(TAG, "Wi-Fi uzsakyti nepavyko: " + e);
        } finally {
            try {
                cm.unregisterNetworkCallback(cb);
            } catch (RuntimeException ignored) {
                // jau nebegalioja
            }
        }
        return rastas[0];
    }
}
