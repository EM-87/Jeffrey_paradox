# The Lost Vikings II (SNES) → GBA

Port de **The Lost Vikings II** (SNES, Blizzard / Interplay, 1995) a Game Boy
Advance. LV2 nunca salió en GBA; el primero sí, en 2003, portado por la propia
Blizzard, y ese cartucho sirve de referencia de cómo resolvieron la pantalla,
el HUD y los controles.

## Estado: investigación

Todavía no hay ROM de GBA. Lo que hay son las herramientas para mirar el
cartucho de SNES funcionando y leer sus datos, y lo que ya se vio con ellas
(`reference/NOTES.md`, cada cosa con la dirección donde se leyó):

- **Los datos ya se leen.** Todo lo empaquetado sale de una tabla de 341
  chunks en `$8B:8000`, comprimidos con el mismo LZSS que OpenVikings
  documentó para el Lost Vikings de DOS (con una diferencia en la longitud).
  `tools/lv2data.py` los descomprime y coinciden byte a byte con lo que el
  propio cartucho deja en VRAM en el nivel 1.
- **LV2 es el motor de LV1, ampliado**: misma tabla, mismo LZSS.
- **El LV1 de GBA no es un emulador**: es un motor reescrito, sin la ROM de
  SNES adentro. Pero conserva 108 chunks del LV1 de SNES idénticos byte a
  byte (sin comprimir): lee los formatos de datos del juego de SNES.

## Por qué no alcanza con un "romswap"

No hay un contenedor donde meter la ROM de LV2: el LV1 de GBA ejecuta código
ARM propio, no emula una SNES (y un GBA no puede emular una SNES a velocidad
de juego: CPU 65816, PPU, y un segundo procesador de sonido con su DSP).
Lo que sí deja abierto lo de los 108 chunks: si los formatos de LV2 se
parecen a los de LV1, una parte del trabajo de Blizzard podría
aprovecharse. Las habilidades nuevas de LV2 (Fang, Scorch, las botas de
Erik, el brazo biónico de Baleog, Olaf encogido) viven en el código, no en
los datos, así que en cualquier caso hay que escribirlas.

## Herramientas

| Comando | Necesita | Qué hace |
| --- | --- | --- |
| `make check` | nada | los tests de las herramientas |
| `make snes-core` | red (clona snes9x) | compila el núcleo de snes9x instrumentado |
| `make chunks ROM=lv2.sfc` | el dump | todos los chunks descomprimidos en `build/chunks/` |
| `python3 tools/dis65816.py ROM 80B8DE 80BB60 [--cdl F]` | el dump | desensambla 65816 (LoROM) |

- `tools/snes9x/` — snes9x (libretro, commit fijo) más `dbg.cpp` y
  `hooks.patch`: registro de código/datos sobre la ROM (qué bytes se
  ejecutaron, con qué ancho de A/X, cuáles se leyeron como datos o salieron
  por DMA), registro de DMA, breakpoints y traza. No cambia nada de lo que
  hace la consola emulada.
- `tools/snesdbg.py` — maneja ese núcleo desde Python: frames, botones,
  capturas, save states, WRAM/VRAM/CGRAM/OAM, PPU.
- `tools/dis65816.py` — desensamblador 65816; con el registro de
  código/datos usa el ancho real con el que corrió cada instrucción.
- `tools/lv2data.py` — la tabla de chunks y su LZSS.

Los dumps no están en el repositorio y no tienen que estar. Tampoco nada
generado a partir de ellos (chunks, listados, save states).

## Material de referencia

| Archivo | Para qué |
| --- | --- |
| LV2 SNES (USA) | la fuente de verdad |
| LV1 SNES (USA) y su prototipo | el mismo motor en su versión anterior |
| LV1 GBA | cómo Blizzard llevó ese motor al GBA |
| OpenVikings (MIT) | formatos del LV1 de DOS: LZSS, chunks, bytecode de objetos |
| LV2 DOS (pendiente) | si el ejecutable es C compilado, la lógica se descompila mucho mejor que el 65816 |
