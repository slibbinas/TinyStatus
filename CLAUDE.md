# TinyStatus

Wear OS programėlė **TinyMakerWiFi** spausdintuvui: būsena ant riešto ir
komplikacijos ciferblate.

**Su ciferblatu (`VDigi`) šis projektas nesusijęs.** Iki 2026-09-10 kodas gulėjo
ten, ir tai buvo laikina. Jei sesija atsidūrė ciferblato kataloge, o kalba eina
apie spausdintuvą - dirbama **čia**.

Spausdintuvo firmware gyvena atskirai:
<https://github.com/slibbinas/TinyMakerWifi>. Jo neliečiam.

## Ribos

- **Laikrodis yra V daiktas ant rankos, ne stendas.** Į jį per `adb` - nieko be
  atskiro V leidimo: nei diegimo, nei nuotraukų, nei bakstelėjimų.
- **GRIEŽTA: prieš KIEKVIENĄ bakstelėjimą ar braukimą - nuotrauka ir pažiūrėti,
  kas ekrane.** Ne prieš seriją, o prieš kiekvieną. Aklas `input tap` nukeliauja
  ten, kur nesitiki - taip jau pataikyta į sistemos nustatymus ir ciferblatų
  rinkiklį.
- **Diegimas - `install -r`**, ne švarus.
- **Sesijos pabaigoje** priminti V išjungti `Wireless debugging` (vieno jungiklio
  užtenka, atskiro `ADB debugging` nereikia).
- **Į git nededama:** parašo raktas (`keystore/`).
- **Įrenginio kalba - anglų.** Dokumentai ir kodo komentarai lietuviškai.
- **Ilgųjų brūkšnių tekste nenaudojam** - visur paprastas minusas.

## Kaip čia dirbama

**Matuok, nespėk.** Kiekviena šio projekto brangiausia klaida kilo iš to, kad
prielaida buvo laikoma faktu. Du pavyzdžiai, abu užrašyti kode:

- „per telefoną vietinis tinklas pasiekiamas" - **neteisinga**, ir tai paaiškėjo
  tik išjungus Wi-Fi ir pažiūrėjus į ekraną;
- „Bluetooth kelias neveikia, nes `.local` neišsisprendžia" - išvada buvo
  teisinga, bet **dėl neteisingos priežasties** (tai mDNS, ne maršrutas).

**Kodėl rašom į kodą, ne tik į komitą.** Komentaruose gyvena priežastys: kodėl
`setSmallImage` nutildo ikoną, kodėl ternarinė sąlyga, kodėl fone debesis pirma.
Tas, kas skaitys po pusmečio, komitų neieškos.

**Vizualų pakeitimą pirma rodyti maketu**, tik paskui diegti.

## Ženklai ir generuojami failai

`res/drawable/ic_ts_*.xml` **nerašomi ranka** - juos sukuria `ikonos.py`.
Taisyti reikia generatoriaus.

## Kas ką daro

| Failas | Kas |
|---|---|
| `TsSaltinis.java` | spausdintuvai, skaitymas, atmintinė, tinklo pasirinkimas |
| `TsBusena.java` | `/api/status` laukai; ten pat surašyta, kurie ką reiškia |
| `TsPranesimas.java` | būsenos mašina; **vienintelis** pranešimų rašytojas |
| `TsSargas.java` | fono sargas; dirba TIK kol spausdinama |
| `TsKompl.java` | komplikacijų teikėjai; tinklo NELIEČIA |
| `MainActivity.java` | ekranas, gestai, nustatymų langai |
| `TsAtnaujink.java` | atsinaujinimas iš GitHub laidos: `Check for updates` → `Update 0.1.xxx` |

## Laidos (nuo 0.1.303 nuo jų priklauso atsinaujinimas programėlėje)

Įdiegusieji naujas versijas gauna per mygtuką, tad laida dabar yra ir
programėlės duomenys, ne tik atsisiuntimo puslapis:

- **Žymė ir pavadinimas tik `0.1.<versionCode>`.** `TsAtnaujink` versiją skaito
  iš paskutinio žymės skaičiaus - kitokia žymė ir programėlė naujos nepamatys.
- **Laida `Latest` su vienu `.apk` priedu.** Skaitomas `releases/latest`, imamas
  pirmas `.apk`; prerelease nematomas.
- **`versionCode` tik didėja, `VERSION_BASE` tik didinamas.** Jei laikrodyje
  stovi rankiniu `VERSION_CODE` surinkta bandomoji versija, kita laida turi būti
  už ją didesnė (2026-09-11 dėl to bazė pakelta 254 -> 264).
- **Tas pats raktas.** Kitu raktu pasirašytos laidos atnaujinimas nepraeis.
- **Iš manifesto neišimti** `REQUEST_INSTALL_PACKAGES` ir
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION`: be antrojo kiekvienas atnaujinimas vėl
  rodys sistemos langą „Do you want to update this app?" (išmatuota).
- **Surinkimo sėkmė tikrinama pagal klaidos kodą**, ne pagal išvesties eilutes:
  2026-09-11 nesusirenkantis kodas taip pateko į commit'ą.

## Paveiksleliai dokumentuose

**Kiekviena laikrodzio nuotrauka rodoma SU KORPUSO REMELIU** (V, 2026-09-10).
Remeli uzdeda `laikrodzio_korpusas.korpusas()`: jis apkerpa kvadratini
`screencap` iki apskritimo ir apibrezia Galaxy Watch korpusa su skale.

Kodel tai svarbu: plika ekrano ispjova atrodo kaip maketas, o su remeliu - kaip
laikrodis ant riesto. Galioja `README`, laidu aprasams ir GIF'ams vienodai.

**Rezultatai gula i `docs/`, NE i `build/`.** `build/` yra valomas katalogas, ir
`build.sh` ji trina - 2026-09-10 taip dingo visas GIF'as kartu su desimtimi
tikru laikrodzio nuotrauku.
