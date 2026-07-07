package com.openbravo.pos.sri;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.openbravo.pos.sri.config.ConfiguracionLoader;
import com.openbravo.pos.sri.dominio.Comprobante;
import com.openbravo.pos.sri.dominio.DatosEmisor;
import com.openbravo.pos.sri.dominio.EstadoComprobante;
import com.openbravo.pos.sri.firma.XadesBesSigner;
import com.openbravo.pos.sri.repository.ComprobanteRepository;
import com.openbravo.pos.sri.repository.TicketCrudo;
import com.openbravo.pos.sri.repository.TicketReader;
import com.openbravo.pos.sri.scheduler.VigilantePendientes;
import com.openbravo.pos.sri.soap.SoapClient;
import com.openbravo.pos.sri.soap.autorizacion.Autorizacion;
import com.openbravo.pos.sri.soap.autorizacion.RespuestaComprobante;
import com.openbravo.pos.sri.soap.recepcion.RespuestaSolicitud;
import com.openbravo.pos.sri.xml.ComprobanteXmlMapper;
import com.openbravo.pos.sri.xml.FacturaXmlWriter;
import com.openbravo.pos.sri.xml.TicketComprobanteMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Orquestador que conecta todas las piezas del conector en un proceso
 * corriendo continuamente: {@link VigilantePendientes} recibe los flags que
 * escribe el hook {@code Ticket.Close} de ECOPos, {@link TicketReader} lee el
 * ticket (solo lectura), {@link TicketComprobanteMapper} +
 * {@link ComprobanteXmlMapper}/{@link FacturaXmlWriter} generan el XML,
 * {@link XadesBesSigner} lo firma, {@link SoapClient} lo envia al SRI, y
 * {@link ComprobanteRepository} persiste el progreso en la tabla propia
 * {@code ecopos_sri_comprobantes}.
 *
 * Ademas del procesamiento inmediato disparado por el flag, un
 * {@link ScheduledExecutorService} reintenta periodicamente los comprobantes
 * que quedaron en ERROR o ENVIADO (sin red, SRI caido, o autorizacion aun en
 * PPR) - ver {@link ComprobanteRepository#listarTicketIdsParaReintentar}.
 */
public final class ConectorPrincipal {

    private static final Logger LOG = LoggerFactory.getLogger(ConectorPrincipal.class);

    private static final Path CONFIG_EMISOR_POR_DEFECTO = Path.of("config/datos-emisor.properties");
    private static final Path CONFIG_CONEXION_POR_DEFECTO = Path.of("config/conexion.properties");
    private static final Path CARPETA_PENDIENTES_POR_DEFECTO = Path.of("sri-conector/pendientes");

    private static final int MAX_INTENTOS_REINTENTO = 5;
    private static final long INTERVALO_REINTENTOS_MINUTOS = 15;
    private static final long ESPERA_ANTES_DE_CONSULTAR_AUTORIZACION_MS = 3000;

    private final DatosEmisor emisor;
    private final TicketReader ticketReader;
    private final ComprobanteRepository comprobanteRepository;
    private final XadesBesSigner firmador;
    private final SoapClient soapClient;

    public ConectorPrincipal(DatosEmisor emisor, javax.sql.DataSource dataSource) {
        this.emisor = emisor;
        this.ticketReader = new TicketReader(dataSource);
        this.comprobanteRepository = new ComprobanteRepository(dataSource);
        this.firmador = new XadesBesSigner(emisor.getRutaCertificadoP12(), emisor.getClaveCertificado());
        this.soapClient = new SoapClient(emisor.getAmbiente());
    }

    /**
     * Arranca el planificador de reintentos y luego bloquea el hilo llamador
     * vigilando la carpeta de pendientes (ver {@link VigilantePendientes#iniciar()}).
     */
    public void iniciar(Path carpetaPendientes) throws IOException {
        iniciarReintentosPeriodicos();
        new VigilantePendientes(carpetaPendientes, this::procesarTicket).iniciar();
    }

    private void iniciarReintentosPeriodicos() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread hilo = new Thread(runnable, "sri-reintentos");
            hilo.setDaemon(true);
            return hilo;
        });
        scheduler.scheduleAtFixedRate(this::reintentarPendientes,
                INTERVALO_REINTENTOS_MINUTOS, INTERVALO_REINTENTOS_MINUTOS, TimeUnit.MINUTES);
    }

    private void reintentarPendientes() {
        try {
            List<String> ticketIds = comprobanteRepository.listarTicketIdsParaReintentar(MAX_INTENTOS_REINTENTO);
            for (String ticketId : ticketIds) {
                try {
                    procesarTicket(ticketId);
                } catch (Exception e) {
                    LOG.warn("Reintento fallido para ticket {}, se reintentara en el siguiente ciclo", ticketId, e);
                }
            }
        } catch (SQLException e) {
            LOG.error("No se pudo listar comprobantes pendientes de reintento", e);
        }
    }

    /**
     * Procesa (o reprocesa) un ticket de principio a fin. Si el ticket ya
     * quedo AUTORIZADO en un intento anterior, no hace nada (idempotente).
     * Si falla en cualquier paso, deja el comprobante en estado ERROR con el
     * detalle en {@code mensajeError} y relanza la excepcion - quien llama
     * (el watcher del flag, o el planificador de reintentos) decide que
     * hacer con eso (el watcher no borra el flag, por lo que se reintentara).
     */
    public void procesarTicket(String ticketId) {
        try {
            procesarTicketInterno(ticketId);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Fallo procesando ticket " + ticketId, e);
        }
    }

    private void procesarTicketInterno(String ticketId) throws Exception {
        Optional<ComprobanteRepository.RegistroExistente> registroPrevio =
                comprobanteRepository.buscarPorTicketId(ticketId);

        if (registroPrevio.isPresent() && registroPrevio.get().estado == EstadoComprobante.AUTORIZADO) {
            LOG.info("Ticket {} ya esta AUTORIZADO, no se reprocesa", ticketId);
            return;
        }

        Optional<TicketCrudo> ticketCrudo = ticketReader.leer(ticketId);
        if (ticketCrudo.isEmpty()) {
            LOG.error("Ticket {} no existe en ECOPos - se descarta (el flag no debio generarse)", ticketId);
            return;
        }

        // El secuencial (y, mas abajo, la claveAcceso) NUNCA se regeneran en
        // un reintento: el SRI exige reenviar exactamente el mismo comprobante
        // cuando se corrige un rechazo (ficha tecnica, seccion 5.10).
        String secuencial = registroPrevio.isPresent()
                ? registroPrevio.get().secuencial
                : comprobanteRepository.siguienteSecuencial();

        Comprobante comprobante = TicketComprobanteMapper.map(ticketCrudo.get(), emisor, secuencial);

        if (registroPrevio.isPresent()) {
            ComprobanteRepository.RegistroExistente registro = registroPrevio.get();
            comprobante.setIntentos(registro.intentos);
            if (registro.claveAcceso != null) {
                comprobante.setClaveAcceso(registro.claveAcceso);
            }
        } else {
            comprobanteRepository.insertar(comprobante);
        }
        comprobante.incrementarIntentos();

        try {
            String xmlSinFirmar = FacturaXmlWriter.toXml(ComprobanteXmlMapper.map(comprobante));
            comprobante.setXmlGenerado(xmlSinFirmar);

            String xmlFirmado = firmador.firmar(xmlSinFirmar);
            comprobante.setXmlFirmado(xmlFirmado);

            RespuestaSolicitud respuestaRecepcion =
                    soapClient.enviarRecepcion(xmlFirmado.getBytes(StandardCharsets.UTF_8));

            if (!"RECIBIDA".equalsIgnoreCase(respuestaRecepcion.getEstado())) {
                comprobante.setEstado(EstadoComprobante.RECHAZADO);
                comprobante.setMensajeError(resumirRecepcion(respuestaRecepcion));
                comprobanteRepository.actualizarProgreso(comprobante);
                LOG.warn("Ticket {} rechazado en Recepcion: {}", ticketId, comprobante.getMensajeError());
                return;
            }

            comprobante.setEstado(EstadoComprobante.ENVIADO);
            comprobanteRepository.actualizarProgreso(comprobante);

            // El SRI no autoriza de forma sincrona con la recepcion; hay que
            // esperar un momento antes de poder consultar la autorizacion.
            Thread.sleep(ESPERA_ANTES_DE_CONSULTAR_AUTORIZACION_MS);

            RespuestaComprobante respuestaAutorizacion = soapClient.consultarAutorizacion(comprobante.getClaveAcceso());
            aplicarRespuestaAutorizacion(comprobante, respuestaAutorizacion);
            comprobanteRepository.actualizarProgreso(comprobante);

        } catch (Exception e) {
            comprobante.setEstado(EstadoComprobante.ERROR);
            comprobante.setMensajeError(e.toString());
            comprobanteRepository.actualizarProgreso(comprobante);
            throw e;
        }
    }

    private void aplicarRespuestaAutorizacion(Comprobante comprobante, RespuestaComprobante respuesta) {
        if (respuesta.getAutorizaciones() == null || respuesta.getAutorizaciones().getAutorizacion().isEmpty()) {
            // Sin autorizaciones todavia (SRI aun procesando, "PPR"): se deja
            // en ENVIADO para que el planificador de reintentos vuelva a
            // consultar en el siguiente ciclo.
            comprobante.setXmlRespuestaSri("Sin autorizacion todavia (en procesamiento)");
            return;
        }

        Autorizacion autorizacion = respuesta.getAutorizaciones().getAutorizacion().get(0);
        comprobante.setXmlRespuestaSri(autorizacion.getComprobante());

        if ("AUTORIZADO".equalsIgnoreCase(autorizacion.getEstado())) {
            comprobante.setEstado(EstadoComprobante.AUTORIZADO);
            comprobante.setNumeroAutorizacion(autorizacion.getNumeroAutorizacion());
            comprobante.setFechaAutorizacion(LocalDateTime.now());
        } else {
            comprobante.setEstado(EstadoComprobante.RECHAZADO);
            comprobante.setMensajeError(resumirAutorizacion(autorizacion));
        }
    }

    private static String resumirRecepcion(RespuestaSolicitud respuesta) {
        StringBuilder resumen = new StringBuilder("Estado: ").append(respuesta.getEstado());
        if (respuesta.getComprobantes() != null) {
            for (var comprobante : respuesta.getComprobantes().getComprobante()) {
                if (comprobante.getMensajes() == null) {
                    continue;
                }
                for (var mensaje : comprobante.getMensajes().getMensaje()) {
                    resumen.append(" | ").append(mensaje.getIdentificador()).append(": ").append(mensaje.getMensaje());
                }
            }
        }
        return resumen.toString();
    }

    private static String resumirAutorizacion(Autorizacion autorizacion) {
        StringBuilder resumen = new StringBuilder("Estado: ").append(autorizacion.getEstado());
        if (autorizacion.getMensajes() != null) {
            for (var mensaje : autorizacion.getMensajes().getMensaje()) {
                resumen.append(" | ").append(mensaje.getIdentificador()).append(": ").append(mensaje.getMensaje());
            }
        }
        return resumen.toString();
    }

    public static void main(String[] args) throws Exception {
        Path archivoEmisor = args.length > 0 ? Path.of(args[0]) : CONFIG_EMISOR_POR_DEFECTO;
        Path archivoConexion = args.length > 1 ? Path.of(args[1]) : CONFIG_CONEXION_POR_DEFECTO;
        Path carpetaPendientes = args.length > 2 ? Path.of(args[2]) : CARPETA_PENDIENTES_POR_DEFECTO;

        DatosEmisor emisor = ConfiguracionLoader.cargar(archivoEmisor);
        javax.sql.DataSource dataSource = cargarDataSource(archivoConexion);

        ConectorPrincipal conector = new ConectorPrincipal(emisor, dataSource);
        LOG.info("ecopos-sri-connector iniciado (ambiente={}, ruc={})", emisor.getAmbiente(), emisor.getRuc());
        conector.iniciar(carpetaPendientes);
    }

    /**
     * Datos de conexion a la MISMA base de datos MySQL/MariaDB de ECOPos
     * (host/puerto/nombre/usuario/clave), leidos de un {@code .properties}
     * separado de {@code datos-emisor.properties} porque son datos de
     * infraestructura, no del emisor SRI. Si el archivo no existe, se usan
     * los valores por defecto de una instalacion XAMPP tipica.
     */
    private static javax.sql.DataSource cargarDataSource(Path archivoConexion) throws IOException, SQLException {
        Properties propiedades = new Properties();
        if (Files.exists(archivoConexion)) {
            try (InputStream entrada = Files.newInputStream(archivoConexion)) {
                propiedades.load(entrada);
            }
        } else {
            LOG.warn("No existe {}, se usan valores por defecto de conexion (localhost/ecopos/root)", archivoConexion);
        }

        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setServerName(propiedades.getProperty("host", "localhost"));
        dataSource.setPort(Integer.parseInt(propiedades.getProperty("puerto", "3306")));
        dataSource.setDatabaseName(propiedades.getProperty("baseDatos", "ecopos"));
        dataSource.setUser(propiedades.getProperty("usuario", "root"));
        dataSource.setPassword(propiedades.getProperty("clave", ""));
        return dataSource;
    }
}
