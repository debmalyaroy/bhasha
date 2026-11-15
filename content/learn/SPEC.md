# Course content format

Each language has a folder `content/learn/<code>/` (`hi`, `bn`, `kn`). `tools/build_courses.py` merges the
folder into `app/src/main/assets/learn/<code>.json` and checks it. Never edit the merged file by hand.

All files are UTF-8 JSON (NFC-normalised, no BOM).

## `00-course.json`

```json
{
  "code": "bn", "name": "Bengali", "nativeName": "বাংলা", "script": "Bengali", "ttsLocale": "bn-IN",
  "about": [ GuideSection, ... ],
  "letters": [
    { "group": "Vowels", "note": "one or two sentences about the group",
      "items": [ { "char": "অ", "roman": "o", "example": "অজগর", "exampleRoman": "ojogor",
                   "exampleMeaning": "python", "note": "how to say it / what it is confused with" } ] }
  ]
}
```

- `about`: 6-10 sections explaining how the script and sound system work for an English speaker: how
  consonants carry an inherent vowel, vowel signs, conjuncts, aspirated vs unaspirated, dental vs retroflex,
  nasalisation, anything where spelling and pronunciation differ, and the romanisation key used in this course.
- `letters` groups, in this order: Vowels, Consonants, Vowel signs (shown on one consonant), Other signs
  (anusvara, visarga, chandrabindu, virama etc. as applicable), Common conjuncts (15-25 of the most frequent),
  Numerals (0-9, with the number word as the example).
- Every letter in the standard alphabet of the language must be present, in traditional order.
- `char` must be something a text-to-speech engine can read and a learner can trace.

## Unit files `NN-<id>.json` (NN = two-digit order)

```json
{
  "id": "greetings", "level": "1 · First words", "title": "Greetings & basics",
  "description": "one line: what the learner can do after this unit",
  "guide": [ { "title": "...", "text": "...", "examples": [ Phrase, ... ] } ],
  "phrases": [ { "native": "...", "roman": "...", "english": "...", "literal": "...", "note": "..." } ]
}
```

- `guide`: 2-5 sections that actually teach: the rule, when it applies, the exceptions a beginner meets, and
  2-6 examples per section. Plain text only (no markdown); short paragraphs separated by `\n\n`. Tables are
  written as examples (one row per example).
- `phrases`: 12-16 per unit. These feed the quiz (pick the right meaning out of four) and speaking practice,
  so within a unit every `native` and every `english` must be unique, and each phrase should be a complete,
  natural utterance of 1-8 words that someone would really say.
- `literal`: word-for-word gloss in the original word order, e.g. "I rice eat". Required unless the phrase
  is a single word.
- `note`: optional; usage, register, regional variation, or a trap.
- Keep the ids of the ten existing units (`greetings`, `introductions`, `numbers`, `food`, `directions`,
  `shopping`, `family`, `time`, `emergencies`, `conversation`) - learners' saved scores are keyed on them.

## Romanisation

One consistent learner-friendly scheme per language, lower case, documented in an `about` section, and the
same as the scheme already used in `app/src/main/assets/learn/<code>.json` (long vowels doubled: aa, ee, oo;
aspirates with h; retroflex vs dental distinction explained in `about` - if the scheme marks it, mark it
everywhere). It must reflect how the phrase is pronounced, not letter-by-letter transliteration, where the
two differ (e.g. Bengali inherent vowel, Hindi schwa deletion). Speaking practice scores the learner's
romanised transcript against `roman`, so it has to be regular.

## Correctness rules

- Standard, widely understood modern usage: Hindi as spoken in Delhi/standard Hindi; Bengali as standard
  Kolkata colloquial (cholito), noting Bangladesh differences where they matter; Kannada as standard
  spoken Bengaluru/Mysuru colloquial, giving the written form in a note where it differs noticeably.
- Use the polite form (aap / aapni / neevu) by default; teach the familiar forms explicitly in the unit on
  pronouns and respect.
- Spelling must be the accepted standard spelling. No invented words. If unsure of a form, choose a
  simpler sentence you are sure of.
- Gender/number agreement must be right, and where a sentence changes with the speaker's gender (Hindi),
  give the masculine form and put the feminine form in `note`.
- English translations must be natural English, with the literal sense in `literal`.
- Anything you are less than fully certain about goes in `content/learn/<code>/REVIEW.md` (unit, phrase,
  what the doubt is) rather than silently into the course.

## Curriculum (same skeleton for each language; adapt titles and add what the language needs)

1 · First words: greetings (id greetings); introductions; yes, no and polite words; numbers 1-10 (id numbers)
2 · How sentences work: word order and "to be"; pronouns and respect; my, your, his (possession);
    case endings / postpositions (in, on, to, from, with); asking questions; saying no (negation);
    this, that, here, there; describing things (adjectives, agreement, comparison)
3 · Verbs: present habitual; happening now (continuous); past; future; requests and commands;
    want, need, can, must; have and have not; compound/helper verbs that the language relies on
4 · Everyday situations: food (id food); directions (id directions); shopping with numbers 11-100 and money
    (id shopping); family (id family); time, days and dates (id time); weather and seasons; health and
    emergencies (id emergencies); phone and appointments; travel and staying somewhere; at work or study
5 · Conversation: small talk (id conversation); feelings and opinions; joining sentences (and, but,
    because, if, when); two or three complete dialogues presented line by line

Roughly 30 units and 400 phrases per language.
