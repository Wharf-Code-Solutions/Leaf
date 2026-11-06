package generado;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import generado.Cuadrupla.Op;

/**
 * Expansión de bucles (loop unrolling completo).
 *
 * Si un ciclo se ejecuta una cantidad de veces CONOCIDA en compilación, se quita el ciclo y se repite su
 * cuerpo tantas veces como vueltas daba. Así desaparecen la comparación, el salto condicional, el
 * incremento y el salto de regreso, y la segunda pasada de plegado de constantes puede calcular todo
 * lo que dependa del contador.
 *
 * Forma de ciclo que reconoce (es exactamente lo que genera GeneradorCodigoIntermedio para
 * "repetir N veces" y para "para" con paso constante):
 *
 *        label Lini
 *        (<|<=|>|>=) X, limite -> t
 *        ifFalse t goto Lfin
 *        ...cuerpo...
 *        label Lcont
 *        + X, paso -> X
 *        goto Lini
 *        label Lfin
 *
 * Condiciones para expandir (si alguna falla, el ciclo se deja intacto):
 *   - valor inicial de X, límite y paso son enteros conocidos (literales, o variables a las que un literal
 *     se les asignó antes del ciclo en código recto, sin etiquetas ni saltos de por medio);
 *   - el cuerpo no modifica X, el límite ni el paso (y, si son globales, no hay llamadas en el cuerpo);
 *   - el ciclo es el más interno (el cuerpo no tiene saltos hacia atrás);
 *   - número de vueltas <= MAX_ITERACIONES y (vueltas x cuádruplos del cuerpo) <= MAX_CUADRUPLAS.
 *
 * Detalles de la expansión:
 *   - Cada copia del cuerpo usa temporales y etiquetas NUEVOS (así cada temporal se asigna una sola vez
 *     y el plegado puede eliminar los que sobren).
 *   - Si el contador se usa (en el cuerpo o después del ciclo), cada copia empieza con "= valor -> X"
 *     y, al terminar, X queda con el valor que tendría al salir del ciclo. Esa asignación hace que el
 *     plegado de constantes sustituya X por su valor dentro de cada copia.
 *   - "continuar" salta al final de SU copia; "romper" salta al final de todas las copias.
 *   - Los ciclos anidados se expanden de adentro hacia afuera: al desaparecer el interno, el externo pasa
 *     a ser el más interno y se puede expandir en la siguiente vuelta.
 */
final class ExpansionBucles {

    /** Máximo de vueltas que se expanden. */
    static final int MAX_ITERACIONES = 8;
    /** Máximo de cuádruplos que puede aportar el cuerpo ya repetido (vueltas x tamaño del cuerpo). */
    static final int MAX_CUADRUPLAS = 80;

    private final Optimizador optimizador;
    private int siguienteTemporal;
    private int siguienteEtiqueta;

    ExpansionBucles(Optimizador optimizador) {
        this.optimizador = optimizador;
    }

    List<Cuadrupla> aplicar(List<Cuadrupla> lista) {
        List<Cuadrupla> actual = new ArrayList<Cuadrupla>(lista);
        inicializarContadores(actual);

        boolean huboCambio = true;
        while (huboCambio) {
            huboCambio = false;
            // De atrás hacia adelante: un ciclo interno empieza después que el externo que lo contiene
            for (int i = actual.size() - 1; i >= 0; i--) {
                List<Cuadrupla> nueva = intentarExpandir(actual, i);
                if (nueva != null) {
                    actual = nueva;
                    huboCambio = true;
                    break;   // las posiciones cambiaron: se vuelve a buscar
                }
            }
        }
        return actual;
    }

    // ================================================================
    //  RECONOCIMIENTO Y EXPANSIÓN DE UN CICLO
    // ================================================================

