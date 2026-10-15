# Desarrollo

## Requisitos

- JDK 21. Sirve el que trae Android Studio (`C:\Program Files\Android\Android Studio\jbr` en Windows); el CI usa Temurin 21. El código compila para Java 17.
- Android SDK 36 (plataforma `android-36`, build-tools 36.0.0). Android Studio lo configura; en terminal basta `sdk.dir` en `local.properties`, que no se sube.
- El wrapper de Gradle incluido (Gradle 8.13). Versiones: AGP 8.13.2, Kotlin 2.2.21, Compose BOM 2026.04.01.

En PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

En Linux o macOS, `./gradlew` con las mismas tareas. Los módulos se describen en [arquitectura](arquitectura.md).

## Pruebas

### JVM

```powershell
.\gradlew.bat :core:test :scanner:testDebugUnitTest :app:testDebugUnitTest
```

- `:core:test`: contrato de acceso y tokens, solicitudes de pago y su cifrado, lectura del JSON de cuenta, tarjetas, saldos e historial, QR, servicios e importes.
- `:scanner:testDebugUnitTest`: lectura de números, QR en imágenes generadas y seguimiento entre cuadros.
- `:app:testDebugUnitTest`: transporte, sesión, API, pago de un solo envío, aprobación, la fachada `EnzonaRepository`, la fusión del historial y lo que muestran las pantallas.

Todas corren sin red: cada respuesta de ENZONA es sintética (un interceptor, `mockwebserver3` o una lambda), igual que los tokens. Ninguna prueba demuestra que el servidor acepte una operación.

### Capturas

```powershell
.\gradlew.bat :app:testPreviewUnitTest
```

`ScreenshotTest` dibuja con Robolectric y Roborazzi cada pantalla del catálogo (`app/src/preview/kotlin/dev/duardo/neotransfer/plus/preview/PreviewCatalog.kt`) en claro, oscuro y claro con letra al 150 %, en `app/build/screenshots/<pantalla>--<modo>.png`. Para regenerar solo algunas, filtra por prefijo del identificador:

```powershell
.\gradlew.bat :app:testPreviewUnitTest --tests '*ScreenshotTest*' -Pscreens=home,transfer,activity
```

Las imágenes del README (`docs/images/`) son `home`, `transfer-review` y `activity` en claro, reducidas a 1080 px de alto.

### En el teléfono

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest :scanner:connectedDebugAndroidTest
```

Necesitan un teléfono con Android 15 o posterior conectado por ADB, o un emulador ARM de 64 bits: todas las variantes traen bibliotecas nativas solo para `arm64-v8a`. Prueban Room en memoria, la sesión cifrada con el Android Keystore y ML Kit con imágenes generadas. No hablan con ENZONA.

El CI (`.github/workflows/android.yml`) ejecuta `:core:test :scanner:testDebugUnitTest :app:testDebugUnitTest :app:testPreviewUnitTest :app:assembleDebug :app:lintDebug`.

## Variantes

| Variante | Paquete | Uso |
|---|---|---|
| `debug` | `dev.duardo.neotransfer.plus.debug` | Desarrollo con una cuenta ENZONA real. Se instala al lado de la versión publicada |
| `preview` | `dev.duardo.neotransfer.plus.preview` | Como `debug`, más `PreviewActivity`: el catálogo de pantallas con datos inventados |
| `release` | `dev.duardo.neotransfer.plus` | La que se publica: R8 con reducción de recursos |

`PreviewActivity` abre una pantalla del catálogo; sin `screen`, lista todas:

```sh
adb shell am start -n dev.duardo.neotransfer.plus.preview/dev.duardo.neotransfer.plus.preview.PreviewActivity --es screen home --es theme DARK --ef fontScale 1.5
```

### `release` y firma

La firma vive fuera del repositorio, en `%USERPROFILE%\.neotransfer\signing.properties` (en Linux o macOS, `~/.neotransfer/signing.properties`):

```properties
storeFile=<ruta al almacén de claves>
storePassword=…
keyAlias=…
keyPassword=…
```

Sin ese archivo, `assembleRelease` produce un APK sin firmar. Todas las versiones se firman con la misma clave para que se instalen encima de la anterior; consérvala junto con su copia.

```powershell
.\gradlew.bat :core:test :scanner:testDebugUnitTest :app:testDebugUnitTest :app:testPreviewUnitTest :app:assembleRelease :app:lintRelease
Get-FileHash app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```

La suma SHA-256 va en las notas de cada versión. Antes de publicar, comprueba que el manifiesto fusionado (`app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`) no contiene `TransportBackendDiscovery` ([por qué](arquitectura.md#escáner)).

## Fondos de tarjeta

`app/src/main/res/drawable-nodpi/card_bpa_red.png` y `card_bandec_red.png` son PNG de 1579 × 996 píxeles, RGB sin transparencia y a sangre: la app redondea las esquinas. Son los mismos de NeoTransfer, exportados de reconstrucciones vectoriales hechas a partir de fotos; el vector de origen no está en el repositorio.

Número, titular, moneda y vencimiento los dibuja la app en las posiciones de `BpaPrint` y `BandecPrint` (`app/src/main/kotlin/dev/duardo/neotransfer/plus/ui/common/Cards.kt`), en fracciones del ancho y del alto de la tarjeta: si el arte cambia, recalíbralas ahí y revisa las capturas.

## Contribuir

Ver [CONTRIBUTING](../CONTRIBUTING.md).
