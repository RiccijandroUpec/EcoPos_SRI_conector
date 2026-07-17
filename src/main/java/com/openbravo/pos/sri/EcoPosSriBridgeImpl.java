package com.openbravo.pos.sri;

import com.openbravo.pos.sri.config.ConfiguracionLoader;
import com.openbravo.pos.sri.config.RutasConector;
import com.openbravo.pos.sri.dominio.DatosEmisor;
import com.openbravo.pos.sri.ui.ConfiguracionCorreoFrame;
import com.openbravo.pos.sri.ui.ConfiguracionFrame;
import com.openbravo.pos.sri.ui.HistorialFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;

/**
 * Implementacion real de {@link EcoPosSriBridge} para el modo fusionado
 * (mismo proceso que ECOPos). La instancia {@link EcoPosSriGlue} (lado
 * ECOPos) via reflexion, a traves de un {@code URLClassLoader} dedicado al
 * jar sombreado de este modulo.
 */
public final class EcoPosSriBridgeImpl implements EcoPosSriBridge {

    private static final Logger LOG = LoggerFactory.getLogger(EcoPosSriBridgeImpl.class);

    private final Connection connection;
    private final ExecutorService executor;
    private final ConectorPrincipal conectorPrincipal;

    /**
     * @param connection      conexion JDBC dedicada a este conector (NO la de ECOPos - ver EcoPosSriGlue), long-lived, reusada.
     * @param carpetaConector carpeta "sri-conector/" real (fuera del jar) - de ahi salen
     *                        config/datos-emisor.properties, config/correo.properties, etc.
     *                        Primero que nada fija la base de {@link RutasConector}, para
     *                        que ConfiguracionFrame/ConfiguracionCorreoFrame/HistorialFrame/
     *                        ConectorPrincipal (que asumen CWD=sri-conector/ en el modo
     *                        standalone) resuelvan bien sus rutas relativas tambien aqui,
     *                        donde el CWD real es el de ECOPos.
     * @param executor        un unico hilo: serializa todo el trabajo de BD/SOAP del conector.
     */
    public EcoPosSriBridgeImpl(Connection connection, Path carpetaConector, ExecutorService executor) throws Exception {
        RutasConector.establecerCarpetaBase(carpetaConector);
        this.connection = connection;
        this.executor = executor;
        DatosEmisor emisor = ConfiguracionLoader.cargar(RutasConector.resolver("config/datos-emisor.properties"));
        this.conectorPrincipal = new ConectorPrincipal(emisor, connection);
    }

    @Override
    public void procesarTicketAsync(String ticketId) {
        executor.submit(() -> {
            try {
                conectorPrincipal.procesarTicket(ticketId);
            } catch (Throwable e) {
                // Un Runnable enviado a un ExecutorService que termina con una
                // excepcion no capturada se traga en silencio (Future.get()
                // la reportaria, pero aqui no se llama get()) - sin este
                // catch, un fallo real del conector no dejaria rastro alguno.
                LOG.error("Fallo procesando el ticket {} en el modo fusionado", ticketId, e);
            }
        });
    }

    @Override
    public void abrirConfiguracionEmisor() {
        SwingUtilities.invokeLater(() -> new ConfiguracionFrame().setVisible(true));
    }

    @Override
    public void abrirConfiguracionCorreo() {
        SwingUtilities.invokeLater(() -> new ConfiguracionCorreoFrame().setVisible(true));
    }

    @Override
    public void abrirHistorial() {
        SwingUtilities.invokeLater(() -> new HistorialFrame().setVisible(true));
    }

    @Override
    public void iniciarReintentosPeriodicos() {
        conectorPrincipal.iniciarReintentosPeriodicos();
    }

    @Override
    public void detenerReintentosPeriodicos() {
        conectorPrincipal.detenerReintentosPeriodicos();
    }

    @Override
    public void cerrar() {
        detenerReintentosPeriodicos();
        executor.shutdown();
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.warn("No se pudo cerrar limpiamente la conexion dedicada del conector", e);
        }
    }
}
