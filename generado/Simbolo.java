package generado;

import java.util.ArrayList;

public class Simbolo {
    public String nombre;
    public String tipo;
    public String categoria;    // "variable", "constante", "arreglo", "funcion", "parametro"
    public String ambito;       // "Global" o el nombre de la función local
    public int offset;
    public int linea;

    public int dimensiones;                    // Para arreglos
    public String tipoRetorno;                 // Exclusivo para funciones
    public ArrayList<String> tiposParametros;  // Exclusivo para firma de función
    public Object valorConstante;              // Para plegado de constantes
    public boolean inicializada;

    public Simbolo(String nombre, String tipo, String categoria, String ambito, int offset, int linea) {
        this.nombre = nombre;
        this.tipo = tipo;
        this.categoria = categoria;
        this.ambito = ambito;
        this.offset = offset;
        this.linea = linea;

        this.dimensiones = 0;
        this.tiposParametros = new ArrayList<>();
        this.inicializada = true;
    }
}