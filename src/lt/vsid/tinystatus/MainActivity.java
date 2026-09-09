package lt.vsid.tinystatus;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * TinyMakerWiFi spausdintuvo busena ant riesto.
 *
 * Vienas ekranas: ziedas ir tekstas. Bakstelejimas - atnaujinti, ilgas
 * paspaudimas - nustatymai, braukimas aukstyn - iseiti, braukimas i sona -
 * kitas spausdintuvas (kai ju keli). Duomenis skaito TsSaltinis, pranesimus
 * veda TsPranesimas, ciferblata maitina TsKompl, o fone, kol spausdina,
 * budi TsSargas. Cia lieka tik ekranas ir gestai.
 *
 * SVARBU - uzklausa eina per WI-FI tinkla, kol i ekrana ziurima: laikrodzio
 * numatytasis tinklas yra telefonas per Bluetooth, ir spausdintuvas per ji
 * pasiekiamas tik tada, kai telefonas pats namie (zr. TsSaltinis.zonduok).
 * Atidarytame ekrane 2,5 s laukimo neverta - prasom Wi-Fi is karto.
 */
public class MainActivity extends Activity {

    private static final String TAG = "TINYSTATUS";
    private static final int REFRESH_MS = 5000;
    /** Kiek laiko be sekmingo atsakymo dar rodom senas reiksmes. Pulto
     *  dashboard'as daro ta pati: spausdintuvas neatsako reguliariai - ji uzima
     *  ikelimas, SD darbai, perziuros generavimas - ir tai NORMALU. */
    private static final long STALE_MS = 12000;
    private static final long DEAD_MS = 45000;
    /** Po tiek fone grizus rodomas spausdinantis / pasirinktas spausdintuvas. */
    private static final long GRIZTAM_MS = 10000L;

    private static final int[] IP_LAUKAI = {R.id.k_ip1, R.id.k_ip2, R.id.k_ip3, R.id.k_ip4};
    private static final int[] BG_MYGTUKAI = {R.id.bg_off, R.id.bg_const, R.id.bg_2, R.id.bg_5, R.id.bg_10};

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView state, model, layer, remaining, resin, hint, printer;
    private RingView ring;
    private ImageView refresh;
    private View nust, ipl;
    private LinearLayout alerts, autoEilute, printers;
    private volatile boolean visible = false;
    private volatile Network wifi = null;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;
    private GestureDetector gestai;
    private GestureDetector langoGestai;
    private long paskutinisPasitraukimas;
    /** Kuris spausdintuvas ekrane. */
    private int rodomas;
    /** Kuri spausdintuva redaguoja IP langas; -1 - naujas. */
    private int redaguojamas = -1;
    /** Po ilgo paspaudimo - nuryti likusi gesta, kol pirstas pakeliamas. */
    private boolean nurykIkiPakelimo;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            fetch();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    /**
     * Amziaus tiksejimas kas sekunde (kaip Vallox). Skaicius apacioje sako,
     * pries kiek laiko spausdintuvas atsake - is jo matai, ar programele dar
     * gyva, ir ar tas 62 % nera valandos senumo.
     */
    private final Runnable tiksi = new Runnable() {
        @Override
        public void run() {
            amzius();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.main);
        state = findViewById(R.id.state);
        model = findViewById(R.id.model);
        layer = findViewById(R.id.layer);
        remaining = findViewById(R.id.remaining);
        resin = findViewById(R.id.resin);
        hint = findViewById(R.id.hint);
        printer = findViewById(R.id.printer);
        ring = findViewById(R.id.ring);
        refresh = findViewById(R.id.refresh);
        nust = findViewById(R.id.nust);
        ipl = findViewById(R.id.ipl);
        alerts = findViewById(R.id.alerts);
        autoEilute = findViewById(R.id.auto_eilute);
        printers = findViewById(R.id.printers);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        rodomas = TsSaltinis.rodomas(this);

        gestaiSukurk();
        nustatymaiSukurk();

        // Pranesimams nuo Android 13 reikia leidimo. Atsisakymas reiskia tik
        // tiek, kad pranesimu nebus - programele veikia toliau.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        derinimas(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        derinimas(i);
    }

