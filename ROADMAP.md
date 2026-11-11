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
- ~~**Arrastrar la Luna para girar la Tierra**~~: hecho en la 40.18. La
  Luna del borde es la manecilla lenta: llevarla da cuerda al globo por días
  (12,19° por día) y al soltarla el mundo vuelve a hoy.
- **Mapa diurno diario**: reflectancia corregida MODIS/VIIRS del día, que
  sustituye al mapa base + las nubes + el Blue Marble mensual por una sola
  foto del día. Sustituye la capa de nubes y los doce Blue Marble. Medido
  en la 40.18 sobre mosaicos reales (ver la sección siguiente): la foto del
  día a 720×360 tiene menos detalle que el mapa incluido (0,5° frente a
  0,35° por píxel), le falta la noche polar entera y, la de MODIS, una
  cuña entre pasada y pasada; para fundirla con el mapa mensual haría
  falta bajarla a 1440×720 (unas 250 KB al día en vez de 75) y decidir
  píxel a píxel de cuál de las dos fiarse.
- **Inclinación de 5° de la órbita lunar**: el punto sublunar se calcula hoy
  sobre la eclíptica (declinación de la Luna = la de la eclíptica en su
  longitud); la órbita real está inclinada 5,1° y la latitud eclíptica de la
  Luna mueve el punto sublunar hasta cinco grados.

## Gráficos del globo: lo medido en la 40.18 y no hecho

Revisión pedida de la esfera hemisférica (resolución, cortes, nubes en
tiempo real, fusión). Lo que se arregló está en el código; esto es lo que
quedó medido y sin hacer.

- **Nubes en tiempo real**. Comprobado contra los servicios el 11 de
  noviembre de 2026, sin clave: GIBS sirve GOES-East, GOES-West y Himawari
  (infrarrojo limpio, banda 13) cada 10 minutos y con 10–30 minutos de
  retraso, las tres capas en una sola petición; **no tiene Meteosat**, así
  que le faltan Europa, África y el Índico. Meteosat está en EUMETView
  (`view.eumetsat.int`, también sin clave): `msg_fes:ir108` (0°, cada 15
  min), `msg_iodc:ir108` (45,5° E) y `mtg_fd:ir105_hrfi` (cada 10 min). Un
  mosaico mundial son, pues, tres peticiones a dos servicios, unos 0,5 MB
  por refresco a 720×360. Falta por decidir: la cadencia (a media hora son
  24 MB al día), cómo pasar de infrarrojo a velo (frío = nube, y cada
  servicio trae su paleta: GIBS en color, EUMETSAT en grises) y la
  atribución que pide EUMETSAT. A cambio el infrarrojo ve las nubes
  también de noche.
- **Cuñas entre pasadas**. El mosaico de MODIS Terra deja sin fotografiar
  el 12–13 % de la franja 0–15° y el 5 % de la 15–30° (tres días medidos).
  VIIRS de NOAA-20 no deja ninguna, pero con la misma regla de velo da un
  20 % menos de nube (24 % del globo frente a 30 %) y pierde más del polo
  de invierno (44 % de la franja 60–75° sin datos frente a 23 %), así que
  cambiar de satélite exige volver a ajustar el velo a ojo. La alternativa
  barata es rellenar la cuña con la foto del día anterior, que ya está en
  el teléfono, como se hace con las luces de la noche.
- **Resolución**. Los mapas son de 1024×512 y el disco se proyecta a 512
  píxeles para una pantalla donde el mundo mide de 767 a 1080. En las dos
  vistas polares el mapa se lee ya entre píxeles; en la de globo sigue
  leyéndose del píxel más cercano, porque allí se vuelve a proyectar a
  cada grado y medio de giro y leer entre píxeles cuesta siete veces más
  (7,0 ms frente a 1,0 por proyección, medido en esta máquina). Para más
  nitidez: proyectar a 768 en las vistas polares (+10 MB de memoria) y
  mapas de 2048×1024 (+1,5 MB de APK y unos 24 MB más de memoria).
- **Auroras con Luna**. El halo solo se lee donde la Luna estaba bajo el
  horizonte al pasar el satélite, porque el hielo iluminado por la Luna es
  más brillante que una aurora y no se distingue de ella por el brillo.
  Con la Luna alta solo salen las auroras que superan el umbral píxel a
  píxel. Al norte de 80° no hay capa «limpia» con la que comparar.
- **Crepúsculo sobre el casquete**. La hora de paso del satélite se
  calcula para el punto bajo él; sobre el propio polo pasa a todas las
  horas, y en las semanas a cada lado de la noche polar el casquete queda
  algo más claro de lo que la regla sabe (unos diez grises, en octubre).

