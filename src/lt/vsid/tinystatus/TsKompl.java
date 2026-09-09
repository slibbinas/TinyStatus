package lt.vsid.tinystatus;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.support.wearable.complications.ComplicationData;
import android.support.wearable.complications.ComplicationManager;
import android.support.wearable.complications.ComplicationProviderService;
import android.support.wearable.complications.ComplicationText;
import android.util.Log;

/**
 * Spausdintuvo reiksme ciferblate: bazine klase visiems saltiniams.
 *
 * Ciferblatas (WFF) kodo neturi ir i tinkla neina, tad reiksme i ji patenka tik
 * per komplikaciju teikeja. Keturi saltiniai: eiga (%), likes laikas, derva ir
 * ziedas (RANGED_VALUE) - V pats renkasi, kuris kur.
 *
 * ATSAKOM TIK IS ATMINTINES ir tinklo NEPRASOM. Sistema klausia teikeju
 * kaskart pakelus ranka; Vallox pamoka (2026-09-09): teikejo sukeltas skaitymas
 * kiekviena rankos pakelima paversdavo Wi-Fi radijo kelimu. Sviezuma cia
 * atnesa fono sargas (TsSargas) arba atidaryta programele.
 *
 * Zenklas: TIK setIcon + setBurnInProtectionIcon. Pridejus setSmallImage,
 * ciferblatas nustoja piesti komplikacija VISAI (ismatuota 2026-09-07).
 */
public abstract class TsKompl extends ComplicationProviderService {

    private static final String TAG = "TINYSTATUS";

    static final Class<?>[] KLASES = {Eiga.class, Laikas.class, Derva.class, Ziedas.class};

    /** Tekstas is busenos; tuscias - kai nera ko rodyti. */
    protected abstract String tekstas(Context c, int n, TsBusena b);

    protected int zenklas() {
        return R.drawable.ic_ts_print;
    }

    /** Kuri tipa sis teikejas moka: SHORT_TEXT arba RANGED_VALUE. */
    protected int tipas() {
        return ComplicationData.TYPE_SHORT_TEXT;
    }

    /** "DONE" 12 h po pabaigos, "IDLE" kai ramu, kitaip tuscia (= imk is b). */
    static String ramybe(Context c, int n, TsBusena b) {
        if (b != null && b.busy) {
            return "";
        }
        return TsPranesimas.rodomDone(c, n, b) ? "DONE" : "IDLE";
    }

    @Override
    public void onComplicationUpdate(int complicationId, int dataType,
                                     ComplicationManager manager) {
        int n = TsSaltinis.rodomas(this);
        TsBusena b = TsSaltinis.atmintineje(this, n);
        String t = (b == null) ? "" : tekstas(this, n, b);
        Log.i(TAG, "teikejas " + getClass().getSimpleName() + " id=" + complicationId
                + " tipas=" + dataType + " tekstas=" + t);
        if (dataType != tipas() || t.isEmpty()) {
            // Nepalaikomo tipo ar tuscios reiksmes NEGALIMA palikti be atsakymo -
            // sistema laukia. noUpdateRequired palieka tai, kas buvo.
            manager.noUpdateRequired(complicationId);
            return;
        }
        ComplicationData.Builder d = new ComplicationData.Builder(tipas())
                .setShortText(ComplicationText.plainText(t))
                .setTapAction(TsPranesimas.atidaryk(this));
        if (tipas() == ComplicationData.TYPE_RANGED_VALUE) {
            // Ziedas: sluoksniai. Ramybeje tuscias, DONE - pilnas.
            float v = b.busy ? b.cur : ("DONE".equals(t) ? 1f : 0f);
            float max = b.busy ? Math.max(1, b.total) : 1f;
            d.setMinValue(0f).setMaxValue(max).setValue(v);
        }
        int z = zenklas();
        if (z != 0) {
            Icon i = Icon.createWithResource(this, z);
            d.setIcon(i).setBurnInProtectionIcon(i);
        }
        manager.updateComplicationData(complicationId, d.build());
    }

    /**
     * Paprasom sistemos vel klausti VISU musu teikeju - kai atmintineje nauja
     * busena. Transliacija siunciama ranka su FLAG_IMMUTABLE: bibliotekos
     * ProviderUpdateRequester kuria PendingIntent be zymes, ir Android 12+ ji
     * atmeta (Vallox greblys, laukai nusirasyti is javap).
     */
    public static void atnaujink(Context c) {
        for (Class<?> k : KLASES) {
            try {
                Intent i = new Intent(
                        "android.support.wearable.complications.ACTION_REQUEST_UPDATE_ALL");
                i.setPackage("com.google.android.wearable.app");
                i.putExtra("android.support.wearable.complications.EXTRA_PROVIDER_COMPONENT",
                        new ComponentName(c, k));
                i.putExtra("android.support.wearable.complications.EXTRA_PENDING_INTENT",
                        PendingIntent.getActivity(c, 0, new Intent(""),
                                PendingIntent.FLAG_IMMUTABLE));
                c.sendBroadcast(i);
            } catch (Exception e) {
                Log.w(TAG, "atnaujinti nepavyko (" + k.getSimpleName() + "): " + e);
            }
        }
    }

    // ------------------------------------------------------------ saltiniai

    /** Eiga procentais: "62%", DONE, IDLE. */
    public static class Eiga extends TsKompl {
        @Override
        protected String tekstas(Context c, int n, TsBusena b) {
            String r = ramybe(c, n, b);
            return r.isEmpty() ? b.percentText() : r;
        }
    }

    /** Likes laikas: "1h20" / "45m", DONE, IDLE. */
    public static class Laikas extends TsKompl {
        @Override
        protected String tekstas(Context c, int n, TsBusena b) {
            String r = ramybe(c, n, b);
            return r.isEmpty() ? b.timeLeft() : r;
        }

        @Override
        protected int zenklas() {
            return R.drawable.ic_ts_timer;
        }
    }

    /** Derva VAT'e gramais: "8.6g" (V). Rodoma ir ramybeje - ji niekur nedingsta. */
    public static class Derva extends TsKompl {
        @Override
        protected String tekstas(Context c, int n, TsBusena b) {
            return b.resinShort();
        }

        @Override
        protected int zenklas() {
            return R.drawable.ic_ts_drop;
        }
    }

    /**
     * Ziedas - RANGED_VALUE. Moka TIK si tipa: lizdo supportedTypes eile lemia,
     * kuri versija atsiunciama (greblys 12), o SHORT_TEXT lizduose visada
     * pirmas. Todel teikejas, mokantis abu, ziedo niekada negautu.
     */
    public static class Ziedas extends TsKompl {
        @Override
        protected String tekstas(Context c, int n, TsBusena b) {
            String r = ramybe(c, n, b);
            return r.isEmpty() ? b.percentText() : r;
        }

        @Override
        protected int tipas() {
            return ComplicationData.TYPE_RANGED_VALUE;
        }
    }
}
