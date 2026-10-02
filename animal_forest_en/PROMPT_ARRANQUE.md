# Prompt de arranque

Pegar esto al empezar una sesión nueva sobre este proyecto.

---

Estoy traduciendo **Doubutsu no Mori (N64)** al inglés en
`animal_forest_en/` dentro de este repo, **recompilándolo** desde el decomp
zeldaret/af en vez de parchear el ROM a mano como hizo el AF Project en
2010 (su parche tiene un cuelgue conocido). El guion será el oficial de
Animal Crossing de GameCube donde coincida (lo leo de mi disco, nunca entra
al repo) y el resto traducido del japonés; gráficos occidentalizados como en
GameCube. Lo que se entrega es un `.bps` sobre el volcado original.

Antes de tocar nada, leé en este orden:

1. `animal_forest_en/CLAUDE.md` — las reglas (el cartucho manda; nada de
   Nintendo en el repo; recompilar, no parchear; igualar antes de cambiar;
   mirar la pantalla en el emulador determinista) y las trampas del
   emulador.
2. `animal_forest_en/reference/NOTES.md` — lo verificado y cómo.
3. `animal_forest_en/README.md` — estado y comandos.

Los archivos que no están en el repo (hay que pedirlos): el volcado de
Doubutsu no Mori (`make ROM=...` una vez), el disco de Animal Crossing USA
GAFE01, y `AFProjectDistro.zip` (el parche de 2010 y su documentación, para
`make nafe NAFE_UPS=...`).

Antes de dar algo por terminado: `make test` y `make emu-check`.
