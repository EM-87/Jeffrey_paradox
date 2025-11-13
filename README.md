# 🎲 Jeffrey Paradox - Simulador Interactivo

Una simulación visual interactiva que demuestra la **Paradoja de Jeffrey**: la divergencia matemática entre expectativas objetivas y subjetivas en selección antrópica cuántica.

## 📖 ¿Qué es la Paradoja de Jeffrey?

### El Escenario

N jugadores participan en un experimento de "ruleta rusa cuántica":
- Cada jugador tiene una probabilidad **p** de sobrevivir (típicamente 0.5)
- **Jeffrey** observa desde fuera y hace predicciones ANTES del experimento
- Los **supervivientes** calculan sus expectativas DESPUÉS (condicionados en su propia supervivencia)

### La Paradoja

Hay una divergencia sistemática entre ambas perspectivas:

```
Predicción de Jeffrey (objetiva):    E[supervivientes] = N × p
Expectativa de superviviente:         E[supervivientes | yo sobreviví] = 1 + (N-1) × p

Divergencia absoluta:                 Δ = (1 - p)
Divergencia relativa:                 Δ_rel = (1 - p) / (N × p)
```

**Resultado sorprendente**: Para p = 0.5, la divergencia Δ es siempre **0.5**, independiente del número de jugadores N.

El factor relativo Δ_rel decae como **1/N**, haciéndose más pronunciado con N pequeño.

## 🚀 Cómo Usar el Simulador

### Instalación

**No requiere instalación**. Es un archivo HTML standalone.

1. Descarga o clona este repositorio
2. Abre `jeffrey_paradox_simulator.html` en cualquier navegador moderno (Chrome, Firefox, Safari, Edge)

### Uso Básico

#### 1. **Ajustar Parámetros**
   - **N (Número de Jugadores)**: Desliza entre 2 y 1000
   - **p (Probabilidad de Supervivencia)**: Desliza entre 0.1 (10%) y 0.9 (90%)
   - Los cálculos se actualizan en tiempo real

#### 2. **Simular Una Ronda**
   - Haz clic en **"🎯 Simular Una Ronda"**
   - Verás:
     - Grid visual de jugadores (✓ = vivo, ✗ = muerto)
     - Comparación entre predicción de Jeffrey y resultado real
     - Expectativa de cada superviviente
     - Divergencia calculada

#### 3. **Visualizar Distribuciones**
   - El **histograma azul** muestra la distribución binomial de Jeffrey
   - El **histograma naranja** muestra la distribución agregada de supervivientes
   - Observa el desplazamiento de Δ ≈ 0.5 para p = 0.5

#### 4. **Analizar el Escalado**
   - Haz clic en **"📊 Actualizar Gráfico de Escalado"**
   - Muestra cómo Δ_rel decae con 1/N
   - Identifica el "sweet spot" donde el efecto es más detectable (N pequeño)

#### 5. **Ejecutar Monte Carlo**
   - Haz clic en **"🔄 Simulación Monte Carlo (10,000 rondas)"**
   - Ejecuta 10,000 experimentos para validar convergencia estadística
   - Verifica que:
     - Δ promedio ≈ (1 - p)
     - Δ_rel promedio ≈ 1/N
   - Visualiza el histograma de divergencias observadas

## 📊 Componentes del Dashboard

### 1. **Panel de Control**
Sliders interactivos para ajustar N y p en tiempo real.

### 2. **Desglose Matemático**
Muestra paso a paso:
- Cálculo de Jeffrey (ANTES)
- Cálculo de supervivientes (DESPUÉS)
- Divergencia absoluta y relativa
- Predicción teórica

### 3. **Visualización de Ronda**
Grid visual que muestra:
- Jugadores vivos (verde) y muertos (rojo)
- Estadísticas comparativas
- Expectativas divergentes

### 4. **Histogramas de Distribución**
Compara visualmente:
- Distribución binomial de Jeffrey (azul)
- Distribución de supervivientes (naranja)
- Desplazamiento Δ

### 5. **Gráfico de Escalado**
Muestra el decaimiento hiperbólico del factor Jeffrey:
- Eje X: N (escala logarítmica)
- Eje Y: Δ_rel en porcentaje
- Línea de referencia 1/N para p = 0.5

### 6. **Resultados Monte Carlo**
Validación estadística con:
- Barra de progreso en tiempo real
- Estadísticas de convergencia
- Histograma de divergencias observadas
- Comparación con valores teóricos

## 🎯 Casos de Uso Interesantes

### Experimento 1: El caso clásico (N=20, p=0.5)
```
N = 20, p = 0.5
Jeffrey predice: 10 supervivientes
Cada superviviente espera: 10.5 supervivientes
Δ = 0.5 (constante!)
Δ_rel = 5% (moderadamente detectable)
```

### Experimento 2: Sweet spot (N=2, p=0.5)
```
N = 2, p = 0.5
Jeffrey predice: 1 superviviente
Cada superviviente espera: 1.5 supervivientes
Δ = 0.5
Δ_rel = 50% (muy pronunciado!)
```

