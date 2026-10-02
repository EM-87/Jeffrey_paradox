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

**Fase 1 — banco de pruebas: en curso.**

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

Lo que viene: pasar el sistema de mensajes (`m_msg_main`) a C usando el de
GameCube como mapa, comprobar qué partes del ROM toleran que el código
cambie de tamaño, el guion (el oficial de GameCube donde coincida, el resto
traducido del japonés) con un comprobador que no deja compilar un texto que
no entra, y la fuente y los gráficos occidentales de GameCube.

## Dónde está cada cosa

- `Makefile` — todo lo de arriba.
- `decomp/` — `AF_REV` (el commit de zeldaret/af) y `patches/` (nuestros
  cambios sobre él, en orden).
- `emu/` — el emulador: `build.sh`, `n64emu.py`, `headless_output.c`,
  `input_headless.c`, `patches/`, `afplay.py` (qué hay en pantalla y
  cómo responder: diálogos, menús, el dial de nombres), `symbols.py` (nombres de funciones y
  variables desde el mapa del decomp) y `contact.py` (varias capturas en
  una hoja).
- `tools/` — `rom.py` (orden de bytes, identidad, CRC de la cabecera),
  `ups.py`, `nafe.py` y `nafe_diff.py` (el parche de 2010: aplicarlo y
  ver qué cambió), `route_newgame.py` (partida nueva hasta las casas).
- `tests/` — `test_tools.py` (`make test`) y `emu_check.py`
  (`make emu-check`).
- `reference/NOTES.md` — lo verificado, con su fuente.
