# Our own text

Files here are ours: our English where the official script has none, and
fixes we decide on. Nothing from the GameCube disc goes here: the official
text is read from the user's disc at build time and never enters the
repository.

- `message/*.txt`: messages of the cartridge's bank that have no official
  English (the debug messages and the lines the GameCube dropped; `make
  script` lists those still untranslated as `TODO translate`), in
  `tools/msgbank.py`'s dump form (`## number`, the message, `<CODE hex>`
  for control codes, `#:` notes). `make script` lays them over the draft
  by number (`tools/af_text.py draft --overrides`), and `make rom-en` over
  the compiled bank again, after the 2010 patch's messages. `af_text.py`
  refuses one whose codes that steer the game are not the cartridge's, or
  that prints a string the game does not fill for it (NOTES, "Our own
  messages").
  Also ours: official messages whose GameCube text is not that message's
  English for the cartridge (NOTES, "The official text's codes and the
  GameCube's own"): it does something else with the game (`flow.txt`),
  talks of its own events (`events.txt`, `moon.txt`) or things
  (`things.txt`, `travel.txt`: its memory card for the Controller Pak;
  `controls.txt`: its buttons), or prints a free string the cartridge
  does not fill (`unfilled.txt`).
- `choice/`, `string/` and a letter bank's folder (`mail_body/`,
  `vmail_a/`...): the same for the choices, the strings and the letters,
  laid over their drafts by `make script` (not again at `make rom-en`):
  the strings and letters the GameCube has no English for or has wrong
  for the cartridge (its staff credits, its units, its event notices for
  events the cartridge does not have). The event notices the GameCube
  keeps at another number are not ours: the draft takes the GameCube's
  from there (`tools/af_text.py` `LETTER_REMAP`, numbers only).
- `ui.txt`: the menus' strings where the font draws them
  (`tools/af_ui.py`); `items.txt`: the item names the GameCube has none
  for (`tools/af_items.py`).
