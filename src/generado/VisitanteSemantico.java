package generado;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Stack;

public class VisitanteSemantico extends LeafDefaultVisitor {

    TablaSimbolos tabla = new TablaSimbolos();
    Stack<String> retornoEsperado = new Stack<>();

    // [CÓDIGO INTERMEDIO] Resolución nodo -> símbolo. La llena este visitante mientras recorre
    // el árbol y la consume GeneradorCodigoIntermedio para saber a qué declaración se refiere
    // cada uso de una variable/función (misma variable en distintos ámbitos = símbolos distintos).
    private final Map<Node, Simbolo> simbolosPorNodo = new IdentityHashMap<>();

    // Contadores para validar "romper" / "continuar": cuántos ciclos y cuántos "segun" rodean al nodo actual.
    private int profundidadCiclo = 0;
    private int profundidadSegun = 0;

    public Simbolo simboloDe(Node nodo) {
        return simbolosPorNodo.get(nodo);
    }

    public VisitanteSemantico() {
        // La tabla ya crea su ámbito "Global" automáticamente
    }

    // Tipo de lo que solo se conoce al ejecutar (ingresar()); se acepta donde se pida cualquier tipo.
    static final String TIPO_DINAMICO = "dinamico";

    private static boolean esNumerico(String t) { return t.equals("entero") || t.equals("decimal"); }

    // MÉTODO: Valida la coerción implícita de entero a decimal
    private boolean sonTiposCompatibles(String esperado, String recibido) {
        if (esperado.equals(recibido)) return true;
        if (recibido.equals(TIPO_DINAMICO)) return true;
        if (esperado.equals("decimal") && recibido.equals("entero")) return true;
        return false;
    }

    private String extraerTipo(SimpleNode nodo) {
        String val = (String) nodo.jjtGetValue();
        return (val != null) ? val : "arreglo";
    }

    private String extraerTipoProfundo(SimpleNode nodoTipo) {
        if (nodoTipo instanceof ASTTipoArreglo) {
            SimpleNode interior = (SimpleNode) nodoTipo.jjtGetChild(0);
            return "arreglo:" + extraerTipoProfundo(interior);
        } else if (nodoTipo instanceof ASTTipo && nodoTipo.jjtGetNumChildren() > 0) {
            return extraerTipoProfundo((SimpleNode) nodoTipo.jjtGetChild(0));
        }
        return (String) nodoTipo.jjtGetValue();
    }

    private void reportar(SimpleNode nodo, String mensaje) {
        Token t = nodo.jjtGetFirstToken();
        Leaf.totalErrores++;
        String pos = (t != null) ? "Línea " + t.beginLine + ", Columna " + t.beginColumn : "Línea ?";
        Leaf.errores.add("Tipo de Error: Semántico " + pos + " : " + mensaje);
    }

    @Override
    public Object visit(ASTBloque node, Object data) {
        tabla.entrarBloque("Bloque");
        Object res = super.visit(node, data);
        tabla.salirBloque();
        return res;
    }

    /** Recorre el cuerpo de una función o de un para sin abrir un scope propio: comparte el del encabezado. */
    private void visitarCuerpoSinScope(Node cuerpo, Object data) {
        if (cuerpo instanceof ASTBloque) super.visit((ASTBloque) cuerpo, data);
        else cuerpo.jjtAccept(this, data);
    }

