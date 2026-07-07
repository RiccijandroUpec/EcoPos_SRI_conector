# ecopos-sri-connector

Módulo Java **independiente** (Maven, propio jar) que emite facturación
electrónica ante el SRI (Servicio de Rentas Internas, Ecuador) a partir de
las ventas registradas en ECOPos.

**¿Vas a instalar esto en la computadora de un negocio?** Ve directo a
[`INSTALAR.md`](INSTALAR.md) - esta guía de abajo es para quien desarrolla
el conector, no para quien lo instala.

## Tecnologías y herramientas

| Categoría | Herramienta | Versión | Para qué |
|---|---|---|---|
| Lenguaje / build | Java | 11 | Mismo bytecode que ECOPos, aunque corre en JVM separada |
| | Apache Maven | 3.9.16 | Build del módulo (independiente del Ant de ECOPos) |
| Generación de código | JAXB (`javax.xml.bind:jaxb-api` + `org.glassfish.jaxb:jaxb-runtime`) | 2.3.1 / 2.3.9 | Genera las 38 clases del comprobante (`Factura`, `InfoTributaria`, `Detalle`...) a partir de `factura_V2.1.0.xsd` — nunca se escriben a mano |
| | `org.codehaus.mojo:jaxb2-maven-plugin` (goal `xjc`) | 2.5.0 | Ejecuta la generación JAXB en cada build (`mvn generate-sources`) |
| | Apache CXF (`cxf-rt-frontend-jaxws`, `cxf-rt-transports-http`, `cxf-codegen-plugin`) | 3.6.4 | Genera los stubs Java del cliente SOAP a partir del WSDL real del SRI (`wsdl2java`), y provee el runtime JAX-WS para invocarlos |
| Firma electrónica | xades4j (`com.googlecode.xades4j`) | 2.4.0 | Firma XAdES-BES sobre el XML del comprobante |
| Base de datos | MySQL Connector/J (`com.mysql:mysql-connector-j`) | 8.0.33 | Lectura de `TICKETS`/`RECEIPTS`/`TICKETLINES` de ECOPos y CRUD de `ecopos_sri_comprobantes` |
| Logging | SLF4J + Logback | 2.0.13 / 1.5.6 | Logging del conector (independiente del logging de ECOPos) |
| RIDE (PDF) | Apache PDFBox + ZXing | 2.0.31 / 3.5.3 | Genera la representación impresa (RIDE) de facturas y notas de crédito, con código de barras GS1-128 de la clave de acceso. Ambas Apache-2.0 (se evitó iText 7 a propósito por su licencia AGPL) |
| Correo | `com.sun.mail:jakarta.mail` | 2.0.1 | Envío del XML+RIDE al comprador (manual desde el Historial, o automático al quedar AUTORIZADO) |
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
- **Dos mecanismos de persistencia distintos, no confundir uno con otro**:
  (a) `sri-conector/facturacion-global.properties` (clave `activo`) es el
  interruptor GLOBAL de si se factura o no - lo escriben los botones
  "SRI: SI/NO" de la pantalla de venta, lo lee `Ticket.Close.xml`, aplica a
  todas las ventas por igual; (b) el correo del cliente para una venta en
  particular sí es por-ticket (propiedad `sri.email` en
  `RECEIPTS.ATTRIBUTES`, formato `Properties.storeToXML` de ECOPos), porque
  es un dato de esa venta específica, no una configuración general.
- El estado de cada comprobante vive en `ecopos_sri_comprobantes` (tabla
  nueva, sin FK real hacia las tablas de ECOPos — ver el `.sql` para el
  razonamiento). Los reintentos ante fallas de red/SRI son responsabilidad
  de este módulo, nunca de ECOPos.

## Estado actual

