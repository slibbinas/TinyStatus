package lt.vsid.tinystatus;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Fono sargas: kol spausdina, kas pasirinkta intervala nuskaito spausdintuva,
 * atnaujina ciferblata ir skelbia pranesimus.
 *
 * KODEL FOREGROUND SERVISAS + TIKSLUS ALARMAS, o ne JobScheduler:
 * JobScheduler periodinis minimumas 15 min, o V intervalai - 30 s / 2 / 5 /
 * 10 min. Vien alarmas be serviso irgi netinka: fone esanciai programelei
 * Android 12+ neleidzia is alarmo pradeti tinklo darbo. Foreground servisas
 * duoda teise i tinkla ir islaiko procesa, AlarmManager duoda laika, o wake
 * lock laikomas TIK skaitymo metu (iki 20 s).
 *
 * KADA VEIKIA: tik kol spausdina (V sprendimas 2026-09-09). Startuoja, kai
 * programele uzdaroma matant vykstanti spausdinima; sustoja pats 20 min po
 * pabaigos, be rysio - po 12 h, jei paskutinis matytas buvo spausdinimas,
 * kitaip po 30 min, arba baterijai nukritus zemiau 15 %.
 *
 * TINKLAS: pirma per telefona (jei zondas parode, kad veikia), tada Wi-Fi,
 * kuris JAU yra, ir tik tada requestNetwork. Pavykusi uzklausa laikoma visa
 * sesija - brangus yra radijo KELIMAS, ne laikymas (energijos auditas); bet
 * nepavykusi kaskart kuriama IS NAUJO (zr. laikykWifi).
 *
 * DOZE: ISMATUOTA 2026-09-11, laikrodis ant stalo salia telefono, Wireless
 * debugging isjungtas, "Every 2 min": gilus Doze (DeviceIdleController.deep)
 * tiku NERETINO - alarmai kas 2 min, exactAllowReason=policy_permission
 * (USE_EXACT_ALARM). Wi-Fi radija pakele pati musu uzklausa (tinklas sukurtas
 * 2 s po REGISTER), "Print finished" atejo per viena intervala nuo pabaigos.
 * Senas spejimas "~1 per 9 min" buvo neteisingas.
 */
public class TsSargas extends Service {

    private static final String TAG = "TINYSTATUS";
    static final String A_START = "lt.vsid.tinystatus.START";
    static final String A_TICK = "lt.vsid.tinystatus.TICK";
    static final String A_STOP = "lt.vsid.tinystatus.STOP";

    /** Intervalai minutemis pagal nustatymo indeksa: Off, Const, 2, 5, 10. */
    static final int[] INTERVALAI = {0, 1, 2, 5, 10};
    private static final long CONST_MS = 30_000L;
    private static final long IDLE_STOP_MS = 20L * 60_000;
    /** Kiek be rysio laukiam, kai paskutine matyta busena NEBUVO spausdinimas. */
    private static final long OFF_STOP_MS = 30L * 60_000;
    /**
     * Kiek be rysio laukiam, kai paskutine matyta busena BUVO spausdinimas.
     *
     * 2026-09-10 sargas po 30 min tylos issijunge, o spaudinys dar tesesi
     * valanda: derva baigesi, V papilde, pratese - ir laikrodis viso to
     * nematė. Kol zinom, kad spausdina, pasiduoti anksti yra blogiausia, ka
     * galima padaryti: butent tada ir reikia pranesimo. 12 h - ilgesnis uz bet
     * kuri spaudini, bet vis dar riba, kad sargas nedegintu baterijos amzinai.
     */
    private static final long OFF_STOP_BUSY_MS = 12L * 3600_000;
    /** Po tiek tylos spausdinant - vienas pranesimas, kad laikrodis nebemato. */
    private static final long AKLAS_PRANESTI_MS = 10L * 60_000;
    /** Aklumo metu tikrinam ne dazniau nei kas tiek - kiekvienas bandymas zadina radija. */
    private static final long AKLAS_INTERVALAS_MS = 5L * 60_000;
    /**
     * Kiek laukiam, kol Wear pakels Wi-Fi radija.
     *
     * Energijos auditas matavo 3-6 s. Duodam dasnesni laika: jei nepakele per
     * 10 s, kitas tikas pabandys is naujo, o ne sis lauks ilgiau.
     */
    private static final long WIFI_LAUKIAM_MS = 10_000L;
    private static final int BATERIJA_MIN = 15;

