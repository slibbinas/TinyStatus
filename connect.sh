#!/usr/bin/env bash
# Suranda laikrodi tinkle ir prisijungia. PORTO NURODYTI NEREIKIA.
#
# Wear OS belaidis derinimas skelbiasi per mDNS (_adb-tls-connect._tcp), tad
# adb pats zino ir adresa, ir kaskart pasikeitusi prievada. Uztenka, kad
# laikrodyje butu ijungtas Wireless debugging.
#
# Naudojimas:
#   bash tools/connect.sh          # prisijungti
#   bash tools/connect.sh --off    # atsijungti ir sustabdyti adb (sesijos pabaiga)

set -uo pipefail

SDK="${ANDROID_SDK:-/c/Users/SViktoras/dev-tools/android-sdk}"
ADB="$SDK/platform-tools/adb.exe"

if [ "${1:-}" = "--off" ]; then
  "$ADB" disconnect >/dev/null 2>&1
  "$ADB" kill-server >/dev/null 2>&1
  echo "Atsijungta, adb sustabdytas."
  echo "Dabar laikrodyje isjunk: Developer options -> Wireless debugging + ADB debugging."
  exit 0
fi

"$ADB" start-server >/dev/null 2>&1

# mDNS gali skelbti KELIS irasus: senus (nebegaliojancius) ir gyva. Imti pirma
# neuztenka - bandom visus, kol vienas prisijungia.
EPS=""
for i in 1 2 3 4 5 6 7 8 9 10; do
  EPS=$("$ADB" mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $NF}' | sort -u)
  [ -n "$EPS" ] && break
  sleep 1
done

if [ -z "$EPS" ]; then
  echo "Laikrodzio tinkle nematyti." >&2
  echo "Patikrink: (1) laikrodyje ijungtas Wireless debugging," >&2
  echo "           (2) laikrodis ir kompiuteris tame paciame Wi-Fi," >&2
  echo "           (3) ekranas nemiega (geriausia ant kroviklio su Stay awake)." >&2
  exit 1
fi

OK=""
for EP in $EPS; do
  if "$ADB" connect "$EP" 2>&1 | grep -q '^connected to\|already connected'; then
    OK="$EP"
    echo "Prisijungta: $EP"
    break
  fi
  echo "  ($EP neatsiliepe - pasenes irasas)"
done

if [ -z "$OK" ]; then
  echo "Nei vienas skelbiamas adresas neatsiliepe." >&2
  echo "Laikrodyje isjunk ir vel ijunk Wireless debugging." >&2
  exit 1
fi

"$ADB" devices -l | grep -v '^List' | grep . | head -3
