---
description: Ejecutar la siguiente fase del plan de PaperLink (o la que se indique)
---

1. Leé `AGENTS.md` y `docs/PLAN.md` completos. Identificá la fase a ejecutar: la que indique el usuario o, si no dice, la que marca "Estado actual".
2. Si quedaron pendientes de la fase anterior (verificaciones del usuario, ítems sin marcar), mencionálos primero.
3. Preguntá al usuario las **decisiones pendientes** de esa fase (están marcadas así en el plan) y esperá sus respuestas.
4. Revisá el código que vas a tocar y proponé un **plan corto** (pasos, archivos, dependencias nuevas, tests y cómo vas a probar en el teléfono). **Esperá el OK** antes de implementar.
5. Implementá en `master`, un commit por bloque lógico (`Phase N: ...`, en inglés). Respetá los invariantes de `AGENTS.md`.
6. Verificá: `./gradlew assembleDebug testDebugUnitTest`, después `./gradlew installDebug installDebugAndroidTest` y `adb shell am instrument -w com.joasasso.paperlink.debug.test/androidx.test.runner.AndroidJUnitRunner`. Probá en el teléfono según el criterio de la fase y revisá `adb logcat -b crash` y `adb logcat -s PaperLink`.
7. Repasá las garantías de confiabilidad de la fase (sección 8 del plan). Si alguna no se cumple, la fase no está terminada.
8. Pedile al usuario las pruebas que requieren sus manos o ajustes del sistema, con pasos concretos.
9. Al cerrar: marcá el checklist, agregá "Verificación" y "Notas y decisiones" en la fase, sumá filas al "Registro de decisiones", actualizá "Estado actual" y hacé el commit final.
