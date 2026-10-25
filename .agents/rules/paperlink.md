---
trigger: always_on
---

# PaperLink — reglas del proyecto

- La guía completa está en `AGENTS.md` (raíz). Leela al empezar cada conversación; manda sobre cualquier suposición.
- El plan, el estado actual y las decisiones están en `docs/PLAN.md`. Empezá siempre por su sección **"Estado actual"**.
- Respondé en español rioplatense (voseo). Código, commits y nombres en inglés; comentarios en español.
- Una fase por sesión: preguntá las decisiones pendientes, proponé un plan corto y **esperá el OK** antes de tocar código.
- Trabajá en `master`, un commit por bloque lógico con prefijo `Phase N:`. Nada de push sin pedido.
- Nunca `fallbackToDestructiveMigration`; cada cambio de esquema lleva `Migration`, test y versión nueva.
- Nada de I/O en composables ni ViewModels; el disco solo lo toca `AttachmentStore`.
- Ningún `catch` vacío; strings siempre en `values/` y `values-es/`; logs solo con `util/log()`.
- No uses `connectedDebugAndroidTest` (desinstala la app y borra los datos del usuario): usá `installDebug installDebugAndroidTest` + `adb shell am instrument`.
- Preguntá antes de borrar datos del usuario, agregar dependencias pesadas o cambiar una decisión pendiente. Los ajustes del sistema del teléfono los hace el usuario.
