# Weird Clock — hoja de ruta

## Pendiente de la efeméride (tras la 40.16)

Lo que quedó medido y no hecho al unificar el Sol y la Luna:

- **La Luna tiene seis términos** (los seis primeros de la tabla 47.A de
  Meeus). Contra Horizons, día a día durante tres años enteros (−584, 1000
  y 2026): 0,09° de error cuadrático medio (0,12° en −584) y 0,36° el peor
  día; las fases de 2026 caen a 17 minutos como mucho. Los siguientes
  términos de la tabla son de 0,06° o menos cada uno.
- **Arrastrar la Luna en el orrery** sigue la longitud media, no la
  perturbada: el dedo y el punto dibujado pueden diferir hasta 7° durante
  el arrastre (vuelven a coincidir al soltar).
- **ΔT reciente**: el polinomio 2005–2050 da 75 s en 2026 frente a los
  69,2 s que usa Horizons; 6 s son 0,003° de Luna, nada visible, pero es un
  número conocido que se podría fijar con la tabla del IERS. En −584
  Horizons usa 18 282 s y el canon de la NASA 18 384 s: 0,016° de Luna.
- **Curva del velo lunar**: con la Luna corregida, la noche del 2 de
  noviembre de 2026 estaba iluminada al 43 %, no al 50 %; el cubo da 0,08
  del camino de nueva a llena y las fotos 0,145 (el cuadrado daría 0,19).
  El dato sugiere un exponente ~2,3; la prueba se mantiene a 0,08 de margen.
- **Dos pruebas con flaqueza por reloj real**: `ColonBreathTest` (parpadeo
  medido contra la hora de pared) y `FurnitureFadeTest` (1 LSB).

## Rigor histórico del orrery (lo que queda sin hacer)

La escala de escrituras de la fecha quedó así: dígitos encendidos desde
1970, las mismas cifras en tipo de imprenta de 1600 a 1969, numerales
romanos del año 1 a 1599, jeroglíficos de −3000 a 0, cuñas de −3500 a
−3001 y nada antes. Lo que sigue sin ser riguroso, medido contra las
fuentes y no corregido:

- **La fecha romana es la nuestra con otras letras**: día·mes·año contados
  desde el año 1, cuando la era cristiana se propuso en 525 y no fue de uso
  corriente hasta el siglo VIII. Un romano fechaba por cónsules y contaba
  los días hacia las calendas, nonas e idus ("a. d. III Id. Mart."). Hacerlo
  como se hizo con Egipto (año de reinado, estaciones del Nilo) es un
  calendario romano entero.
- **Las cuñas son tres mil años más jóvenes que su ventana**: el sistema
  posicional en sesentas es de hacia −2000; entre −3500 y −3000 los números
  eran marcas redondas impresas (protocuneiforme). Ya estaba anotado en
  `Cuneiform.Word`.
- **Los nombres latinos de los planetas acaban en 1600**, con los numerales,
  para que toda la pantalla cruce una sola línea; Kepler y Newton todavía
  escribieron en latín.
- **1600 es una convención**: no hubo un día en que Europa cambiara de
  numerales. "Ampliamente adoptados a mediados del siglo XVI" (Wikipedia) y
  "en gran parte sustituidos hacia el siglo XVII" (Britannica); las portadas
  de los libros siguieron dando el año en romanos hasta el XVIII.

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
