# Cómo instalar ecopos-sri-connector en un EcoPos nuevo

Esta guía es para un administrador que va a instalar la facturación
electrónica SRI en la computadora de un negocio - no asume que sabes
programar. Si necesitas entender por qué está construido así, o qué falta
por probar, mira `README.md` en vez de esta guía.

## 0. Requisitos antes de empezar

- **EcoPos ya instalado y funcionando** en esa computadora, con su base de
  datos MySQL corriendo (XAMPP u otra instalación de MySQL).
- **Java 11 o más nuevo instalado**, y disponible en el PATH del sistema.
  Comprobar abriendo una ventana de CMD nueva y escribiendo:
  ```
  java -version
  ```
  Si da error "no se reconoce el comando", hay que instalar Java (por
  ejemplo, Eclipse Temurin 11) antes de seguir.
- **El certificado de firma electrónica (.p12)** del negocio, acreditado
  ante una entidad certificadora ecuatoriana (Security Data, BCE, etc.),
  y su contraseña. Sin esto no se pueden firmar comprobantes - se puede
  instalar todo lo demás primero y agregar el certificado después.

## 1. Copiar los archivos

Dentro de la carpeta de instalación de EcoPos (donde está `bin/`, `lib/`,
etc.), crear una carpeta llamada **`sri-conector`** y copiar ahí adentro:

- `ecopos-sri-connector.jar` (el jar compilado de este proyecto -
  generarlo con `mvn clean package` desde este repo, o pedirlo a quien te
  lo entregó)
- El contenido completo de la carpeta `servicio-windows/` de este repo
  (el wrapper del servicio de Windows)

Al terminar este paso debe quedar así:

```
EcoPos/
  sri-conector/
    ecopos-sri-connector.jar
    ecopos-sri-connector-service.exe
    ecopos-sri-connector-service.xml
```

## 2. Instalar/actualizar los "ganchos" en la base de datos

Abrir una ventana de CMD, pararse **dentro de la carpeta `sri-conector`**
que acabas de crear, y correr:

```bat
java -cp ecopos-sri-connector.jar com.openbravo.pos.sri.instalador.InstaladorEcoPos
```

Esto conecta a la base de datos de EcoPos (por defecto
`localhost:3306/ecopos` con usuario `root` sin clave - si tu instalación
usa otros datos, crea primero `config/conexion.properties` con
`host`/`puerto`/`baseDatos`/`usuario`/`clave` y pásalo como argumento) y
deja todo lo que EcoPos necesita para mostrar los botones de facturación,
el historial, etc. **Se puede correr las veces que sea sin miedo** - si
algo ya está instalado, lo dice y no lo toca dos veces.

Vas a ver algo así:

```
[+] Tabla ecopos_sri_comprobantes creada.
[+] Menu.Root actualizado con el hook de ecopos-sri-connector.
[+] Ticket.Buttons actualizado con el hook de ecopos-sri-connector.
...
Listo. ecopos-sri-connector esta instalado/actualizado en esta base de datos.
```

Si EcoPos estaba abierto durante este paso, **ciérralo y vuelve a
abrirlo** para que cargue los botones/menús nuevos.

## 3. Configurar los datos del negocio (RUC, certificado)

Abrir EcoPos, entrar como Administrador, ir a **Administración > Sistema**
y presionar el botón de configuración del conector SRI. Completar:

- RUC, razón social, nombre comercial (opcional)
- Dirección matriz / dirección del establecimiento
- Establecimiento y punto de emisión (3 dígitos cada uno, ej. `001`)
- Ambiente: **Pruebas** primero, cambiar a **Producción** solo cuando el
  negocio esté listo para emitir facturas reales
- Ruta al certificado `.p12` y su contraseña

Guardar. Esto crea `sri-conector/config/datos-emisor.properties`.

## 4. (Opcional) Configurar el envío de correo

Desde el Historial de facturación (mismo menú Administración > Sistema),
botón "Configurar correo...", completar host/puerto/usuario/clave/remitente
de un servidor SMTP real. Sin esto, el envío automático/manual por correo
simplemente no hace nada (no rompe la facturación).

## 5. Instalar el servicio de Windows

Ver `servicio-windows/README.md` para el detalle - en resumen, desde una
consola **como Administrador**, parado en `sri-conector/`:

```bat
ecopos-sri-connector-service.exe install
ecopos-sri-connector-service.exe start
```

Con esto el conector queda vigilando las ventas y facturando
automáticamente, arranca solo con Windows, y se reinicia solo si se cae.

## 6. Probar

1. En EcoPos, abrir una venta nueva y presionar el botón **"SRI: SI"**
   (verde). Confirma que factura electrónicamente **todas** las ventas
   desde ahora (no solo esta) - es un interruptor global.
2. Cerrar una venta normal.
3. Abrir el Historial de facturación (Administración > Sistema) y
   confirmar que el ticket aparece, primero como PENDIENTE/ENVIADO y
   luego (unos segundos después) como AUTORIZADO.
4. Revisar `sri-conector/logs/ecopos-sri-connector-service.out.log` si
   algo no aparece - ahí queda el detalle de cada intento.

## Problemas comunes

- **El botón de facturación no aparece / no hace nada**: cerraste EcoPos
  después de correr el instalador (paso 2)? Los menús/botones se cargan
  una sola vez al iniciar sesión.
- **El servicio no arranca**: revisa que `java` esté en el PATH del
  sistema (no solo el de tu usuario) - el servicio corre sin sesión de
  usuario. Ver `servicio-windows/README.md`.
- **Factura siempre RECHAZADA/ERROR**: revisa el mensaje de error exacto
  en el Historial (columna "Error") - casi siempre es un dato del emisor
  mal configurado (paso 3) o el certificado vencido/incorrecto.
