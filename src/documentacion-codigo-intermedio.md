# Leaf — Documentación de la fase de código intermedio

Documento de apoyo para actualizar el **manual de usuario** y el **manual de sistema**.
Cubre todo lo que se agregó o cambió desde el compilador anterior (léxico + sintáctico + semántico) para añadir la generación de **código intermedio en cuádruplos**.

- Rama de trabajo: `feature/codigo-intermedio` (ya integrada a `main`).
- Todos los ejemplos de tablas de este documento son salidas reales del compilador.

---

## 1. Resumen ejecutivo

1. Se agregaron **2 archivos Java nuevos**:
   - `Cuadrupla.java`: la estructura de datos de una instrucción intermedia.
   - `GeneradorCodigoIntermedio.java`: recorre el árbol (AST) y produce la lista de cuádruplos.
2. Se modificaron **4 archivos existentes**:
   - `Leaf.jjt` (gramática y `main`).
   - `VisitanteSemantico.java`.
   - `TablaSimbolos.java`.
   - `Simbolo.java` solo cambió de carpeta, sin cambios de contenido.
3. El compilador ahora tiene una fase más: tras analizar sin errores, **genera código intermedio**. Con cualquier error léxico, sintáctico o semántico **no se genera nada**.
4. Hay dos opciones de línea de comandos nuevas: `--tres-dir` y `--guardar`.
5. El repositorio pasó a una estructura con carpeta `src/` (la de IntelliJ).

Pipeline completo:

```
archivo .txt
   │
   ▼
Análisis léxico + sintáctico   (Leaf.jj generado por JavaCC/JJTree)  → AST
   │
   ▼
Análisis semántico             (VisitanteSemantico + TablaSimbolos + Simbolo)
   │         └─ errores → se listan y TERMINA (no hay código intermedio)
   ▼
Generación de código intermedio (GeneradorCodigoIntermedio → List<Cuadrupla>)
   │
   ├─ consola: tabla de cuádruplos (siempre)
   ├─ consola: forma de tres direcciones (solo con --tres-dir)
   └─ archivo .cuad (solo con --guardar)
```

---

## 2. Conceptos básicos

### 2.1 Código de tres direcciones vs. cuádruplos
- **Código de tres direcciones** es la *idea*: cada instrucción tiene como máximo tres "direcciones" (dos operandos y un resultado). Ejemplo: `t1 = $a + $b`.
- **Cuádruplo** es el *formato de almacenamiento*: la misma instrucción guardada como cuatro campos `(operación, arg1, arg2, resultado)`.
- En Leaf, el código intermedio **vive en memoria** como una `List<Cuadrupla>`. La tabla y el texto de tres direcciones son solo **vistas** de esa lista. El archivo `.cuad` es opcional y sirve para depurar.
- Se eligieron cuádruplos y no texto porque las fases siguientes (optimización y código objeto) necesitan **datos que se puedan consultar y modificar**, no cadenas que haya que volver a interpretar.

### 2.2 Los cuatro campos
| Campo | Qué contiene |
|---|---|
| `op` | La operación (enumeración `Cuadrupla.Op`). |
| `arg1` | Primer operando. |
| `arg2` | Segundo operando (o un dato auxiliar, según la operación). |
| `resultado` | Dónde se guarda el resultado, o la etiqueta/nombre al que se refiere la operación. |

Los campos que una operación no usa quedan en `null` (se muestran vacíos en la tabla).

Un operando puede ser:
- un **literal**: `3`, `14.3`, `"hola"`, `verdadero`;
- una **variable** con nombre único: `$x`, `$n@suma`, `$i#2`;
- un **temporal**: `t1`, `t2`, … (variables internas del compilador);
- una **etiqueta**: `L1`, `L2`, … (solo en operaciones de salto y `label`).

### 2.3 Convenciones de nombres
- **Temporales** (`t1`, `t2`…): se numeran desde 1 en cada compilación. Cada uno tiene un tipo conocido (`entero`, `decimal`, `logico`, `texto`, `arreglo:...` o `?` si solo se sabe al ejecutar).
- **Etiquetas** (`L1`, `L2`…): también desde 1 en cada compilación.
- **Variables**: nombre único por declaración.
  - Variable global: `$x`.
  - Variable local o parámetro de una función: `$x@nombreFuncion`.
  - Si dos declaraciones dieran el mismo nombre, se agrega `#2`, `#3`… (ejemplo: dos ciclos `para $i` en el programa principal: `$i` y `$i#2`).
  - Así una variable local nunca se confunde con una global del mismo nombre.

