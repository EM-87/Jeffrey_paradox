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
make bps                          # el entregable: build/animalforest-en.bps, de tu volcado
                                  # (en orden .z64) al ROM en inglés, comprobado aplicándolo
```

El `.bps` se aplica con cualquier parcheador BPS (Flips, beat...) o con
`python3 tools/bps.py apply build/baserom.z64 build/animalforest-en.bps
salida.z64`, siempre sobre el volcado en orden `.z64` (`build/baserom.z64`
ya lo está); el parche comprueba el CRC32 de la entrada, de la salida y el
suyo propio. Lleva dentro lo que tu compilación sacó de tus copias (el guion
y la fuente de GameCube, lo del parche de 2010): es para tu uso, como el
ROM del que sale.

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
Nuestras traducciones de lo que GameCube no tiene van en `script/`
(los mensajes, en `script/message/`).

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
precio las cifras del menú van en el hueco de la frase inglesa; y las
acciones de la etiqueta que se abre sobre un objeto (las de GameCube) y su
pregunta antes de tirarlo; y las etiquetas del mapa (tienda, comisaría,
correos, santuario, estación, vertedero) y la palabra de las casas vacías
("The menus' strings where the font draws them"); y el nombre de una carta en
su etiqueta, que el build en inglés dibuja en el orden de GameCube (qué es y
para quién, el destinatario, de quién) desde code, porque el overlay no puede
crecer (para eso, el overlay pasó a C con su función en C, idéntica en
instrucciones pero con un marco de pila 8 bytes mayor: queda como
NON_MATCHING, "The letter's tag"); y la ventana del precio que se pone a un
objeto (las palabras de GameCube, la de las bayas tras las cifras); y en el
tablón de anuncios el número de la nota y su fecha, cada cifra en su casilla
del cartucho, donde la textura del tablón tiene sus barras.
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
El logo del título de ese parche también se aprovecha: el nombre del juego
en inglés, en dos líneas, donde el cartucho tiene el logo japonés. La
función del actor que dibuja sus piezas pasó antes a C, idéntica al
cartucho; `tools/af_nafe_logo.py` toma al compilar de tu copia del parche
las piezas (vértices, listas y texturas), los datos del actor que las
colocan y colorean y los cuatro valores de su código que el parche cambió,
comprobando antes que lo que no toma no se ve. El aviso que sale en el
título cuando no hay mando en el primer puerto es nuestro: su función
pasó a C, idéntica al cartucho, y en el build en inglés dibuja sus tres
líneas por la línea de la fuente, que las sustituye por nuestro inglés,
centrado; `make menus-check-en` lo fuerza en el título y mide cada línea
("The title's logo").

Los mensajes que el guion de GameCube no tiene (909 seguían en japonés)
se toman, donde se puede sin riesgo, del inglés de esa misma traducción
de 2010 del AF Project (Zoinkity): `tools/af_nafe_text.py` los lee al
compilar de tu copia del parche y nada de ellos entra al repositorio. Un
mensaje entra solo si los códigos que mueven el juego (cómo acaba, las
elecciones, el mensaje que sigue) son los del cartucho. Las órdenes de
expresión del personaje que habla, que el parche movió o cambió en 46,
se reponen: las del cartucho, en su orden, en el sitio del texto inglés
que les corresponde. De los 175 que el parche tiene en inglés entran
130; en los demás el parche cambió algo de lo que el juego hace. El
mérito de esas frases es del AF Project ("The 2010 patch's
messages").

Los 45 que el parche de 2010 tiene en inglés pero que no entran por sus
códigos los hemos traducido nosotros, del japonés del cartucho
(`script/message/ours.txt`): conservan los códigos del cartucho que
mueven el juego, y solo las pausas, los colores y los saltos de página
se colocan para el inglés. Al compilar, `af_text.py` rechaza cualquier
mensaje superpuesto (el del parche de 2010 o el nuestro) cuyos códigos
no sean los del cartucho ("Our own messages"). Los 717 mensajes que
seguían en japonés también son traducción nuestra, en `script/message/`:
etiquetas de los desarrolladores y mensajes de prueba, una versión
antigua de los saludos de los vecinos (con una personalidad de Kansai
que el juego final no tiene), de la tienda de Tom Nook y del gyroide de
la casa, la oficina de correos con la Controller Pak, frases de cartas y
algunas fiestas. Ya no queda ningún mensaje en japonés: de los 10.847
con texto, 10.813 están en inglés y 34 no tienen letras (notas
musicales, números o códigos). `make messages-check-en` muestra en la
ventana de diálogo los que no necesitan quien hable y mide cada línea
contra el modelo de la fuente, y reproduce hasta el final los que llevan
órdenes de gesto para comprobar que son las del cartucho.

Las cadenas y las cartas que seguían en japonés también son nuestras:
423 cadenas en `script/string/` (una lista antigua de 200 nombres de
animales que el juego no lee, muletillas que no dice ningún animal,
huecos de reserva, dos contadores de la tienda, las unidades, los
créditos del personal y dos frases de penitencia de Resetti) y cartas en
`script/mail_body/` y `script/vmail_a/`. Al compararlas una a una con el
cartucho salieron fallos que se veían en el juego: el nombre del pueblo
llevaba detrás la palabra japonesa «pueblo» (ahora va solo, como en
GameCube); el tamaño de los peces salía en pulgadas con el número en
centímetros; los créditos eran la lista de GameCube (otras personas)
cortada a 15 letras, y ahora son los del cartucho línea a línea; y en
los avisos de eventos del tablón, GameCube quitó fiestas japonesas,
puso las suyas y movió el resto: 17 se toman de su número correcto
(`af_text.LETTER_REMAP`, solo números) y 10 son nuestros (Hinamatsuri,
White Day, la previsión de los cerezos, el Día del Niño, los fuegos
artificiales de los sábados de agosto...), igual que seis cartas de
White Day que decían San Valentín. Ya no queda japonés en ningún banco
de texto ("The strings and letters still in Japanese").

Los nombres de los objetos son los de GameCube, leídos de tu disco al
compilar (`tools/af_items.py`): de los 1.703 del cartucho, 1.696 tienen
nombre en GameCube y 7 son nuestros (`script/items.txt`). Los de GameCube
llegan a 16 letras y el cartucho los copia en búferes de 10 bytes en unos 27
overlays que no pueden crecer, así que el build en inglés escribe ahí una
ficha: una marca, el número del nombre y relleno hasta las casillas que
ocupa el inglés (con eso miden los menús el sitio del nombre). La fuente la
dibuja como el nombre, y los diálogos, las cartas y las elecciones ponen el
nombre en su lugar; los nombres están en un segmento propio del ROM,
leídos por DMA como el cartucho lee los suyos. Como en GameCube, el
nombre entra en un diálogo o una carta con su artículo ("a", "an", "the",
"some") salvo donde el texto lo quita, y con mayúscula donde el texto la
pide: esos dos códigos de GameCube no existen en el cartucho y el banco
inglés los lleva con dos números que ninguno de los dos juegos usa
("Item names"). `make menus-check-en` copia nombres como el juego, los mete
en mensajes, elecciones y cartas por el código del juego, y mide en
pantalla el nombre del dinero en el inventario.

El tablón de anuncios guarda notas de 192 bytes, como GameCube. El
cartucho guarda 96 por nota, y el inglés de 110 de las 124 notas del juego
pasa de 96; el build en inglés deja los primeros 96 en la partida y el
resto en la extensión que ya guarda el resto de las cartas, bajo una clave
hecha con la nota y su fecha. El tablón dibuja cada nota entera, por
anchura como el cuerpo de una carta (192 píxeles, seis líneas, cortando
tras el último espacio que cabe), y el editor escribe una nota nueva en el
mismo búfer de 192 bytes que una carta, 32 letras por línea. Las funciones
del tablón que abren el editor, publican la nota y dibujan la página
pasaron antes a C, idénticas al cartucho ("The bulletin board").
`make notice-check-en` publica notas como las publica el juego, pasa las
páginas del tablón y mide cada línea en la imagen contra el modelo, teclea
en el dial una nota de más de 96 bytes, la termina, la lee entera, y la
guarda y la carga con la partida.

Los nombres que no caben en los 6 bytes del cartucho (38 vecinos de 7 u 8
letras y 8 personajes especiales, Tom Nook entre ellos) ya no se cortan:
usan la misma ficha que los objetos. Sus registros van detrás de los de
los objetos; la ficha de cada vecino se escribe en su campo del segmento
de nombres, así que el juego la copia como copiaba el nombre, y la de los
especiales al cargar su cadena. La placa con el nombre del que habla, su
nombre dentro del texto y la cabecera de una carta dirigida a un vecino
ponen el nombre entero ("Names longer than 6"); `make messages-check-en` y
`make board-check-en` lo miden en pantalla.

Lo que viene: las texturas occidentales de GameCube donde las haya
(letreros), las muletillas de los vecinos (4 bytes en el cartucho) y una
pasada de pruebas de todo el texto en el juego.

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
  (las cadenas de los menús), `af_items.py` (los nombres de los objetos,
  de tu disco), `af_nafe_gfx.py`, `af_nafe_logo.py` y `af_nafe_text.py`
  (las etiquetas, el logo del título y los mensajes del parche de 2010, de
  tu copia), `af_dumpdata.py` (los datos del cartucho que
  un archivo decompilado incluye, sacados de tu volcado al extraer) y
  `af_ovlreloc.py` (la tabla de reubicaciones de un overlay que cambia).
- `script/` — nuestro texto: lo que no tiene versión oficial (los
  mensajes en `script/message/`).
- `tests/` — `test_tools.py` (`make test`), `emu_check.py`
  (`make emu-check`) y las pruebas del build en inglés en el emulador:
  `emu_save.py` (la partida), `emu_letters.py` (las cartas),
  `emu_board.py` (el tablero de cartas), `emu_write.py` (escribir una
  carta), `emu_notice.py` (el tablón de anuncios), `emu_messages.py` (los
  mensajes en la ventana de diálogo) y `emu_menus.py` (las cadenas de los
  menús y los nombres de los objetos).
- `reference/NOTES.md` — lo verificado, con su fuente.
