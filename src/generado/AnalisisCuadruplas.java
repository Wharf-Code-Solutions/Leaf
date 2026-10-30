package generado;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import generado.Cuadrupla.Op;

/**
 * Utilidades de análisis sobre una lista de cuádruplos, compartidas por los métodos de optimización:
 * qué es cada operando, qué define y qué usa cada instrucción, bloques básicos y ciclos.
 */
final class AnalisisCuadruplas {

    private AnalisisCuadruplas() {}

    // ----------------------------------------------------------------
    //  Operandos
    // ----------------------------------------------------------------

    static boolean esTemporal(String s) {
        return s != null && s.matches("t[0-9]+");
    }

    static boolean esVariable(String s) {
        return s != null && s.startsWith("$");
    }

    /** Variable del programa principal (sin "@funcion"): una llamada a función podría modificarla. */
    static boolean esVariableGlobal(String s) {
        return esVariable(s) && s.indexOf('@') < 0;
    }

    static boolean esLiteral(String s) {
        return tipoLiteral(s) != null;
    }

    /** Tipo de un literal ("entero", "decimal", "texto", "logico") o null si no es un literal. */
    static String tipoLiteral(String s) {
        if (s == null || s.isEmpty()) return null;
        if (s.startsWith("\"")) return "texto";
        if (s.equals("verdadero") || s.equals("falso")) return "logico";
        if (s.matches("-?[0-9]+")) return "entero";
        if (s.matches("-?[0-9]+\\.[0-9]+([eE]-?[0-9]+)?")) return "decimal";
        return null;
    }

    static boolean esCero(String literal) {
        String t = tipoLiteral(literal);
        if ("entero".equals(t)) return Long.parseLong(literal) == 0;
        if ("decimal".equals(t)) return Double.parseDouble(literal) == 0.0;
        return false;
    }

    // ----------------------------------------------------------------
    //  Qué define y qué usa cada instrucción
    // ----------------------------------------------------------------

    /** Nombre que la instrucción asigna (temporal o variable), o null si no asigna nada. */
    static String definicion(Cuadrupla c) {
        switch (c.op) {
            case SALTA: case SALTA_FALSO: case SALTA_VERD: case ETIQUETA:
            case INICIO: case FIN: case FUNC_INICIO: case FUNC_FIN:
            case PARAM: case RETORNA: case MUESTRA:
            case ARR_ESCRIBE: case AGREGAR: case QUITAR:
                return null;
            default:
                return c.resultado;   // ASIGNA, conversiones, aritmética, comparaciones, RECIBE, LLAMA, LEE, arreglos...
        }
    }

    /** Arreglo que la instrucción modifica ([]=, append, remove), o null. */
    static String arregloModificado(Cuadrupla c) {
        if (c.op == Op.ARR_ESCRIBE) return c.resultado;
        if (c.op == Op.AGREGAR || c.op == Op.QUITAR) return c.arg1;
        return null;
    }

    /** Qué campos de la instrucción son valores que se leen: {arg1, arg2, resultado}. */
    static boolean[] camposUsados(Cuadrupla c) {
        switch (c.op) {
            case ASIGNA: case A_DECIMAL: case A_ENTERO: case NEG: case RAIZ: case NO:
            case SALTA_FALSO: case SALTA_VERD: case PARAM: case RETORNA: case MUESTRA:
            case LEE: case LONGITUD: case NUEVO_ARR:
                return new boolean[] { true, false, false };
            case SUMA: case RESTA: case MULT: case DIV: case MOD: case POT: case RAIZ_N:
            case IGUAL: case DIFERENTE: case MAYOR: case MENOR: case MAYOR_IGUAL: case MENOR_IGUAL:
            case ARR_LEE: case AGREGAR: case QUITAR:
                return new boolean[] { true, true, false };
            case ARR_ESCRIBE:
                return new boolean[] { true, true, true };
            default:
                return new boolean[] { false, false, false };   // LLAMA (nombre y nº de args), saltos, etiquetas...
        }
    }

    /** Operandos que la instrucción lee (sin nulos). */
    static List<String> usos(Cuadrupla c) {
        boolean[] campos = camposUsados(c);
        List<String> usos = new ArrayList<String>();
        if (campos[0] && c.arg1 != null) usos.add(c.arg1);
        if (campos[1] && c.arg2 != null) usos.add(c.arg2);
        if (campos[2] && c.resultado != null) usos.add(c.resultado);
        return usos;
    }