    @Override
    public Object visit(ASTDefFuncion node, Object data) {
        SimpleNode nodoId = (SimpleNode) node.jjtGetChild(0);
        String nombreFun = (String) nodoId.jjtGetValue();
        int linea = nodoId.jjtGetFirstToken() != null ? nodoId.jjtGetFirstToken().beginLine : 0;

        int numHijos = node.jjtGetNumChildren();
        SimpleNode nodoRetorno = (SimpleNode) node.jjtGetChild(numHijos - 2);

        String tipoRetorno = (nodoRetorno instanceof ASTTipoArreglo || (nodoRetorno instanceof ASTTipo && nodoRetorno.jjtGetNumChildren() > 0))
                ? extraerTipoProfundo(nodoRetorno) : extraerTipo(nodoRetorno);

        Simbolo simFunc = tabla.insertar(nombreFun, tipoRetorno, "funcion", linea, 0);

        if (simFunc == null) {
            Simbolo existente = tabla.buscarLocal(nombreFun);
            reportar(nodoId, "La función '" + nombreFun + "' ya existe. Fue declarada previamente como " + existente.categoria + ".");
            return super.visit(node, data);
        }

        simFunc.tipoRetorno = tipoRetorno;
        simbolosPorNodo.put(nodoId, simFunc); // [CÓDIGO INTERMEDIO]

        tabla.entrarFuncion(nombreFun);
        retornoEsperado.push(tipoRetorno);

        for (int i = 1; i < numHijos - 2; i++) {
            SimpleNode param = (SimpleNode) node.jjtGetChild(i);
            if (param instanceof ASTParametro) {
                SimpleNode nodoTipoParam = (SimpleNode) param.jjtGetChild(1);
                String tipoParam = (nodoTipoParam instanceof ASTTipoArreglo || (nodoTipoParam instanceof ASTTipo && nodoTipoParam.jjtGetNumChildren() > 0))
                        ? extraerTipoProfundo(nodoTipoParam) : extraerTipo(nodoTipoParam);
                simFunc.tiposParametros.add(tipoParam);
                param.jjtAccept(this, data);
            }
        }

        visitarCuerpoSinScope(node.jjtGetChild(numHijos - 1), data);

        retornoEsperado.pop();
        tabla.salirBloque();

        return null;
    }

    @Override
    public Object visit(ASTRetornar node, Object data) {
        String tipoDevuelto = "vacio";
        if (node.jjtGetNumChildren() > 0) {
            tipoDevuelto = (String) node.jjtGetChild(0).jjtAccept(this, data);
        }

        String tipoRequerido = retornoEsperado.isEmpty() ? "vacio" : retornoEsperado.peek();

        // INTEGRACIÓN: Coerción en retornos de funciones
        if (!sonTiposCompatibles(tipoRequerido, tipoDevuelto) && !tipoDevuelto.equals("error")) {
            reportar(node, "La función promete entregar '" + tipoRequerido + "' pero está intentando retornar '" + tipoDevuelto + "'.");
        }
        return super.visit(node, data);
    }

    @Override
    public Object visit(ASTDeclaracion node, Object data) {
        return procesarDeclaracion(node, "variable", data);
    }

    @Override
    public Object visit(ASTDeclConstante node, Object data) {
        return procesarDeclaracion(node, "constante", data);
    }

    private Object procesarDeclaracion(SimpleNode node, String categoria, Object data) {
        SimpleNode nodoVar = (SimpleNode) node.jjtGetChild(0);
        SimpleNode nodoTipo = (SimpleNode) node.jjtGetChild(1);
        SimpleNode nodoExpresion = (SimpleNode) node.jjtGetChild(2);

        String nombreVar = (String) nodoVar.jjtGetValue();
        String tipoDeclarado = (nodoTipo instanceof ASTTipoArreglo || (nodoTipo instanceof ASTTipo && nodoTipo.jjtGetNumChildren() > 0))
                ? extraerTipoProfundo(nodoTipo) : extraerTipo(nodoTipo);

        int linea = nodoVar.jjtGetFirstToken() != null ? nodoVar.jjtGetFirstToken().beginLine : 0;

        int dimensiones = 0;
        String t = tipoDeclarado;
        while (t != null && t.startsWith("arreglo:")) {
            dimensiones++;
            t = t.substring(8);
        }

        if ("vacio".equals(tipoDeclarado)) {
            reportar(nodoTipo, "El tipo 'vacio' es exclusivo para funciones, una " + categoria + " no puede ser vacía.");
            return null;
        }

        Simbolo insertado = tabla.insertar(nombreVar, tipoDeclarado, categoria, linea, dimensiones);

        if (insertado == null) {
            Simbolo existente = tabla.buscarLocal(nombreVar);
            reportar(nodoVar, "El identificador '" + nombreVar + "' ya fue declarado previamente en este ámbito como " + existente.categoria + ".");
        } else {
            simbolosPorNodo.put(nodoVar, insertado); // [CÓDIGO INTERMEDIO]
        }

        String tipoValor = (String) nodoExpresion.jjtAccept(this, data);

        // INTEGRACIÓN: Coerción en inicialización de variables/constantes
        if (tipoValor != null && !tipoValor.equals("error") && !sonTiposCompatibles(tipoDeclarado, tipoValor) && !tipoValor.startsWith("arreglo")) {
            reportar(nodoExpresion, "Incompatibilidad de tipos: Intentas guardar un '" + tipoValor + "' en una variable definida como '" + tipoDeclarado + "'.");
        }

        if (categoria.equals("constante") && insertado != null) {
            if (nodoExpresion instanceof ASTLiteral) {
                insertado.valorConstante = nodoExpresion.jjtGetValue();
            }
        }

        return null;
    }

