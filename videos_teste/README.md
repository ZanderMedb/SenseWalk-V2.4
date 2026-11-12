# Vídeos e reprodução offline

Os originais encontrados nesta execução estão em `../videos/` (não rastreados pelo Git).
Referências locais, sem alterar os originais:

* `teste_01_faixa_veiculo.mp4` → `WhatsApp Video 2026-10-15.mp4` (928 frames).
* `teste_02_faixa_semaforo.mp4` → `WhatsApp Video 2026-10-15 at 21.05.41.mp4` (948 frames).

Em outro checkout, copie os MP4 para esta pasta com os nomes acima. Resultados e MP4
locais estão ignorados. Não há download de dados ou retreinamento.

## Executar

Python 3.12, ambiente virtual, dependências em `Projeto_Android/tools/requirements-offline.txt`:

```bash
python Projeto_Android/tools/offline_video_validation.py --stride 3
# Com JAVA_HOME/SDK configurados, pipeline completo em um comando:
# python Projeto_Android/tools/offline_video_validation.py --stride 3 --replay-kotlin
# stride 1 processa todos os frames; stride 3 processa cerca de 10 amostras/s.
# Diretório deve estar vazio; use --output videos_teste/resultados_nova_execucao.
python Projeto_Android/tools/diagnose_video_preprocessing.py videos_teste/resultados
SENSEWALK_VIDEO_RESULTS="$PWD/videos_teste/resultados" \
  Projeto_Android/gradlew -p Projeto_Android testDebugUnitTest --tests '*OfflineVideoReplayTest' --rerun-tasks
python Projeto_Android/tools/diagnose_video_preprocessing.py videos_teste/resultados
python Projeto_Android/tools/summarize_video_validation.py videos_teste/resultados \
  --annotations videos_teste/anotacoes_revisao_astra.json --tracking-video
python -m unittest discover -s Projeto_Android/tools -p 'test_*.py' -v
```

JDK 17 e SDK 35 necessários para Gradle. Replay opcional é explicitamente ignorado
sem `SENSEWALK_VIDEO_RESULTS`. Os demais testes não dependem dos vídeos.

CSV `deteccoes` registra boxes float após NMS por classe a .45 e confiança .20.
`segmentacao` tem PTS do contêiner, proporções na ROI Android, contato pista/calçada,
IoU temporal **não compensada** (não acurácia) e tempos Linux.
PNG sem perdas + máscaras alimentam `OfflineVideoReplayTest`: classes reais
AdvancedVehicleTracker, Rastreador/VehicleMotionRules, SparseOpticalFlowEstimator,
SceneEvidenceTracker, CrossingRiskAnalyzer e CrossingVerification. `tracking.csv`
e `cena_kotlin.csv` são gerados apenas pelo replay; `replay_kotlin.json` confirma a execução.

Os MP4 anotados mostram apenas amostras inferidas, a FPS/stride; nenhum frame sem
inferência recebe caixas antigas. O último intervalo pode diferir da duração original
por até um passo. O formato MP4 de saída é CFR; o CSV conserva PTS do original.

## Paridade e limites

* Mesmo RGB [0,1], dimensões truncadas/padding 114 do YOLO, coordenadas normalizadas,
  seleção da melhor classe, clipping float e NMS. O validador antigo é reutilizado.
* FFNet: RGB/255 quantizado com arredondamento positivo, resize-pad preto, interpolação
  bilinear de **escores** antes do argmax e ROI idêntica à Kotlin.
* Teste compartilhado dos tensores reais do primeiro frame de cada vídeo verifica
  decode/NMS YOLO e cada pixel de argmax FFNet. ROI verificada em todas as amostras.
* O replay salva também a entrada letterbox da classe Android efetiva (Skia nativo
  no Robolectric) do primeiro frame de cada vídeo. O diagnóstico mede as diferenças
  de pixels e executa YOLO nessa entrada. O arredondamento do padding foi corrigido
  por essa contraprova; o teste exige MAE < 1 nível por canal de 8 bits nos primeiros
  frames compartilhados. Resize/Canvas Skia e OpenCV não são bit a bit equivalentes. Runtime LiteRT host
  2.3.0 difere do Android 1.4.2. Não chamar este pipeline de integração Android.
* Replay usa PTS de mídia e inferência em cada amostra; não reproduz escalonamento,
  descarte por carga, exposição/transporte nem áudio Android.

## Anotação

Copie `anotacoes_exemplo.json`. Não anote pelo resultado do detector. `intervals`:

```json
{"video":"teste_01_faixa_veiculo", "start_s":0.0, "end_s":1.5,
 "class_name":"crosswalk", "presence":"present", "occlusion":"none",
 "notes":"exemplo de formato, não anotação já executada"}
```

`presence`: `present`, `absent`, `unknown`. Intervalos fechados; para uma amostra
isolada, use o mesmo timestamp CSV em início/fim. Nunca estenda a anotação de um
frame aos frames não revisados. Classes: lista Android documentada no README.

`objects` aceita `{video, frame, object_id, class_name, bbox:[x1,y1,x2,y2],
motion:"stationary|moving|unknown", signal_type:"vehicle|pedestrian|unknown",
signal_color:"green|red|yellow|unknown", applicable:"yes|no|unknown"}`.
`risks` aceita `{video,start_s,end_s,known_conflict:true,source,notes}`.
IDs devem ser únicos por objeto/vídeo e persistentes nas anotações.

Uma revisão esparsa pode usar `frames:[0,60,120]` em vez de início/fim. A revisão
incluída (`anotacoes_revisao_astra.json`) é uma inspeção **do assistente**, não ground
truth humano independente; suas métricas são exploratórias. O resumo registra a fonte.

Com `--annotations arquivo.json`, a ferramenta calcula recall de **presença por
amostra anotada** e frequência de detecção em frames anotados ausentes. Não mede
IoU de objetos, precisão de risco ou ID switches sem ground truth correspondente.
Dados de objetos/risco são formato preparado para revisão, sem avaliador quantitativo
de MOT/risco implementado. Métricas automáticas descritivas não substituem essa revisão.
