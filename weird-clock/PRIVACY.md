# Weird Clock — Privacy Policy / Política de privacidad

_Last updated / Última actualización: 2026-11-25_

## English

**Weird Clock has no accounts, no analytics and no server of its own: it
never sends anything to its author.** It uses the network only for two
optional features, both off until you switch them on. What they send, and
to whom, is set out below.

- **Network access** (optional, off by default): the app makes no network
  requests at all until you turn on *Weather* or *Tell the house (IFTTT)*.
  Turn them off again and the requests stop.
  - **Weather.** With the *Weather* switch on, the app asks three public
    forecast services — Open-Meteo (`api.open-meteo.com`), MET Norway
    (`api.met.no`) and wttr.in — for the weather where you are. The only
    thing about you in the request is your latitude and longitude,
    rounded to two decimal places: about a kilometre. It asks again once
    its last answer is half an hour old, and only while the app is open
    or its weather widget is on your home screen. It asks sooner after a
    round that fails, when you press *Find me again* on the widget, or
    if you have sped the clock up with *Time speed (%)*. If the app has
    no location, it asks nothing.
  - **Pictures of the Earth.** With *Weather* on, and while the app is
    open with *Hemispheric* chosen as its kind of clock (the globe), the
    app also downloads pictures of the whole planet. From NASA's GIBS
    (`gibs.earthdata.nasa.gov`): the daily satellite photograph for the
    globe's clouds (*Satellite clouds*, on unless you turn it off) and,
    if you choose them, last night's lights (*Night lights*). From NOAA's
    nowCOAST
    (`nowcoast.noaa.gov`): the hourly satellite mosaic, only if you
    choose the *Live, by the hour* clouds. These requests ask for the
    entire Earth on a given day or hour. There is no location in them.
  - **Tell the house (IFTTT).** With this switch on and your own IFTTT
    webhook key typed in, the app sends IFTTT (`maker.ifttt.com`) a
    message at five fixed moments: when an alarm or reminder is about to
    ring, when it rings, when it is snoozed or stopped on its ringing
    screen, and when a countdown runs out and rings. The message carries
    the name of the moment, your key, the alarm's or reminder's label
    (for a countdown, the words "Countdown finished" in the app's
    language), the time of day on your phone and a number of minutes (of
    warning, or of snooze). Without a key nothing is sent, and no other
    address can be set.
  - **What any request reveals.** Every request necessarily reveals your
    device's IP address to the service it is sent to, the picture
    requests included. Each also names the app and its GitHub
    repository, in the same words on every device:
    `WeirdClock/1.0 (github.com/EM-87/Jeffrey_paradox)`.
    Beyond what is listed here, the app adds nothing that identifies you
    or your device: no account, no cookie, no device identifier. All
    requests go over HTTPS. What a service does with what it receives is
    governed by its own privacy policy, not by this one.
- **Location** (optional): the app asks for your approximate location —
  never the precise one — the first time it runs and, while *Sun time*
  is on without it, each time the app starts, until you allow it or
  Android stops showing the request. If you allow it, it reads the
  device's last known position, or takes a single fix if there is none,
  and keeps the latitude and longitude on your device. It reads them
  again only while the app is open or when you press *Find me again* on
  the weather widget, never on its own in the background. They are used
  to work out the sun and the sky where you are: sunrise and sunset,
  *Sun time*, the sundial and the globe. The app transmits them only as
  described under *Weather* above: rounded, and only with that switch on.
  Android's own backup can carry them too; see *Android's own backup*
  below.
- **Calendar** (optional): if you turn on *Your calendar*, the app asks
  for permission to read the device's calendar and draws what it finds
  on the clock and on the month page. It can only read: it has no
  permission to add, change or delete an event. Events are read each
  time they are drawn and are not kept: they are written to no file, are
  in no backup, and are never sent anywhere.
- **Cycle** (optional): if you mark your periods under *Cycle*, the day
  each one starts and how many days it lasts are kept on your device.
  They are in your backups (see below) and are never sent anywhere.
- **Sensors**: the accelerometer, the orientation sensor and the light
  sensor are read only while the app is open, and for these things only:
  the knock-the-hands-off physics, the hourglass and its flip, alarms
  that have to be shaken off, pointing the globe or the sundial at the
  real sun, and fitting the screen of a *Gentle wake* to the light in the
  room. Readings are used in the moment, never stored and never sent.
