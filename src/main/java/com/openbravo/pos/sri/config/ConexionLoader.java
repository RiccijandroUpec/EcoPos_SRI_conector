package com.openbravo.pos.sri.config;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import javax.sql.DataSource;

/**
 * Datos de conexion a la MISMA base de datos MySQL/MariaDB de ECOPos
 * (host/puerto/nombre/usuario/clave), leidos de un {@code .properties}
 * separado de {@code datos-emisor.properties} porque son datos de
 * infraestructura, no del emisor SRI. Si el archivo no existe, se usan los
 * valores por defecto de una instalacion XAMPP tipica (localhost/ecopos/root).
 *
 * Compartido por {@code ConectorPrincipal} y cualquier pantalla que necesite
 * leer la misma base (ej. {@code HistorialFrame}) - antes vivia como un
 * metodo privado solo en ConectorPrincipal.
 */
public final class ConexionLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ConexionLoader.class);

    private ConexionLoader() {
    }

    public static DataSource cargar(Path archivoConexion) throws IOException {
        Properties propiedades = new Properties();
        if (Files.exists(archivoConexion)) {
            try (InputStream entrada = Files.newInputStream(archivoConexion)) {
                propiedades.load(entrada);
            }
        } else {
            LOG.warn("No existe {}, se usan valores por defecto de conexion (localhost/ecopos/root)", archivoConexion);
        }

        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setServerName(propiedades.getProperty("host", "localhost"));
        dataSource.setPort(Integer.parseInt(propiedades.getProperty("puerto", "3306")));
        dataSource.setDatabaseName(propiedades.getProperty("baseDatos", "ecopos"));
        dataSource.setUser(propiedades.getProperty("usuario", "root"));
        dataSource.setPassword(propiedades.getProperty("clave", ""));
        return dataSource;
    }
}
