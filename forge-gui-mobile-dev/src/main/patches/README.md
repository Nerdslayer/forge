# libGDX LWJGL3 backend patch

`lwjgl3-minimized-fullscreen.patch` applies to the Apache-2.0-licensed sources from
`com.badlogicgames.gdx:gdx-backend-lwjgl3:1.14.2:sources`. During Maven's
`generate-sources` phase, Forge verifies the source JAR's SHA-256, extracts the affected
files, and applies the patch with `git apply`. Git must be on the build machine's path.
The Adventure assembly includes the recompiled classes and excludes their upstream copies.

When updating the backend, review the startup clears, regular window update, and native
framebuffer resize callback for buffer swaps. Rebase the patch and update the source JAR
checksum in `pom.xml`. Run the Adventure backend tests and verify that the assembled JAR
contains the patched classes.