---

## 3. Archivo nuevo: `Cuadrupla.java`

Clase `final` e **inmutable** (todos los campos son `public final`). Contiene:

- `enum Op`: las 39 operaciones posibles, cada una con su símbolo corto (el que se ve en la tabla).
- `String FIN_MOSTRAR = "fin"`: la marca que indica el último argumento de un `mostrar` (ver 5.10).
- `columnas()` (la tabla impresa tiene esas cuatro columnas y no lleva índice de fila): devuelve `{operación, arg1, arg2, resultado}` como texto, para la tabla.
- `aTexto()`: devuelve la forma legible de tres direcciones (por ejemplo `t1 = $a + $b`).
- `toString()`: `(op, arg1, arg2, resultado)`, útil para depurar.

### 3.1 Catálogo de operaciones

| Grupo | Símbolo en la tabla | Significado | Campos usados |
|---|---|---|---|
| **Datos** | `=` | `res = arg1` | arg1, res |
| | `a_decimal` | `res = (decimal) arg1`, conversión entero → decimal | arg1, res |
| | `a_entero` | `res = (entero) arg1`, conversión decimal → entero que **trunca hacia cero** (`7.9` → `7`, `-7.9` → `-7`). Solo se emite para `a / b` de enteros guardado en un entero (ver 4.4, punto 9) | arg1, res |
| **Aritmética** | `+ - * / % ^` | `res = arg1 (op) arg2` | arg1, arg2, res |
| | `-/` | raíz enésima: `res = arg1 -/ arg2` (raíz de índice `arg1` de `arg2`, como se escribe `3 -/ 27`) | arg1, arg2, res |
| | `neg` | `res = - arg1` | arg1, res |
| | `raiz` | `res = -/ arg1` (raíz cuadrada, siempre decimal) | arg1, res |
| **Comparación y lógica** | `== != > < >= <=` | `res = arg1 (cmp) arg2`; `res` es lógico | arg1, arg2, res |
| | `not` | `res = not arg1` | arg1, res |
| **Saltos** | `label` | marca de destino `res:` | res |
| | `goto` | salta a la etiqueta `res` | res |
| | `ifFalse` | si `arg1` es falso, salta a `res` | arg1, res |
| | `if` | si `arg1` es verdadero, salta a `res` | arg1, res |
| **Programa** | `main` | marca el inicio del programa principal | res = `main` |
| | `halt` | fin del programa principal | — |
| **Funciones** | `func` / `endfunc` | inicio y fin de una función (`res` = nombre) | res |
| | `recibe` | parámetro formal, en orden de declaración | res |
| | `param` | pasa `arg1` como argumento (se emiten justo antes de `call`) | arg1 |
| | `call` | `res = call arg1, arg2` (`arg1` = función, `arg2` = nº de argumentos; `res` puede ser vacío si es una sentencia) | arg1, arg2, res |
| | `return` | `return arg1` (`arg1` puede ser vacío) | arg1 |
| **Entrada/salida** | `read` | `res = ingresar(arg1)` (`arg1` = mensaje opcional) | arg1, res |
| | `print` | imprime `arg1`; si `arg2` = `fin`, es el último argumento del `mostrar` | arg1, arg2 |
| **Arreglos** | `newarray` | `res = arreglo nuevo de arg1 elementos` | arg1, res |
| | `[]` | `res = arg1[arg2]` (lectura) | arg1, arg2, res |
| | `[]=` | `res[arg2] = arg1` (escritura; `res` es el arreglo destino) | arg1, arg2, res |
| | `append` | agrega `arg2` al final del arreglo `arg1` | arg1, arg2 |
| | `remove` | quita la posición `arg2` del arreglo `arg1` | arg1, arg2 |
| | `length` | `res = longitud(arg1)` | arg1, res |

### 3.2 Forma de tres direcciones
Cada cuádrupla tiene una forma de texto (`aTexto()`). Algunos ejemplos:

| Cuádrupla | Texto |
|---|---|
| `(+, $a, 1, t1)` | `t1 = $a + 1` |
| `(ifFalse, t2, , L2)` | `ifFalse t2 goto L2` |
| `(label, , , L2)` | `L2:` |
| `(call, factorial, 1, t4)` | `t4 = call factorial, 1` |
| `(print, "hola", fin, )` | `println "hola"` |
| `(print, $x, , )` | `print $x` |
| `(a_decimal, $n, , t3)` | `t3 = (decimal) $n` |
| `(a_entero, t6, , t7)` | `t7 = (entero) t6` |