    @Override
    public Object visit(ASTAsignacion node, Object data) {
        SimpleNode nodoIdentificador = (SimpleNode) node.jjtGetChild(0);
        String tipoVar = (String) nodoIdentificador.jjtAccept(this, data);

        String nombreVar;
        boolean esAccesoArreglo = nodoIdentificador instanceof ASTAccesoArreglo;

        if (esAccesoArreglo) {
            nombreVar = (String) ((SimpleNode) nodoIdentificador.jjtGetChild(0)).jjtGetValue();
        } else {
            nombreVar = (String) nodoIdentificador.jjtGetValue();
        }

        Simbolo simbolo = tabla.buscar(nombreVar);

        if (simbolo != null && simbolo.categoria.equals("constante")) {
            reportar(nodoIdentificador, "La constante '" + nombreVar + "' es inmutable y no puede ser reasignada.");
        }

        String tipoNuevoValor = (String) node.jjtGetChild(1).jjtAccept(this, data);

        if (simbolo != null && tipoNuevoValor != null && !tipoNuevoValor.equals("error")) {
            if (esAccesoArreglo) {
                if (simbolo.tipo != null && simbolo.tipo.startsWith("arreglo:")) {
                    int numIndices = nodoIdentificador.jjtGetNumChildren() - 1;
                    String tipoEsperado = simbolo.tipo;
                    for (int i = 0; i < numIndices && tipoEsperado.startsWith("arreglo:"); i++) {
                        tipoEsperado = tipoEsperado.substring(8);
                    }
                    // INTEGRACIÓN: Coerción en asignación a arreglos
                    if (!sonTiposCompatibles(tipoEsperado, tipoNuevoValor) && !tipoNuevoValor.startsWith("arreglo")) {
                        reportar(node, "Choque de tipos: No puedes asignar un '" + tipoNuevoValor
                                + "' al elemento de '" + nombreVar + "' que es de tipo '" + tipoEsperado + "'.");
                    }
                }
            } else {
                // INTEGRACIÓN: Coerción en asignaciones normales
                if (!sonTiposCompatibles(simbolo.tipo, tipoNuevoValor) && !tipoNuevoValor.startsWith("arreglo")) {
                    reportar(node, "Choque de tipos: No puedes asignar un '" + tipoNuevoValor + "' a la variable '" + nombreVar + "' que es de tipo '" + simbolo.tipo + "'.");
                }
            }
        }
        return null;
    }

    @Override
    public Object visit(ASTPara node, Object data) {
        tabla.entrarBloque("Para");

        SimpleNode nodoVar = (SimpleNode) node.jjtGetChild(0);
        String nombreVar = (String) nodoVar.jjtGetValue();
        int linea = nodoVar.jjtGetFirstToken() != null ? nodoVar.jjtGetFirstToken().beginLine : 0;

        Simbolo simVarPara = tabla.insertar(nombreVar, "entero", "variable", linea, 0);
        if (simVarPara != null) simbolosPorNodo.put(nodoVar, simVarPara); // [CÓDIGO INTERMEDIO]

        int numHijos = node.jjtGetNumChildren();
        profundidadCiclo++;
        for (int i = 1; i < numHijos; i++) {
            Node hijo = node.jjtGetChild(i);
            if (hijo instanceof ASTBloque) visitarCuerpoSinScope(hijo, data);
            else hijo.jjtAccept(this, data);
        }
        profundidadCiclo--;

        tabla.salirBloque();
        return null;
    }

