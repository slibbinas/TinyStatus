package lt.vsid.tinystatus;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * TinyMakerWiFi spausdintuvo busena ant riesto.
 *
 * Duomenys: GET http://tinymaker.local/api/status - visas atsakymas ~1.2 KB,
 * be jokios autentikacijos (patikrinta 2026-09-05 tiesiai is laikrodzio).
 * Vardas issisprendzia per marsrutizatoriaus DNS, tad NsdManager nereikia.
 *
 * Atnaujinimas: automatiskai kas REFRESH_MS, kol i ekrana ziurima, ir is karto
 * bakstelejus. Uzdarius - nieko, jokiu fono darbu: spausdinimas trunka
 * valandas, tad pakelti ranka ir pasiziureti visai uztenka.
 *
 * SVARBU - uzklausa privalo eiti per WI-FI tinkla, ne per numatytaji.
 * Laikrodzio numatytasis tinklas yra telefonas per Bluetooth, ir jis apie
 * "tinymaker.local" nieko nezino:
 *     java.net.UnknownHostException: Unable to resolve host "tinymaker.local"
 * Apvalkalo ping pavykdavo, nes jis naudoja Wi-Fi resolveri tiesiogiai.
 * Todel prasom konkretaus Wi-Fi tinklo ir jungiames per ji - tada ir vardas
 * issisprendzia, ir marsrutas teisingas.
 *
 * Viskas surenkama be Gradle ir be bibliotekiu: HttpURLConnection ir org.json
 * yra pacioje Android sistemoje.
 */
public class MainActivity extends Activity {

    private static final String TAG = "TINYSTATUS";
    private static final int REFRESH_MS = 5000;
    private static final int CONNECT_MS = 2500;
    private static final int READ_MS = 3500;
    /** Kiek laiko be sekmingo atsakymo dar rodom senas reiksmes.
     *
     *  Taip daro ir pats pulto dashboard'as: jis neskelbia "offline", o tik
     *  pazymi, kad duomenys pasene (`stale = now - lastPollOkAt > 4000`).
     *  Spausdintuvas neatsako reguliariai - ji uzima ikelimas, SD darbai ar
     *  peržiūros generavimas - ir tai NORMALU, ne gedimas. */
    private static final long STALE_MS = 12000;
    private static final long DEAD_MS = 45000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView state, model, layer, remaining, resin, hint;
    private RingView ring;
    private volatile boolean visible = false;
    private volatile Network wifi = null;
    private long lastOkAt = 0;              // kada paskutini karta gavom atsakyma
    private String lastBody = null;         // ir ka jis sake
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            fetch();
            ui.postDelayed(this, REFRESH_MS);
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
        ring = findViewById(R.id.ring);

        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        findViewById(R.id.root).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Nieko neleidziam iskristi is klausytojo: nepagauta klaida cia
                // uzdaro visa programele (ADB Toggle pamoka).
                try {
                    fetch();
                } catch (RuntimeException e) {
                    Log.w(TAG, "bakstelejimas: " + e);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        requestWifi();
        ui.removeCallbacks(loop);
        ui.post(loop);
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

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        ui.removeCallbacks(loop);
        releaseWifi();
    }

    /** Uzklausa atskirame gijoje - tinklas pagrindineje gijoje neleidziamas. */
    private void fetch() {
        final String url = "http://" + getString(R.string.host) + "/api/status";
        new Thread(new Runnable() {
            @Override
            public void run() {
                String body = null;
                try {
                    body = get(url);
                } catch (Exception e) {
                    Log.w(TAG, "uzklausa nepavyko: " + e);
                }
                final String result = body;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (visible) {
                            show(result);
                        }
                    }
                });
            }
        }).start();
    }

    private String get(String url) throws Exception {
        Network net = wifi;
        URL u = new URL(url);
        // Per Wi-Fi tinkla, jei jis gautas; kitaip - kaip iseina.
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
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void show(String body) {
        long now = System.currentTimeMillis();
        if (body != null) {
            lastBody = body;
            lastOkAt = now;
        }
        long age = lastOkAt == 0 ? Long.MAX_VALUE : now - lastOkAt;

        if (lastBody == null || age > DEAD_MS) {
            // Tikrai negyvas: nei karto negavom, arba tyli jau labai ilgai.
            state.setText(R.string.offline);
            state.setTextColor(0xFFFF453A);
            model.setText(R.string.dash);
            layer.setText(R.string.dash);
            remaining.setText(R.string.dash);
            resin.setText(R.string.dash);
            hint.setText(R.string.tap_hint);
            ring.set(-1f, false);
            return;
        }
        body = lastBody;
        // Senos reiksmes lieka ekrane; apie ju amziu pasako tik prierasas.
        hint.setText(age > STALE_MS
                ? getString(R.string.stale, age / 1000)
                : getString(R.string.tap_hint));
        try {
            JSONObject j = new JSONObject(body);
            boolean paused = j.optBoolean("paused", false);
            int cur = j.optInt("currentLayer", 0);
            int total = j.optInt("totalLayers", 0);

            state.setText(j.optString("state", "?").toUpperCase());
            state.setTextColor(paused ? 0xFFF5C542 : 0xFF2FD4B5);

            String name = j.optString("model", "");
            model.setText(name.isEmpty() ? getString(R.string.dash) : name);

            // layerText jau paruostas ("123 / 240"), o kai nespausdinama - "0 / 0"
            layer.setText(total > 0 ? j.optString("layerText", "—")
                                    : getString(R.string.dash));
            remaining.setText(total > 0 ? j.optString("remainingTime", "—")
                                        : getString(R.string.dash));

            String r = j.optString("resinText", "");
            if (r.isEmpty()) {
                r = j.optString("vatText", "");
            }
            resin.setText(r.isEmpty() ? getString(R.string.dash) : r);
            resin.setTextColor(j.optBoolean("vatLow", false) ? 0xFFF5C542 : 0xFF8A8A8E);

            // Procentu API neduoda - skaiciuojam patys is sluoksniu.
            ring.set(total > 0 ? (float) cur / (float) total : -1f, paused);
        } catch (Exception e) {
            Log.w(TAG, "JSON nesuprastas: " + e);
            state.setText(R.string.offline);
            state.setTextColor(0xFFFF453A);
        }
    }
}