| Pieza | Estado |
|---|---|
| Estructura Maven + `pom.xml` | ✅ Listo, `mvn clean test` verificado en verde |
| Hook con ECOPos (script `Ticket.Close` + carpeta de flags) | ✅ Listo y probado (arranque de ECOPos verificado, sintaxis BeanShell validada con intérprete real) |
| Tabla `ecopos_sri_comprobantes` | ✅ Creada y en uso real contra el MySQL de la instalación (más la migración `002_agregar_nota_credito.sql` para Nota de Crédito, también ya ejecutada) |
| `VigilantePendientes` (detección de ventas cerradas) | ✅ Escrito, probado con tickets reales (dispara `ConectorPrincipal.procesarTicket` de verdad al cerrar una venta) |
| Clases de dominio (`Comprobante`, `DatosEmisor`, `Cliente`, `DetalleFactura`, `ImpuestoDetalle`, `Pago`, enums) | ✅ Escritas, compilan, usadas en los flujos reales de factura y nota de crédito |
| `ClaveAccesoGenerator` (clave de 49 dígitos, módulo 11) | ✅ Escrito + 5 tests unitarios en verde — el dígito verificador quedó implícitamente validado: el SRI real aceptó (RECIBIDA) varias claves generadas por este código, tanto de facturas como de la nota de crédito |
| `TicketReader` / `ComprobanteRepository` (lectura ECOPos + CRUD tabla propia) | ✅ Escritos y **probados extensamente contra MySQL real** (no solo compilación) — incluye la lectura de `RECEIPTS.ATTRIBUTES` para el correo del cliente |
| WSDL de Recepción/Autorización | ✅ **Descargados el 2026-07-06 desde `celcer.sri.gob.ec` (ambiente de pruebas), confirmados oficiales por la ficha técnica v2.32 (sección 7.2.1)**. `cxf-codegen-plugin` genera los stubs reales, `mvn clean test` en verde |
| Cliente SOAP (stubs generados: `RecepcionComprobantesOffline`, `AutorizacionComprobantesOffline`) | ✅ Clases generadas y compilando (`target/generated-sources/cxf/...`). El wrapper `SoapClient` que las invoca ya está escrito y probado — ver fila siguiente |
| XSD del comprobante (factura v2.1.0) | ✅ **`factura_V2.1.0.xsd` real integrado** (ver `src/main/resources/xsd-README.md` para procedencia). `jaxb2-maven-plugin` genera 38 clases (`Factura`, `InfoTributaria`, `Detalle`, `Reembolsos`, etc.) en cada build, `mvn clean test` en verde |
| Mapeo `Comprobante` (dominio) → clases JAXB → XML (`com.openbravo.pos.sri.xml`) | ✅ **`ComprobanteXmlMapper` + `FacturaXmlWriter` escritos y probados** — 6 tests, incluyendo generación de XML real verificado campo por campo contra la ficha técnica (`infoTributaria`, `infoFactura`, `detalles`, impuestos con `codigoPorcentaje` resuelto vía `CodigoPorcentajeIva`, montos siempre a 2 decimales) |
| Mapeo `TicketCrudo` → `Comprobante` (`TicketComprobanteMapper`) | ✅ **Escrito y probado** — 5 tests, más una prueba manual de la cadena completa `TicketCrudo → Comprobante → XML` con dos líneas de distinta tarifa (15% y exenta), agrupadas correctamente en dos `<totalImpuesto>`. Resuelve tipo de identificación del cliente por longitud (`TipoIdentificacionResolver`) y forma de pago por heurística de nombre (`FormaPagoResolver`) — **ambas heurísticas deben revisarse contra los datos reales de cada instalación antes de producción** |
| `secuencial` en `ecopos_sri_comprobantes` | ✅ Columna agregada (no existía) + `ComprobanteRepository.siguienteSecuencial()` (MAX+1, sin bloqueo — suficiente mientras el conector procese un ticket a la vez) |
| `SoapClient` (envoltorio sobre los stubs CXF) | ✅ **Escrito y probado contra el servidor real de pruebas del SRI** (`celcer.sri.gob.ec`, no solo compilación) — ver hallazgo importante abajo |
| Firma XAdES-BES (`XadesBesSigner`) | ✅ **Escrito y probado con una firma real** (certificado autofirmado generado con `keytool` en el test, no un mock) — 3 tests, incluye verificar que usa **RSA-SHA1** (no el SHA-256 por defecto de xades4j) tal como exige la sección 6.8/Anexo 14 de la ficha técnica. Ver notas técnicas abajo sobre el conflicto de runtime JAXB con xades4j |
| `ConfiguracionLoader` (lee/escribe `datos-emisor.properties` ↔ `DatosEmisor`) | ✅ **Escrito y probado** — 4 tests con round-trip real a disco (`@TempDir`), incluyendo verificar que la clave del certificado nunca queda en texto plano en el archivo (`ClaveCifrador`, AES-GCM) |
| Pantalla Swing de configuración (`ConfiguracionFrame`) | ✅ Escrita y compila; carga/guarda contra `ConfiguracionLoader` — se usó para guardar los datos reales del emisor que llevaron al AUTORIZADO real de abajo, así que su lógica sí está probada. **No se pudo verificar visualmente en este entorno** (ver nota al final del documento). Se abre desde EcoPos vía botón (Administración > Sistema), ver fila siguiente |
| Clase orquestadora `ConectorPrincipal` (une todo en un proceso que corra continuamente) | ✅ **Escrita y probada de punta a punta contra servicios reales, con resultado AUTORIZADO** (MySQL real + certificado real acreditado + servidor real de pruebas del SRI, no mocks) — ver hallazgo abajo con los 4 bugs reales encontrados y corregidos en el camino. **Corriendo de verdad como proceso persistente** desde `sri-conector/` (no solo invocado por harnesses de prueba) — encontró y corrigió un quinto bug real (carpeta de pendientes mal resuelta, ver hallazgo más abajo). Sigue faltando dejarlo como tarea programada/servicio de Windows que sobreviva un reinicio |
| Botón en EcoPos para abrir `ConfiguracionFrame` (Administración > Sistema) | ✅ **Escrito y probado** — hook data-only (`SriConnectorConfig.bs` + `Menu.Root`/`Role.Administrator` en el repo de EcoPos), lanza el jar del conector como proceso externo. Confirmado con un lanzamiento real (título de ventana verificado vía la tabla de procesos del SO) |
| Botones "Facturar SRI: SI/NO" en la pantalla de venta de EcoPos | ✅ **Rediseñados como interruptor GLOBAL persistente, con íconos** — ya no son botones de solo texto ni marcan un atributo por-ticket (eso reseteaba a "NO" en cada venta nueva). Ahora escriben en un archivo compartido (`sri-conector/facturacion-global.properties`, clave `activo`) que `Ticket.Close.xml` lee directo: una vez en SI, aplica a **todas** las ventas hasta que alguien presione NO (elegido así explícitamente por el usuario, sin excepción por ticket). Íconos propios (`img.sriinvoiceon`/`img.sriinvoiceoff`, check verde / X gris, ver "Hallazgo: límite del framework de botones" abajo) insertados como filas nuevas en `RESOURCES`. El botón SI sigue ofreciendo capturar el correo del cliente para esa venta puntual si su perfil no tiene uno guardado. El ticket siempre se imprime igual, sin importar este ajuste. **No verificado visualmente en la pantalla de venta real** (mismo límite de sandbox que `ConfiguracionFrame`, ver nota abajo) |
| Historial de facturación (`HistorialFrame`) | ✅ **Escrito y probado** — lista todo `ecopos_sri_comprobantes` (facturas y notas de crédito), colorea por estado, y marca en naranja los comprobantes ENVIADO/ERROR con más de 24h sin resolverse (aviso operativo, no una cita textual de un plazo legal del SRI) |
| RIDE en PDF (`RideGenerator` / `RideNotaCreditoGenerator`) | ✅ **Escrito y verificado** (render-a-imagen con `PDFRenderer`, no solo extracción de texto) contra un layout de referencia real de otro sistema — cubre fecha/hora de autorización, subtotales por tarifa/tipo de impuesto (con IVA/ICE/IRBPNR etiquetados por su código real), código auxiliar y detalle adicional por línea, subsidio, e Información Adicional |
| **Nota de Crédito (anulación de facturas)** (`AnulacionService`, `NotaCreditoXmlMapper`, `AnulacionFrame`) | ✅ **Escrita y probada de punta a punta contra el SRI real** (firma, Recepción, Autorización) — el SRI la **rechazó** con `69: ERROR EN LA IDENTIFICACION DEL RECEPTOR` al anular una factura emitida a "CONSUMIDOR FINAL" (ver hallazgo abajo). El flujo técnico (XML válido, firma, envío, consulta) funciona; falta confirmar con un comprador identificado si el rechazo es por eso |
| `notaCredito_V1.1.0.xsd` | ✅ Vendorizado desde el mismo mirror que `factura_V2.1.0.xsd` (`xprl-gjf/sri-efactura-core`) — paquete Java propio (`xml.generado.notacredito`) porque comparte nombres de tipo con el XSD de factura pero con forma distinta |
| Envío por correo (`NotificadorCorreo`, `ConfiguracionCorreoFrame`) | ✅ Escrito y compila (jakarta.mail/SMTP) — botón "Enviar por correo" en el Historial, config propia en `correo.properties` (clave cifrada igual que el certificado). **No probado contra un servidor SMTP real todavía** |
| Envío automático al cliente al quedar AUTORIZADO | ✅ **Escrito** (`ConectorPrincipal.intentarEnvioAutomaticoPorCorreo`) — si el cliente del ticket tiene correo (el de su perfil `CUSTOMERS.EMAIL`, o el que el cajero ingresó al activar "Facturar SRI: SI" si no tenía uno) y existe `config/correo.properties`, se le manda el XML+RIDE apenas el SRI autoriza, sin acción manual. El correo se lee de `RECEIPTS.ATTRIBUTES` (formato `Properties.storeToXML` de ECOPos, sin depender de sus clases). Un fallo de correo nunca afecta el resultado ya resuelto ante el SRI. **No probado contra un servidor SMTP real todavía** (mismo pendiente que el botón manual) |
| Reintento manual desde el Historial | ✅ Escrito — botón "Reintentar envío" para FACTURA en ERROR/RECHAZADO/ENVIADO, reusa `ConectorPrincipal.procesarTicket` (relee el ticket de ECOPos, así que recoge correcciones hechas desde la última vez) |
| **Servicio de Windows** (`servicio-windows/`, WinSW) | ✅ **Instalado y probado de verdad** — `ConectorPrincipal` corre como servicio real (arranque automático, se reinicia solo si se cae), no como proceso manual en una terminal. Probado instalar/iniciar/detener/reiniciar, logs con rotación. Pendiente: probar en una máquina limpia distinta a esta |
| **Instalador auto-contenido** (`InstaladorEcoPos`) | ✅ **Escrito y probado dos veces de punta a punta** (contra una base de prueba limpia simulando una instalación existente, y contra la base real de este negocio) — sincroniza de forma idempotente `Menu.Root`/`Ticket.Buttons`/`Ticket.Close`/los scripts SI-NO/sus íconos/permisos de rol, y crea la tabla propia del conector. No depende de tener el repo de EcoPos a mano (plantillas empaquetadas en este jar, `src/main/resources/plantillas-ecopos/`) |
| **Instalación nueva de EcoPos (desde cero)** | ✅ `MySQL-create.sql` (repo de EcoPos) actualizado para incluir los 4 recursos que faltaban (`script.SriInvoiceOn/Off`, `img.sriinvoiceon/off`) - `Menu.Root`/`Ticket.Buttons`/`Ticket.Close`/`Role.*` ya estaban al día en sus archivos plantilla. **No probado instalando un EcoPos realmente desde cero** (solo se verificó leyendo el script SQL) - `InstaladorEcoPos` de todas formas crea la tabla propia del conector después, en ambos escenarios |

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

