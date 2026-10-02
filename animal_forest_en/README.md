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
                                  # y los compara mensaje a mensaje con el del cartucho (build/gc/)
make test                         # tests de las herramientas, sin ROM
make nafe NAFE_UPS=.../NAFE-WIP-2_12_2010.ups   # el parche de 2010, para estudiarlo
```

`ROM=` hace falta una sola vez: queda normalizado en `build/baserom.z64`.

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
crecer) y su bloque de datos en la misma dirección módulo 64 KB; un objeto
cuyos datos cambien de tamaño pasa a una región `code_en`. Antes de
comprimir, tres comprobaciones prueban la disposición contra el mapa del
cartucho (`tools/af_shiftcheck.py`, `af_anchors.py`, `af_luicheck.py`). Lo
que las motivó: una dirección que el desensamblado atribuía a la función
equivocada (en `ovl_Birth_Control`) y que dejaba a Rover plantado en la
puerta del tren en cuanto los datos se movían; con ella corregida, un ROM
con todo el bloque desplazado 64 KB juega el recorrido entero igual que el
original (`reference/NOTES.md`, "Shiftability").

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
por ahora). El ROM en inglés ya habla: K.K. y Rover en la intro, con la
fuente japonesa (de ancho fijo, 16 px por letra: la fuente es la fase 5).
Nuestras traducciones de lo que GameCube no tiene van en `script/`.

Lo que viene: la fuente proporcional y los gráficos occidentales de
GameCube, las cartas, y traducir ese 6 %.

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
  correspondencia) y `af_text.py` (el banco inglés: borrador, comprobador y
  compilador).
- `script/` — nuestro texto: lo que no tiene versión oficial.
- `tests/` — `test_tools.py` (`make test`) y `emu_check.py`
  (`make emu-check`).
- `reference/NOTES.md` — lo verificado, con su fuente.
