package generado;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import generado.Cuadrupla.Op;

/**
 * Reducción de frecuencia (optimización de bucles), también llamada movimiento de código invariante.
 *
 * Un cálculo dentro de un ciclo es INVARIANTE si da el mismo resultado en todas las vueltas: sus operandos
 * son constantes o valores que no cambian dentro del ciclo. Esos cálculos se sacan y se colocan justo antes
 * de la etiqueta de inicio del ciclo, para que se ejecuten una sola vez en lugar de en cada vuelta.
 *
 * Ejemplo:  mientras ($i menor que $n * 2)  ->  "* $n 2 -> t1" sale del ciclo, antes de su "label".
 *
 * Solo se mueven cálculos puros que guardan su resultado en un temporal asignado una sola vez. No se mueven:
 *   - read, print, call (tienen efectos);
 *   - lecturas de arreglos ni length (el arreglo puede cambiar dentro del ciclo);
 *   - "/" ni "%" con divisor que no sea una constante distinta de cero (si el ciclo no se ejecutara,
 *     aparecería una división entre cero que antes no ocurría);
 *   - asignaciones a variables del usuario.
 * Si el ciclo contiene una llamada a función, se supone que todas las variables globales pueden cambiar.
 * Los ciclos anidados se procesan del interno al externo, así un cálculo puede salir de varios niveles.
 */
final class ReduccionFrecuencia {

    ReduccionFrecuencia(Optimizador optimizador) {
        // No necesita información del semántico: trabaja solo con la lista de cuádruplos.
    }

    List<Cuadrupla> aplicar(List<Cuadrupla> lista) {
        List<Cuadrupla> actual = new ArrayList<Cuadrupla>(lista);
        boolean huboCambio = true;
        while (huboCambio) {
            huboCambio = false;
            Map<String, Integer> definiciones = AnalisisCuadruplas.contarDefiniciones(actual);
            for (int[] ciclo : AnalisisCuadruplas.ciclos(actual)) {   // del más interno al más externo
                List<Integer> invariantes = invariantes(actual, ciclo, definiciones);
                if (!invariantes.isEmpty()) {
                    actual = sacarDelCiclo(actual, ciclo[0], invariantes);
                    huboCambio = true;
                    break;   // las posiciones cambiaron: se vuelven a detectar los ciclos
                }
            }
        }
        return actual;
    }

    /** Posiciones (en orden) de las instrucciones invariantes del ciclo {etiqueta, saltoDeRegreso}. */
    private static List<Integer> invariantes(List<Cuadrupla> lista, int[] ciclo, Map<String, Integer> definiciones) {
        int inicio = ciclo[0], fin = ciclo[1];

        // Todo lo que se asigna o modifica dentro del ciclo
        Set<String> modificados = new HashSet<String>();
        boolean hayLlamada = false;
        for (int k = inicio + 1; k <= fin; k++) {
            Cuadrupla c = lista.get(k);
            String d = AnalisisCuadruplas.definicion(c);
            if (d != null) modificados.add(d);
            String arr = AnalisisCuadruplas.arregloModificado(c);
            if (arr != null) modificados.add(arr);
            if (c.op == Op.LLAMA) hayLlamada = true;
        }

        List<Integer> posiciones = new ArrayList<Integer>();
        Set<String> sacados = new HashSet<String>();   // temporales que ya salen en esta misma ronda
        for (int k = inicio + 1; k < fin; k++) {
            Cuadrupla c = lista.get(k);
            if (!esMovible(c, definiciones)) continue;

            boolean invariante = true;
            for (String u : AnalisisCuadruplas.usos(c)) {
                if (AnalisisCuadruplas.esLiteral(u)) continue;
                boolean cambia = modificados.contains(u) && !sacados.contains(u);
                if (hayLlamada && AnalisisCuadruplas.esVariableGlobal(u)) cambia = true;
                if (cambia) { invariante = false; break; }
            }
            if (invariante) {
                posiciones.add(k);
                sacados.add(c.resultado);
            }
        }
        return posiciones;
    }

    /** ¿Es un cálculo puro que guarda en un temporal asignado una sola vez? */
    private static boolean esMovible(Cuadrupla c, Map<String, Integer> definiciones) {
        switch (c.op) {
            case ASIGNA: case A_DECIMAL: case A_ENTERO:
            case SUMA: case RESTA: case MULT: case POT: case RAIZ_N: case NEG: case RAIZ: case NO:
            case IGUAL: case DIFERENTE: case MAYOR: case MENOR: case MAYOR_IGUAL: case MENOR_IGUAL:
                break;
            case DIV: case MOD:
                // Solo con divisor constante distinto de cero
                if (!AnalisisCuadruplas.esLiteral(c.arg2) || AnalisisCuadruplas.esCero(c.arg2)) return false;
                break;
            default:
                return false;
        }
        return AnalisisCuadruplas.esTemporal(c.resultado) && definiciones.getOrDefault(c.resultado, 0) == 1;
    }

    /** Copia de la lista con las instrucciones indicadas movidas justo antes de la etiqueta del ciclo. */
    private static List<Cuadrupla> sacarDelCiclo(List<Cuadrupla> lista, int etiqueta, List<Integer> posiciones) {
        Set<Integer> mover = new HashSet<Integer>(posiciones);
        List<Cuadrupla> resultado = new ArrayList<Cuadrupla>(lista.size());
        for (int k = 0; k < etiqueta; k++) resultado.add(lista.get(k));
        for (int k : posiciones) resultado.add(lista.get(k));
        for (int k = etiqueta; k < lista.size(); k++) {
            if (!mover.contains(k)) resultado.add(lista.get(k));
        }
        return resultado;
    }
}