## ⚠️ Hallazgo real: el framework de botones de ECOPos no soporta color dinámico

Al rediseñar los botones "Facturar SRI: SI/NO" (pedido del usuario: menos
espacio, un color cuando está activado y otro cuando no, íconos menos
pixelados), se investigó `JPanelButtons`/`JButtonFunc` (código de ECOPos,
solo lectura) para ver si un único botón podía cambiar de color en vivo
según el estado actual. **No se puede sin modificar un `.java` de
ECOPos**: los botones de la barra (`JButtonFunc extends JButton`) se crean
**una sola vez**, al abrir la pantalla de venta, a partir de la
configuración XML estática (`Ticket.Buttons.xml`) - no hay ningún
mecanismo de repintado ligado al estado del ticket o a un archivo externo.
Cambiar esto violaría la regla de "solo extensiones de datos" que se ha
mantenido en todo este proyecto (ver sección de arquitectura arriba).

**Solución adoptada dentro del límite**: dos botones separados con ícono
de color fijo (verde=activar, gris=desactivar) en vez de un único
interruptor dinámico, más un aviso emergente grande cada vez que cambia el
estado (para que quede claro que el cambio es global, no solo del ticket
actual).

**Lección de diseño de íconos para esta app**: el primer boceto (documento
con líneas + un círculo con check superpuesto, dibujado a 256px) se veía
bien en grande pero quedaba borroso/ilegible una vez reducido a 24×24 (el
tamaño real que usa `ThumbNailBuilder` en la barra de botones - interpolación
bilinear). Se confirmó renderizando una previsualización real a 24×24 con
la misma interpolación que usa la app, no solo mirando la fuente en alta
resolución. El diseño final (fondo con degradado + un solo trazo blanco
grueso, check o X, sin más elementos) sí se lee bien a ese tamaño. **Para
cualquier ícono futuro de ~24px en esta app: previsualizar al tamaño real
antes de dar por buena la fuente en alta resolución.**