    private static volatile boolean veikia;
    private PowerManager.WakeLock uzraktas;
    private ConnectivityManager.NetworkCallback wifiCb;
    private volatile Network laikomasWifi;
    private long pirmasBeRysio;
    /** Ar jau pranesta, kad laikrodis nebemato spausdintuvo (sio aklumo metu). */
    private boolean aklumasPranestas;

    public static boolean veikia() {
        return veikia;
    }

    /** Pasirinktas intervalas: 0 - isjungta, 1 - "pastoviai", kitaip minutes. */
    public static int intervalas(Context c) {
        int i = TsSaltinis.prefs(c).getInt("bg.int", 0);
        return INTERVALAI[(i >= 0 && i < INTERVALAI.length) ? i : 0];
    }

    /** Paleidzia sarga, jei nustatymas leidzia. Kviesti is ekrano arba is boot. */
    public static void paleisk(Context c, String kodel) {
        if (intervalas(c) == 0) {
            return;
        }
        Log.i(TAG, "sargas: paleidziam (" + kodel + ")");
        TsSaltinis.prefs(c).edit().putBoolean("bg.on", true).apply();
        Intent i = new Intent(c, TsSargas.class).setAction(A_START);
        try {
            c.startForegroundService(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "sargas: paleisti nepavyko: " + e);
        }
    }

