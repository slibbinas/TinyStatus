package lt.vsid.tinystatus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Progreso ziedas pakrasciu.
 *
 * Spausdintuvas procentu NEGRAZINA (patikrinta: /api/status neturi nei
 * "progress", nei "percent"), tad progresas skaiciuojamas is sluoksniu -
 * currentLayer / totalLayers. Kai sluoksniu nezinoma, ziedas nepiesiamas.
 */
public class RingView extends View {

    // TinyMaker firmines spalvos (zr. res/values/colors.xml). Ziedas oranzinis:
    // brandbook'as sako, kad oranzine yra vienintelis firmos signalas, ir
    // ekrane jis atitenka svarbiausiam dalykui - eigai.
    private static final int TRACK = 0xFF26262A;
    /** Tarpeliai kas 10 % - piesiami fono spalva, tad ziedas atrodo dalytas. */
    private static final int GAP = 0xFF000000;
    private static final int DALYS = 10;
    private static final float GAP_DEG = 1.6f;
    private static final int FILL = 0xFFE8720C;
    private static final int FILL_PAUSED = 0xFFFFB15F;
    private static final float STROKE_DP = 9f;
    private static final float INSET_DP = 5f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float progress = -1f;           // <0 - nezinoma, ziedo nepiesiam
    private boolean paused = false;

    public RingView(Context c, AttributeSet a) {
        super(c, a);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** progress 0..1, arba neigiamas, kai duomenu nera. */
    public void set(float progress, boolean paused) {
        this.progress = progress;
        this.paused = paused;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float stroke = dp(STROKE_DP);
        float inset = dp(INSET_DP) + stroke / 2f;
        box.set(inset, inset, getWidth() - inset, getHeight() - inset);
        paint.setStrokeWidth(stroke);

        paint.setColor(TRACK);
        canvas.drawArc(box, 0, 360, false, paint);

        if (progress > 0f) {
            paint.setColor(paused ? FILL_PAUSED : FILL);
            // -90 laipsniu: pradedam nuo virsaus, kaip laikrodyje
            canvas.drawArc(box, -90f, 360f * Math.min(progress, 1f), false, paint);
        }

        // Bruksniukai kas 10 % (V): be ju is ziedo matai tik "maždaug puse", o
        // su jais - "septyni is desimties". Piesiam PO progreso ir fono spalva,
        // tad tie patys tarpeliai dalija ir takeli, ir uzpildyta dali.
        //
        // Nulinio (virsutinio) NEPIESIAM: ten ziedo pradzia ir pabaiga, ir
        // tarpelis tik praplatintu ir taip esancia siule.
        paint.setColor(GAP);
        Paint.Cap senas = paint.getStrokeCap();
        paint.setStrokeCap(Paint.Cap.BUTT);
        for (int i = 1; i < DALYS; i++) {
            canvas.drawArc(box, -90f + i * (360f / DALYS) - GAP_DEG / 2f,
                    GAP_DEG, false, paint);
        }
        paint.setStrokeCap(senas);
    }
}
