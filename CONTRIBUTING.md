# Contribuir a NeoTransfer+

Se aceptan correcciones, pruebas, accesibilidad, documentación y contratos respaldados por evidencia. Para una función nueva, abre antes un issue con el problema, el comportamiento esperado y de dónde sale el contrato de ENZONA que necesita.

## Preparar el proyecto

Necesitas JDK 21 (sirve el de Android Studio) y Android SDK 36. Abre el repositorio en Android Studio o pon la ruta del SDK en `local.properties`. La firma de producción es privada y no hace falta para `debug` ni `preview`.

Lo mismo que comprueba el CI:

```sh
./gradlew :core:test :scanner:testDebugUnitTest :app:testDebugUnitTest :app:testPreviewUnitTest :app:assembleDebug :app:lintDebug
```

En Windows, `.\gradlew.bat`. Las capturas y las pruebas en el teléfono están en [desarrollo](docs/desarrollo.md#pruebas).

## Proponer un cambio

- Mantén el cambio enfocado, contra `dev`, y conserva los avisos de licencia de terceros.
- Acompaña un cambio de contrato con su fuente y con casos de éxito, rechazo y respuesta ambigua o ausente. Un pago se envía una sola vez: lo que no se confirma queda «Sin confirmar» y nunca se reenvía solo.
- Usa datos inventados. No incluyas tokens, contraseñas, respuestas reales de ENZONA, números de tarjeta reales, APK de terceros ni material de firma.
- Describe en el PR qué cambió, qué ejecutaste y qué quedó sin comprobar. Una prueba local no demuestra que ENZONA acepte la operación.
- Si cambias una pantalla, revisa sus capturas en claro, oscuro y con letra al 150 % (`ScreenshotTest`); comparte solo imágenes con datos inventados.

Los textos de la app y la documentación están en español. Las contribuciones se distribuyen bajo [Apache 2.0](LICENSE); cada dependencia conserva su licencia.
