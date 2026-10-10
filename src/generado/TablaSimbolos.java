package generado;

import java.util.HashMap;
import java.util.Stack;

public class TablaSimbolos {
    // ===== [IDE] Rastreo paso a paso de la tabla (lo consume LeafIDE) =====
    // No afecta el comportamiento del compilador: solo registra eventos en memoria.
    // Requiere Java 16+ por el uso de 'record'.
    public enum TipoEvento { ENTRAR, SALIR, INSERTAR, BUSCAR }
    public record EventoTabla(TipoEvento tipo, String ambito, Simbolo simbolo, String mensaje) {}
    public final java.util.List<EventoTabla> historial = new java.util.ArrayList<>();
    // ======================================================================

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
        // [IDE] rastreo
        historial.add(new EventoTabla(TipoEvento.ENTRAR, nombreAmbito, null, "Entrando al ámbito: " + nombreAmbito));
    }

    public void salirBloque() {
        if (pilaAmbitos.size() > 1) {
            // [IDE] rastreo (se registra antes del pop para conocer el ámbito que sale)
            String ambitoSaliente = pilaNombresAmbitos.peek();
            historial.add(new EventoTabla(TipoEvento.SALIR, ambitoSaliente, null, "Saliendo del ámbito: " + ambitoSaliente));
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
        Simbolo s = pilaAmbitos.peek().get(nombre);
        // [IDE] rastreo
        historial.add(new EventoTabla(TipoEvento.BUSCAR, obtenerAmbitoActual(), s, "Búsqueda local de '" + nombre + "': " + (s != null ? "Encontrado" : "No encontrado")));
        return s;
    }

    public Simbolo buscar(String nombre) {
        // Búsqueda desde el bloque actual hacia afuera hasta llegar al global
        for (int i = pilaAmbitos.size() - 1; i >= 0; i--) {
            if (pilaAmbitos.get(i).containsKey(nombre)) {
                Simbolo s = pilaAmbitos.get(i).get(nombre);
                // [IDE] rastreo
                historial.add(new EventoTabla(TipoEvento.BUSCAR, pilaNombresAmbitos.get(i), s, "Búsqueda de '" + nombre + "' resuelta en ámbito: " + pilaNombresAmbitos.get(i)));
                return s;
            }
        }
        // [IDE] rastreo
        historial.add(new EventoTabla(TipoEvento.BUSCAR, obtenerAmbitoActual(), null, "Búsqueda de '" + nombre + "' fallida"));
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

        // [IDE] rastreo
        historial.add(new EventoTabla(TipoEvento.INSERTAR, nombreAmbito, nuevoSimbolo, "Insertado '" + nombre + "' como " + categoria + " (" + tipo + ")"));

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