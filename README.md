# SenseWalk 2.4

Aplicativo Android de visão computacional para identificar veículos, semáforos e faixas de pedestres, com acompanhamento de objetos e feedback por voz. Esta versão reúne o aplicativo, os modelos de IA, os resultados dos experimentos e os scripts usados na preparação e no treinamento.

O repositório separa o desenvolvimento do aplicativo dos arquivos de inteligência artificial: abra **`Projeto_Android` no Android Studio** e use **`Dados_IA` para trabalhar com os modelos e treinamentos**.

## Índice

- [Organização do repositório](#organização-do-repositório)
- [Recursos do aplicativo](#recursos-do-aplicativo)
- [Como baixar o projeto](#como-baixar-o-projeto)
- [Como executar o Android](#como-executar-o-android)
- [Modelos e backup](#modelos-e-backup)
- [Resultados dos treinamentos](#resultados-dos-treinamentos)
- [Scripts e novos treinamentos](#scripts-e-novos-treinamentos)
- [Compatibilidade entre o modelo e o aplicativo](#compatibilidade-entre-o-modelo-e-o-aplicativo)

## Organização do repositório

```text
SenseWalk-V2.4/
├── Projeto_Android/                 # Projeto para abrir no Android Studio
│   ├── app/
│   │   └── src/main/
│   │       ├── assets/model.tflite   # Modelo carregado atualmente pelo app
│   │       ├── java/com/travessia/segura/
│   │       └── res/
│   ├── gradle/
│   ├── gradlew
│   ├── gradlew.bat
│   └── settings.gradle.kts
├── Dados_IA/
│   ├── best.pt                      # Novo modelo selecionado do treinamento
│   ├── backup/
│   │   └── model.tflite             # Cópia de segurança do modelo original
│   ├── dados_treinamento_ia/
│   │   ├── runs/                    # Resultados de validação
│   │   └── runs_sensewalk/          # Experimentos, métricas e checkpoints
│   └── treinamento_sem_os_dataset/
│       ├── audit_sensewalk_labels.py
│       ├── merge_sensewalk_dataset.py
│       ├── merge_sensewalk_dataset_v2.py
│       ├── merge_sensewalk_dataset_v3.py
│       ├── train_sensewalk_yolov8.py
│       ├── yolov8m.pt               # Peso base para treinamento
│       └── yolov8s.pt               # Peso base para treinamento
├── .gitattributes                  # Regras do Git LFS para os pesos .pt
├── .gitignore
└── README.md
```

Os datasets de imagens e anotações não estão incluídos. As pastas de resultados contêm os artefatos dos experimentos, mas não substituem os datasets necessários para repetir o treinamento.

## Recursos do aplicativo

- Detecção com LiteRT (API TensorFlow Lite) e visualização das caixas dos objetos.
- Captura pela câmera do aparelho, usando CameraX, e entrada experimental X5 por Wi-Fi/RTSP JPEG.
- Rastreamento de veículos e análise de movimento, com fluxo óptico para compensação do movimento da câmera.
- Identificação de faixas e semáforos, com lógica de estados para interpretar a cena.
- Alertas por voz, comandos de voz e interface em português, inglês e espanhol.
- Monitoramento visual experimental de pista/calçada com FFNet local, ativação temporal do YOLO e sobreposição de depuração, sem localização ou serviços de mapas. Consulte [a documentação e as limitações](DOCUMENTACAO_DETECCAO_AUTOMATICA.md).
- Configurações de detecção, região de interesse, rastreamento e apresentação da interface.
- Registro e compartilhamento de benchmarks: métricas por frame em `frames.csv` e resumo da sessão em `summary.txt`.

As alterações experimentais de interpretação de veículos, faixa e sequência de verificação direcional estão descritas em [DOCUMENTACAO_MELHORIAS_TRAVESSIA.md](DOCUMENTACAO_MELHORIAS_TRAVESSIA.md). A [auditoria independente Astra](AUDITORIA_ASTRA_SENSEWALK.md) registra os builds, testes, correções e bloqueadores atuais. A direção/cobertura física da câmera ainda não é verificada: o aplicativo não conclui condições favoráveis com essa evidência ausente. Em instalação nova, o monitoramento automático fica ativado por padrão, sujeito a permissões e comando Parar; o consentimento inicia a sequência de travessia, enquanto os alertas já permanecem ativos durante o monitoramento.

O logger de benchmark registra métricas numéricas, sem salvar os frames da câmera.

### Validação offline com vídeos reais (12/11/2026)

Pipeline e reprodução: [`videos_teste/README.md`](videos_teste/README.md).
Resultados e correções: [`RELATORIO_TESTES_VIDEOS_SENSEWALK.md`](RELATORIO_TESTES_VIDEOS_SENSEWALK.md).
Pendências atuais: [`PENDENCIAS_SENSEWALK_FINALIZACAO.md`](PENDENCIAS_SENSEWALK_FINALIZACAO.md).
Foram inferidas 626 amostras de dois MP4 com os assets reais e feito replay das classes
Kotlin. Falhas importantes de faixa/veículos permanecem; não há aprovação de travessia
nem validação física das câmeras/áudio a partir dessas gravações.

Fonte experimental **USB / UVC** implementada separadamente do CameraX. HiCamera testada no aparelho com recepção RGB 640×360 e entrada na análise YOLO; estabilidade e demais cenários ainda precisam de validação. Veja [DOCUMENTACAO_CAMERA_USB.md](DOCUMENTACAO_CAMERA_USB.md).

## Como baixar o projeto

Os arquivos `.pt` são armazenados com **Git LFS**. Tenha Git e Git LFS instalados e execute:

```powershell
git lfs install
git clone https://github.com/ZanderMedb/SenseWalk-V2.4.git
cd SenseWalk-V2.4
git lfs pull
```

Se você já clonou o repositório, execute `git lfs pull` dentro dele para baixar os pesos. Os modelos e checkpoints ocupam vários gigabytes; reserve espaço para o checkout e para o armazenamento local do LFS.

Se um arquivo `.pt` aparecer como um pequeno texto começando com `version https://git-lfs.github.com/spec/v1`, você está vendo o ponteiro do LFS, não o peso baixado.

## Como executar o Android

### Configuração do projeto

| Item | Valor no projeto |
| --- | --- |
| Linguagem | Kotlin |
| Identificador do app | `com.travessia.segura` |
| Android mínimo | API 26 — Android 8.0 |
| SDK de compilação / destino | API 35 |
| Android Gradle Plugin | 8.13.2 |
| Gradle Wrapper | 8.13 |
| Plugin Kotlin | 1.9.24 |
| LiteRT (API TensorFlow Lite) | 1.4.2 |
| CameraX | 1.4.2 |
| Versão do aplicativo | `2.4.8-quiet-transitions` |

1. No Android Studio, selecione **Open** e abra a pasta `Projeto_Android`.
2. Configure o Android SDK, incluindo a API 35, e um Gradle JDK compatível com as versões acima.
3. Aguarde a sincronização do Gradle e o download das dependências.
4. Conecte um aparelho Android compatível e execute o módulo `app`.
5. Conceda as permissões de câmera e, para comandos de voz, microfone.

O arquivo `local.properties` contém o caminho do SDK da sua máquina e não é versionado. Deixe o Android Studio configurá-lo no seu ambiente.

Para gerar um APK de depuração no Windows, a partir da raiz do repositório:

```powershell
cd Projeto_Android
.\gradlew.bat assembleDebug
```

O APK será gerado em `Projeto_Android/app/build/outputs/apk/debug/`.

Use JDK 17 para compilar. Execute `./gradlew testDebugUnitTest assembleDebug` para validar os testes e gerar o APK. As bibliotecas nativas mantêm compatibilidade com páginas de 16 KB; consulte `Projeto_Android/COMPATIBILIDADE_16KB.md`.

Na versão 2.4.8, os avisos automáticos são falados uma vez por condição observada, sem repetições periódicas enquanto ela permanecer igual. Mudanças confirmadas rearmam os avisos; perguntas de consentimento e comandos manuais continuam disponíveis. Detalhes e limites de validação em `DOCUMENTACAO_DETECCAO_AUTOMATICA.md`.

### Câmera X5 Z-IOT (experimental)

A fonte **X5** aparece junto à câmera do celular no botão de fontes. Antes de iniciar a análise, conecte manualmente o Galaxy S23 à rede aberta `ZIOTA_775012581081`; ela não oferece internet. Em Ajustes, a seção **X5 RTSP** permite mudar SSID, IP, porta de controle, URL RTSP alternativa, rotação de 180 graus e o limite para frames vencidos.

Arquitetura: `X5CameraClient` abre o TCP local `192.168.1.1:8266`, envia o handshake comprovado e mantém essa sessão aberta enquanto o vídeo é usado. A resposta é limitada e validada antes de extrair a URL RTSP. O cliente negocia SDP por RTSP/TCP e só continua quando o SDP anuncia `JPEG` RTP. Os fragmentos JPEG RFC 2435 são remontados sem fila crescente e entregues ao `ExternalFrameProcessor`, portanto TensorFlow Lite, tracking, voz, overlay e benchmark permanecem únicos.

Não foi adicionada biblioteca ou binário de decoder: o módulo usa apenas APIs Android, Kotlin e Coroutines já presentes. Não há licença adicional. H.264, áudio RTSP, JPEG RTP com tabelas de quantização fora do modo básico e qualquer SDP sem `JPEG` são recusados de propósito e mostrados como codec não suportado.

Checklist no S23:

1. Conecte-se manualmente a `ZIOTA_775012581081` e confirme que `rtsp://192.168.1.1:7070/webcam/jpeg_dvp` abre no VLC em outra máquina da mesma rede.
2. Abra SenseWalk, selecione X5, confira a mensagem de Wi-Fi e inicie a análise.
3. Confirme preview, rotação, caixas alinhadas, atualizações de tracking e alertas de voz.
4. Desligue e religue a X5. O overlay deve ser limpo, o status deve voltar a verificar e a reconexão é limitada a cinco tentativas.
5. Pare a análise ou saia da tela. A sessão TCP 8266 e o RTSP devem ser fechados.

O preview experimental não comprova latência, estabilidade, FPS nem segurança de travessia. Valide em ambiente controlado e confirme o entorno antes de atravessar.

## Modelos e backup

| Arquivo | Finalidade |
| --- | --- |
| `Projeto_Android/app/src/main/assets/model.tflite` | Modelo usado atualmente pelo aplicativo Android. |
| `Dados_IA/backup/model.tflite` | Cópia preservada do modelo original do aplicativo. |
| `Dados_IA/best.pt` | Novo peso selecionado, copiado de `yolov8m_baseline_640-3/weights/best.pt`. |
| `Dados_IA/dados_treinamento_ia/runs_sensewalk/yolov8m_baseline_640-3/weights/` | Modelo selecionado, último checkpoint e checkpoints periódicos do experimento. |
| `Dados_IA/treinamento_sem_os_dataset/yolov8m.pt` e `yolov8s.pt` | Pesos base disponíveis para iniciar treinamentos. |

O app carrega `model.tflite`, não `best.pt`. O commit `e3ffdae` substituiu o asset por uma exportação compacta com 11 saídas de classe. O arquivo não contém metadados nominais das classes; a procedência/ordem precisa ser vinculada ao relatório de exportação e validada com imagens. Copiar ou renomear um `.pt` não o converte para TensorFlow Lite.

Para atualizar o modelo do app, é necessário exportar o peso para um formato TensorFlow Lite compatível com o detector, conferir as classes e a estrutura dos tensores e validar a inferência no aparelho. O backup deve ser preservado durante essa atualização.

## Resultados dos treinamentos

Os resultados estão reunidos em `Dados_IA/dados_treinamento_ia`:

| Pasta | Conteúdo |
| --- | --- |
| `runs/detect/runs_sensewalk/val_best_agnostic/` | Curvas de precisão, recall, F1 e precisão-recall, matrizes de confusão e exemplos visuais de validação. |
| `runs_sensewalk/yolov8m_baseline_640/` | Configuração preservada em `args.yaml`. |
| `runs_sensewalk/yolov8m_baseline_640-2/` | Configuração preservada em `args.yaml`. |
| `runs_sensewalk/yolov8m_baseline_640-3/` | Configuração, métricas por época, gráficos, exemplos de imagens e pesos. |

No experimento `yolov8m_baseline_640-3`:

- `args.yaml` guarda os parâmetros da execução, incluindo 150 épocas, resolução 640 e batch 8.
- `results.csv` reúne perdas de treino/validação, precisão, recall e mAP por época.
- `results.png` e as curvas permitem acompanhar a evolução do treinamento.
- `weights/best.pt` guarda o modelo selecionado durante o treino; `last.pt` permite retomar a execução, e `epoch*.pt` são checkpoints periódicos.

Os caminhos absolutos em `args.yaml`, como `C:\zander\sensewalk\...`, são registros do ambiente original. Eles foram preservados como histórico e precisam ser ajustados para uma nova execução.

## Scripts e novos treinamentos

Os scripts ficam em `Dados_IA/treinamento_sem_os_dataset`:

| Script | Função |
| --- | --- |
| `audit_sensewalk_labels.py` | Inspecionar anotações e gerar material de auditoria visual. |
| `merge_sensewalk_dataset.py` | Preparar a versão inicial do dataset combinado. |
| `merge_sensewalk_dataset_v2.py` | Preparar a segunda versão do dataset. |
| `merge_sensewalk_dataset_v3.py` | Preparar a terceira versão, com mapeamento de classes e controle de duplicatas. |
| `train_sensewalk_yolov8.py` | Treinar com YOLOv8, salvar resultados/checkpoints e avaliar no split de teste, salvo quando desativado. |

### Preparação do ambiente

Use um ambiente Python separado. Os scripts importam PyTorch, Ultralytics, PyYAML e Pillow. O script de treinamento exige uma GPU CUDA disponível e não inicia o treinamento pela CPU.

Instale uma distribuição do PyTorch compatível com sua GPU e seu ambiente CUDA, além das demais dependências. Não há um arquivo de dependências com versões fixadas neste repositório; a reprodução exata do ambiente original ainda requer registrar essas versões.

Os scripts de preparação procuram fontes de dados a partir da própria pasta. Confira seus caminhos e disponibilize as imagens/anotações antes de executá-los. O treinamento espera um `data.yaml` com os caminhos dos splits e os nomes das classes.

### Exemplo de treinamento

No PowerShell, a partir da raiz do repositório, substitua o caminho do dataset por um caminho válido na sua máquina:

```powershell
python .\Dados_IA\treinamento_sem_os_dataset\train_sensewalk_yolov8.py `
  --data "C:\caminho\sensewalk_dataset_v3\data.yaml" `
  --model .\Dados_IA\treinamento_sem_os_dataset\yolov8m.pt `
  --project .\Dados_IA\dados_treinamento_ia\runs_sensewalk `
  --name novo_experimento `
  --epochs 150 `
  --imgsz 640 `
  --batch 8 `
  --device 0
```

Use um nome novo para preservar os resultados existentes. O exemplo informa `--project` explicitamente para manter as saídas na organização deste repositório. Sem esse argumento, o script usa uma pasta `runs_sensewalk` junto ao próprio script.

Para consultar as opções de treinamento, com as dependências instaladas:

```powershell
python .\Dados_IA\treinamento_sem_os_dataset\train_sensewalk_yolov8.py --help
```

## Compatibilidade entre o modelo e o aplicativo

O arquivo `AppConfig.kt` e o script `merge_sensewalk_dataset_v3.py` definem estas 11 classes. A inspeção do asset confirmou saída `[1,15,8400]`, compatível com quatro coordenadas e 11 escores; a forma do tensor não comprova a ordem nominal:

| ID | Configuração atual do Android | Preparação do dataset V3 |
| --- | --- | --- |
| 0 | `car` | `car` |
| 1 | `motorcycle` | `motorcycle` |
| 2 | `bicycle` | `bicycle` |
| 3 | `truck` | `truck` |
| 4 | `bus` | `bus` |
| 5 | `traffic-light-green` | `traffic-light-green` |
| 6 | `traffic-light-red` | `traffic-light-red` |
| 7 | `traffic-light-yellow` | `traffic-light-yellow` |
| 8 | `crosswalk` | `crosswalk` |
| 9 | `pedestrian-light-green` | `pedestrian-light-green` |
| 10 | `pedestrian-light-red` | `pedestrian-light-red` |

Antes de integrar o novo modelo, confira os nomes e a ordem das classes efetivamente salvos no checkpoint, atualize o mapeamento e a lógica do app conforme necessário e valide o resultado. A lista do script de preparação, isoladamente, não confirma os metadados do arquivo `best.pt`.
