# Unity Műhely

Android-app Unity játékcsomagok (APK, OBB) kibontására, szövegek és képek exportjára/importjára, újracsomagolásra és aláírásra.
Felület: Kotlin + Jetpack Compose. Motor: Python (Chaquopy) + UnityPy.

## Fordítás telefonról (számítógép nélkül)
1. Hozz létre egy GitHub repót, töltsd fel ennek a mappának a teljes tartalmát (a `.github` mappával együtt).
2. Actions fül → "APK építés" → Run workflow (vagy az első push automatikusan indítja).
3. Ha lefutott, az Artifacts alatt letöltheted az APK-t (zip-ben), telepítsd.
4. Első indításkor engedélyezd a teljes fájlhozzáférést, akkor a Dokumentumok/UnityMuhely mappában látod a projekteket.

## Munkamenet
Projekt (megnyitás, kibontás) → Scanner → Csere (export, szerkesztés, import) → Csomag (építés, aláírás, telepítés).

## Ismert korlátok
- Tömörített textúrák (ETC, ASTC) dekódolása nincs: a `texture2ddecoder` C-bővítmény Androidra nincs kész csomagban.
- Hang: exportnál WAV, ha nincs FMOD, nyers (FSB/OGG) fájl. Hang importja még nincs.
- IL2CPP `global-metadata.dat` és XAPK/split APK még nincs.