    /** Si en la posición i empieza un ciclo expandible, devuelve la lista nueva; si no, null. */
    private List<Cuadrupla> intentarExpandir(List<Cuadrupla> l, int i) {
        int n = l.size();
        Cuadrupla ini = l.get(i);
        if (ini.op != Op.ETIQUETA || i + 2 >= n) return null;

        Cuadrupla cmp = l.get(i + 1);
        Cuadrupla sf = l.get(i + 2);
        if (!esComparacionDeCiclo(cmp.op)) return null;
        if (sf.op != Op.SALTA_FALSO || cmp.resultado == null || !cmp.resultado.equals(sf.arg1)) return null;

        String etqIni = ini.resultado;
        String etqFin = sf.resultado;
        String x = cmp.arg1;                          // contador del ciclo
        if (x == null || entero(x) != null) return null;

        // Debe haber exactamente un salto a la etiqueta de inicio y ser el "goto" de regreso
        int g = -1, saltosAIni = 0;
        for (int k = 0; k < n; k++) {
            Cuadrupla c = l.get(k);
            if (esSalto(c.op) && etqIni.equals(c.resultado)) {
                saltosAIni++;
                if (c.op == Op.SALTA && k > i + 2) g = k;
            }
        }
        if (saltosAIni != 1 || g < i + 5 || g + 1 >= n) return null;

        Cuadrupla incr = l.get(g - 1);
        Cuadrupla cont = l.get(g - 2);
        Cuadrupla etqF = l.get(g + 1);
        if (incr.op != Op.SUMA || !x.equals(incr.arg1) || !x.equals(incr.resultado)) return null;
        if (cont.op != Op.ETIQUETA || etqF.op != Op.ETIQUETA || !etqFin.equals(etqF.resultado)) return null;

        int cuerpoIni = i + 3;
        int cuerpoFin = g - 3;                       // el cuerpo puede estar vacío (cuerpoFin < cuerpoIni)

        // ---- Revisión del cuerpo ----
        Map<String, Integer> etiquetas = indiceEtiquetas(l);
        Set<String> etqCuerpo = new LinkedHashSet<String>();
        Set<String> temporales = new LinkedHashSet<String>();
        Set<String> definidos = new HashSet<String>();
        boolean hayLlamada = false, usaFin = false, usaCont = false, usadaEnCuerpo = false;

        for (int k = cuerpoIni; k <= cuerpoFin; k++) {
            Cuadrupla c = l.get(k);
            switch (c.op) {
                case INICIO: case FIN: case FUNC_INICIO: case FUNC_FIN:
                    return null;
                default:
                    break;
            }
            if (c.op == Op.ETIQUETA) etqCuerpo.add(c.resultado);
            if (c.op == Op.LLAMA) hayLlamada = true;

            String d = AnalisisCuadruplas.definicion(c);
            if (d != null) definidos.add(d);
            if (c.resultado != null && defineResultado(c.op) && AnalisisCuadruplas.esTemporal(c.resultado)) {
                temporales.add(c.resultado);
            }
            for (String u : AnalisisCuadruplas.usos(c)) {
                if (x.equals(u)) usadaEnCuerpo = true;
            }
        }

        for (int k = cuerpoIni; k <= cuerpoFin; k++) {
            Cuadrupla c = l.get(k);
            if (!esSalto(c.op)) continue;
            String destino = c.resultado;
            if (destino.equals(etqFin)) {
                usaFin = true;                       // romper
            } else if (destino.equals(cont.resultado)) {
                usaCont = true;                      // continuar
            } else {
                Integer idx = etiquetas.get(destino);
                // Solo se permiten saltos hacia adelante dentro del cuerpo (un salto hacia atrás es un ciclo anidado)
                if (idx == null || idx <= k || idx < cuerpoIni || idx > cuerpoFin) return null;
            }
        }

        // Ningún salto desde fuera puede entrar al ciclo
        Set<String> etqRegion = new HashSet<String>(etqCuerpo);
        etqRegion.add(etqIni);
        etqRegion.add(cont.resultado);
        etqRegion.add(etqFin);
        boolean usadaFuera = false;
        for (int k = 0; k < n; k++) {
            if (k >= i && k <= g + 1) continue;
            Cuadrupla c = l.get(k);
            if (esSalto(c.op) && etqRegion.contains(c.resultado)) return null;
            for (String u : AnalisisCuadruplas.usos(c)) {
                if (x.equals(u)) usadaFuera = true;
                if (temporales.contains(u)) return null;   // un temporal del cuerpo se usa fuera: no se toca
            }
        }

        // ---- Lo que no debe cambiar mientras el ciclo corre ----
        String limiteOp = cmp.arg2;
        String pasoOp = incr.arg2;
        String[] observados = { x, limiteOp, pasoOp };
        for (String o : observados) {
            if (o == null || entero(o) != null) continue;
            if (definidos.contains(o)) return null;
            if (hayLlamada && AnalisisCuadruplas.esVariableGlobal(o)) return null;
        }

        // ---- Número de vueltas ----
        Long x0 = valor(l, i, x, 0);
        Long limite = valor(l, i, limiteOp, 0);
        Long paso = valor(l, i, pasoOp, 0);
        if (x0 == null || limite == null || paso == null) return null;

        long vx0 = x0.longValue(), vs = paso.longValue();
        long cuenta = contarIteraciones(cmp.op, vx0, limite.longValue(), vs);
        if (cuenta < 0) return null;
        int tamCuerpo = Math.max(0, cuerpoFin - cuerpoIni + 1);
        if (cuenta > MAX_ITERACIONES || cuenta * tamCuerpo > MAX_CUADRUPLAS) return null;

        boolean escribirX = usadaEnCuerpo || usadaFuera;

        // ---- Construcción de la lista nueva ----
        List<Cuadrupla> res = new ArrayList<Cuadrupla>(n + (int) cuenta * tamCuerpo);
        res.addAll(l.subList(0, i));

        for (int k = 0; k < cuenta; k++) {
            // En la primera vuelta X ya vale x0 (se asignó antes del ciclo)
            if (k > 0 && escribirX) {
                res.add(new Cuadrupla(Op.ASIGNA, String.valueOf(vx0 + k * vs), null, x));
            }
            Map<String, String> mapaTemp = new HashMap<String, String>();
            Map<String, String> mapaEtq = new HashMap<String, String>();
            for (String t : temporales) {
                String nuevo = "t" + (siguienteTemporal++);
                mapaTemp.put(t, nuevo);
                String tipo = optimizador.tiposTemporales.get(t);
                optimizador.tiposTemporales.put(nuevo, tipo == null ? "?" : tipo);
            }
            for (String e : etqCuerpo) mapaEtq.put(e, "L" + (siguienteEtiqueta++));
            mapaEtq.put(cont.resultado, "L" + (siguienteEtiqueta++));

            for (int j = cuerpoIni; j <= cuerpoFin; j++) res.add(copiar(l.get(j), mapaTemp, mapaEtq));
            if (usaCont) res.add(new Cuadrupla(Op.ETIQUETA, null, null, mapaEtq.get(cont.resultado)));
        }
        if (cuenta > 0) {
            // Valor que X tendría al salir del ciclo por la condición (un "romper" salta por encima de esto)
            if (usadaFuera) res.add(new Cuadrupla(Op.ASIGNA, String.valueOf(vx0 + cuenta * vs), null, x));
            if (usaFin) res.add(new Cuadrupla(Op.ETIQUETA, null, null, etqFin));
        }
        res.addAll(l.subList(g + 2, n));
        return res;
    }

