# Webcam USB/UVC — SenseWalk 2.4.5

## Atualização 2.4.7 — HD real

A HiCamera anunciou MJPEG em 1920×1080, 1280×720 e 640×360, além de vídeo não comprimido em 640×360. Passamos a preferir a maior resolução anunciada até **1280×720**, priorizando MJPEG em caso de empate. Full HD não é selecionado para limitar custo de conversão/memória. São pixels reais, não ampliação artificial.

Validado no S25 FE: `Opened 4817:17185 1280x720` e `First RGB frame 1280x720, 2764800 bytes`. Callback de RGB em HD limitado a aproximadamente seis frames/s; isso não garante a mesma taxa de inferência. Desempenho sustentado e ganho de acurácia ainda não foram medidos para HD. A resolução anterior era 640×360.

## Implementação

Fonte adicional USB Host/UVC, sem substituir CameraX ou X5 Wi-Fi. Dependência Maven Central: `com.herohan:UVCAndroid:1.0.13`. Não se assume que a webcam aparece em Camera2.

`UsbCameraClient.kt` enumera interfaces USB de classe vídeo (14), pede acesso pelo mecanismo do Android e negocia resolução suportada, preferindo próxima de 640×480, limitada inicialmente a 1280×720. O callback solicita RGB24. Seu tamanho real é validado antes da conversão para ARGB opaco por `UsbRgbCodec.kt`.

O buffer nativo só é válido durante o callback: os bytes são copiados para um canal de capacidade um. Conversão fora da UI e descarte de frames substituídos/atrasados. `VideoFrame` com relógio monotônico alimenta o mesmo `ExternalFrameProcessor`/`InferenceCoordinator` de FFNet e YOLO, incluindo consentimento sim/não. Não se cria outro detector ou captura interna em paralelo. Preview usa o ImageView externo existente.

A fonte é salva nas preferências. Pausar/sair encerra USB e filas. Desconexão, permissão negada, disputa com outro aplicativo e formato inesperado geram aviso; sem fallback silencioso para câmera interna.

## Como usar

1. Conectar webcam e adaptador OTG ao celular, mantendo ADB por Wi-Fi.
2. Desbloquear e abrir SenseWalk. Parar análise/monitoramento antes de trocar a fonte.
3. No botão de fonte (Celular/X5/USB), selecionar **USB / UVC**.
4. Autorizar o SenseWalk no diálogo USB. Permissão concedida a outro aplicativo não vale para SenseWalk.
5. Se solicitado, permitir também Câmera: Android exige essa permissão para UVC em apps modernos; o app não inicia a câmera interna para obtê-la.
6. Confirmar preview. Ativar análise manual ou reativar Detecção automática de ruas nas configurações.

Se estiver ocupado, fechar outro aplicativo que usa a webcam (por exemplo USB Camera) e reconectar. Para falta de energia/estabilidade, testar hub OTG alimentado. Não há garantia para qualquer webcam/formato: depende de UVC, formatos negociados, energia, cabo e driver.

## Validações executadas e pendências

- Android reconheceu **Huawei HiCamera**, VID/PID decimal **4817:17185**, hexadecimal `12d1:4321`, interfaces UVC Video Control/Streaming.
- Um teste instrumentado carregou `UVCCamera` e suas dependências nativas no S25 FE e enumerou uma webcam. Passou; a permissão estava inicialmente não concedida.
- Após desbloqueio e autorização, abertura real confirmada: `UsbCamera: Opened 4817:17185 640x360` e `First RGB frame 640x360, 691200 bytes`.
- Interface confirmou fonte **USB**, status **HiCamera — 640×360**. Análise manual acionada nessa fonte; `tvEstado` e `tvSituacao` passaram a **RECONHECENDO** pelo callback do pipeline YOLO. Não foi necessário trocar para câmera interna.
- **27 testes JVM passaram**, incluindo três testes novos de RGB/ARGB e rejeição de buffers/resoluções inválidos.
- **16 bibliotecas de 64 bits** passaram LOAD/RELRO/ZIP para 16 KB. O celular usa páginas de 4 KB; execução em kernel 16 KB permanece pendente.
- Manifesto final sem localização ou acesso amplo ao armazenamento. Permissões de gerenciamento/leitura/gravação de arquivos e foreground service trazidas pela dependência foram removidas: só usamos frames em memória, sem gravação de vídeo/serviço em segundo plano.

**Pendente:** validação visual de cores, desconexão/reconexão, estabilidade prolongada, desempenho e FFNet/consentimento usando cenas capturadas por essa webcam. A recepção real de frames e a entrada no processamento YOLO foram confirmadas; isso não valida acurácia ou segurança de travessia. O timestamp é de chegada ao callback nativo, não um timestamp de exposição do sensor. A conexão ADB por Wi-Fi caiu ao final; não foi possível obter acompanhamento prolongado nessa sessão.

## Arquivos e licença

Novos: `camera/UsbCameraClient.kt`, `camera/UsbRgbCodec.kt`, `UsbRgbCodecTest.kt`, `UsbRuntimeTest.kt`, `assets/usb_licenses/`.
Alterados: Gradle, Manifesto, MainActivity, AppConfig, SettingsActivity e documentação. Pesos e classes YOLO/FFNet não foram alterados.

UVCAndroid/UVCCamera: Apache-2.0; libusb: LGPL-2.1-or-later; libuvc/libyuv: BSD; libjpeg-turbo: BSD/IJG/zlib; RapidJSON: licença incluída. Textos e atribuições em `assets/usb_licenses`, acessíveis nas configurações. Nativas usadas sem modificação; fonte/build upstream: https://github.com/shiyinghan/UVCAndroid . Revisar obrigações de redistribuição LGPL antes de publicação comercial.

This software is based in part on the work of the Independent JPEG Group.
