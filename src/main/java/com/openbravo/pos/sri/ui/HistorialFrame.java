package com.openbravo.pos.sri.ui;

import com.openbravo.pos.sri.config.ConexionLoader;
import com.openbravo.pos.sri.dominio.EstadoComprobante;
import com.openbravo.pos.sri.repository.ComprobanteRepository;
import com.openbravo.pos.sri.ride.RideGenerator;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Historial de facturacion electronica: lista todo lo que hay en
 * {@code ecopos_sri_comprobantes} (mas reciente primero), con el numero de
 * ticket de ECOPos en vez del UUID interno. Permite ver el XML guardado y
 * generar el RIDE en PDF del comprobante seleccionado. Ventana independiente
 * (ver {@link #main(String[])}), lanzada desde ECOPos via un boton
 * data-only (mismo patron que {@link ConfiguracionFrame}) - nunca escribe
 * nada en la tabla, solo lee.
 */
public class HistorialFrame extends JFrame {

    private static final String RUTA_CONEXION_POR_DEFECTO = "config/conexion.properties";
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final Path archivoConexion;
    private final DefaultTableModel modelo;
    private final JLabel etiquetaEstado = new JLabel(" ");
    /** ticket_id (UUID) de cada fila, en el mismo orden que el modelo de la tabla - la tabla muestra el numero, no el UUID. */
    private final List<String> ticketIdsPorFila = new ArrayList<>();
    private final JTable tabla;

    public HistorialFrame() {
        this(Path.of(RUTA_CONEXION_POR_DEFECTO));
    }

    public HistorialFrame(Path archivoConexion) {
        super("EcoPos SRI Connector - Historial de facturación electrónica");
        this.archivoConexion = archivoConexion;

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        modelo = new DefaultTableModel(
                new Object[]{"Ticket", "Fecha", "Estado", "Secuencial", "Clave de acceso", "N° Autorización", "Intentos", "Error"}, 0) {
            @Override
            public boolean isCellEditable(int fila, int columna) {
                return false;
            }
        };
        tabla = new JTable(modelo);
        tabla.setDefaultRenderer(Object.class, new RenderizadorPorEstado());
        tabla.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        tabla.getColumnModel().getColumn(4).setPreferredWidth(280);
        tabla.getColumnModel().getColumn(7).setPreferredWidth(300);

        add(new JScrollPane(tabla), BorderLayout.CENTER);
        add(construirPanelInferior(), BorderLayout.SOUTH);

        cargarHistorial();

        setMinimumSize(new Dimension(950, 520));
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel construirPanelInferior() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(etiquetaEstado, BorderLayout.WEST);

        JPanel botones = new JPanel();
        JButton botonVerXml = new JButton("Ver XML");
        botonVerXml.addActionListener(e -> verXml());
        botones.add(botonVerXml);

        JButton botonVerRide = new JButton("Ver RIDE (PDF)");
        botonVerRide.addActionListener(e -> verRide());
        botones.add(botonVerRide);

        JButton botonActualizar = new JButton("Actualizar");
        botonActualizar.addActionListener(e -> cargarHistorial());
        botones.add(botonActualizar);

        panel.add(botones, BorderLayout.EAST);
        return panel;
    }

    private void cargarHistorial() {
        modelo.setRowCount(0);
        ticketIdsPorFila.clear();
        try {
            DataSource dataSource = ConexionLoader.cargar(archivoConexion);
            List<ComprobanteRepository.RegistroHistorial> historial =
                    new ComprobanteRepository(dataSource).listarHistorial();

            for (ComprobanteRepository.RegistroHistorial registro : historial) {
                modelo.addRow(new Object[]{
                        registro.numeroTicket,
                        registro.fechaEmision != null ? registro.fechaEmision.format(FORMATO_FECHA) : "",
                        registro.estado,
                        registro.secuencial,
                        registro.claveAcceso,
                        registro.numeroAutorizacion != null ? registro.numeroAutorizacion : "",
                        registro.intentos,
                        registro.mensajeError != null ? registro.mensajeError : ""
                });
                ticketIdsPorFila.add(registro.ticketId);
            }
            etiquetaEstado.setText(historial.size() + " comprobante(s).");
        } catch (Exception e) {
            etiquetaEstado.setText("No se pudo cargar el historial: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "No se pudo cargar el historial:\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String ticketIdSeleccionado() {
        int fila = tabla.getSelectedRow();
        if (fila < 0) {
            JOptionPane.showMessageDialog(this, "Selecciona primero una fila de la tabla.", "Nada seleccionado", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return ticketIdsPorFila.get(tabla.convertRowIndexToModel(fila));
    }

    private void verXml() {
        String ticketId = ticketIdSeleccionado();
        if (ticketId == null) {
            return;
        }
        try {
            DataSource dataSource = ConexionLoader.cargar(archivoConexion);
            var xml = new ComprobanteRepository(dataSource).obtenerXml(ticketId);
            if (xml.isEmpty() || xml.get().masReciente() == null) {
                JOptionPane.showMessageDialog(this, "Este comprobante todavía no tiene ningún XML generado.", "Sin XML", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            mostrarDialogoXml(xml.get().masReciente());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "No se pudo obtener el XML:\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void mostrarDialogoXml(String xml) {
        JTextArea area = new JTextArea(xml, 30, 90);
        area.setEditable(false);
        area.setLineWrap(false);

        JButton botonGuardar = new JButton("Guardar como...");
        botonGuardar.addActionListener(e -> guardarArchivo(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8), "comprobante.xml"));

        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        JPanel panelBoton = new JPanel();
        panelBoton.add(botonGuardar);
        panel.add(panelBoton, BorderLayout.SOUTH);

        JOptionPane.showMessageDialog(this, panel, "XML del comprobante", JOptionPane.PLAIN_MESSAGE);
    }

    private void verRide() {
        String ticketId = ticketIdSeleccionado();
        if (ticketId == null) {
            return;
        }
        try {
            DataSource dataSource = ConexionLoader.cargar(archivoConexion);
            var xml = new ComprobanteRepository(dataSource).obtenerXml(ticketId);
            if (xml.isEmpty() || xml.get().masReciente() == null) {
                JOptionPane.showMessageDialog(this, "Este comprobante todavía no tiene ningún XML generado.", "Sin XML", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            byte[] pdf = RideGenerator.generar(xml.get().masReciente(), xml.get().fechaAutorizacion);
            File temporal = File.createTempFile("ride-" + ticketId, ".pdf");
            temporal.deleteOnExit();
            Files.write(temporal.toPath(), pdf);

            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(temporal);
            } else {
                JOptionPane.showMessageDialog(this, "RIDE generado en:\n" + temporal.getAbsolutePath(), "RIDE generado", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "No se pudo generar el RIDE:\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void guardarArchivo(byte[] contenido, String nombreSugerido) {
        JFileChooser selector = new JFileChooser();
        selector.setSelectedFile(new File(nombreSugerido));
        if (selector.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.write(selector.getSelectedFile().toPath(), contenido);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "No se pudo guardar el archivo:\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** Colorea cada fila segun el estado del comprobante, para detectar rechazos/errores de un vistazo. */
    private static class RenderizadorPorEstado extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable tabla, Object valor, boolean seleccionado,
                                                         boolean tieneFoco, int fila, int columna) {
            Component componente = super.getTableCellRendererComponent(tabla, valor, seleccionado, tieneFoco, fila, columna);
            if (!seleccionado) {
                Object estado = tabla.getModel().getValueAt(fila, 2);
                componente.setBackground(colorParaEstado(estado));
            }
            return componente;
        }

        private static Color colorParaEstado(Object estado) {
            if (EstadoComprobante.AUTORIZADO.equals(estado)) {
                return new Color(214, 245, 214);
            } else if (EstadoComprobante.RECHAZADO.equals(estado) || EstadoComprobante.ERROR.equals(estado)) {
                return new Color(250, 214, 214);
            } else {
                return new Color(255, 244, 214);
            }
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignorado) {
            // se sigue con el look and feel por defecto si el del sistema no esta disponible
        }
        SwingUtilities.invokeLater(() -> new HistorialFrame().setVisible(true));
    }
}
