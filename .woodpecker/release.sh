#!/usr/bin/env bash
# Publica en GitHub la versión de app/build.gradle.kts si todavía no tiene release: firma, comprueba y sube el APK.
# Las notas salen de docs/versiones/v<versión>.md; la suma SHA-256 se añade aquí.
# En un pipeline manual es un ensayo: crea el borrador «v<versión>-ensayo» y no publica nada.
set -euo pipefail

version=$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)
tag="v$version"
notes="docs/versiones/$tag.md"
api="https://api.github.com/repos/$CI_REPO"
auth=(-H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json")

rehearsal=false
if [ "$CI_PIPELINE_EVENT" = manual ]; then rehearsal=true; tag="$tag-ensayo"; fi

code=$(curl -s -o /dev/null -w '%{http_code}' "${auth[@]}" "$api/releases/tags/$tag")
case "$code" in
  200) echo "$tag ya está publicada"; exit 0 ;;
  404) ;;
  *) echo "GitHub respondió $code al buscar $tag"; exit 1 ;;
esac
[ -f "$notes" ] || { echo "Falta $notes con las notas de la versión"; exit 1; }

mkdir -p "$HOME/.neotransfer"
printf '%s' "$SIGNING_KEYSTORE" | base64 -d > "$HOME/.neotransfer/release.jks"
{ echo "storeFile=$HOME/.neotransfer/release.jks"; printf '%s\n' "$SIGNING_PROPERTIES"; } > "$HOME/.neotransfer/signing.properties"
bash ./gradlew --no-daemon :app:assembleRelease
rm -rf "$HOME/.neotransfer"

apk=app/build/outputs/apk/release/app-release.apk
tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
certs=$("$tools/apksigner" verify --print-certs "$apk")
[[ $certs == *"certificate SHA-256 digest: $SIGNING_CERT_SHA256"* ]] || { echo "El APK no lleva la firma de siempre"; exit 1; }
badging=$("$tools/aapt2" dump badging "$apk")
[[ $badging == *"versionName='$version'"* ]] || { echo "El APK no es la versión $version"; exit 1; }
if [ -n "${MANIFEST_MUST_NOT_CONTAIN:-}" ] && grep -q "$MANIFEST_MUST_NOT_CONTAIN" \
  app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml; then
  echo "El manifiesto de release contiene $MANIFEST_MUST_NOT_CONTAIN"; exit 1
fi

asset="$CI_REPO_NAME-$version.apk"
sha=$(sha256sum "$apk" | cut -d' ' -f1)
body=$(cat "$notes"; printf '\n**SHA-256** `%s`\n`%s`\n' "$asset" "$sha")
json() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\t/\\t/g' -e 's/\r//g' | awk 'BEGIN { ORS = "\\n" } { print }'; }

release=$(curl -sf "${auth[@]}" -X POST "$api/releases" \
  -d "{\"tag_name\":\"$tag\",\"target_commitish\":\"$CI_COMMIT_SHA\",\"name\":\"$APP_NAME $version\",\"body\":\"$(json "$body")\",\"draft\":true}")
[[ $release =~ \"id\":\ *([0-9]+) ]] || { echo "GitHub no devolvió el release creado"; exit 1; }
id=${BASH_REMATCH[1]}
# Un borrador a medias no se queda: si algo falla antes de publicar, se borra.
trap 'curl -s "${auth[@]}" -X DELETE "$api/releases/$id" > /dev/null' ERR
curl -sf "${auth[@]}" -H "Content-Type: application/vnd.android.package-archive" --data-binary "@$apk" \
  "https://uploads.github.com/repos/$CI_REPO/releases/$id/assets?name=$asset" > /dev/null
if $rehearsal; then
  trap - ERR
  echo "Ensayo: borrador $tag con $asset ($sha)"
  exit 0
fi
curl -sf "${auth[@]}" -X PATCH "$api/releases/$id" -d '{"draft":false,"make_latest":"true"}' > /dev/null
trap - ERR
echo "Publicada $tag: $asset ($sha)"
