# -*- coding: utf-8 -*-
"""TinyStatus GIF: visi programeles ekranai vienoje juostoje.

Nuotraukos - TIKROS, is laikrodzio (`adb exec-out screencap -p`), sudetos i
`build/doc_ts/`. Nieko cia nepiesiama is naujo: GIF turi rodyti tai, kas is
tikruju yra ekrane, o ne tai, kaip norejosi.

Kadru saltiniai (vardai turi sutapti su tuo, kas nufotografuota):

    ciferblatas     ciferblatas su trimis komplikacijomis (derva, laikas, ziedas)
    g1_main         pagrindinis ekranas, spausdinama
    g9_byname_on    nustatymai: PRINTER, "By name" IJUNGTAS (randa pagal varda)
    g3_printer      nustatymai: PRINTER, "By name" ISJUNGTAS (du IP)
    ipl             IP ivedimo langas
    g4_background   nustatymai: BACKGROUND intervalai
    g6_alerts       nustatymai: ALERTS jungikliai
    g2_pr2          antras spausdintuvas, OFFLINE
    g11_done        spausdinimas baigtas (DONE) - programeles isvesta busena,
                    nes API apie pabaiga nepraneša

Paleidimas:  python tools/tinystatus/dokumentas.py
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from PIL import Image
import laikrodzio_korpusas as korp

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
DOC = os.path.join(ROOT, "build", "doc_ts")
FONAS = "#ffffff"


def ekranas(vardas, dydis):
    """Nuotrauka laikrodzio korpuse, ant balto fono."""
    p = os.path.join(DOC, vardas + ".png")
    if not os.path.isfile(p):
        raise SystemExit("Truksta kadro: %s" % p)
    e = korp.korpusas(Image.open(p), dydis)
    im = Image.new("RGB", (dydis, dydis), FONAS)
    im.paste(e, (0, 0), e)
    return im


def gif(kelias, dydis=300, spalvos=96):
    kadrai, trukmes = [], []

    def laikyk(im, ms=1700):
        kadrai.append(im)
        trukmes.append(ms)

    def slinkti(a, b, vertikaliai=False, n=4):
        """Perejimo iliuzija: du ekranai prastumiami vienas pro kita."""
        for i in range(1, n + 1):
            k = Image.new("RGB", (dydis, dydis), FONAS)
            d = int(dydis * i / (n + 1.0))
            if vertikaliai:
                k.paste(a, (0, -d))
                k.paste(b, (0, dydis - d))
            else:
                k.paste(a, (-d, 0))
                k.paste(b, (dydis - d, 0))
            kadrai.append(k)
            trukmes.append(90)

    # Istorija ta pati, kaip ranka: pirma pamatai, kas vyksta, tada nueini i
    # nustatymus ir grizti.
    eiga = [
        # Pradzia - ciferblatas: nuo jo viskas ir prasideda, nes ten skaicius
        # matai nepakeles piršto. Programele - kitas zingsnis, ne pirmas.
        ("ciferblatas", False, 2400),
        ("g1_main", False, 2100),        # kas vyksta dabar
        ("g9_byname_on", True, 1800),    # nustatymai: randa pagal varda
        ("g3_printer", False, 1800),     # ... arba du IP ranka
        ("ipl", False, 1700),            # IP ivedimas
        ("g4_background", True, 1700),   # fono intervalas
        ("g6_alerts", True, 1700),       # kurie ivykiai zadina
        ("g2_pr2", False, 1700),         # antras spausdintuvas tyli
        # Pabaiga. Sitos busenos API neturi - ja isveda pati programele is
        # dvieju gretimu apklausu skirtumo, todel ji ir verta parodyti.
        ("g11_done", False, 2400),
    ]
    buves = None
    for vardas, vert, ms in eiga:
        dab = ekranas(vardas, dydis)
        if buves is not None:
            slinkti(buves, dab, vert)
        laikyk(dab, ms)
        buves = dab

    # VIENA palete visiems kadrams. Kiekvienam kadrui susikuriant savo, GIF
    # nesa desimtis paleciu ir uzima dvigubai daugiau. Palete renkam is VISO
    # filmo, ne is pirmo kadro - kitaip oranzinis ziedas nudaztu rusvai.
    pavyzdys = Image.new("RGB", (dydis, dydis * len(kadrai[::4])))
    for i, k in enumerate(kadrai[::4]):
        pavyzdys.paste(k, (0, i * dydis))
    palete = pavyzdys.quantize(colors=spalvos, method=Image.MEDIANCUT)
    mazi = [k.quantize(palette=palete, dither=Image.FLOYDSTEINBERG) for k in kadrai]
    mazi[0].save(kelias, save_all=True, append_images=mazi[1:],
                 duration=trukmes, loop=0, optimize=True, disposal=2)
    print("%s  (%d kadrai, %d B)" % (kelias, len(kadrai), os.path.getsize(kelias)))


if __name__ == "__main__":
    gif(os.path.join(DOC, "tinystatus.gif"))
