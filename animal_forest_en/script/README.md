# Our own text

Files here are ours: translations for the messages that have no official
English (the debug messages and the lines the GameCube dropped, 6% of the
bank: `make script` lists them as `TODO translate`), and fixes we decide
on. They are in `tools/msgbank.py`'s dump form (`## number`, the message,
`<CODE hex>` for control codes, `#:` notes), and `make script` lays every
`script/*.txt` over the draft by number (`tools/af_text.py draft
--overrides`). Nothing from the GameCube disc goes here: the official text
is read from the user's disc at build time and never enters the repository.
