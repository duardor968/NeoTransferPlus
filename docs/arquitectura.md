# Arquitectura

NeoTransfer+ habla por HTTPS con la API personal de ENZONA, con la sesión de la persona, y guarda en el teléfono lo último que leyó. No hay servidor propio. Esta página resume cómo está hecho.

## Módulos

| Módulo | Qué contiene | Depende de |
|---|---|---|
| `core` | Kotlin puro (JVM), sin Android: contrato de acceso y tokens, cifrado, solicitudes y evidencia de pago, QR, servicios, lectura del JSON de ENZONA e importes | — |
| `designsystem` | Tema azul real, tipografías Inter y Arimo, iconos, componentes Compose y la cara de la tarjeta | — |
| `scanner` | Cámara y lectura de imágenes: QR y números de tarjeta y móvil | — |
| `app` | La app Android: datos, plataforma y pantallas | los tres anteriores |

Paquetes de `app/src/main/kotlin/dev/duardo/neotransfer/plus/`:

- `data/`: `EnzonaDataGraph` (un cliente HTTP, una sesión y una base de datos por proceso), `EnzonaRepository` (la fachada que usan las pantallas), `enzona/` (sesión, transporte, API y pagos), `enzona/db/` (Room) y `contacts/`.
- `app/`: `PlusApp`, el estado del proceso (sesión, bloqueo, lecturas, pagos, avisos y navegación); `Authorizer` (huella y contraseña de pago); `Presentation.kt` (de filas de ENZONA a modelos de pantalla) y `routes/`.
- `platform/`: biometría, contraseña de pago, notificaciones, mosaico de Ajustes rápidos y rutas de entrada.
- `ui/`: pantallas sin estado, que reciben un modelo y unas acciones.

