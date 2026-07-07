package com.openbravo.pos.sri.repository;

import com.openbravo.pos.sri.dominio.Comprobante;
import com.openbravo.pos.sri.dominio.EstadoComprobante;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * CRUD sobre la tabla propia {@code ecopos_sri_comprobantes} (ver
 * src/main/resources/sql/001_create_ecopos_sri_comprobantes.sql). Nunca
 * toca ninguna tabla original de ECOPos.
 */
public class ComprobanteRepository {

    private final DataSource dataSource;

    public ComprobanteRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Estado actual del comprobante de un ticket, si ya se creo alguno.
     * No reconstruye el {@link Comprobante} completo (lineas/impuestos no
     * se persisten normalizados en esta tabla, solo el XML ya generado) -
     * esto es solo para el chequeo de idempotencia ("ya se proceso este
     * ticket, y en que estado quedo?").
     */
    public Optional<EstadoComprobante> buscarEstadoPorTicketId(String ticketId) throws SQLException {
        String sql = "SELECT estado FROM ecopos_sri_comprobantes WHERE ticket_id = ?";
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(EstadoComprobante.valueOf(rs.getString("estado")));
            }
        }
    }

    /**
     * Datos minimos de un comprobante ya existente, necesarios para
     * reintentar su procesamiento sin generar un secuencial/claveAcceso
     * distinto al ya usado (el SRI exige reenviar con la MISMA clave de
     * acceso cuando se corrige un rechazo - ver seccion 5.10 de la ficha
     * tecnica).
     */
    public static final class RegistroExistente {
        public final String id;
        public final String secuencial;
        public final String claveAcceso;
        public final EstadoComprobante estado;
        public final int intentos;

        RegistroExistente(String id, String secuencial, String claveAcceso, EstadoComprobante estado, int intentos) {
            this.id = id;
            this.secuencial = secuencial;
            this.claveAcceso = claveAcceso;
            this.estado = estado;
            this.intentos = intentos;
        }
    }

    public Optional<RegistroExistente> buscarPorTicketId(String ticketId) throws SQLException {
        String sql = "SELECT id, secuencial, clave_acceso, estado, intentos " +
            "FROM ecopos_sri_comprobantes WHERE ticket_id = ?";
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new RegistroExistente(
                        rs.getString("id"),
                        rs.getString("secuencial"),
                        rs.getString("clave_acceso"),
                        EstadoComprobante.valueOf(rs.getString("estado")),
                        rs.getInt("intentos")));
            }
        }
    }

    public boolean existePorTicketId(String ticketId) throws SQLException {
        String sql = "SELECT 1 FROM ecopos_sri_comprobantes WHERE ticket_id = ?";
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void insertar(Comprobante c) throws SQLException {
        String sql = "INSERT INTO ecopos_sri_comprobantes " +
            "(id, ticket_id, secuencial, ambiente, estado, fecha_emision, intentos) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, c.getId());
            ps.setString(2, c.getTicketId());
            ps.setString(3, c.getSecuencial());
            ps.setString(4, c.getAmbiente().name());
            ps.setString(5, c.getEstado().name());
            ps.setTimestamp(6, Timestamp.valueOf(c.getFechaEmision()));
            ps.setInt(7, c.getIntentos());
            ps.executeUpdate();
        }
    }

    /**
     * Siguiente secuencial de 9 digitos a asignar (el mayor ya usado + 1,
     * con ceros a la izquierda). Devuelve "000000001" si todavia no existe
     * ningun comprobante. Asume un unico establecimiento/punto de emision
     * por instalacion del conector (ver DatosEmisor) - si en el futuro se
     * soportan varios, este metodo debe filtrar por ellos.
     *
     * No es seguro ante llamadas concurrentes (no hay bloqueo/transaccion);
     * suficiente mientras el conector procese un ticket a la vez.
     */
    public String siguienteSecuencial() throws SQLException {
        String sql = "SELECT MAX(CAST(secuencial AS UNSIGNED)) FROM ecopos_sri_comprobantes " +
            "WHERE secuencial IS NOT NULL";
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            long maximo = 0;
            if (rs.next()) {
                maximo = rs.getLong(1);
            }
            return String.format("%09d", maximo + 1);
        }
    }

    public void actualizarProgreso(Comprobante c) throws SQLException {
        String sql = "UPDATE ecopos_sri_comprobantes SET " +
            "clave_acceso = ?, numero_autorizacion = ?, estado = ?, " +
            "xml_generado = ?, xml_firmado = ?, xml_respuesta_sri = ?, " +
            "mensaje_error = ?, intentos = ?, fecha_autorizacion = ? " +
            "WHERE id = ?";
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, c.getClaveAcceso());
            ps.setString(2, c.getNumeroAutorizacion());
            ps.setString(3, c.getEstado().name());
            ps.setString(4, c.getXmlGenerado());
            ps.setString(5, c.getXmlFirmado());
            ps.setString(6, c.getXmlRespuestaSri());
            ps.setString(7, c.getMensajeError());
            ps.setInt(8, c.getIntentos());
            ps.setTimestamp(9, c.getFechaAutorizacion() != null ? Timestamp.valueOf(c.getFechaAutorizacion()) : null);
            ps.setString(10, c.getId());
            ps.executeUpdate();
        }
    }

    /** IDs de ticket cuyo comprobante quedo en ERROR y aun no agota reintentos, para el planificador. */
    public List<String> listarTicketIdsParaReintentar(int maxIntentos) throws SQLException {
        String sql = "SELECT ticket_id FROM ecopos_sri_comprobantes " +
            "WHERE estado IN ('ERROR', 'ENVIADO') AND intentos < ? " +
            "ORDER BY fecha_creacion";
        List<String> ids = new ArrayList<>();
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, maxIntentos);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString("ticket_id"));
                }
            }
        }
        return ids;
    }

    /** Una fila del historial de facturacion, enriquecida con el numero de ticket de ECOPos (mas legible que el UUID interno). */
    public static final class RegistroHistorial {
        public final String ticketId;
        public final int numeroTicket;
        public final LocalDateTime fechaEmision;
        public final String secuencial;
        public final String claveAcceso;
        public final EstadoComprobante estado;
        public final String numeroAutorizacion;
        public final String mensajeError;
        public final int intentos;

        RegistroHistorial(String ticketId, int numeroTicket, LocalDateTime fechaEmision, String secuencial,
                           String claveAcceso, EstadoComprobante estado, String numeroAutorizacion,
                           String mensajeError, int intentos) {
            this.ticketId = ticketId;
            this.numeroTicket = numeroTicket;
            this.fechaEmision = fechaEmision;
            this.secuencial = secuencial;
            this.claveAcceso = claveAcceso;
            this.estado = estado;
            this.numeroAutorizacion = numeroAutorizacion;
            this.mensajeError = mensajeError;
            this.intentos = intentos;
        }
    }

    /** Los tres XML guardados para un comprobante, para inspeccion/exportacion desde el historial. */
    public static final class XmlComprobante {
        public final String xmlGenerado;
        public final String xmlFirmado;
        public final String xmlRespuestaSri;

        XmlComprobante(String xmlGenerado, String xmlFirmado, String xmlRespuestaSri) {
            this.xmlGenerado = xmlGenerado;
            this.xmlFirmado = xmlFirmado;
            this.xmlRespuestaSri = xmlRespuestaSri;
        }

        /** El mas "final" disponible: la respuesta autorizada del SRI si existe, si no el firmado, si no el generado sin firmar. */
        public String masReciente() {
            if (xmlRespuestaSri != null && !xmlRespuestaSri.isBlank()) {
                return xmlRespuestaSri;
            }
            if (xmlFirmado != null && !xmlFirmado.isBlank()) {
                return xmlFirmado;
            }
            return xmlGenerado;
        }
    }

    /**
     * Trae los XML (MEDIUMTEXT, no se piden en {@link #listarHistorial()}
     * para no cargar contenido pesado innecesariamente al mostrar la lista).
     */
    public Optional<XmlComprobante> obtenerXml(String ticketId) throws SQLException {
        String sql = "SELECT xml_generado, xml_firmado, xml_respuesta_sri " +
            "FROM ecopos_sri_comprobantes WHERE ticket_id = ?";
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new XmlComprobante(
                        rs.getString("xml_generado"),
                        rs.getString("xml_firmado"),
                        rs.getString("xml_respuesta_sri")));
            }
        }
    }

    /**
     * Historial completo de comprobantes, mas reciente primero. Hace JOIN
     * de solo lectura con TICKETS (ECOPos) unicamente para mostrar el
     * numero de ticket humano en vez del UUID interno - no escribe nada
     * en tablas de ECOPos.
     */
    public List<RegistroHistorial> listarHistorial() throws SQLException {
        String sql = "SELECT c.ticket_id, t.TICKETID, c.fecha_emision, c.secuencial, c.clave_acceso, " +
            "c.estado, c.numero_autorizacion, c.mensaje_error, c.intentos " +
            "FROM ecopos_sri_comprobantes c " +
            "LEFT JOIN TICKETS t ON t.ID = c.ticket_id " +
            "ORDER BY c.fecha_emision DESC";
        List<RegistroHistorial> historial = new ArrayList<>();
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Timestamp fechaEmision = rs.getTimestamp("fecha_emision");
                historial.add(new RegistroHistorial(
                        rs.getString("ticket_id"),
                        rs.getInt("TICKETID"),
                        fechaEmision != null ? fechaEmision.toLocalDateTime() : null,
                        rs.getString("secuencial"),
                        rs.getString("clave_acceso"),
                        EstadoComprobante.valueOf(rs.getString("estado")),
                        rs.getString("numero_autorizacion"),
                        rs.getString("mensaje_error"),
                        rs.getInt("intentos")));
            }
        }
        return historial;
    }
}
