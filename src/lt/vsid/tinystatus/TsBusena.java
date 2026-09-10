package lt.vsid.tinystatus;

import org.json.JSONObject;

/**
 * Spausdintuvo busena - tai, ka is /api/status reikia ekranui, ciferblatui ir
 * pranesimams. Vienas isskaitymas, trys vartotojai.
 *
 * Laukai nusirasyti is TinyMakerWiFi Network.ino handleApiStatus (2026-09-09),
 * ne speti. Svarbiausi kabliai:
 *   - procentu API NEDUODA: progresas is currentLayer/totalLayers;
 *   - pabaigos API NEPRANESA: baigus tiesiog busy=false ir model tuscias;
 *   - stateCode 8 ("Raising plate") buna IR baigus, IR atsaukus - skiria
 *     waitStage ("stopLift"/"stopTail" = atsaukimas) arba anksciau matytas
 *     stopping=true;
 *   - stateCode 10 ("Refill resin") - spausdintuvas sustojo del dervos;
 *   - vatLow = derva <= STOP slenkscio (numatyta 2 ml); ISPEJIMO slenkstis
 *     (numatyta 5 ml) gyvena /api/config, ne cia.
 */
public final class TsBusena {

    public boolean busy;
    public boolean paused;
    public boolean stopping;
    public int stateCode;
    public String state = "";
    public String waitStage = "";
    public String model = "";
    public int cur;
    public int total;
    public String layerText = "";
    public long remainingSecs;
    public String remainingTime = "";
    public long runSecs;
    public String runTime = "";
    public double resinUsedMl;
    public String resinText = "";
    public double vatRemainingMl = -1;
    public String vatText = "";
    public boolean vatLow;
    public String ip = "";
    /** Kada gauta (System.currentTimeMillis). */
    public long at;

    private TsBusena() {
    }

    /** null, jei tai ne JSON arba ne /api/status atsakymas. */
    public static TsBusena parse(String json, long at) {
        if (json == null) {
            return null;
        }
        try {
            JSONObject j = new JSONObject(json);
            // "ok" - vientisumo zyme: 200 be jos reiskia nukirsta atsakyma.
            if (!j.optBoolean("ok", false) && !j.has("state")) {
                return null;
            }
            TsBusena b = new TsBusena();
            b.busy = j.optBoolean("busy", false);
            b.paused = j.optBoolean("paused", false);
            b.stopping = j.optBoolean("stopping", false);
            b.stateCode = j.optInt("stateCode", -1);
            b.state = j.optString("state", "");
            b.waitStage = j.optString("waitStage", "");
            b.model = j.optString("model", "");
            b.cur = j.optInt("currentLayer", 0);
            b.total = j.optInt("totalLayers", 0);
            b.layerText = j.optString("layerText", "");
            b.remainingSecs = j.optLong("remainingSecs", 0);
            b.remainingTime = j.optString("remainingTime", "");
            b.runSecs = j.optLong("runSecs", 0);
            b.runTime = j.optString("runTime", "");
            b.resinUsedMl = j.optDouble("resinUsedMl", 0);
            b.resinText = j.optString("resinText", "");
            b.vatRemainingMl = j.optDouble("vatRemainingMl", -1);
            b.vatText = j.optString("vatText", "");
            b.vatLow = j.optBoolean("vatLow", false);
            b.ip = j.optString("ip", "");
            b.at = at;
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** 0..1, arba neigiamas, kai sluoksniu nezinoma. */
    public float progress() {
        return total > 0 ? Math.min(1f, (float) cur / (float) total) : -1f;
    }

    /** "62%" - komplikacijai. */
    public String percentText() {
        return total > 0 ? Math.round(100f * progress()) + "%" : "";
    }

    /**
     * "1h20" arba "45m" - kiek liko. Be tarpo ir be "m" po valandu: lizdas
     * ciferblate yra 78 vienetu, ir "1h 20m" i ji nebetelpa.
     */
    public String timeLeft() {
        if (total <= 0 || remainingSecs <= 0) {
            return "";
        }
        long m = (remainingSecs + 30) / 60;
        return m >= 60 ? (m / 60) + "h" + String.format("%02d", m % 60) : m + "m";
    }

    /** Ar spausdintuvas siuo metu stabdo/atsauke spausdinima. */
    public boolean isCancel() {
        return stopping || stateCode == 4
                || "stopTail".equals(waitStage) || "stopLift".equals(waitStage);
    }

    /** Kada spausdinimas turetu baigtis pagal spausdintuvo ivertinima; 0 - nezinia. */
    public long expectedEnd() {
        return (busy && remainingSecs > 0) ? at + remainingSecs * 1000L : 0;
    }

    /**
     * Dervos eilute ekranui: KIEK LIKO VAT'e (V), ne kiek sunaudota, ir tai
     * pasakyta zodziu - "7.4 ml left". Vien skaicius neatsako i klausima, ar
     * tai likutis, ar sunaudotas kiekis.
     *
     * Mililitrais, ne gramais (V): gramai priklauso nuo dervos tankio, o
     * buteliai ir VAT'as matuojami mililitrais.
     */
    public String resinLine() {
        if (vatRemainingMl < 0) {
            return resinText.isEmpty() ? vatText : resinText;
        }
        return String.format("%.1f ml left", vatRemainingMl);
    }

    /** Derva komplikacijai: "7.4ml" - lizde vietos zodziui nera, ji pasako pats
     *  teikejo vardas ("TinyMaker resin left"). */
    public String resinShort() {
        return vatRemainingMl >= 0 ? String.format("%.1fml", vatRemainingMl) : "";
    }
}
