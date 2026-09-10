# -*- coding: utf-8 -*-
"""TinyStatus zenkliukai - Material Symbols is Google material-design-icons.

Licencija Apache 2.0. Saltinis:
  https://github.com/google/material-design-icons
  symbols/web/<vardas>/materialsymbolsoutlined/<vardas>_24px.svg

Material Symbols SVG naudoja viewBox "0 -960 960 960" - Y asis neigiama.
VectorDrawable tokio neturi, todel kelias apgaubiamas <group> su
translateY="960": tai perkelia -960..0 i 0..960 nieko neiskraipant.

NERASYTA RANKA - res/drawable failus sukuria sis skriptas.

Paleidimas:  python ikonos.py
"""
import io
import os

KELIAI_TS = {
    "ic_ts_print": ("print",
                    "M640-640v-120H320v120h-80v-200h480v200h-80Zm-480 80h640-640Zm560 100q17 "
                    "0 28.5-11.5T760-500q0-17-11.5-28.5T720-540q-17 0-28.5 11.5T680-500q0 17 "
                    "11.5 28.5T720-460Zm-80 260v-160H320v160h320Zm80 80H240v-160H80v-240q0-51 "
                    "35-85.5t85-34.5h560q51 0 85.5 34.5T880-520v240H720v160Zm80-240v-160q0-17"
                    "-11.5-28.5T760-560H200q-17 0-28.5 11.5T160-520v160h80v-80h480v80h80Z"),
    "ic_ts_timer": ("timer",
                    "M360-840v-80h240v80H360Zm80 440h80v-240h-80v240Zm40 320q-74 0-139.5-28.5"
                    "T226-186q-49-49-77.5-114.5T120-440q0-74 28.5-139.5T226-694q49-49 "
                    "114.5-77.5T480-800q62 0 119 20t107 58l56-56 56 56-56 56q38 50 58 107t20 "
                    "119q0 74-28.5 139.5T734-186q-49 49-114.5 77.5T480-80Zm0-80q116 0 "
                    "198-82t82-198q0-116-82-198t-198-82q-116 0-198 82t-82 198q0 116 82 "
                    "198t198 82Zm0-280Z"),
    "ic_ts_refresh": ("refresh",
                      "M480-160q-134 0-227-93t-93-227q0-134 93-227t227-93q69 0 132 28.5T720-690v"
                      "-110h80v280H520v-80h168q-32-56-87.5-88T480-720q-100 0-170 70t-70 170q0 100 "
                      "70 170t170 70q77 0 139-44t87-116h84q-28 106-114 173t-196 67Z"),
    "ic_ts_drop": ("water_drop",
                   "M491-200q12-1 20.5-9.5T520-230q0-14-9-22.5t-23-7.5q-41 3-87-22.5T343-375q-2"
                   "-11-10.5-18t-19.5-7q-14 0-23 10.5t-6 24.5q17 91 80 130t127 35ZM480-80q-137 "
                   "0-228.5-94T160-408q0-100 79.5-217.5T480-880q161 137 240.5 254.5T800-408q0 "
                   "140-91.5 234T480-80Zm0-80q104 0 172-70.5T720-408q0-73-60.5-165T480-774Q361"
                   "-665 300.5-573T240-408q0 107 68 177.5T480-160Zm0-320Z"),
}

SABLONAS = u'''<?xml version="1.0" encoding="utf-8"?>
<!--
  Material Symbols "%s" is Google material-design-icons (Apache License 2.0).
  NERASYTAS RANKA - sukuria tools/material_ikonos.py.

  <group translateY="960"> todel, kad Material Symbols piesia neigiamoje Y
  puseje (viewBox "0 -960 960 960"), o VectorDrawable tokio viewBox neturi.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="18dp"
    android:height="18dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
    <group android:translateY="960">
        <path
            android:pathData="%s"
            android:fillColor="#FFFFFF" />
    </group>
</vector>
'''


def main():
    kur = os.path.join(os.path.dirname(os.path.abspath(__file__)), "res", "drawable")
    for failas, (vardas, kelias) in KELIAI_TS.items():
        p = os.path.join(kur, failas + ".xml")
        io.open(p, "w", encoding="utf-8", newline=chr(10)).write(SABLONAS % (vardas, kelias))
        print(p)


if __name__ == "__main__":
    main()