Las imágenes de ECOPos también son **DB-only** (`DataLogicSystem.getResource`,
sin fallback a classpath, igual que los scripts `Ticket.Buttons`/`Ticket.Close`) -
se insertaron como filas nuevas en `RESOURCES` (`img.sriinvoiceon`/`off`,
`RESTYPE=1`), verificado leyendo los bytes de vuelta desde la base y
comprobando que el tamaño coincide exactamente con el archivo fuente
(5670/5274 bytes). Los PNG fuente quedan en
`src/main/resources/iconos-ecopos/` de este repo solo como respaldo/para
regenerarlos - no se cargan desde ahí en tiempo de ejecución.

## ⚠️ Hallazgo real: `ConectorPrincipal` vigilaba la carpeta equivocada

Al arrancar `ConectorPrincipal` de verdad como proceso persistente por
primera vez (antes solo se había probado invocando `procesarTicket(...)`
directamente desde harnesses de prueba, nunca corriendo `main()` de
principio a fin en segundo plano), el log mostró:

```
Vigilando carpeta de pendientes: C:\xampp\htdocs\EcoPos\sri-conector\sri-conector\pendientes
```

`sri-conector` duplicado - carpeta que nunca iba a tener nada adentro.
Causa: `CARPETA_PENDIENTES_POR_DEFECTO` estaba fijada a
`"sri-conector/pendientes"`, asumiendo un directorio de trabajo (`dirname.path`
de EcoPos, un nivel arriba de `sri-conector/`) distinto al que realmente
usan `CONFIG_EMISOR_POR_DEFECTO`/`CONFIG_CONEXION_POR_DEFECTO`/
`CONFIG_CORREO_POR_DEFECTO` (todas `"config/..."`, relativas a estar
parado *dentro* de `sri-conector/` - el mismo directorio de trabajo con el
que se lanzan `ConfiguracionFrame`/`HistorialFrame` desde sus hooks
`.bs`). Dos convenciones de ruta contradictorias en la misma clase.

