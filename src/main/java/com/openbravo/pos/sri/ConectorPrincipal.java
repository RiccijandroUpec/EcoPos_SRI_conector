package com.openbravo.pos.sri;

import com.openbravo.pos.sri.config.ConexionLoader;
import com.openbravo.pos.sri.config.ConfiguracionLoader;
import com.openbravo.pos.sri.dominio.Comprobante;
import com.openbravo.pos.sri.dominio.DatosEmisor;
import com.openbravo.pos.sri.dominio.EstadoComprobante;
import com.openbravo.pos.sri.dominio.TipoComprobante;
import com.openbravo.pos.sri.envio.EnvioComprobanteService;
import com.openbravo.pos.sri.firma.XadesBesSigner;
import com.openbravo.pos.sri.repository.ComprobanteRepository;
import com.openbravo.pos.sri.repository.TicketCrudo;
import com.openbravo.pos.sri.repository.TicketReader;
import com.openbravo.pos.sri.scheduler.VigilantePendientes;
import com.openbravo.pos.sri.soap.SoapClient;
import com.openbravo.pos.sri.xml.ComprobanteXmlMapper;
import com.openbravo.pos.sri.xml.FacturaXmlWriter;
import com.openbravo.pos.sri.xml.TicketComprobanteMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
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

    private final DatosEmisor emisor;
    private final TicketReader ticketReader;
    private final ComprobanteRepository comprobanteRepository;
    private final EnvioComprobanteService envioComprobanteService;

    public ConectorPrincipal(DatosEmisor emisor, javax.sql.DataSource dataSource) {
        this.emisor = emisor;
        this.ticketReader = new TicketReader(dataSource);
        this.comprobanteRepository = new ComprobanteRepository(dataSource);
        XadesBesSigner firmador = new XadesBesSigner(emisor.getRutaCertificadoP12(), emisor.getClaveCertificado());
        SoapClient soapClient = new SoapClient(emisor.getAmbiente());
        this.envioComprobanteService = new EnvioComprobanteService(firmador, soapClient, comprobanteRepository);
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
                : comprobanteRepository.siguienteSecuencial(TipoComprobante.FACTURA);

        Comprobante comprobante = TicketComprobanteMapper.map(ticketCrudo.get(), emisor, secuencial);

        if (registroPrevio.isPresent()) {
            ComprobanteRepository.RegistroExistente registro = registroPrevio.get();
            // Reutiliza el id de la fila ya existente - si no, actualizarProgreso()
            // (que hace UPDATE ... WHERE id = ?) apuntaria a un id recien generado
            // que nunca se inserto, y el reintento no actualizaria nada en la tabla.
            comprobante.setId(registro.id);
            comprobante.setIntentos(registro.intentos);
            if (registro.claveAcceso != null) {
                comprobante.setClaveAcceso(registro.claveAcceso);
            }
        } else {
            comprobanteRepository.insertar(comprobante);
        }
        comprobante.incrementarIntentos();

        String xmlSinFirmar = FacturaXmlWriter.toXml(ComprobanteXmlMapper.map(comprobante));
        envioComprobanteService.firmarEnviarYConsultar(comprobante, xmlSinFirmar);
    }

    public static void main(String[] args) throws Exception {
        Path archivoEmisor = args.length > 0 ? Path.of(args[0]) : CONFIG_EMISOR_POR_DEFECTO;
        Path archivoConexion = args.length > 1 ? Path.of(args[1]) : CONFIG_CONEXION_POR_DEFECTO;
        Path carpetaPendientes = args.length > 2 ? Path.of(args[2]) : CARPETA_PENDIENTES_POR_DEFECTO;

        DatosEmisor emisor = ConfiguracionLoader.cargar(archivoEmisor);
        javax.sql.DataSource dataSource = ConexionLoader.cargar(archivoConexion);

        ConectorPrincipal conector = new ConectorPrincipal(emisor, dataSource);
        LOG.info("ecopos-sri-connector iniciado (ambiente={}, ruc={})", emisor.getAmbiente(), emisor.getRuc());
        conector.iniciar(carpetaPendientes);
    }
}
