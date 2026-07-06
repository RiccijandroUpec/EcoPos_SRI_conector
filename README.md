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
- **XSD** (`factura_V2.1.0.xsd` + `xmldsig-core-schema.xsd`): vendorizados desde el repo open-source (Apache-2.0) [`xprl-gjf/sri-efactura-core`](https://github.com/xprl-gjf/sri-efactura-core) — `www.sri.gob.ec` no es alcanzable desde este entorno de red. Verificado campo por campo contra la ficha técnica oficial v2.32.
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
| Mapeo `TicketCrudo` (lectura cruda de ECOPos) → `Comprobante` (dominio) | ⏳ Siguiente paso — falta resolver `codigoPrincipal`/`descripcion` desde `LineaCruda`, agrupar impuestos, generar la `ClaveAcceso` con el secuencial real, y construir `Cliente`/`Pago` desde `TicketCrudo` |
| Firma XAdES-BES | ⏳ Siguiente paso |
| Pantalla Swing de configuración | ⏳ Siguiente paso |

## Siguiente paso inmediato

1. Ejecuta `src/main/resources/sql/001_create_ecopos_sri_comprobantes.sql`
   contra la base `ecopos`.
2. Escribe el mapeo `TicketCrudo` → `Comprobante` (falta resolver el
   secuencial real y armar la `ClaveAcceso`, agrupar impuestos por tarifa
   para `totalesPorImpuesto`, y decidir `codigoPrincipal` desde
   `LineaCruda.referencia`).
3. Escribe el `SoapClient` que envuelve los stubs CXF ya generados
   (`RecepcionComprobantesOffline`, `AutorizacionComprobantesOffline`).
4. Firma XAdES-BES sobre el XML ya generado (`FacturaXmlWriter.toXml(...)`),
   antes de enviarlo.

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
  `javax.xml.bind:jaxb-api:2.3.1` + `org.glassfish.jaxb:jaxb-runtime:2.3.9`,
  de modo que los tres generadores (dominio propio, CXF, JAXB) usen el
  mismo namespace. Si en el futuro se sube a JDK 17 y CXF 4.x, hay que revertir
  este cambio a `jakarta.xml.bind-api`.

## Cómo se prueba de forma independiente

Este módulo no requiere que ECOPos esté corriendo para compilarse ni para
correr sus propios tests unitarios — solo necesita acceso de red a la misma
base de datos MySQL para las pruebas de integración (lectura de `TICKETS`/
`RECEIPTS`/`TICKETLINES`, y CRUD de `ecopos_sri_comprobantes`).
