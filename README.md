# DualCam 3D

App Android que usa **dos lentes traseros a la vez** (Camera2, cámara lógica multi-lente) y guarda:

- **Fotos 3D** lado a lado (SBS) → `Pictures/DualCam3D/DualCam_..._SBS.jpg`
- **Videos 3D** lado a lado (H.264, sin audio) → `Movies/DualCam3D/DualCam_..._SBS.mp4`

Requiere Android 10 o superior y un celular que exponga sus lentes físicos (por ejemplo, Motorola Edge 50 Ultra: lentes 2 = principal, 3 = gran angular, 5 = tele).

## Compilar el APK

### Opción A: Android Studio (PC)
1. Descomprimí el ZIP y abrí la carpeta `DualCam` con Android Studio (*File → Open*).
2. Esperá a que sincronice Gradle.
3. *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.
4. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`. Pasalo al celular e instalalo (hay que permitir "instalar apps desconocidas").

### Opción B: GitHub, sin instalar nada
1. Creá un repositorio en GitHub y subí todo el contenido de la carpeta (incluida `.github/workflows/build.yml`).
2. En la pestaña **Actions** se compila solo. Al terminar, descargá el artefacto **DualCam3D-apk** (es un ZIP con el APK adentro).

## Uso
- La app se usa **en horizontal**, así los lentes, que en vertical están uno encima del otro, quedan uno al lado del otro.
- **Lentes**: cambia el par de lentes. Arranca con el par de campo de visión más parecido (principal + gran angular). El lente más angular se recorta para igualar el encuadre del otro.
- **Izq ⇄ Der**: si el 3D se ve raro (cansa la vista o lo cercano parece lejos), los ojos están invertidos. Tocalo.
- **Girar**: si la imagen sale rotada, tocalo hasta que quede derecha.
- **Ajustes**: panel de calibración. Elegí qué ojo editar y mové su imagen (← → y ↓ ↑), rotala de a 0,1° o cambiá el zoom. **Separación** aleja o acerca las dos imágenes entre sí. **Líneas guía** muestra líneas para alinear (no salen en fotos ni videos). Los ajustes se guardan por par de lentes y se aplican a la vista en vivo, las fotos y los videos.
- **Tocar la imagen** oculta los controles. Con el celular dentro de unos lentes VR tipo cardboard, ves el 3D en vivo.

## Para ver en VR
Abrí la foto o el video con un reproductor VR que acepte formato **SBS (lado a lado)** o, en lentes tipo cardboard, mostralo a pantalla completa en horizontal. El nombre del archivo termina en `_SBS` para que los reproductores lo detecten.

## Limitaciones
- Los lentes están a ~1–2 cm entre sí, contra ~6,5 cm entre los ojos. El 3D se nota sobre todo en objetos cercanos (de 30 cm a 2–3 m). Los paisajes lejanos se ven casi planos.
- Los dos lentes son distintos (resolución, nitidez, color). El ojo del gran angular recortado tiene menos detalle.
- Si los lentes no están perfectamente alineados, puede haber un pequeño desfase vertical.
- Resolución: hasta 1920×1440 por ojo en foto y 1440×1080 por ojo en video, según lo que acepte el equipo.