    @Override
    public Object visit(ASTParametro node, Object data) {
        SimpleNode nodoVar = (SimpleNode) node.jjtGetChild(0);
        SimpleNode nodoTipo = (SimpleNode) node.jjtGetChild(1);
        String nombreVar = (String) nodoVar.jjtGetValue();

        String tipoParam = (nodoTipo instanceof ASTTipoArreglo || (nodoTipo instanceof ASTTipo && nodoTipo.jjtGetNumChildren() > 0))
                ? extraerTipoProfundo(nodoTipo) : extraerTipo(nodoTipo);

        int linea = nodoVar.jjtGetFirstToken() != null ? nodoVar.jjtGetFirstToken().beginLine : 0;

        int dimensiones = 0;
        String tParam = tipoParam;
        while (tParam != null && tParam.startsWith("arreglo:")) {
            dimensiones++;
            tParam = tParam.substring(8);
        }

        if ("vacio".equals(tipoParam)) {
            reportar(nodoTipo, "No se pueden usar parámetros de tipo vacio.");
            return null;
        }

        Simbolo insertado = tabla.insertar(nombreVar, tipoParam, "parametro", linea, dimensiones);

        if (insertado == null) {
            Simbolo existente = tabla.buscarLocal(nombreVar);
            reportar(nodoVar, "El parámetro '" + nombreVar + "' ya fue declarado previamente en este ámbito como " + existente.categoria + ".");
        } else {
            simbolosPorNodo.put(nodoVar, insertado); // [CÓDIGO INTERMEDIO]
        }
        return null;
    }

    @Override
    public Object visit(ASTVariable node, Object data) {
        String nombreVar = (String) node.jjtGetValue();
        Simbolo simbolo = tabla.buscar(nombreVar);

        if (simbolo == null) {
            reportar(node, "Uso de variable fantasma: '" + nombreVar + "' no ha sido declarada.");
            return "error";
        }
        simbolosPorNodo.put(node, simbolo); // [CÓDIGO INTERMEDIO]
        return simbolo.tipo;
    }

    @Override
    public Object visit(ASTLiteral node, Object data) {
        String val = (String) node.jjtGetValue();
        if (val == null) {
            // [CÓDIGO INTERMEDIO] Literal de arreglo: se visitan sus elementos para validarlos y resolverlos.
            node.childrenAccept(this, data);
            return "arreglo";
        }
        if (val.startsWith("\"")) return "texto";
        if (val.equals("verdadero") || val.equals("falso")) return "logico";
        if (val.contains(".")) return "decimal";
        return "entero";
    }

    @Override
    public Object visit(ASTAccesoArreglo node, Object data) {
        SimpleNode nodoVar = (SimpleNode) node.jjtGetChild(0);
        String nombreVar = (String) nodoVar.jjtGetValue();
        Simbolo simbolo = tabla.buscar(nombreVar);

        if (simbolo == null || simbolo.tipo == null) return "error";
        simbolosPorNodo.put(nodoVar, simbolo); // [CÓDIGO INTERMEDIO]

        // [CÓDIGO INTERMEDIO] Se visitan las expresiones de los índices (antes no se revisaban).
        for (int i = 1; i < node.jjtGetNumChildren(); i++) {
            node.jjtGetChild(i).jjtAccept(this, data);
        }

        int numIndices = node.jjtGetNumChildren() - 1;
        String tipoResultado = simbolo.tipo;
        for (int i = 0; i < numIndices && tipoResultado.startsWith("arreglo:"); i++) {
            tipoResultado = tipoResultado.substring(8);
        }

        return tipoResultado;
    }

    @Override
    public Object visit(ASTLongitud node, Object data) {
        // [CÓDIGO INTERMEDIO] Se visita la variable (valida que exista y la resuelve).
        node.jjtGetChild(0).jjtAccept(this, data);
        return "entero";
    }