- **Custom alarm sounds**: if you pick an audio file for an alarm, the app
  keeps a read permission for that file so the alarm can play it. The file
  itself is never copied or uploaded.
- **Overlay permission** (optional): used exclusively to draw the floating
  hourglass while a countdown runs.
- **Backups** (optional): *Save a backup* and *Automatic backups* write
  what the app keeps — alarms, reminders, your cycle record, your
  birthday and your settings, the stored location included — to a file
  or a folder you choose. The webhook key and the last weather readings
  are left out. The app itself sends the file nowhere. Restoring one
  puts the settings back as they were saved, the *Weather* switch
  included.
- **Android's own backup**: the app lets Android back up its data. If
  backup is turned on in your phone's settings, Android can copy what
  the app keeps — the stored location, the cycle record and the last
  weather readings among it — into your phone's backup (usually your
  Google account) and onto a new phone when you move to one. Never the
  webhook key: the app keeps it in a file of its own, which both the
  backup and the transfer leave out.
- **No analytics, no ads, no third-party SDKs, no accounts.**

## Español

**Weird Clock no tiene cuentas, ni analíticas, ni servidor propio: nunca
envía nada a su autor.** Solo usa la red para dos funciones opcionales,
apagadas hasta que tú las enciendas. Lo que envían, y a quién, se detalla
a continuación.

- **Acceso a la red** (opcional, apagado por defecto): la app no hace
  ninguna petición de red hasta que enciendas *Clima* o *Contárselo a la
  casa (IFTTT)*. Si los vuelves a apagar, las peticiones cesan.
  - **Clima.** Con el interruptor *Clima* encendido, la app pregunta a
    tres servicios públicos de predicción — Open-Meteo
    (`api.open-meteo.com`), MET Norway (`api.met.no`) y wttr.in — por el
    tiempo que hace donde estás. Lo único sobre ti que lleva la petición
    es tu latitud y tu longitud, redondeadas a dos decimales: más o menos
    un kilómetro. Vuelve a preguntar cuando su última respuesta tiene
    media hora, y solo mientras la app está abierta o su widget del clima
    está en tu pantalla de inicio. Pregunta antes tras una ronda fallida,
    cuando pulsas *Buscarme otra vez* en el widget, o si has acelerado el
    reloj con *Velocidad del tiempo (%)*. Si la app no tiene ubicación,
    no pregunta nada.
  - **Imágenes de la Tierra.** Con *Clima* encendido, y mientras la app
    está abierta con *Hemisférico* elegido como tipo de reloj (el globo),
    la app descarga además imágenes del planeta entero. De GIBS, de la
    NASA (`gibs.earthdata.nasa.gov`): la foto diaria de satélite para las
    nubes del globo (*Nubes de satélite*, encendido salvo que lo apagues)
    y, si las eliges, las luces de anoche (*Luces nocturnas*). De
    nowCOAST, de la NOAA
    (`nowcoast.noaa.gov`): el mosaico horario de satélite, solo si eliges
    las nubes *En directo, hora a hora*. Estas peticiones piden la Tierra
    entera en un día o una hora dados. No llevan ninguna ubicación.
  - **Contárselo a la casa (IFTTT).** Con este interruptor encendido y tu
    propia clave de webhook de IFTTT escrita, la app envía a IFTTT
    (`maker.ifttt.com`) un mensaje en cinco momentos fijos: cuando una
    alarma o un recordatorio está a punto de sonar, cuando suena, cuando
    se pospone o se detiene en su pantalla de alarma, y cuando termina
    una cuenta atrás y suena. El mensaje lleva el nombre del momento, tu
    clave, la etiqueta de la alarma o del recordatorio (en una cuenta
    atrás, las palabras «Cuenta atrás finalizada» en el idioma de la
    app), la hora del día en tu teléfono y un número de minutos (de aviso
    o de posposición). Sin clave no se envía nada, y no se puede poner
    ninguna otra dirección.
  - **Lo que revela cualquier petición.** Toda petición revela
    necesariamente la dirección IP de tu dispositivo al servicio al que
    se envía, incluidas las de imágenes. Cada una nombra además la app y
    su repositorio de GitHub, con las mismas palabras en todos los
    dispositivos:
    `WeirdClock/1.0 (github.com/EM-87/Jeffrey_paradox)`.
    Aparte de lo que aquí se enumera, la app no añade nada que te
    identifique a ti ni a tu dispositivo: ni cuenta, ni cookies, ni
    identificador del dispositivo. Todas las peticiones van por HTTPS. Lo
    que cada servicio haga con lo que recibe se rige por su propia
    política de privacidad, no por esta.
