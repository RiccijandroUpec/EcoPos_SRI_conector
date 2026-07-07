package com.openbravo.pos.sri.ui;

import com.openbravo.pos.sri.config.ConfiguracionLoader;
import com.openbravo.pos.sri.dominio.Ambiente;
import com.openbravo.pos.sri.dominio.DatosEmisor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pantalla de configuracion de los datos del emisor (RUC, razon social,
 * establecimiento, certificado .p12) para la facturacion electronica del
 * SRI. Lee/escribe siempre a traves de {@link ConfiguracionLoader}, nunca
 * directamente el archivo.
 *
 * Se lanza como ventana independiente (ver {@link #main(String[])}) - no
 * depende de que ECOPos este corriendo. Como se abre desde la perspectiva
 * del administrador (acceso directo, o un boton agregado a ECOPos) es una
 * decision separada, pendiente de confirmar.
 */
public class ConfiguracionFrame extends JFrame {

    private static final String RUTA_CONFIG_POR_DEFECTO = "config/datos-emisor.properties";

    private final Path archivoConfiguracion;

    private final JTextField campoRuc = new JTextField(20);
    private final JTextField campoRazonSocial = new JTextField(30);
    private final JTextField campoNombreComercial = new JTextField(30);
    private final JTextField campoDirMatriz = new JTextField(30);
    private final JTextField campoDirEstablecimiento = new JTextField(30);
    private final JTextField campoContribuyenteEspecial = new JTextField(10);
    private final JCheckBox campoObligadoContabilidad = new JCheckBox("Obligado a llevar contabilidad");
    private final JTextField campoEstablecimiento = new JTextField(3);
    private final JTextField campoPuntoEmision = new JTextField(3);
    private final JComboBox<Ambiente> campoAmbiente = new JComboBox<>(Ambiente.values());
    private final JTextField campoRutaCertificado = new JTextField(25);
    private final JPasswordField campoClaveCertificado = new JPasswordField(20);
    private final JLabel etiquetaEstado = new JLabel(" ");

    /** Clave del certificado ya guardada (cifrada en disco), si el archivo ya existia - se preserva si el usuario no escribe una nueva. */
    private char[] claveCertificadoExistente;

    public ConfiguracionFrame() {
        this(Path.of(RUTA_CONFIG_POR_DEFECTO));
    }

    public ConfiguracionFrame(Path archivoConfiguracion) {
        super("EcoPos SRI Connector - Configuración de facturación electrónica");
        this.archivoConfiguracion = archivoConfiguracion;

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        add(construirFormulario(), BorderLayout.CENTER);
        add(construirPanelBotones(), BorderLayout.SOUTH);

        cargarSiExiste();

        setMinimumSize(new Dimension(520, 420));
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel construirFormulario() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 5, 15));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        int fila = 0;
        fila = agregarFila(panel, c, fila, "RUC:", campoRuc);
        fila = agregarFila(panel, c, fila, "Razón social:", campoRazonSocial);
        fila = agregarFila(panel, c, fila, "Nombre comercial:", campoNombreComercial);
        fila = agregarFila(panel, c, fila, "Dirección matriz:", campoDirMatriz);
        fila = agregarFila(panel, c, fila, "Dirección establecimiento:", campoDirEstablecimiento);
        fila = agregarFila(panel, c, fila, "Contribuyente especial (Nro. resolución):", campoContribuyenteEspecial);

        c.gridx = 0; c.gridy = fila; c.gridwidth = 2;
        panel.add(campoObligadoContabilidad, c);
        c.gridwidth = 1;
        fila++;

        fila = agregarFila(panel, c, fila, "Establecimiento (3 dígitos):", campoEstablecimiento);
        fila = agregarFila(panel, c, fila, "Punto de emisión (3 dígitos):", campoPuntoEmision);
        fila = agregarFila(panel, c, fila, "Ambiente:", campoAmbiente);

        c.gridx = 0; c.gridy = fila; c.gridwidth = 1;
        panel.add(new JLabel("Certificado (.p12):"), c);
        c.gridx = 1;
        JPanel panelCertificado = new JPanel(new BorderLayout(5, 0));
        panelCertificado.add(campoRutaCertificado, BorderLayout.CENTER);
        JButton botonExaminar = new JButton("Examinar...");
        botonExaminar.addActionListener(e -> elegirCertificado());
        panelCertificado.add(botonExaminar, BorderLayout.EAST);
        panel.add(panelCertificado, c);
        fila++;

        fila = agregarFila(panel, c, fila, "Clave del certificado:", campoClaveCertificado);

        JLabel notaClave = new JLabel("(dejar en blanco para mantener la clave ya guardada)");
        notaClave.setFont(notaClave.getFont().deriveFont(Font.ITALIC, 11f));
        c.gridx = 1; c.gridy = fila;
        panel.add(notaClave, c);

        return panel;
    }

    private int agregarFila(JPanel panel, GridBagConstraints c, int fila, String etiqueta, java.awt.Component campo) {
        c.gridx = 0; c.gridy = fila; c.weightx = 0;
        panel.add(new JLabel(etiqueta), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(campo, c);
        return fila + 1;
    }

    private JPanel construirPanelBotones() {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createEmptyBorder(0, 15, 15, 15));

        etiquetaEstado.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 15));
        panel.add(etiquetaEstado);

        JButton botonGuardar = new JButton("Guardar");
        botonGuardar.addActionListener(e -> guardar());
        panel.add(botonGuardar);

        return panel;
    }

    private void elegirCertificado() {
        JFileChooser selector = new JFileChooser();
        selector.setDialogTitle("Selecciona el certificado .p12");
        selector.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Certificado PKCS#12 (*.p12)", "p12"));
        if (selector.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            campoRutaCertificado.setText(selector.getSelectedFile().getAbsolutePath());
        }
    }

    private void cargarSiExiste() {
        if (!Files.exists(archivoConfiguracion)) {
            return;
        }
        try {
            DatosEmisor datos = ConfiguracionLoader.cargar(archivoConfiguracion);
            campoRuc.setText(datos.getRuc());
            campoRazonSocial.setText(datos.getRazonSocial());
            campoNombreComercial.setText(datos.getNombreComercial());
            campoDirMatriz.setText(datos.getDirMatriz());
            campoDirEstablecimiento.setText(datos.getDirEstablecimiento());
            campoContribuyenteEspecial.setText(datos.getContribuyenteEspecial());
            campoObligadoContabilidad.setSelected(datos.isObligadoContabilidad());
            campoEstablecimiento.setText(datos.getEstablecimiento());
            campoPuntoEmision.setText(datos.getPuntoEmision());
            campoAmbiente.setSelectedItem(datos.getAmbiente());
            campoRutaCertificado.setText(datos.getRutaCertificadoP12());
            claveCertificadoExistente = datos.getClaveCertificado();
            etiquetaEstado.setText("Configuración existente cargada.");
        } catch (Exception e) {
            etiquetaEstado.setText("No se pudo cargar la configuración existente: " + e.getMessage());
        }
    }

    private void guardar() {
        try {
            char[] claveEscrita = campoClaveCertificado.getPassword();
            char[] claveAGuardar = (claveEscrita.length > 0) ? claveEscrita : claveCertificadoExistente;

            DatosEmisor datos = new DatosEmisor(
                    textoOVacio(campoRuc),
                    textoOVacio(campoRazonSocial),
                    textoOVacio(campoNombreComercial),
                    textoOVacio(campoDirMatriz),
                    textoOVacio(campoDirEstablecimiento),
                    textoOVacio(campoContribuyenteEspecial),
                    campoObligadoContabilidad.isSelected(),
                    textoOVacio(campoEstablecimiento),
                    textoOVacio(campoPuntoEmision),
                    (Ambiente) campoAmbiente.getSelectedItem(),
                    textoOVacio(campoRutaCertificado),
                    claveAGuardar);

            ConfiguracionLoader.guardar(datos, archivoConfiguracion);
            claveCertificadoExistente = claveAGuardar;
            campoClaveCertificado.setText("");
            etiquetaEstado.setText("Configuración guardada en " + archivoConfiguracion.toAbsolutePath());
        } catch (IllegalStateException | IOException e) {
            JOptionPane.showMessageDialog(this,
                    "No se pudo guardar la configuración:\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String textoOVacio(JTextField campo) {
        String texto = campo.getText();
        return (texto == null || texto.isEmpty()) ? null : texto;
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignorado) {
            // se sigue con el look and feel por defecto si el del sistema no esta disponible
        }
        SwingUtilities.invokeLater(() -> new ConfiguracionFrame().setVisible(true));
    }
}
