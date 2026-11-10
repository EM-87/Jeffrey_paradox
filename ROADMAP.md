# Weird Clock — hoja de ruta

## Pendiente de la efeméride (tras la 40.15)

Lo que quedó medido y no hecho al unificar el Sol y la Luna:

- **Tres términos más de la Luna** (0,21° sen 2M′, −0,19° sen M, −0,11° sen 2F):
  con los tres actuales la fase queda a 1,26 h de las tablas de la NASA en
  el peor caso de 2026 y a medio grado de Horizons; con seis quedaría a un
  cuarto de hora. Un cambio de una línea en `Ephemeris.moonLongitudeOfDate`.
- **Arrastrar la Luna en el orrery** sigue la longitud media, no la
  perturbada: el dedo y el punto dibujado pueden diferir hasta 7° durante
  el arrastre (vuelven a coincidir al soltar).
- **ΔT reciente**: el polinomio 2005–2050 da 75 s en 2026 frente a los ~69 s
  observados; 6 s son 0,003° de Luna, nada visible, pero es un número
  conocido que se podría fijar con la tabla del IERS.
- **Curva del velo lunar**: con la Luna corregida, la noche del 2 de
  noviembre de 2026 estaba iluminada al 43 %, no al 50 %; el cubo da 0,08
  del camino de nueva a llena y las fotos 0,145 (el cuadrado daría 0,19).
  El dato sugiere un exponente ~2,3; la prueba se mantiene a 0,08 de margen.
- **Dos pruebas con flaqueza por reloj real**: `ColonBreathTest` (parpadeo
  medido contra la hora de pared) y `FurnitureFadeTest` (1 LSB).

## v41 (congelado hasta enero)

Ideas apuntadas durante el pulido de la 40.6. No se tocan hasta enero.

- **Nubes iluminadas por la Luna** (nube × velo): el velo lunar se dibuja
  hoy bajo las nubes como un tinte uniforme; las nubes de la noche deberían
  ser más claras donde la Luna las alumbra, es decir, el velo multiplicado
  por la nube y no sumado a ella.
- **Arrastrar la Luna para girar la Tierra**: hoy se gira el mundo con el
  dedo sobre el disco; arrastrar la Luna por su órbita daría cuerda al globo
  por el mismo camino que el orrery da cuerda al cielo.
- **Mapa diurno diario**: reflectancia corregida MODIS/VIIRS del día, que
  sustituye al mapa base + las nubes + el Blue Marble mensual por una sola
  foto del día. Sustituye la capa de nubes y los doce Blue Marble.
- **Inclinación de 5° de la órbita lunar**: el punto sublunar se calcula hoy
  sobre la eclíptica (declinación de la Luna = la de la eclíptica en su
  longitud); la órbita real está inclinada 5,1° y la latitud eclíptica de la
  Luna mueve el punto sublunar hasta cinco grados.