- **Ubicación** (opcional): la app pide tu ubicación aproximada — nunca
  la precisa — la primera vez que se abre y, mientras la *Hora del sol*
  esté encendida sin ella, cada vez que se inicia, hasta que la permitas
  o Android deje de mostrar la petición. Si la permites, lee la última
  posición conocida del dispositivo, o toma una sola lectura si no hay
  ninguna, y guarda la latitud y la longitud en tu dispositivo. Solo
  vuelve a leerlas con la app abierta o cuando pulsas *Buscarme otra vez*
  en el widget del clima, nunca por su cuenta en segundo plano. Se usan
  para calcular el sol y el cielo donde estás: la salida y la puesta del
  sol, la *Hora del sol*, el reloj de sol y el globo. La app solo las
  transmite como se describe en *Clima*, más arriba: redondeadas, y solo
  con ese interruptor encendido. La copia de seguridad del propio
  Android también puede llevarlas; consulta *La copia de seguridad de
  Android*, más abajo.
- **Calendario** (opcional): si enciendes *Tu calendario*, la app pide
  permiso para leer el calendario del dispositivo y dibuja lo que
  encuentra en el reloj y en la página del mes. Solo puede leer: no tiene
  permiso para añadir, cambiar ni borrar ningún evento. Los eventos se
  leen cada vez que se dibujan y no se conservan: no se escriben en
  ningún archivo, no entran en ninguna copia de seguridad y nunca se
  envían a ninguna parte.
- **Ciclo** (opcional): si marcas tus periodos en *Ciclo*, el día en que
  empieza cada uno y cuántos días dura se guardan en tu dispositivo.
  Entran en tus copias de seguridad (ver más abajo) y nunca se envían a
  ninguna parte.
- **Sensores**: el acelerómetro, el sensor de orientación y el sensor de
  luz se leen solo con la app abierta, y solo para esto: las físicas de
  derribar las manecillas, el reloj de arena y su volteo, las alarmas que
  hay que sacudir para detenerlas, apuntar el globo o el reloj de sol al
  sol de verdad, y ajustar la pantalla de un *Despertar suave* a la luz de
  la habitación. Las lecturas se usan al momento, nunca se almacenan y
  nunca se envían.
- **Sonidos de alarma propios**: si eliges un archivo de audio para una
  alarma, la app conserva un permiso de lectura sobre ese archivo para
  poder reproducirlo. El archivo nunca se copia ni se sube.
- **Permiso de superposición** (opcional): se usa exclusivamente para
  dibujar el reloj de arena flotante mientras corre una cuenta atrás.
- **Copias de seguridad** (opcional): *Guardar una copia* y *Copias
  automáticas* escriben lo que la app conserva — alarmas, recordatorios,
  tu registro del ciclo, tu cumpleaños y tus ajustes, incluida la
  ubicación guardada — en un archivo o en una carpeta que tú elijas. La
  clave del webhook y las últimas lecturas del clima se quedan fuera. La
  propia app no envía ese archivo a ninguna parte. Restaurar una copia
  devuelve los ajustes tal como se guardaron, incluido el interruptor
  *Clima*.
- **La copia de seguridad de Android**: la app deja que Android haga
  copia de sus datos. Si la copia de seguridad está activada en los
  ajustes de tu teléfono, Android puede copiar lo que la app conserva —
  entre ello la ubicación guardada, el registro del ciclo y las últimas
  lecturas del clima — en la copia de tu teléfono (normalmente tu cuenta
  de Google) y en un teléfono nuevo cuando te pasas a él. Nunca la clave
  del webhook: la app la guarda en un archivo aparte, que tanto la copia
  como el traspaso dejan fuera.
- **Sin analíticas, sin anuncios, sin SDK de terceros, sin cuentas.**
