# ecopos-sri-connector

Módulo Java **independiente** (Maven, propio jar) que emite facturación
electrónica ante el SRI (Servicio de Rentas Internas, Ecuador) a partir de
las ventas registradas en ECOPos.

## Tecnologías y herramientas

| Categoría | Herramienta | Versión | Para qué |
|---|---|---|---|
| Lenguaje / build | Java | 11 | Mismo bytecode que ECOPos, aunque corre en JVM separada |
| | Apache Maven | 3.9.16 | Build del módulo (independiente del Ant de ECOPos) |
| Generación de código | JAXB (`javax.xml.bind:jaxb-api` + `org.glassfish.jaxb:jaxb-runtime`) | 2.3.1 / 2.3.9 | Genera las 38 clases del comprobante (`Factura`, `InfoTributaria`, `Detalle`...) a partir de `factura_V2.1.0.xsd` — nunca se escriben a mano |
| | `org.codehaus.mojo:jaxb2-maven-plugin` (goal `xjc`) | 2.5.0 | Ejecuta la generación JAXB en cada build (`mvn generate-sources`) |
| | Apache CXF (`cxf-rt-frontend-jaxws`, `cxf-rt-transports-http`, `cxf-codegen-plugin`) | 3.6.4 | Genera los stubs Java del cliente SOAP a partir del WSDL real del SRI (`wsdl2java`), y provee el runtime JAX-WS para invocarlos |
| Firma electrónica | xades4j (`com.googlecode.xades4j`) | 2.4.0 | Firma XAdES-BES sobre el XML del comprobante (aún por integrar) |
| Base de datos | MySQL Connector/J (`com.mysql:mysql-connector-j`) | 8.0.33 | Lectura de `TICKETS`/`RECEIPTS`/`TICKETLINES` de ECOPos y CRUD de `ecopos_sri_comprobantes` |
| Logging | SLF4J + Logback | 2.0.13 / 1.5.6 | Logging del conector (independiente del logging de ECOPos) |
| Tests | JUnit Jupiter | 5.10.2 | Tests unitarios (`ClaveAccesoGeneratorTest`, `ComprobanteXmlMapperTest`) |
| Empaquetado | `maven-shade-plugin` | 3.5.3 | Genera un jar único con todas las dependencias, para poder soltarlo en un classpath sin gestionar ~15 jars sueltos |