La variante `preview` añade `app/src/preview/`: un catálogo de pantallas con datos inventados ([desarrollo](desarrollo.md#variantes)).

## Sesión OAuth

- **Contrato** (`core/src/main/kotlin/dev/duardo/neotransfer/plus/core/auth/EnzonaAuthContract.kt`): código de autorización con el client ID público de la app oficial, redirección `http://apk-callback`, reto PKCE S256 (con la grafía `code_challange` de la app oficial) y un `state` propio que debe volver exacto.
- **Página de acceso** (`data/enzona/EnzonaLoginWeb.kt`): una WebView con `identity.enzona.net` por HTTPS en el marco principal. La redirección a `apk-callback` se intercepta antes de llegar a la red. TLS estricto, sin puente JavaScript, sin descargas ni permisos web, y `FLAG_SECURE` mientras está visible.
- **Arranque** (`data/enzona/EnzonaSession.kt`): canjea el código, registra el dispositivo como la app oficial (`EnzonaDeviceInfo`: modelo, fabricante, placa, versión y compilación de Android, tipo de red e IP local; su fallo no bloquea), lee la cuenta y solo entonces guarda los tokens.
- **Renovación**: una sola renovación compartida por todos los lectores. `invalid_grant` borra la sesión y pide entrar de nuevo. Un `401` «Invalid Credentials» en una lectura renueva y la repite una vez; en dinero, nunca.
- **Guardado** (`data/enzona/EnzonaSessionStore.kt`): AES-256-GCM con una clave del Android Keystore, en `noBackupFilesDir`. La clave no exige desbloquear la pantalla, para que la comprobación periódica funcione con el teléfono bloqueado.
- **Cerrar sesión** es local: borra los tokens, las cookies y el almacenamiento web, la caché de la cuenta y la contraseña de pago, y cancela la comprobación periódica. No revoca los tokens en el servidor.

## Transporte

`data/enzona/EnzonaTransport.kt` y `EnzonaHttp.kt`:

- OkHttp sin reintentos de conexión, sin seguir redirecciones y sin autenticadores. Tiempos: 15 s conectar, 45 s leer, 30 s escribir y 60 s en total.
- Solo HTTPS hacia `api.enzona.net` e `identity.enzona.net`; cualquier otro destino falla antes de salir.
- Cada cuerpo se puede escribir una sola vez (`SingleSendBody`): ni OkHttp ni un `503` con `Retry-After: 0` pueden repetir un POST.
- Respuestas de 8 MB como máximo, sin caché HTTP. Tokens, intentos y respuestas se imprimen siempre redactados.

## Pagos de un solo envío

`data/enzona/EnzonaPayments.kt`, para transferencias a tarjeta o a cuenta, pagos QR y facturas:

1. **Preparar.** `EnzonaPaymentCoordinator.prepare` congela lo revisado en `intent_journal` con estado `PREPARED`, sin contraseña.
2. **Aprobar.** `Authorizer` abre la contraseña de pago con la huella. `EnzonaPaymentApproval` ata esa aprobación a la pantalla revisada: salir de la app, bloquearla o cambiar de cuenta la invalida.
3. **Cifrar.** `EnzonaPaymentRequests` cifra la contraseña y el número de tarjeta destino con la clave pública de ENZONA (`app/src/main/res/raw/enzona_public_key.pem`, RSA PKCS#1 v1.5) y borra la contraseña de memoria.
4. **Enviar.** Un compare-and-set `PREPARED → SUBMITTING` en Room concede el único envío de ese intento, para siempre. La aprobación se comprueba justo antes y después.
5. **Resultado.** `CONFIRMED` o `REJECTED` con su evidencia. Sin respuesta en 30 s, con timeout o con una respuesta ambigua queda `UNKNOWN` («Sin confirmar»); una respuesta tardía del mismo POST todavía se registra.

Un intento `UNKNOWN` nunca se reenvía: se concilia con el historial. Pagar otra vez exige revisar y aprobar un intento nuevo. Un pago confirmado relee el saldo de la tarjeta y la última semana del historial.

## Caché

Room, base `enzona.db`, versión 1 (`data/enzona/db/`; esquemas en `app/schemas/`):

| Tabla | Contenido |
|---|---|
| `read_cache` | Cuenta, lista de tarjetas y saldo de cada tarjeta, como JSON sin material de autorización (`EnzonaCacheSanitizer`) |
| `transaction_history` | Historial de la cuenta por `transaction_uuid` y titular, con la primera vez que se vio y el estado del aviso |
| `history_sync` | Primera sincronización del titular: lo anterior nunca avisa |
| `card_links` | Número completo y alias de las tarjetas propias, escritos por la persona (ENZONA solo da los cuatro últimos) |
| `intent_journal` | Cada intento de pago, sin contraseña ni solicitud cifrada |

`contacts.db` guarda contactos, tarjetas y teléfonos, sin relación con la cuenta ENZONA. Las preferencias (`preferences`) guardan el tema y si ya se pidieron los permisos.

Las pantallas leen de Room, así que se dibujan sin conexión. `PlusApp.refresh` lee la cuenta (si falta), las tarjetas, cada saldo y la última semana del historial al entrar, al desbloquear, al volver a la app y al deslizar hacia abajo; «Ver anteriores» pide 30 días más. Cerrar sesión borra todo lo del titular salvo `intent_journal`, que es el registro del dinero enviado.

La copia de seguridad de Android está desactivada (`allowBackup="false"` y `data_extraction_rules.xml` excluye todo).

## Comprobación periódica

`EnzonaCreditWorker` y `WorkManagerCreditScheduler` (`data/EnzonaDataGraph.kt`): trabajo periódico `enzona-credit-check` cada 15 minutos, solo con red. Se programa mientras hay sesión y se cancela al cerrarla.

1. `EnzonaRepository.syncCredits` lee la última semana del historial. Nunca toca dinero.
2. Cada abono nuevo queda `PENDING` en `transaction_history`, salvo en la primera sincronización tras entrar y en los días anteriores que se piden a mano.
3. `PlusApp`, que se crea con el proceso aunque lo arranque WorkManager, publica «Nombre te envió importe» y los marca entregados.

Un fallo espera al siguiente periodo, sin reintentos en ráfaga contra ENZONA.

## Seguridad

- **Bloqueo**: huella o credencial del teléfono al abrir y al volver tras 1 minuto en segundo plano (`PlusApp.LockAfterMillis`). Sin bloqueo de pantalla, la app abre directamente. Bloquear la interfaz no cierra la sesión de ENZONA.
- **Contraseña de pago** (`platform/PaymentPasswordVault.kt`): AES-256-GCM bajo una clave del Keystore que exige biometría fuerte en cada uso y se invalida al registrar una huella nueva. Se escribe una vez, se descifra para un solo pago y se borra de memoria al cifrarla para ENZONA.
- **Capturas**: bloqueadas (`SecureWindow`) en la página de acceso, la hoja de contraseña y Ajustes › Contraseña de pago.
- **Notificaciones**: privadas, con una versión pública genérica («Transferencia recibida») para la pantalla de bloqueo.
- **Permisos**: `INTERNET`, `ACCESS_NETWORK_STATE`, `USE_BIOMETRIC`, `CAMERA`, `READ_CONTACTS` y `POST_NOTIFICATIONS`, más los que añade WorkManager.

## Escáner

`scanner/src/main/kotlin/dev/duardo/neotransfer/plus/scanner/`:

- `ScannerView`: cámara a pantalla completa con CameraX, linterna, zoom, enfoque al tocar y galería.
- Lectura: ML Kit empaquetado en la app (códigos y texto), sin Google Play Services, y ZXing para los QR invertidos. En imágenes prueba la imagen entera, su negativo y después por mosaicos (`ImageScan.kt`).
- Números (`NumberReader.kt`, `Tracking.kt`): tarjetas cubanas de 16 dígitos con control Luhn y móviles de 8 dígitos, impresos o a mano; se proponen tras leerlos igual en varios cuadros.
- `scanner` no conoce formatos de pago. La app decide (`app/routes/MoneyRoutes.kt`, `app/routes/Common.kt`): el QR de tarjeta de Transfermóvil o un número abren una transferencia, y solo los códigos `01`, `05` y `07` (`EnzonaQrCode`) se consultan a ENZONA.

**Informes de uso de ML Kit.** ML Kit los envía a Google con `com.google.android.datatransport`, cuyo único backend (`cct`) se declara en el servicio `TransportBackendDiscovery`. `app/src/main/AndroidManifest.xml` lo retira con `tools:node="remove"`: sin backend registrado, `DefaultScheduler` descarta cada informe antes de guardarlo o programar su subida. El manifiesto fusionado de `release` (`app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`) no debe contenerlo.
