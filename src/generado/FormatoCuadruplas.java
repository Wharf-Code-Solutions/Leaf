package generado;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import generado.Cuadrupla.Op;

/**
 * Presentación de una lista de cuádruplos: tabla de cuatro columnas y forma de tres direcciones.
 * Sirve para cualquier lista (la original del generador o la optimizada).
 */
public final class FormatoCuadruplas {

    private FormatoCuadruplas() {}

    /** Tabla de cuádruplos: operación, arg1, arg2, resultado (cuatro columnas, sin índice). */
    public static List<String> tabla(List<Cuadrupla> cuadruplas) {
        int w1 = 9, w2 = 4, w3 = 4, w4 = 9;
        for (Cuadrupla c : cuadruplas) {
            String[] col = c.columnas();
            w1 = Math.max(w1, col[0].length());
            w2 = Math.max(w2, col[1].length());
            w3 = Math.max(w3, col[2].length());
            w4 = Math.max(w4, col[3].length());
        }
        List<String> lineas = new ArrayList<String>();
        String formato = "%-" + w1 + "s | %-" + w2 + "s | %-" + w3 + "s | %-" + w4 + "s";
        lineas.add(String.format(formato, "operación", "arg1", "arg2", "resultado"));
        for (Cuadrupla c : cuadruplas) {
            String[] col = c.columnas();
            lineas.add(String.format(formato, col[0], col[1], col[2], col[3]));
        }
        return lineas;
    }

    /** Código en forma de tres direcciones, una línea por cuádrupla. */
    public static List<String> texto(List<Cuadrupla> cuadruplas) {
        List<String> lineas = new ArrayList<String>();
        for (Cuadrupla c : cuadruplas) {
            boolean sinSangria = c.op == Op.ETIQUETA || c.op == Op.INICIO
                    || c.op == Op.FUNC_INICIO || c.op == Op.FUNC_FIN;
            lineas.add(sinSangria ? c.aTexto() : "    " + c.aTexto());
        }
        return lineas;
    }

    /**
     * Salida completa del código intermedio: la tabla original y la optimizada
     * (con tresDirecciones, cada una seguida de su forma de tres direcciones).
     */
    public static List<String> salida(List<Cuadrupla> original, List<Cuadrupla> optimizada, boolean tresDirecciones) {
        List<String> lineas = new ArrayList<String>();
        lineas.add("--- CÓDIGO INTERMEDIO (cuádruplos) ---");
        lineas.addAll(tabla(original));
        if (tresDirecciones) {
            lineas.add("");
            lineas.add("--- Forma de tres direcciones ---");
            lineas.addAll(texto(original));
        }
        lineas.add("");
        lineas.add("--- CÓDIGO INTERMEDIO OPTIMIZADO ---");
        lineas.addAll(tabla(optimizada));
        if (tresDirecciones) {
            lineas.add("");
            lineas.add("--- Forma de tres direcciones (optimizado) ---");
            lineas.addAll(texto(optimizada));
        }
        return lineas;
    }

    /** Escribe las líneas en un .cuad junto al fuente (programa.leaf -> programa.cuad). */
    public static File guardar(File fuente, List<String> lineas) throws IOException {
        String nombre = fuente.getName();
        int punto = nombre.lastIndexOf('.');
        if (punto > 0) nombre = nombre.substring(0, punto);
        File carpeta = fuente.getAbsoluteFile().getParentFile();
        File salida = new File(carpeta, nombre + ".cuad");
        Files.write(salida.toPath(), lineas, StandardCharsets.UTF_8);
        return salida;
    }
}
