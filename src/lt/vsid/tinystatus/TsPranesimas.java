package lt.vsid.tinystatus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Map;

/**
 * Pranesimai apie spausdinima - tie patys, kuriuos firmware siuncia i Telegram:
 * pabaiga / atsaukimas, derva senka, derva baigesi (spausdintuvas sustojo),
 * pauze.
 *
 * Spausdintuvas ivykiu NESKELBIA - viska isvedam is dvieju gretimu apklausu
 * skirtumo, todel cia gyvena BUSENOS MASINA (s.N.*), ir ji yra vienintelis
 * pranesimu rasytojas. Ja maitina ir ekranas (tylus=true), ir fono sargas
 * (tylus=false): ekranas busena veda, bet nauju pranesimu neskelbia - zmogus
 * ir taip ziuri; sargas skelbia.
 *
 * Pranesam TIK persijungimo metu (Vallox pamoka): tas pats "derva senka" kas
 * 2 min per valanda virstu triuksmu, kuri isjungtum.
 *
 * Jungiklis (al.*) isjungtas NESTABDO busenos masinos - tik skelbima. Kitaip
 * ijungus jungikli po valandos gautum pasenusi ivyki.
 */
public final class TsPranesimas {

    private static final String TAG = "TINYSTATUS";
    static final String KANALAS = "ts_alerts";
    static final String KANALAS_SARGAS = "ts_watch";
    /** Sargo nuolatinis pranesimas. */
    static final int ID_SARGAS = 5299;
    private static final int BAZE = 5300;
    static final int K_END = 1;
    static final int K_RESIN = 2;
    static final int K_PAUSE = 3;
    static final int K_WATCH = 4;

    public static final int PABAIGA_BAIGTA = 1;
    public static final int PABAIGA_ATSAUKTA = 2;
    public static final int PABAIGA_NUTRUKO = 3;
    /**
     * Pabaiga, kurios NEMATEM: tarp paskutinio "spausdina" ir "nebespausdina"
     * praejo per daug laiko, kad galetume pasakyti, kaip ji baigesi.
     *
     * 2026-09-10 laikrodis dvi valandas nepasieke spausdintuvo, o paskui
     * pranese "baigta ties 218/408" - nors 218 buvo tik paskutinis MATYTAS
     * sluoksnis, o spaudinys ejo iki galo. Pasenusiu skaiciu nerodom.
     */
    public static final int PABAIGA_NEMATYTA = 4;
    /** Kiek tarpo tarp dvieju apklausu dar leidzia spresti, kaip baigesi. */
    private static final long NEMATYTA_NUO_MS = 10L * 60_000;

    /** Kiek laiko po pabaigos dar rodom DONE ekrane ir ciferblate. */
    public static final long DONE_MS = 12L * 3600 * 1000;

    private TsPranesimas() {
    }

    static int id(int n, int k) {
        return BAZE + n * 10 + k;
    }

    private static String r(int n) {
        return "s." + n + ".";
    }

