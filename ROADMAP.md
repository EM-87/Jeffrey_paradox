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
- ~~**Mapa diurno diario**~~: hecho en la 40.19 como tercera clase de nubes
  («La propia foto de ayer»): la foto diaria de VIIRS en NOAA-20 a
  1024×512, el tamaño de los mapas, puesta sobre el mapa del mes allí donde
  hay foto. No sustituye a los doce Blue Marble, como se apuntó aquí: donde
  la foto se acaba (la noche del polo de invierno) y en todo el lado de
  noche sigue estando el mapa.
- **Inclinación de 5° de la órbita lunar**: el punto sublunar se calcula hoy
  sobre la eclíptica (declinación de la Luna = la de la eclíptica en su
  longitud); la órbita real está inclinada 5,1° y la latitud eclíptica de la
  Luna mueve el punto sublunar hasta cinco grados.

## Gráficos del globo: lo hecho en la 40.19 y lo que queda

Revisión pedida de la esfera hemisférica (resolución, cortes, nubes en
tiempo real, fusión). Las tres cosas que la 40.18 dejó medidas y sin hacer
están hechas; debajo, lo que sigue sin hacerse.

Hecho en la 40.19:

- **Cuñas entre pasadas**. Los huecos de la foto diaria de MODIS Terra se
  rellenan con la foto del día anterior. Medido en los mosaicos del 20, 21
  y 22 de noviembre de 2026: del 11,8–12,3 % de la franja 0–15° sin
  fotografiar al 0,0 %, y del 5,2 % de la 15–30° al 0,0 %.
- **Nubes en tiempo real** («En directo, hora a hora»). No el mosaico de
  tres peticiones a dos servicios que se apuntó aquí, sino el GMGSI de la
  NOAA (cinco geoestacionarios, servido sin clave por nowCOAST): una
  petición por imagen, horario, publicado unos 40 minutos después de su
  hora. Dos imágenes por hora, la visible y la infrarroja, 45 kB. Cada sitio
  es la visible en la medida en que el sol estaba alto al tomarse y la
  infrarroja en el resto. Las dos reglas se ajustaron contra la foto MODIS
  del mismo día en la franja que el satélite sobrevolaba a cada hora (24 de
  noviembre de 2026, 25 000 píxeles entre 45° N y 45° S): correlación 0,65
  con las dos imágenes, 0,53 solo con el infrarrojo.
- **Fusión con la foto del día** («La propia foto de ayer»). Ver arriba.
  Se eligió VIIRS de NOAA-20 y no la foto del velo porque no deja cuñas:
  0,2 % del trópico sin fotografiar (tres días medidos) frente al 12 %.

Lo que queda:

- **Nubes de noche**. El infrarrojo del mosaico horario las ve y no se
  dibujan: la nube solo se pinta en el lado de día, como antes. Falta
  decidir cómo conviven con las luces y con el velo lunar (enlaza con
  «Nubes iluminadas por la Luna», arriba).
- **El borde de la mañana, en directo**. Donde el sol aún no había salido
  cuando se compuso el fotograma solo hay infrarrojo, que no ve las nubes
  bajas: una franja junto al terminador de la mañana, tanto más ancha
  cuanto más viejo es el fotograma (15° por hora), con menos nube de la que
  hay. En el océano Austral el infrarrojo solo encuentra una quinta parte
  de la nube de la foto, y no en los mismos sitios (correlación 0,10).
- **Suelo frío como nube**, también en el infrarrojo: las mesetas altas y
  el interior de los continentes en invierno llevan un velo que no existe,
  casi siempre sobre nieve.
- **Sin polos en directo**. El mosaico acaba a 72,7° de latitud y el
  servicio solo responde de él hasta 60°.
- **El velo sigue saliendo de MODIS Terra**, con el relleno. Pasarlo a la
  foto de NOAA-20, que no deja cuñas, ahorraría el relleno, pero la regla
  del velo está ajustada a ojo sobre MODIS: con la misma regla VIIRS da un
  20 % menos de nube (24 % del globo frente a 30 %) y habría que volver a
  ajustarla.
- **Costura de la foto del día**. Donde la foto se acaba se funde con el
  mapa a lo largo de 4°; el mar de la foto y el del mapa no son del mismo
  azul y el cambio se ve como una banda.
- **La descarga no tiene prueba**. Las pruebas sustituyen el socket por un
  servicio de mentira que contesta como contestó el de verdad; el código
  que abre la conexión y lee la cabecera `Warning` no se ejecuta en
  ninguna, ni en este almacén ni en los anteriores.
- **Resolución**. Los mapas son de 1024×512 y el disco se proyecta a 512
  píxeles para una pantalla donde el mundo mide de 767 a 1080. En las dos
  vistas polares el mapa se lee ya entre píxeles; en la de globo sigue
  leyéndose del píxel más cercano, porque allí se vuelve a proyectar a
  cada grado y medio de giro y leer entre píxeles cuesta siete veces más
  (7,0 ms frente a 1,0 por proyección, medido en esta máquina). Para más
  nitidez: proyectar a 768 en las vistas polares (+10 MB de memoria) y
  mapas de 2048×1024 (+1,5 MB de APK y unos 24 MB más de memoria); la foto
  del día tendría que pedirse entonces al mismo tamaño (unas cuatro veces
  más datos al día).
