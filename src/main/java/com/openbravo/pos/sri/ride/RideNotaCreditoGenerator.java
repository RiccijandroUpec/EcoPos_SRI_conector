package com.openbravo.pos.sri.ride;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.openbravo.pos.sri.xml.generado.notacredito.NotaCredito;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * RIDE de una Nota de Credito - espejo de {@link RideGenerator} (misma
 * tecnica: leer el XML ya AUTORIZADO por el SRI y dibujarlo con PDFBox), con
 * dos diferencias de contenido que exige el Anexo 2 para este tipo de
 * documento: el titulo dice "NOTA DE CREDITO" y hay un bloque obligatorio
 * que referencia la factura que esta nota modifica (numero, fecha y motivo).
 */
public final class RideNotaCreditoGenerator {

    private static final float MARGEN = 40f;
    private static final float ANCHO_PAGINA = PDRectangle.A4.getWidth();
    private static final DateTimeFormatter FORMATO_FECHA_AUTORIZACION = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private RideNotaCreditoGenerator() {
    }

    public static byte[] generar(String xmlAutorizado, LocalDateTime fechaAutorizacion) throws IOException {
        NotaCredito notaCredito = desmarshallar(xmlAutorizado);

        try (PDDocument documento = new PDDocument()) {
            PDPage pagina = new PDPage(PDRectangle.A4);
            documento.addPage(pagina);

            PDFont normal = PDType1Font.HELVETICA;
            PDFont negrita = PDType1Font.HELVETICA_BOLD;

            try (PDPageContentStream cs = new PDPageContentStream(documento, pagina)) {
                float y = PDRectangle.A4.getHeight() - MARGEN;
                y = escribirEncabezado(documento, cs, notaCredito, normal, negrita, y, fechaAutorizacion);
                y = escribirDocumentoModificado(cs, notaCredito, normal, negrita, y);
                y = escribirDatosComprador(cs, notaCredito, normal, negrita, y);
                y = escribirDetalle(cs, notaCredito, normal, negrita, y);
                y = escribirTotales(cs, notaCredito, normal, negrita, y);
                escribirInfoAdicional(cs, notaCredito, normal, negrita, y);
            }

            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            documento.save(salida);
            return salida.toByteArray();
        }
    }

