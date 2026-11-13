captured: 2026-11-06 (headless mgba_dump, a native libmgba build on the Mac)
rom: Pokemon Monomyth v1.1 (~/Downloads/monomyth_v1_1.gba: Emerald patched with Team Monomyth's monomyth_v1_1.bps, game code BPEE,
  title POKEMON MONO, sha1 eb660caf..., 32 MB)
save: ~/Downloads/monomyth_v1_1.sav (the user's own: 0:01 played, player Chris, no badges, no Pokémon yet)
boot script: native-capture/boot/monomyth_v1_1.txt (START past its warnings and intro to the CONTINUE menu - seven, not v1.0's
  eight - then A: the field in the first bedroom, CHAMOMILE TOWN, mapsec 0x00, map 1.1, pos (5, 7), mapType 8)
the game's own screens: the CONTINUE menu (Chamomile Town, Chris, 0:01, Badges 0); the bedroom. Money 3000 (SaveBlock1 +0x494 XOR
  SaveBlock2 +0x40, a new game's), gPlayerPartyCount 0, the eight bag pockets' pointers inside SaveBlock1.
Every RAM address is v1.0's (NATIVE_MONOMYTH_V1_1 changes only ROM addresses and the battle handlers' code addresses).
