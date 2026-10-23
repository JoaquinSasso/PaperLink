# Prompts para Claude Code

Abrí Claude Code en la raíz del repo y pegá el prompt de la fase. `CLAUDE.md` se carga solo; el detalle está en `docs/PLAN.md`.

## Fase 0
Leé CLAUDE.md y docs/PLAN.md completos. Ejecutá la Fase 0 del plan. Antes de tocar nada, mostrame un plan corto y esperá mi OK. Al terminar, marcá el checklist en PLAN.md y hacé commit.

## Fases 1 a 5 (cambiá el número)
Leé CLAUDE.md y docs/PLAN.md. Ejecutá la Fase N. Empezá preguntándome las decisiones pendientes de esa fase, si las hay. Después proponé un plan corto, esperá mi OK e implementá. Antes de cerrar: compilá, corré los tests y, si aplica, probá en el dispositivo con adb siguiendo el criterio de aceptación. Marcá el checklist, anotá decisiones en el registro y hacé commit directo en master.

## Si una sesión se corta a mitad de fase
Leé CLAUDE.md, docs/PLAN.md y `git log` de la rama actual. Decime qué quedó hecho de la fase en curso y qué falta, y seguí desde ahí.