- **Auroras con Luna**. El halo solo se lee donde la Luna estaba bajo el
  horizonte al pasar el satélite, porque el hielo iluminado por la Luna es
  más brillante que una aurora y no se distingue de ella por el brillo.
  Con la Luna alta solo salen las auroras que superan el umbral píxel a
  píxel. Al norte de 80° no hay capa «limpia» con la que comparar.
- **Crepúsculo sobre el casquete**. La hora de paso del satélite se
  calcula para el punto bajo él; sobre el propio polo pasa a todas las
  horas, y en las semanas a cada lado de la noche polar el casquete queda
  algo más claro de lo que la regla sabe (unos diez grises, en octubre).

## El globo como una bola iluminada: lo hecho en la 40.20 y lo que queda

La pregunta fue por qué el globo de otro teléfono es bonito y este no. Porque
aquel es una bola con luz y este era un mapa con la noche puesta sobre la
mitad. Se eligieron tres cosas que acortan la distancia sin dibujar la Tierra
de otra manera: cada una es una imagen más sobre el disco, calculada una vez
para donde está el sol (`GlobeLight`).

Hecho en la 40.20:

- **Luz según la altura del sol**. El suelo y la nube conservan toda su luz
  bajo el sol y el 40 % en el terminador, con una curva que sube deprisa
  (71 % con el sol a 15°, 82 % a 30°): no la ley del coseno, que deja media
  Europa a oscuras un mediodía de invierno. Medido en el cristal, en las
  tres vistas: a 0,002–0,005 de la regla de media, 0,020 como mucho.
- **Aire en el borde**. Un azul (`#78B6FF`) que tapa hasta el 55 % justo en
  el borde iluminado, se adelgaza hacia dentro y llega un 3,5 % del radio
  hacia fuera; nada en el borde de noche. Medido: a 0,012 de la regla fuera
  del mundo y a dos tonos dentro, hasta el último píxel.
- **Nubes con la misma luz y con sombra**. La sombra es la propia nube en
  negro, al 45 %, corrida 0,012 radios en sentido contrario al sol: 76 km,
  unas diez veces lo real, como el relieve de un globo de escritorio.
  Medido con una nube sola sobre suelo gris en las tres vistas, a tres
  horas y con el sol fijado a los dos lados: el suelo oscurecido queda del
  lado contrario al sol, a menos de un píxel del eje, y conserva el 61 % de
  su luz bajo la nube más espesa (la regla dice 62 %).
- **La noche, como estaba**: ni un píxel distinto en el lado oscuro (de
  411 000 a 631 000 píxeles, según la vista), salvo el contorno.

Dos fallos que salieron al medir lo anterior, corregidos:

- **El mapa de la vista sur giraba al revés**, desde la 30.0. El disco de
  las dos vistas polares se pinta una vez y se gira; se giraba como el del
  norte en las dos. En la sur la noche, el marcador y las horas estaban en
  su sitio y debajo había la mitad equivocada del mundo, con un error del
  doble de la longitud del sol: solo acertaba con el sol sobre Greenwich o
  sobre el antimeridiano. A la una de la madrugada UTC, en una pantalla de
  1080, el suelo estaba a 202 píxeles de su sitio y las luces a 172; con el
  dedo, el mundo giraba al contrario. Ninguna prueba preguntaba dónde se
  dibuja un lugar del mapa: ahora lo pregunta una por capa (suelo, nube,
  mapa nocturno, luces) en las tres vistas (`PlacesOnTheGlassTest`).
- **La noche se adelgazaba en su propio borde**: la máscara de la noche se
  quedaba vacía fuera del disco y, al estirarla, el vacío entraba en los
  últimos siete píxeles del mundo, por donde asomaba el mapa de día.

Lo que queda:

- **Sin ver en un teléfono**. Ni el aspecto ni lo que cuesta por fotograma
  (tres imágenes más por cuadro, y 80 000 píxeles de cálculo cada vez que el
  sol cambia un cuarto de grado de latitud). Todo lo medido es sobre
  imágenes generadas en las pruebas.
- **El brillo del sol en el mar**. El océano es el del mapa, mate.
- **La sombra no es una proyección**: es un desplazamiento fijo en el plano
  de la pantalla. No se alarga con el sol bajo ni se acorta con el sol
  alto, y junto al borde del mundo debería encogerse con la curvatura.
- **Las nubes siguen siendo planas**: tienen luz y sombra en el suelo, no
  volumen ni lado oscuro propio.
- **El terminador no tiene color**. La penumbra va del 40 % de luz a la
  noche sin el rojo del crepúsculo, y el aire del borde es de un solo azul.
- **Con el zoom al máximo** la luz (una imagen de 128 píxeles de lado) y el
  aire (256) se estiran el doble; solo están medidos sin zoom.
- **El contorno**: un anillo de dos o tres píxeles donde el recorte
  suavizado del mundo mezcla la noche con lo que hay debajo. Ya estaba.
- **El velo de la Luna** sigue adelgazándose en el último píxel del borde,
  igual que hacía la noche; se dejó como estaba porque no tapa nada.
