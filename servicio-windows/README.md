# Servicio de Windows para ecopos-sri-connector

Envuelve `ConectorPrincipal` (el proceso que vigila ventas cerradas y las
factura ante el SRI) como un servicio real de Windows: arranca solo con la
máquina, se reinicia solo si se cae, y no depende de dejar una ventana de
consola abierta.

Usa [WinSW](https://github.com/winsw/winsw) (MIT, código abierto) - el
wrapper estándar para correr programas Java como servicio de Windows (lo
usa, por ejemplo, Jenkins). `ecopos-sri-connector-service.exe` en esta
carpeta **es** WinSW, solo renombrado para que coincida con
`ecopos-sri-connector-service.xml` (WinSW exige que el `.exe` y el `.xml`
compartan el mismo nombre base).

## Requisitos antes de instalar el servicio

1. Java 11+ instalado y **`java` disponible en el PATH del sistema**
   (no solo el de tu usuario) - comprueba con `where java` desde una
   terminal nueva. Si no aparece, edita `<executable>` en
   `ecopos-sri-connector-service.xml` con la ruta completa a `java.exe`.
2. Esta carpeta completa (`servicio-windows/`) debe copiarse **dentro**
   de `sri-conector/`, junto a `ecopos-sri-connector.jar` y `config/` -
   el servicio usa `%BASE%` (la carpeta donde vive este `.xml`) como
   directorio de trabajo, así que todo tiene que estar junto.
3. `config/datos-emisor.properties` ya configurado (via
   `ConfiguracionFrame`, el botón de EcoPos en Administración > Sistema)
   antes de arrancar el servicio - si no existe, el servicio arranca y
   falla en bucle.

## Instalar

Abrir **PowerShell o CMD como Administrador**, pararse en esta carpeta
(la que quedó copiada dentro de `sri-conector/`) y correr:

```bat
ecopos-sri-connector-service.exe install
ecopos-sri-connector-service.exe start
```

Verificar que quedó corriendo:

```bat
ecopos-sri-connector-service.exe status
```

O desde el Administrador de servicios de Windows (`services.msc`),
buscando "EcoPos SRI Connector".

## Ver logs

`logs/ecopos-sri-connector.out.log` (salida normal) y
`ecopos-sri-connector.err.log` (errores) en esta misma carpeta -
con rotación automática (WinSW corta el archivo cada 10MB, guarda las
últimas 8 rotaciones).

## Detener / desinstalar

```bat
ecopos-sri-connector-service.exe stop
ecopos-sri-connector-service.exe uninstall
```

## Por qué WinSW y no una Tarea Programada de Windows

Una Tarea Programada puede arrancar el proceso al iniciar sesión, pero no
lo reinicia sola si se cae, y normalmente corre atada a una sesión de
usuario (se cierra si el usuario cierra sesión). Un servicio de Windows
real corre sin sesión de usuario y Windows sabe reiniciarlo — más acorde
a "esto tiene que quedar andando siempre, en la computadora de cualquier
negocio, sin que alguien tenga que acordarse de nada".
