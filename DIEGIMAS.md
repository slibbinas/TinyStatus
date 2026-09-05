# TinyStatus — diegimas į laikrodį

Wear OS programėlė, rodanti **TinyMakerWiFi** spausdintuvo būseną: ką spausdina,
kelintą sluoksnį, kiek liko laiko, kiek dervos, ir progreso žiedą aplink ekraną.
Pasibaigus — ką atspausdino ir prieš kiek laiko.

Google Play jos nėra, tad diegiama **sideload** būdu. Yra du keliai; pirmasis
nereikalauja kompiuterio.

## Ko reikia

- Wear OS 4+ laikrodis (tikrinta su Galaxy Watch 8)
- **TinyMakerWiFi firmware 0.11.0 arba naujesnė.** Senesnėse (nuo 0.8.4) veiks
  viskas, išskyrus žemos dervos perspėjimą — API tuomet dar neturėjo `vatLow`
- Laikrodis ir spausdintuvas **tame pačiame Wi-Fi**

## Kelias A: telefonu, be kompiuterio

1. **Laikrodyje:** `Settings → About watch → Software` — penkis kartus bakstelėk
   `Software version`, kol atsiras `Developer options`
2. Ten įjunk **`ADB debugging`** ir **`Wireless debugging`**
3. **Telefone** įsidiek diegiklį, kuris moka `adb` per Wi-Fi (pvz. *Wear
   Installer 2*)
4. Laikrodyje atidaryk `Wireless debugging → Pair new device` — pamatysi kodą
5. Telefone suporuok tuo kodu ir pasirink atsisiųstą `tinystatus.apk`
6. **Baigus išjunk abu derinimo jungiklius** — kitaip laikrodis laikys nuolatinį
   „Debugging enabled" pranešimą ir vibruos kaskart nutrūkus ryšiui

## Kelias B: kompiuteriu

Reikia `adb` (Android SDK platform-tools). Laikrodyje tie patys 1–2 žingsniai.

```bash
adb pair <laikrodzio-ip>:<poravimo-portas>     # kodą rodo laikrodis
adb connect <laikrodzio-ip>:<portas>
adb install -r tinystatus.apk
```

Atnaujinant naudok **`install -r`** — jis atnaujina vietoje.

## Ką programėlė daro

- Kas 5 sekundes klausia `GET /api/status`, **tik kol į ją žiūri**. Uždarius —
  jokių fono darbų, tad baterijos neėda
- Bakstelėjimas — atnaujina iš karto
- Su spausdintuvu kalbasi **tik vietiniame tinkle**; niekur nieko nesiunčia,
  jokių paskyrų, jokios telemetrijos
- Vienintelis leidimas — `INTERNET` (plius `ACCESS/CHANGE_NETWORK_STATE`, kad
  galėtų paprašyti būtent Wi-Fi tinklo)

## Jei rodo `OFFLINE`

1. **Ar laikrodyje įjungtas Wi-Fi?** Dažnai jis eina per telefoną Bluetooth'u, o
   tada spausdintuvo nepasiekia. Programėlė specialiai prašo Wi-Fi tinklo, bet
   jis turi būti įjungtas
2. **Ar spausdintuvas atsiliepia?** Patikrink naršyklėje: `http://tinymaker.lan`
3. **Vardas.** Programėlė bando `tinymaker.lan`, `tinymaker` ir `tinymaker.local`
   iš eilės. Jei tavo tinkle spausdintuvas vadinasi kitaip, reikės pakeisti
   `HOSTS` sąrašą `MainActivity.java`

Trumpas neatsakymas nėra klaida: spausdintuvą užima įkėlimai ir SD darbai, tad
programėlė 45 sekundes rodo senas reikšmes su prierašu `no answer Ns`, ir tik
paskui skelbia `OFFLINE`.

## Surinkimas iš šaltinio

```bash
bash tools/tinystatus/build.sh
```

Reikia JDK ir Android build-tools; Gradle **nereikia**. Versija imama iš git
commit'ų skaičiaus, tad `versionCode` savaime didėja — be to naudotojai
negalėtų atsinaujinti.