    @Override
    public Object visit(ASTAritmetica node, Object data) {
        String tipoIzq = (String) node.jjtGetChild(0).jjtAccept(this, data);
        String tipoDer = (String) node.jjtGetChild(2).jjtAccept(this, data);

        String operador = (String) ((SimpleNode) node.jjtGetChild(1)).jjtGetValue();

        if (tipoIzq == null || tipoDer == null) return "error";
        if (tipoIzq.equals("error") || tipoDer.equals("error")) return "error";

        if (tipoIzq.equals("texto") || tipoDer.equals("texto") || tipoIzq.equals("logico") || tipoDer.equals("logico")) {
            reportar(node, "Operación matemática inválida entre '" + tipoIzq + "' y '" + tipoDer + "'. Solo se permiten números.");
            return "error";
        }

        // INTEGRACIÓN: La división o la presencia de un decimal promueven el resultado a decimal
        if (tipoIzq.equals("decimal") || tipoDer.equals("decimal") || "/".equals(operador)) {
            return "decimal";
        }
        // Con un operando dinámico (ingresar) no se sabe si el resultado es entero o decimal
        if (tipoIzq.equals(TIPO_DINAMICO) || tipoDer.equals(TIPO_DINAMICO)) return TIPO_DINAMICO;
        return "entero";
    }

    // ingresar(): su tipo se conoce solo al ejecutar
    @Override
    public Object visit(ASTLeer node, Object data) {
        return TIPO_DINAMICO;
    }

    // -x : conserva el tipo numérico de x
    @Override
    public Object visit(ASTNegacion node, Object data) {
        String t = (String) node.jjtGetChild(0).jjtAccept(this, data);
        if (t == null || t.equals("error")) return "error";
        if (!esNumerico(t) && !t.equals(TIPO_DINAMICO)) {
            reportar(node, "Operación inválida: no se puede negar un valor de tipo '" + t + "'. Solo se permiten números.");
            return "error";
        }
        return t;
    }

    // raiz x : x numérico; el resultado es decimal
    @Override
    public Object visit(ASTRaiz node, Object data) {
        String t = (String) node.jjtGetChild(0).jjtAccept(this, data);
        if (t == null || t.equals("error")) return "error";
        if (!esNumerico(t) && !t.equals(TIPO_DINAMICO)) {
            reportar(node, "Operación inválida: no se puede sacar la raíz de un valor de tipo '" + t + "'. Solo se permiten números.");
            return "error";
        }
        return "decimal";
    }

    // no x : x lógico; el resultado es lógico
    @Override
    public Object visit(ASTNot node, Object data) {
        String t = (String) node.jjtGetChild(0).jjtAccept(this, data);
        if (t == null || t.equals("error")) return "error";
        if (!t.equals("logico") && !t.equals(TIPO_DINAMICO)) {
            reportar(node, "Operación lógica inválida: 'no' requiere un valor lógico, pero recibió '" + t + "'.");
            return "error";
        }
        return "logico";
    }

    @Override
    public Object visit(ASTComparacion node, Object data) {
        String tipoIzq = (String) node.jjtGetChild(0).jjtAccept(this, data);
        String tipoDer = (String) node.jjtGetChild(2).jjtAccept(this, data);

        if (tipoIzq == null || tipoDer == null || tipoIzq.equals("error") || tipoDer.equals("error")) {
            return "error";
        }

        // Validación de compatibilidad para comparaciones
        // Un valor dinámico (ingresar) se compara con cualquier tipo; se valida al ejecutar
        if (tipoIzq.equals(TIPO_DINAMICO) || tipoDer.equals(TIPO_DINAMICO)) return "logico";

        boolean sonIguales = tipoIzq.equals(tipoDer);
        boolean sonNumerosMezclados = (tipoIzq.equals("entero") || tipoIzq.equals("decimal")) &&
                (tipoDer.equals("entero") || tipoDer.equals("decimal"));

        // Si no son exactamente el mismo tipo, y tampoco son una mezcla válida de números (entero vs decimal), es un error
        if (!sonIguales && !sonNumerosMezclados) {
            reportar(node, "Operación relacional inválida: No puedes comparar un '" + tipoIzq + "' con un '" + tipoDer + "'.");
            return "error";
        }

        return "logico";
    }

