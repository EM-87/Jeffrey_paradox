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
  python3 python3-venv cmake nasm libsdl2-dev libpng-dev zlib1g-dev`.
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
make resetti-check-en             # teclea en el dial las frases que pide Resetti y se las da
                                  # a juzgar a su propio código
make menus-check-en               # abre la ventana del nombre, la pregunta al terminar una carta y
                                  # la libreta, y mide sus cadenas; busca las etiquetas en memoria
make psel-check-en                # enciende con pueblos de 1, 2 y 4 jugadores y lee las preguntas
                                  # del personaje del principio (también en ares si está compilado)
make words-check-en               # las palabras que el cartucho carga en 10 bytes (o 4) y los
                                  # nombres de los personajes especiales, por el código del juego
make reset-check-en               # enciende siete veces la misma partida, guardando o sin guardar:
                                  # las visitas de Resetti por la cuenta del propio juego (y la
                                  # quinta, que asoma en el concierto del sábado)
make memory-check-en              # la memoria libre en un pueblo lleno (4 jugadores, 15 vecinos)
                                  # en las 21 fechas del año, entrando en cada parcela por todos
                                  # sus lados, y dentro de casas, tiendas, tiendas de campaña e iglú;
                                  # y la llegada de un jugador nuevo a ese pueblo
make job-check-en                 # la primera hora de una partida nueva jugada con el mando: el
                                  # trabajo de Nook encargo a encargo, vender, comprar, pagar la
                                  # deuda, guardar y volver a encender
make trip-check-en                # un viaje con Controller Pak: salir en tren con una carta larga
                                  # en el bolsillo, llegar a otro pueblo como visitante y volver
                                  # a casa con la carta entera; y después guardarla en el Controller
                                  # Pak en correos, guardar la partida y recuperarla entera
make ares                         # el segundo emulador, ares, sin ventana (y su aplicación)
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
  nuestro parche de código descompilado (`decomp/matching.patch`), extrae,
  compila y comprime. El resultado es **idéntico al cartucho original**, y
  `make verify` lo comprueba con `cmp`: es la prueba de que la cadena de
  herramientas funciona, y de que el C que hemos escrito es el mismo código,
  antes de cambiar nada. La traducción va aparte, encima
  (`decomp/changes.patch`, solo para la ROM inglesa: `make rom-en`).

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

**Fase 4 — el guion oficial de GameCube: hecha.** `make script` lee del
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
los nombres de los animales (6 letras en el N64; los 38 que en inglés son
más largos van enteros, más abajo). El ROM en inglés ya habla: K.K. y Rover en la intro, con
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

**Fase 5 — lo occidental de GameCube: hecha.** Parte de lo que los
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
de texto ("The strings and letters still in Japanese"). Los créditos y
la talla de los peces se han visto también en pantalla: las 16 páginas
de créditos, dibujadas por la caja naranja como en el concierto de K.K.
(`make menus-check-en`), y la talla en los tres mensajes del torneo de
pesca que la muestran (`make messages-check-en`), cada línea donde la
pone el modelo de la fuente.

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

Las muletillas de los vecinos (4 bytes en el cartucho, guardadas en la
partida) tampoco se cortan ya: 198 de las 215 pasan de 4 letras en inglés.
Usan la misma ficha; la de cada una va en el banco de cadenas en lugar de
sus palabras (esas cadenas solo se cargan como muletillas), así que el
juego la carga y la guarda como antes, y el código de mensajes pone las
palabras al insertarla. El banco de mensajes se maqueta ahora contando el
nombre de quien habla como 9 letras y la muletilla como 11, lo más ancho
que pueden ocupar ("Catchphrases longer than 4"); `make messages-check-en`
lo mide en pantalla.

Los letreros de los edificios también son los de GameCube, leídos de tu
disco al compilar (`tools/af_gc_signs.py`): GameCube guarda estas
texturas con los mismos nombres y tamaños que el cartucho, en su propio
formato (mosaicos de 8x8 y paletas RGB5A3), y su versión inglesa redibujó
24 con palabras: los nombres de las cuatro tiendas (y el cartel y el
emblema de la primera, y el cartel de rebajas de la cuarta), el mercado
negro, el vertedero, la placa de la comisaría, las unidades del cartel de
cuenta atrás, el puesto del torneo de pesca y las placas de nombre de las
casas. Se convierten al formato de N64 y se escriben sobre la copia del
árbol en inglés (31 copias, más los dos colores de la paleta del mercado
negro); `make signs-check-en` las
comprueba en el ROM y en la RAM del juego tras montar cada edificio junto
al jugador ("The GameCube's signs").

Las últimas palabras que quedaban en japonés en texturas también están
en inglés (`tools/af_menu_tex.py`): las notas del editor de la melodía del
pueblo llevan las letras de GameCube (C, D, E, F, G, A, B y "?" para la
nota al azar), y donde GameCube no tiene nada parecido usamos el propio
tipo de letra del juego: un guion entre mes y día en la ventana del
cumpleaños (como el reloj), comillas inglesas en la ventana de la
muletilla, y "Safety"/"First" y "New Year" en los dos papeles de carta
que llevaban kanji ("The word textures").

Donde ningún inglés redibujó las palabras de un edificio, el dibujo es
nuestro (`tools/af_own_signs.py`, a mano, con los colores de la propia
paleta de cada textura): el puesto de los fuegos artificiales dice
REDD'S en su toldo y K.K., FRUIT y GYROID en sus tres tarjetas (lo que
vende); el tablón de la comisaría, NOTICE en verano y BEWARE OF THIEVES
en invierno; el cartel del cuartel del torneo de pesca, HQ; y el puesto
de la fortuna de Año Nuevo, LUCK en su placa y GOOD LUCK en la papeleta.
GameCube tiene otros modelos para estos puestos y la comisaría, y dejó
el cartel del torneo en japonés. Probamos antes PixelLab, que devolvió
casi sin cambios el dibujo de partida. `make signs-check-en` comprueba
estas texturas en el ROM y en la RAM, y el barrido del año las ha visto
en pantalla ("Our own signs"). De paso, el puesto de los fuegos hablaba
de lo que vende el de GameCube (un abanico, un molinillo, globos, con
sus precios) mientras ofrecía música de K.K.: sus tres saludos son ahora
traducción nuestra del cartucho (música de K.K., gyroides y fruta, con
sus precios).

Dentro de los edificios también había rótulos (abajo, «Rótulos en
japonés dentro de las tiendas grandes»): los siete de la tienda más
grande salen del disco de GameCube, como los de fuera, y también los dos
carteles de la comisaría, que primero dibujamos nosotros y resultaron
tener versión inglesa oficial; el cartel de la segunda tienda, que
GameCube no tiene, es nuestro.

Después, una revisión de todo el texto antes de una sola compilación
("The text's questions and facts"). GameCube escribió sus preguntas para
sus propias respuestas, y 220 de sus mensajes ofrecen en el cartucho
otras: en 35 la pregunta o la réplica no encajaba (un "no, gracias" que
en el cartucho acepta un cambio, los concursos de verdadero o falso que
se responden con círculo o X sin decir cuál es cuál, la prueba de la
vista que enseñaba otra letra) y ahora son traducción nuestra. 13
respuestas del banco se han reescrito para todos sus usos (la primera
opción de Nook pregunta el precio de los nabos). La reforma de la casa
cobra lo que el cartucho (49.800 y 498.000 bayas), y se han corregido
fechas y fiestas que seguían siendo de GameCube o del parche de 2010
(la feria deportiva el 20 de abril, los fuegos en agosto, la luna de la
cosecha, el White Day, el Setsubun de las cartas de la madre), los 15
mensajes del parche de 2010 que decían "Memory Pak" y los del jefe de
estación, un juego de NES que el cartucho no tiene, y los huecos que el
cartucho deja vacíos y llevaban texto ajeno (mensajes, siete cadenas,
dos cartas).

Por último, la ROM jugada como un jugador en el emulador ("A day in the
town, played"): tres fechas, cada vecino cuatro veces con respuestas
distintas, la policía, el jefe de estación, y la tienda, Correos y el
puesto de policía por dentro. Corregido lo que salió: la muletilla del
vecino empieza en mayúscula tras "!" o "?" (como en GameCube; 71 de
nuestros mensajes), tres erratas de palabra repetida del texto de
GameCube, un guion de final de línea que la maquetación separaba con un
espacio, el aviso de Año Nuevo (ahora nuestro, con su refrán y el pozo
de los deseos), y la opción de Correos para pagar el préstamo a Nook
("Make a payment"). Dentro del puesto de policía había dos carteles en
japonés (JOIN US! y WANTED! ahora), y un barrido de las 2.778 texturas
de los 561 muebles encontró 17 con palabras legibles, todas redibujadas
con dos tipos de letra nuestros (`tools/af_own_ftr.py`): las vallas y
señales de obras (SAFETY FIRST, SORRY! ROAD WORK AHEAD, DETOUR), NO
FIRE, BUS STOP, la tabla de precios del baño, ROYAL A, MILK, CARDS y
CASH, GO SLOW, N64 y la placa NINTENDO ("Our own signs", "Our own
furniture words").

La pasada de pruebas del texto ("The official text's codes and the
GameCube's own") encontró dos clases de fallo en el texto oficial. La
primera: 688 mensajes de GameCube llevaban sus propios códigos, los que
mueven el juego, en vez de los del cartucho: órdenes de gesto, de
recados y de fin de charla, las opciones de los concursos (una, fuera
de las 460 del N64), saltos al azar a mensajes que el banco del N64 no
tiene, un código de suerte, preguntas que el vecino del cartucho no
hace. El borrador conserva ahora su inglés con los códigos del cartucho
(`af_text.mend`); donde el mensaje hace otra cosa, el inglés es nuestro.
La segunda: GameCube cambió, en los mismos números, las fiestas y las
cosas del cartucho por las suyas. El White Day pasó a ser su Día de la
Marmota, el Día del Niño su fiesta de la cosecha de otoño y la luna de
mitad de otoño su lluvia de estrellas de agosto. La decimotercera noche
pasó a ser su luna de la cosecha, los fuegos de los sábados de agosto
su 4 de julio, y cambió las fechas de la feria deportiva. La Controller
Pak y la Game Pak eran su Memory Card en una ranura de la GameCube, los
botones START y R sus X e Y, y aparecían un museo y un alcalde que el
cartucho no tiene. Son 202 mensajes y 3 opciones de concurso nuevos,
traducción nuestra del japonés del cartucho (`script/message/`,
`script/choice/`). Ya no queda ninguna cadena libre sin rellenar.

El dial ya no tiene modos kana y escribe minúsculas ("The dial's small
letters"). En el cartucho Z recorría hiragana, símbolos, katakana,
letras (solo mayúsculas: R pasaba la letra anterior a minúscula) y
números, y el editor abría en hiragana. Ahora abre en minúsculas ("abc",
con una etiqueta dibujada por nosotros), y Z pasa a mayúsculas ("ABC")
sin mover el anillo, luego a los símbolos, a los números y de vuelta a
"abc"; el panel de modos muestra esas cuatro filas en ese orden. R
convierte la letra anterior a mayúscula, así que "Dear" se escribe d, R,
e, a, r. El anillo de símbolos pierde los cinco signos japoneses (coma,
punto, punto medio y comillas angulares) y queda en 25, en un orden
pensado para el inglés. `tools/af_dial.py` pone nuestra etiqueta donde
estaba la de hiragana y reordena el panel y el anillo al compilar; el
editor solo reparte las filas, sin gastar más de una de las reubicaciones
que le quedaban. `make write-check-en` comprueba el arranque en
minúsculas, el ciclo de Z, las filas del panel medidas en pantalla, el
anillo de símbolos y una frase tecleada con los tres modos. La ruta de
pruebas escribe ahora los nombres en minúsculas.

Sin el ajuste manual del tipo de guardado que usan nuestras pruebas, un
mupen64plus normal trata la ROM como desconocida (tipo de guardado 0),
pero aun así guarda en Flash RAM: el núcleo solo distingue SRAM del
resto y detecta la Flash RAM al primer acceso. Medido: la ruta y todas
las comprobaciones de guardado, carga al encender incluida, pasan igual.
La ROM conserva el nombre y el código de juego del cartucho (NAFJ).

Probada en otros emuladores ("Other emulators"):

- **ares** (v148, el más fiel al hardware: emula las cachés de la CPU y
  el RDP con paraLLEl-RDP). Lo reconoce como el cartucho por su código
  (Flash RAM de 128 KB, reloj y Controller Pak) y la partida nueva llega
  a los mismos puntos casi en los mismos fotogramas que en mupen64plus. Al
  encender con una partida guardada, el juego repara la ranura dañada, la
  carga y guarda solo al llegar al pueblo. `emu/ares/` lo compila sin
  ventana (`make ares`) y `make ares-route-en` juega la ruta en él.
- **Project64 3.0.1** necesita un ajuste: *Self-mod code method: Protect
  Memory* (en su base de datos, `SMM-Protect=1`). Project64 reconoce el
  cartucho y el parche de 2010 por la suma de verificación de la cabecera
  y les aplica ese ajuste; la nuestra es otra, así que usa el valor por
  defecto y se queda en el logo de Nintendo 64. El cartucho original sin
  su ficha se queda en el mismo sitio. Con el ajuste llega al pueblo
  (K.K., los diales del nombre y del pueblo, Rover, la estación), con su
  propio plugin de vídeo y con GLideN64. Dónde está: Options → Settings,
  desmarcar «Hide advanced settings»; luego, con clic derecho en el juego,
  «Edit Game Settings» → Recompiler → marcar «Protect memory».
- **Project64 4.0** (la versión de desarrollo 4.0.0.6807) necesita dos
  ajustes, los mismos que su base de datos le da al cartucho: el motor de
  32 bits y el método de código automodificable *Store Instruction*
  (`32bit=1` y `SMM-StoreInst=1`). Sin ninguno, la demo del título se
  para al minuto («Failed to translate PC to a PAddr: 0»). Con solo
  *Store Instruction*, se para en la charla de K.K. («Failed to find a
  free register», un fallo de su recompilador). El cartucho original
  hace lo mismo en los dos casos. Con los dos ajustes la ROM juega la
  partida nueva hasta la estación del pueblo. También carga un pueblo
  guardado de dos jugadores: borra una casa sin colgarse, guarda (las dos
  ranuras bien, con la hora del ordenador) y, al encender otra vez, carga
  lo que guardó. El tipo de guardado puede quedarse en «Use first-used
  save type». Dónde está: Options → Configuration → General settings,
  desmarcar «Hide advanced settings»; luego «Config: DOUBUTSUNOMORI» →
  Recompiler, marcar «32-bit engine» y, en «Self mod methods», «Store
  Instruction»; OK y reiniciar el juego. Lo guarda en
  `Config/Project64.rdb.user`. Su .fla tiene el orden de bytes del de
  mupen64plus, y su mando por defecto lleva el stick en el teclado
  numérico (8, 2, 4, 6) y la cruceta en las flechas.
- **RetroArch 1.22.2 con Mupen64Plus-Next** (el núcleo nightly,
  3.0-Vulkan) funciona sin tocar nada. Su base de datos no conoce la ROM
  y la trata como desconocida, pero con las opciones por defecto del
  núcleo (GLideN64, RSP en HLE, recompilador dinámico) la partida nueva
  llega a la estación del pueblo, con la hora bien. La partida va en el
  `.srm` de RetroArch: 296.960 bytes, con la Flash RAM a partir de
  0x28800 en el mismo orden de bytes que el `.fla` de mupen64plus. Un
  pueblo guardado de dos jugadores carga, borrar una casa no se cuelga,
  guarda las dos ranuras bien y al volver a encender las carga. También
  con ParaLLEl-RDP y ParaLLEl-RSP en Vulkan. El reloj es siempre el del
  ordenador: el núcleo no implementa cambiar la hora del cartucho.
- **Rosalie's Mupen GUI 0.9.1** (RMG, Mupen64Plus standalone, desde
  Flathub) funciona sin ajustes para el juego. Su núcleo y sus plugins
  por defecto (GLideN64, RSP en HLE, recompilador dinámico) la tratan
  como ROM desconocida y la juegan igual. Solo hay que configurar el
  mando, como en cualquier juego: sin perfil de mando, el título enseña
  nuestro aviso de mando no conectado. La partida nueva llega a la
  estación del pueblo. La partida va en
  `Save/Game/DOUBUTSUNOMORI-XXXXXXXX.fla` (las X son las 8 primeras
  cifras del MD5 de la ROM), con el orden de bytes del
  `.fla` de mupen64plus. Un pueblo guardado de dos jugadores carga,
  borrar una casa no se cuelga, guarda las dos ranuras bien y al volver
  a encender las carga. También con ParaLLEl-RDP y ParaLLEl-RSP en
  Vulkan.
- **simple64 v2024.12.1.** Su app de Flathub está retirada (su autor la
  sustituyó por gopher64) y su repositorio de GitHub ya no existe. Se
  probó su última versión para Windows, de la réplica del proyecto en
  SourceForge, bajo WineHQ 11.0 (Wine 9.0 no dibuja Vulkan en una
  ventana hija). Funciona sin ajustes. Con sus teclas por defecto (A en
  el Shift izquierdo, Start en Intro, el stick en las flechas), la
  partida nueva llega a la estación y a Porter. Un pueblo guardado de dos
  jugadores carga, con los menús de la recepción en inglés. Borrar una
  casa pregunta cuál y cancelar sigue. Elegir jugador guarda las dos
  ranuras bien, y al volver a encender carga con el otro jugador. La
  partida va en
  `AppData/Roaming/Mupen64Plus/save/DOUBUTSUNOMORI-XXXXXXXX.fla`, como
  en RMG.
- **gopher64 1.2.1** (el sucesor de simple64) se cierra nada más
  arrancar en esta máquina, también con el cartucho original, así que
  no está probado.

En RMG y simple64 el nombre del `.fla` lleva las 8 primeras cifras del
MD5 de la ROM, que cambian con cada versión de la traducción. Para
seguir con el mismo pueblo tras actualizar la ROM, renombra el `.fla`
viejo al nombre nuevo (el emulador crea el nombre nuevo en cuanto el
juego guarda; mira cuál es o calcula el MD5). Project64 4.0 también
guarda cada ROM en su propia carpeta (`Save/DOUBUTSUNOMORI-…`): copia
ahí el `.fla` de la carpeta vieja. RetroArch llama al `.srm` como el
archivo de la ROM, así que basta con conservar el nombre del archivo.

El «Forest» del logo cambia de color con cada demo del título: verde,
lila, azul claro. Pasa igual en mupen64plus, en los dos Project64 y en
RetroArch.

Probarla en ares sacó a la luz un fallo nuestro y una palabra sin
traducir ("The player-select actor's answers"). El personaje que recibe
al jugador al encender con una partida guardada monta sus preguntas
cargando respuestas del banco de opciones en huecos de 10 bytes de su
pila. Nuestro cargador escribía los 24 de las respuestas inglesas y
pisaba el puntero de la ventana de opciones guardado detrás: en un pueblo
con dos o más jugadores, pedir que derribe la casa de un jugador colgaba el juego (medido; el
cartucho, en el mismo pueblo, enseña la lista). Ahora el cargador escribe
los 10 bytes del cartucho y guarda aparte la respuesta entera, que la
ventana toma de ahí, así que además se ven enteras las respuestas de ese
personaje y del gestor de encargos, que antes salían cortadas. Su
respuesta para un jugador nuevo, una palabra en japonés que copiaba de
sus propios datos, es ahora la opción del banco que dice lo mismo, la de
GameCube para una primera visita (`tools/af_answers.py`). `make psel-check-en` lo comprueba con
pueblos de uno, dos y cuatro jugadores, y en ares si está compilado.

Una pasada final por el calendario (`make year-check-en`, "The year's
events") enciende ese pueblo guardado en las 21 fechas de los eventos del
año, las que dan los avisos del tablón. En cada una recorre el pueblo
entero y habla con los personajes del evento, los vecinos que el evento
reúne, los personajes especiales y dos vecinos más, leyendo cada página y
cada respuesta. Encontró cuatro fallos, ya corregidos:

- **En diciembre el juego se colgaba** al decir la fecha (el personaje
  que recibe al encender la dice, y con ella el nombre del mes). El banco
  de cadenas en inglés tenía un número impar de entradas, y leer la
  última, la de diciembre, pedía 4 bytes más allá de su tabla. El gestor
  de DMA del juego se detiene ante esa lectura. Ahora todas las tablas se
  rellenan hasta múltiplos de 16 bytes.
- **El puesto de la noche de fuegos artificiales** ofrecía su última
  respuesta en japonés: la copia de sus propios datos, como hacía el
  personaje de la recepción. Una de sus dos respuestas es ahora la del
  banco que dice lo mismo. La otra no está en ningún banco: se toma al
  compilar del propio puesto de GameCube, o la nuestra si no hay disco.
- **El policía decía las fechas con dos números** («2 3»). Ahora pone el
  nombre del mes y el ordinal del día, como el policía de GameCube.
- **Diez mensajes oficiales acababan con el punto japonés** («。»), siete
  de ellos además con la muletilla repetida. Al dar al texto de
  GameCube los códigos del cartucho, el borrador copiaba también el
  final de la frase japonesa. Ahora copia solo los códigos, y la
  compilación avisa si algún mensaje muestra japonés: en la ROM, ninguno.

Una segunda revisión, independiente y sin dar por bueno nada de lo ya
marcado como hecho ("The second review"), repasó el texto compilado
mensaje a mensaje contra el cartucho, los nombres uno a uno, el código de
la traducción, las herramientas, las texturas que quedaban por mirar y la
memoria del juego. Lo que estaba bien lo sigue estando (los códigos que
dirigen el juego son los del cartucho en los 11.752 mensajes, ninguna
línea se sale de la ventana, ningún búfer puede desbordarse, el guardado
tiene margen de sobra). Lo que encontró, ya corregido:

- **Dos textos se cortaban a 10 letras.** El juego guarda en 10 bytes las
  palabras que mete en una carta. La papeleta de la fortuna de Año Nuevo
  salía con sus cinco frases truncadas, y los avisos del tablón que firma
  la tienda, con el nombre a medias. Ahora esas cadenas viajan como una
  ficha de 10 bytes que el código de las cartas, que ya sabía hacerlo con
  los nombres de los objetos, convierte en el texto entero.
- **El cumpleaños salía como dos números sueltos** («3 14»). El cartucho
  escribe dos números donde GameCube escribe el mes con su nombre; ahora
  se lee «3-14», como en la ventana del cumpleaños.
- **Una de las frases que pide Resetti no se podía teclear** (llevaba un
  signo que el dial inglés no tiene), y su lista de insultos no casaba
  con lo que el juego compara. Las dos son nuestras ahora, y la
  compilación comprueba que todo lo que Resetti compara se puede escribir
  con el dial. `make resetti-check-en` lo prueba en el juego: abre su
  ventana como la abre él, teclea en el dial cada una de las 16 frases y
  se las da a juzgar a su propio código, que las acepta todas; también
  comprueba los 32 insultos y la página en la que enseña la frase.
- **Seis mensajes decían otra cosa que el cartucho**: hablaban de cosas
  que solo tiene GameCube (un sastre, un faro, un fantasma) o imprimían
  una cadena que el cartucho no rellena. Traducidos de nuevo desde el
  japonés. Otros siete nombraban las filas del mapa con la letra de
  GameCube; el mapa del cartucho las numera.
- **Diez objetos tenían el nombre de otro dibujo.** GameCube redibujó
  algunas prendas, dos suelos y dos papeles de pared conservando el
  número, y su nombre inglés es el del dibujo nuevo. Tienen ahora el
  nombre de lo que el cartucho dibuja. Cinco artículos «a/an» que
  GameCube trae al revés se corrigen con una regla.
- **La memoria iba más justa de lo necesario.** El código que creció con
  la traducción se colocaba entero al final, y cada byte ahí es un byte
  menos para el juego (y un fallo de memoria ya había hecho fallar dos
  comprobaciones durante el desarrollo).
  Pero al mover ese código quedaban vacíos sus sitios originales: ahora
  se rellenan con las otras piezas que crecieron. El código añadido al
  final pasa de 112 KB a 52 KB. Medido en un pueblo de dos jugadores: en
  el recorrido de la gimnasia matutina el mínimo libre pasa de 32 KB a
  97 KB, y el punto más justo del año (el día de Año Nuevo, junto al
  santuario con sus puestos) queda en 72 KB, donde antes, por la misma
  cuenta, habrían sido unos 12. `make year-check-en` lo mide en cada
  parcela de cada fecha y falla si baja de un mínimo.
- Erratas del texto oficial (seis frases que empezaban en minúscula,
  cuatro comas sin su espacio) y dos respuestas mal ajustadas.
- **La integración continua de GitHub no había pasado nunca.** El trabajo
  que compila el emulador fallaba desde el primer commit (89 ejecuciones)
  porque a sus dependencias les faltaba `nasm`; los tests de las
  herramientas sí pasaban, así que el rojo era siempre el mismo y nadie lo
  leía. Añadido `nasm` (también a la lista de paquetes de arriba): desde
  entonces pasa entera.

De paso, un hallazgo que resultó ser falsa alarma mía: creí que una
herramienta nunca había leído los nombres japoneses de los objetos; los
leía bien en la compilación, y solo fallaba, en silencio, si se le daba
el volcado sin descomprimir. Ahora lo rechaza con un mensaje claro.

Esa revisión terminó con una lista de lo que no había cubierto. Cubrirlo
("Strings cut by their loaders", "What the second review had left open")
sacó tres fallos más que ninguna pasada anterior había visto, la segunda
revisión tampoco:

- **Palabras cortadas a 10 letras.** El código del cartucho no siempre
  lleva una cadena del banco directa a la página: muchas veces la carga en
  un búfer propio de 10 bytes (o de 4) y pasa eso. Las palabras japonesas
  caben; 303 de las inglesas no (234 palabras distintas: peces, insectos,
  comidas, deportes, signos del zodiaco, las listas de la adivina, las de
  las cartas con que contestan los vecinos, el tipo de tienda) y salían
  cortadas. Ahora viajan como los nombres de los objetos, con una ficha
  que el código de la página, la carta y las respuestas despliega.
  `make words-check-en` las carga una a una como las carga el juego (837
  cadenas) y llama a los propios rellenadores de la charla y de la
  adivina; `make letters-check-en` hace contestar a los vecinos 388 veces
  con el código del juego.
- **Los pueblos de las comprobaciones no tenían casas.** Las pruebas que
  encienden un pueblo guardado lo fabricaban copiando la memoria del
  juego, y en esa copia las casas de los jugadores, sus buzones y sus
  giroides no están como objetos (el juego los repone justo antes de
  guardar). El pueblo se encendía con los solares vacíos y nadie miraba
  ahí. Era fallo de las pruebas, no del juego ni de la traducción; ahora
  el pueblo se hace como lo hace el juego (`tests/emu_town.py`: la casa
  elegida con la charla de Nook, los actores guardados con la función del
  juego) y las pruebas miran la parcela de las casas.
- **El policía de dentro llevaba el nombre del de fuera.** El cartucho da
  una sola cadena de nombre a tres personajes: el policía de la puerta,
  el del mostrador de objetos perdidos y el que dirige la gimnasia (en
  japonés los tres son «el policía»). GameCube les da en inglés dos
  nombres, y su propia tabla, entrada por entrada igual que la del
  cartucho, solo difiere en esa. Como las frases del de dentro son las de
  GameCube, las decía con el nombre del otro. Ahora toma el suyo del
  disco, y la compilación compara las dos tablas enteras cada vez.

Y lo que quedaba pendiente:

- **Resetti por el camino del juego.** `make reset-check-en` no llama a
  nada suyo: enciende siete veces la misma partida. Guarda en el giroide
  y sigue jugando, corta la corriente, y Resetti aparece; guarda y sale,
  y no aparece; después no guarda nunca, y van saliendo sus cuatro
  visitas, cada una con su texto. En la tercera pide una frase: la prueba
  la teclea mal (él insiste), teclea un insulto (él se enfada) y la
  teclea bien (él se va). Al quinto reinicio el propio juego devuelve la
  cuenta a tres y vuelve el de la frase: a partir de ahí se alternan la
  tercera visita y la cuarta. Todas las páginas se leen: ni japonés ni
  fichas sin desplegar.
- **Sus insultos.** Ninguno cabe ya dentro de una frase mal tecleada por
  un solo desliz (una letra de menos, de más, cambiada o dos bailadas); la
  compilación lo comprueba para todas las frases y su código lo confirma
  en el emulador con 658 intentos.
- **Dos nombres cortados** por el tope de 16 letras: el registro guarda
  ahora 20 y están enteros.
- **La memoria en un pueblo lleno y en interiores.**
  `make memory-check-en` hace crecer el pueblo hasta sus 15 vecinos con el
  código del juego, con cuatro jugadores y sus cuatro casas, lo enciende
  en las 21 fechas del año y entra en las 30 parcelas. El punto más justo
  del año queda en 65,9 KB libres (una mañana de febrero, con bolas de
  nieve en la parcela de las cuatro casas); las otras fechas con nieve,
  entre 73 y 80 KB, y sin nieve, de 80 a 86 KB. Sobre lo que una parcela
  tiene al entrar puede aparecer algo más. Los vecinos, los personajes (el
  cartero, Resetti en la puerta, el visitante del día) y los edificios no
  gastan de esa memoria: el juego les guarda sitio aparte, y la prueba lo
  comprueba con cada uno que encuentra (y `make reset-check-en` con
  Resetti: 88,5 KB libres mientras habla). Lo que sí gasta (un globo, las
  abejas de un árbol, un pez devuelto al agua, un muñeco de nieve, un
  avión de papel, lo que cuatro vecinos lleven en la mano y, con nieve, las
  dos bolas, que se pueden empujar desde la parcela de al lado) son 51,9 KB
  si llegara todo a la vez. La prueba exige que en cada parcela de cada
  fecha quepa todo lo que no estaba ya, junto, y sobren 16 KB: en la más
  justa del año sobran 26,6 KB. Y da cuenta de los 177 tipos de actor del
  juego: los 87 que nunca ve cargados en el pueblo son personajes o
  edificios, están en esa suma, los encuentra en interiores o en la
  partida nueva, o están apuntados con el sitio donde viven. La primera
  versión de esta cuenta estaba mal (sumaba el cartero, que no gasta, y no
  contaba todo lo que ocupa cada cosa); la cifra de «42 KB» que di venía
  de ahí. Un matiz: el hueco libre de una parcela cambia según por dónde
  se llegue a ella (de ahí los 16 KB de reserva); eso se midió después,
  entrando por todos los lados (abajo).
  Hay otro momento en que el juego pide mucha memoria de golpe: al pulsar
  START en el título lee la partida en un búfer de 63,9 KB, y no comprueba
  si se lo dan. Todas las pruebas pulsaban START en la primera de las
  cinco demos del título; ahora la prueba deja pasar las cinco y en la más
  justa hay 175 KB libres.
  Dentro de las casas y edificios sobra: más de 400 KB libres en la casa
  del jugador (también llena de giroides), en la tienda y en casa de un
  vecino, y más de 580 KB en correos y en la comisaría; todo el código de
  todos los muebles junto son 153 KB. Además la tabla de palabras de los
  menús se guarda ahora compacta y devuelve 4,6 KB: el código añadido
  pasa de 52 KB a 48 KB. El cartucho original sigue teniendo esos 48 KB
  más; si la memoria se agotara, el juego se detendría, igual que en el
  cartucho, y no hay ningún caso medido que se acerque.

Y lo último que quedaba sin cubrir (la lista con la que cerré la revisión
anterior), ya cubierto:

- **Por dónde se llega a una parcela.** La memoria libre de una parcela
  cambia según el camino. Ahora `make memory-check-en` pone un contador
  dentro del propio juego, en las dos funciones por las que se pide
  memoria, desde que se enciende: en ningún recorrido ni en ninguna
  habitación el juego recibe un «no», y queda apuntado el mínimo real,
  también entre dos fotogramas. Y en cinco fechas (las cuatro con nieve
  y la noche de los fuegos) entra en cada una de las 30 parcelas desde
  todos sus lados, 106 cruces cada vez. En la ROM que se entrega, el
  punto más justo del año son 57,7 KB libres (Año Nuevo, la parcela de
  las cuatro casas entrando desde el oeste), y si además llegara de
  golpe todo lo que puede aparecer sobrarían 24,0 KB, donde se exigen
  16. Con nieve, el hueco de una misma parcela llega a cambiar 33,4 KB
  según por dónde se entre. (Cada compilación con cambios de código
  genera otro pueblo, así que estas cifras son las de esta ROM.)
- **Los actores que solo estaban «leídos».** El contador apunta también
  cada actor que el juego crea, en el único sitio del código donde se
  crean, desde el encendido y en todas las sesiones de la prueba. De los
  13 tipos que la prueba daba por situados leyendo tablas, cuatro se ven
  ahora crear (el logotipo del título, el bombo del sorteo, el decorado
  del iglú y el de la tienda del mercadillo) y los otros nueve no los
  crea nada: no aparecen nunca, cuatro están vacíos, tres son de
  pruebas, y el único que figura en la lista de una escena lo hace en
  una escena de pruebas a la que no lleva ninguna puerta (solo el menú
  de depuración, que en el cartucho no se puede abrir). Leer las tablas
  había situado mal tres de ellos. Si algo los creara, todos juntos
  ocuparían 11,1 KB, menos que la reserva.
- **Los interiores que faltaban**, cada uno como llega a él el juego, en
  varios encendidos de la misma partida guardando en el giroide entre
  ellos. La casa se amplía sola al encender al día siguiente de
  encargarla (dos veces: mediana y grande) y se mide vacía y con un
  mueble distinto en cada casilla, los de código más grande. Aquí salió
  un tope del propio cartucho: el cuarto solo crea 32 muebles en la casa
  mediana y 48 en la grande (24 en la pequeña), por muchas casillas que
  tenga. Con ese máximo quedan 360 KB y 247 KB libres, y todo el código
  de todos los muebles junto son 153 KB. La tienda crece tres veces con
  la reforma del propio juego (el día antes está cerrada, con su
  cartel) y se entra en cada una: más de 400 KB libres en todas,
  también en la planta de arriba de la grande y el día del sorteo. El
  mercadillo y la adivina salen el día que el juego fija al encender a
  una hora concreta; dentro hay 495 KB y 506 KB libres y sus dueños
  hablan en inglés limpio. El iglú, con su campista (472 KB); la tienda
  el último día del mes, con el bombo; la casa de noche.
- **Rótulos en japonés dentro de las tiendas grandes.** Al entrar
  aparecieron: siete en la tienda más grande (el cartel de la escalera,
  la placa de información y el tablón de bienvenida y horario en la
  planta baja; el cartel de agradecimiento, la placa y el tablón del
  sorteo el día del sorteo; otro cartel de agradecimiento arriba) y un
  cartel de «se busca personal» en la segunda tienda. Los siete existen
  en inglés en el disco de GameCube y se toman de ahí al compilar, como
  los rótulos de fuera; el cartel de la segunda tienda no existe en
  inglés y lo he dibujado yo (HELP WANTED / PART-TIME / GOOD PAY / ASK
  INSIDE!). Para no depender del ojo, se compararon con el disco las 358
  texturas de todas las salas del juego: 285 son idénticas byte a byte
  y las otras 73 se miraron una a una (el resto es arte sin letras). De
  ahí salió también que los dos carteles de la comisaría, que eran
  dibujo mío, tienen versión inglesa oficial en el disco: ahora se usa
  esa cuando hay disco, y la mía queda para compilar sin él. `make
  signs-check-en` los comprueba en la ROM y `make memory-check-en` entra
  en cada tienda y los encuentra en memoria.
- **La quinta variante de Resetti** no la trae ningún reinicio: asoma
  junto a la caja de la estación durante el concierto de Totakeke, entre
  las 20:45 y las 20:55 de un sábado. `make reset-check-en` enciende a
  las 20:46, pide la canción y lo ve salir y marcharse; no tiene texto, y
  ocupa el sitio reservado a los personajes.
- **Toda la batería sobre el commit final.** Las comprobaciones corren
  todas juntas, como objetivos de un solo `make`, sobre el código que se
  entrega.
- **Las notas «sin verificar».** Quedaban diez y no queda ninguna: cada
  una tiene ya su fuente en el código del juego (dos tamaños de
  estructuras; el 2 % de anchura de los tableros, que es el redondeo de
  una matriz y la prueba lo encuentra en el fotograma; de dónde salen
  dos respuestas de un menú; qué significa un código de GameCube) o se
  comprueba en marcha: en cada página que leen las pruebas se mira que
  ninguna línea se salga del bocadillo y que la placa del nombre no
  caiga en la palabra japonesa de relleno.

Y lo que salió al repasar todo otra vez antes de cerrar: tres caminos del
juego que ninguna prueba había jugado nunca, porque los pueblos de las
pruebas se fabricaban con ellos ya recorridos (o nunca salían de ellos).

- **La primera hora de toda partida nueva.** El juego no deja guardar
  hasta terminar el primer trabajo para Nook (una hora de recados), así
  que las partidas de prueba se sellaban a mano con el trabajo dado por
  hecho, y ese tramo no lo había jugado nadie en la ROM en inglés.
  `make job-check-en` lo juega entero con el mando, como un jugador:
  elige casa, se pone el uniforme desde la pantalla de objetos, planta
  las flores y los arbolitos, saluda a todos los vecinos, entrega un
  mueble, escribe una carta a una vecina y la echa en correos, entrega
  una alfombra y un hacha (la vecina enseña entonces la carta que le
  llegó), escribe un anuncio en el tablón, y Nook da el trabajo por
  terminado. Después vende algo a Nook, compra algo de su mesa, paga
  100 bayas de la deuda en correos, guarda en el giroide (que hasta
  entonces no dejaba) y vuelve a encender la consola con esa partida.
  La prueba no sigue un guion fijo: lee en los datos del jugador qué
  encargo toca y para qué vecino, y exige que el propio juego lo dé por
  hecho. Todos los menús (las etiquetas de los objetos, la libreta de
  direcciones, el dial, las preguntas al terminar una carta o un
  anuncio, el mostrador de pagos) salen en inglés y funcionan.
- **Un mando que no existe.** Lo único que falló en esa hora: el aviso
  de ayuda del tablón de anuncios decía que las páginas se pasan con el
  «C Stick», la palanca del mando de GameCube; el cartucho dice «los
  botones C». Ahora el aviso es texto propio traducido del cartucho, y
  la compilación rechaza cualquier texto que nombre un control que el
  mando de N64 no tiene (C Stick, botones X o Y, START/PAUSE): no hay
  ningún otro. Además comparé con el japonés, mensaje a mensaje, todos
  los controles que se nombran (36 mensajes): coinciden.
- **Un jugador que llega después.** Crear un segundo jugador en un
  pueblo que ya existe va por otro camino (otro tren, con otro guía que
  no acepta un nombre repetido; Nook enseña las casas y deja mirar las
  ocupadas). `make memory-check-en` lo recorre en el pueblo lleno, el
  día más justo del año, con una casa libre: todo en inglés y limpio, y
  la memoria aguanta como en cualquier parcela.
- **Un viaje a otro pueblo.** El juego tiene cuatro maneras de empezar
  a jugar; la cuarta, llegar con un Controller Pak, tampoco la había
  jugado nadie. `make trip-check-en` fabrica dos pueblos (el segundo
  con otros nombres: dos pueblos con los mismos nombres son «el mismo»
  para el juego), escribe en casa una carta más larga de lo que el
  cartucho guarda, habla con el jefe de estación, sale en tren, llega
  al otro pueblo como visitante, habla allí con un vecino y vuelve a
  casa. Todo el texto del camino sale en inglés y limpio.
- **Un fallo de la traducción, corregido: las cartas del viajero.** Al
  salir de viaje el juego saca del guardado las cosas del jugador (van
  en el Controller Pak). La traducción guarda aparte el final de las
  cartas largas (lo que pasa de los 96 bytes del cartucho) y lo borraba
  justo en ese guardado: a la vuelta, toda carta que el jugador llevara
  encima quedaba cortada para siempre. En el cartucho original no se
  pierde nada. Ahora ese texto se conserva en casa mientras dura el
  viaje y la carta vuelve entera; la prueba lo exige. El arreglo no le
  quita memoria al juego (una primera versión le costaba 6 KB; la medí
  y la rehice). Queda un límite sin arreglo posible sin cambiar el
  formato del Controller Pak: en el *otro* pueblo, una carta traída de
  casa se ve (y se envía) solo con sus primeros 96 bytes, y en casa
  vuelve a estar entera; y una carta escrita en el otro pueblo vuelve a
  casa sin su final.
- **El mismo fallo en otros dos sitios, corregido también.** Encontrado
  el del viajero, busqué en qué otros sitios guarda el juego una carta
  fuera de los bolsillos, los buzones y correos. Hay dos. Cada vecino
  conserva la última carta que le mandó cada jugador (y la enseña, o la
  devuelve): en cuanto la carta salía de correos hacia el vecino, el
  siguiente guardado borraba su final, y el vecino enseñaba la carta
  cortada. Y correos deja guardar hasta 160 cartas en un Controller Pak
  (la tercera opción de la empleada): la carta guardada allí volvía al
  bolsillo cortada en el byte 96. Ahora el final de esas cartas se conserva en la
  partida mientras el vecino tenga la carta o el Pak la haya guardado.
  `make job-check-en` escribe ahora larga la carta del primer trabajo y
  exige que siga entera cuando solo la tiene la vecina, y
  `make trip-check-en` guarda la carta en el Pak, guarda la partida dos
  veces y la recupera entera en otro encendido. Tampoco estos dos
  arreglos le quitan memoria al juego. Límites que quedan: el espacio
  para esos finales es el de la partida (9 KB para todas las cartas
  largas; si se llena, pierden el final las más antiguas, como ya
  pasaba), y una carta guardada en el Pak desde *otro* pueblo no tiene
  allí final que conservar. Una nota de uso que no sabía nadie hasta
  jugarlo: en ese menú las cartas se cogen y se sueltan con Z, y el
  juego no deja guardar en el Pak una carta escrita por uno mismo y sin
  enviar.
- **Cada fallo, visto fallar antes de darlo por arreglado.** Los tres
  fallos de las cartas se probaron también en una ROM sin su arreglo,
  con la misma prueba que ahora lo vigila: en las tres la prueba falla
  justo ahí y en nada más.
- **La batería final.** Las 21 comprobaciones como objetivos de un solo
  `make` sobre la ROM que se entrega: 19 pasaron a la primera. Las otras
  dos fallaron por la propia prueba, no por el juego, y están
  corregidas y vueltas a pasar: en la de memoria, un vecino que aún no
  conocía al jugador se le adelantaba a hablar justo cuando la prueba
  iba al giroide a guardar (ahora la prueba vuelve al giroide); la de
  mensajes seguía contando como «del parche de 2010» dos mensajes que
  ya son nuestros. La ROM no cambió entre una pasada y otra.
- **Tres palabras sueltas.** Un barrido del texto ya compilado buscando
  palabras que el resto del guion no usa encontró tres: un «Memory Pak»
  partido entre dos líneas (por eso se le escapó a la revisión que
  corrigió los otros quince), un «Famicom» donde todo lo demás dice
  «NES», y un «shrine square» nuestro donde el texto oficial dice
  «wishing well». Corregidas.

## Dónde está cada cosa

- `Makefile` — todo lo de arriba.
- `decomp/` — `AF_REV` (el commit de zeldaret/af), `matching.patch` (lo
  descompilado: reconstruye el cartucho) y `changes.patch` (la traducción,
  encima).
- `emu/` — el emulador: `build.sh`, `n64emu.py`, `headless_output.c`,
  `input_headless.c`, `patches/`, `ares/` y `ares64.py` (el segundo
  emulador, ares, sin ventana), `afplay.py` (qué hay en pantalla y
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
  tu copia), `af_answers.py` (las respuestas que un overlay copia de sus
  propios datos, apuntadas al banco de opciones), `af_dumpdata.py` (los datos del cartucho que
  un archivo decompilado incluye, sacados de tu volcado al extraer) y
  `af_ovlreloc.py` (la tabla de reubicaciones de un overlay que cambia).
- `script/` — nuestro texto: lo que no tiene versión oficial (los
  mensajes en `script/message/`).
- `tests/` — `test_tools.py` (`make test`), `emu_check.py`
  (`make emu-check`) y las pruebas del build en inglés en el emulador:
  `emu_save.py` (la partida), `emu_letters.py` (las cartas),
  `emu_board.py` (el tablero de cartas), `emu_write.py` (escribir una
  carta), `emu_notice.py` (el tablón de anuncios), `emu_messages.py` (los
  mensajes en la ventana de diálogo), `emu_menus.py` (las cadenas de los
  menús y los nombres de los objetos), `emu_psel.py` (las preguntas del
  personaje que recibe al jugador al encender con una partida guardada),
  `emu_resetti.py` (las frases de Resetti), `emu_signs.py` (los rótulos),
  `emu_year.py` (las fechas del año), `emu_words.py` (las palabras que el
  cartucho carga en pocos bytes y los nombres de los personajes
  especiales), `emu_town.py` (cómo hacen las pruebas un pueblo guardado),
  `emu_reset.py` (Resetti por el camino del juego) y `emu_memory.py` (la
  memoria en un pueblo lleno y en interiores).
- `reference/NOTES.md` — lo verificado, con su fuente.