    /** Copia de la instrucción reemplazando sus operandos leídos según el mapa (los que no estén, se quedan). */
    static Cuadrupla sustituir(Cuadrupla c, Map<String, String> valores) {
        boolean[] campos = camposUsados(c);
        String a1 = (campos[0] && valores.containsKey(c.arg1)) ? valores.get(c.arg1) : c.arg1;
        String a2 = (campos[1] && valores.containsKey(c.arg2)) ? valores.get(c.arg2) : c.arg2;
        String res = (campos[2] && valores.containsKey(c.resultado)) ? valores.get(c.resultado) : c.resultado;
        if (a1 == c.arg1 && a2 == c.arg2 && res == c.resultado) return c;
        return new Cuadrupla(c.op, a1, a2, res);
    }

    static Map<String, Integer> contarDefiniciones(List<Cuadrupla> lista) {
        Map<String, Integer> cuenta = new HashMap<String, Integer>();
        for (Cuadrupla c : lista) {
            String d = definicion(c);
            if (d != null) cuenta.put(d, cuenta.getOrDefault(d, 0) + 1);
        }
        return cuenta;
    }

    static Map<String, Integer> contarUsos(List<Cuadrupla> lista) {
        Map<String, Integer> cuenta = new HashMap<String, Integer>();
        for (Cuadrupla c : lista) {
            for (String u : usos(c)) cuenta.put(u, cuenta.getOrDefault(u, 0) + 1);
        }
        return cuenta;
    }

    // ----------------------------------------------------------------
    //  Bloques básicos y ciclos
    // ----------------------------------------------------------------

    static boolean esSalto(Cuadrupla c) {
        return c.op == Op.SALTA || c.op == Op.SALTA_FALSO || c.op == Op.SALTA_VERD;
    }

    /**
     * Bloques básicos: regiones de instrucciones seguidas sin etiquetas ni saltos intermedios.
     * Cada bloque es {inicio, fin} con fin exclusivo. Un bloque empieza en una etiqueta o en el
     * inicio de una función, y termina después de un salto, un return o el fin del programa.
     */
    static List<int[]> bloquesBasicos(List<Cuadrupla> lista) {
        List<int[]> bloques = new ArrayList<int[]>();
        int inicio = 0;
        for (int i = 0; i < lista.size(); i++) {
            Cuadrupla c = lista.get(i);
            boolean abreBloque = (c.op == Op.ETIQUETA || c.op == Op.FUNC_INICIO) && i > inicio;
            if (abreBloque) {
                bloques.add(new int[] { inicio, i });
                inicio = i;
            }
            boolean cierraBloque = esSalto(c) || c.op == Op.RETORNA || c.op == Op.FIN || c.op == Op.FUNC_FIN;
            if (cierraBloque) {
                bloques.add(new int[] { inicio, i + 1 });
                inicio = i + 1;
            }
        }
        if (inicio < lista.size()) bloques.add(new int[] { inicio, lista.size() });
        return bloques;
    }

    /**
     * Ciclos: un salto que regresa a una etiqueta anterior ({@code goto L1} o {@code if t2 goto L1}
     * del hacer-mientras) delimita un ciclo desde esa etiqueta hasta el último salto que vuelve a ella.
     * Cada ciclo es {indiceEtiqueta, indiceSaltoDeRegreso}, ordenados del más pequeño (interno) al más grande.
     */
    static List<int[]> ciclos(List<Cuadrupla> lista) {
        Map<String, Integer> etiquetas = new HashMap<String, Integer>();
        for (int i = 0; i < lista.size(); i++) {
            if (lista.get(i).op == Op.ETIQUETA) etiquetas.put(lista.get(i).resultado, i);
        }
        Map<Integer, Integer> regreso = new HashMap<Integer, Integer>();
        for (int j = 0; j < lista.size(); j++) {
            Cuadrupla c = lista.get(j);
            if (!esSalto(c)) continue;
            Integer i = etiquetas.get(c.resultado);
            if (i != null && i < j) regreso.put(i, Math.max(j, regreso.getOrDefault(i, j)));
        }
        List<int[]> ciclos = new ArrayList<int[]>();
        for (Map.Entry<Integer, Integer> e : regreso.entrySet()) ciclos.add(new int[] { e.getKey(), e.getValue() });
        Collections.sort(ciclos, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return Integer.compare(a[1] - a[0], b[1] - b[0]);
            }
        });
        return ciclos;
    }
}