---

## 4. Archivo nuevo: `GeneradorCodigoIntermedio.java`

### 4.1 Qué hace
Recibe el árbol (AST) que produce el parser y el `VisitanteSemantico` que ya lo analizó, y lo recorre construyendo la `List<Cuadrupla>`.

Se apoya en el semántico para saber **a qué declaración se refiere cada variable o función** (mapa `simbolosPorNodo`): así sabe su tipo, su ámbito y puede darle un nombre único.

Solo se ejecuta **después** del análisis semántico y **solo si no hubo errores**.

### 4.2 API pública

| Método | Descripción |
|---|---|
| `GeneradorCodigoIntermedio(VisitanteSemantico)` | Constructor. |
| `generar(Node raiz)` | Genera los cuádruplos (reinicia contadores y listas, así que se puede reutilizar). |
| `getCuadruplas()` | La lista (de solo lectura): es lo que consumirán el optimizador y el código objeto. |
| `getTiposTemporales()` | Mapa `t3 → "entero"`, etc. |
| `getSimbolosIR()` | Mapa nombre único → `Simbolo` (tipo, ámbito, offset…). |
| `getTabla()` | La tabla de cuádruplos como lista de líneas de texto. |
| `getTexto()` | La forma de tres direcciones como lista de líneas. |
| `imprimir(boolean tresDirecciones)` | Imprime la tabla; con `true`, agrega debajo la forma de tres direcciones. |
| `guardar(File fuente, boolean tresDirecciones)` | Escribe el `.cuad` junto al fuente. |

### 4.3 Estructura de la salida
```
main                      ← inicio del programa principal
   ...instrucciones sueltas del programa...
halt                      ← fin
func nombre               ← las funciones van DESPUÉS del halt
   recibe ...
   ...
   return
endfunc nombre
```

**Por qué siempre empieza con `main`:** en Leaf el programa principal no se declara; las instrucciones sueltas, fuera de funciones, *son* el programa. `main` es solo una marca de dónde empieza la ejecución (no hace nada por sí misma). Las funciones se colocan después del `halt` para que no se ejecuten por accidente al "caer" en ellas: solo se ejecutan cuando alguien las llama con `call`.

### 4.4 Decisiones de diseño (el manual del lenguaje no las fija)
Conviene documentarlas, porque definen cómo se comporta el lenguaje:

1. **Nombres únicos por declaración** (sección 2.3).
2. **Coerción entero → decimal explícita:** donde Leaf permite convertir un entero a decimal (asignaciones, argumentos, retornos, operaciones mixtas, división `/`, comparaciones mixtas), se emite `a_decimal`. Si el valor es un número literal, se convierte directamente (`2` → `2.0`) sin emitir instrucción.
3. **`Y` / `O` con cortocircuito:** el lado derecho no se evalúa si el izquierdo ya decide el resultado.
4. **`segun` sin caída:** al terminar un `caso` no se continúa con el siguiente (cada caso salta al final). `romper` dentro de un `segun` lo termina.
5. **`para` con límite inclusivo:** el `hasta` se incluye (`para $i de 1 hasta 3` ejecuta con 1, 2 y 3). El límite y el paso se evalúan **una sola vez**. Si el paso es una constante, el sentido (sube/baja) se decide al compilar; si no lo es, se decide al ejecutar.
6. **Orden de evaluación en `mostrar`:** primero se evalúan **todos** los argumentos (llamadas a función, cálculos) y después se imprime. Así, si una función imprime algo, su salida aparece antes de la línea completa del `mostrar`, como en los lenguajes habituales.
7. **La precedencia ya viene resuelta** por la gramática (el árbol está anidado), así que el generador no la recalcula.
8. **Contadores propios de cada compilación:** cada ejecución empieza otra vez en `t1` y `L1`, sin estado compartido.
9. **División de enteros guardada en un entero (truncado):** `/` siempre produce `decimal` (`7 / 2` = `3.5`). Como excepción, si la expresión *completa* es `a / b` con ambos operandos enteros y el destino es un `entero`, se acepta y el cociente se **trunca hacia cero** con `a_entero` (`7 / 2` → `3`, `-7 / 2` → `-3`). Aplica en declaraciones, asignaciones, elementos de arreglo, argumentos y `retornar`. Cualquier otra expresión decimal sigue siendo error al guardarla en un entero (por ejemplo `$a / $b / 2`, `($a / $b) * 2` o `3.5`). Ejemplo real (`$temp con valor de $temp / 10.` con `$temp` entero):
   ```
   a_decimal | $temp | | t5
   /         | t5    | 10.0 | t6
   a_entero  | t6    | | t7
   =         | t7    | | $temp
   ```

