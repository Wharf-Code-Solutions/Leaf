package generado;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import generado.Cuadrupla.Op;

/**
 * Plegado de constantes (optimización de mirilla).
 *
 * Recorre cada bloque básico (instrucciones seguidas sin etiquetas ni saltos intermedios: la "mirilla"
 * nunca cruza un label ni un salto) y:
 *   1. Propaga los valores conocidos: si un temporal o una variable recibió un literal dentro del bloque,
 *      sus usos siguientes en ese mismo bloque se sustituyen por el literal (hasta que se reasigne).
 *      Las constantes declaradas con "constante" se sustituyen en todo el programa, porque no cambian.
 *   2. Calcula las operaciones cuyos operandos quedaron constantes: "* 4 2 -> t1" se convierte en "= 8 -> t1".
 *   3. Elimina las asignaciones a temporales que, tras propagar su valor, ya nadie usa.
 *
 * Ejemplo:  * 4 2 -> t8 ;  + 8 t8 -> t9 ;  = t9 -> $x      queda      = 16 -> $x
 */
final class PlegadoConstantes {

    private final Optimizador optimizador;

    PlegadoConstantes(Optimizador optimizador) {
        this.optimizador = optimizador;
    }

    List<Cuadrupla> aplicar(List<Cuadrupla> lista) {
        Map<String, String> constantes = constantesDeclaradas();
        Map<String, Integer> definiciones = AnalisisCuadruplas.contarDefiniciones(lista);

        List<Cuadrupla> resultado = new ArrayList<Cuadrupla>(lista.size());
        for (int[] bloque : AnalisisCuadruplas.bloquesBasicos(lista)) {
            // Valores conocidos en este bloque: se reinician en cada bloque (la mirilla no cruza saltos)
            Map<String, String> conocidos = new HashMap<String, String>(constantes);

            for (int i = bloque[0]; i < bloque[1]; i++) {
                Cuadrupla c = AnalisisCuadruplas.sustituir(lista.get(i), conocidos);

                if (EvaluadorConstantes.esCalculable(c.op) && operandosConstantes(c)) {
                    String valor = EvaluadorConstantes.evaluar(c.op, c.arg1, c.arg2);
                    if (valor != null) c = new Cuadrupla(Op.ASIGNA, valor, null, c.resultado);
                }

                String definido = AnalisisCuadruplas.definicion(c);
                if (definido != null && !constantes.containsKey(definido)) {
                    if (c.op == Op.ASIGNA && AnalisisCuadruplas.esLiteral(c.arg1)) conocidos.put(definido, c.arg1);
                    else conocidos.remove(definido);
                }
                // Una llamada puede modificar las variables globales: se olvidan sus valores
                if (c.op == Op.LLAMA) olvidarGlobales(conocidos, constantes);

                resultado.add(c);
            }
        }
        return eliminarTemporalesSinUso(resultado, definiciones);
    }

    /** ¿Todos los operandos que la operación lee son literales? */
    private static boolean operandosConstantes(Cuadrupla c) {
        List<String> usos = AnalisisCuadruplas.usos(c);
        if (usos.isEmpty()) return false;
        for (String u : usos) if (!AnalisisCuadruplas.esLiteral(u)) return false;
        return true;
    }

    /** Constantes declaradas con "constante" y su valor (adaptado al tipo declarado: decimal 3 -> 3.0). */
    private Map<String, String> constantesDeclaradas() {
        Map<String, String> constantes = new HashMap<String, String>();
        for (Map.Entry<String, Simbolo> e : optimizador.simbolos.entrySet()) {
            Simbolo s = e.getValue();
            if (!"constante".equals(s.categoria) || s.valorConstante == null) continue;
            String valor = s.valorConstante.toString();
            if ("decimal".equals(s.tipo) && "entero".equals(AnalisisCuadruplas.tipoLiteral(valor))) valor = valor + ".0";
            if (AnalisisCuadruplas.esLiteral(valor)) constantes.put(e.getKey(), valor);
        }
        return constantes;
    }

    private static void olvidarGlobales(Map<String, String> conocidos, Map<String, String> constantes) {
        Iterator<String> it = conocidos.keySet().iterator();
        while (it.hasNext()) {
            String nombre = it.next();
            if (AnalisisCuadruplas.esVariableGlobal(nombre) && !constantes.containsKey(nombre)) it.remove();
        }
    }

    /** Quita "= literal -> tN" cuando tN se asigna una sola vez y ya no tiene usos. */
    private static List<Cuadrupla> eliminarTemporalesSinUso(List<Cuadrupla> lista, Map<String, Integer> definiciones) {
        Map<String, Integer> usos = AnalisisCuadruplas.contarUsos(lista);
        List<Cuadrupla> resultado = new ArrayList<Cuadrupla>(lista.size());
        for (Cuadrupla c : lista) {
            boolean sobra = c.op == Op.ASIGNA
                    && AnalisisCuadruplas.esLiteral(c.arg1)
                    && AnalisisCuadruplas.esTemporal(c.resultado)
                    && definiciones.getOrDefault(c.resultado, 0) == 1
                    && usos.getOrDefault(c.resultado, 0) == 0;
            if (!sobra) resultado.add(c);
        }
        return resultado;
    }
}
