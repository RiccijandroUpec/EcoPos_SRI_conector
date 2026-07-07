package com.openbravo.pos.sri.ride;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.openbravo.pos.sri.xml.generado.Factura;
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
 * Genera la Representacion Impresa de Documento Electronico (RIDE) en PDF a
 * partir del XML autorizado por el SRI, siguiendo los campos obligatorios
 * documentados en el Anexo 2 de la ficha tecnica (no es un calco pixel a
 * pixel del layout de ejemplo del SRI, pero incluye todos los campos que
 * exige: RUC, numero, numero de autorizacion, clave de acceso + codigo de
 * barras, ambiente, emision, datos del emisor y comprador, detalle, y
 * totales). El PDF generado tiene validez tributaria igual que cualquier
 * otra representacion impresa que cumpla esos requisitos (Resolucion 233,
 * junio 2018, seccion 8.19 de la ficha tecnica).
 *
 * Recibe el XML ya AUTORIZADO (el que el SRI devuelve dentro de
 * {@code <autorizacion><comprobante>}, guardado en
 * {@code ecopos_sri_comprobantes.xml_respuesta_sri}) y lo desmarshalla con
 * JAXB sobre las mismas clases generadas del XSD que usa
 * {@link com.openbravo.pos.sri.xml.ComprobanteXmlMapper} - el
 * {@code ds:Signature} incrustado se ignora, solo interesan los datos del
 * comprobante.
 */
public final class RideGenerator {

    private static final float MARGEN = 40f;
    private static final float ANCHO_PAGINA = PDRectangle.A4.getWidth();
    private static final DateTimeFormatter FORMATO_FECHA_AUTORIZACION = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    /** codigo (tabla 19 del SRI) -> nombre del impuesto, para no etiquetar ICE/IRBPNR como "IVA". */
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

    private RideGenerator() {
    }

    public static byte[] generar(String xmlAutorizado) throws IOException {
        return generar(xmlAutorizado, null);
    }

    /**
     * @param fechaAutorizacion fecha/hora en que el SRI autorizo el comprobante
     *                          (columna {@code ecopos_sri_comprobantes.fecha_autorizacion} -
     *                          no viaja dentro del XML del comprobante, solo en la
     *                          respuesta completa de autorizacion). Puede ser null
     *                          si aun no se conoce.
     */
    public static byte[] generar(String xmlAutorizado, LocalDateTime fechaAutorizacion) throws IOException {
        Factura factura = desmarshallar(xmlAutorizado);

        try (PDDocument documento = new PDDocument()) {
            PDPage pagina = new PDPage(PDRectangle.A4);
            documento.addPage(pagina);

            PDFont fuenteNormal = PDType1Font.HELVETICA;
            PDFont fuenteNegrita = PDType1Font.HELVETICA_BOLD;

            try (PDPageContentStream cs = new PDPageContentStream(documento, pagina)) {
                float y = PDRectangle.A4.getHeight() - MARGEN;
                y = escribirEncabezado(documento, cs, factura, fuenteNormal, fuenteNegrita, y, fechaAutorizacion);
                y = escribirDatosComprador(cs, factura, fuenteNormal, fuenteNegrita, y);
                y = escribirDetalle(cs, factura, fuenteNormal, fuenteNegrita, y);
                y = escribirTotales(cs, factura, fuenteNormal, fuenteNegrita, y);
                escribirInfoAdicional(cs, factura, fuenteNormal, fuenteNegrita, y);
            }

            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            documento.save(salida);
            return salida.toByteArray();
        }
    }