### 4.5 Cómo se traduce cada construcción

#### Declaración, asignación y aritmética
`definir $x como entero con valor de 5.` → `= | 5 | | $x`.
Una expresión se parte en pasos con temporales: `$suma + $i` →
```
+ | $suma | $i | t2
= | t2    |    | $suma
```

#### Comparaciones y lógica
`($numero % 2) igual que 0` en `ParOImpar`:
```
%  | $numero | 2.0 | t1
== | t1      | 0.0 | t2
```
(Los `2.0` y `0.0` aparecen porque `$numero` es decimal y se convirtieron las constantes enteras.)

`a Y b`: `t = a; ifFalse t → Lfin; t = b; Lfin:`. Para `O`: igual pero con `if` en lugar de `ifFalse`.

#### `si` / `sino si` / `sino`
```
ifFalse cond1 → Lsiguiente1
   ...bloque 1...
goto Lfin                 (solo si hay más ramas)
label Lsiguiente1
ifFalse cond2 → Lsiguiente2
   ...bloque 2...
goto Lfin
label Lsiguiente2
   ...bloque del sino...
label Lfin
```
Ejemplo real (`ParOImpar`):
```
%         | $numero                   | 2.0  | t1
==        | t1                        | 0.0  | t2
ifFalse   | t2                        |      | L2
print     | "El numero es par."       | fin  |
goto      |                           |      | L1
label     |                           |      | L2
print     | "El numero es impar."     | fin  |
label     |                           |      | L1
```

#### `mientras`
```
label Linicio
ifFalse cond → Lfin
   ...cuerpo...
goto Linicio
label Lfin
```
`continuar` salta a `Linicio`; `romper` salta a `Lfin`.

#### `hacer ... mientras`
```
label Linicio
   ...cuerpo...
label Lcontinuar
if cond → Linicio         (se repite mientras sea verdadera)
label Lfin
```

#### `repetir N veces`
Usa un contador temporal que empieza en 0:
```
t = 0
label Linicio
c = t < N ; ifFalse c → Lfin
   ...cuerpo...
label Lcontinuar
t = t + 1 ; goto Linicio
label Lfin
```

#### `para`
Ejemplo real (`Sumatoria`: `para $i de 1 hasta $limite`):
```
=         | 1         |         | $i
label     |           |         | L1
<=        | $i        | $limite | t1
ifFalse   | t1        |         | L3
+         | $suma     | $i      | t2
=         | t2        |         | $suma
label     |           |         | L2         ← destino de "continuar"
+         | $i        | 1       | $i         ← avanza el paso
goto      |           |         | L1
label     |           |         | L3         ← destino de "romper"
```

#### `segun`
Primero todas las pruebas (`== selector valor` + `if → Lcaso`), luego un `goto` al `defecto` (o al final si no hay), y después los cuerpos, cada uno terminando en `goto Lfin`.

#### Funciones y llamadas
Ejemplo real (`Factorial`, recursiva):
```
param     | $numeroEvaluar |      |
call      | factorial      | 1    | t1          ← t1 = factorial($numeroEvaluar)
   ...
halt      |
func      |                |      | factorial
recibe    |                |      | $n@factorial
<=        | $n@factorial   | 1    | t2
ifFalse   | t2             |      | L2
return    | 1              |      |
goto      |                |      | L1
label     |                |      | L2
-         | $n@factorial   | 1    | t3
param     | t3             |      |
call      | factorial      | 1    | t4
*         | $n@factorial   | t4   | t5
return    | t5             |      |
label     |                |      | L1
return    |                |      |             ← return implícito de seguridad
endfunc   |                |      | factorial
```
- Los argumentos se evalúan **todos** antes de emitir los `param`, para que las llamadas anidadas no se mezclen.
- Si el cuerpo no termina en `return`, el generador agrega uno vacío al final.

#### `mostrar`
`mostrar("a", $x, "b").` genera tres `print`; **el último lleva `fin` en `arg2`**:
```
print | "a"
print | $x
print | "b" | fin
```
Un `mostrar()` vacío genera `print ""` con `fin`.

#### `ingresar`
`read` con el mensaje en `arg1` (opcional) y un temporal en `res`. El tipo del temporal es desconocido (`?`) hasta ejecutar.

