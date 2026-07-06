package com.openbravo.pos.sri.dominio;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Comprobante (factura) en construccion/procesamiento. A diferencia de los
 * demas objetos de dominio (inmutables), este es el agregado con estado
 * mutable que via
 * {@link com.openbravo.pos.sri.repository.ComprobanteRepository}
 * corresponde 1:1 a una fila de {@code ecopos_sri_comprobantes}.
 *
 * El ciclo de vida esperado es:
 * PENDIENTE -[XML generado + firmado]-> ENVIADO -[SRI autoriza]-> AUTORIZADO
 *                                                 \-[SRI rechaza]-> RECHAZADO
 *                        \-[fallo tecnico, ej. sin red]-> ERROR (reintentable)
 */
public class Comprobante {

    private final String id;
    private final String ticketId;
    private final TipoComprobante tipo;
    private final Ambiente ambiente;
    private final LocalDateTime fechaEmision;
    private final DatosEmisor emisor;
    private final Cliente cliente;
    private final List<DetalleFactura> detalles;
    private final List<ImpuestoDetalle> totalesPorImpuesto;
    private final List<Pago> pagos;
    private final BigDecimal totalSinImpuestos;
    private final BigDecimal totalDescuento;
    private final BigDecimal importeTotal;
    private final String secuencial; // 9 digitos

    private String claveAcceso;
    private String numeroAutorizacion;
    private EstadoComprobante estado;
    private String xmlGenerado;
    private String xmlFirmado;
    private String xmlRespuestaSri;
    private String mensajeError;
    private int intentos;
    private LocalDateTime fechaAutorizacion;

    public Comprobante(String ticketId, TipoComprobante tipo, Ambiente ambiente,
                        LocalDateTime fechaEmision, DatosEmisor emisor, Cliente cliente,
                        List<DetalleFactura> detalles, List<ImpuestoDetalle> totalesPorImpuesto,
                        List<Pago> pagos, BigDecimal totalSinImpuestos, BigDecimal totalDescuento,
                        BigDecimal importeTotal, String secuencial) {
        this.id = UUID.randomUUID().toString();
        this.ticketId = ticketId;
        this.tipo = tipo;
        this.ambiente = ambiente;
        this.fechaEmision = fechaEmision;
        this.emisor = emisor;
        this.cliente = cliente;
        this.detalles = detalles;
        this.totalesPorImpuesto = totalesPorImpuesto;
        this.pagos = pagos;
        this.totalSinImpuestos = totalSinImpuestos;
        this.totalDescuento = totalDescuento;
        this.importeTotal = importeTotal;
        this.secuencial = secuencial;
        this.estado = EstadoComprobante.PENDIENTE;
        this.intentos = 0;
    }

    // --- identidad / datos fijos ---
    public String getId() { return id; }
    public String getTicketId() { return ticketId; }
    public TipoComprobante getTipo() { return tipo; }
    public Ambiente getAmbiente() { return ambiente; }
    public LocalDateTime getFechaEmision() { return fechaEmision; }
    public DatosEmisor getEmisor() { return emisor; }
    public Cliente getCliente() { return cliente; }
    public List<DetalleFactura> getDetalles() { return detalles; }
    public List<ImpuestoDetalle> getTotalesPorImpuesto() { return totalesPorImpuesto; }
    public List<Pago> getPagos() { return pagos; }
    public BigDecimal getTotalSinImpuestos() { return totalSinImpuestos; }
    public BigDecimal getTotalDescuento() { return totalDescuento; }
    public BigDecimal getImporteTotal() { return importeTotal; }
    public String getSecuencial() { return secuencial; }

    // --- estado mutable segun avanza el procesamiento ---
    public String getClaveAcceso() { return claveAcceso; }
    public void setClaveAcceso(String claveAcceso) { this.claveAcceso = claveAcceso; }

    public String getNumeroAutorizacion() { return numeroAutorizacion; }
    public void setNumeroAutorizacion(String numeroAutorizacion) { this.numeroAutorizacion = numeroAutorizacion; }

    public EstadoComprobante getEstado() { return estado; }
    public void setEstado(EstadoComprobante estado) { this.estado = estado; }

    public String getXmlGenerado() { return xmlGenerado; }
    public void setXmlGenerado(String xmlGenerado) { this.xmlGenerado = xmlGenerado; }

    public String getXmlFirmado() { return xmlFirmado; }
    public void setXmlFirmado(String xmlFirmado) { this.xmlFirmado = xmlFirmado; }

    public String getXmlRespuestaSri() { return xmlRespuestaSri; }
    public void setXmlRespuestaSri(String xmlRespuestaSri) { this.xmlRespuestaSri = xmlRespuestaSri; }

    public String getMensajeError() { return mensajeError; }
    public void setMensajeError(String mensajeError) { this.mensajeError = mensajeError; }

    public int getIntentos() { return intentos; }
    public void incrementarIntentos() { this.intentos++; }

    public LocalDateTime getFechaAutorizacion() { return fechaAutorizacion; }
    public void setFechaAutorizacion(LocalDateTime fechaAutorizacion) { this.fechaAutorizacion = fechaAutorizacion; }
}