**Arreglado**: `CARPETA_PENDIENTES_POR_DEFECTO` ahora es solo `"pendientes"`,
consistente con las demás rutas por defecto. Verificado relanzando el
proceso real: el log ahora muestra `Vigilando carpeta de pendientes:
C:\xampp\htdocs\EcoPos\sri-conector\pendientes` (sin duplicar), y un ticket
ya AUTORIZADO que tenía un flag viejo pendiente se procesó (idempotente,
no reintentó de más) y el flag se limpió solo.

De paso se encontró que **MySQL de XAMPP se había caído** (el proceso
`mysqld` seguía "vivo" pero ya no aceptaba conexiones en el puerto 3306,
sin nada en el log de error que lo explicara) - se reinició con
`mysql_start.bat` y los datos quedaron intactos (verificado contando filas
en `ecopos_sri_comprobantes`/`TICKETS` antes y después). Sin relación con
el bug de la ruta, pero hubiera bloqueado igual la primera prueba real del
servicio persistente si no se corregía.

## Siguiente paso inmediato

**Pendientes que requieren confirmación/prueba humana (nada de esto se
puede resolver solo con más código):**

1. **Confirmar el hallazgo de Nota de Crédito vs. consumidor final** con
   una factura real a un comprador identificado (cédula/RUC, no
   "consumidor final") - ver hallazgo arriba. Mientras no se confirme, no
   confíes en la anulación para facturas emitidas a consumidor final.
2. **Verificar visualmente la pantalla de venta de EcoPos** (tamaño/color
   de los botones "SRI: SI/NO" rediseñados) y `ConfiguracionFrame` (layout
   general) - ninguna de las dos se pudo confirmar visualmente en este
   entorno (ver nota abajo). Ambas compilan y su lógica está probada, pero
   el aspecto real en pantalla no.
