package com.openbravo.pos.sri.dominio;

/**
 * Catalogo "Formas de pago" del SRI (Tabla 24 de la ficha tecnica),
 * verificado campo por campo contra la ficha tecnica oficial v2.32.
 */
public enum FormaPago {
    SIN_SISTEMA_FINANCIERO("01"),   // efectivo, cheque, etc. sin usar el sistema financiero
    COMPENSACION_DEUDAS("15"),
    TARJETA_DEBITO("16"),
    DINERO_ELECTRONICO("17"),
    TARJETA_PREPAGO("18"),
    TARJETA_CREDITO("19"),
    OTROS_SISTEMA_FINANCIERO("20"),
    ENDOSO_TITULOS("21");

    private final String codigo;

    FormaPago(String codigo) {
        this.codigo = codigo;
    }

    public String getCodigo() {
        return codigo;
    }
}
