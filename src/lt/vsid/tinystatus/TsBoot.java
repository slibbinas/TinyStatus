package lt.vsid.tinystatus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Po perkrovimo atkuria sarga, jei jis buvo ijungtas ir spausdinimas matytas
 * ne seniau nei pries 12 h. Kitaip laikrodzio perkrovimas spausdinimo viduryje
 * tyliai nutrauktu sekima. connectedDevice tipo servisa is BOOT_COMPLETED
 * Android 15 leidzia.
 */
public class TsBoot extends BroadcastReceiver {

    private static final String TAG = "TINYSTATUS";

    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            return;
        }
        if (!TsSaltinis.prefs(c).getBoolean("bg.on", false)) {
            return;
        }
        long naujausia = 0;
        for (int n = 0; n < TsSaltinis.skaicius(c); n++) {
            naujausia = Math.max(naujausia, TsSaltinis.atmintinesLaikas(c, n));
        }
        if (System.currentTimeMillis() - naujausia > 12L * 3600 * 1000) {
            Log.i(TAG, "boot: sargas buvo ijungtas, bet spausdinimas senas - neatkuriam");
            TsSaltinis.prefs(c).edit().putBoolean("bg.on", false).apply();
            return;
        }
        TsSargas.paleisk(c, "boot");
    }
}