    public static void sustabdyk(Context c, String kodel) {
        Log.i(TAG, "sargas: stabdom (" + kodel + ")");
        TsSaltinis.prefs(c).edit().putBoolean("bg.on", false).apply();
        try {
            c.stopService(new Intent(c, TsSargas.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "sargas: stabdyti nepavyko: " + e);
        }
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String a = (i == null) ? A_START : i.getAction();
        if (A_STOP.equals(a)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!priekyje()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        veikia = true;
        if (A_START.equals(a)) {
            pirmasBeRysio = 0;
        }
        tikas();
        return START_STICKY;
    }

    /**
     * startForeground su tipu connectedDevice (isorinis irenginys per tinkla).
     * dataSync netinka: Android 15 ji riboja 6 h per para, o spausdinimas
     * trunka ilgiau. Jei sistema connectedDevice atmestu - specialUse.
     */
    private boolean priekyje() {
        Notification n = sargoPranesimas();
        try {
            startForeground(TsPranesimas.ID_SARGAS, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "sargas: connectedDevice atmestas: " + e);
        }
        try {
            startForeground(TsPranesimas.ID_SARGAS, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "sargas: specialUse atmestas: " + e);
            return false;
        }
    }

    private Notification sargoPranesimas() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(TsPranesimas.KANALAS_SARGAS,
                    "TinyMaker watching", NotificationManager.IMPORTANCE_LOW));
        }
        int min = intervalas(this);
        return new Notification.Builder(this, TsPranesimas.KANALAS_SARGAS)
                .setSmallIcon(R.drawable.ic_ts_print)
                .setContentTitle("Watching TinyMaker")
                .setContentText(min == 1 ? "every 30 s while printing" : "every " + min + " min")
                .setContentIntent(TsPranesimas.atidaryk(this))
                .setOngoing(true)
                .build();
    }

    // ------------------------------------------------------------ tikas

    private void tikas() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (uzraktas == null && pm != null) {
            uzraktas = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tinystatus:tick");
            uzraktas.setReferenceCounted(false);
        }
        if (uzraktas != null) {
            uzraktas.acquire(20_000L);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    dirbk();
                } catch (RuntimeException e) {
                    Log.w(TAG, "sargas: tikas nukrito: " + e);
                    planuok(intervaloMs(false));
                } finally {
                    if (uzraktas != null && uzraktas.isHeld()) {
                        uzraktas.release();
                    }
                }
            }
        }).start();
    }

    private long intervaloMs(boolean spausdina) {
        int min = intervalas(this);
        if (min == 1) {
            return spausdina ? CONST_MS : 2 * 60_000L;
        }
        return min * 60_000L;
    }

    private void dirbk() {
        if (intervalas(this) == 0) {
            baik("intervalas Off");
            return;
        }
        if (baterija() < BATERIJA_MIN) {
            baik("baterija " + baterija() + " %");
            return;
        }
        SharedPreferences p = TsSaltinis.prefs(this);
        int kiek = TsSaltinis.skaicius(this);
        long now = System.currentTimeMillis();
        boolean kasNorsSpausdina = false;
        boolean kasNorsAtsake = false;
        long naujausiaPabaiga = 0;
        long intervalas = intervaloMs(true);
        Network net = null;

        for (int n = 0; n < kiek; n++) {
            TsBusena b;
            long amzius = now - TsSaltinis.atmintinesLaikas(this, n);
            if (amzius < intervalas - 5_000L) {
                // Ekranas ka tik skaite - antro skaitymo nereikia, tik
                // busenos masinos zingsnis is to, kas jau atmintineje.
                b = TsSaltinis.atmintineje(this, n);
                Log.i(TAG, "sargas: " + n + " sviezia (" + amzius / 1000 + " s), praleidziam tinkla");
            } else {
                if (net == null) {
                    net = tinklas();
                }
                b = TsSaltinis.skaityk(this, n, net);
                if (b == null && net != null && TsSaltinis.perTelefona(this)
                        && TsSaltinis.jauWifi(this) == null) {
                    // Per telefona nebepasiekiam (isejo is namu) - Wi-Fi.
                    p.edit().putBoolean("bt", false).apply();
                    Log.i(TAG, "sargas: per telefona nebeatsako, keliam Wi-Fi");
                    net = tinklas();
                    b = TsSaltinis.skaityk(this, n, net);
                }
                if (b != null) {
                    TsSaltinis.konfig(this, n, net);
                }
            }
            if (b != null) {
                kasNorsAtsake = true;
                TsPranesimas.tikrink(this, n, b, false, intervalas);
                if (b.busy) {
                    kasNorsSpausdina = true;
                }
            }
            naujausiaPabaiga = Math.max(naujausiaPabaiga, TsPranesimas.pabaigosLaikas(this, n));
        }
        TsKompl.atnaujink(this);

        if (kasNorsAtsake) {
            if (pirmasBeRysio != 0) {
                Log.i(TAG, "sargas: rysys grizo po " + (now - pirmasBeRysio) / 60_000 + " min");
            }
            pirmasBeRysio = 0;
            if (aklumasPranestas) {
                // Vel matom - "nebematau" pranesimas nebegalioja.
                TsPranesimas.nuimk(this, 0, TsPranesimas.K_WATCH);
                aklumasPranestas = false;
            }
        } else if (pirmasBeRysio == 0) {
            pirmasBeRysio = now;
        } else {
            long tyla = now - pirmasBeRysio;
            boolean spausdino = TsPranesimas.kasNorsSpausdino(this);
            // Viena nepavykusi apklausa nera isvada: spausdintuvas siusdamas
            // Telegram zinute gali tyleti iki ~13 s (printerio sesija, 2026-09-10).
            if (spausdino && tyla > AKLAS_PRANESTI_MS && !aklumasPranestas) {
                // Pasakom VIENA karta: be sito zmogus mano, kad laikrodis
                // saugo, o jis aklas - taip ir nutiko 2026-09-10.
                TsPranesimas.pranesk(this, TsPranesimas.id(0, TsPranesimas.K_WATCH),
                        "Can't reach the printer",
                        "Still trying. Keep the watch near Wi-Fi to get alerts.");
                aklumasPranestas = true;
            }
            long riba = spausdino ? OFF_STOP_BUSY_MS : OFF_STOP_MS;
            if (tyla > riba) {
                TsPranesimas.pranesk(this, TsPranesimas.id(0, TsPranesimas.K_WATCH),
                        "Printer unreachable", "stopped watching after "
                                + (riba >= 3600_000 ? (riba / 3600_000) + " h" : (riba / 60_000) + " min"));
                baik((riba / 60_000) + " min be rysio");
                return;
            }
        }
        if (!kasNorsSpausdina && kasNorsAtsake) {
            long nuoPabaigos = now - naujausiaPabaiga;
            if (naujausiaPabaiga == 0 || nuoPabaigos > IDLE_STOP_MS) {
                baik("nespausdina " + (naujausiaPabaiga == 0 ? "" : nuoPabaigos / 60_000 + " min"));
                return;
            }
        }
        // Akli tikai retesni: kiekvienas is ju zadina Wi-Fi radija, o
        // spausdintuvas per kelias minutes niekur nedings.
        long kitas = intervaloMs(kasNorsSpausdina || TsPranesimas.kasNorsSpausdino(this));
        if (pirmasBeRysio != 0) {
            kitas = Math.max(kitas, AKLAS_INTERVALAS_MS);
        }
        planuok(kitas);
    }

    private int baterija() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm == null) {
            return 100;
        }
        int lygis = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return lygis <= 0 ? 100 : lygis;
    }

    private void baik(String kodel) {
        Log.i(TAG, "sargas: baigiam - " + kodel);
        TsSaltinis.prefs(this).edit().putBoolean("bg.on", false).apply();
        stopSelf();
    }

    /** Kitas tikas per ms - tikslus alarmas, kuris pazadina ir Doze rezime. */
    private void planuok(long ms) {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = tikoIntentas(this);
        long kada = SystemClock.elapsedRealtime() + ms;
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, kada, pi);
            } else {
                Log.w(TAG, "sargas: tikslus alarmai neleisti, planuojam apytiksliai");
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, kada, pi);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "sargas: alarmo planuoti nepavyko: " + e);
        }
    }

    private static PendingIntent tikoIntentas(Context c) {
        Intent i = new Intent(c, TsSargas.class).setAction(A_TICK);
        // getForegroundService: jei procesas tuo metu negyvas, alarmas ji
        // prikelia kaip foreground servisa (tikslus alarmas - viena is
        // leistinu priezasciu pradeti FGS is fono).
        return PendingIntent.getForegroundService(c, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ------------------------------------------------------------ tinklas

    /**
     * Tinklas skaitymui: telefonas (jei zondas parode) -> Wi-Fi, kuris jau yra
     * -> laikomas Wi-Fi (vienas prasymas visai sesijai).
     */
    private Network tinklas() {
        Network wifi = TsSaltinis.jauWifi(this);
        if (wifi != null) {
            return wifi;
        }
        if (TsSaltinis.perTelefona(this)) {
            Network n = TsSaltinis.numatytasis(this);
            if (n != null) {
                return n;
            }
        }
        if (laikomasWifi != null) {
            return laikomasWifi;
        }
        return laikykWifi();
    }

    /**
     * Wi-Fi tinklas sargui. KASKART, kol jo neturim, siunciam NAUJA uzklausa.
     *
     * Taip buvo ne visada, ir tai buvo 2026-09-10 aklumo priezastis. Anksciau
     * uzklausa buvo registruojama VIENA karta visai sesijai. Kai laikrodis
     * gulejo ant stalo salia telefono, Wear isjunge Wi-Fi radija; sena
     * uzklausa liko registruota, bet naujo pakėlimo nebeprašė, o kiekvienas
     * kitas kvietimas susikurdavo nauja laukimo skaitikli, kurio senasis
     * klausytojas niekada neatleisdavo - tad 4,5 s laukimas ir tuscias
     * atsakymas kas tika, 30 minuciu is eiles.
     *
     * Dabar: jei laikomo tinklo nera, sena uzklausa atleidziama ir siunciama
     * nauja, su savo laukimo skaitikliu ir laiko riba.
     */
    private Network laikykWifi() {
        if (laikomasWifi != null) {
            return laikomasWifi;
        }
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            return null;
        }
        paleiskWifi();
        final CountDownLatch laukiam = new CountDownLatch(1);
        wifiCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                laikomasWifi = n;
                Log.i(TAG, "sargas: Wi-Fi gautas");
                laukiam.countDown();
            }

            @Override
            public void onLost(Network n) {
                if (n.equals(laikomasWifi)) {
                    laikomasWifi = null;
                }
                Log.i(TAG, "sargas: Wi-Fi dingo");
            }

            @Override
            public void onUnavailable() {
                Log.i(TAG, "sargas: Wi-Fi negautas per " + WIFI_LAUKIAM_MS / 1000 + " s");
                laukiam.countDown();
            }
        };
        try {
            // Su laiko riba: nepakilus Wear pats atleidzia uzklausa ir kviecia
            // onUnavailable - kitas tikas pabandys is naujo, o ne kabos.
            cm.requestNetwork(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    wifiCb, (int) WIFI_LAUKIAM_MS);
        } catch (RuntimeException e) {
            Log.w(TAG, "sargas: Wi-Fi uzsakyti nepavyko: " + e);
            wifiCb = null;
            return null;
        }
        try {
            laukiam.await(WIFI_LAUKIAM_MS + 1000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            // grazinam, ka turim
        }
        return laikomasWifi;
    }

    private void paleiskWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (wifiCb != null && cm != null) {
            try {
                cm.unregisterNetworkCallback(wifiCb);
            } catch (RuntimeException ignored) {
                // jau nebegalioja
            }
        }
        wifiCb = null;
        laikomasWifi = null;
    }

    @Override
    public void onDestroy() {
        veikia = false;
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            am.cancel(tikoIntentas(this));
        }
        paleiskWifi();
        if (uzraktas != null && uzraktas.isHeld()) {
            uzraktas.release();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        Log.i(TAG, "sargas: sustojo");
        super.onDestroy();
    }
}
