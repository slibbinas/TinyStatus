# -*- coding: utf-8 -*-
"""Galaxy Watch 8 Classic korpusas aplink ekrano nuotrauka.

Kam: nuotrauka is 'adb screencap' yra kvadratas su juodais kampais, o ekranas
apvalus. Dokumente toks kvadratas atrodo kaip pusgaminis. Cia ji apkerpam iki
apskritimo ir apvedziam korpusu su besisukanciu bezeliu - kaip Classic modelyje.

Tai PIESINYS, ne nuotrauka: korpusas schematiskas, bet proporcijos paimtos is
tikro laikrodzio (46 mm korpusas, 1,34 colio ekranas -> ekranas uzima ~74 %
skersmens).
"""
import math

EKRANO_DALIS = 0.78          # ekrano skersmuo nuo viso korpuso


def korpusas(ekranas, dydis=560, spalva=(198, 200, 205), tamsi=(96, 99, 106)):
    """ekranas - PIL paveikslas (kvadratinis screencap). Grazina RGBA."""
    from PIL import Image, ImageDraw
    K = 4                                   # piesiam 4x, mazinam - svelnus krastai
    D = dydis * K
    im = Image.new("RGBA", (D, D), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    # Korpuso ziedas: keli sluoksniai vietoj gradiento - uztenka, kad metalas
    # neatrodytu plokscias.
    for i, (r, c) in enumerate((
            (0.500, tamsi),
            (0.492, spalva),
            (0.474, tuple(int(v * 0.82) for v in spalva)),
            (0.462, spalva))):
        d.ellipse([D * (0.5 - r), D * (0.5 - r), D * (0.5 + r), D * (0.5 + r)], fill=c + (255,))

    # Bezelio zymos - Classic pozymis. Kas 5 laipsniai, kas 30 - ilgesne.
    rv, ra = 0.492, 0.470
    for a in range(0, 360, 5):
        ilga = (a % 30 == 0)
        r2 = ra if ilga else (ra + 0.008)
        t = math.radians(a - 90)
        d.line([D * (0.5 + rv * math.cos(t)), D * (0.5 + rv * math.sin(t)),
                D * (0.5 + r2 * math.cos(t)), D * (0.5 + r2 * math.sin(t))],
               fill=tamsi + (255,), width=int(1.8 * K) if ilga else int(1.0 * K))

    # Juodas remelis apie ekrana
    rb = EKRANO_DALIS / 2 + 0.018
    d.ellipse([D * (0.5 - rb), D * (0.5 - rb), D * (0.5 + rb), D * (0.5 + rb)],
              fill=(12, 12, 14, 255))

    # Ekranas - apkirptas iki apskritimo
    e = int(D * EKRANO_DALIS)
    ek = ekranas.convert("RGB").resize((e, e), Image.LANCZOS)
    kauke = Image.new("L", (e, e), 0)
    ImageDraw.Draw(kauke).ellipse([0, 0, e - 1, e - 1], fill=255)
    im.paste(ek, (int((D - e) / 2), int((D - e) / 2)), kauke)

    # Du mygtukai DESINIAME KRASTE, ne ekrano viduryje: pirmoji versija juos
    # nupiese ties centru, nes 0.5 yra vidurys, o ne krastas.
    for y0, y1 in ((0.355, 0.425), (0.575, 0.645)):
        d.rounded_rectangle([D * 0.962, D * y0, D * 0.998, D * y1],
                            radius=int(0.010 * D), fill=spalva + (255,))
        d.rounded_rectangle([D * 0.968, D * (y0 + 0.006), D * 0.992, D * (y1 - 0.006)],
                            radius=int(0.008 * D),
                            fill=tuple(int(v * 0.78) for v in spalva) + (255,))
    return im.resize((dydis, dydis), Image.LANCZOS)
