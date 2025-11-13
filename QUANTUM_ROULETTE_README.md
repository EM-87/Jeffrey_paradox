# 🎲 Quantum Russian Roulette - Jeffrey Paradox Visualizer

An interactive game that visualizes the **Jeffrey Paradox** through quantum branching in a deadly game of Russian Roulette.

## 🎯 What is the Jeffrey Paradox?

The Jeffrey Paradox demonstrates a fundamental tension in quantum mechanics between:

- **Jeffrey's Expectation (External Observer)**: Before the experiment, an external observer expects **E[survivors] = 3.0** (6 players × 0.5 survival rate)
- **Survivor's Expectation (Branch-Conditional)**: From the perspective of someone who survives, the expected number of survivors is **E[survivors | you survive] = 3.5**

This **systematic bias of +0.5 survivors** arises because survivors only observe branches where they survive, creating a selection effect that shifts their rational expectations away from the objective prior.

## 🎮 How to Play

### Starting the Game

1. Open `quantum_roulette_game.html` in any modern web browser
2. Click **🚀 Start Game**
3. Click **▶ Fire Round** to execute each round of the game

### Game Mechanics

- **6 Players** start in the initial state (all alive)
- Each round, reality **branches into all possible outcomes**:
  - Each player either survives (🟢) or dies (🔴)
  - This creates **2^6 = 64 branches per round**
- The tree grows exponentially, showing all Many-Worlds branches
- Green bubbles = more survivors, Red bubbles = fewer survivors
- Bubble size indicates the number of survivors

### Controls

| Button | Function |
|--------|----------|
| 🚀 Start Game | Begin the quantum roulette |
| ▶ Fire Round | Execute the next round (branches all current states) |
| 🔄 Reset | Return to initial state |
| 🎯 Enter Random Branch | Select a random branch where you survive (survivor's perspective) |
| 💡 Highlight Jeffrey | Highlight branches near Jeffrey's expectation (≈3 survivors) |
| + / - | Zoom in/out |
| ⌂ | Reset view |

### View Filters

- **Show all branches**: Display every possible outcome
- **Only ≥3 survivors**: Filter to "average" outcomes (near Jeffrey's expectation)
- **Only "lucky" (≥5 survivors)**: See only the most fortunate branches
- **Only observable (≥1 survivor)**: Hide branches with no observers (the Anthropic Shadow)

## 📊 Understanding the Statistics Panel

### Divergent Expectations

```
📊 Jeffrey (pre-experiment): 3.00
   - External observer's rational expectation
   - Based on Born rule probabilities

👤 Survivor (conditional): 3.50
   - Expected survivors from inside a surviving branch
   - Conditioned on "you survive"

🔺 Jeffrey Factor (Δ): +0.50
   - The systematic bias
   - Always positive for symmetric scenarios
   - Represents the "anthropic" selection effect
```

### Branch Distribution

Shows the percentage of branches with each survivor count. The distribution follows a **binomial distribution** centered around Jeffrey's expectation (3 survivors), but survivors experience a **shifted distribution** weighted toward higher survivor counts.

## 🎨 Visual Legend

| Color | Meaning |
|-------|---------|
| 🟢 Green | High survivor count (alive) |
| 🔴 Red | Low survivor count (dead) |
| 💛 Yellow Glow | Your selected branch (Survivor Mode) |
| 💙 Blue Glow | Jeffrey center (branches near E=3) |
| Transparent Red | Unobservable branches (no survivors) |

## 🧠 Key Insights

### 1. Exponential Growth
- Round 1: 64 branches
- Round 2: 4,096 branches
- Round 3: 262,144 branches
- Round 10: **2^60 ≈ 10^18 branches** (visualizer limits rounds for performance)

### 2. The Anthropic Shadow
Branches with **0 survivors** are unobservable (no one exists to observe them). These are shown semi-transparent and represent the "anthropic shadow" - outcomes that happen but are never experienced.

### 3. Selection Bias
When you click **"Enter Random Branch"**, you're randomly placed in a surviving branch. Notice how the expected number of survivors in your branch is **always higher** than Jeffrey's pre-experiment expectation. This is not luck - it's a mathematical necessity.

### 4. Many-Worlds Interpretation
This game visualizes the **Everett Many-Worlds** interpretation of quantum mechanics:
- All possible outcomes occur
- Reality branches at each quantum event
- There's no "collapse" - all branches are equally real
- You experience only one branch, but "you" exist in many branches

## 🔬 Technical Details

### Implementation
- **Pure JavaScript** (no frameworks)
- **HTML5 Canvas** for rendering
- **Reingold-Tilford algorithm** for tree layout
- **Smooth animations** using requestAnimationFrame
- **Interactive zoom & pan** for exploring large trees

### Data Structure
```javascript
class BubbleState {
  players: [boolean, boolean, ...]  // true = alive
  survivors: number
  probability: number                // Born rule probability
  parentBranch: BubbleState | null
  childBranches: [BubbleState, ...]
  position: {x, y}
}
```

### Branching Algorithm
For each existing branch:
1. Generate all 2^n possible outcomes for n players
2. Each outcome is equally probable (Born rule)
3. Create child BubbleState for each outcome
4. Layout using tree algorithm to avoid overlap

## 🎓 Educational Use

This visualizer is ideal for:
- **Philosophy of Science**: Understanding observer selection effects
- **Quantum Mechanics**: Visualizing Many-Worlds interpretation
- **Probability Theory**: Branch-conditional probabilities
- **Decision Theory**: Anthropic reasoning and self-locating beliefs

## 🚀 Browser Compatibility

Works on all modern browsers:
- ✅ Chrome/Edge 90+
- ✅ Firefox 88+
- ✅ Safari 14+
- ✅ Opera 76+

## 📚 Further Reading

- **Richard Jeffrey** (1983): "The Logic of Decision" - Original source of the paradox
- **David Lewis** (2004): "How Many Lives Has Schrödinger's Cat?" - Analysis of quantum branching
- **Nick Bostrom** (2002): "Anthropic Bias" - Book on observer selection effects
- **Hilary Greaves** (2007): "Probability in the Everett Interpretation" - Technical analysis

## 🎯 Try This

1. **Start the game** and fire 3-4 rounds
2. Click **"Enter Random Branch"** - you'll be placed in a surviving branch
3. Notice the **yellow glow** on your branch
4. Look at the **Survivor expectation** in the stats panel
5. Click **"Highlight Jeffrey"** to see where Jeffrey expects to find you
6. **Question**: Why are you almost never in the blue (Jeffrey center) branches?

**Answer**: Because you can only exist in surviving branches, and among those, higher-survivor branches are more likely to contain you specifically. This is the Jeffrey Paradox in action!

---

**Built with ❤️ for quantum philosophy enthusiasts**

*"The universe doesn't branch. You branch." - Everett (paraphrased)*
