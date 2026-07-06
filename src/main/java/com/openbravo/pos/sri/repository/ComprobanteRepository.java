package com.openbravo.pos.sri.repository;

import com.openbravo.pos.sri.dominio.Comprobante;
import com.openbravo.pos.sri.dominio.EstadoComprobante;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
            "(id, ticket_id, ambiente, estado, fecha_emision, intentos) " +
            "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, c.getId());
            ps.setString(2, c.getTicketId());
            ps.setString(3, c.getAmbiente().name());
            ps.setString(4, c.getEstado().name());
            ps.setTimestamp(5, Timestamp.valueOf(c.getFechaEmision()));
            ps.setInt(6, c.getIntentos());
            ps.executeUpdate();
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
}