**Fuentes de datos oficiales usadas (no fabricadas a mano):**
- **XSD** (`factura_V2.1.0.xsd`, `notaCredito_V1.1.0.xsd` + `xmldsig-core-schema.xsd`): vendorizados desde el repo open-source (Apache-2.0) [`xprl-gjf/sri-efactura-core`](https://github.com/xprl-gjf/sri-efactura-core) — `www.sri.gob.ec` no es alcanzable desde este entorno de red. Verificado campo por campo contra la ficha técnica oficial v2.32. La Nota de Crédito no tiene una versión 2.x en ese mirror (a diferencia de factura); 1.1.0 es la más reciente disponible.
- **WSDL** (`RecepcionComprobantesOffline.wsdl`, `AutorizacionComprobantesOffline.wsdl`): descargados directamente con `curl` desde `celcer.sri.gob.ec` (ambiente de pruebas), URLs confirmadas oficiales por la misma ficha técnica (sección 7.2.1).
- **Ficha técnica del SRI** v2.32 (octubre 2025): fuente de las tablas de catálogo (`FormaPago`, `TipoComprobante`, códigos de IVA) y de la estructura exacta de cada tag XML.

## Arquitectura (por qué se ve así)

- **ECOPos no tiene ningún sistema de eventos/plugins en Java.** Sí tiene,
  en cambio, un mecanismo de **scripts guardados como datos** (BeanShell)
  que se ejecutan en puntos concretos de la venta — entre ellos,
  `ticket.close`, justo después de que la venta se guardó en la base de
  datos. Ese script (`Ticket.Close`) está **activo por defecto** en ECOPos.
- El único cambio en ECOPos es haber **extendido ese script** (dato, no
  código: `src-pos/com/openbravo/pos/templates/Ticket.Close.xml` en el repo
  de ECOPos, y el registro correspondiente en la tabla `RESOURCES`) para que,
  al cerrar una venta normal, deje un archivo vacío
  `sri-conector/pendientes/<ticketId>.flag`. **Ningún `.java` de ECOPos fue
  modificado.**
- Este módulo corre como **proceso separado** (su propio JVM). Nunca
  comparte classpath con ECOPos, así que sus dependencias (CXF, xades4j,
  JAXB) no pueden chocar con los jars de 2012 que usa ECOPos.
- `VigilantePendientes` observa esa carpeta (vía `WatchService`, con
  barrido de respaldo cada 5s por si el evento de filesystem se pierde).
  Por cada flag nuevo, lee el ticket completo de la base de datos por su
  cuenta (no depende de clases de ECOPos) y arranca el flujo: generar XML →
  firmar XAdES-BES → enviar a Recepción → consultar Autorización.
- El estado de cada comprobante vive en `ecopos_sri_comprobantes` (tabla
  nueva, sin FK real hacia las tablas de ECOPos — ver el `.sql` para el
  razonamiento). Los reintentos ante fallas de red/SRI son responsabilidad
  de este módulo, nunca de ECOPos.

## Estado actual

| Pieza | Estado |
|---|---|
| Estructura Maven + `pom.xml` | ✅ Listo, `mvn clean test` verificado en verde |
| Hook con ECOPos (script `Ticket.Close` + carpeta de flags) | ✅ Listo y probado (arranque de ECOPos verificado, sintaxis BeanShell validada con intérprete real) |
| Tabla `ecopos_sri_comprobantes` | ✅ Script SQL listo, falta ejecutarlo contra tu MySQL |
| `VigilantePendientes` (detección de ventas cerradas) | ✅ Escrito, compila |
| Clases de dominio (`Comprobante`, `DatosEmisor`, `Cliente`, `DetalleFactura`, `ImpuestoDetalle`, `Pago`, enums) | ✅ Escritas, compilan |
| `ClaveAccesoGenerator` (clave de 49 dígitos, módulo 11) | ✅ Escrito + 5 tests unitarios en verde — **falta validar el dígito verificador contra un ejemplo real del SRI** (ver comentario en el test) |
| `TicketReader` / `ComprobanteRepository` (lectura ECOPos + CRUD tabla propia) | ✅ Escritos, compilan (sin probar aún contra MySQL real) |
| WSDL de Recepción/Autorización | ✅ **Descargados el 2026-07-06 desde `celcer.sri.gob.ec` (ambiente de pruebas), confirmados oficiales por la ficha técnica v2.32 (sección 7.2.1)**. `cxf-codegen-plugin` genera los stubs reales, `mvn clean test` en verde |
| Cliente SOAP (stubs generados: `RecepcionComprobantesOffline`, `AutorizacionComprobantesOffline`) | ✅ Clases generadas y compilando (`target/generated-sources/cxf/...`). Falta escribir el wrapper `SoapClient` que las invoque |
| XSD del comprobante (factura v2.1.0) | ✅ **`factura_V2.1.0.xsd` real integrado** (ver `src/main/resources/xsd-README.md` para procedencia). `jaxb2-maven-plugin` genera 38 clases (`Factura`, `InfoTributaria`, `Detalle`, `Reembolsos`, etc.) en cada build, `mvn clean test` en verde |
| Mapeo `Comprobante` (dominio) → clases JAXB → XML (`com.openbravo.pos.sri.xml`) | ✅ **`ComprobanteXmlMapper` + `FacturaXmlWriter` escritos y probados** — 6 tests, incluyendo generación de XML real verificado campo por campo contra la ficha técnica (`infoTributaria`, `infoFactura`, `detalles`, impuestos con `codigoPorcentaje` resuelto vía `CodigoPorcentajeIva`, montos siempre a 2 decimales) |
| Mapeo `TicketCrudo` → `Comprobante` (`TicketComprobanteMapper`) | ✅ **Escrito y probado** — 5 tests, más una prueba manual de la cadena completa `TicketCrudo → Comprobante → XML` con dos líneas de distinta tarifa (15% y exenta), agrupadas correctamente en dos `<totalImpuesto>`. Resuelve tipo de identificación del cliente por longitud (`TipoIdentificacionResolver`) y forma de pago por heurística de nombre (`FormaPagoResolver`) — **ambas heurísticas deben revisarse contra los datos reales de cada instalación antes de producción** |
| `secuencial` en `ecopos_sri_comprobantes` | ✅ Columna agregada (no existía) + `ComprobanteRepository.siguienteSecuencial()` (MAX+1, sin bloqueo — suficiente mientras el conector procese un ticket a la vez) |
| `SoapClient` (envoltorio sobre los stubs CXF) | ✅ **Escrito y probado contra el servidor real de pruebas del SRI** (`celcer.sri.gob.ec`, no solo compilación) — ver hallazgo importante abajo |
| Firma XAdES-BES (`XadesBesSigner`) | ✅ **Escrito y probado con una firma real** (certificado autofirmado generado con `keytool` en el test, no un mock) — 3 tests, incluye verificar que usa **RSA-SHA1** (no el SHA-256 por defecto de xades4j) tal como exige la sección 6.8/Anexo 14 de la ficha técnica. Ver notas técnicas abajo sobre el conflicto de runtime JAXB con xades4j |
| `ConfiguracionLoader` (lee/escribe `datos-emisor.properties` ↔ `DatosEmisor`) | ✅ **Escrito y probado** — 4 tests con round-trip real a disco (`@TempDir`), incluyendo verificar que la clave del certificado nunca queda en texto plano en el archivo (`ClaveCifrador`, AES-GCM) |
| Pantalla Swing de configuración (`ConfiguracionFrame`) | ✅ Escrita y compila; carga/guarda contra `ConfiguracionLoader`. **No se pudo verificar visualmente en este entorno** (el sandbox de ejecución no comparte sesión de escritorio interactiva con este proceso — ver nota abajo). Corrida tú mismo antes de confiar en el layout. Falta decidir cómo la abre el administrador (standalone vs. botón-hook en ECOPos) |
| Clase orquestadora `ConectorPrincipal` (une todo en un proceso que corra continuamente) | ✅ **Escrita y probada de punta a punta contra servicios reales, con resultado AUTORIZADO** (MySQL real + certificado real acreditado + servidor real de pruebas del SRI, no mocks) — ver hallazgo abajo con los 4 bugs reales encontrados y corregidos en el camino |
| Botón en EcoPos para abrir `ConfiguracionFrame` (Administración > Sistema) | ✅ **Escrito y probado** — hook data-only (`SriConnectorConfig.bs` + `Menu.Root`/`Role.Administrator` en el repo de EcoPos), lanza el jar del conector como proceso externo. Confirmado con un lanzamiento real (título de ventana verificado vía la tabla de procesos del SO) |
| Botones "Facturar SRI: SI/NO" en la pantalla de venta de EcoPos | ✅ **Escritos y probados con un ticket real** — hook data-only (`script.SriInvoiceOn/Off.txt` + `Ticket.Buttons`/3 roles en el repo de EcoPos), marcan un atributo por ticket que `Ticket.Close` lee para decidir si factura. Por defecto NO factura si el cajero no toca el control; el ticket siempre se imprime igual |
| Historial de facturación (`HistorialFrame`) | ✅ **Escrito y probado** — lista todo `ecopos_sri_comprobantes` (facturas y notas de crédito), colorea por estado, y marca en naranja los comprobantes ENVIADO/ERROR con más de 24h sin resolverse (aviso operativo, no una cita textual de un plazo legal del SRI) |
| RIDE en PDF (`RideGenerator` / `RideNotaCreditoGenerator`) | ✅ **Escrito y verificado** (render-a-imagen con `PDFRenderer`, no solo extracción de texto) contra un layout de referencia real de otro sistema — cubre fecha/hora de autorización, subtotales por tarifa/tipo de impuesto (con IVA/ICE/IRBPNR etiquetados por su código real), código auxiliar y detalle adicional por línea, subsidio, e Información Adicional |
| **Nota de Crédito (anulación de facturas)** (`AnulacionService`, `NotaCreditoXmlMapper`, `AnulacionFrame`) | ✅ **Escrita y probada de punta a punta contra el SRI real** (firma, Recepción, Autorización) — el SRI la **rechazó** con `69: ERROR EN LA IDENTIFICACION DEL RECEPTOR` al anular una factura emitida a "CONSUMIDOR FINAL" (ver hallazgo abajo). El flujo técnico (XML válido, firma, envío, consulta) funciona; falta confirmar con un comprador identificado si el rechazo es por eso |
| `notaCredito_V1.1.0.xsd` | ✅ Vendorizado desde el mismo mirror que `factura_V2.1.0.xsd` (`xprl-gjf/sri-efactura-core`) — paquete Java propio (`xml.generado.notacredito`) porque comparte nombres de tipo con el XSD de factura pero con forma distinta |
| Envío por correo (`NotificadorCorreo`, `ConfiguracionCorreoFrame`) | ✅ Escrito y compila (jakarta.mail/SMTP) — botón "Enviar por correo" en el Historial, config propia en `correo.properties` (clave cifrada igual que el certificado). **No probado contra un servidor SMTP real todavía** |
| Reintento manual desde el Historial | ✅ Escrito — botón "Reintentar envío" para FACTURA en ERROR/RECHAZADO/ENVIADO, reusa `ConectorPrincipal.procesarTicket` (relee el ticket de ECOPos, así que recoge correcciones hechas desde la última vez) |

## ⚠️ Hallazgo importante: el WSDL oficial no coincide con el servidor real

Al probar `SoapClient` contra el servidor de pruebas real (`celcer.sri.gob.ec`,
no un mock), la desserialización fallaba con
`Unmarshalling Error: elemento inesperado (URI:"", local:"comprobante")`.

Causa: en ambos WSDL, los elementos `<comprobante>`, `<mensaje>` y
`<autorizacion>` estaban declarados como `ref="tns:..."` (referencia a un
elemento global), lo que por regla de XML Schema los obliga a llevar
namespace en el XML sin importar `elementFormDefault`. Pero **el servidor
real del SRI los devuelve sin namespace** — un desajuste entre el contrato
publicado y la implementación real del servicio (no es un error de este
proyecto). Confirmado inspeccionando el XML crudo de la respuesta con una
petición SOAP armada a mano.

**Arreglo aplicado**: se editaron los dos `.wsdl` locales
(`src/main/resources/wsdl/*.wsdl`) para declarar esos tres elementos como
locales (`name="..." type="tns:..."`) en vez de `ref=`, para que sigan la
regla `elementFormDefault="unqualified"` del propio esquema y coincidan con
lo que el servidor realmente envía. Verificado con dos llamadas reales
(Recepción con un XML inválido a propósito, Autorización con una clave de
acceso inexistente) — ambas desserializan correctamente ahora.

Si en algún momento se vuelve a descargar el WSDL "fresco" del SRI, hay que
re-aplicar este mismo ajuste antes de regenerar los stubs.

## ✅ Verificado de punta a punta con un certificado real: el SRI autoriza el comprobante

Se ejecutó `ConectorPrincipal.procesarTicket(...)` contra datos 100% reales:
un ticket real cerrado desde EcoPos (botón "Facturar SRI: SI" en la pantalla
de venta — ver más abajo), el jar empaquetado del conector (no el classpath
de test de Maven), un certificado `.p12` real acreditado por **Security
Data S.A.** (entidad certificadora ecuatoriana), y el servidor de pruebas
real del SRI (`celcer.sri.gob.ec`), en ambiente **Pruebas**. Resultado:

```
estado=AUTORIZADO
claveAcceso=0707202601045002268600110010010000000018046172214
numeroAutorizacion=0707202601045002268600110010010000000018046172214
```

Llegar hasta aquí requirió encontrar y arreglar **cuatro bugs reales**,
cada uno confirmado repitiendo la misma prueba real después del arreglo:

1. **El jar empaquetado no arrancaba en absoluto** (`SecurityException:
   Invalid signature file digest for Manifest main attributes`) -
   `maven-shade-plugin` fusionaba los `META-INF/*.SF` de una dependencia
   firmada sin quitarlos. Arreglado con un filtro de exclusión en el
   shade-plugin.
2. **`SoapClient` lanzaba un `NullPointerException` sin mensaje** dentro de
   CXF (`WSDLServiceFactory`) al construirse desde el jar empaquetado (no
   pasaba corriendo por Maven). Causa real: `cxf-rt-wsdl` registra
   `WSDLManagerImpl` en su propio `META-INF/cxf/bus-extensions.txt`, pero
   varios jars de CXF declaran ese mismo archivo y el shading se quedaba
   solo con uno, perdiendo el registro (`Bus.getExtension(WSDLManager.class)`
   volvía `null`). Arreglado con un `AppendingTransformer` para ese recurso,
   más copiar el WSDL a un archivo temporal real antes de construir el
   `Service` de JAX-WS (defensa extra contra rarezas de URLs `jar:`).
3. **Los reintentos de un ticket ya procesado no actualizaban nada en la
   base** - `TicketComprobanteMapper.map` siempre genera un `Comprobante`
   con un id `UUID` nuevo, pero `ConectorPrincipal` solo reutilizaba el
   `secuencial`/`claveAcceso` viejos, nunca el id de la fila ya insertada -
   el `UPDATE ... WHERE id = ?` de `actualizarProgreso()` apuntaba a un id
   que nunca existió, y la tabla se quedaba mostrando el resultado del
   primerísimo intento para siempre. Arreglado agregando `Comprobante.setId`
   y restaurando el id de la fila existente al reintentar.
4. **El SRI real rechazaba el XML con `35: ARCHIVO NO CUMPLE ESTRUCTURA
   XML`**, incluso con un certificado acreditado válido. Causa real:
   `nombreComercial`/`dirEstablecimiento`/`contribuyenteEspecial` son
   `minOccurs="0"` en el XSD, pero sus tipos también exigen `minLength >= 1`
   - un valor configurado en blanco (no nulo) se serializaba como una
   etiqueta vacía (`<nombreComercial/>`), que el SRI trata igual que una
   estructura malformada. Arreglado tratando blanco como ausente (`null`,
   que JAXB omite del todo) en `ComprobanteXmlMapper`.

**Cómo reproducir esta prueba:** `ConectorPrincipalManualE2ETest`
(`src/test/java/com/openbravo/pos/sri/ConectorPrincipalManualE2ETest.java`)
documenta el procedimiento completo (queda `@Disabled` a propósito, no corre
en CI). Recuerda borrar los datos de prueba de la base `ecopos` al terminar
— no lo hace el test automáticamente.

## ⚠️ Hallazgo real: el SRI rechaza una Nota de Crédito a "CONSUMIDOR FINAL"

Se ejecutó `AnulacionService.anular(...)` contra datos 100% reales (mismo
certificado, mismo servidor de pruebas real, misma factura ya AUTORIZADA de
la prueba anterior). El flujo técnico funcionó de punta a punta - XML
válido, firma correcta, Recepción respondió `RECIBIDA` - pero la
Autorización volvió:

```
Estado: NO AUTORIZADO | 69: ERROR EN LA IDENTIFICACION DEL RECEPTOR
```

La factura que se intentó anular fue emitida a **CONSUMIDOR FINAL**
(`tipoIdentificacionComprador=07`, `identificacionComprador=9999999999999`) -
exactamente esos mismos datos, sin cambiar nada, es lo que el SRI ya había
AUTORIZADO en la factura original. La hipótesis más probable (**no
confirmada** - no se pudo consultar la documentación oficial del SRI desde
este entorno de red, y no se volvió a probar con un comprador identificado
para no gastar otra transacción real) es que **el SRI no permite emitir una
Nota de Crédito contra un comprador "consumidor final" genérico** - a
diferencia de una factura, una nota de crédito necesitaría un comprador
identificable (cédula/RUC real) para ser trazable.

**Antes de confiar en la anulación para facturas reales**: prueba
`AnulacionService` contra una factura emitida a un comprador con
cédula/RUC real (no consumidor final) y confirma si el rechazo 69
desaparece. Si se confirma la hipótesis, `AnulacionFrame` debería advertir
o bloquear el intento cuando la factura original sea a consumidor final,
en vez de dejar que el usuario descubra el rechazo despues de una llamada
real al SRI.

## Siguiente paso inmediato

1. **Confirmar el hallazgo de arriba** (Nota de Crédito vs. consumidor
   final) con una factura real a un comprador identificado.
2. **Probar el ambiente de Producción** una vez que el negocio esté listo
   para emitir facturas reales (hasta ahora todo se probó en `PRUEBAS` a
   propósito).
3. **Corre `ConfiguracionFrame` tú mismo y confirma que el layout se ve bien**
   (no se pudo verificar visualmente en esta sesión, ver nota abajo) -
   aunque ya se confirmó indirectamente que funciona: se usó para guardar
   los datos reales del emisor que llevaron a la prueba de arriba.
4. Crear `config/conexion.properties` (host/puerto/baseDatos/usuario/clave)
   en la instalación real donde corra el conector — `ConectorPrincipal`
   usa `localhost`/`3306`/`ecopos`/`root`/`` como valores por defecto si el
   archivo no existe, pensado para XAMPP local, no para producción.
5. Dejar `ConectorPrincipal` corriendo de forma continua junto a EcoPos
   (tarea programada de Windows, servicio, o similar) - hasta ahora solo se
   ha probado lanzándolo a mano para cada ticket.
6. **Probar el envío por correo contra un servidor SMTP real** (solo se
   verificó que compila, `NotificadorCorreo` no se ha probado con
   credenciales SMTP reales todavía).

## Nota: verificación visual de `ConfiguracionFrame` no realizada

Se intentó lanzar la ventana y capturar una captura de pantalla para
verificar el layout (como se hace normalmente con cambios de UI), pero el
entorno de ejecución de esta sesión no comparte la sesión de escritorio
interactiva con el proceso Java: `GraphicsEnvironment.isHeadless()` da
`false` y la ventana obtiene bounds correctos, pero una captura con
`Robot` (desde el mismo proceso) devolvió contenido de la pantalla real
del usuario, no de la ventana. Se verificó que compila, que la carga/guarda
contra `ConfiguracionLoader` es correcta, y que no hay excepciones al
construirse — pero el layout visual (alineación, tamaños, textos cortados)
no está confirmado. Corre `com.openbravo.pos.sri.ui.ConfiguracionFrame`
localmente antes de darlo por bueno.

## Notas técnicas de esta iteración (2026-07-06)

- **WSDL real**: descargados con `curl` directo desde
  `celcer.sri.gob.ec` (ambiente de pruebas) — ese subdominio SÍ responde
  desde esta red, a diferencia de `www.sri.gob.ec` (portal principal, donde
  vive la documentación/XSD para descarga humana), que da timeout total.
- **XSD real**: como `www.sri.gob.ec` no fue alcanzable, se usó el mirror
  open-source (Apache-2.0) `xprl-gjf/sri-efactura-core` en GitHub, que
  vendoriza los XSD oficiales del SRI. Verificado campo por campo contra la
  ficha técnica v2.32 que compartió el usuario — coincide exactamente.
- `cxf-codegen-plugin` 4.0.4 requiere Java 17; este módulo compila con
  JDK 11 (mismo que ECOPos, aunque corre en proceso separado), así que
  se bajó a **CXF 3.6.4** (soporta Java 11+).
- **Conflicto de namespace JAXB entre generadores**: CXF 3.6.4 genera sus
  stubs usando `javax.xml.bind` (JAXB clásico), pero `jaxb2-maven-plugin`
  3.1.0 por defecto genera `jakarta.xml.bind` — ambos en el mismo proyecto
  chocan. Se resolvió bajando `jaxb2-maven-plugin` a **2.5.0** (también
  genera `javax.xml.bind`) y ajustando las dependencias del proyecto a
  `javax.xml.bind:jaxb-api:2.3.1` + `com.sun.xml.bind:jaxb-impl:2.3.9`,
  de modo que los generadores de dominio propio/CXF/JAXB usen el mismo
  namespace. Si en el futuro se sube a JDK 17 y CXF 4.x, hay que revertir
  este cambio a `jakarta.xml.bind-api`.
- **xades4j 2.4.0 necesita el namespace `jakarta.xml.bind` por dentro**
  (para su propio marshalling interno de las propiedades XAdES), un
  namespace *distinto* al que usan CXF/JAXB en este proyecto. No es un
  choque real (son paquetes de nombres diferentes que coexisten en el mismo
  classpath), pero hacen falta **ambos** declarados: la API
  (`jakarta.xml.bind:jakarta.xml.bind-api:4.0.2`) y un runtime real que la
  implemente (`org.glassfish.jaxb:jaxb-runtime:4.0.4`) — por eso el runtime
  javax se cambió de `org.glassfish.jaxb:jaxb-runtime` a
  `com.sun.xml.bind:jaxb-impl` (mismo groupId:artifactId no puede tener dos
  versiones a la vez en el classpath).
- **La firma XAdES-BES por defecto de xades4j usa SHA-256**, pero la ficha
  técnica del SRI (sección 6.8, Anexo 14) exige **RSA-SHA1** explícitamente.
  Se configuró `SignatureAlgorithms` en `XadesBesSigner` para forzar
  `rsa-sha1`/`sha1` en vez del default — verificado con una firma real y un
  test dedicado (`usaRsaSha1ComoExigeLaFichaTecnica`).
- **El DOM no sabe que `id="comprobante"` es un atributo de tipo ID** sin
  DTD/XSD durante el parseo — sin `Element.setIdAttribute("id", true)`
  explícito antes de firmar, XML-DSig falla con
  `Cannot resolve element with ID comprobante` al intentar resolver la
  referencia `#comprobante` del Anexo 14.

## Cómo se prueba de forma independiente

Este módulo no requiere que ECOPos esté corriendo para compilarse ni para
correr sus propios tests unitarios — solo necesita acceso de red a la misma
base de datos MySQL para las pruebas de integración (lectura de `TICKETS`/
`RECEIPTS`/`TICKETLINES`, y CRUD de `ecopos_sri_comprobantes`).
