# Compatibilidade com páginas de 16 KB

Versão do app: `2.4.8-quiet-transitions` (versionCode 13).

- TensorFlow Lite 2.14.0 substituído por `com.google.ai.edge.litert:litert:1.4.2`. A série 1.x preserva a API `org.tensorflow.lite.Interpreter`, sem alterar o modelo YOLO nem migrar o pipeline de inferência.
- CameraX atualizado de 1.3.4 para 1.4.2, compatível com o compileSdk 35 atual.
- AGP 8.13.2 já suporta empacotamento de 16 KB; não foi necessário esconder avisos, ativar modo de compatibilidade ou alterar o SDK mínimo.

## Verificação após compilar

```bash
./gradlew testDebugUnitTest assembleDebug
python3 tools/check_16kb.py app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk
```

O script verifica alinhamento LOAD/RELRO e posição no ZIP de todas as bibliotecas ARM64 e x86_64. Bibliotecas de 32 bits não fazem parte desse requisito. Não basta zipalign: os segmentos ELF internos também precisam estar alinhados.

## Resultado desta atualização

- 24 testes JVM passaram (protocolo X5, fila de voz, monitoramento semântico e consentimento). Dois testes instrumentados executaram modelos reais no aparelho; detalhes em `../DOCUMENTACAO_DETECCAO_AUTOMATICA.md`.
- As 16 bibliotecas de 64 bits passaram LOAD/RELRO/ZIP, incluindo LiteRT, CameraX e UVCAndroid 1.0.13. Testes USB em `../DOCUMENTACAO_CAMERA_USB.md`.
- APK atualizado gerado com sucesso e aprovado no zipalign.
- O celular informou `getconf PAGE_SIZE = 4096`; execução com kernel de 16 KB ainda precisa ser validada em emulador/aparelho configurado para isso.
- Teste interativo da câmera/IA após a atualização ficou pendente porque a tela do celular estava bloqueada.

Referência: https://developer.android.com/guide/practices/page-sizes
