package com.openbravo.pos.sri.ui;

import com.openbravo.pos.sri.config.ConfiguracionCorreoLoader;
import com.openbravo.pos.sri.config.RutasConector;
import com.openbravo.pos.sri.dominio.ConfiguracionCorreo;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuracion SMTP para el envio de comprobantes por correo - mismo patron
 * que {@link ConfiguracionFrame} (lee/escribe siempre via
 * {@link ConfiguracionCorreoLoader}, clave nunca en texto plano).
 */
public class ConfiguracionCorreoFrame extends JFrame {

    private final Path archivoConfiguracion;

    private final JTextField campoHost = new JTextField(25);
    private final JTextField campoPuerto = new JTextField(5);
    private final JTextField campoUsuario = new JTextField(25);
    private final JPasswordField campoClave = new JPasswordField(20);
    private final JTextField campoRemitente = new JTextField(25);
    private final JCheckBox campoUsarTls = new JCheckBox("Usar STARTTLS", true);
    private final JLabel etiquetaEstado = new JLabel(" ");

    private char[] claveExistente;

    public ConfiguracionCorreoFrame() {
        this(RutasConector.resolver("config/correo.properties"));
    }

    public ConfiguracionCorreoFrame(Path archivoConfiguracion) {
        super("EcoPos SRI Connector - Configuración de correo");
        this.archivoConfiguracion = archivoConfiguracion;

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        add(construirFormulario(), BorderLayout.CENTER);
        add(construirPanelBotones(), BorderLayout.SOUTH);

        cargarSiExiste();

        setMinimumSize(new Dimension(460, 260));
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
        fila = agregarFila(panel, c, fila, "Servidor SMTP:", campoHost);
        fila = agregarFila(panel, c, fila, "Puerto:", campoPuerto);
        fila = agregarFila(panel, c, fila, "Usuario:", campoUsuario);
        fila = agregarFila(panel, c, fila, "Clave:", campoClave);
        fila = agregarFila(panel, c, fila, "Correo remitente:", campoRemitente);

        c.gridx = 0; c.gridy = fila; c.gridwidth = 2;
        panel.add(campoUsarTls, c);

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

    private void cargarSiExiste() {
        if (!Files.exists(archivoConfiguracion)) {
            campoPuerto.setText("587");
            return;
        }
        try {
            ConfiguracionCorreo datos = ConfiguracionCorreoLoader.cargar(archivoConfiguracion);
            campoHost.setText(datos.getHost());
            campoPuerto.setText(String.valueOf(datos.getPuerto()));
            campoUsuario.setText(datos.getUsuario());
            campoRemitente.setText(datos.getRemitente());
            campoUsarTls.setSelected(datos.isUsarTls());
            claveExistente = datos.getClave();
            etiquetaEstado.setText("Configuración existente cargada.");
        } catch (Exception e) {
            etiquetaEstado.setText("No se pudo cargar la configuración existente: " + e.getMessage());
        }
    }

    private void guardar() {
        try {
            char[] claveEscrita = campoClave.getPassword();
            char[] claveAGuardar = (claveEscrita.length > 0) ? claveEscrita : claveExistente;

            ConfiguracionCorreo datos = new ConfiguracionCorreo(
                    campoHost.getText(),
                    Integer.parseInt(campoPuerto.getText().trim()),
                    campoUsuario.getText(),
                    claveAGuardar,
                    campoRemitente.getText(),
                    campoUsarTls.isSelected());

            ConfiguracionCorreoLoader.guardar(datos, archivoConfiguracion);
            claveExistente = claveAGuardar;
            campoClave.setText("");
            etiquetaEstado.setText("Configuración guardada en " + archivoConfiguracion.toAbsolutePath());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "El puerto debe ser un número.", "Error", JOptionPane.ERROR_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "No se pudo guardar la configuración:\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignorado) {
            // se sigue con el look and feel por defecto si el del sistema no esta disponible
        }
        SwingUtilities.invokeLater(() -> new ConfiguracionCorreoFrame().setVisible(true));
    }
}