    @Override
    public Object visit(ASTLlamadaFuncion node, Object data) {
        SimpleNode nodoId = (SimpleNode) node.jjtGetChild(0);
        String nombreFun = (String) nodoId.jjtGetValue();

        Simbolo simFunc = tabla.buscar(nombreFun);

        if (simFunc == null || !simFunc.categoria.equals("funcion")) {
            reportar(nodoId, "Estás llamando a la función '" + nombreFun + "', pero no existe.");
            return "error";
        }

        simbolosPorNodo.put(nodoId, simFunc); // [CÓDIGO INTERMEDIO]

        int argsEnviados = node.jjtGetNumChildren() - 1;

        if (argsEnviados != simFunc.tiposParametros.size()) {
            reportar(nodoId, "La función '" + nombreFun + "' exige " + simFunc.tiposParametros.size() + " argumento(s), pero le enviaste " + argsEnviados + ".");
        } else {
            for (int i = 0; i < argsEnviados; i++) {
                String tipoArg = (String) node.jjtGetChild(i + 1).jjtAccept(this, data);
                String tipoParam = simFunc.tiposParametros.get(i);

                // INTEGRACIÓN: Coerción en paso de parámetros (ej. enviar entero donde se pide decimal)
                if (tipoArg != null && !tipoArg.equals("error") && !sonTiposCompatibles(tipoParam, tipoArg) && !tipoArg.startsWith("arreglo")) {
                    reportar((SimpleNode) node.jjtGetChild(i + 1), "El argumento #" + (i + 1) + " de '" + nombreFun + "' debe ser '" + tipoParam + "', pero le enviaste un '" + tipoArg + "'.");
                }
            }
        }
        return simFunc.tipoRetorno;
    }

    @Override public Object visit(ASTExpresion node, Object data) { return node.jjtGetChild(0).jjtAccept(this, data); }
    @Override public Object visit(ASTMostrar node, Object data) { super.visit(node, data); return null; }

    // ---- Validación de "romper" y "continuar" ----
    @Override
    public Object visit(ASTMientras node, Object data) {
        profundidadCiclo++;
        Object r = super.visit(node, data);
        profundidadCiclo--;
        return r;
    }

    @Override
    public Object visit(ASTHacerMientras node, Object data) {
        profundidadCiclo++;
        Object r = super.visit(node, data);
        profundidadCiclo--;
        return r;
    }

    @Override
    public Object visit(ASTRepetir node, Object data) {
        profundidadCiclo++;
        Object r = super.visit(node, data);
        profundidadCiclo--;
        return r;
    }

    @Override
    public Object visit(ASTSegun node, Object data) {
        profundidadSegun++;
        Object r = super.visit(node, data);
        profundidadSegun--;
        return r;
    }

    @Override
    public Object visit(ASTRomper node, Object data) {
        if (profundidadCiclo == 0 && profundidadSegun == 0) {
            reportar(node, "\"romper\" solo puede usarse dentro de un ciclo o de un \"segun\".");
        }
        return super.visit(node, data);
    }

    @Override
    public Object visit(ASTContinuar node, Object data) {
        if (profundidadCiclo == 0) {
            reportar(node, "\"continuar\" solo puede usarse dentro de un ciclo.");
        }
        return super.visit(node, data);
    }

    // [CÓDIGO INTERMEDIO] Operadores lógicos Y / O (nodo #Logica de la gramática).
    @Override
    public Object visit(ASTLogica node, Object data) {
        String tipoIzq = (String) node.jjtGetChild(0).jjtAccept(this, data);
        String tipoDer = (String) node.jjtGetChild(2).jjtAccept(this, data);

        if (tipoIzq == null || tipoDer == null || tipoIzq.equals("error") || tipoDer.equals("error")) {
            return "error";
        }
        boolean izqOk = tipoIzq.equals("logico") || tipoIzq.equals(TIPO_DINAMICO);
        boolean derOk = tipoDer.equals("logico") || tipoDer.equals(TIPO_DINAMICO);
        if (!izqOk || !derOk) {
            reportar(node, "Operación lógica inválida: 'Y' y 'O' requieren valores lógicos, pero recibió '"
                    + tipoIzq + "' y '" + tipoDer + "'.");
            return "error";
        }
        return "logico";
    }
}
