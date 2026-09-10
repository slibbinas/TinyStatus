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
