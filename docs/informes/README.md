# Informes

Registro de problemas investigados y resueltos: qué pasó, qué se midió, qué se decidió y cómo se comprobó. Se escriben con narrativa de postmortem (la historia completa, incluidos los errores del proceso), para que cualquiera, persona o agente, entienda el porqué de una decisión sin tener que reconstruirlo.

## Cuándo escribir uno

Siempre que un trabajo de **corrección, refactorización u optimización** siga este camino:

1. Se encuentra un problema (lo reporta el usuario, aparece en una prueba o en una revisión).
2. Se hacen pruebas o mediciones para elegir la mejor solución.
3. Se implementa la solución y se comprueba.

No hace falta para cambios directos sin investigación (una corrección obvia, un texto, un ajuste visual).

## Nombre y formato

- Archivo: `AAAA-MM-DD-tema-corto.md` (fecha en que se cerró).
- En español, con datos concretos: tablas de mediciones, tiempos, cantidades, commits.
- Agregalo al índice de abajo y enlazalo desde la sección correspondiente de `docs/PLAN.md` y su "Registro de decisiones".

### Estructura

1. **Encabezado:** fecha, estado, áreas del código, dispositivo, commits.
2. **Resumen:** el problema y el resultado en pocas líneas, con una tabla antes/después.
3. **Línea de tiempo:** los hechos en orden.
4. **El problema:** síntoma y cómo se detectó.
5. **Hipótesis y pruebas:** cada alternativa, cómo se midió y los números, **también las descartadas** y por qué.
6. **Decisión e implementación:** qué se eligió, quién lo decidió y qué costos se aceptan.
7. **Bugs encontrados en el camino** (si los hubo): cómo apareció, causa, impacto, corrección y test que lo cubre.
8. **Verificación:** tests (cuántos, cuáles nuevos, cuántas repeticiones si hubo intermitencia) y prueba en el dispositivo.
9. **Qué salió bien / qué se puede mejorar:** del proceso, sin culpas.
10. **Pendientes.**

## Índice

| Fecha | Informe | Tema |
| --- | --- | --- |
| 27/10/2026 | [Fotos: espacio en el respaldo y velocidad de carga](2026-10-27-fotos-espacio-y-velocidad.md) | WebP 2400 q70 (−79 %), carga en segundo plano con el código al instante, 4 bugs de concurrencia y cancelación |
