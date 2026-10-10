package generado;

/**
 * Una instrucción de código intermedio en forma de CUÁDRUPLA:
 *
 *     ( operación , argumento1 , argumento2 , resultado )
 *
 * Es inmutable. Los operandos son cadenas: un literal ("3", "\"hola\"", "verdadero"),
 * una variable con nombre único por ámbito ("$x", "$n@suma", "$t#2"), un temporal ("t1")
 * o una etiqueta ("L1"). Los campos que una operación no usa quedan en null.
 *
 * Convención de campos por operación (para cuando se genere código objeto / se optimice):
 *
 *   ASIGNA        res = arg1
 *   A_DECIMAL     res = (decimal) arg1                  conversión entero -> decimal
 *   SUMA .. POT   res = arg1 (op) arg2
 *   RAIZ_N        res = arg1 -/ arg2                    raíz arg1-ésima de arg2, igual que se escribe (3 -/ 27)
 *   NEG, RAIZ, NO res = (op) arg1
 *   IGUAL .. MENOR_IGUAL   res = arg1 (cmp) arg2        res es lógico
 *   SALTA         goto res                              res = etiqueta
 *   SALTA_FALSO   if arg1 es falso goto res
 *   SALTA_VERD    if arg1 es verdadero goto res
 *   ETIQUETA      res:
 *   INICIO / FIN  inicio y fin (halt) del programa principal
 *   FUNC_INICIO / FUNC_FIN   res = nombre de la función
 *   RECIBE        res = parámetro formal (se recibe en el orden de declaración)
 *   PARAM         pasa arg1 como argumento
 *   LLAMA         res = call arg1, arg2 (arg2 = nº de argumentos); res puede ser null
 *   RETORNA       return arg1 (arg1 puede ser null)
 *   LEE           res = ingresar(arg1)  (arg1 = mensaje, opcional; res puede ser null)
 *   MUESTRA       print arg1
 *   NUEVO_ARR     res = arreglo nuevo de arg1 elementos
 *   ARR_LEE       res = arg1[arg2]
 *   ARR_ESCRIBE   res[arg2] = arg1                      (res = arreglo destino)
 *   AGREGAR       agregar arg2 al final del arreglo arg1
 *   QUITAR        quitar la posición arg2 del arreglo arg1
 *   LONGITUD      res = longitud(arg1)
 */
public final class Cuadrupla {

    public enum Op {
        ASIGNA("="), A_DECIMAL("a_decimal"),
        SUMA("+"), RESTA("-"), MULT("*"), DIV("/"), MOD("%"), POT("^"), RAIZ_N("-/"),
        NEG("neg"), RAIZ("raiz"), NO("not"),
        IGUAL("=="), DIFERENTE("!="), MAYOR(">"), MENOR("<"), MAYOR_IGUAL(">="), MENOR_IGUAL("<="),
        SALTA("goto"), SALTA_FALSO("ifFalse"), SALTA_VERD("if"),
        ETIQUETA("label"), INICIO("main"), FIN("halt"),
        FUNC_INICIO("func"), FUNC_FIN("endfunc"), RECIBE("recibe"),
        PARAM("param"), LLAMA("call"), RETORNA("return"),
        LEE("read"), MUESTRA("print"),
        NUEVO_ARR("newarray"), ARR_LEE("[]"), ARR_ESCRIBE("[]="),
        AGREGAR("append"), QUITAR("remove"), LONGITUD("length");

        /** Nombre corto de la operación, tal como se muestra en la tabla de cuádruplos. */
        public final String simbolo;
        Op(String simbolo) { this.simbolo = simbolo; }
    }

    public final Op op;
    public final String arg1;
    public final String arg2;
    public final String resultado;

    public Cuadrupla(Op op, String arg1, String arg2, String resultado) {
        this.op = op;
        this.arg1 = arg1;
        this.arg2 = arg2;
        this.resultado = resultado;
    }

    private static String o(String s) { return s == null ? "" : s; }

    /** Las cuatro columnas de la tabla de cuádruplos: operación, arg1, arg2, resultado. */
    public String[] columnas() {
        return new String[] { op.simbolo, o(arg1), o(arg2), o(resultado) };
    }

    /** Forma legible de tres direcciones, p. ej. "t1 = $a + $b". */
    public String aTexto() {
        switch (op) {
            case ETIQUETA:    return resultado + ":";
            case INICIO:      return "main:";
            case FIN:         return "halt";
            case FUNC_INICIO: return "func " + resultado + ":";
            case FUNC_FIN:    return "endfunc " + resultado;
            case RECIBE:      return "recibe " + resultado;
            case SALTA:       return "goto " + resultado;
            case SALTA_FALSO: return "ifFalse " + arg1 + " goto " + resultado;
            case SALTA_VERD:  return "if " + arg1 + " goto " + resultado;
            case PARAM:       return "param " + arg1;
            case LLAMA:       return (resultado != null ? resultado + " = " : "") + "call " + arg1 + ", " + arg2;
            case RETORNA:     return arg1 == null ? "return" : "return " + arg1;
            case LEE:         return (resultado != null ? resultado + " = " : "") + "read" + (arg1 != null ? " " + arg1 : "");
            case MUESTRA:     return "print " + arg1;
            case NUEVO_ARR:   return resultado + " = newarray " + arg1;
            case ARR_LEE:     return resultado + " = " + arg1 + "[" + arg2 + "]";
            case ARR_ESCRIBE: return resultado + "[" + arg2 + "] = " + arg1;
            case AGREGAR:     return "append " + arg1 + ", " + arg2;
            case QUITAR:      return "remove " + arg1 + ", " + arg2;
            case LONGITUD:    return resultado + " = length " + arg1;
            case ASIGNA:      return resultado + " = " + arg1;
            case A_DECIMAL:   return resultado + " = (decimal) " + arg1;
            case NEG:         return resultado + " = - " + arg1;
            case RAIZ:        return resultado + " = -/ " + arg1;
            case NO:          return resultado + " = not " + arg1;
            case RAIZ_N:      return resultado + " = " + arg1 + " -/ " + arg2;
            default:          return resultado + " = " + arg1 + " " + op.simbolo + " " + arg2;
        }
    }

    @Override
    public String toString() {
        return "(" + op.simbolo + ", " + o(arg1) + ", " + o(arg2) + ", " + o(resultado) + ")";
    }
}
