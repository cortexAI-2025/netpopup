#!/usr/bin/env bash
# Rapport de taille d'un ou plusieurs APK : taille totale, répartition par dossier, plus gros éléments.
set -euo pipefail
for apk in "$@"; do
  echo "=== $(basename "$apk") : $(stat -c %s "$apk") octets ($(python3 -c "import os,sys;print(round(os.path.getsize(sys.argv[1])/1048576,2))" "$apk") Mio) ==="
  echo "--- par dossier (taille compressée dans l'APK, Kio) ---"
  unzip -lv "$apk" | awk 'NR>3 && $NF!="" && $1 ~ /^[0-9]+$/ {n=$NF; sub(/\/.*/,"",n); if (n ~ /\.dex$/) n="classes*.dex"; if (n=="resources.arsc") n="resources.arsc"; s[n]+=$3} END {for (k in s) printf "%10.0f  %s\n", s[k]/1024, k}' | sort -rn
  echo "--- 25 plus gros éléments (compressé, Kio) ---"
  unzip -lv "$apk" | awk 'NR>3 && $1 ~ /^[0-9]+$/ {printf "%10.0f  %s\n", $3/1024, $NF}' | sort -rn | head -25
  echo
done
