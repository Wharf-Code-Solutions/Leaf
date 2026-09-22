package generado;

import java.util.HashMap;
import java.util.Stack;

public class TablaSimbolos {
    private Stack<HashMap<String, Simbolo>> pilaAmbitos;
    private Stack<String> pilaNombresAmbitos;

    private int offsetGlobal = 0;
    private int offsetLocal = 0;

    public TablaSimbolos() {
        pilaAmbitos = new Stack<>();
        pilaNombresAmbitos = new Stack<>();

        pilaAmbitos.push(new HashMap<>()); // Crea el ámbito Global por defecto
        pilaNombresAmbitos.push("Global");
    }

    public void entrarBloque(String nombreAmbito) {
        pilaAmbitos.push(new HashMap<>());
        pilaNombresAmbitos.push(nombreAmbito);
    }

    public void salirBloque() {
        if (pilaAmbitos.size() > 1) {
            pilaAmbitos.pop();
            pilaNombresAmbitos.pop();
        }
    }

    public void resetearOffsetLocal() {
        this.offsetLocal = 0;
    }

    public String obtenerAmbitoActual() {
        return pilaNombresAmbitos.peek();
    }

    public Simbolo buscarLocal(String nombre) {
        return pilaAmbitos.peek().get(nombre);
    }

    public Simbolo buscar(String nombre) {
        // Búsqueda desde el bloque actual hacia afuera hasta llegar al global
        for (int i = pilaAmbitos.size() - 1; i >= 0; i--) {
            if (pilaAmbitos.get(i).containsKey(nombre)) {
                return pilaAmbitos.get(i).get(nombre);
            }
        }
        return null;
    }

    public Simbolo insertar(String nombre, String tipo, String categoria, int linea, int dimensiones) {
        HashMap<String, Simbolo> ambitoActual = pilaAmbitos.peek();

        if (ambitoActual.containsKey(nombre)) {
            return null; // Error: Ya existe en este ámbito
        }

        String nombreAmbito = obtenerAmbitoActual();
        int tamano = obtenerTamano(tipo, dimensiones);
        int offsetAsignado = nombreAmbito.equals("Global") ? offsetGlobal : offsetLocal;

        Simbolo nuevoSimbolo = new Simbolo(nombre, tipo, categoria, nombreAmbito, offsetAsignado, linea);
        nuevoSimbolo.dimensiones = dimensiones;
        ambitoActual.put(nombre, nuevoSimbolo);

        if (nombreAmbito.equals("Global")) {
            offsetGlobal += tamano;
        } else {
            offsetLocal += tamano;
        }

        return nuevoSimbolo;
    }

    private int obtenerTamano(String tipo, int dimensiones) {
        if (tipo == null || tipo.equals("vacio")) return 0;
        if (dimensiones > 0 || tipo.equals("texto") || tipo.equals("arreglo")) return 8; // Punteros
        switch (tipo) {
            case "logico": return 1;
            case "entero": return 4;
            case "decimal": return 8;
            default: return 4;
        }
    }
}