#### Arreglos
- Literal `[a, b, c]` → `newarray 3` + un `[]=` por elemento.
- Acceso `$a[i][j]` → `t = $a[i]` y luego se usa `t` con el último índice, para poder leer o escribir.
- `agregar`, `quitar` y `longitud` → `append`, `remove`, `length`.

### 4.6 Qué NO hace
- No genera nada si hay errores léxicos, sintácticos o semánticos.
- No optimiza (es una traducción directa; la optimización es una fase posterior).
- Un `RuntimeException` interno durante la generación se captura y se informa con el mensaje "No se pudo generar el código intermedio", sin tumbar el compilador.

---

## 5. Archivos modificados

### 5.1 `Leaf.jjt` (gramática y `main`)

**a) Nueva fase en `main`.** Tras el análisis semántico, si no hay errores:
1. Se crea el `GeneradorCodigoIntermedio` con el visitante semántico (que ahora se guarda en una variable para poder pasárselo).
2. Se genera y se imprime la tabla de cuádruplos.
3. Si se pidió `--guardar`, se escribe el `.cuad`.

**b) Opciones de línea de comandos.** Se leen **todos** los argumentos después del archivo, en cualquier orden:
- `--tres-dir`: agrega la "Forma de tres direcciones" a la salida (y al `.cuad`).
- `--guardar`: escribe `<archivo>.cuad` junto al fuente.

**c) Precedencia lógica corregida.** Antes `Y` y `O` se evaluaban todos al mismo nivel, de izquierda a derecha. Ahora `Y` se agrupa **antes** que `O` (como en cualquier lenguaje). La regla `condicion()` se dividió en `condicion()` (para `O`) y `conjuncion()` (para `Y`). Cada operador genera un nodo nuevo `#Logica(3)` = (izquierda, operador, derecha), igual que `Aritmetica`.
→ *Efecto para el usuario:* `a O b Y c` ahora significa `a O (b Y c)`.

**d) Precedencia aritmética corregida.** Antes todos los operadores aritméticos tenían el mismo nivel. Ahora, de menor a mayor precedencia:
1. suma y resta (`+ -`)
2. multiplicación, división y módulo (`* / %`)
3. potencia y raíz enésima (`^ -/`), que asocian **a la derecha**

Se crearon las reglas `termino_mult()` y `factor_pot()`, y el operador se dividió en `op_suma()`, `op_mult()` y `op_pot()` (y `op_y()` / `op_o()` para lo lógico).
→ *Efecto para el usuario:* `2 + 3 * 4` ahora da 14 y no 20.

**e) Nodo `#Defecto`.** Las instrucciones del `defecto` de un `segun` ahora cuelgan de su propio nodo (`caso_defecto()`); antes quedaban sueltas bajo `Segun` y el generador no podía distinguirlas.

**f) Corrección menor:** el mensaje "structure huérfana" → "estructura huérfana".

> Nota técnica: los nuevos nodos (`ASTLogica`, `ASTDefecto`) cambian la interfaz `LeafVisitor`. Cualquier clase que implemente `LeafVisitor` directamente (por ejemplo, algo de la IDE) deberá agregar esos métodos o heredar de `LeafDefaultVisitor`.

### 5.2 `VisitanteSemantico.java` (análisis semántico)

**a) Soporte para el generador.**
- Mapa `simbolosPorNodo` (nodo → símbolo) y método público `simboloDe(Node)`: la fase semántica registra a qué declaración se refiere cada variable, función, parámetro y variable de `para`.
- Ahora se visitan los **índices de arreglos**, los **elementos de literales de arreglo** y el argumento de `longitud` (antes no se revisaban, así que una variable fantasma ahí pasaba sin error).

**b) Validación de `romper` y `continuar`.** Contadores `profundidadCiclo` y `profundidadSegun`:
- `romper` fuera de un ciclo o de un `segun` → error.
- `continuar` fuera de un ciclo → error.

**c) Validación de `Y` / `O`.** Nuevo `visit(ASTLogica)`: ambos lados deben ser lógicos.

**d) Tipado de expresiones que antes devolvían `null`.** Antes `Negacion`, `Raiz`, `Not` e `ingresar()` no tenían tipo, y una operación que los contenía terminaba en `"error"` **sin reportar nada**, ocultando incompatibilidades. Ahora:

| Expresión | Tipo | Error si… |
|---|---|---|
| `-x` | el de `x` | `x` es texto o lógico |
| `-/ x` | `decimal` | `x` no es numérico |
| `NO x` | `logico` | `x` no es lógico |
| `ingresar(...)` | `dinamico` | — |

