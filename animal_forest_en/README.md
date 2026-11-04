# Animal Forest (N64) en inglés, recompilado

Traducción al inglés de **どうぶつの森 / Doubutsu no Mori** (Nintendo 64, 2001,
solo salió en Japón), hecha **recompilando el juego desde su código fuente**,
no parcheando el ROM a mano.

## Por qué recompilar

La traducción que hubo, la del AF Project (Zoinkity, último parche
2-12-2010), parcheaba el ensamblador del ROM directamente: alargaba cajas
de texto, nombres e ítems sin poder cambiar el tamaño de los búferes donde
cae ese texto, porque eso movería todo lo que hay detrás en memoria. Su
propia documentación lo llama "a very nasty, buggy, terrifying
work-in-progress", y tiene un cuelgue conocido más adelante en el juego.

Hoy existe [zeldaret/af](https://github.com/zeldaret/af), el decomp del
juego: saca código y datos de tu ROM, recompila el código en C y produce un
ROM **idéntico byte a byte**. Con eso, agrandar un búfer es cambiar una
constante y el enlazador reacomoda el resto. Y el decomp de Animal Crossing
de GameCube ([ac-decomp](https://github.com/ACreTeam/ac-decomp)) tiene en C
el sistema de mensajes que en el de N64 sigue en ensamblador, ya adaptado
al inglés por la propia Nintendo: es el mapa.

El parche es solo la forma de entregarlo: un `.bps` con la diferencia entre
tu ROM original y el que compilamos. **Este repositorio no contiene nada de
Nintendo**: ni ROM, ni imagen de disco, ni texto, ni gráficos sacados de
ellos.

## Qué hace falta

- Tu volcado de Doubutsu no Mori (NUS-NAFJ-JPN, 16 MiB, md5
  `a4f7c57c180297b2e7ba5a5feb44fe0b` en orden `.z64`). Da igual el orden de
  bytes del archivo: `.z64`, `.v64` o `.n64`, `tools/rom.py` lo normaliza.
- Ubuntu/Debian: `make git build-essential clang binutils-mips-linux-gnu
  python3 python3-venv cmake libsdl2-dev libpng-dev zlib1g-dev`.
- Más adelante (fases 4 y 5): el disco de Animal Crossing USA (GAFE01 rev 0)
  para el guion oficial y los gráficos occidentales. Se usa en tu máquina y
  nunca entra al repositorio.

## Uso

```sh
make ROM=ruta/a/tu/volcado        # compila el ROM y lo verifica
make emu-check                    # lo arranca en el emulador sin ventana
make rom-en                       # la traducción: mismo código + decomp/changes.patch,
                                  # con code libre de crecer (build/animalforest-en.z64)
make route-en                     # la juega en el emulador hasta las casas
make script ISO=ruta/al/disco.iso # el guion oficial de GameCube: extrae los bancos de texto
                                  # y los compara mensaje a mensaje con el del cartucho, y su
                                  # fuente (build/gc/)
make board-check-en               # abre cartas en el tablero y mide sus líneas en pantalla
make write-check-en               # escribe una carta con el dial y la mide en pantalla
make menus-check-en               # abre la ventana del nombre, la pregunta al terminar una carta y
                                  # la libreta, y mide sus cadenas; busca las etiquetas en memoria
make test                         # tests de las herramientas, sin ROM
make nafe NAFE_UPS=.../NAFE-WIP-2_12_2010.ups   # el parche de 2010: para estudiarlo, y
                                  # sus etiquetas en inglés para make rom-en (opcional)
```

`ROM=` hace falta una sola vez: queda normalizado en `build/baserom.z64`.
Si tienes el parche de 2010, `make nafe NAFE_UPS=...` antes de `make
rom-en` pone en los menús las etiquetas que el AF Project ya dibujó en
inglés; sin él quedan las del cartucho.

## Estado

**Fase 0 — cimientos: hecha.**

- `make` trae zeldaret/af en el commit fijado (`decomp/AF_REV`), le aplica
  nuestros parches (`decomp/patches/`, todavía ninguno), extrae, compila y
  comprime. Hoy el resultado es **idéntico al cartucho original**, y
  `make verify` lo comprueba con `cmp`: es la prueba de que la cadena de
  herramientas funciona antes de tocar nada.

**Fase 1 — banco de pruebas: hecha.**

- `emu/`: una N64 sin ventana manejada desde Python, frame a frame
  (`emu/n64emu.py`). mupen64plus-core con su depurador (puntos de
  vigilancia sobre memoria), el RSP cxd4, y angrylion-rdp-plus (el RDP por
  software, exacto al píxel) con su salida OpenGL reemplazada por una copia
  en memoria. Un plugin de mando propio pulsa botones en el frame exacto.
  Todo fijado a commits concretos y compilado por `emu/build.sh`.
- Es **determinista**: con el reloj del cartucho fijado (arranca el día del
  lanzamiento, 14-4-2001, y avanza con el tiempo emulado), dos ejecuciones
  dan la misma imagen byte a byte, y cargar un savestate repite exactamente
  la misma partida. Para eso hubo que parchear el emulador en tres sitios
  (`emu/patches/`): el reloj, la semilla con que se formatea el Controller
  Pak, y el ruido del tramado gamma del VI. Se cuenta en
  `reference/NOTES.md`.
- `make emu-check`: arranca el ROM, comprueba que llega al título y que es
  la misma imagen que dibuja el original, que START lleva a la intro, y que
  un savestate cargado repite los mismos frames, píxeles y RAM.
- `make nafe` reconstruye el ROM del AF Project desde tu volcado y lo
  verifica contra la huella que tiene registrada mupen64plus. Arranca y se
  juega: la intro, los nombres, la llegada, Nook y las casas funcionan
  (`tools/route_newgame.py` hace ese recorrido solo). Su cuelgue, según se
  cuenta, aparece en momentos distintos y a veces tras días de juego: no
  se busca jugando sino comparando. `tools/nafe_diff.py` lista, archivo por
  archivo y función por función, todo lo que cambió el parche
  (`reference/NOTES.md`).

**Fase 2 — el sistema de mensajes en C: hecha.** `m_msg_main` (322
funciones) es un archivo C en `decomp/matching.patch`; 279 de las 283 que
están en C compilan a los bytes exactos del cartucho y las otras 4 quedan
tras `NON_MATCHING` con su ensamblador original. El tamaño real de cada
búfer y lo que el parche de 2010 corrompió están en `reference/NOTES.md`.

**Fase 3 — que el ROM tolere cambios de tamaño: hecha la regla, probada.**
`make rom-en` compila la traducción con `code` al final del ROM (libre de
crecer) y sin mover en la RAM nada de lo que el cartucho colocó: lo que
crece, o es nuevo, va a una región `code_en` detrás de `buffers`, cargada
con el resto de `code`. Antes de comprimir, tres comprobaciones prueban la
disposición contra el mapa del cartucho (`tools/af_shiftcheck.py`,
`af_anchors.py`, `af_luicheck.py`). Lo que las motivó: una dirección que el
desensamblado atribuía a la función equivocada (en `ovl_Birth_Control`)
dejaba a Rover plantado en la puerta del tren en cuanto los datos se
movían; y la primera regla (mover todo el bloque de datos 64 KB) pintaba
de negro o de ruido rosa el suelo de todos los pueblos menos el del
recorrido, porque los modelos del campo del propio cartucho apuntan a
búferes de `code` por su dirección de RAM (2.348 cargas de textura en 204
ficheros; `reference/NOTES.md`, "Shiftability").

**Fase 4 — el guion oficial de GameCube: en curso.** `make script` lee del
disco del usuario (sin ventana, sin copiar nada al repositorio) los bancos
de texto de Animal Crossing y los compara con el del cartucho
(`tools/gciso.py`, `msgbank.py`, `af_align.py`). Medido: GameCube conservó
la numeración de mensajes de Doubutsu no Mori, y de los 11.752 mensajes del
N64, el 73,6 % tiene su texto oficial en inglés tal cual (mismos códigos de
control), el 18,1 % lo tiene con retoques, el 6 % no lo tiene (mensajes de
depuración y líneas que GameCube quitó) y el 2,2 % cambió de sentido
(`reference/NOTES.md`, "The GameCube script"). `tools/af_text.py` compila el
banco inglés (el oficial donde lo hay, el japonés con una nota `TODO`
donde no) a los dos archivos que lee el juego, comprobando antes lo que el
sistema de mensajes del N64 admite (caracteres, códigos, tamaño, líneas), y
`make rom-en` los enlaza como segmentos al final del ROM con sus entradas
de dmadata (`decomp/changes.patch`): los mensajes, las respuestas de las
elecciones, las cadenas (nombres de bichos y peces, muletillas, fechas) y
los nombres de los animales (6 letras en el N64: 38 de 232 quedan cortados
por ahora). El ROM en inglés ya habla: K.K. y Rover en la intro, con
letra proporcional y los glifos latinos de GameCube con sus anchuras
(`make script` los lee del disco del usuario, del módulo `foresta.rel` y
su mapa `foresta.map`; `tools/af_font.py` los pone en la fuente del
cartucho al compilar; el `m_font.c` del build en inglés los dibuja y los
mide así, y el japonés sigue igual: `reference/NOTES.md`, "The font in
the en build"). Sin el disco, el build usa los glifos latinos del propio
cartucho, movidos al borde izquierdo de su celda de 12 px.
Nuestras traducciones de lo que GameCube no tiene van en `script/`.

Las líneas que no caben en los 192 px de la ventana (un 0,1 % con los
glifos de GameCube; un 3,9 % con los del cartucho) se recolocan al
compilar (`af_text.py --font`): las últimas palabras pasan a la línea
siguiente, o la página se rellena de nuevo, o se parte tras la última
frase que cabe.

Las cartas: el ROM guarda cada carta oficial entera (la partida
comprimida y su extensión: "The compressed save", "The letters in the en
build"), y el tablero de cartas (`board_ovl`, ya en C e idéntico al
cartucho) la muestra entera al leerla: el encabezado con el nombre, el
cuerpo partido a los 192 px del papel en sus seis líneas, como en
GameCube, y la despedida alineada a la derecha. `make board-check-en`
abre cartas en el emulador y mide cada línea en la imagen contra un
modelo del tablero ("The letter board in the en build").

Escribir cartas: el editor de texto (el programa 10 de los submenús, ya
en C donde hacía falta e idéntico al cartucho) coloca la carta igual que
el tablero al leerla: las líneas se parten a los 192 px del papel tras el
último espacio que cabe, el cursor va entre las letras a su anchura y
arriba/abajo conservan su posición; una carta nueva empieza con "To " y
"from " como en GameCube. El cuerpo tiene los 192 bytes de GameCube (sus
seis líneas enteras): al terminar la carta, los primeros 96 van en la
carta del cartucho y el resto en la extensión de la partida, como el de
las cartas oficiales. `make write-check-en` escribe cartas con el dial en
el emulador, mide líneas, cursor y marca de fin en la imagen, las termina
y las vuelve a leer enteras ("Writing a letter in the en build").

**Fase 5 — lo occidental de GameCube: en curso.** Parte de lo que los
menús muestran en japonés no son texturas ni está en los bancos de texto:
son cadenas en los datos de cada menú, que se dibujan con la fuente (los
títulos de la ventana del nombre, los globos de la libreta de direcciones,
la pregunta al terminar una carta, las frases del haniwa...). GameCube
guarda las mismas cadenas en su código, en inglés. `make script` las lee
del disco (`tools/af_ui.py`) y `make rom-en` las pone en el ROM; sin el
disco quedan las del cartucho. Primero, la ventana del nombre: sus cinco
títulos son los de GameCube y la palabra tras el destino desaparece, como
en GameCube. Después, la pregunta al terminar una carta y sus respuestas, y
el globo de la libreta de direcciones (centrado en el globo del cartucho y
estrechado si no cabe). Para el resto de menús, que dibujan sus cadenas desde
funciones grandes, el build en inglés cambia la cadena donde la fuente la
dibuja: la reconoce por sus bytes (sacados de tu volcado) y pone la inglesa
en la caja de la original. Así ya están en inglés la cantidad del banco, el
cumpleaños, el reloj (con la fecha año-mes-día y la hora en sus casillas, que
el build en inglés tenía rotas), la pregunta de la melodía y el título de las
notas del Controller Pak; y después los avisos del inventario, los mensajes
del Controller Pak (estos dos, nuestros: GameCube parte esos avisos de otra
manera y no tiene Controller Pak) y la tienda del haniwa, que escribe su frase
letra a letra en un búfer: la inglesa se escribe al mismo ritmo, y en el
precio las cifras del menú van en el hueco de la frase inglesa ("The menus'
strings where the font draws them").
`make menus-check-en` abre esas ventanas y mide cada cadena en la imagen
contra el modelo de la fuente ("The menus' own words", "The edit-end check
and the address book").
Las texturas de los menús compartidos son solo marcos e iconos; las
etiquetas del dial sí son texturas, pero GameCube no tiene dial (escribe
con un teclado).

Las etiquetas que el parche de 2010 del AF Project (Zoinkity) ya dibujó
en inglés se aprovechan: 42 texturas del dial, el inventario, el mapa, el
tablón, el banco, la melodía del pueblo, las notas del Controller Pak, el
catálogo y gameplay_keep, cada una en su sitio, formato y tamaño.
`tools/af_nafe_gfx.py` las toma al compilar de tu copia del parche (`make
nafe`) y nada de él entra al repositorio; `make menus-check-en` abre esos
menús y comprueba cada etiqueta en memoria ("The 2010 patch's labels").
El logo del título de ese parche cambia también el código del actor que
lo dibuja, que sigue en ensamblador: queda para después.

Lo que viene: las cadenas que quedan en otros menús, los nombres de los objetos
(un archivo propio de 10 bytes por nombre), el tablón de anuncios (parte
sus líneas por número de letras, no por anchura), el logo del título del
parche de 2010, las texturas occidentales de GameCube donde las haya
(letreros) y traducir ese 6 %.

## Dónde está cada cosa

- `Makefile` — todo lo de arriba.
- `decomp/` — `AF_REV` (el commit de zeldaret/af), `matching.patch` (lo
  descompilado: reconstruye el cartucho) y `changes.patch` (la traducción,
  encima).
- `emu/` — el emulador: `build.sh`, `n64emu.py`, `headless_output.c`,
  `input_headless.c`, `patches/`, `afplay.py` (qué hay en pantalla y
  cómo responder: diálogos, menús, el dial de nombres), `symbols.py` (nombres de funciones y
  variables desde el mapa del decomp) y `contact.py` (varias capturas en
  una hoja).
- `tools/` — `rom.py` (orden de bytes, identidad, CRC de la cabecera),
  `ups.py`, `nafe.py` y `nafe_diff.py` (el parche de 2010: aplicarlo y
  ver qué cambió), `route_newgame.py` (partida nueva hasta las casas),
  `af_match.py`/`af_try.py`/`af_wrap.py`/`af_bench.py` (igualar funciones),
  `af_relink.py`/`af_relsyms.py`/`af_ranges.py`/`af_dmaorder.py` (la
  disposición del ROM traducido), `af_shiftcheck.py`/`af_anchors.py`/
  `af_luicheck.py` (sus pruebas), `gciso.py`/`msgbank.py`/`af_align.py`
  (el disco de GameCube, los bancos de texto de ambos juegos y su
  correspondencia), `af_text.py` (el banco inglés: borrador, comprobador y
  compilador), `af_font.py` (la fuente del build en inglés), `af_ui.py`
  (las cadenas de los menús), `af_nafe_gfx.py` (las etiquetas del parche
  de 2010, de tu copia), `af_dumpdata.py` (los datos del cartucho que
  un archivo decompilado incluye, sacados de tu volcado al extraer) y
  `af_ovlreloc.py` (la tabla de reubicaciones de un overlay que cambia).
- `script/` — nuestro texto: lo que no tiene versión oficial.
- `tests/` — `test_tools.py` (`make test`), `emu_check.py`
  (`make emu-check`) y las pruebas del build en inglés en el emulador:
  `emu_save.py` (la partida), `emu_letters.py` (las cartas),
  `emu_board.py` (el tablero de cartas), `emu_write.py` (escribir una
  carta) y `emu_menus.py` (las cadenas de los menús).
- `reference/NOTES.md` — lo verificado, con su fuente.
