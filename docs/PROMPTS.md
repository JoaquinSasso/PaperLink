# Prompts para los agentes

Sirven para Antigravity y para Claude Code. Abrí el agente en la raíz del repo: `AGENTS.md` es la guía (Antigravity la lee sola; también toma `.agents/rules/paperlink.md`, y en Claude Code la importa `CLAUDE.md`). El detalle está en `docs/PLAN.md`.

En Antigravity hay tres workflows (escribí `/` en el chat):
- `/retomar`: lee el estado y te resume dónde quedó todo.
- `/fase`: ejecuta la siguiente fase (o la que digas) siguiendo el proceso completo.
- `/probar`: compila, corre todos los tests y hace una prueba rápida en el teléfono.

## Primera sesión en Antigravity

Leé AGENTS.md y docs/PLAN.md completos; empezá por la sección "Estado actual" del plan. Este proyecto viene de sesiones anteriores con otro agente: las fases 0 a 3 están hechas y todo está commiteado en master. Antes de tocar nada:
1. Revisá `git log --oneline -20` y `git status`, y comprobá que compila y que pasan los tests (`./gradlew assembleDebug testDebugUnitTest`; con el teléfono conectado, también los instrumentados con `installDebug installDebugAndroidTest` + `adb shell am instrument`, nunca `connectedDebugAndroidTest`).
2. Resumime en pocas líneas qué entendiste del proyecto, qué quedó pendiente y cuál es el siguiente paso.
3. Después esperá mis indicaciones: no empieces la Fase 4 hasta que te lo diga.

## Fases 4 y 5 (cambiá el número)

Leé AGENTS.md y docs/PLAN.md. Ejecutá la Fase N. Empezá preguntándome las decisiones pendientes de esa fase, si las hay. Después proponé un plan corto, esperá mi OK e implementá. Antes de cerrar: compilá, corré los unit tests y los instrumentados, y probá en el teléfono con adb siguiendo el criterio de aceptación. Marcá el checklist, anotá las decisiones en el registro, actualizá "Estado actual" y hacé commit directo en master.

(En Antigravity alcanza con `/fase` o `/fase 4`.)

## Si una sesión se corta a mitad de fase

Leé AGENTS.md, docs/PLAN.md y `git log` de master. Decime qué quedó hecho de la fase en curso y qué falta, y seguí desde ahí. (En Antigravity: `/retomar`.)
