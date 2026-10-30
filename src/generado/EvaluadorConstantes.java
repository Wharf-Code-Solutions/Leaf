package generado;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import generado.Cuadrupla.Op;

/**
 * Calcula, en tiempo de compilación, una operación cuyos operandos son literales, con las mismas
 * reglas de tipos que el análisis semántico de Leaf:
 *   - entero (op) entero da entero (aritmética de 32 bits, como el tamaño de 4 bytes de la tabla de símbolos);
 *   - si interviene un decimal, o la operación es "/", el resultado es decimal;
 *   - las comparaciones dan verdadero / falso; a_entero trunca hacia cero.
 * Devuelve null cuando no es seguro calcularla (división o módulo entre cero, potencia de enteros con
 * exponente negativo, raíz de un negativo, raíz enésima entre enteros...): la instrucción se deja igual.
 */
final class EvaluadorConstantes {

    private EvaluadorConstantes() {}

    /** Resultado (como literal) de aplicar op a los literales a y b (b puede ser null), o null. */
    static String evaluar(Op op, String a, String b) {
        try {
            switch (op) {
                case SUMA: case RESTA: case MULT: case DIV: case MOD: case POT: case RAIZ_N:
                    return aritmetica(op, a, b);
                case IGUAL: case DIFERENTE: case MAYOR: case MENOR: case MAYOR_IGUAL: case MENOR_IGUAL:
                    return comparacion(op, a, b);
                case NEG:       return negacion(a);
                case RAIZ:      return raizCuadrada(a);
                case NO:        return "logico".equals(tipo(a)) ? logico(!a.equals("verdadero")) : null;
                case A_DECIMAL: return esNumero(a) ? decimal(new BigDecimal(a)) : null;
                case A_ENTERO:  return esNumero(a) ? String.valueOf(new BigDecimal(a).setScale(0, RoundingMode.DOWN).intValue()) : null;
                default:        return null;
            }
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    /** ¿Es una operación que se puede calcular si sus operandos son constantes? */
    static boolean esCalculable(Op op) {
        switch (op) {
            case SUMA: case RESTA: case MULT: case DIV: case MOD: case POT: case RAIZ_N:
            case IGUAL: case DIFERENTE: case MAYOR: case MENOR: case MAYOR_IGUAL: case MENOR_IGUAL:
            case NEG: case RAIZ: case NO: case A_DECIMAL: case A_ENTERO:
                return true;
            default:
                return false;
        }
    }

    // ----------------------------------------------------------------

    private static String aritmetica(Op op, String a, String b) {
        if (!esNumero(a) || !esNumero(b)) return null;
        boolean enteros = "entero".equals(tipo(a)) && "entero".equals(tipo(b));

        if (enteros && op != Op.DIV) {
            int x = Integer.parseInt(a), y = Integer.parseInt(b);
            switch (op) {
                case SUMA:  return String.valueOf(x + y);
                case RESTA: return String.valueOf(x - y);
                case MULT:  return String.valueOf(x * y);
                case MOD:   return y == 0 ? null : String.valueOf(x % y);
                case POT:   return y < 0 ? null : String.valueOf(potenciaEntera(x, y));
                default:    return null;   // RAIZ_N entre enteros: su resultado no es exacto, no se calcula
            }
        }

        BigDecimal x = new BigDecimal(a), y = new BigDecimal(b);
        switch (op) {
            case SUMA:  return decimal(x.add(y));
            case RESTA: return decimal(x.subtract(y));
            case MULT:  return decimal(x.multiply(y));
            case DIV:   return y.signum() == 0 ? null : decimal(x.divide(y, MathContext.DECIMAL64));
            case MOD:   return y.signum() == 0 ? null : decimal(x.remainder(y));
            case POT:   return doble(Math.pow(x.doubleValue(), y.doubleValue()));
            case RAIZ_N: {
                // a -/ b = raíz a-ésima de b
                double indice = x.doubleValue(), radicando = y.doubleValue();
                if (indice == 0 || radicando < 0) return null;
                return doble(Math.pow(radicando, 1.0 / indice));
            }
            default:    return null;
        }
    }

    private static String comparacion(Op op, String a, String b) {
        if (esNumero(a) && esNumero(b)) {
            int c = new BigDecimal(a).compareTo(new BigDecimal(b));
            switch (op) {
                case IGUAL:       return logico(c == 0);
                case DIFERENTE:   return logico(c != 0);
                case MAYOR:       return logico(c > 0);
                case MENOR:       return logico(c < 0);
                case MAYOR_IGUAL: return logico(c >= 0);
                case MENOR_IGUAL: return logico(c <= 0);
                default:          return null;
            }
        }
        if ("logico".equals(tipo(a)) && "logico".equals(tipo(b))) {
            if (op == Op.IGUAL) return logico(a.equals(b));
            if (op == Op.DIFERENTE) return logico(!a.equals(b));
        }
        return null;   // textos: no se comparan en compilación
    }

    private static String negacion(String a) {
        if ("entero".equals(tipo(a))) return String.valueOf(-Integer.parseInt(a));
        if ("decimal".equals(tipo(a))) return decimal(new BigDecimal(a).negate());
        return null;
    }

    private static String raizCuadrada(String a) {
        if (!esNumero(a)) return null;
        double v = new BigDecimal(a).doubleValue();
        return v < 0 ? null : doble(Math.sqrt(v));
    }

    private static int potenciaEntera(int base, int exponente) {
        int resultado = 1;
        int b = base;
        int e = exponente;
        while (e > 0) {
            if ((e & 1) == 1) resultado *= b;
            b *= b;
            e >>= 1;
        }
        return resultado;
    }

    // ----------------------------------------------------------------

    private static String tipo(String literal) {
        return AnalisisCuadruplas.tipoLiteral(literal);
    }

    private static boolean esNumero(String literal) {
        String t = tipo(literal);
        return "entero".equals(t) || "decimal".equals(t);
    }

    private static String logico(boolean v) {
        return v ? "verdadero" : "falso";
    }

    private static String doble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return decimal(BigDecimal.valueOf(v));
    }

    /** Literal decimal sin ceros sobrantes, pero siempre con punto (para que siga siendo decimal): 4 -> 4.0 */
    static String decimal(BigDecimal v) {
        String s = v.stripTrailingZeros().toPlainString();
        return s.indexOf('.') < 0 ? s + ".0" : s;
    }
}