    /**
     * Viena apklausa - vienas zingsnis busenos masinoje.
     *
     * @param intervalasMs kas kiek apklausiama: pagal ji sprendziam, ar
     *                     spausdinimas baigesi laiku (baigta), ar per anksti
     *                     (nutruko), kai atsaukimo paties nematem.
     */
    public static synchronized void tikrink(Context c, int n, TsBusena b, boolean tylus,
                                            long intervalasMs) {
        if (b == null) {
            return;                            // rysio nera - nieko nezinom, nieko nekeiciam
        }
        SharedPreferences p = TsSaltinis.prefs(c);
        String k = r(n);
        boolean buvoBusy = p.getBoolean(k + "busy", false);
        SharedPreferences.Editor e = p.edit();
        String vardas = TsSaltinis.skaicius(c) > 1 ? TsSaltinis.vardas(c, n) + ": " : "";

        if (b.busy) {
            boolean naujas = !buvoBusy
                    || b.cur < p.getInt(k + "cur", 0)
                    || !b.model.equals(p.getString(k + "model", ""));
            if (naujas) {
                Log.i(TAG, "spausdinimas prasidejo: " + b.model + " " + b.total + " sl.");
                e.putBoolean(k + "cancel", false).putInt(k + "lvl", 0)
                        .putBoolean(k + "pause", false).putLong(k + "end", 0)
                        .putInt(k + "endKind", 0);
                nuimk(c, n, K_END);
                nuimk(c, n, K_RESIN);
                nuimk(c, n, K_PAUSE);
            }
            e.putBoolean(k + "busy", true).putInt(k + "cur", b.cur).putInt(k + "total", b.total)
                    .putString(k + "model", b.model).putLong(k + "run", b.runSecs)
                    .putFloat(k + "used", (float) b.resinUsedMl).putLong(k + "seenAt", b.at);
            if (b.expectedEnd() > 0) {
                e.putLong(k + "expEnd", b.expectedEnd());
            }
            if (b.isCancel()) {
                e.putBoolean(k + "cancel", true);
            }

            // Derva: 2 - baigesi (stop slenkstis arba spausdintuvas jau
            // sustojo), 1 - senka (warn slenkstis), 0 - gerai. Pranesam tik
            // KYLANT; ipylus (lygis nukrito) - pranesima nuimam, o nauja ciklas
            // prasidės pats, kaip ir firmware "re-arm on refill".
            int lvl = 0;
            if (b.vatLow || b.stateCode == 10) {
                lvl = 2;
            } else if (b.vatRemainingMl >= 0 && b.vatRemainingMl <= TsSaltinis.warnMl(c, n)) {
                lvl = 1;
            }
            int buvoLvl = naujas ? 0 : p.getInt(k + "lvl", 0);
            if (lvl != buvoLvl) {
                Log.i(TAG, "derva: " + buvoLvl + " -> " + lvl + " (" + b.vatRemainingMl + " ml)");
                e.putInt(k + "lvl", lvl);
                if (lvl == 0) {
                    nuimk(c, n, K_RESIN);
                } else if (lvl > buvoLvl && !tylus) {
                    boolean leista = (lvl == 2) ? p.getBoolean("al.stop", true)
                            : p.getBoolean("al.warn", true);
                    if (leista) {
                        String ml = String.format("%.1f ml", Math.max(0, b.vatRemainingMl));
                        pranesk(c, id(n, K_RESIN),
                                vardas + (lvl == 2
                                        ? (b.stateCode == 10 ? "Resin out - printer paused" : "Resin out")
                                        : "Resin running low"),
                                ml + " left at layer " + b.cur + "/" + b.total
                                        + (lvl == 2 ? " - refill the VAT" : ""));
                    }
                }
            }

            // Pauze ranka (ne del dervos - ta jau pasakyta aukščiau).
            boolean pauze = b.paused && b.stateCode != 10;
            boolean buvoPauze = !naujas && p.getBoolean(k + "pause", false);
            if (pauze != buvoPauze) {
                e.putBoolean(k + "pause", pauze);
                if (!pauze) {
                    nuimk(c, n, K_PAUSE);
                } else if (!tylus && p.getBoolean("al.pause", false)) {
                    pranesk(c, id(n, K_PAUSE), vardas + "Print paused",
                            "at layer " + b.cur + "/" + b.total);
                }
            }
            e.apply();
            return;
        }

        if (!buvoBusy) {
            e.apply();
            return;                            // ramybe buvo ir liko
        }

        // Ka tik buvo busy, dabar ne - PABAIGA. Kokia - pagal tai, ka matem.
        int cur = p.getInt(k + "cur", 0);
        int total = p.getInt(k + "total", 0);
        long expEnd = p.getLong(k + "expEnd", 0);
        int rusis;
        long matyta = p.getLong(k + "seenAt", 0);
        long tarpas = (matyta == 0) ? Long.MAX_VALUE : b.at - matyta;
        if (tarpas > Math.max(3 * intervalasMs, NEMATYTA_NUO_MS)) {
            rusis = PABAIGA_NEMATYTA;
        } else if (p.getBoolean(k + "cancel", false)) {
            rusis = PABAIGA_ATSAUKTA;
        } else if (total > 0 && cur >= total - 1) {
            rusis = PABAIGA_BAIGTA;            // paskutinis sluoksnis matytas
        } else if (expEnd > 0 && b.at >= expEnd - (long) (1.5 * intervalasMs)) {
            rusis = PABAIGA_BAIGTA;            // baigesi tada, kai ir turejo
        } else {
            rusis = PABAIGA_NUTRUKO;           // per anksti, o atsaukimo nematem
        }
        String modelis = p.getString(k + "model", "");
        long run = p.getLong(k + "run", 0);
        float used = p.getFloat(k + "used", 0);
        Log.i(TAG, "spausdinimas baigesi: rusis " + rusis + " " + cur + "/" + total);
        e.putBoolean(k + "busy", false).putLong(k + "end", b.at).putInt(k + "endKind", rusis)
                .putInt(k + "endCur", cur).putBoolean(k + "pause", false).putInt(k + "lvl", 0)
                .apply();
        nuimk(c, n, K_PAUSE);
        nuimk(c, n, K_RESIN);
        if (!tylus && p.getBoolean("al.end", true)) {
            String antraste;
            String tekstas;
            if (rusis == PABAIGA_BAIGTA) {
                antraste = "Print finished";
                tekstas = trukme(run) + String.format(", ~%.1f ml used", used);
            } else if (rusis == PABAIGA_NEMATYTA) {
                antraste = "Print ended";
                tekstas = "while the watch couldn't reach the printer";
            } else if (rusis == PABAIGA_ATSAUKTA) {
                antraste = "Print canceled";
                tekstas = "at layer " + cur + "/" + total + " after " + trukme(run);
            } else {
                antraste = "Print stopped";
                tekstas = "at layer " + cur + "/" + total + " - not finished";
            }
            pranesk(c, id(n, K_END), vardas + antraste,
                    (modelis.isEmpty() ? "" : modelis + " - ") + tekstas);
        }
    }