El tipo `dinamico` significa "se conoce al ejecutar". Se acepta en cualquier destino (variables, argumentos, retornos), en comparaciones y en `Y`/`O`. En una operación aritmética con un entero, el resultado también es `dinamico`.

**e) Condiciones lógicas obligatorias.** `si`, `sino si`, `mientras` y `hacer mientras` ahora exigen que la condición sea lógica (o `dinamico`). Antes `si ($n)` con `$n` entero pasaba como válido.

**e2) División de enteros a entero.** El visitante registra (`divisionesEnteras`) los nodos `a / b` con ambos operandos enteros y expone `esDivisionEnteraSimple(Node)`. La compatibilidad de tipos usa el nuevo `compatibleCon(esperado, recibido, nodo)`, que acepta `decimal` donde se espera `entero` solo para ese caso (declaración, asignación, elemento de arreglo, argumento y `retornar`). El generador consulta el mismo método para emitir `a_entero`.

**f) Corrección de ámbitos** (hallazgos 9a–9d):
- El cuerpo de una función y el de un `para` **comparten el scope de su encabezado** (método `visitarCuerpoSinScope`). Antes abrían un segundo scope con el mismo nombre.
  - Consecuencia: ya **no se puede redefinir un parámetro** dentro de su función ni la **variable del `para`** dentro de su ciclo (ahora da error de "ya declarado").
- Los bloques internos (`si`, `mientras`, etc.) se llaman `Bloque` en el rastreo, en vez de repetir el nombre del ámbito (antes un `si` global creaba un scope llamado "Global").
- `definir` de una función usa `tabla.entrarFuncion(nombre)`.

### 5.3 `TablaSimbolos.java`

- **Rastreo para la IDE:** lista `historial` de `EventoTabla` (ENTRAR, SALIR, INSERTAR, BUSCAR). No afecta al compilador; la IDE lo usa para mostrar la tabla paso a paso. Requiere **Java 16+** (usa `record`).
- **`entrarFuncion(nombre)`:** abre el scope de una función, reinicia el offset local y registra que estamos dentro de esa función.
- **`Simbolo.ambito`** ahora es siempre *la función dueña* o `"Global"`, sin importar cuántos bloques haya en medio.
- **Espacio de offsets:** se decide por *estar dentro de una función* (offset local) o no (offset global). Antes se decidía comparando el nombre del scope con `"Global"`, y por eso un `para` en el programa principal usaba el espacio local y sus variables **colisionaban** con las globales (mismo offset 0).
- **Una función ocupa 0 bytes de datos.** Antes consumía el tamaño de su tipo de retorno y desplazaba los offsets de las variables globales siguientes.
- Tamaños: `logico` 1, `entero` 4, `decimal` 8, `texto` 8, `arreglo` 8, `vacio` y funciones 0.

### 5.4 `Simbolo.java`
Sin cambios de contenido (solo se movió a `src/generado/`).

### 5.5 Cambio de estructura del repositorio y `.gitignore`

```
C:\Leaf
├── .gitignore
├── README.md
└── src/
    ├── Leaf.jjt
    ├── generado/        ← salida de JavaCC (ignorada) + archivos hechos a mano
    └── pruebas/         ← programas de prueba (versionados)
```

- `src/` es la carpeta de código fuente que usa IntelliJ; de ahí cuelgan los paquetes (`generado`).
- El `.gitignore` excluye `*.class`, `*.cuad`, lo que genera JavaCC/JJTree y la configuración local (`.idea`, `.claude`, `CLAUDE.md`). Solo se versionan en `src/generado/` los cinco archivos escritos a mano: `Simbolo`, `TablaSimbolos`, `VisitanteSemantico`, `Cuadrupla` y `GeneradorCodigoIntermedio`.
- Quien clone el repositorio debe **regenerar y compilar** (ver sección 7).

---

## 6. Para el MANUAL DE USUARIO

### 6.1 Uso desde la línea de comandos
Desde `src`:
```
java -cp . generado.Leaf <archivo.txt> [--guardar] [--tres-dir]
```

| Comando | Resultado |
|---|---|
| `java -cp . generado.Leaf pruebas\ParOImpar.txt` | Análisis + tabla de cuádruplos en consola. |
| `... --tres-dir` | Además, la forma de tres direcciones (depuración). |
| `... --guardar` | Además, crea `pruebas\ParOImpar.cuad` con la tabla. |
| `... --guardar --tres-dir` | El `.cuad` incluye también la forma de tres direcciones. |