3. **Probar el envío por correo contra un servidor SMTP real** (manual
   desde el Historial, y automático al quedar AUTORIZADO) - solo se
   verificó que compila y que arma el mensaje correctamente,
   `NotificadorCorreo` nunca se conectó a un servidor SMTP de verdad.
4. **Probar el ambiente de Producción** una vez que el negocio esté listo
   para emitir facturas reales (hasta ahora todo se probó en `PRUEBAS` a
   propósito, incluida la Nota de Crédito).
5. Crear `config/conexion.properties` (host/puerto/baseDatos/usuario/clave)
   en la instalación real donde corra el conector — `ConectorPrincipal`
   usa `localhost`/`3306`/`ecopos`/`root`/`` como valores por defecto si el
   archivo no existe, pensado para XAMPP local, no para producción.
6. **Probar una instalación de EcoPos realmente desde cero** (no una
   actualización) con `MySQL-create.sql` ya actualizado, para confirmar que
   los botones/menús/permisos quedan andando de una sin correr
   `InstaladorEcoPos` a mano - solo se verificó leyendo el script SQL, no
   ejecutándolo contra una base nueva de verdad.
7. Probar `servicio-windows/` (WinSW) en una máquina distinta a esta -
   aquí se probó instalar/iniciar/detener/reiniciar con resultado
   correcto, pero siempre en la misma máquina de desarrollo.

**Limitaciones conocidas, no bloqueantes pero buenas de tener presentes:**

- **Sin tests automatizados para nada de lo agregado después del hito de
  factura AUTORIZADO**: el historial, el RIDE, la Nota de Crédito, el
  correo y el rediseño de botones se verificaron con pruebas manuales
  puntuales contra servicios/base de datos reales (documentadas en este
  README), no con tests JUnit nuevos - la suite automatizada sigue en 24
  tests, sin cambios desde antes de todo esto. Si se refactoriza algo de
  esa superficie más nueva, no hay red de seguridad automatizada todavía.
- **Un solo establecimiento/punto de emisión por instalación**:
  `ComprobanteRepository.siguienteSecuencial()` calcula un único
  consecutivo por tipo de comprobante, sin filtrar por
  estab/ptoEmi. Si el negocio abre una segunda sucursal o caja con su
  propio punto de emisión, esto hay que revisarlo antes (el SRI exige un
  consecutivo independiente por cada combinación estab-ptoEmi-codDoc).
- **Nota de Crédito solo cubre anulación TOTAL**: copia el detalle y el
  valor completo de la factura original tal como el SRI la autorizó, no
  permite acreditar solo algunas líneas o un monto parcial.
- **`TipoIdentificacionResolver`/`FormaPagoResolver`** (heurísticas para
  adivinar tipo de identificación por longitud y forma de pago por texto
  libre de ECOPos) siguen sin validarse contra datos reales de clientes/
  pagos de esta instalación específica - revisar antes de confiar en ellas
  a ciegas en producción.
- **Fuera de alcance actual** (el usuario los descartó explícitamente al
  construir la Nota de Crédito): Nota de Débito, Guía de Remisión,
  Comprobante de Retención, Liquidación de Compra. Se puede agregar
  soporte si el negocio los llega a necesitar - la arquitectura (XSD propio
  por tipo, `EnvioComprobanteService`/`FacturaXmlReader` compartidos) ya
  está pensada para que sea un mapeo nuevo, no un rediseño.

## Nota: cosas que no se pudieron verificar visualmente en este entorno

Tanto `ConfiguracionFrame` como los botones rediseñados de la pantalla de
venta de EcoPos (`Ticket.Buttons.xml`) tienen su lógica probada (compilan,
cargan/guardan correctamente, la imagen se lee bien desde la base) pero
**su aspecto visual real nunca se confirmó con una captura de pantalla**.
Se intentó lanzar `ConfiguracionFrame` y capturar la ventana con `Robot`,
pero el entorno de ejecución de esta sesión no comparte la sesión de
escritorio interactiva con el proceso Java: `GraphicsEnvironment.isHeadless()`
da `false` y la ventana obtiene bounds correctos, pero la captura devolvió
el contenido de la pantalla real del usuario, no el de la ventana. Corre
`com.openbravo.pos.sri.ui.ConfiguracionFrame` localmente, y abre la
pantalla de venta de EcoPos, antes de dar por bueno el aspecto visual de
cualquiera de las dos.

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