    private static Factura desmarshallar(String xml) throws IOException {
        try {
            JAXBContext contexto = JAXBContext.newInstance(Factura.class);
            Unmarshaller unmarshaller = contexto.createUnmarshaller();
            return (Factura) unmarshaller.unmarshal(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException("No se pudo leer el XML autorizado para generar el RIDE", e);
        }
    }

    private static float escribirEncabezado(PDDocument documento, PDPageContentStream cs, Factura factura,
                                             PDFont normal, PDFont negrita, float y,
                                             LocalDateTime fechaAutorizacion) throws IOException {
        var it = factura.getInfoTributaria();

        texto(cs, negrita, 14, MARGEN, y, "R.U.C.: " + it.getRuc());
        y -= 20;
        texto(cs, negrita, 16, MARGEN, y, "FACTURA");
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

        // Datos del emisor, a la derecha del encabezado (reutiliza la misma altura de arriba)
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
        var infoFactura = factura.getInfoFactura();
        if (infoFactura.getDirEstablecimiento() != null) {
            texto(cs, normal, 8, xDerecha, yEmisor, "Sucursal: " + infoFactura.getDirEstablecimiento());
            yEmisor -= 12;
        }
        if (infoFactura.getContribuyenteEspecial() != null) {
            texto(cs, normal, 8, xDerecha, yEmisor, "Contribuyente especial: " + infoFactura.getContribuyenteEspecial());
            yEmisor -= 12;
        }
        texto(cs, normal, 8, xDerecha, yEmisor,
                "Obligado a llevar contabilidad: " + (infoFactura.getObligadoContabilidad() != null ? infoFactura.getObligadoContabilidad().value() : "NO"));

        return y - 10;
    }

    private static float escribirDatosComprador(PDPageContentStream cs, Factura factura, PDFont normal, PDFont negrita, float y) throws IOException {
        var info = factura.getInfoFactura();
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
        if (info.getDireccionComprador() != null) {
            texto(cs, negrita, 9, MARGEN, y, "Dirección: ");
            texto(cs, normal, 9, MARGEN + 190, y, info.getDireccionComprador());
            y -= 13;
        }
        if (info.getPlaca() != null) {
            texto(cs, negrita, 9, MARGEN, y, "Placa: ");
            texto(cs, normal, 9, MARGEN + 190, y, info.getPlaca());
            y -= 13;
        }
        if (info.getGuiaRemision() != null) {
            texto(cs, negrita, 9, MARGEN, y, "Guía: ");
            texto(cs, normal, 9, MARGEN + 190, y, info.getGuiaRemision());
            y -= 13;
        }
        return y - 6;
    }

    private static float escribirDetalle(PDPageContentStream cs, Factura factura, PDFont normal, PDFont negrita, float y) throws IOException {
        linea(cs, MARGEN, y, ANCHO_PAGINA - MARGEN, y);
        y -= 14;

        float[] columnasX = {MARGEN, MARGEN + 40, MARGEN + 85, MARGEN + 270, MARGEN + 310, MARGEN + 370, MARGEN + 430};
        String[] encabezados = {"Cód.", "Cód. Aux.", "Descripción", "Cant.", "P. Unit.", "Desc.", "Total"};
        for (int i = 0; i < encabezados.length; i++) {
            texto(cs, negrita, 8, columnasX[i], y, encabezados[i]);
        }
        y -= 12;
        linea(cs, MARGEN, y, ANCHO_PAGINA - MARGEN, y);
        y -= 12;

        for (Factura.Detalles.Detalle detalle : factura.getDetalles().getDetalle()) {
            texto(cs, normal, 8, columnasX[0], y, textoOVacio(detalle.getCodigoPrincipal()));
            texto(cs, normal, 8, columnasX[1], y, textoOVacio(detalle.getCodigoAuxiliar()));
            texto(cs, normal, 8, columnasX[2], y, recortar(textoOVacio(detalle.getDescripcion()), 33));
            texto(cs, normal, 8, columnasX[3], y, formatoNumero(detalle.getCantidad()));
            texto(cs, normal, 8, columnasX[4], y, formatoNumero(detalle.getPrecioUnitario()));
            texto(cs, normal, 8, columnasX[5], y, formatoNumero(detalle.getDescuento()));
            texto(cs, normal, 8, columnasX[6], y, formatoNumero(detalle.getPrecioTotalSinImpuesto()));
            y -= 12;

            if (detalle.getPrecioSinSubsidio() != null) {
                texto(cs, normal, 7, columnasX[2], y, "Precio sin subsidio: " + formatoNumero(detalle.getPrecioSinSubsidio()));
                y -= 10;
            }
            if (detalle.getDetallesAdicionales() != null) {
                for (var adicional : detalle.getDetallesAdicionales().getDetAdicional()) {
                    texto(cs, normal, 7, columnasX[2], y, textoOVacio(adicional.getNombre()) + ": " + textoOVacio(adicional.getValor()));
                    y -= 10;
                }
            }
        }
        return y - 8;
    }

    private static float escribirTotales(PDPageContentStream cs, Factura factura, PDFont normal, PDFont negrita, float y) throws IOException {
        var info = factura.getInfoFactura();
        linea(cs, MARGEN, y, ANCHO_PAGINA - MARGEN, y);
        y -= 14;

        float xEtiqueta = ANCHO_PAGINA - MARGEN - 200;
        float xValor = ANCHO_PAGINA - MARGEN - 60;

        y = filaTotal(cs, normal, negrita, xEtiqueta, xValor, y, "Subtotal sin impuestos:", formatoNumero(info.getTotalSinImpuestos()));
        y = filaTotal(cs, normal, negrita, xEtiqueta, xValor, y, "Descuento:", formatoNumero(info.getTotalDescuento()));
        if (info.getTotalSubsidio() != null) {
            y = filaTotal(cs, normal, negrita, xEtiqueta, xValor, y, "Subsidio:", formatoNumero(info.getTotalSubsidio()));
        }
        if (info.getTotalConImpuestos() != null) {
            // Una fila de SUBTOTAL (base imponible) por cada tarifa/tipo de impuesto, y despues
            // el valor del impuesto correspondiente - etiquetado por su codigo real (2=IVA,
            // 3=ICE, 5=IRBPNR), no siempre "IVA" como antes.
            for (var totalImpuesto : info.getTotalConImpuestos().getTotalImpuesto()) {
                String nombre = nombreImpuesto(totalImpuesto.getCodigo());
                String sufijoTarifa = totalImpuesto.getTarifa() != null ? " " + formatoNumero(totalImpuesto.getTarifa()) + "%" : "";
                y = filaTotal(cs, normal, negrita, xEtiqueta, xValor, y,
                        "SUBTOTAL " + nombre + sufijoTarifa + ":", formatoNumero(totalImpuesto.getBaseImponible()));
                y = filaTotal(cs, normal, negrita, xEtiqueta, xValor, y,
                        nombre + sufijoTarifa + ":", formatoNumero(totalImpuesto.getValor()));
            }
        }
        y = filaTotal(cs, normal, negrita, xEtiqueta, xValor, y, "Propina:", formatoNumero(info.getPropina()));
        y = filaTotal(cs, negrita, negrita, xEtiqueta, xValor, y, "VALOR TOTAL:", formatoNumero(info.getImporteTotal()));
        if (info.getTotalSubsidio() != null) {
            y = filaTotal(cs, negrita, negrita, xEtiqueta, xValor, y,
                    "VALOR TOTAL SIN SUBSIDIO:", formatoNumero(info.getImporteTotal().add(info.getTotalSubsidio())));
            y = filaTotal(cs, normal, negrita, xEtiqueta, xValor, y, "AHORRO POR SUBSIDIO:", formatoNumero(info.getTotalSubsidio()));
        }

        y -= 6;
        if (info.getPagos() != null) {
            for (var pago : info.getPagos().getPago()) {
                texto(cs, normal, 8, MARGEN, y, "Forma de pago: " + pago.getFormaPago() + "  -  " + formatoNumero(pago.getTotal()));
                y -= 11;
            }
        }
        return y;
    }

    /** "Información Adicional" del comprobante (email, telefono, etc. - campoAdicional del XSD, hasta 15 segun el Anexo 1). */
    private static void escribirInfoAdicional(PDPageContentStream cs, Factura factura, PDFont normal, PDFont negrita, float y) throws IOException {
        if (factura.getInfoAdicional() == null || factura.getInfoAdicional().getCampoAdicional().isEmpty()) {
            return;
        }
        y -= 10;
        linea(cs, MARGEN, y, ANCHO_PAGINA - MARGEN, y);
        y -= 14;
        texto(cs, negrita, 9, MARGEN, y, "Información Adicional");
        y -= 12;
        for (var campo : factura.getInfoAdicional().getCampoAdicional()) {
            texto(cs, normal, 8, MARGEN, y, textoOVacio(campo.getNombre()) + ": " + textoOVacio(campo.getValue()));
            y -= 11;
        }
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
            // El RIDE sigue siendo valido sin el codigo de barras (es una
            // ayuda opcional segun la seccion 8.20 de la ficha tecnica) -
            // no se debe romper la generacion del PDF por esto.
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