Notas:
- Las opciones van después del archivo y en cualquier orden.
- Si el programa tiene errores, solo se muestra la lista de errores: no se genera código intermedio ni archivo.
- El `.cuad` se **sobrescribe** en cada compilación (mismo nombre que el fuente, con extensión `.cuad`).
- Si la consola muestra mal los acentos: `java "-Dstdout.encoding=UTF-8" -cp . generado.Leaf archivo.txt`.

### 6.2 Cómo leer la tabla
La tabla tiene **cuatro columnas**: `operación | arg1 | arg2 | resultado` (sin columna de índice). Cada fila es una operación `resultado = arg1 operación arg2`. Una expresión compleja se parte en varias filas con temporales (`t1`, `t2`…). `label`, `goto`, `ifFalse` e `if` implementan `si` y los ciclos. `main` marca el inicio y `halt` el fin; las funciones aparecen después de `halt`.

Ejemplo (`ParOImpar.txt`):
```
operación | arg1                      | arg2 | resultado
main      |                           |      | main
=         | 14.3                      |      | $numero
print     | "Evaluando si el numero " |      |
print     | $numero                   |      |
print     | " es par o impar..."      | fin  |
%         | $numero                   | 2.0  | t1
==        | t1                        | 0.0  | t2
ifFalse   | t2                        |      | L2
print     | "El numero es par."       | fin  |
goto      |                           |      | L1
label     |                           |      | L2
print     | "El numero es impar."     | fin  |
label     |                           |      | L1
halt      |                           |      |
```

### 6.3 Cambios visibles en el lenguaje
1. **Precedencia de operadores** (secciones 5.1 c y d): `* / %` antes que `+ -`; `^` y `-/` antes que todos y asocian a la derecha; `Y` antes que `O`.
2. **Las condiciones deben ser lógicas** en `si`, `sino si`, `mientras` y `hacer mientras`.
3. **No se puede redefinir un parámetro** dentro de su función, ni la variable del `para` dentro de su ciclo.
4. **`romper` y `continuar`** solo son válidos dentro de un ciclo (`romper` también dentro de un `segun`).
5. **Más chequeo de tipos:** negación, raíz y `NO` validan su operando; `ingresar()` es de tipo dinámico (se acepta donde se pida cualquier tipo).
6. **`mostrar`** evalúa todos sus argumentos antes de imprimir.
7. Una raíz (`-/ x`) produce `decimal`: guardarla en una variable `entero` es un error de tipos.
8. **División de enteros en un entero:** `$temp con valor de $temp / 10.` es válido con `$temp` entero; el resultado se trunca hacia cero (`7 / 2` → `3`, `-7 / 2` → `-3`). Solo vale para `a / b` simple con ambos lados enteros; `$a / $b / 2` o `($a / $b) * 2` siguen siendo error al guardarse en un entero. Si el destino es `decimal`, la división conserva sus decimales (`7 / 2` → `3.5`).

### 6.4 Mensajes de error semánticos nuevos
| Mensaje | Cuándo |
|---|---|
| `La condición del "si" debe ser lógica, pero es de tipo 'entero'.` | Condición no lógica. También para `"sino si"`, `"mientras"` y `"hacer mientras"`. |
| `Operación inválida: no se puede negar un valor de tipo 'texto'. Solo se permiten números.` | `-x` con texto o lógico. |
| `Operación inválida: no se puede sacar la raíz de un valor de tipo 'texto'. Solo se permiten números.` | `-/ x` con texto o lógico. |
| `Operación lógica inválida: 'no' requiere un valor lógico, pero recibió 'entero'.` | `NO x` con `x` no lógico. |
| `Operación lógica inválida: 'Y' y 'O' requieren valores lógicos, pero recibió '...' y '...'.` | `Y`/`O` con operandos no lógicos. |
| `"romper" solo puede usarse dentro de un ciclo o de un "segun".` | `romper` fuera de lugar. |
| `"continuar" solo puede usarse dentro de un ciclo.` | `continuar` fuera de lugar. |
| `El identificador '$x' ya fue declarado previamente en este ámbito como parametro.` | Redefinir un parámetro (o la variable del `para`) en el cuerpo. |

---

## 7. Para el MANUAL DE SISTEMA

### 7.1 Requisitos y compilación
- **Java 16 o superior** (por el `record` de `TablaSimbolos.EventoTabla`). Probado con Java 26.
- JavaCC 7.0.13 (`jjtree` y `javacc` en el PATH).

