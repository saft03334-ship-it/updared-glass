# Glass Villager Bot (Fabric 1.21.11, client-side)

Levels librarians until they sell glass (Journeyman trade), moves glass / non-glass villagers to two drop-off spots,
restocks emeralds from emerald blocks, mines bookshelves for books when a trade needs them, and dumps junk.

Setup (look at the block, then run the command; with no block in view it uses your feet):
    /glassbot set queue      where the villagers in minecarts wait (radius: /glassbot radius queue 8)
    /glassbot set glass      where glass villagers get pushed to
    /glassbot set nonglass   where the others get pushed to
    /glassbot set emerald    chest / shulker with emerald blocks
    /glassbot set dump       chest / shulker for everything else
    /glassbot set books      centre of the bookshelves (radius: /glassbot radius books 6)
    /glassbot start | stop | status          (key J also toggles)


## Building
The repo root must contain `build.gradle`, `settings.gradle`, `gradle.properties`, `src/` and `.github/`.
Push to GitHub, open Actions -> latest run -> download the `glassbot-jar` artifact and put the jar in `.minecraft/mods`
(needs Fabric Loader + Fabric API for 1.21.11). A working jar is tens of KB; the workflow now fails if it would be ~1 KB.
