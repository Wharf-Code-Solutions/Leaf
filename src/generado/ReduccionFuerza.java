package generado;

import java.util.ArrayList;
import java.util.List;

import generado.Cuadrupla.Op;

/**
 * Reducción de fuerza mediante optimizaciones de mirilla.
 */
public final class ReduccionFuerza {

    private final Optimizador optimizador;

    public ReduccionFuerza(Optimizador optimizador) {
        this.optimizador = optimizador;
    }

    /**
     * Aplica las reglas de reducción de fuerza a los cuádruplos.
     */
    public List<Cuadrupla> aplicar(List<Cuadrupla> lista) {

        List<Cuadrupla> resultado = new ArrayList<>(lista);

        for (int i = 0; i < resultado.size(); i++) {

            Cuadrupla c = resultado.get(i);

            if (!AnalisisCuadruplas.esTemporal(c.resultado)) {
                continue;
            }

            String tipoResultado =
                optimizador.tiposTemporales.get(c.resultado);
                if (tipoResultado == null) {
 		   continue;
		}  
            // REGLA 1: x * 2 = x + x
            if (c.op == Op.MULT) {

                if (esConstante(c.arg1, "2", tipoResultado)
                    && tipoResultado.equals(tipoDe(c.arg2))) {

                    resultado.set(i, new Cuadrupla(
                        Op.SUMA, c.arg2, c.arg2, c.resultado
                    ));
                    continue;
                }

                if (esConstante(c.arg2, "2", tipoResultado)
                    && tipoResultado.equals(tipoDe(c.arg1))) {

                    resultado.set(i, new Cuadrupla(
                        Op.SUMA, c.arg1, c.arg1, c.resultado
                    ));
                    continue;
                }
            }

            // REGLA 2: x ^ 2 = x * x
            if (c.op == Op.POT
                && esConstante(c.arg2, "2", tipoResultado)
                && tipoResultado.equals(tipoDe(c.arg1))) {

                resultado.set(i, new Cuadrupla(
                    Op.MULT, c.arg1, c.arg1, c.resultado
                ));
                continue;
            }

            // REGLAS 3, 4 y 5: multiplicación por 1,
            // suma de 0 y multiplicación por 0.
            if (c.op == Op.MULT || c.op == Op.SUMA) {

                String valor = null;
                String constante = null;

                if (esCero(c.arg1) || esUno(c.arg1)) {
                    constante = c.arg1;
                    valor = c.arg2;
                } else if (esCero(c.arg2) || esUno(c.arg2)) {
                    constante = c.arg2;
                    valor = c.arg1;
                }

                if (constante == null) {
                    continue;
                }

                if (!tipoResultado.equals(tipoDe(constante))) {
                    continue;
                }

                if ("0".equals(constante) || "0.0".equals(constante)) {

                    if (c.op == Op.MULT) {
                        resultado.set(i, new Cuadrupla(
                            Op.ASIGNA,
                            constante,
                            null,
                            c.resultado
                        ));
                    } else if (c.op == Op.SUMA
                        && tipoResultado.equals(tipoDe(valor))) {

                        resultado.set(i, new Cuadrupla(
                            Op.ASIGNA,
                            valor,
                            null,
                            c.resultado
                        ));
                    }

                } else if (("1".equals(constante) || "1.0".equals(constante))
                    && c.op == Op.MULT
                    && tipoResultado.equals(tipoDe(valor))) {

                    resultado.set(i, new Cuadrupla(
                        Op.ASIGNA,
                        valor,
                        null,
                        c.resultado
                    ));
                }
            }
        }

        return resultado;
    }

    /**
     * Comprueba si un operando es una constante numérica.
     */
    private boolean esConstante(
        String operando, String valor, String tipo
    ) {
        if (operando == null || tipo == null) {
            return false;
        }

        if ("decimal".equals(tipo)) {
            return (valor + ".0").equals(operando);
        }

        return valor.equals(operando);
    }

    private boolean esCero(String valor) {
        return "0".equals(valor) || "0.0".equals(valor);
    }

    private boolean esUno(String valor) {
        return "1".equals(valor) || "1.0".equals(valor);
    }

    /**
     * Obtiene el tipo de un operando.
     */
    private String tipoDe(String operando) {

        if (operando == null) {
            return null;
        }

        String tipoLiteral = AnalisisCuadruplas.tipoLiteral(operando);

        if (tipoLiteral != null) {
            return tipoLiteral;
        }

        if (AnalisisCuadruplas.esTemporal(operando)) {
            return optimizador.tiposTemporales.get(operando);
        }

        Simbolo simbolo = optimizador.simbolos.get(operando);

        return simbolo == null ? null : simbolo.tipo;
    }
}