    /** "2h 14m" arba "45m". */
    static String trukme(long secs) {
        long m = secs / 60;
        return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m";
    }

    // ------------------------------------------------------------ pabaigos atmintis

    /** Kada baigesi paskutinis spausdinimas (0 - nezinia arba jau spausdina). */
    public static long pabaigosLaikas(Context c, int n) {
        return TsSaltinis.prefs(c).getLong(r(n) + "end", 0);
    }

    /** Ar dar rodom DONE: baigesi per 12 h ir dabar nespausdina. */
    public static boolean rodomDone(Context c, int n, TsBusena b) {
        long end = pabaigosLaikas(c, n);
        return (b == null || !b.busy) && end > 0
                && System.currentTimeMillis() - end < DONE_MS
                // Jau matyta (V, 2026-09-13): pabaiga rodoma, kol zmogus ja
                // pamato ekrane ir uzdaro programele. Prirista prie konkretaus
                // `end`, tad kitas spaudinys vel parodys DONE be jokio nunulinimo.
                && TsSaltinis.prefs(c).getLong(r(n) + "endAck", 0) != end;
    }

    /** Pabaiga `end` jau matyta ekrane - daugiau DONE nerodom nei ten, nei komplikacijoje. */
    public static void patvirtinkPabaiga(Context c, int n, long end) {
        TsSaltinis.prefs(c).edit().putLong(r(n) + "endAck", end).apply();
        android.util.Log.i("TINYSTATUS", "pabaiga " + n + " pazymeta matyta (endAck " + end + ")");
    }

    /** Ar kuris nors spausdintuvas paskutini karta matytas spausdinantis. */
    public static boolean kasNorsSpausdino(Context c) {
        SharedPreferences p = TsSaltinis.prefs(c);
        for (int n = 0; n < TsSaltinis.skaicius(c); n++) {
            if (p.getBoolean(r(n) + "busy", false)) {
                return true;
            }
        }
        return false;
    }

    public static int pabaigosRusis(Context c, int n) {
        return TsSaltinis.prefs(c).getInt(r(n) + "endKind", 0);
    }

    public static String pabaigosModelis(Context c, int n) {
        return TsSaltinis.prefs(c).getString(r(n) + "model", "");
    }

    public static int pabaigosSluoksniai(Context c, int n) {
        return TsSaltinis.prefs(c).getInt(r(n) + "total", 0);
    }

    /** Nuvalo visa busena (isėmus spausdintuva numeriai nebeatitinka). */
    public static void nuvalyk(Context c) {
        SharedPreferences p = TsSaltinis.prefs(c);
        SharedPreferences.Editor e = p.edit();
        for (Map.Entry<String, ?> x : p.getAll().entrySet()) {
            if (x.getKey().startsWith("s.")) {
                e.remove(x.getKey());
            }
        }
        e.apply();
        NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            for (int n = 0; n < TsSaltinis.MAX_PR; n++) {
                for (int k = K_END; k <= K_WATCH; k++) {
                    nm.cancel(id(n, k));
                }
            }
        }
    }

    // ------------------------------------------------------------ skelbimas

    static void nuimk(Context c, int n, int k) {
        NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(id(n, k));
        }
    }

    static void pranesk(Context c, int id, String antraste, String tekstas) {
        NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Log.i(TAG, "pranesimas " + id + ": " + antraste + " / " + tekstas);
        nm.createNotificationChannel(new NotificationChannel(KANALAS, "TinyMaker alerts",
                NotificationManager.IMPORTANCE_HIGH));
        nm.notify(id, new Notification.Builder(c, KANALAS)
                .setSmallIcon(R.drawable.ic_ts_print)
                .setContentTitle(antraste)
                .setContentText(tekstas)
                .setContentIntent(atidaryk(c))
                .setAutoCancel(true)
                .build());
    }

    static PendingIntent atidaryk(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Pavyzdinis pranesimas GIF'ui ir patikrai. BUSENOS NELIECIA ir atmintines
     * nerašo - isgalvoti duomenys karta jau buvo patekę i ciferblata (Vallox
     * greblys).
     */
    static void demo(Context c, String rusis) {
        if ("resin".equals(rusis)) {
            pranesk(c, id(0, K_RESIN), "Resin running low", "4.8 ml left at layer 412/826");
        } else if ("stop".equals(rusis)) {
            pranesk(c, id(0, K_RESIN), "Resin out - printer paused",
                    "1.9 ml left at layer 640/826 - refill the VAT");
        } else if ("cancel".equals(rusis)) {
            pranesk(c, id(0, K_END), "Print canceled", "Benchy - at layer 120/826 after 24m");
        } else {
            pranesk(c, id(0, K_END), "Print finished", "Benchy - 2h 14m, ~9.3 ml used");
        }
    }
}