Desde `src`:
```
jjtree Leaf.jjt
javacc generado/Leaf.jj
javac -encoding UTF-8 generado\*.java
java -cp . generado.Leaf archivo [--guardar] [--tres-dir]
```
`javacc` muestra 4 advertencias de conflicto de elección (`sino si`, `sino`, `caso`, `defecto`). Son conocidas y anteriores a esta fase (JavaCC elige por defecto la primera alternativa que coincide).

### 7.2 Dónde está cada cosa
| Archivo | Escrito a mano | Rol |
|---|---|---|
| `src/Leaf.jjt` | Sí | Gramática (JJTree/JavaCC) y `main`. |
| `src/generado/Leaf.jj`, `Leaf.java`, `AST*.java`, `Token*.java`, … | No (generados) | Parser y nodos. Ignorados por git. |
| `src/generado/Simbolo.java` | Sí | Entrada de la tabla de símbolos. |
| `src/generado/TablaSimbolos.java` | Sí | Pila de ámbitos, offsets y rastreo para la IDE. |
| `src/generado/VisitanteSemantico.java` | Sí | Análisis semántico. |
| `src/generado/Cuadrupla.java` | Sí | **Nuevo.** Instrucción intermedia. |
| `src/generado/GeneradorCodigoIntermedio.java` | Sí | **Nuevo.** AST → `List<Cuadrupla>`. |
| `src/pruebas/*.txt` | Sí | Programas de prueba. |

### 7.3 Flujo de datos entre componentes
1. El parser construye el AST (`ASTPrograma` y descendientes).
2. `VisitanteSemantico` recorre el AST, llena `TablaSimbolos`, reporta errores en `Leaf.errores` y deja `simbolosPorNodo`.
3. Si `Leaf.errores` está vacío, `GeneradorCodigoIntermedio` recibe el AST y el visitante, y llena su `List<Cuadrupla>`.
4. `main` imprime/guarda según las opciones.

El generador consume del semántico:
- `simboloDe(nodo)`: la declaración a la que se refiere un nodo (de ahí salen tipo, ámbito y nombre único).
- `Simbolo.tipo`, `Simbolo.ambito`, `Simbolo.tipoRetorno`, `Simbolo.tiposParametros`.

### 7.4 Integración con la IDE (pendiente)
- `ServicioCompilacion` debe llamar al nuevo generador.
- Los golden tests de la IDE deben regenerarse: cambió la precedencia, los ámbitos y los nombres de variables del `para` (por ejemplo `$i@Para` pasó a `$i@funcion` o `$i`).
- Verificar que ninguna clase implemente `LeafVisitor` directamente (hay nuevos nodos `ASTLogica` y `ASTDefecto`).

### 7.5 Verificación realizada
Para cada programa de `src/pruebas/` se revisó:
- que toda etiqueta usada exista y ninguna esté repetida;
- que cada temporal se asigne antes de usarse;
- que cada `mostrar` termine con `fin`;
- que, con errores, no se genere ninguna cuádrupla.

Resultado: las 12 pruebas válidas generan código correcto en su forma; las 5 pruebas con errores (`codigo`, `test`, `test-completo`, `prueba-total-errores`, `prueba-total-semantico`) no generan código. Además se comparó la salida antes y después de cada cambio de ámbitos y tipos: misma estructura de cuádruplos y mismos errores.

> Esta revisión comprueba la **forma** del código, no que los valores resultantes sean correctos. Eso lo validará el intérprete de cuádruplos (pendiente).

---

## 8. Limitaciones y pendientes conocidos

- **Intérprete de cuádruplos** de prueba, para validar resultados de ejecución.
- **Integración con la IDE** (7.4).
- **Optimización** y **generación de código objeto** (fases siguientes).
- `mostrar($x, f())`: si `f` modifica la variable global `$x`, el `print` de `$x` verá el valor ya modificado (se guarda el nombre, no una copia). Caso poco común, no resuelto.
- Los offsets de variables en bloques internos no se reutilizan al salir del bloque (solo crecen).
- `ingresar()` devuelve tipo `dinamico`: no se valida el tipo de lo que el usuario escribe hasta ejecutar.
- El manual del lenguaje no dice si `mostrar` agrega salto de línea; se adoptó el comportamiento actual (la marca `fin` equivale a "aquí termina el `mostrar`").
- Las advertencias de JavaCC (4) siguen presentes y son esperadas.
- Las pruebas `codigo.txt`, `test.txt` y `test-completo.txt` contienen errores y no sirven para probar la generación hasta corregirlas.
