package generado;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import generado.Cuadrupla.Op;

/**
 * Generador de código intermedio de Leaf en forma de CUÁDRUPLAS.
 *
 * Se ejecuta DESPUÉS del análisis semántico y solo si el programa no tiene errores.
 * Recorre el árbol (nodos AST de JJTree) apoyándose en la información que dejó
 * VisitanteSemantico: a qué símbolo (declaración) se refiere cada variable o función,
 * con su tipo y su ámbito.
 *
 * Decisiones de diseño (documentadas porque el manual no las fija):
 *  - Variables con nombre ÚNICO por declaración: "$x" (global), "$x@funcion" (local) y,
 *    si dos declaraciones darían el mismo nombre, se agrega "#2", "#3"... Así una variable
 *    local o un parámetro nunca se confunde con una global del mismo nombre.
 *  - Tipos: cada temporal tiene tipo (ver getTiposTemporales). Donde Leaf permite la
 *    coerción entero -> decimal (asignaciones, argumentos, retornos, operaciones mixtas,
 *    la división "/") se emite explícitamente A_DECIMAL.
 *  - Y / O con CORTOCIRCUITO (el lado derecho no se evalúa si el izquierdo ya decide).
 *  - "segun": sin caída al siguiente caso; "romper" dentro de un caso sale del segun.
 *  - "para": el límite "hasta" es INCLUSIVO y, junto con el paso, se evalúa una sola vez.
 *    Si el paso no es una constante, el sentido del ciclo (sube/baja) se decide al ejecutar.
 *  - Los contadores de temporales y etiquetas son de la instancia: cada compilación
 *    empieza en t1 / L1 sin estado compartido.
 *  - La precedencia de operadores ya viene resuelta por la gramática (el árbol está anidado).
 *
 * Estructura de la salida:  main: ...código principal... halt  /  func f: ... endfunc f
 * (las funciones se colocan después del código principal).
 */
public class GeneradorCodigoIntermedio {

    private final VisitanteSemantico semantico;

    private final List<Cuadrupla> cuadruplas = new ArrayList<Cuadrupla>();
    private final Deque<String> pilaBreak = new ArrayDeque<String>();
    private final Deque<String> pilaContinue = new ArrayDeque<String>();

    private int contTemporales = 1;
    private int contEtiquetas = 1;

    /** Tipo de cada temporal: "t3" -> "entero" | "decimal" | "logico" | "texto" | "arreglo:..." | "?". */
    private final Map<String, String> tiposTemporales = new LinkedHashMap<String, String>();
    /** Nombre único en el código intermedio de cada símbolo declarado. */
    private final IdentityHashMap<Simbolo, String> nombresIR = new IdentityHashMap<Simbolo, String>();
    /** Inverso: nombre en el código intermedio -> símbolo (tipo, ámbito, offset...). */
    private final Map<String, Simbolo> simbolosIR = new LinkedHashMap<String, Simbolo>();

    private String tipoRetornoActual = null;

    public GeneradorCodigoIntermedio(VisitanteSemantico semantico) {
        this.semantico = semantico;
    }

    // ================================================================
    //  API PÚBLICA
    // ================================================================

    public void generar(Node raiz) {
        cuadruplas.clear();
        pilaBreak.clear();
        pilaContinue.clear();
        tiposTemporales.clear();
        nombresIR.clear();
        simbolosIR.clear();
        contTemporales = 1;
        contEtiquetas = 1;
        tipoRetornoActual = null;

        List<Node> funciones = new ArrayList<Node>();

        emitir(Op.INICIO, null, null, "main");
        for (int i = 0; i < raiz.jjtGetNumChildren(); i++) {
            Node hijo = raiz.jjtGetChild(i);
            if (hijo instanceof ASTDefFuncion) funciones.add(hijo);
            else sentencia(hijo);
        }
        emitir(Op.FIN, null, null, null);

        for (Node f : funciones) funcion(f);
    }

    public List<Cuadrupla> getCuadruplas() {
        return Collections.unmodifiableList(cuadruplas);
    }

    public Map<String, String> getTiposTemporales() {
        return Collections.unmodifiableMap(tiposTemporales);
    }

    public Map<String, Simbolo> getSimbolosIR() {
        return Collections.unmodifiableMap(simbolosIR);
    }