    /**
     * Cuántas veces se ejecuta el cuerpo de:  X = x0 ; mientras (X cmp b) { cuerpo ; X += s }
     * Devuelve -1 si no es seguro (paso 0 o con sentido contrario a la comparación: ciclo infinito).
     */
    private static long contarIteraciones(Op op, long x0, long b, long s) {
        if (s == 0) return -1;
        switch (op) {
            case MENOR:
                if (s < 0) return -1;
                return x0 < b ? (b - x0 + s - 1) / s : 0;
            case MENOR_IGUAL:
                if (s < 0) return -1;
                return x0 <= b ? (b - x0) / s + 1 : 0;
            case MAYOR:
                if (s > 0) return -1;
                return x0 > b ? (x0 - b + (-s) - 1) / (-s) : 0;
            case MAYOR_IGUAL:
                if (s > 0) return -1;
                return x0 >= b ? (x0 - b) / (-s) + 1 : 0;
            default:
                return -1;
        }
    }

    // ================================================================
    //  UTILIDADES
    // ================================================================

    /** Copia una cuádrupla del cuerpo cambiando temporales y etiquetas por los de esta copia. */
    private static Cuadrupla copiar(Cuadrupla c, Map<String, String> temp, Map<String, String> etq) {
        switch (c.op) {
            case ETIQUETA:
            case SALTA:
                return new Cuadrupla(c.op, c.arg1, c.arg2, renombrar(c.resultado, etq));
            case SALTA_FALSO:
            case SALTA_VERD:
                return new Cuadrupla(c.op, renombrar(c.arg1, temp), c.arg2, renombrar(c.resultado, etq));
            case LLAMA:
                // arg1 = nombre de la función, arg2 = número de argumentos: no son temporales
                return new Cuadrupla(c.op, c.arg1, c.arg2, renombrar(c.resultado, temp));
            default:
                return new Cuadrupla(c.op, renombrar(c.arg1, temp), renombrar(c.arg2, temp), renombrar(c.resultado, temp));
        }
    }

