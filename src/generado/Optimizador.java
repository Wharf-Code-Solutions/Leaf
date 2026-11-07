package generado;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optimización del código intermedio.
 *
 * Recibe la lista de cuádruplos del generador y devuelve una lista NUEVA optimizada: la original
 * no se modifica (Cuadrupla es inmutable), así se pueden mostrar ambas. La lista optimizada es la
 * definitiva, la que usará la generación de código objeto.
 *
 * Métodos, en el orden en que se aplican:
 *   1. Plegado de constantes            (mirilla)
 *   2. Reducción de fuerza              (mirilla)
 *   3. Reducción de frecuencia          (bucles)
 *   4. Expansión de bucles              (bucles)
 *   5. Plegado de constantes, otra vez, para aprovechar lo que dejaron los anteriores
 */
public final class Optimizador {

    /**
     * Tipo de cada temporal ("t3" -> "entero"...). Es una COPIA del mapa del generador, con los temporales
     * nuevos que crea la expansión de bucles. El código objeto debe leer los tipos de aquí.
     */
    final Map<String, String> tiposTemporales;
    /** Nombre en el código intermedio -> símbolo (tipo, categoría, valor de las constantes...). */
    final Map<String, Simbolo> simbolos;

    public Optimizador(GeneradorCodigoIntermedio generador) {
        this.tiposTemporales = new LinkedHashMap<String, String>(generador.getTiposTemporales());
        this.simbolos = generador.getSimbolosIR();
    }

    /** Tipos de todos los temporales de la lista optimizada (incluye los creados al optimizar). */
    public Map<String, String> getTiposTemporales() {
        return Collections.unmodifiableMap(tiposTemporales);
    }

    public List<Cuadrupla> optimizar(List<Cuadrupla> original) {
        List<Cuadrupla> lista = new ArrayList<Cuadrupla>(original);
        lista = new PlegadoConstantes(this).aplicar(lista);
        lista = new ReduccionFuerza(this).aplicar(lista);
        lista = new ReduccionFrecuencia(this).aplicar(lista);
        lista = new ExpansionBucles(this).aplicar(lista);
        lista = new PlegadoConstantes(this).aplicar(lista);
        return Collections.unmodifiableList(lista);
    }
}
