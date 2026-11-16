captured: 2026-11-06 (headless mgba_dump)
rom / save: as the monomyth_v1_1 fixture's README (a save with no Pokémon yet)
boot script: native-capture/boot/monomyth_v1_1_battle.txt: the field, then the v1.0 monomyth fixture's six-mon party (its 600
  gPlayerParty bytes) poked into gPlayerParty with the count 6 and FLAG_SYS_POKEMON_GET / POKEDEX raised - the game's own party
  menu then drew Mishimizu Lv12 12/37, Leonte Lv9, Joltik Lv10, Venipede Lv10, Whismur Lv10 (and Scraggy) as v1.0 did, so the
  party layout and key hold - then v1.0's scripted wild POLIWAG Lv5 (setwildbattle B6 / dowildbattle B7) through v1.1's
  ScriptContext_SetupScript 0x08090A61; A through "Wild Poliwag appeared!" and the send-out: the action menu, dumped.
gBattleTypeFlags 4; gBattlersCount 2; gBattlerPositions 00 01; gBattleMons[0]: Mishimizu Lv12 12/37; gBattleMons[1]: Poliwag
  Lv5 (this capture's IVs). gBattlerControllerFuncs[0] = HandleInputChooseAction, 0x08048521 in v1.1 (v1.0's 0x08048549 less
  0x28); the action / move cursors 0.
From this point, headless: RIGHT walked the action cursor 0 -> 1; A on FIGHT swapped the handler to 0x08048E41 (move), on BAG
  to 0x0804AC95 (bag), on POKéMON to 0x0804ABCD (party) - NATIVE_MONOMYTH_V1_1's four.