    private static String renombrar(String nombre, Map<String, String> mapa) {
        if (nombre == null) return null;
        String nuevo = mapa.get(nombre);
        return nuevo != null ? nuevo : nombre;
    }

    /**
     * Valor entero conocido de un operando justo ANTES de la posición pos: un literal, o el literal que se
     * le asignó en código recto hacia atrás (se detiene en etiquetas y saltos).
     */
    private static Long valor(List<Cuadrupla> l, int pos, String operando, int profundidad) {
        if (operando == null || profundidad > 4) return null;
        Long v = entero(operando);
        if (v != null) return v;

        boolean global = AnalisisCuadruplas.esVariableGlobal(operando);
        for (int k = pos - 1; k >= 0; k--) {
            Cuadrupla c = l.get(k);
            switch (c.op) {
                case ETIQUETA: case SALTA: case SALTA_FALSO: case SALTA_VERD: case INICIO: case FUNC_INICIO:
                    return null;
                default:
                    break;
            }
            if (c.op == Op.LLAMA && global) return null;
            if (operando.equals(AnalisisCuadruplas.definicion(c))) {
                return c.op == Op.ASIGNA ? valor(l, k, c.arg1, profundidad + 1) : null;
            }
        }
        return null;
    }

    private static Long entero(String s) {
        if (s == null || !s.matches("-?[0-9]{1,9}")) return null;
        return Long.valueOf(s);
    }

    private static boolean esComparacionDeCiclo(Op op) {
        return op == Op.MENOR || op == Op.MENOR_IGUAL || op == Op.MAYOR || op == Op.MAYOR_IGUAL;
    }

    private static boolean esSalto(Op op) {
        return op == Op.SALTA || op == Op.SALTA_FALSO || op == Op.SALTA_VERD;
    }

    /** ¿El campo "resultado" de esta operación es un valor que se define (y no una etiqueta o un destino de arreglo)? */
    private static boolean defineResultado(Op op) {
        switch (op) {
            case ETIQUETA: case SALTA: case SALTA_FALSO: case SALTA_VERD:
            case INICIO: case FIN: case FUNC_INICIO: case FUNC_FIN:
            case RECIBE: case ARR_ESCRIBE:
                return false;
            default:
                return true;
        }
    }

    private static Map<String, Integer> indiceEtiquetas(List<Cuadrupla> l) {
        Map<String, Integer> m = new HashMap<String, Integer>();
        for (int k = 0; k < l.size(); k++) {
            Cuadrupla c = l.get(k);
            if (c.op == Op.ETIQUETA && c.resultado != null) m.put(c.resultado, k);
        }
        return m;
    }

    /** Los temporales y etiquetas nuevos empiezan después del mayor "tN" / "LN" que ya existe. */
    private void inicializarContadores(List<Cuadrupla> l) {
        int maxT = 0, maxL = 0;
        for (Cuadrupla c : l) {
            for (String s : new String[] { c.arg1, c.arg2, c.resultado }) {
                maxT = Math.max(maxT, sufijo(s, 't'));
                maxL = Math.max(maxL, sufijo(s, 'L'));
            }
        }
        for (String t : optimizador.tiposTemporales.keySet()) maxT = Math.max(maxT, sufijo(t, 't'));
        siguienteTemporal = maxT + 1;
        siguienteEtiqueta = maxL + 1;
    }

    private static int sufijo(String s, char prefijo) {
        if (s == null || s.length() < 2 || s.charAt(0) != prefijo) return 0;
        for (int k = 1; k < s.length(); k++) {
            if (!Character.isDigit(s.charAt(k))) return 0;
        }
        try {
            return Integer.parseInt(s.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
