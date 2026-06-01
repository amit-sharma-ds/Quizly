<div align="center">

# ⚡ Quizly

### RAG Implementation from Scratch — in Pure Java. No Libraries. No Embeddings. No Excuses.

[![Java](https://img.shields.io/badge/Language-Java%2017+-orange?style=flat-square&logo=openjdk)](https://openjdk.org/)
[![Lines](https://img.shields.io/badge/Lines%20of%20Code-632-blue?style=flat-square)](./Quizly2.java)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square)](./LICENSE)
[![Event](https://img.shields.io/badge/Code%20Olympics-2026-purple?style=flat-square)](#code-olympics-2026)

<br/>

**Paste any document. Get a quiz. Instantly.**  
*A document-driven, adaptive quiz generator powered by a hand-rolled RAG pipeline — built entirely in Java Swing with zero external dependencies.*

<br/>

![Quizly Demo](https://img.shields.io/badge/Demo-Interactive%20Preview%20Available-cyan?style=flat-square)

</div>

---

## What is Quizly?

Most study tools make you create questions manually. That's slow, boring, and it doesn't scale.

**Quizly solves this**: paste any raw text — lecture notes, a Wikipedia article, a research paper, your own study material — and Quizly automatically generates a full quiz from it in seconds. No setup. No API keys. No internet connection required.

### The Real-World Problem It Solves

| Problem | How Quizly Fixes It |
|---|---|
| Students spend more time making flashcards than studying | Auto-generates MCQ, T/F, and fill-in-the-blank from any text |
| Generic quiz apps don't understand your content | RAG pipeline retrieves the most relevant chunks from *your* document |
| One-size-fits-all difficulty is discouraging | Adaptive engine adjusts difficulty per answer in real time |
| Requires internet, accounts, or subscriptions | Fully offline, single `.java` file, runs anywhere Java runs |
| PDFs and notes are hard to convert into quizzes | Plain text paste is enough — the engine handles the rest |

---

## 🏆 Code Olympics 2026

> *How it works: four random constraints, one assigned language.*

```
D1 — Constraint   : Simple State Machine Creator
D2 — Budget       : Professional Builder  (700–900 lines)
D3 — Domain       : Quiz Systems
D4 — Language     : Java
```

This project was built under those exact four constraints for **Code Olympics 2026**.  
Every design decision — the 3-state architecture, the rule-based RAG, the single-file structure — exists because of them.

**D1 (Simple State Machine)** forced a clean 3-state app: `HOME → QUIZ → RESULTS`. No routing library, no framework. A `CardLayout` and a `UIController` that's just a state machine in disguise.

**D2 (Professional Builder)** pushed visual polish above what the line budget would normally allow — custom-painted neon panels, animated particles, a glow button with timer-based float interpolation.

**D3 (Quiz Systems)** defined the entire product: adaptive difficulty, three question types, performance tracking, and weak-area detection.

**D4 (Java)** meant zero ML libraries, zero NLP toolkits. Everything — chunking, retrieval scoring, question generation, UI — had to be written from scratch with `java.util` and `javax.swing`.

---

## RAG Pipeline: From Scratch in Java

> No `LangChain`. No `LlamaIndex`. No vector database. No embeddings API.  
> This is **Retrieval-Augmented Generation built from first principles**, entirely in standard Java.

Modern RAG systems use dense vector embeddings to find semantically similar passages. This project implements the same *conceptual pipeline* using term-frequency scoring — a legitimate sparse retrieval method used in classic IR systems like BM25.

### The Full Pipeline

```
┌─────────────────────────────────────────────────────────────────────┐
│                        QUIZLY RAG PIPELINE                          │
└─────────────────────────────────────────────────────────────────────┘

  USER INPUT (raw text / paste from PDF / notes / article)
       │
       ▼
┌──────────────────┐
│  INGESTION       │  TextProcessor.chunk()
│                  │  → Split on [.!?\n]
│  "Chunking"      │  → Filter chunks < 20 chars
│                  │  → Store in ArrayList<String>
└────────┬─────────┘
         │  chunks[]
         ▼
┌──────────────────┐
│  INDEXING        │  TextProcessor.termFreq()
│                  │  → Build global term→frequency map
│  "Index Build"   │  → Stop-word filtered
│                  │  → Per-chunk frequency maps computed
└────────┬─────────┘
         │  globalFreq{}, chunkFreq{}
         ▼
┌──────────────────┐
│  RETRIEVAL       │  RetrievalEngine.score(chunk)
│                  │  → For each term t in chunk:
│  "Sparse Search" │    score += chunkFreq(t) × (1 + globalFreq(t)/2)
│                  │  → Sort chunks by score descending
│                  │  → Filter by difficulty tier (word count proxy)
└────────┬─────────┘
         │  topChunks(n, difficultyLevel)
         ▼
┌──────────────────┐
│  GENERATION      │  QuizEngine.generate(chunk)
│                  │  → MCQ   : highest-freq term as answer
│  "Synthesis"     │  → T/F   : real chunk (True) or mutate() (False)
│                  │  → Fill  : blankify() replaces key word with _____
│                  │  → Distractors from global freq pool
└────────┬─────────┘
         │  Question[]
         ▼
┌──────────────────┐
│  ADAPTIVE LOOP   │  QuizEngine.answer()
│                  │  → Correct → difficulty++ → longer chunks next
│  "Feedback"      │  → Wrong   → difficulty-- → shorter chunks next
│                  │  → Track wrongChunks[] for Results weak-area display
└──────────────────┘
```

### Why This Is Actually RAG

Real RAG = **Retrieve** relevant context + **Augment** a prompt + **Generate** an answer.

| RAG Component | Real System | Quizly Implementation |
|---|---|---|
| **Document Store** | Vector DB (Pinecone, Chroma) | `ArrayList<String> chunks` |
| **Embedding Model** | OpenAI `text-embedding-3` | `termFreq()` sparse vectors |
| **Similarity Search** | Cosine distance on vectors | Weighted term-freq scoring |
| **Retrieval** | Top-K ANN search | `topChunks(n, diffLevel)` sorted by score |
| **Generation** | LLM prompt + context | Template engine + `blankify()` + `mutate()` |
| **Augmentation** | Prompt stuffing | Chunk injected directly into question prompt |

The math behind `RetrievalEngine.score()`:

```
score(chunk) = Σ  chunkFreq(t) × (1 + globalFreq(t) / 2)
              t ∈ chunk
```

This is intentionally similar to **TF-IDF** and **BM25** scoring — industry-standard sparse retrieval used in Elasticsearch and Lucene. The difference: we skip IDF (inverse document frequency) because our corpus is a single document, making global frequency a reasonable substitute.

### PDF Upload Flow (How It Works End-to-End)

While the app accepts pasted text, here's how a **PDF → Quiz** workflow maps to the pipeline:

```
PDF File
   │
   ▼  (Copy text from PDF reader, or use pdftotext / Apache PDFBox upstream)
Paste into Quizly text area
   │
   ▼
TextProcessor.chunk()         ← sentence boundary detection
   │
   ▼
RetrievalEngine               ← builds sparse index over chunks
   │
   ▼
QuizEngine.topChunks()        ← retrieves most information-dense sentences
   │
   ▼
generate() × N questions      ← MCQ / T-F / Fill-blank synthesised per chunk
   │
   ▼
Quiz presented adaptively     ← difficulty adjusts per answer
   │
   ▼
Results + weak chunk report   ← shows exactly which source sentences you got wrong
```

The "retrieved chunk" shown at the bottom of each quiz question is the **exact source passage** the question was generated from — this is the RAG transparency principle: every answer is traceable back to your document.

---

## Architecture

```
Quizly2.java  (single file, 632 lines)
│
├── TextProcessor           Pure static utility class
│   ├── chunk(text)         Sentence splitter → ArrayList<String>
│   ├── termFreq(text)      Stop-word filtered frequency map
│   ├── blankify(sentence)  Selects a key word → replaces with _____
│   └── capitalize(s)       Helper
│
├── RetrievalEngine         Pseudo-RAG core
│   ├── score(chunk)        Weighted TF scoring per chunk
│   └── topChunks(n, diff)  Returns n best chunks at given difficulty tier
│
├── Question                Data model
│   └── Type enum           MCQ | TRUE_FALSE | FILL_BLANK
│
├── QuizEngine              Question lifecycle manager
│   ├── generate(chunk)     Routes to MCQ / T-F / Fill-blank generator
│   ├── makeTF(chunk)       True/False with mutate() for false statements
│   ├── buildDistractors()  Wrong-answer pool from global freq
│   └── answer(chosen)      Scores + updates adaptive difficulty
│
├── NeonPanel               Custom JPanel with glassmorphism paint
├── ParticleCanvas          Animated 55-particle background
├── GlowButton              Hover-animated neon button
├── RoundedBorder           Custom AbstractBorder helper
│
└── UIController            3-state app controller (JFrame + CardLayout)
    ├── buildHome()         State 0 — text input + controls
    ├── buildQuizPanel()    State 1 — question renderer
    ├── renderQuestion()    Per-question UI with answer buttons
    ├── handleAnswer()      Feedback overlay + state transition
    └── buildResults()      State 2 — animated score + weak areas
```

### 3-State Machine

```
         ┌─────────────────────────────────────┐
         │              HOME                    │
         │   Paste text → slider → Generate     │
         └─────────────────┬───────────────────┘
                           │ startQuiz()
                           ▼
         ┌─────────────────────────────────────┐
         │           QUIZ ARENA                 │
         │   Question → Answer → Feedback loop  │◄──┐
         └─────────────────┬───────────────────┘   │
                           │ all questions done      │ restart()
                           ▼                         │
         ┌─────────────────────────────────────┐    │
         │             RESULTS                  │    │
         │   Score ring → Rank → Weak areas     │────┘
         └─────────────────────────────────────┘
                           │ newDoc()
                           ▼
                         HOME
```

---

## Question Types

### MCQ — Multiple Choice
```
Source chunk : "Machine learning allows computers to learn from data"
               ↓  termFreq() → highest scored term = "learning"
Generated    : "What best describes: 'Machine learning allows...'?"
               A) Learning   B) Network   C) System   D) Process
```

### True / False
```
Source chunk : "Deep learning uses neural networks with many layers"
               ↓  Math.random() > 0.45 → False branch → mutate()
Generated    : True or False: "Deep learning avoids neural networks..."
Answer       : False
```

### Fill in the Blank
```
Source chunk : "Natural language processing enables computers to understand language"
               ↓  blankify() → picks "processing" (length > 4)
Generated    : Fill in the blank: "Natural language _____ enables computers..."
Answer       : Processing
```

---

## Adaptive Difficulty Engine

```java
// After each answer in QuizEngine.answer():
if (correct) {
    score++;
    difficulty = Math.min(3, difficulty + 1);  // harder next question
} else {
    wrongChunks.add(chunk);
    difficulty = Math.max(1, difficulty - 1);  // simpler next question
}

// RetrievalEngine filters chunks by difficulty tier:
// Level 1 → chunks with ≤ 15 words  (direct, short facts)
// Level 2 → chunks with > 8 words   (medium complexity)
// Level 3 → chunks with > 14 words  (dense, multi-concept)
```

The difficulty variable silently controls which chunks get retrieved — longer, denser passages = harder questions. Users never see the level; they just feel the progression.

---

## Getting Started

### Prerequisites
- Java 17 or higher (`java -version`)
- No build tool needed. No Maven. No Gradle. One file.

### Run

```bash
# Clone
git clone https://github.com/yourusername/quizly.git
cd quizly

# Compile
javac Quizly2.java

# Run
java Quizly2
```

### How to Use

1. **Paste text** into the input area — any subject, any length (100+ words works best)
2. **Set question count** using the slider (4–15)
3. Click **GENERATE QUIZ →**
4. Answer each question — instant feedback shown after each
5. View **Results** — see your score, rank, and which source chunks you got wrong
6. Click **NEW DOCUMENT** to quiz a different text, or **PLAY AGAIN** to retry the same one

### Recommended Text Sources
- Copy-paste from PDF (Ctrl+A → Ctrl+C in any PDF viewer)
- Wikipedia articles
- Textbook chapters
- Lecture notes
- News articles
- Your own study material

---

## Visual Design

| Element | Implementation |
|---|---|
| Particle background | 55 particles, per-frame canvas repaint, connection lines < 75px apart |
| Neon title pulse | `Math.sin(t)` on RGB channel, `requestAnimationFrame`-style Swing timer |
| Glow button | `float glow` interpolated 0→1 on hover via `javax.swing.Timer` at 16ms |
| Progress bar | `LinearGradient` painted on custom JPanel, width animated on question change |
| Results circle | Arc drawn progressively each timer tick, score counter increments in sync |
| Glass panels | Semi-transparent fill + white top highlight + neon border in `paintComponent` |

All animations are driven by `javax.swing.Timer` at ~16ms (≈60fps) — no threads, no `Thread.sleep()`, no EDT violations.

---

## Constraints Reflection

| Constraint | Challenge | Solution |
|---|---|---|
| **Java only** | No NLP, no ML, no vector math libraries | TF scoring as sparse retrieval; template-based generation |
| **Simple state machine** | 3 states must cover full UX | Home/Quiz/Results with `CardLayout` swap; state stored as fields in `UIController` |
| **Professional builder** | Visual quality expected at 700–900 lines | Custom-painted components; reuse `NeonPanel` and `GlowButton` across all states |
| **Quiz systems domain** | Must feel intelligent despite being rule-based | Source chunk shown per question; adaptive difficulty; 3 question types; weak-area tracking |

---

## License

MIT — use it, fork it, submit it, improve it.

---

<div align="center">

**Built for Code Olympics 2026** · Pure Java · Zero Dependencies · RAG from Scratch

*"Any sufficiently well-engineered rule-based system is indistinguishable from AI."*

</div>
