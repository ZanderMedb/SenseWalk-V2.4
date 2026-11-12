# Verificações complementares — 12/11/2026

- HEAD: `896f6674c5d63fe966eb0018471ea1339ebbded7`, branch `master`.
- `git diff --check`: código de saída 0 nas verificações da auditoria.
- `adb devices -l`: somente `List of devices attached`, sem dispositivos.
- JUnit XML: 92 testes, 0 falhas, 0 erros, 0 ignorados. Casos em `jvm-tests.json`.
- Lint XML: 148 avisos, 0 erros. Tipos: ScopedStorage 3; OldTargetApi 1; UseSwitchCompatOrMaterialCode 1; GradleDependency 9; LockedOrientationActivity 1; NewerVersionAvailable 2; DiscouragedApi 3; DrawAllocation 1; NestedWeights 1; UselessParent 5; MissingApplicationIcon 1; ButtonStyle 4; UseKtx 65; SetTextI18n 19; HardcodedText 32.

## APK novo / 16 KB

Executados em `Projeto_Android`:

```text
python3 tools/check_16kb.py app/build/outputs/apk/debug/app-debug.apk
OK lib/arm64-v8a/libUVCCamera.so
OK lib/arm64-v8a/libimage_processing_util_jni.so
OK lib/arm64-v8a/libjpeg-turbo212.so
OK lib/arm64-v8a/libsurface_util_jni.so
OK lib/arm64-v8a/libtensorflowlite_jni.so
OK lib/arm64-v8a/libusb1.0.so
OK lib/arm64-v8a/libuvc.so
OK lib/arm64-v8a/libyuv.so
OK lib/x86_64/libUVCCamera.so
OK lib/x86_64/libimage_processing_util_jni.so
OK lib/x86_64/libjpeg-turbo212.so
OK lib/x86_64/libsurface_util_jni.so
OK lib/x86_64/libtensorflowlite_jni.so
OK lib/x86_64/libusb1.0.so
OK lib/x86_64/libuvc.so
OK lib/x86_64/libyuv.so
Verificadas 16 bibliotecas de 64 bits: LOAD, RELRO e ZIP compatíveis com 16 KB.
```

`/home/zander_mb/Android/Sdk/build-tools/35.0.0/zipalign -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk`: sem saída, código 0. Não houve execução em kernel 16 KB.

## Contraprova do decodificador offline

Carregados via importlib o arquivo em HEAD (`git show HEAD:Dados_IA/dados_treinamento_ia/validar_videos_tflite.py`) e a versão corrigida. Mesma entrada: tensor float32 `[1,15,20]`, primeira predição `[cx=.5,cy=.5,w=.5,h=.25]`, score da classe 8 = .9, restante zero. Frame 1280×720, razão .5, padding (0,140), confiança .2, IoU .45.

```text
Synthetic class 8; expected box (320, 200, 960, 520)
BEFORE: [(8, 0.8999999761581421, 0, -278, 1, 0)]
AFTER: [(8, 0.8999999761581421, 320, 200, 960, 520)]
```

Isto valida geometria sintética, não reconhecimento visual de faixa.