### Experimento 3: Muchos jugadores (N=1000, p=0.5)
```
N = 1000, p = 0.5
Jeffrey predice: 500 supervivientes
Cada superviviente espera: 500.5 supervivientes
Δ = 0.5
Δ_rel = 0.1% (difícil de detectar)
```

### Experimento 4: Probabilidad baja (N=50, p=0.1)
```
N = 50, p = 0.1
Jeffrey predice: 5 supervivientes
Cada superviviente espera: 5.9 supervivientes
Δ = 0.9 (mayor divergencia!)
Δ_rel = 18%
```

## 🔬 Conceptos Clave

### Efecto Antrópico
Los supervivientes están condicionados en su propia supervivencia, lo que sesga sus expectativas.

### Selección de Observador
Solo los supervivientes pueden hacer observaciones post-experimento, creando un sesgo de selección.

### Invarianza de Δ (para p=0.5)
La divergencia absoluta es **constante** en 0.5, independiente de N. Este es el resultado más sorprendente.

### Escalado 1/N
El factor relativo Δ_rel = 1/N muestra que el efecto es más pronunciado con pocos jugadores.

## 🎨 Paleta de Colores

- **Azul (#4dabf7)**: Predicción de Jeffrey (objetiva)
- **Naranja/Rojo (#ff8787)**: Expectativa de supervivientes (subjetiva)
- **Verde (#51cf66)**: Jugadores vivos
- **Rojo (#ff6b6b)**: Jugadores muertos / Divergencia

## 🛠️ Tecnologías Utilizadas

- **HTML5**: Estructura
- **CSS3**: Diseño responsive con gradientes modernos
- **JavaScript (Vanilla)**: Lógica y simulaciones
- **Chart.js 4.4.0**: Visualización de gráficos interactivos

## 📱 Compatibilidad

- ✅ Chrome/Edge (recomendado)
- ✅ Firefox
- ✅ Safari
- ✅ Opera
- ✅ Responsive: funciona en laptop, tablet y móvil

## 🧮 Matemáticas Detalladas

### Distribución Binomial (Jeffrey)
```
P(X = k) = C(n,k) × p^k × (1-p)^(n-k)
E[X] = n × p
```

### Expectativa Condicionada (Superviviente)
```
E[S | yo sobreviví] = 1 + E[otros supervivientes]
                    = 1 + (N-1) × p
```

### Derivación de la Divergencia
```
Δ = [1 + (N-1)×p] - [N×p]
  = 1 + Np - p - Np
  = 1 - p
```

Para p = 0.5:
```
Δ = 1 - 0.5 = 0.5 ✓
```

### Factor Relativo
```
Δ_rel = Δ / (N×p)
      = (1-p) / (N×p)

Para p = 0.5:
Δ_rel = 0.5 / (N × 0.5) = 1/N ✓
```

## 🤔 Preguntas Frecuentes

**P: ¿Por qué Δ = 0.5 para p = 0.5, sin importar N?**
R: Es consecuencia matemática directa del condicionamiento. El superviviente siempre se cuenta a sí mismo (el "+1"), mientras que Jeffrey no.

**P: ¿Esta paradoja es real o solo un truco matemático?**
R: Es un efecto real de selección antrópica. Tiene implicaciones en física cuántica, cosmología y probabilidad bayesiana.

**P: ¿Cuándo es más fácil detectar este efecto?**
R: Con N pequeño (2-10 jugadores), donde Δ_rel > 5%.

**P: ¿Funciona con otras distribuciones?**
R: Sí, pero la fórmula exacta de Δ cambia. Este simulador usa binomial (independiente).

## 📚 Referencias

- Richard C. Jeffrey - *The Logic of Decision* (1965)
- Nick Bostrom - *Anthropic Bias* (2002)
- David Deutsch - *The Beginning of Infinity* (2011)
- Quantum Russian Roulette thought experiments
- Sleeping Beauty problem (relacionado)

## 🐛 Solución de Problemas

**El simulador no carga:**
- Verifica que JavaScript esté habilitado en tu navegador
- Asegúrate de tener conexión a internet (se carga Chart.js desde CDN)
- Intenta con otro navegador

**Los gráficos no se muestran:**
- Revisa la consola del navegador (F12) para errores
- Verifica que Chart.js se haya cargado correctamente

**La simulación Monte Carlo es lenta:**
- Es normal, son 10,000 iteraciones
- Espera 2-5 segundos, verás la barra de progreso

## 📝 Licencia

MIT License - Uso libre para educación e investigación.

## 👨‍💻 Autor

Creado como herramienta educativa para demostrar la Paradoja de Jeffrey y efectos de selección antrópica.

---

**¡Disfruta explorando la paradoja!** 🎲✨

Para reportar bugs o sugerencias, abre un issue en el repositorio.