    /** Código en forma de tres direcciones, una línea por cuádrupla. */
    public List<String> getTexto() {
        List<String> lineas = new ArrayList<String>();
        for (Cuadrupla c : cuadruplas) {
            boolean sinSangria = c.op == Op.ETIQUETA || c.op == Op.INICIO
                    || c.op == Op.FUNC_INICIO || c.op == Op.FUNC_FIN;
            lineas.add(sinSangria ? c.aTexto() : "    " + c.aTexto());
        }
        return lineas;
    }

    /** Tabla de cuádruplos: operación, arg1, arg2, resultado (cuatro columnas, sin índice). */
    public List<String> getTabla() {
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

    /** Imprime la tabla de cuádruplos; con tresDirecciones, agrega debajo la forma de texto (depuración). */
    public void imprimir(boolean tresDirecciones) {
        for (String linea : getTabla()) System.out.println(linea);
        if (tresDirecciones) {
            System.out.println("\n--- Forma de tres direcciones ---");
            for (String linea : getTexto()) System.out.println(linea);
        }
    }

    /** Guarda la tabla (y, si se pide, la forma de tres direcciones) en un .cuad junto al fuente (programa.leaf -> programa.cuad). */
    public File guardar(File fuente, boolean tresDirecciones) throws IOException {
        String nombre = fuente.getName();
        int punto = nombre.lastIndexOf('.');
        if (punto > 0) nombre = nombre.substring(0, punto);
        File carpeta = fuente.getAbsoluteFile().getParentFile();
        File salida = new File(carpeta, nombre + ".cuad");

        List<String> todo = new ArrayList<String>(getTabla());
        if (tresDirecciones) {
            todo.add("");
            todo.add("--- Forma de tres direcciones ---");
            todo.addAll(getTexto());
        }
        Files.write(salida.toPath(), todo, StandardCharsets.UTF_8);
        return salida;
    }

    // ================================================================
    //  UTILIDADES
    // ================================================================

    private void emitir(Op op, String arg1, String arg2, String resultado) {
        cuadruplas.add(new Cuadrupla(op, arg1, arg2, resultado));
    }

    private String nuevoTemporal(String tipo) {
        String t = "t" + (contTemporales++);
        tiposTemporales.put(t, tipo == null ? "?" : tipo);
        return t;
    }

    private String nuevaEtiqueta() {
        return "L" + (contEtiquetas++);
    }

    private void etiqueta(String nombre) {
        emitir(Op.ETIQUETA, null, null, nombre);
    }

    private String valor(Node n) {
        // jjtGetValue() existe en SimpleNode, no en la interfaz Node
        Object v = (n instanceof SimpleNode) ? ((SimpleNode) n).jjtGetValue() : null;
        return v == null ? "" : v.toString();
    }

    private static String quitarArreglo(String tipo, int niveles) {
        String t = tipo;
        for (int i = 0; i < niveles && t != null && t.startsWith("arreglo:"); i++) t = t.substring(8);
        return t;
    }

    private static boolean esDecimal(String t) { return "decimal".equals(t); }
    private static boolean esEntero(String t) { return "entero".equals(t); }

    private Node sinEnvolturas(Node n) {
        while (n instanceof ASTExpresion && n.jjtGetNumChildren() == 1) n = n.jjtGetChild(0);
        return n;
    }

    /** Nombre único (por declaración) de la variable que representa este nodo ASTVariable. */
    private String nombreVar(Node nodoVariable) {
        Simbolo s = semantico.simboloDe(nodoVariable);
        if (s == null) return valor(nodoVariable);          // no debería pasar tras un semántico limpio

        String existente = nombresIR.get(s);
        if (existente != null) return existente;

        String base = "Global".equals(s.ambito) ? s.nombre : s.nombre + "@" + s.ambito;
        String candidato = base;
        for (int k = 2; simbolosIR.containsKey(candidato); k++) candidato = base + "#" + k;

        nombresIR.put(s, candidato);
        simbolosIR.put(candidato, s);
        return candidato;
    }

    private String nombreFuncion(Node nodoId) {
        Simbolo s = semantico.simboloDe(nodoId);
        return s != null ? s.nombre : valor(nodoId);
    }

    private String tipoVar(Node nodoVariable) {
        Simbolo s = semantico.simboloDe(nodoVariable);
        return s != null ? s.tipo : "?";
    }

    private static boolean esNumeroEntero(String s) {
        return s.matches("-?[0-9]+");
    }

    /** Si el valor es entero y se necesita decimal, emite la conversión (o convierte la constante). */
    private String coercion(String lugar, String desde, String hasta) {
        if (esEntero(desde) && esDecimal(hasta)) {
            if (esNumeroEntero(lugar)) return lugar + ".0";
            String t = nuevoTemporal("decimal");
            emitir(Op.A_DECIMAL, lugar, null, t);
            return t;
        }
        return lugar;
    }

    // ----------------------------------------------------------------
    //  Tipo estático de una expresión (misma regla que el semántico)
    // ----------------------------------------------------------------

    private String tipoNodo(Node n) {
        if (n instanceof ASTExpresion) return tipoNodo(n.jjtGetChild(0));
        if (n instanceof ASTLiteral) {
            if (n.jjtGetNumChildren() > 0) return "arreglo";
            String v = valor(n);
            if (v.startsWith("\"")) return "texto";
            if (v.equals("verdadero") || v.equals("falso")) return "logico";
            if (v.contains(".")) return "decimal";
            return "entero";
        }
        if (n instanceof ASTVariable) return tipoVar(n);
        if (n instanceof ASTAritmetica) {
            String l = tipoNodo(n.jjtGetChild(0));
            String r = tipoNodo(n.jjtGetChild(2));
            String op = valor(n.jjtGetChild(1));
            if (esDecimal(l) || esDecimal(r) || "/".equals(op)) return "decimal";
            return "entero";
        }
        if (n instanceof ASTComparacion || n instanceof ASTLogica || n instanceof ASTNot) return "logico";
        if (n instanceof ASTNegacion) return tipoNodo(n.jjtGetChild(0));
        if (n instanceof ASTRaiz) return "decimal";
        if (n instanceof ASTLongitud) return "entero";
        if (n instanceof ASTAccesoArreglo) {
            return quitarArreglo(tipoVar(n.jjtGetChild(0)), n.jjtGetNumChildren() - 1);
        }
        if (n instanceof ASTLlamadaFuncion) {
            Simbolo f = semantico.simboloDe(n.jjtGetChild(0));
            return f != null && f.tipoRetorno != null ? f.tipoRetorno : "?";
        }
        return "?";   // ingresar() u otros: el tipo se conoce solo al ejecutar
    }

    // ================================================================
    //  SENTENCIAS
    // ================================================================

    private void sentencia(Node n) {
        if (n instanceof ASTDeclaracion) declaracion(n);
        else if (n instanceof ASTDeclConstante) declaracion(n);
        else if (n instanceof ASTAsignacion) asignacion(n);
        else if (n instanceof ASTCondicional) condicional(n);
        else if (n instanceof ASTMientras) mientras(n);
        else if (n instanceof ASTHacerMientras) hacerMientras(n);
        else if (n instanceof ASTRepetir) repetir(n);
        else if (n instanceof ASTPara) para(n);
        else if (n instanceof ASTSegun) segun(n);
        else if (n instanceof ASTMostrar) mostrar(n);
        else if (n instanceof ASTLeer) leer(n, true);
        else if (n instanceof ASTRetornar) retornar(n);
        else if (n instanceof ASTRomper) romper();
        else if (n instanceof ASTContinuar) continuar();
        else if (n instanceof ASTAgregar) agregar(n);
        else if (n instanceof ASTQuitar) quitar(n);
        else if (n instanceof ASTLlamadaFuncion) llamada(n, true);
        else if (n instanceof ASTBloque) bloque(n);
        else throw new IllegalStateException("instrucción no soportada por el generador: " + n.toString());
    }

    private void bloque(Node b) {
        for (int i = 0; i < b.jjtGetNumChildren(); i++) sentencia(b.jjtGetChild(i));
    }

    /** definir $x como T con valor de E.  /  constante $x como T con valor de L. */
    private void declaracion(Node n) {
        Node variable = n.jjtGetChild(0);
        String tipo = tipoVar(variable);
        String v = exprCon(n.jjtGetChild(2), tipo);
        emitir(Op.ASIGNA, v, null, nombreVar(variable));
    }

    private void asignacion(Node n) {
        Node destino = n.jjtGetChild(0);
        if (destino instanceof ASTAccesoArreglo) {
            String[] d = destinoArreglo(destino);
            String tipoElem = quitarArreglo(tipoVar(destino.jjtGetChild(0)), destino.jjtGetNumChildren() - 1);
            String v = exprCon(n.jjtGetChild(1), tipoElem);
            emitir(Op.ARR_ESCRIBE, v, d[1], d[0]);
        } else {
            String v = exprCon(n.jjtGetChild(1), tipoVar(destino));
            emitir(Op.ASIGNA, v, null, nombreVar(destino));
        }
    }

    private void mostrar(Node n) {
        int total = n.jjtGetNumChildren();
        if (total == 0) {
            emitir(Op.MUESTRA, "\"\"", Cuadrupla.FIN_MOSTRAR, null);
            return;
        }
        // Primero se evalúan todos los argumentos (llamadas, cálculos) y luego se imprime
        List<String> valores = new ArrayList<String>();
        for (int i = 0; i < total; i++) valores.add(expr(n.jjtGetChild(i)));
        for (int i = 0; i < total; i++) {
            emitir(Op.MUESTRA, valores.get(i), i == total - 1 ? Cuadrupla.FIN_MOSTRAR : null, null);
        }
    }

    private void retornar(Node n) {
        if (n.jjtGetNumChildren() == 0) {
            emitir(Op.RETORNA, null, null, null);
        } else {
            String v = exprCon(n.jjtGetChild(0), tipoRetornoActual);
            emitir(Op.RETORNA, v, null, null);
        }
    }

    private void romper() {
        if (pilaBreak.isEmpty()) throw new IllegalStateException("\"romper\" fuera de un ciclo o de un \"segun\".");
        emitir(Op.SALTA, null, null, pilaBreak.peek());
    }

    private void continuar() {
        if (pilaContinue.isEmpty()) throw new IllegalStateException("\"continuar\" fuera de un ciclo.");
        emitir(Op.SALTA, null, null, pilaContinue.peek());
    }

    /** agregar E en $v [i]* . */
    private void agregar(Node n) {
        int indices = n.jjtGetNumChildren() - 2;
        Node nodoBase = n.jjtGetChild(1);
        String tipoElem = quitarArreglo(tipoVar(nodoBase), indices + 1);
        String v = exprCon(n.jjtGetChild(0), tipoElem);

        String base = nombreVar(nodoBase);
        for (int i = 2; i < n.jjtGetNumChildren(); i++) {
            String idx = expr(n.jjtGetChild(i));
            String t = nuevoTemporal(quitarArreglo(tipoVar(nodoBase), i - 1));
            emitir(Op.ARR_LEE, base, idx, t);
            base = t;
        }
        emitir(Op.AGREGAR, base, v, null);
    }

    /** quitar indice [i] [j]* de $v . */
    private void quitar(Node n) {
        int total = n.jjtGetNumChildren();
        Node nodoBase = n.jjtGetChild(total - 1);
        String base = nombreVar(nodoBase);
        for (int i = 0; i < total - 2; i++) {
            String idx = expr(n.jjtGetChild(i));
            String t = nuevoTemporal(quitarArreglo(tipoVar(nodoBase), i + 1));
            emitir(Op.ARR_LEE, base, idx, t);
            base = t;
        }
        String ultimo = expr(n.jjtGetChild(total - 2));
        emitir(Op.QUITAR, base, ultimo, null);
    }

    // ================================================================
    //  CONTROL DE FLUJO
    // ================================================================

    /* Hijos de Condicional: cond, Bloque, (cond, Bloque)*, [Bloque del sino] */
    private void condicional(Node n) {
        int total = n.jjtGetNumChildren();
        String fin = (total > 2) ? nuevaEtiqueta() : null;

        int i = 0;
        while (i < total) {
            Node actual = n.jjtGetChild(i);

            if (actual instanceof ASTBloque) {            // bloque del "sino"
                bloque(actual);
                i++;
            } else {                                      // cond + bloque
                String c = expr(actual);
                String siguiente = nuevaEtiqueta();
                emitir(Op.SALTA_FALSO, c, null, siguiente);
                bloque(n.jjtGetChild(i + 1));
                boolean hayMas = (i + 2) < total;
                if (hayMas) emitir(Op.SALTA, null, null, fin);
                etiqueta(siguiente);
                i += 2;
            }
        }
        if (fin != null) etiqueta(fin);
    }

    private void mientras(Node n) {
        String inicio = nuevaEtiqueta();
        String fin = nuevaEtiqueta();

        etiqueta(inicio);
        String c = expr(n.jjtGetChild(0));
        emitir(Op.SALTA_FALSO, c, null, fin);

        pilaBreak.push(fin);
        pilaContinue.push(inicio);
        bloque(n.jjtGetChild(1));
        pilaBreak.pop();
        pilaContinue.pop();

        emitir(Op.SALTA, null, null, inicio);
        etiqueta(fin);
    }

    /** hacer > ... < mientras (cond). */
    private void hacerMientras(Node n) {
        String inicio = nuevaEtiqueta();
        String cont = nuevaEtiqueta();
        String fin = nuevaEtiqueta();

        etiqueta(inicio);
        pilaBreak.push(fin);
        pilaContinue.push(cont);
        bloque(n.jjtGetChild(0));
        pilaBreak.pop();
        pilaContinue.pop();

        etiqueta(cont);
        String c = expr(n.jjtGetChild(1));
        emitir(Op.SALTA_VERD, c, null, inicio);
        etiqueta(fin);
    }

    /** repetir E veces > ... < */
    private void repetir(Node n) {
        String inicio = nuevaEtiqueta();
        String cont = nuevaEtiqueta();
        String fin = nuevaEtiqueta();

        String limite = expr(n.jjtGetChild(0));
        String contador = nuevoTemporal("entero");
        emitir(Op.ASIGNA, "0", null, contador);

        etiqueta(inicio);
        String c = nuevoTemporal("logico");
        emitir(Op.MENOR, contador, limite, c);
        emitir(Op.SALTA_FALSO, c, null, fin);

        pilaBreak.push(fin);
        pilaContinue.push(cont);
        bloque(n.jjtGetChild(1));
        pilaBreak.pop();
        pilaContinue.pop();

        etiqueta(cont);
        emitir(Op.SUMA, contador, "1", contador);
        emitir(Op.SALTA, null, null, inicio);
        etiqueta(fin);
    }

    /*
     * para $i de A hasta B [paso S] > ... <
     * Hijos: Variable, Expresion(A), Expresion(B), [Expresion(S)], Bloque
     */
    private void para(Node n) {
        int total = n.jjtGetNumChildren();
        Node nodoVar = n.jjtGetChild(0);
        String variable = nombreVar(nodoVar);

        String desde = expr(n.jjtGetChild(1));
        emitir(Op.ASIGNA, desde, null, variable);
        String tipoHasta = tipoNodo(n.jjtGetChild(2));
        String hasta = expr(n.jjtGetChild(2));

        // Paso: constante conocida (sube / baja) o dinámico (se decide al ejecutar)
        String paso = "1";
        int sentido = +1;           // +1 sube, -1 baja, 0 = dinámico
        String tipoPaso = "entero";
        if (total == 5) {
            Node nodoPaso = n.jjtGetChild(3);
            Node base = sinEnvolturas(nodoPaso);
            tipoPaso = tipoNodo(nodoPaso);
            if (base instanceof ASTNegacion && sinEnvolturas(base.jjtGetChild(0)) instanceof ASTLiteral) sentido = -1;
            else if (base instanceof ASTLiteral) sentido = +1;
            else sentido = 0;
            paso = expr(nodoPaso);
        }
        Node cuerpo = n.jjtGetChild(total - 1);

        String inicio = nuevaEtiqueta();
        String cont = nuevaEtiqueta();
        String fin = nuevaEtiqueta();

        etiqueta(inicio);
        String c;
        if (sentido != 0) {
            c = comparar(sentido > 0 ? Op.MENOR_IGUAL : Op.MAYOR_IGUAL, variable, "entero", hasta, tipoHasta);
        } else {
            String esPositivo = comparar(Op.MAYOR_IGUAL, paso, tipoPaso, "0", "entero");
            String lBaja = nuevaEtiqueta();
            String lVer = nuevaEtiqueta();
            c = nuevoTemporal("logico");
            emitir(Op.SALTA_FALSO, esPositivo, null, lBaja);
            String sube = comparar(Op.MENOR_IGUAL, variable, "entero", hasta, tipoHasta);
            emitir(Op.ASIGNA, sube, null, c);
            emitir(Op.SALTA, null, null, lVer);
            etiqueta(lBaja);
            String baja = comparar(Op.MAYOR_IGUAL, variable, "entero", hasta, tipoHasta);
            emitir(Op.ASIGNA, baja, null, c);
            etiqueta(lVer);
        }
        emitir(Op.SALTA_FALSO, c, null, fin);

        pilaBreak.push(fin);
        pilaContinue.push(cont);
        bloque(cuerpo);
        pilaBreak.pop();
        pilaContinue.pop();

        etiqueta(cont);
        emitir(Op.SUMA, variable, paso, variable);
        emitir(Op.SALTA, null, null, inicio);
        etiqueta(fin);
    }

    /*
     * segun ($x) > caso L -> ... caso L -> ... defecto -> ... <
     * Hijos de Segun: Variable, Caso*, [Defecto]
     * Hijos de Caso: Literal, sentencias...      Hijos de Defecto: sentencias...
     */
    private void segun(Node n) {
        int total = n.jjtGetNumChildren();
        Node nodoSelector = n.jjtGetChild(0);
        String selector = nombreVar(nodoSelector);
        String tipoSelector = tipoVar(nodoSelector);

        List<Node> casos = new ArrayList<Node>();
        Node nodoDefecto = null;
        for (int i = 1; i < total; i++) {
            Node hijo = n.jjtGetChild(i);
            if (hijo instanceof ASTCaso) casos.add(hijo);
            else if (hijo instanceof ASTDefecto) nodoDefecto = hijo;
        }
        boolean hayDefecto = nodoDefecto != null;

        String fin = nuevaEtiqueta();
        String defecto = hayDefecto ? nuevaEtiqueta() : fin;

        // 1) Pruebas
        List<String> etiquetasCaso = new ArrayList<String>();
        for (Node caso : casos) {
            String etq = nuevaEtiqueta();
            etiquetasCaso.add(etq);
            Node lit = caso.jjtGetChild(0);
            String valorCaso = expr(lit);
            String c = comparar(Op.IGUAL, selector, tipoSelector, valorCaso, tipoNodo(lit));
            emitir(Op.SALTA_VERD, c, null, etq);
        }
        emitir(Op.SALTA, null, null, defecto);

        // 2) Cuerpos (sin caída al siguiente caso)
        pilaBreak.push(fin);
        for (int k = 0; k < casos.size(); k++) {
            Node caso = casos.get(k);
            etiqueta(etiquetasCaso.get(k));
            for (int j = 1; j < caso.jjtGetNumChildren(); j++) sentencia(caso.jjtGetChild(j));
            Cuadrupla ultima = cuadruplas.get(cuadruplas.size() - 1);
            if (!(ultima.op == Op.SALTA && fin.equals(ultima.resultado))) emitir(Op.SALTA, null, null, fin);
        }
        if (hayDefecto) {
            etiqueta(defecto);
            for (int j = 0; j < nodoDefecto.jjtGetNumChildren(); j++) sentencia(nodoDefecto.jjtGetChild(j));
        }
        pilaBreak.pop();

        etiqueta(fin);
    }

    // ================================================================
    //  FUNCIONES
    // ================================================================

    /* Hijos de DefFuncion: Id, Parametro*, Tipo, Bloque */
    private void funcion(Node f) {
        Simbolo simFunc = semantico.simboloDe(f.jjtGetChild(0));
        String nombre = nombreFuncion(f.jjtGetChild(0));
        tipoRetornoActual = simFunc != null ? simFunc.tipoRetorno : null;

        emitir(Op.FUNC_INICIO, null, null, nombre);

        int total = f.jjtGetNumChildren();
        for (int i = 1; i < total; i++) {
            Node h = f.jjtGetChild(i);
            if (h instanceof ASTParametro) emitir(Op.RECIBE, null, null, nombreVar(h.jjtGetChild(0)));
        }

        bloque(f.jjtGetChild(total - 1));

        Cuadrupla ultima = cuadruplas.get(cuadruplas.size() - 1);
        if (ultima.op != Op.RETORNA) emitir(Op.RETORNA, null, null, null);
        emitir(Op.FUNC_FIN, null, null, nombre);
        tipoRetornoActual = null;
    }

    /** Devuelve el temporal con el resultado (o null si es sentencia). */
    private String llamada(Node n, boolean comoSentencia) {
        Simbolo f = semantico.simboloDe(n.jjtGetChild(0));
        String nombre = nombreFuncion(n.jjtGetChild(0));
        int nargs = n.jjtGetNumChildren() - 1;

        // Se evalúan todos los argumentos antes de emitir los PARAM (los anidados no se mezclan)
        List<String> args = new ArrayList<String>();
        for (int i = 1; i <= nargs; i++) {
            String tipoParam = (f != null && i - 1 < f.tiposParametros.size()) ? f.tiposParametros.get(i - 1) : null;
            args.add(exprCon(n.jjtGetChild(i), tipoParam));
        }
        for (String a : args) emitir(Op.PARAM, a, null, null);

        if (comoSentencia) {
            emitir(Op.LLAMA, nombre, String.valueOf(nargs), null);
            return null;
        }
        String t = nuevoTemporal(f != null && f.tipoRetorno != null ? f.tipoRetorno : "?");
        emitir(Op.LLAMA, nombre, String.valueOf(nargs), t);
        return t;
    }

    // ================================================================
    //  EXPRESIONES  (devuelven el "lugar" donde queda el resultado:
    //  un literal, una variable o un temporal)
    // ================================================================

    /** Evalúa la expresión y, si hace falta, la convierte al tipo esperado (entero -> decimal). */
    private String exprCon(Node n, String tipoEsperado) {
        Node base = sinEnvolturas(n);
        if (base instanceof ASTLiteral && base.jjtGetNumChildren() > 0) {
            return literalArreglo(base.jjtGetChild(0), tipoEsperado);
        }
        String lugar = expr(n);
        // a / b (enteros) guardado en un entero: el cociente decimal se trunca hacia cero
        if ("entero".equals(tipoEsperado) && semantico.esDivisionEnteraSimple(n)) {
            String t = nuevoTemporal("entero");
            emitir(Op.A_ENTERO, lugar, null, t);
            return t;
        }
        return tipoEsperado == null ? lugar : coercion(lugar, tipoNodo(n), tipoEsperado);
    }

    private String expr(Node n) {
        if (n instanceof ASTExpresion) {
            return expr(n.jjtGetChild(0));
        }
        if (n instanceof ASTLiteral) {
            if (n.jjtGetNumChildren() > 0) return literalArreglo(n.jjtGetChild(0), null);
            return valor(n);
        }
        if (n instanceof ASTVariable) {
            return nombreVar(n);
        }
        if (n instanceof ASTAritmetica) {
            return aritmetica(n);
        }
        if (n instanceof ASTComparacion) {
            Node izq = n.jjtGetChild(0), der = n.jjtGetChild(2);
            String li = expr(izq);
            String ld = expr(der);
            return comparar(opRelacional(valor(n.jjtGetChild(1))), li, tipoNodo(izq), ld, tipoNodo(der));
        }
        if (n instanceof ASTLogica) {
            return logica(n);
        }
        if (n instanceof ASTNot) {
            String v = expr(n.jjtGetChild(0));
            String t = nuevoTemporal("logico");
            emitir(Op.NO, v, null, t);
            return t;
        }
        if (n instanceof ASTNegacion) {
            String v = expr(n.jjtGetChild(0));
            String t = nuevoTemporal(tipoNodo(n));
            emitir(Op.NEG, v, null, t);
            return t;
        }
        if (n instanceof ASTRaiz) {
            String v = expr(n.jjtGetChild(0));
            String t = nuevoTemporal("decimal");
            emitir(Op.RAIZ, v, null, t);
            return t;
        }
        if (n instanceof ASTLongitud) {
            String v = nombreVar(n.jjtGetChild(0));
            String t = nuevoTemporal("entero");
            emitir(Op.LONGITUD, v, null, t);
            return t;
        }
        if (n instanceof ASTAccesoArreglo) {
            String[] d = destinoArreglo(n);
            String t = nuevoTemporal(tipoNodo(n));
            emitir(Op.ARR_LEE, d[0], d[1], t);
            return t;
        }
        if (n instanceof ASTLiteralArreglo) {
            return literalArreglo(n, null);
        }
        if (n instanceof ASTLlamadaFuncion) {
            return llamada(n, false);
        }
        if (n instanceof ASTLeer) {
            return leer(n, false);
        }
        throw new IllegalStateException("expresión no soportada por el generador: " + n.toString());
    }

    private String aritmetica(Node n) {
        Node nIzq = n.jjtGetChild(0), nDer = n.jjtGetChild(2);
        String op = valor(n.jjtGetChild(1));
        String tIzq = tipoNodo(nIzq), tDer = tipoNodo(nDer);
        String tRes = tipoNodo(n);

        String izq = expr(nIzq);
        String der = expr(nDer);
        if (esDecimal(tRes)) {                       // decimal o división: ambos operandos como decimal
            izq = coercion(izq, tIzq, "decimal");
            der = coercion(der, tDer, "decimal");
        }
        String t = nuevoTemporal(tRes);
        emitir(opAritmetico(op), izq, der, t);
        return t;
    }

    private Op opAritmetico(String op) {
        if (op.equals("+")) return Op.SUMA;
        if (op.equals("-")) return Op.RESTA;
        if (op.equals("*")) return Op.MULT;
        if (op.equals("/")) return Op.DIV;
        if (op.equals("%")) return Op.MOD;
        if (op.equals("^")) return Op.POT;
        if (op.equals("-/")) return Op.RAIZ_N;
        throw new IllegalStateException("operador aritmético desconocido: " + op);
    }

    private Op opRelacional(String op) {
        if (op.equals("igual que")) return Op.IGUAL;
        if (op.equals("diferente que")) return Op.DIFERENTE;
        if (op.equals("mayor que")) return Op.MAYOR;
        if (op.equals("menor que")) return Op.MENOR;
        if (op.equals("mayor o igual que")) return Op.MAYOR_IGUAL;
        if (op.equals("menor o igual que")) return Op.MENOR_IGUAL;
        throw new IllegalStateException("operador relacional desconocido: " + op);
    }

    /** Emite la comparación (convirtiendo el entero a decimal si el otro lado es decimal). */
    private String comparar(Op op, String izq, String tIzq, String der, String tDer) {
        if (esEntero(tIzq) && esDecimal(tDer)) izq = coercion(izq, tIzq, "decimal");
        else if (esDecimal(tIzq) && esEntero(tDer)) der = coercion(der, tDer, "decimal");
        String t = nuevoTemporal("logico");
        emitir(op, izq, der, t);
        return t;
    }

    /**
     * a Y b / a O b con cortocircuito:
     *     t = a ; ifFalse t goto Lfin   (para O: if t goto Lfin) ; t = b ; Lfin:
     * La precedencia (Y antes que O) ya viene del árbol.
     */
    private String logica(Node n) {
        boolean esY = valor(n.jjtGetChild(1)).equals("Y");
        String resultado = nuevoTemporal("logico");
        String fin = nuevaEtiqueta();

        String izq = expr(n.jjtGetChild(0));
        emitir(Op.ASIGNA, izq, null, resultado);
        emitir(esY ? Op.SALTA_FALSO : Op.SALTA_VERD, resultado, null, fin);

        String der = expr(n.jjtGetChild(2));
        emitir(Op.ASIGNA, der, null, resultado);
        etiqueta(fin);
        return resultado;
    }

    /** ingresar() / ingresar("mensaje") */
    private String leer(Node n, boolean comoSentencia) {
        String mensaje = null;
        if (n.jjtGetNumChildren() > 0) mensaje = valor(n.jjtGetChild(0));
        if (comoSentencia) {
            emitir(Op.LEE, mensaje, null, null);
            return null;
        }
        String t = nuevoTemporal("?");
        emitir(Op.LEE, mensaje, null, t);
        return t;
    }

    /** [a, b, c]  ->  t = newarray 3 ; t[0] = a ; ... (tipoArreglo = tipo esperado del arreglo, si se conoce) */
    private String literalArreglo(Node n, String tipoArreglo) {
        int tam = n.jjtGetNumChildren();
        String tipoElem = (tipoArreglo != null && tipoArreglo.startsWith("arreglo:")) ? tipoArreglo.substring(8) : null;
        String t = nuevoTemporal(tipoArreglo != null ? tipoArreglo : "arreglo");
        emitir(Op.NUEVO_ARR, String.valueOf(tam), null, t);
        for (int i = 0; i < tam; i++) {
            String v = exprCon(n.jjtGetChild(i), tipoElem);
            emitir(Op.ARR_ESCRIBE, v, String.valueOf(i), t);
        }
        return t;
    }

    /**
     * Para $a[i][j]: emite t = $a[i] y devuelve {"t", "j"}.
     * El último índice no se carga, para poder usarlo tanto de lectura como de escritura.
     */
    private String[] destinoArreglo(Node n) {
        int total = n.jjtGetNumChildren();
        Node nodoBase = n.jjtGetChild(0);
        String base = nombreVar(nodoBase);
        for (int i = 1; i < total - 1; i++) {
            String idx = expr(n.jjtGetChild(i));
            String t = nuevoTemporal(quitarArreglo(tipoVar(nodoBase), i));
            emitir(Op.ARR_LEE, base, idx, t);
            base = t;
        }
        String ultimo = expr(n.jjtGetChild(total - 1));
        return new String[] { base, ultimo };
    }
}
