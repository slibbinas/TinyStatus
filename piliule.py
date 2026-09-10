# -*- coding: utf-8 -*-
"""Ilgas (slenkamas) ekranas - viena "piliule", kaip rodo Wear OS dokumentacija.

Kam: SETTINGS, COMFORT ir rezimu rinkiklis i apvalu ekrana NETELPA. Rodyti juos
viena nuotrauka reiskia rodyti treciadali; rodyti trimis - versti zmogu patiems
susidelioti. Piliule parodo visa turini kaip viena aukšta ekrana suapvalintais
galais - butent taip savo ilgus ekranus piesia ir Google.

Naudojimas:

    python tools/piliule.py isvestis.png kadras1.png kadras2.png ...

Nereikalingas vietas galima uzlieti (pvz. irenginio varda, kuris pas V yra
adresas): `--slepk x1,y1,x2,y2` (galima keliskart, koordinates - jau sulipdytoje
juostoje, pries deda parastes).

Kadrai - to paties lango nuotraukos slenkant NUO VIRSAUS ZEMYN, is TO PACIO
surinkimo. Persidengimas turi buti bent trecdalis ekrano: pagal ji ir randama
siule. Sulipdyta juosta apkerpama piliules forma ir dedama i balta fona.

Siule randama ne pagal zingsni, o pagal PANASUMA: kiekvienam poslinkiui
skaiciuojam vidutini skirtuma ir imam maziausia. Zingsniu pasitiketi negalima -
`input swipe` nuslenka kaskart kitaip, o Wear dar prideda inercija.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

TARPAS = 2               # px tarp kadru siules paieskos zingsniu
# Nuo NULIO: pasiekus lango apacia slinkimas sustoja, ir paskutiniai kadrai
# sutampa visiskai. Reikalaujant minimalaus zingsnio lipdytojas tokiu atveju
# ikisdavo desimtis pikseliu turinio, kurio nebuvo - butent taip "Temp."
# atsirasdavo dukart.
MIN_PERSIDENGIMAS = 0
NIEKO_NAUJO = 8          # px - toks poslinkis reiskia, kad kadras nieko neprideda


def _pilkai(im):
    return im.convert("L")


def poslinkis(a, b):
    """Per kiek pikseliu b nuslinkes zemyn nuo a. Grazina None, jei nerasta."""
    ap, bp = _pilkai(a), _pilkai(b)
    w, h = ap.size
    # Lyginam VIRSUTINE b juosta su kiekviena a vieta.
    #
    # Juosta AUKSTA (200 px, ne 90): nustatymu lange eilutes vienodos - vardas
    # ir jungiklis - ir zema juosta vienodai gerai sutampa su bet kuria ju.
    # Butent taip "Temp." atsirado dukart.
    juosta = 200
    bj = bp.crop((0, 0, w, juosta)).tobytes()
    kainos = []
    for d in range(MIN_PERSIDENGIMAS, h - juosta, TARPAS):
        aj = ap.crop((0, d, w, d + juosta)).tobytes()
        s = 0
        # Kas ketvirtas pikselis - uztenka, o greitis skiriasi kartais.
        for i in range(0, len(aj), 4):
            s += abs(aj[i] - bj[i])
        kainos.append((s / (len(aj) / 4.0), d))
    if not kainos:
        return None, None
    geriausia = min(k for k, _ in kainos)
    # Is visu, kurie tinka vienodai gerai, imam MAZIAUSIA poslinki. Kartojantis
    # turinys duoda kelis vienodai gerus atsakymus, ir teisingas ju yra tas,
    # kuris nuslenka maziau: didesnis reikstu, kad praleidom eilute.
    tinka = [d for k, d in kainos if k <= geriausia + 0.4]
    return min(tinka), geriausia


def sulipdyk(keliai):
    kadrai = [Image.open(k).convert("RGB") for k in keliai]
    w, h = kadrai[0].size
    juosta = kadrai[0].copy()
    for kitas in kadrai[1:]:
        d, kaina = poslinkis(juosta.crop((0, juosta.height - h, w, juosta.height)),
                             kitas)
        if d is None:
            raise SystemExit("Siule nerasta - ar kadrai tikrai persidengia?")
        if d < NIEKO_NAUJO:
            print("  kadras praleistas - nieko naujo (%d px)" % d)
            continue
        print("  siule ties %d px (skirtumas %.1f)" % (d, kaina))
        nauja = Image.new("RGB", (w, juosta.height - h + d + h), (0, 0, 0))
        nauja.paste(juosta, (0, 0))
        nauja.paste(kitas, (0, juosta.height - h + d))
        juosta = nauja
    return juosta


def piliule(juosta, parastes=30):
    """Suapvalinam galus ir dedam i balta fona."""
    w, h = juosta.size
    k = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(k)
    # Spinduliai - puse plocio: ekranas apvalus, tad galai turi buti apskritimai.
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=w // 2, fill=255)
    isv = Image.new("RGB", (w + parastes * 2, h + parastes * 2), (255, 255, 255))
    isv.paste(juosta, (parastes, parastes), k)
    return isv


def uzliek(juosta, sritys):
    """Uzliejam, o ne uzdazom: uzlieta vieta MATOSI kaip uzlieta, ir zmogus
    zino, kad ten kazkas buvo. Nudazyta juoda atrodytu kaip tuscia vieta."""
    for x1, y1, x2, y2 in sritys:
        sritis = juosta.crop((x1, y1, x2, y2))
        juosta.paste(sritis.filter(ImageFilter.GaussianBlur(12)), (x1, y1))
    return juosta


def main():
    argv = sys.argv[1:]
    sritys = []
    likutis = []
    i = 0
    while i < len(argv):
        if argv[i] == "--slepk":
            sritys.append(tuple(int(v) for v in argv[i + 1].split(",")))
            i += 2
        else:
            likutis.append(argv[i])
            i += 1
    if len(likutis) < 3:
        raise SystemExit(__doc__)
    isvestis, keliai = likutis[0], likutis[1:]
    print("lipdau is %d kadru" % len(keliai))
    juosta = sulipdyk(keliai)
    print("juosta: %dx%d" % juosta.size)
    if sritys:
        juosta = uzliek(juosta, sritys)
        print("uzlieta sriciu: %d" % len(sritys))
    piliule(juosta).save(isvestis)
    print(isvestis)


main()
