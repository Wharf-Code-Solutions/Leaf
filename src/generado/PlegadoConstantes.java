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

    // Da acceso a la tabla de símbolos (para saber qué nombres son constantes declaradas)
    private final Optimizador optimizador;

    PlegadoConstantes(Optimizador optimizador) {
        this.optimizador = optimizador;
    }

    /** Recibe la lista de cuádruplos y devuelve una lista NUEVA con el plegado aplicado. */
    List<Cuadrupla> aplicar(List<Cuadrupla> lista) {
        // Constantes declaradas ($PI -> 3.1416): valen en todo el programa
        Map<String, String> constantes = constantesDeclaradas();
        // Cuántas veces se asigna cada nombre; se usa en la limpieza final
        Map<String, Integer> definiciones = AnalisisCuadruplas.contarDefiniciones(lista);

        List<Cuadrupla> resultado = new ArrayList<Cuadrupla>(lista.size());

        // La mirilla trabaja bloque por bloque (cada bloque es {inicio, fin})
        for (int[] bloque : AnalisisCuadruplas.bloquesBasicos(lista)) {
            // Valores conocidos del bloque (nombre -> literal). Se crea de nuevo en cada bloque:
            // al llegar a una etiqueta se olvida todo, salvo las constantes declaradas.
            Map<String, String> conocidos = new HashMap<String, String>(constantes);

            for (int i = bloque[0]; i < bloque[1]; i++) {
                // PASO 1 - Propagar: cambia los operandos conocidos por su valor ("+ 2 t1" -> "+ 2 12")
                Cuadrupla c = AnalisisCuadruplas.sustituir(lista.get(i), conocidos);

                // PASO 2 - Plegar: si todos los operandos son números, calcula ("+ 2 12 -> t2" -> "= 14 -> t2")
                if (EvaluadorConstantes.esCalculable(c.op) && operandosConstantes(c)) {
                    String valor = EvaluadorConstantes.evaluar(c.op, c.arg1, c.arg2);
                    // evaluar devuelve null si no es seguro (división entre cero...): la instrucción se queda igual
                    if (valor != null) c = new Cuadrupla(Op.ASIGNA, valor, null, c.resultado);
                }

                // PASO 3 - Recordar u olvidar: nombre que asigna esta instrucción (temporal o variable)
                String definido = AnalisisCuadruplas.definicion(c);
                if (definido != null && !constantes.containsKey(definido)) {
                    // Recibe un número: se recuerda para las siguientes instrucciones del bloque
                    if (c.op == Op.ASIGNA && AnalisisCuadruplas.esLiteral(c.arg1)) conocidos.put(definido, c.arg1);
                    // Recibe otra cosa (read, una operación no calculable...): se olvida lo que valía

                    else conocidos.remove(definido);
                }
                // PASO 4 - Una llamada puede modificar las variables globales: se olvidan sus valores
                if (c.op == Op.LLAMA) olvidarGlobales(conocidos, constantes);

                resultado.add(c);
            }
        }
        // PASO 5 - Limpieza: borra los temporales que quedaron sin uso
        return eliminarTemporalesSinUso(resultado, definiciones);
    }

    /** ¿Todos los operandos que la operación lee son literales? */
    private static boolean operandosConstantes(Cuadrupla c) {
        List<String> usos = AnalisisCuadruplas.usos(c);   // operandos que se leen (arg1, arg2)
        if (usos.isEmpty()) return false;                  // sin operandos no hay nada que calcular
        for (String u : usos) if (!AnalisisCuadruplas.esLiteral(u)) return false;   // basta uno no numérico
        return true;
    }

    /** Constantes declaradas con "constante" y su valor (adaptado al tipo declarado: decimal 3 -> 3.0). */
    private Map<String, String> constantesDeclaradas() {
        Map<String, String> constantes = new HashMap<String, String>();
        // simbolos: nombre en el código intermedio ($PI, $PI@funcion) -> símbolo de la tabla
        for (Map.Entry<String, Simbolo> e : optimizador.simbolos.entrySet()) {
            Simbolo s = e.getValue();
            // Solo constantes con valor guardado por el semántico (valorConstante)
            if (!"constante".equals(s.categoria) || s.valorConstante == null) continue;
            String valor = s.valorConstante.toString();
            // Una constante decimal escrita como entero (3) se usa como decimal (3.0)
            if ("decimal".equals(s.tipo) && "entero".equals(AnalisisCuadruplas.tipoLiteral(valor))) valor = valor + ".0";
            if (AnalisisCuadruplas.esLiteral(valor)) constantes.put(e.getKey(), valor);
        }
        return constantes;
    }

    /** Quita de los valores conocidos las variables globales ($x sin @), menos las constantes declaradas. */
    private static void olvidarGlobales(Map<String, String> conocidos, Map<String, String> constantes) {
        Iterator<String> it = conocidos.keySet().iterator();
        while (it.hasNext()) {
            String nombre = it.next();
            if (AnalisisCuadruplas.esVariableGlobal(nombre) && !constantes.containsKey(nombre)) it.remove();
        }
    }

    /** Quita "= literal -> tN" cuando tN se asigna una sola vez y ya no tiene usos. */
    private static List<Cuadrupla> eliminarTemporalesSinUso(List<Cuadrupla> lista, Map<String, Integer> definiciones) {
        // Usos contados en la lista YA propagada: si su valor se sustituyó en todos lados, quedan 0
        Map<String, Integer> usos = AnalisisCuadruplas.contarUsos(lista);
        List<Cuadrupla> resultado = new ArrayList<Cuadrupla>(lista.size());
        for (Cuadrupla c : lista) {
            boolean sobra = c.op == Op.ASIGNA                                // es "= algo -> destino"
                    && AnalisisCuadruplas.esLiteral(c.arg1)                   // ese algo es un número
                    && AnalisisCuadruplas.esTemporal(c.resultado)             // el destino es un temporal (nunca una variable)
                    && definiciones.getOrDefault(c.resultado, 0) == 1         // se asigna una sola vez en todo el programa
                    && usos.getOrDefault(c.resultado, 0) == 0;                // y ya nadie lo lee
            if (!sobra) resultado.add(c);
        }
        return resultado;
    }
}
