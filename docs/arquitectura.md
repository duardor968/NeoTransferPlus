# Arquitectura

NeoTransfer+ habla por HTTPS con la API personal de ENZONA, con la sesión de la persona, y guarda en el teléfono lo último que leyó. No hay servidor propio. Esta página resume cómo está hecho.

## Módulos

| Módulo | Qué contiene | Depende de |
|---|---|---|
| `core` | Kotlin puro (JVM), sin Android: contrato de acceso y tokens, cifrado, solicitudes y evidencia de pago, QR, servicios, lectura del JSON de ENZONA, importes, y banco y moneda por el número de tarjeta | — |
| `designsystem` | Tema azul real, tipografías Inter y Arimo, iconos, componentes Compose y la cara de la tarjeta | — |
| `scanner` | Cámara y lectura de imágenes: QR y números de tarjeta y móvil | — |
| `app` | La app Android: datos, plataforma y pantallas | los tres anteriores |

Paquetes de `app/src/main/kotlin/dev/duardo/neotransfer/plus/`:

- `data/`: `EnzonaDataGraph` (un cliente HTTP, una sesión y una base de datos por proceso), `EnzonaRepository` (la fachada que usan las pantallas), `enzona/` (sesión, transporte, API y pagos), `enzona/db/` (Room) y `contacts/` (las tarjetas guardadas y `Directory`, ver [Contactos](#contactos)).
- `app/`: `PlusApp`, el estado del proceso (sesión, bloqueo, lecturas, pagos, avisos y navegación); `Authorizer` (huella y contraseña de pago); `Presentation.kt` (de filas de ENZONA a modelos de pantalla) y `routes/`.
- `platform/`: biometría, contraseña de pago, notificaciones, mosaico de Ajustes rápidos, rutas de entrada, la agenda del teléfono (`AndroidPhoneBook`) y la exención de batería.
- `ui/`: pantallas sin estado, que reciben un modelo y unas acciones.

La variante `preview` añade `app/src/preview/`: un catálogo de pantallas con datos inventados ([desarrollo](desarrollo.md#variantes)).

## Sesión OAuth

- **Contrato** (`core/src/main/kotlin/dev/duardo/neotransfer/plus/core/auth/EnzonaAuthContract.kt`): código de autorización con el client ID público de la app oficial, redirección `http://apk-callback`, reto PKCE S256 (con la grafía `code_challange` de la app oficial) y un `state` propio que debe volver exacto.
- **Página de acceso** (`data/enzona/EnzonaLoginWeb.kt`): una WebView con `identity.enzona.net` por HTTPS en el marco principal. La redirección a `apk-callback` se intercepta antes de llegar a la red. TLS estricto, sin puente JavaScript, sin descargas ni permisos web, y `FLAG_SECURE` mientras está visible.
- **Arranque** (`data/enzona/EnzonaSession.kt`): canjea el código, registra el dispositivo como la app oficial (`EnzonaDeviceInfo`: modelo, fabricante, placa, versión y compilación de Android, tipo de red e IP local; su fallo no bloquea), lee la cuenta y solo entonces guarda los tokens.
- **Renovación**: una sola renovación compartida por todos los lectores. `invalid_grant` borra la sesión y pide entrar de nuevo. Un `401` «Invalid Credentials» en una lectura renueva y la repite una vez; en dinero, nunca.
- **Guardado** (`data/enzona/EnzonaSessionStore.kt`): AES-256-GCM con una clave del Android Keystore, en `noBackupFilesDir`. La clave no exige desbloquear la pantalla, para que la comprobación periódica funcione con el teléfono bloqueado.
- **Correo sugerido** (`data/enzona/EnzonaLoginHint.kt`): la dirección de acceso lleva `login_hint` con el correo de la cuenta, y la página de ENZONA decide si lo usa. Se guarda al leer la cuenta, se conserva si ENZONA cierra la sesión y se borra al cerrarla. La contraseña y el SMS siguen solo en la página de ENZONA.
- **Cerrar sesión** es local: borra los tokens, las cookies y el almacenamiento web, el correo sugerido, la caché de la cuenta y la contraseña de pago, y cancela la comprobación periódica. No revoca los tokens en el servidor.

## Transporte

`data/enzona/EnzonaTransport.kt` y `EnzonaHttp.kt`:

- OkHttp sin reintentos de conexión, sin seguir redirecciones y sin autenticadores. Tiempos: 15 s conectar, 45 s leer, 30 s escribir y 60 s en total.
- Los cuatro POST de dinero abren cada uno una conexión propia, que se cierra tras la respuesta: una conexión reutilizada que ENZONA ya cerró se tragaría el cuerpo del pago y lo dejaría «Sin confirmar». El resto comparte el pool.
- `EnzonaHttp` repite una vez una lectura (GET) cuya conexión se rompe, como la de una conexión reutilizada que ENZONA ya cerró, y anota cómo acabó la repetición (`retry ok` o `again …`). Nada más se repite: ni un envío, ni una lectura que agotó el tiempo.
- Si el cuerpo de una solicitud nunca llegó a escribirse en la conexión (sin red, sin TLS o fallo al conectar), el fallo es `EnzonaNotDeliveredException`: la solicitud no salió del teléfono.
- Solo HTTPS hacia `api.enzona.net` e `identity.enzona.net`; cualquier otro destino falla antes de salir.
- Cada cuerpo se puede escribir una sola vez (`SingleSendBody`): ni OkHttp ni un `503` con `Retry-After: 0` pueden repetir un POST.
- Respuestas de 8 MB como máximo, sin caché HTTP. Tokens, intentos y respuestas se imprimen siempre redactados.

## Pagos de un solo envío

`data/enzona/EnzonaPayments.kt`, para transferencias a tarjeta o a cuenta, pagos QR y facturas:

1. **Preparar.** `EnzonaPaymentCoordinator.prepare` congela lo revisado en `intent_journal` con estado `PREPARED`, sin contraseña.
2. **Aprobar.** `Authorizer` abre la contraseña de pago con la huella. `EnzonaPaymentApproval` ata esa aprobación a la pantalla revisada: salir de la app, bloquearla o cambiar de cuenta la invalida.
3. **Cifrar.** `EnzonaPaymentRequests` exige seis dígitos (el formato de ENZONA), cifra la contraseña y el número de tarjeta destino con la clave pública de ENZONA (`app/src/main/res/raw/enzona_public_key.pem`, RSA PKCS#1 v1.5) y borra la contraseña de memoria.
4. **Enviar.** Un compare-and-set `PREPARED → SUBMITTING` en Room concede el único envío de ese intento, para siempre. La aprobación se comprueba justo antes y después.
5. **Resultado.**
   - `CONFIRMED` o `REJECTED` con su evidencia.
   - `NOT_DELIVERED` si el cuerpo nunca salió del teléfono o la aprobación caducó tras el compare-and-set: no se movió dinero, y «Enviar de nuevo» prepara un intento nuevo con los mismos valores y una huella nueva.
   - `UNKNOWN` («Sin confirmar») sin respuesta en 30 s, con timeout o con una respuesta que no prueba nada. Se guarda lo que dijo esa respuesta: el código HTTP (0 si no llegó), el `transaction_uuid` y el `fault` de ENZONA (código y texto saneado, 200 caracteres como máximo). Un `fault` nunca convierte el intento en `REJECTED`. Una respuesta tardía del mismo POST todavía se registra.
   - Al arrancar el proceso, un `SUBMITTING` ya no tiene vuelo vivo y pasa a `UNKNOWN`.

Un intento `UNKNOWN` nunca se reenvía. Pagar otra vez exige revisar y aprobar un intento nuevo.

### Conciliación con el historial

Cada lectura del historial (al entrar, al volver a la app, en la comprobación periódica, al pedir días anteriores y tras cada pago) concilia los `UNKNOWN` (`data/enzona/EnzonaReconciliation.kt`), con el `transaction_status_code` como lo lee el historial de la app oficial (1111 Aceptada, 1121 Pendiente, 1112 Fallida):

- **Por uuid.** Con el `transaction_uuid` de la respuesta, su fila decide: 1111 `CONFIRMED`, 1112 `REJECTED`; 1121 sigue pendiente.
- **Por importe y hora.** Sin uuid, una sola fila saliente de la misma tarjeta al mismo destino (cuatro últimos dígitos, usuario o referencia), con el importe y la moneda exactos, creada entre 2 min antes y 24 h después. Si es única y 1111, `CONFIRMED` «por importe y hora». Con varias candidatas, o una fila que ya es de otro intento, nada. Los pagos QR no traen destino en el historial y solo se concilian por uuid.
- **Relecturas.** Un pago confirmado o sin confirmar relee el saldo de la tarjeta y la última semana del historial al momento; uno sin confirmar, otra vez a los 5 min. Volver a la app las repite.
- **Cierre por la persona.** Si una lectura hecha al menos 24 h después del pago cubre su fecha y no tiene su uuid ni ninguna fila de esa tarjeta con ese importe, moneda y hora (sea cual sea el destino), el detalle dice «No aparece en tu historial de ENZONA. Si el saldo no bajó, no se movió dinero.» y ofrece «Marcar como no realizada». Es una resolución de la persona (`markedNotDoneAt`), no un `REJECTED`: el intento sigue `UNKNOWN` por debajo, sale de Inicio y, si después aparece una fila suya, la marca se deshace.

## Caché

Room, base `enzona.db`, versión 2 (`data/enzona/db/`; esquemas en `app/schemas/`). La migración 1 → 2 solo añade columnas a `intent_journal` y conserva cada intento:

| Tabla | Contenido |
|---|---|
| `read_cache` | Cuenta, lista de tarjetas y saldo de cada tarjeta, como JSON sin material de autorización (`EnzonaCacheSanitizer`) |
| `transaction_history` | Historial de la cuenta por `transaction_uuid` y titular, con la primera vez que se vio y el estado del aviso |
| `history_sync` | Primera sincronización del titular: lo anterior nunca avisa |
| `card_links` | Número completo y alias de las tarjetas propias, escritos por la persona (ENZONA solo da los cuatro últimos) |
| `intent_journal` | Cada intento de pago, sin contraseña ni solicitud cifrada; lo que respondió ENZONA (código HTTP, `fault`) y lo que dice el historial (cómo se concilió, cuándo no apareció, si la persona lo marcó como no realizado) |

`contacts.db` guarda las tarjetas de la gente de la agenda y los contactos que solo existen en la app, sin relación con la cuenta ENZONA ([Contactos](#contactos)). Las preferencias (`preferences`) guardan el tema, si ya se pidieron los permisos y si ya se pidió la exención de batería. Guardados, en Servicios, son los pagos de servicios que ENZONA confirmó (`intent_journal`); `saved_references` (`data/services/SavedReferences.kt`) guarda solo el nombre que se les da y cuándo se quitó uno, que vuelve con el siguiente pago.

Las pantallas leen de Room, así que se dibujan sin conexión. `PlusApp.refresh` lee la cuenta (si falta), las tarjetas, cada saldo y la última semana del historial al entrar, al desbloquear, al volver a la app y al deslizar hacia abajo; «Ver anteriores» pide 30 días más. Cerrar sesión borra todo lo del titular salvo `intent_journal`, que es el registro del dinero enviado.

La copia de seguridad de Android está desactivada (`allowBackup="false"` y `data_extraction_rules.xml` excluye todo).

## Comprobación periódica

`EnzonaCreditWorker` y `WorkManagerCreditScheduler` (`data/EnzonaDataGraph.kt`): trabajo periódico `enzona-credit-check` cada 15 minutos, solo con red. Se programa mientras hay sesión y se cancela al cerrarla.

1. `EnzonaRepository.syncCredits` lee la última semana del historial. Nunca toca dinero.
2. Cada abono nuevo queda `PENDING` en `transaction_history`, salvo en la primera sincronización tras entrar y en los días anteriores que se piden a mano.
3. `PlusApp`, que se crea con el proceso aunque lo arranque WorkManager, publica «Nombre te envió importe» y los marca entregados.

Un fallo espera al siguiente periodo, sin reintentos en ráfaga contra ENZONA.

Algunos gestores de energía no dejan correr el trabajo con la app cerrada: el de TECNO bloquea el `SystemJobService` de WorkManager (`limitBindServiceLocked by hs&job`). Por eso, una vez y después de los permisos del primer arranque (`app/Root.kt`), la app pide con el diálogo del sistema (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) quedar fuera de la optimización de batería si `isIgnoringBatteryOptimizations` es falso; si el teléfono tiene la lista «Inicio automático» de Transsion (`com.cyin.himgr.applicationmanager.view.activities.AUTO_START_ACTIVITY`, declarada en `<queries>`), ofrece abrirla para activar la app. Ajustes › Avisos con la app cerrada vuelve a lo mismo.

## Contactos

La agenda del teléfono es la fuente de nombres y teléfonos; la app no importa nada. `AndroidPhoneBook` (`platform/System.kt`) la lee entera (una consulta a `Phone.CONTENT_URI`) al volver a la app y al conceder `READ_CONTACTS`, y se queda con quien tiene nombre y un móvil cubano (8 dígitos que empiezan por 5 o 6).

`Directory` (`data/contacts/Directory.kt`) une esa lectura con `contacts.db`:

- A la gente de la agenda la app solo le guarda tarjetas, con el identificador `phone:<lookup key>`; su nombre y sus teléfonos llegan siempre de la agenda. Se editan en la app de Contactos (`ACTION_EDIT` sobre su lookup URI) y nunca se borran desde aquí.
- Los contactos que solo existen en la app conservan nombre, tarjetas y teléfonos; su formulario cambia nombre y teléfonos (y su primera tarjeta, al crearlo), y se borran en la app.
- Las tarjetas de cualquiera se gestionan en su ficha: cada fila transfiere y su menú copia, edita (`Dest.CardEdit`, con la cara del banco mientras se escribe) o quita al momento, con «Deshacer» en el aviso.
- Un contacto de la app que comparte un teléfono con una sola persona de la agenda se une a ella con sus tarjetas (`Directory.link`): así no se duplican los contactos importados antes.
- Sin permiso, la lista son los contactos guardados en la app, y la pestaña ofrece «Permitir».

La pestaña lista a quien tiene una tarjeta guardada o un movimiento ligado, por el último movimiento y después por nombre; «Todos los contactos» abre la agenda completa por letras y el buscador mira en toda ella. ENZONA, en lo recibido y en su historial, solo da el nombre y los cuatro últimos dígitos, así que un movimiento se liga a un contacto solo si consta con la tarjeta completa: las transferencias a tarjeta enviadas desde este teléfono (`intent_journal`), y solo cuando una sola persona tiene esa tarjeta (`contactMovements` en `app/Presentation.kt`). Nunca por el nombre ni por los cuatro dígitos.

De una tarjeta ajena, el banco y la moneda salen del número, como los lee Transfermóvil (`core/.../core/card/CardIssuer.kt`): en las que empiezan por `92`, los dígitos 5–6 son el banco (`12` BPA, `06` BANDEC, `95` Metropolitano) y los 3–4 la moneda (`25`, `26`, `28`, `29`, `35`, `40` y `45` dólares; `50` desconocida; el resto, pesos); `9760` y `9560` (Fincimex) son dólares sin banco.

## Tarjetas de la cuenta

Ajustes › Tarjetas (`core/card/EnzonaCards.kt`, `ui/settings/AddCardScreen.kt`), con los endpoints de la app oficial:

- **Predeterminada**: `POST /cards/v1.0.0/cards/default`.
- **Quitar**: `DELETE /cards/v1.0.0/cards/{uuid}/delete`, tras confirmarlo.
- **Añadir**:
  - lee el catálogo de bancos (`/nomenclator/v1.0.0/nomenclator/banks`) y las casillas que pide ENZONA (`/nomenclator/v1.0.0/nomenclator/coordinates`): dos coordenadas y dos posiciones del PIN;
  - envía un solo `POST /coremz/1.0.2/card`, que nunca se repite;
  - el número va cifrado con la clave de ENZONA (PKCS#1). Las casillas y lo tecleado van juntos en `cadenaEncript`, con OAEP-SHA1 y el certificado del banco del catálogo;
  - lo tecleado solo vive en memoria y se borra tras el envío o al salir;
  - sin respuesta de ENZONA, el resultado es «Sin confirmar» y la lista se vuelve a leer.

## Seguridad

- **Bloqueo**: huella o credencial del teléfono al abrir y al volver tras 1 minuto en segundo plano (`PlusApp.LockAfterMillis`). Sin bloqueo de pantalla, la app abre directamente. Bloquear la interfaz no cierra la sesión de ENZONA.
- **Contraseña de pago** (`platform/PaymentPasswordVault.kt`): seis dígitos, AES-256-GCM bajo una clave del Keystore que exige biometría fuerte en cada uso y se invalida al registrar una huella nueva. La escrita se cifra al aprobar el pago y se guarda solo cuando ese pago llega a `CONFIRMED`. Si ENZONA responde a un pago sin confirmarlo, queda «Por comprobar» y el siguiente pago la pide otra vez, con el aviso de que ENZONA la bloquea tras 3 intentos fallidos. Se descifra para un solo pago y se borra de memoria al cifrarla para ENZONA.
- **Capturas**: bloqueadas (`SecureWindow`) en la página de acceso, la hoja de contraseña, Ajustes › Contraseña de pago y el paso de coordenadas y PIN del alta de una tarjeta.
- **Diagnóstico** (`data/enzona/EnzonaLog.kt`, `adb logcat -s NeoTransferPlus`): cada fallo de lectura o de pago y cada pérdida de sesión. Anota el tipo, la clase de la excepción, el código HTTP, la ruta con los identificadores como `*` y la duración. De una respuesta que la app no entiende, solo el campo rechazado; del `fault` de un pago, solo los nombres de sus campos. Nunca tokens, cabeceras, cuerpos, nombres, números de tarjeta ni importes.
- **Notificaciones**: privadas, con una versión pública genérica («Transferencia recibida») para la pantalla de bloqueo.
- **Permisos**: `INTERNET`, `ACCESS_NETWORK_STATE`, `USE_BIOMETRIC`, `CAMERA`, `READ_CONTACTS`, `POST_NOTIFICATIONS` y `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, más los que añade WorkManager.

## Escáner

`scanner/src/main/kotlin/dev/duardo/neotransfer/plus/scanner/`:

- `ScannerView`: cámara a pantalla completa con CameraX, linterna, zoom, enfoque al tocar y galería.
- Lectura: ML Kit empaquetado en la app (códigos y texto), sin Google Play Services, y ZXing para los QR invertidos. En imágenes prueba la imagen entera, su negativo y después por mosaicos (`ImageScan.kt`).
- Números (`NumberReader.kt`, `Tracking.kt`): tarjetas cubanas de 16 dígitos con control Luhn y móviles de 8 dígitos, impresos o a mano; se proponen tras leerlos igual en varios cuadros.
- `scanner` no conoce formatos de pago. La app decide (`app/routes/MoneyRoutes.kt`, `app/routes/Common.kt`): el QR de tarjeta de Transfermóvil o un número abren una transferencia, y solo los códigos `01`, `05` y `07` (`EnzonaQrCode`) se consultan a ENZONA.

**Informes de uso de ML Kit.** ML Kit los envía a Google con `com.google.android.datatransport`, cuyo único backend (`cct`) se declara en el servicio `TransportBackendDiscovery`. `app/src/main/AndroidManifest.xml` lo retira con `tools:node="remove"`: sin backend registrado, `DefaultScheduler` descarta cada informe antes de guardarlo o programar su subida. El manifiesto fusionado de `release` (`app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`) no debe contenerlo.