    private static NotaCredito desmarshallar(String xml) throws IOException {
        try {
            JAXBContext contexto = JAXBContext.newInstance(NotaCredito.class);
            Unmarshaller unmarshaller = contexto.createUnmarshaller();
            return (NotaCredito) unmarshaller.unmarshal(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException("No se pudo leer el XML autorizado para generar el RIDE de la nota de credito", e);
        }
    }

    private static float escribirEncabezado(PDDocument documento, PDPageContentStream cs, NotaCredito nc,
                                             PDFont normal, PDFont negrita, float y,
                                             LocalDateTime fechaAutorizacion) throws IOException {
        var it = nc.getInfoTributaria();

        texto(cs, negrita, 14, MARGEN, y, "R.U.C.: " + it.getRuc());
        y -= 20;
        texto(cs, negrita, 16, MARGEN, y, "NOTA DE CRÉDITO");
        y -= 18;
        texto(cs, normal, 10, MARGEN, y, "No. " + it.getEstab() + "-" + it.getPtoEmi() + "-" + it.getSecuencial());
        y -= 16;
        texto(cs, normal, 9, MARGEN, y, "NÚMERO DE AUTORIZACIÓN:");
        y -= 12;
        texto(cs, normal, 8, MARGEN, y, it.getClaveAcceso());
        y -= 14;
        texto(cs, normal, 9, MARGEN, y,
                "FECHA Y HORA DE AUTORIZACIÓN: " + (fechaAutorizacion != null ? fechaAutorizacion.format(FORMATO_FECHA_AUTORIZACION) : ""));
        y -= 12;
        texto(cs, normal, 9, MARGEN, y, "AMBIENTE: " + ("1".equals(it.getAmbiente()) ? "PRUEBAS" : "PRODUCCIÓN"));
        y -= 12;
        texto(cs, normal, 9, MARGEN, y, "EMISIÓN: NORMAL");
        y -= 16;

        texto(cs, normal, 9, MARGEN, y, "CLAVE DE ACCESO");
        y -= 45;
        dibujarCodigoBarras(documento, cs, it.getClaveAcceso(), MARGEN, y, 260, 40);
        y -= 15;

        float xDerecha = ANCHO_PAGINA / 2 + 20;
        float yEmisor = PDRectangle.A4.getHeight() - MARGEN - 20;
        texto(cs, negrita, 10, xDerecha, yEmisor, textoOVacio(it.getRazonSocial()));
        yEmisor -= 12;
        if (it.getNombreComercial() != null) {
            texto(cs, normal, 8, xDerecha, yEmisor, textoOVacio(it.getNombreComercial()));
            yEmisor -= 12;
        }
        texto(cs, normal, 8, xDerecha, yEmisor, "Matriz: " + textoOVacio(it.getDirMatriz()));
        yEmisor -= 12;
        var info = nc.getInfoNotaCredito();
        if (info.getDirEstablecimiento() != null) {
            texto(cs, normal, 8, xDerecha, yEmisor, "Sucursal: " + info.getDirEstablecimiento());
            yEmisor -= 12;
        }
        if (info.getContribuyenteEspecial() != null) {
            texto(cs, normal, 8, xDerecha, yEmisor, "Contribuyente especial: " + info.getContribuyenteEspecial());
            yEmisor -= 12;
        }
        texto(cs, normal, 8, xDerecha, yEmisor,
                "Obligado a llevar contabilidad: " + (info.getObligadoContabilidad() != null ? info.getObligadoContabilidad().value() : "NO"));

        return y - 10;
    }

    /** Bloque obligatorio (Anexo 2): que factura modifica esta nota, y por que. */
    private static float escribirDocumentoModificado(PDPageContentStream cs, NotaCredito nc, PDFont normal, PDFont negrita, float y) throws IOException {
        var info = nc.getInfoNotaCredito();
        linea(cs, MARGEN, y, ANCHO_PAGINA - MARGEN, y);
        y -= 14;
        texto(cs, negrita, 9, MARGEN, y, "Comprobante que modifica: ");
        texto(cs, normal, 9, MARGEN + 160, y, "FACTURA No. " + textoOVacio(info.getNumDocModificado())
                + "  -  Fecha emisión: " + textoOVacio(info.getFechaEmisionDocSustento()));
        y -= 13;
        texto(cs, negrita, 9, MARGEN, y, "Motivo: ");
        texto(cs, normal, 9, MARGEN + 160, y, textoOVacio(info.getMotivo()));
        return y - 6;
    }

    private static float escribirDatosComprador(PDPageContentStream cs, NotaCredito nc, PDFont normal, PDFont negrita, float y) throws IOException {
        var info = nc.getInfoNotaCredito();
        linea(cs, MARGEN, y, ANCHO_PAGINA - MARGEN, y);
        y -= 14;
        texto(cs, negrita, 9, MARGEN, y, "Razón Social / Nombres y Apellidos: ");
        texto(cs, normal, 9, MARGEN + 190, y, textoOVacio(info.getRazonSocialComprador()));
        y -= 13;
        texto(cs, negrita, 9, MARGEN, y, "Identificación: ");
        texto(cs, normal, 9, MARGEN + 190, y, textoOVacio(info.getIdentificacionComprador()));
        y -= 13;
        texto(cs, negrita, 9, MARGEN, y, "Fecha de emisión: ");
        texto(cs, normal, 9, MARGEN + 190, y, textoOVacio(info.getFechaEmision()));
        y -= 13;
        return y - 6;
    }

    private static float escribirDetalle(PDPageContentStream cs, NotaCredito nc, PDFont normal, PDFont negrita, float y) throws IOException {
        linea(cs, MARGEN, y, ANCHO_PAGINA - MARGEN, y);
        y -= 14;

        float[] columnasX = {MARGEN, MARGEN + 40, MARGEN + 85, MARGEN + 270, MARGEN + 310, MARGEN + 370, MARGEN + 430};
        String[] encabezados = {"Cód.", "Cód. Adic.", "Descripción", "Cant.", "P. Unit.", "Desc.", "Total"};
        for (int i = 0; i < encabezados.length; i++) {
            texto(cs, negrita, 8, columnasX[i], y, encabezados[i]);
        }
        y -= 12;
        linea(cs, MARGEN, y, ANCHO_PAGINA - MARGEN, y);
        y -= 12;

        for (NotaCredito.Detalles.Detalle detalle : nc.getDetalles().getDetalle()) {
            texto(cs, normal, 8, columnasX[0], y, textoOVacio(detalle.getCodigoInterno()));
            texto(cs, normal, 8, columnasX[1], y, textoOVacio(detalle.getCodigoAdicional()));
            texto(cs, normal, 8, columnasX[2], y, recortar(textoOVacio(detalle.getDescripcion()), 33));
            texto(cs, normal, 8, columnasX[3], y, formatoNumero(detalle.getCantidad()));
            texto(cs, normal, 8, columnasX[4], y, formatoNumero(detalle.getPrecioUnitario()));
            texto(cs, normal, 8, columnasX[5], y, formatoNumero(detalle.getDescuento()));
            texto(cs, normal, 8, columnasX[6], y, formatoNumero(detalle.getPrecioTotalSinImpuesto()));
            y -= 12;

            if (detalle.getDetallesAdicionales() != null) {
                for (var adicional : detalle.getDetallesAdicionales().getDetAdicional()) {
                    texto(cs, normal, 7, columnasX[2], y, textoOVacio(adicional.getNombre()) + ": " + textoOVacio(adicional.getValor()));
                    y -= 10;
                }
            }
        }
        return y - 8;
    }

    private static float escribirTotales(PDPageContentStream cs, NotaCredito nc, PDFont normal, PDFont negrita, float y) throws IOException {
        var info = nc.getInfoNotaCredito();
        linea(cs, MARGEN, y, ANCHO_PAGINA - MARGEN, y);
        y -= 14;

        float xEtiqueta = ANCHO_PAGINA - MARGEN - 200;
        float xValor = ANCHO_PAGINA - MARGEN - 60;

        y = filaTotal(cs, normal, negrita, xEtiqueta, xValor, y, "Subtotal sin impuestos:", formatoNumero(info.getTotalSinImpuestos()));
        if (info.getTotalConImpuestos() != null) {
            for (var totalImpuesto : info.getTotalConImpuestos().getTotalImpuesto()) {
                String nombre = nombreImpuesto(totalImpuesto.getCodigo());
                y = filaTotal(cs, normal, negrita, xEtiqueta, xValor, y,
                        "SUBTOTAL " + nombre + ":", formatoNumero(totalImpuesto.getBaseImponible()));
                y = filaTotal(cs, normal, negrita, xEtiqueta, xValor, y,
                        nombre + ":", formatoNumero(totalImpuesto.getValor()));
            }
        }
        y = filaTotal(cs, negrita, negrita, xEtiqueta, xValor, y, "VALOR TOTAL A ACREDITAR:", formatoNumero(info.getValorModificacion()));
        return y;
    }

    /** "Información Adicional" del comprobante (email, telefono, etc. - campoAdicional del XSD). */
    private static void escribirInfoAdicional(PDPageContentStream cs, NotaCredito nc, PDFont normal, PDFont negrita, float y) throws IOException {
        if (nc.getInfoAdicional() == null || nc.getInfoAdicional().getCampoAdicional().isEmpty()) {
            return;
        }
        y -= 10;
        linea(cs, MARGEN, y, ANCHO_PAGINA - MARGEN, y);
        y -= 14;
        texto(cs, negrita, 9, MARGEN, y, "Información Adicional");
        y -= 12;
        for (var campo : nc.getInfoAdicional().getCampoAdicional()) {
            texto(cs, normal, 8, MARGEN, y, textoOVacio(campo.getNombre()) + ": " + textoOVacio(campo.getValue()));
            y -= 11;
        }
    }

    /** codigo (tabla 19 del SRI) -> nombre del impuesto, igual que en RideGenerator. */
    private static String nombreImpuesto(String codigo) {
        if ("2".equals(codigo)) {
            return "IVA";
        } else if ("3".equals(codigo)) {
            return "ICE";
        } else if ("5".equals(codigo)) {
            return "IRBPNR";
        }
        return "Impuesto (código " + codigo + ")";
    }

    private static float filaTotal(PDPageContentStream cs, PDFont fuenteEtiqueta, PDFont fuenteValor,
                                    float xEtiqueta, float xValor, float y, String etiqueta, String valor) throws IOException {
        texto(cs, fuenteEtiqueta, 9, xEtiqueta, y, etiqueta);
        texto(cs, fuenteValor, 9, xValor, y, valor);
        return y - 13;
    }

    private static void dibujarCodigoBarras(PDDocument documento, PDPageContentStream cs, String claveAcceso,
                                             float x, float y, int anchoPx, int altoPx) throws IOException {
        try {
            BitMatrix matriz = new Code128Writer().encode(claveAcceso, BarcodeFormat.CODE_128, anchoPx, altoPx);
            BufferedImage imagen = MatrixToImageWriter.toBufferedImage(matriz);
            PDImageXObject imagenPdf = LosslessFactory.createFromImage(documento, imagen);
            cs.drawImage(imagenPdf, x, y, anchoPx / 2f, altoPx / 2f);
        } catch (Exception e) {
            texto(cs, PDType1Font.HELVETICA, 7, x, y + altoPx / 2f, "(codigo de barras no disponible)");
        }
    }

    private static void texto(PDPageContentStream cs, PDFont fuente, float tamano, float x, float y, String valor) throws IOException {
        cs.beginText();
        cs.setFont(fuente, tamano);
        cs.newLineAtOffset(x, y);
        cs.showText(valor == null ? "" : valor);
        cs.endText();
    }

    private static void linea(PDPageContentStream cs, float x1, float y1, float x2, float y2) throws IOException {
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    private static String textoOVacio(String valor) {
        return valor == null ? "" : valor;
    }

    private static String formatoNumero(BigDecimal valor) {
        return valor == null ? "" : valor.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String recortar(String valor, int maxLargo) {
        return valor.length() > maxLargo ? valor.substring(0, maxLargo - 1) + "…" : valor;
    }
}
