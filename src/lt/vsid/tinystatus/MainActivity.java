package lt.vsid.tinystatus;

import android.app.Activity;
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
 * Viskas surenkama be Gradle ir be bibliotekiu: HttpURLConnection ir org.json
 * yra pacioje Android sistemoje.
 */
public class MainActivity extends Activity {

    private static final String TAG = "TINYSTATUS";
    private static final int REFRESH_MS = 5000;
    private static final int CONNECT_MS = 2500;
    private static final int READ_MS = 3500;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView state, model, layer, remaining, resin;
    private RingView ring;
    private volatile boolean visible = false;

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
        ring = findViewById(R.id.ring);

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
        ui.removeCallbacks(loop);
        ui.post(loop);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        ui.removeCallbacks(loop);
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
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
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
        if (body == null) {
            state.setText(R.string.offline);
            state.setTextColor(0xFFFF453A);
            model.setText(R.string.dash);
            layer.setText(R.string.dash);
            remaining.setText(R.string.dash);
            resin.setText(R.string.dash);
            ring.set(-1f, false);
            return;
        }
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
