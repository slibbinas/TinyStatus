# -*- coding: utf-8 -*-
"""TinyStatus GIF: ekranai viena juosta.

Nuotraukos TIKROS, is laikrodzio (`adb exec-out screencap -p`), sudetos i
`docs/img/`. NE i `build/` - tas katalogas valomas, ir 2026-09-10 taip dingo
visas ankstesnis GIF'as kartu su nuotraukomis.

Kadrai apgaubiami korpuso remeliu: plika ekrano ispjova atrodo kaip maketas,
o su remeliu - kaip laikrodis ant riesto.

TRUKSTA VYKSTANCIO SPAUSDINIMO. Sio GIF'o kadrai nufotografuoti tada, kai
spausdintuvas jau buvo baigęs, tad pagrindinis ekranas rodo `DONE`. Kai kita
karta kas nors spausdins, uztenka nufotografuoti viena ekrana i
`docs/img/t_spausdina.png` - jis bus paimtas automatiskai ir atsidurs pirmas.

Paleidimas:  python gifas.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PIL import Image

import laikrodzio_korpusas as korp

ROOT = os.path.dirname(os.path.abspath(__file__))
IMG = os.path.join(ROOT, "docs", "img")
FONAS = "#ffffff"

# (failas, kiek laikyti ms, ar butinas)
EIGA = [("t_spausdina", 2200, False),   # vykstantis spausdinimas, jei turim
        ("t0", 2200, True),             # pabaiga: DONE, kiek sluoksniu, kiek dervos
        ("t1", 1800, True),             # nustatymai: PRINTER
        ("t2", 1800, True),             # ...rastas spausdintuvas ir BACKGROUND
        ("t3", 1700, True),
        ("t4", 1700, True),             # intervalai ir ALERTS
        ("t5", 1800, True)]             # jungikliai


def kadras(vardas, dydis):
    p = os.path.join(IMG, vardas + ".png")
    if not os.path.isfile(p):
        return None
    e = korp.korpusas(Image.open(p), dydis)
    k = Image.new("RGB", (dydis, dydis), FONAS)
    k.paste(e, (0, 0), e)
    return k


def gif(kelias, dydis=340, spalvos=128):
    kadrai, trukmes = [], []

    def slinkti(a, b, n=5):
        for i in range(1, n + 1):
            k = Image.new("RGB", (dydis, dydis), FONAS)
            d = int(dydis * i / (n + 1.0))
            k.paste(a, (-d, 0))
            k.paste(b, (dydis - d, 0))
            kadrai.append(k)
            trukmes.append(80)

    buves = None
    for vardas, ms, butinas in EIGA:
        dab = kadras(vardas, dydis)
        if dab is None:
            if butinas:
                raise SystemExit("Truksta kadro: %s.png" % vardas)
            continue
        if buves is not None:
            slinkti(buves, dab)
        kadrai.append(dab)
        trukmes.append(ms)
        buves = dab
    slinkti(buves, kadrai[0])

    pavyzdys = Image.new("RGB", (dydis, dydis * len(kadrai[::3])))
    for i, k in enumerate(kadrai[::3]):
        pavyzdys.paste(k, (0, i * dydis))
    palete = pavyzdys.quantize(colors=spalvos, method=Image.MEDIANCUT)
    mazi = [k.quantize(palette=palete, dither=Image.FLOYDSTEINBERG) for k in kadrai]
    mazi[0].save(kelias, save_all=True, append_images=mazi[1:],
                 duration=trukmes, loop=0, optimize=True, disposal=2)
    print("%s  (%d kadrai, %d B)" % (kelias, len(kadrai), os.path.getsize(kelias)))


if __name__ == "__main__":
    gif(os.path.join(IMG, "tinystatus.gif"))