    /**
     * Derinimo rankenos per adb (tik su V leidimu):
     *   am start -n lt.vsid.tinystatus/.MainActivity --ez probe true  - zondas
     *   ... --es demo end|cancel|resin|stop  - pavyzdinis pranesimas GIF'ui
     */
    private void derinimas(Intent i) {
        if (i == null) {
            return;
        }
        if (i.getBooleanExtra("probe", false)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    TsSaltinis.zonduok(MainActivity.this);
                }
            }).start();
        }
        String demo = i.getStringExtra("demo");
        if (demo != null) {
            TsPranesimas.demo(this, demo);
        }
    }

    // ------------------------------------------------------------ gyvavimas

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        boolean ilgai = paskutinisPasitraukimas > 0
                && System.currentTimeMillis() - paskutinisPasitraukimas > GRIZTAM_MS;
        if (ilgai && nust.getVisibility() != View.VISIBLE && ipl.getVisibility() != View.VISIBLE) {
            // Grizus po ilgesnio laiko - ta, kuris spausdina (arba pasirinktas),
            // ne tas, kuri paliko pirstas.
            rodomas = TsSaltinis.rodomas(this);
        }
        requestWifi();
        show();
        ui.removeCallbacks(loop);
        ui.post(loop);
        ui.removeCallbacks(tiksi);
        ui.post(tiksi);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        paskutinisPasitraukimas = System.currentTimeMillis();
        ui.removeCallbacks(loop);
        ui.removeCallbacks(tiksi);
        releaseWifi();
        // Fono sargas startuoja CIA: uzdarant programele, kai spausdintuvas
        // ka tik matytas spausdinantis. Spausdintuvas pats nieko neskelbia,
        // tad "atidaryk programele pradejes spausdinti" ir yra sutartis.
        if (TsSargas.intervalas(this) != 0 && !TsSargas.veikia()) {
            for (int n = 0; n < TsSaltinis.skaicius(this); n++) {
                TsBusena b = TsSaltinis.atmintineje(this, n);
                if (b != null && b.busy && System.currentTimeMillis() - b.at < 2 * 60_000L) {
                    TsSargas.paleisk(this, "programele uzdaryta spausdinant");
                    break;
                }
            }
        }
    }

    /** Prasom Wi-Fi tinklo ir laikom ji, kol i ekrana ziurima. */
    private void requestWifi() {
        if (netCb != null || cm == null) {
            return;
        }
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                wifi = n;
                Log.i(TAG, "Wi-Fi tinklas gautas");
            }

            @Override
            public void onLost(Network n) {
                wifi = null;
                Log.i(TAG, "Wi-Fi tinklas dingo");
            }
        };
        try {
            cm.requestNetwork(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(), netCb);
        } catch (RuntimeException e) {
            Log.w(TAG, "Wi-Fi uzsakyti nepavyko: " + e);
            netCb = null;
        }
    }

    private void releaseWifi() {
        if (netCb != null && cm != null) {
            try {
                cm.unregisterNetworkCallback(netCb);
            } catch (RuntimeException ignored) {
                // jau nebegalioja
            }
        }
        netCb = null;
        wifi = null;
    }

    // ------------------------------------------------------------ skaitymas

    private void fetch() {
        fetch(false);
    }

    /**
     * Uzklausa atskiroje gijoje - tinklas pagrindineje gijoje neleidziamas.
     *
     * rankinis=true (braukimas aukstyn arba bakstelejimas) suka rodykle, kad
     * matytusi, jog gestas suveike. Automatine kilpa kas 5 s NESUKA: nuolat
     * besisukantis zenklas nustotu ka nors reikses.
     */
    private void fetch(boolean rankinis) {
        final int n = rodomas;
        if (TsSaltinis.skaicius(this) == 0) {
            return;
        }
        if (rankinis) {
            sukis(true);
        }
        final boolean r = rankinis;
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Per Wi-Fi tinkla, jei jis gautas; kitaip - kaip iseina.
                TsBusena b = TsSaltinis.skaityk(MainActivity.this, n, wifi);
                if (b != null) {
                    TsSaltinis.konfig(MainActivity.this, n, wifi);
                    // Ekranas busena VEDA, bet nauju pranesimu neskelbia -
                    // zmogus ir taip ziuri (tylus=true).
                    TsPranesimas.tikrink(MainActivity.this, n, b, true, REFRESH_MS);
                    TsKompl.atnaujink(MainActivity.this);
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (r) {
                            sukis(false);
                        }
                        if (visible) {
                            show();
                        }
                    }
                });
            }
        }).start();
    }

    /** Rodykle sukasi, kol vyksta rankinis atnaujinimas. */
    private void sukis(boolean ar) {
        if (!ar) {
            refresh.clearAnimation();
            refresh.setColorFilter(getColor(R.color.brand_faint));
            return;
        }
        refresh.setColorFilter(getColor(R.color.brand_orange));
        RotateAnimation a = new RotateAnimation(0, 360,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        a.setDuration(650);
        a.setRepeatCount(Animation.INFINITE);
        a.setInterpolator(new LinearInterpolator());
        refresh.startAnimation(a);
    }

    /**
     * Prierasas apacioje: pries kiek laiko spausdintuvas atsake, o fono sargui
     * veikiant - ir jo intervalas. Ekranas klausia kas 5 s visada; BACKGROUND
     * nustatymas liecia tik uzdaryta programele.
     */
    private void amzius() {
        int kiek = TsSaltinis.skaicius(this);
        long ts = (kiek == 0) ? 0 : TsSaltinis.atmintinesLaikas(this, rodomas);
        if (ts == 0) {
            hint.setText("");
            return;
        }
        long s = (System.currentTimeMillis() - ts) / 1000;
        String kada = (s < 60) ? s + "s" : (s < 3600 ? (s / 60) + "m" : (s / 3600) + "h");
        if (TsSargas.veikia()) {
            int min = TsSargas.intervalas(this);
            hint.setText(getString(R.string.age_bg, kada, min == 1 ? "30s" : min + "min"));
        } else {
            hint.setText(getString(R.string.age, kada));
        }
    }

    /** "1h 5m" arba "5m" - kiek praejo nuo pabaigos. */
    private static String since(long ms) {
        long m = ms / 60000;
        return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m";
    }

    /** Ekranas is atmintines: tinklas cia nedalyvauja. */
    private void show() {
        int kiek = TsSaltinis.skaicius(this);
        if (kiek == 0) {
            state.setText(R.string.no_printer);
            state.setTextColor(getColor(R.color.brand_warn));
            model.setText(R.string.dash);
            layer.setText(R.string.dash);
            remaining.setText(R.string.dash);
            resin.setText(R.string.dash);
            hint.setText(R.string.no_printer_hint);
            printer.setVisibility(View.GONE);
            ring.set(-1f, false);
            return;
        }
        int n = rodomas;
        if (kiek > 1) {
            printer.setText(TsSaltinis.vardas(this, n));
            printer.setVisibility(View.VISIBLE);
        } else {
            printer.setVisibility(View.GONE);
        }
        long now = System.currentTimeMillis();
        TsBusena b = TsSaltinis.atmintineje(this, n);
        long age = (b == null) ? Long.MAX_VALUE : now - b.at;

        if (b == null || age > DEAD_MS) {
            // Tikrai negyvas: nei karto negavom, arba tyli jau labai ilgai.
            state.setText(R.string.offline);
            state.setTextColor(getColor(R.color.brand_danger));
            model.setText(R.string.dash);
            layer.setText(R.string.dash);
            remaining.setText(R.string.dash);
            resin.setText(R.string.dash);
            amzius();
            ring.set(-1f, false);
            return;
        }
        amzius();

        if (TsPranesimas.rodomDone(this, n, b)) {
            // API pabaigos neturi, tad rodom TAI, KA MATEME PATYS.
            int rusis = TsPranesimas.pabaigosRusis(this, n);
            String was = TsPranesimas.pabaigosModelis(this, n);
            long end = TsPranesimas.pabaigosLaikas(this, n);
            boolean gerai = rusis == TsPranesimas.PABAIGA_BAIGTA;
            state.setText(gerai ? R.string.done
                    : (rusis == TsPranesimas.PABAIGA_ATSAUKTA ? R.string.canceled : R.string.stopped));
            state.setTextColor(gerai ? getColor(R.color.brand_ok) : getColor(R.color.brand_warn));
            model.setText(was.isEmpty() ? getString(R.string.dash) : was);
            layer.setText(getString(R.string.layers, TsPranesimas.pabaigosSluoksniai(this, n)));
            remaining.setText(getString(gerai ? R.string.finished_ago : R.string.ended_ago,
                    since(now - end)));
            ring.set(gerai ? 1f : -1f, false);
        } else if (b.busy) {
            state.setText(b.state.isEmpty() ? "?" : b.state.toUpperCase());
            state.setTextColor(b.paused ? getColor(R.color.brand_warn) : getColor(R.color.brand_text));
            model.setText(b.model.isEmpty() ? getString(R.string.dash) : b.model);
            layer.setText(b.total > 0 ? b.layerText : getString(R.string.dash));
            remaining.setText(b.total > 0 ? b.remainingTime : getString(R.string.dash));
            // Procentu API neduoda - skaiciuojam patys is sluoksniu.
            ring.set(b.progress(), b.paused);
        } else {
            state.setText(b.state.isEmpty() ? getString(R.string.idle) : b.state.toUpperCase());
            state.setTextColor(getColor(R.color.brand_muted));
            model.setText(R.string.dash);
            layer.setText(R.string.dash);
            remaining.setText(R.string.dash);
            ring.set(-1f, false);
        }

        String r = b.resinLine();
        resin.setText(r.isEmpty() ? getString(R.string.dash) : r);
        resin.setTextColor(b.vatLow ? getColor(R.color.brand_warn) : getColor(R.color.brand_muted));
    }

    // ------------------------------------------------------------ gestai

    /**
     * Gestus gaudom dispatchTouchEvent, o ne klausytoju ant saknies: su
     * klausytoju braukimas nesuveikdavo isvis (Vallox pamoka).
     */
    private void gestaiSukurk() {
        gestai = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || b == null) {
                    return false;
                }
                if (Math.abs(vy) > Math.abs(vx)) {
                    // AUKSTYN - atnaujinti, ZEMYN - iseiti (V). Pagrindinis
                    // ekranas telpa, tad vertikalus judesys jame laisvas.
                    float dy = b.getY() - a.getY();
                    if (dy < -60) {
                        Log.i(TAG, "braukimas aukstyn - atnaujinam");
                        fetch(true);
                        return true;
                    }
                    if (dy > 60) {
                        Log.i(TAG, "braukimas zemyn - uzdarom");
                        finish();
                        return true;
                    }
                    return false;
                }
                float dx = b.getX() - a.getX();
                int kiek = TsSaltinis.skaicius(MainActivity.this);
                if (kiek > 1 && Math.abs(dx) > 50) {
                    rodomas = (rodomas + (dx < 0 ? 1 : kiek - 1)) % kiek;
                    // Tas, i kuri nubraukei, tampa ir numatytuoju - jokio
                    // atskiro "rodyti si" nustatymo nereikia.
                    TsSaltinis.prefs(MainActivity.this).edit().putInt("pr.sel", rodomas).apply();
                    Log.i(TAG, "spausdintuvas " + rodomas);
                    show();
                    fetch();
                }
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (TsSaltinis.skaicius(MainActivity.this) == 0) {
                    rodykNustatymus(true);
                } else {
                    fetch(true);
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                Log.i(TAG, "ilgas paspaudimas - nustatymai");
                // Pirstas dar ant ekrano: jo judesys nuslinktu ka tik
                // atidaryta langa (antraste dingdavo virsuje). Nurijam.
                nurykIkiPakelimo = true;
                rodykNustatymus(true);
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;                   // be sito onFling nesuveikia
            }
        });
        // Atidarytame lange braukimas i sona grazina atgal - Back mygtuko ant
        // apvalaus ekrano nera. Vertikalus judesys lieka ScrollView slinkimui.
        langoGestai = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || b == null || Math.abs(vx) < Math.abs(vy)
                        || Math.abs(b.getX() - a.getX()) < 50) {
                    return false;
                }
                Log.i(TAG, "langas: braukimas atgal");
                onBackPressed();
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (nurykIkiPakelimo) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                nurykIkiPakelimo = false;
            }
            return true;
        }
        if (nust.getVisibility() == View.VISIBLE || ipl.getVisibility() == View.VISIBLE) {
            // Mygtukai lange dirba patys (super), mes is salies ziurim tik,
            // ar tai nebuvo braukimas atgal.
            langoGestai.onTouchEvent(e);
            return super.dispatchTouchEvent(e);
        }
        gestai.onTouchEvent(e);
        return super.dispatchTouchEvent(e);
    }

    /** Back uzdaro langa, o ne programele. */
    @Override
    public void onBackPressed() {
        if (ipl.getVisibility() == View.VISIBLE) {
            ipl.setVisibility(View.GONE);
            rodykNustatymus(true);
            return;
        }
        if (nust.getVisibility() == View.VISIBLE) {
            rodykNustatymus(false);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------ nustatymai

    private void nustatymaiSukurk() {
        for (int i = 0; i < BG_MYGTUKAI.length; i++) {
            final int pasirinkimas = i;
            findViewById(BG_MYGTUKAI[i]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    TsSaltinis.prefs(MainActivity.this).edit().putInt("bg.int", pasirinkimas).apply();
                    Log.i(TAG, "fonas: " + pasirinkimas);
                    if (pasirinkimas == 0) {
                        TsSargas.sustabdyk(MainActivity.this, "isjungta nustatymuose");
                    } else if (TsSargas.veikia()) {
                        // Naujas intervalas isigalios nuo kito tiko - sargas
                        // ji skaito kaskart is nustatymu.
                        Log.i(TAG, "fonas: sargas veikia, intervalas nuo kito tiko");
                    }
                    zymekFona();
                }
            });
        }
        findViewById(R.id.n_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rodykNustatymus(false);
            }
        });

        // IP langas: suvedus tris skaitmenis - i kita langeli; klaviaturos
        // varnele issaugo, nes ji uzdengia Save.
        for (int i = 0; i < IP_LAUKAI.length; i++) {
            final int kitas = (i + 1 < IP_LAUKAI.length) ? IP_LAUKAI[i + 1] : 0;
            ((EditText) findViewById(IP_LAUKAI[i])).addTextChangedListener(new TextWatcher() {
                @Override
                public void afterTextChanged(Editable e) {
                    if (e.length() == 3 && kitas != 0) {
                        findViewById(kitas).requestFocus();
                    }
                }

                @Override
                public void beforeTextChanged(CharSequence c, int a, int b, int d) {
                }

                @Override
                public void onTextChanged(CharSequence c, int a, int b, int d) {
                }
            });
        }
        ((EditText) findViewById(R.id.k_ip4)).setOnEditorActionListener(
                new TextView.OnEditorActionListener() {
                    @Override
                    public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                        irasykIp();
                        return true;
                    }
                });
        findViewById(R.id.k_irasyk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                irasykIp();
            }
        });
        findViewById(R.id.k_isimk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (redaguojamas >= 0) {
                    TsSaltinis.isimkSpausdintuva(MainActivity.this, redaguojamas);
                    Log.i(TAG, "spausdintuvas " + redaguojamas + " isimtas");
                }
                rodomas = TsSaltinis.rodomas(MainActivity.this);
                ipl.setVisibility(View.GONE);
                rodykNustatymus(true);
            }
        });
    }

    private void rodykNustatymus(boolean ar) {
        if (ar) {
            zymekFona();
            piesk();
            nust.setVisibility(View.VISIBLE);
            nust.post(new Runnable() {
                @Override
                public void run() {
                    nust.scrollTo(0, 0);       // praeito karto slinktis - ne musu
                }
            });
        } else {
            nust.setVisibility(View.GONE);
            rodomas = TsSaltinis.rodomas(this);
            show();
            fetch();
        }
    }

    private void zymekFona() {
        int i = TsSaltinis.prefs(this).getInt("bg.int", 0);
        for (int k = 0; k < BG_MYGTUKAI.length; k++) {
            findViewById(BG_MYGTUKAI[k]).setSelected(k == i);
        }
    }

    /** Jungikliu eilutes ir spausdintuvu sarasas - perpiesiama kaskart atidarius. */
    private void piesk() {
        final SharedPreferences p = TsSaltinis.prefs(this);
        alerts.removeAllViews();
        String[][] al = {{"al.end", getString(R.string.a_end), "1"},
                {"al.warn", getString(R.string.a_warn), "1"},
                {"al.stop", getString(R.string.a_stop), "1"},
                {"al.pause", getString(R.string.a_pause), "0"}};
        for (final String[] a : al) {
            alerts.addView(eilute(a[1], p.getBoolean(a[0], "1".equals(a[2])),
                    new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton v, boolean ar) {
                            p.edit().putBoolean(a[0], ar).apply();
                        }
                    }));
        }

        autoEilute.removeAllViews();
        autoEilute.addView(eilute(getString(R.string.s_auto), TsSaltinis.auto(this),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton v, boolean ar) {
                        p.edit().putBoolean("auto", ar).apply();
                        Log.i(TAG, "autodiscovery: " + ar);
                        // Kitas rezimas - kita atmintine ir kita pranesimu
                        // busena; sena butu apie kita spausdintuva.
                        TsPranesimas.nuvalyk(MainActivity.this);
                        rodomas = TsSaltinis.rodomas(MainActivity.this);
                        pieskSarasa();
                    }
                }));
        pieskSarasa();
    }

    private void pieskSarasa() {
        printers.removeAllViews();
        if (TsSaltinis.auto(this)) {
            String ip = TsSaltinis.prefs(this).getString("pr.0.seen", "");
            String host = TsSaltinis.prefs(this).getString("pr.0.host", "");
            if (!host.isEmpty()) {
                printers.addView(prierasas(host + (ip.isEmpty() ? "" : " · " + ip)));
            }
            return;
        }
        int kiek = TsSaltinis.skaicius(this);
        for (int n = 0; n < kiek; n++) {
            final int nr = n;
            TextView v = mygtukas(TsSaltinis.ip(this, n), n == TsSaltinis.pasirinktas(this));
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    rodykIpLanga(nr);
                }
            });
            printers.addView(v);
        }
        if (kiek < TsSaltinis.MAX_PR) {
            TextView v = mygtukas(getString(R.string.a_add), false);
            v.setBackgroundResource(R.drawable.veiksmas);
                v.setTextColor(getColor(R.color.on_blue));
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    rodykIpLanga(-1);
                }
            });
            printers.addView(v);
        }
    }

    private TextView mygtukas(String tekstas, boolean pazymetas) {
        float t = getResources().getDisplayMetrics().density;
        TextView v = new TextView(this);
        v.setText(tekstas);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(13);
        v.setTextColor(getColorStateList(R.color.mygtuko_tekstas));
        v.setPadding(0, Math.round(7 * t), 0, Math.round(7 * t));
        v.setBackgroundResource(R.drawable.mygtukas);
        v.setSelected(pazymetas);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(6 * t);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView prierasas(String tekstas) {
        float t = getResources().getDisplayMetrics().density;
        TextView v = new TextView(this);
        v.setText(tekstas);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(11);
        v.setTextColor(getColor(R.color.brand_muted));
        v.setPadding(0, Math.round(6 * t), 0, 0);
        return v;
    }

    /**
     * Jungiklio eilute: vardas ir jungiklis ATSKIRAI. Su Switch nuosavu tekstu
     * vardas nukerpamas net sumazinus srifta (Vallox greblys). Busena dedam
     * PRIES klausytoja - setChecked ji pazadina.
     */
    private View eilute(String vardas, boolean busena, CompoundButton.OnCheckedChangeListener kl) {
        float t = getResources().getDisplayMetrics().density;
        LinearLayout e = new LinearLayout(this);
        e.setOrientation(LinearLayout.HORIZONTAL);
        e.setGravity(Gravity.CENTER_VERTICAL);
        e.setPadding(Math.round(12 * t), Math.round(9 * t), Math.round(6 * t), Math.round(9 * t));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(6 * t);
        e.setLayoutParams(lp);

        TextView v = new TextView(this);
        v.setText(vardas);
        v.setSingleLine(true);
        v.setTextSize(13);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setTextColor(getColor(R.color.brand_text));
        v.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        e.addView(v);

        final Switch j = new Switch(this);
        j.setChecked(busena);
        j.setOnCheckedChangeListener(kl);
        e.addView(j);

        // Spaudziama visa eilute - taikytis i jungikli ant riesto sunku.
        e.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                j.toggle();
            }
        });
        return e;
    }

    // ------------------------------------------------------------ IP langas

    private void rodykIpLanga(int n) {
        redaguojamas = n;
        String a = (n >= 0) ? TsSaltinis.ip(this, n) : "";
        String[] d = a.split("\\.", -1);
        for (int i = 0; i < IP_LAUKAI.length; i++) {
            ((EditText) findViewById(IP_LAUKAI[i])).setText(d.length == 4 ? d[i] : "");
        }
        ((TextView) findViewById(R.id.k_busena)).setText("");
        findViewById(R.id.k_isimk).setVisibility(n >= 0 ? View.VISIBLE : View.GONE);
        nust.setVisibility(View.GONE);
        ipl.setVisibility(View.VISIBLE);
        findViewById(R.id.k_ip1).requestFocus();
    }

    private String ipLaukuose() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < IP_LAUKAI.length; i++) {
            if (i > 0) {
                b.append('.');
            }
            b.append(((EditText) findViewById(IP_LAUKAI[i])).getText().toString().trim());
        }
        return b.toString();
    }

    /** Ar tai IPv4 adresas. Keturios dalys, kiekviena 0..255. */
    private static boolean arIP(String a) {
        String[] d = a.split("\\.", -1);
        if (d.length != 4) {
            return false;
        }
        for (String x : d) {
            if (x.length() == 0 || x.length() > 3) {
                return false;
            }
            for (int i = 0; i < x.length(); i++) {
                if (!Character.isDigit(x.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(x) > 255) {
                return false;
            }
        }
        return true;
    }

    private void irasykIp() {
        final String a = ipLaukuose();
        final TextView busena = findViewById(R.id.k_busena);
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) {
            im.hideSoftInputFromWindow(findViewById(R.id.k_ip1).getWindowToken(), 0);
        }
        // Ant riesto vedant lengva suklysti - tokio adreso NEIRASOM: tyliai
        // priimtas jis reikstu programele, kuri nebeveikia, ir nezinia kodel.
        if (!arIP(a)) {
            busena.setText(R.string.e_ip);
            return;
        }
        TsSaltinis.irasykSpausdintuva(this, redaguojamas, a, null);
        final int n = (redaguojamas >= 0) ? redaguojamas : TsSaltinis.skaicius(this) - 1;
        redaguojamas = n;
        findViewById(R.id.k_isimk).setVisibility(View.VISIBLE);
        Log.i(TAG, "spausdintuvas " + n + ": " + a);
        busena.setText(R.string.e_checking);
        // Pasitikrinam, ar tuo adresu atsiliepia SPAUSDINTUVAS. Neatsiliepus
        // vis tiek issaugom - gal jis tiesiog isjungtas.
        new Thread(new Runnable() {
            @Override
            public void run() {
                final TsBusena b = TsSaltinis.skaityk(MainActivity.this, n, wifi);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busena.setText(b != null ? R.string.e_ok : R.string.e_none);
                        if (b != null) {
                            ui.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (ipl.getVisibility() == View.VISIBLE) {
                                        ipl.setVisibility(View.GONE);
                                        rodykNustatymus(true);
                                    }
                                }
                            }, 1400);
                        }
                    }
                });
            }
        }).start();
    }
}
