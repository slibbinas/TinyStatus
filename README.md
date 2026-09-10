# TinyStatus

Wear OS programėlė, rodanti **[TinyMakerWiFi](https://github.com/slibbinas/TinyMakerWifi)**
spausdintuvo būseną ant riešto ir ciferblate.

Kiek liko laiko, kelintas sluoksnis, kiek dervos vonelėje, progreso žiedas.
Fone gali sekti spausdinimą ir pranešti, kai jis baigiasi arba baigiasi derva.
Reikšmes galima įsidėti į ciferblatą kaip komplikacijas - tada matai jas
nepakėlęs piršto.

Diegimas ir naudojimas: **[INSTALL.md](INSTALL.md)**.
Laidos: [Releases](https://github.com/slibbinas/TinyStatus/releases).

## Kodėl atskira repozitorija

Iki 2026-09-10 kodas gyveno `vdigi` (ciferblato) repozitorijoje, o laidos buvo
skelbiamos `TinyMakerWifi`. Abu dalykai buvo laikini ir kliuvo:

- ciferblatas su spausdintuvu neturi nieko bendro;
- `TinyMakerWifi` yra PlatformIO projektas, ir Android grandinė ten terštų
  firmware CI, o laidos gulėjo be savo šaltinio.

Istorija perkelta su kodu (`git subtree split`), tad kiekvieno sprendimo
paaiškinimas išliko - ten guli visi „kodėl", kurie kainavo po pusdienį.

## Ką verta žinoti prieš skaitant kodą

**Spausdintuvas apie pabaigą nepraneša.** `/api/status` tiesiog nustoja
rodyti `busy`, o `model` ištuštėja. Pabaigą programėlė **išveda pati** iš
dviejų gretimų apklausų skirtumo, ir iš to seka visas `TsPranesimas` sluoksnis
su būsenos mašina.

**Procentų API neduoda** - progresas skaičiuojamas iš sluoksnių.

**Komplikacijos tinklo neliečia.** Sistema jų klausia kaskart pakėlus ranką;
jei jos skaitytų pačios, kiekvienas rankos pakėlimas virstų Wi-Fi radijo
kėlimu. Jos atsako iš atmintinės, o šviežumą atneša fono sargas.

**Ekrano eilučių plotis skaičiuojamas iš apskritimo lygties**, ne parenkamas
iš akies - kitaip tekstas lenda po žiedu.

## Failai

| | |
|---|---|
| `src/.../TsSaltinis.java` | spausdintuvų sąrašas, skaitymas, atmintinė |
| `src/.../TsBusena.java` | `/api/status` laukai ir jų prasmė |
| `src/.../TsPranesimas.java` | būsenos mašina ir pranešimai |
| `src/.../TsSargas.java` | fono sargas (foreground servisas + tikslūs alarmai) |
| `src/.../TsKompl.java` | keturi komplikacijų teikėjai |
| `build.sh` | surenka pasirašytą APK **be Gradle** |
| `ikonos.py` | ženkliukai (Material Symbols, Apache 2.0) |
| `dokumentas.py` | GIF iš tikrų laikrodžio nuotraukų |

## Surinkimas

```bash
bash build.sh
```

Reikia JDK ir Android build-tools; **Gradle nereikia**. Pirmą kartą
parsisiunčiama viena 700 KB Google biblioteka (`wearable:2.9.0`) -
komplikacijų teikėjo bazinė klasė gyvena ten.

Parašo raktas į git nededamas. Numatytoji jo vieta -
`../keystore/vdigi.keystore`, keičiama per `WEAR_KEYSTORE`. **Keisti raktą
negalima:** kitu raktu pasirašyto APK naudotojas nebegalėtų įdiegti virš seno.
