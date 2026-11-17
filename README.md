# Intercom Local

Intercomunicador de voz entre teléfonos **sin internet ni datos**.
Un Android crea la zona Wi‑Fi y hace de servidor; los invitados se conectan a esa zona:
otro Android con la misma app, o un iPhone con Safari. Pueden estar varios a la vez.

## Obtener el APK

Cada vez que se sube un cambio, GitHub compila la app sola (pestaña **Actions**, unos 5 minutos).
Cuando termina, aparece en **Releases** (columna derecha de la página del repositorio) como `IntercomLocal.apk`.

En el Android: abre esa página de Releases, descarga `IntercomLocal.apk` y ábrelo.
Si pide permiso para "instalar apps desconocidas", acéptalo para el navegador.

## Usarlo

**Android (anfitrión)**
1. Apaga los datos móviles si quieres y activa la **zona Wi‑Fi** en Ajustes.
2. Abre **Intercom Local** y pulsa **Encender servidor**. Acepta micrófono y notificaciones.
3. La pantalla muestra una dirección (por ejemplo `192.168.43.1:8080`) y un código QR.

**iPhone (invitado), la primera vez**
1. Conéctate a la zona Wi‑Fi del Android (Ajustes → Wi‑Fi).
2. Escanea el QR con la cámara o escribe la dirección en Safari.
3. Sigue los pasos de la página: descargar el certificado, instalarlo y activar su confianza
   (Ajustes → General → Información → Ajustes de confianza de certificados).
4. Toca **Abrir intercomunicador** y luego Compartir → **Agregar a inicio**.

**Otro Android (invitado)**
1. Instala la misma app y conéctate a la zona Wi‑Fi del anfitrión.
2. Abre Intercom Local y toca **Unirse a otro Android**. La dirección del anfitrión se detecta sola; confirma con **Unirse**.
3. No necesita certificado y también funciona con la pantalla apagada.

**Las siguientes veces**: conectar el iPhone a la zona Wi‑Fi, abrir el ícono de inicio y tocar "Conectar".

## Detalles
- Voz sin comprimir a 16 kHz (unos 256 kbit/s), retraso aproximado de 100–250 ms.
- Modos "Pulsar para hablar" y "Manos libres" en los dos teléfonos.
- El Android sigue funcionando con la pantalla apagada. El iPhone necesita Safari al frente; si sales y vuelves, se reconecta solo.
- Audífonos con cable o Bluetooth en ambos.
- Si cambia la dirección de la zona Wi‑Fi, vuelve a escanear el QR (el certificado no hay que reinstalarlo).
- Si desinstalas la app del Android se crea un certificado nuevo y hay que repetir el paso 3 en el iPhone.
