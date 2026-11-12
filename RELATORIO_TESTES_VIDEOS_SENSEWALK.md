# Relatório de testes em vídeos — SenseWalk V2.4

**12/11/2026 — resultado: falhas visuais relevantes; fluxo assistivo não aprovado.**
O projeto compila e tem novas evidências reproduzíveis. Isso não certifica travessia.

## 1. Estado recebido e preservação

- HEAD recebido: `55ec182`, posterior ao HEAD `896f667` descrito na auditoria.
  Árvore rastreada inicialmente limpa; `videos/` era conteúdo não rastreado do usuário.
- Lidos `AUDITORIA_ASTRA_SENSEWALK.md`, README, documentação de travessia/automático/USB,
  Kotlin relevante e validadores YOLO/FFNet. O arquivo de pendências não existia; foi criado.
- Algumas pendências da auditoria já estavam corrigidas no código recebido, como a
  revalidação de token/timestamp na entrega dos callbacks de status/boxes à UI; preservadas.
- A árvore tinha **93 testes**, incluindo um caso posterior aos 92 da auditoria:
  `roadAgeMustIncludeCurrentInferenceLatency`. Esse caso falhou na primeira execução.
  Todos os casos foram mantidos. Duas expectativas de movimento foram atualizadas de
  `APROXIMANDO` para `POTENCIAL_CONFLITO`, conservando a detecção do movimento e seu alerta.
- Sem push, merge, retreinamento, modificação dos assets ou descarte de correções anteriores.

## 2. Vídeos e integridade

Não havia `videos_teste/`; encontrados os originais em `videos/`. Criadas referências
locais com os nomes esperados, preservando os arquivos originais.

| Referência | Original em `videos/` | Resolução | FPS informado | Duração informada | Frames decodificados | Inferidos |
|---|---|---|---:|---:|---:|---:|
| `teste_01_faixa_veiculo.mp4` | `WhatsApp Video 2026-10-15.mp4` | 854×480 | 30,008893 | 30,924 s | 928 | 310 |
| `teste_02_faixa_semaforo.mp4` | `WhatsApp Video 2026-10-15 at 21.05.41.mp4` | 854×480 | 29,994959 | 31,605 s | 948 | 316 |

**1.876 frames decodificados; 626 amostras inferidas por modelo**, stride 3 (~10 Hz de
mídia). Contagem decodificada coincide com o contêiner; nenhuma mudança de dimensão,
frame vazio ou erro de decodificação observado. Rotação declarada 0°, paisagem coerente
na inspeção. PTS crescente do contêiner, zero fallbacks de timestamp. Não é medição do
FPS físico de captura. Hashes dos vídeos em `metricas.json`.

## 3. Modelos e contrato

| Modelo real do APK | Entrada | Saída | SHA-256 |
|---|---|---|---|
| `model.tflite` | FLOAT32 `[1,640,640,3]` | FLOAT32 `[1,15,8400]` | `b11df3830eb40b413fa1dea0a845c17f1d2af38ebc3fb1e599aff50c0ac4a8fd` |
| `ffnet_122ns_lowres.tflite` | UINT8 `[1,512,1024,3]` | UINT8 `[1,128,256,19]` | `814ed92970669c0fe1f436e07733cdd41247cddc741c21657d51dadabfdff34f` |

Hashes iguais nas fontes e dentro do APK gerado. FFNet: escala de entrada
0,003920366987586021/zero 0; saída 0,20315125584602356/zero 153.

YOLO usa RGB/255, letterbox 114, geometria truncada do Android, coordenadas normalizadas,
confiança 0,20 e NMS 0,45 por classe. Os 11 nomes/IDs seguem `AppConfig`/preparação V3:
car, motorcycle, bicycle, truck, bus, traffic-light-green/red/yellow, crosswalk,
pedestrian-light-green/red. **O asset não contém metadados nominais vinculando os nomes
ao hash**: o contrato numérico está confirmado, a procedência nominal permanece aberta.

FFNet usa resize-pad preto, quantização RGB, normalização ImageNet já dentro do grafo,
interpolação dos escores antes do argmax e ROI inferior central do Android. Ordem
Cityscapes oficial de 19 classes, pista=0 e calçada=1; não se inventa confiança probabilística.

## 4. Paridade e correção do validador

O script antigo foi reutilizado, preservando seu comportamento legado e acrescentando
modo Android com boxes float, clipping, igualdade no threshold e NMS compatível.

O replay executa o **letterbox da classe de produção `YoloDetector`** com Skia nativo
no Robolectric. Isso revelou um erro concreto do Python: para 854×480, o Android
rasteriza `padY=140,5` na linha 141; o Python usava 140. O erro médio inicial era
8,23/4,77 níveis por canal nos dois primeiros frames. Corrigido o alinhamento; a
inferência inteira foi repetida, mantendo os resultados anteriores em
`videos_teste/resultados_antes_letterbox/`.

Também são compartilhados os tensores reais do primeiro frame de cada vídeo:
decode/NMS Kotlin versus Python, interpolação/argmax FFNet pixel a pixel e ROI em
todas as amostras. O teste de entrada YOLO exige MAE < 1 nível por canal de 8 bits.
Diferenças residuais de interpolação Skia/OpenCV e LiteRT host 2.3.0 versus Android
1.4.2 permanecem explícitas. **Não é teste de integração de câmera/TFLite Android**.

## 5. Faixas e veículos detectados

Resultados da execução final, depois da correção de padding:

| Boxes após NMS | Vídeo 1 | Vídeo 2 |
|---|---:|---:|
| `car` | 47 | 217 |
| `crosswalk` | 0 | 17 |
| `pedestrian-light-green` | 241 | 63 |
| `pedestrian-light-red` | 255 | 197 |
| `traffic-light-green` | 0 | 86 |
| **Total** | **543** | **580** |

Outras classes: zero. Contagens de boxes ao longo do tempo **não são objetos únicos**.

- Faixa: **0/310** e **17/316** amostras com detecção. No vídeo 1, nenhuma detecção
  entre 0 e 30,891 s. No vídeo 2, não há detecção entre 0 e 25,104 s; os positivos
  concentram-se aproximadamente entre 25,20 e 27,40 s, com lacunas.
- Revisão visual esparsa registrada em `anotacoes_revisao_astra.json`: 16 amostras do
  vídeo 1 e 15 do vídeo 2 têm faixa visível. São anotações **do assistente**, não ground
  truth humano independente. O restante do vídeo não foi implicitamente anotado.
- No vídeo 2, frame 780 (~26 s), a box cobre marcações triangulares com a câmera
  voltada ao longo da via: **hipótese de falso positivo**, deixada como `unknown`.
  Não é justificativa para reduzir thresholds ou considerar esses positivos como sucesso.
- Há veículo visível sem box em exemplos inspecionados: vídeo 1, frame 600 (~19,99 s),
  e vídeo 2, frame 420 (~14 s). Há também exemplos corretos de box sobre carro, como
  vídeo 1/frame 420 e vídeo 2/frame 600. Esses exemplos não fornecem recall de veículos.
- O diagnóstico compara geometria Android, arredondamento legado e resize deformado
  apenas para investigação. Os escores muito baixos em faixas visíveis não sustentam
  uma simples mudança de threshold. **Nenhum limiar do aplicativo foi reduzido.**

CSV, gráficos e intervalos sem detecção estão nos resultados. Intervalos não revisados
são candidatos a inspeção, não falsos negativos automaticamente confirmados.

## 6. FFNet: pista, calçada e continuidade

| Critério da ROI Kotlin | Vídeo 1 | Vídeo 2 |
|---|---:|---:|
| Pista ≥12% | 310/310 | 316/316 |
| Calçada ≥5% | 110/310 | 153/316 |
| Pista/calçada/contato suficientes para `possibleRoadBoundary` | 110/310 | 153/316 |

Máscaras sobrepostas mostram região de pista consistente em grande parte das amostras.
Há oscilações de calçada/contato, especialmente ao reposicionar a câmera. Exemplos
mostram superfícies pavimentadas ao fundo e trechos próximos à borda recebendo rótulos
variáveis; precisam de máscara de referência para medir erros de superfície.

`segmentacao.csv` registra IoU de pista entre amostras consecutivas, **sem alinhamento
de câmera**. A queda pode decorrer tanto do giro quanto da segmentação; não é acurácia.
Não houve perda do critério de presença de pista nessa ROI, mas isso não prova que todos
os pixels estejam corretos. Contato de classes não localiza os pés/meio-fio e não
comprova local apropriado, legal ou seguro para travessia.

## 7. Replay Kotlin, movimento e sinais

`OfflineVideoReplayTest` executou nas 626 amostras as classes reais:
`AdvancedVehicleTracker`, `Rastreador`, `VehicleMotionRules`,
`SparseOpticalFlowEstimator`, `SceneEvidenceTracker`, `RoadSceneAnalyzer`,
`CrossingRiskAnalyzer` e `CrossingVerification`. Frames PNG sem perdas, timestamps
de mídia e saídas dos modelos Linux alimentam o replay. Ghosts são separados de
observações e não atualizam cinemática.

Resultados estruturados: `tracking.csv`, `cena_kotlin.csv`, `replay_kotlin.json` e
`avaliacao.json`. Eles registram IDs, entradas/saídas, lacunas, velocidades de imagem,
escala, confiança do fundo, rotação 2D, incerteza, razões de risco e estados.
Criação de IDs e lacunas são observáveis; **não equivalem a ID switches anotados**.

O movimento horizontal independente agora gera `POTENCIAL_CONFLITO` quando dirigido
ao centro; preserva aviso SAFETY. Projeção varrida da box por um segundo pode acrescentar
conflito com a faixa, mas não liberar veículos nem estimar TTC físico. Expansão/retração
não provam aproximação/afastamento; uma box pequena não comprova distância segura.

O ajuste robusto de similaridade estima rotação **no plano da imagem**, escala e
resíduo; rejeita translação quando esses termos ou desacordo do fundo a invalidam.
Testes incluem translação, carro parado sob rotação/zoom e camadas de movimento
incompatíveis; frames sintéticos também atravessam o estimador efetivo. Não é solução
geral de paralaxe, homografia, profundidade ou câmera caminhando.

Semáforos: detecções verdes/vermelhas existem, mas o tipo alterna entre pedestre e
veículo em regiões próximas de um mesmo conjunto de sinais. Continuidade por classe,
perdas e timestamps estão em `avaliacao.json`; não foi certificado que se trata do mesmo
sinal físico. **Aplicabilidade é incerta**. Verde geométrico agora exige evidência
independente adicional para sustentar resultado favorável; continua exigindo laterais.
Vermelho/ocupação/obstáculo preservam bloqueios e alertas.

Não há ground truth suficiente para declarar taxa de falsos alertas, alertas perdidos,
classificação física de movimento, IDF1, precisão ou recall de objetos. Não houve
resultado favorável no replay sem cobertura física.

## 8. Correções e implementação

1. **Idade do contexto:** `SceneConfidence.frameAgeMs` transporta o custo até a decisão;
   contexto FFNet e laterais não ganham validade artificial. Contraprova preexistente
   de contexto 500 ms anterior + inferência de 1.400 ms agora passa.
2. **Faixa temporal:** dois hits separados por 100 ms não amadurecem durante ausência
   até 500 ms; a separação exige timestamps de detecções efetivas. Teste falhou antes.
3. **Movimento:** similaridade robusta/rejeição, trajetória varrida e escala incerta;
   sem afirmação de aproximação física baseada apenas em movimento lateral.
4. **Orientação:** diagnóstico visual integrado ao controlador. Câmera externa não
   usa orientação do celular. Sem yaw/FOV/calibração, cobertura continua não comprovada.
5. **Estados:** início manual conectado à sequência; FFNet disponível no manual mesmo
   com automático desativado. Expiração, cancelamento e perigo continuam prevalecendo.
6. **Sinal/mão:** verde exige associação independente; mensagem solicitada de levantar
   uma das mãos está conectada somente ao cenário atestado de sinal não aplicável e
   verificações completas. Ausência de detecção não ativa o gesto; não promete parada
   dos motoristas. O produtor real da ausência de sinal ainda não existe.
7. **Latência:** LUT de quantização FFNet verificada exaustivamente nos 256 valores;
   elimina quantização repetida de 1.572.864 canais/frame. Não se afirma ganho medido
   de FPS Android. Conversão YUV/JPEG e idade física de fontes externas continuam abertas.
8. **Acessibilidade/configuração:** Parar/direção/voz não somem com o painel informativo;
   descrição de Parar no automático, autoescuta, comandos USB/direção, prioridade de
   Stop e negações no parser. Tempo de ausência de movimento ligado à preferência.
   Removidos seletores sem efeito para tensor/proximidade; mensagens USB e principais
   textos X5 traduzidos. Correções TTS anteriores preservadas.

## 9. Testes, build e métricas

- Suíte JVM/Robolectric: **109 casos**, incluindo o replay real e novas regressões.
- Python: **10 casos** (4 existentes + 6 novos).
- `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`: concluídos;
  lint **0 erros, 145 avisos**, sem supressão para aprovação.
- `check_16kb.py`: 16 bibliotecas ARM64/x86_64 aprovadas LOAD/RELRO/ZIP;
  `zipalign -c -P 16 -v 4`: código zero.
- **Nenhum teste instrumentado foi executado**: apenas compilado o APK de teste.

Tempos de parede Linux da execução final:

| Tempo, ms (p50 / p95) | Vídeo 1 | Vídeo 2 |
|---|---:|---:|
| Inferência YOLO | 30,97 / 32,40 | 30,72 / 32,15 |
| Inferência FFNet | 51,72 / 53,66 | 51,60 / 53,50 |
| Pré/inferências/pós Python | 100,99 / 104,91 | 100,47 / 104,97 |

Não inclui decodificação, gravação de artefatos, tracking Kotlin ou som. Não extrapolar
para celular, FPS sustentado, temperatura, latência exposição→áudio ou ganho antes/depois.
Python 3.12.16, NumPy 2.5.4, OpenCV 5.0.0, LiteRT 2.3.0; CPU, 4 threads YOLO/2 FFNet.

### APK

`Projeto_Android/app/build/outputs/apk/debug/app-debug.apk`

SHA-256: `1efc7cc54624a7a3550ce56a693425ea084665350f2cb2f72be41e983f489e5f`.
APK instrumentado: `Projeto_Android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
Hashes completos/fontes/modelos em `Projeto_Android/validation/videos_2026-11-12/artifacts.json`.

## 10. Reprodução e evidências

Instruções completas: [`videos_teste/README.md`](videos_teste/README.md).

```bash
python Projeto_Android/tools/offline_video_validation.py --stride 3 --replay-kotlin
# JDK 17 / SDK 35 / dependências requirements-offline.txt necessários.
# Use --output videos_teste/resultados_novos para preservar a execução atual.
```

Execução nesta máquina usou Python/JDK isolados em `/tmp/opencode/astra-audit/`;
não altera configuração global. O replay foi chamado via `SENSEWALK_VIDEO_RESULTS`
e `./gradlew testDebugUnitTest`, com os mesmos artefatos produzidos pelo comando composto.

Resultados finais em `videos_teste/resultados/`: `deteccoes.csv`, `segmentacao.csv`,
`tracking.csv`, `cena_kotlin.csv`, `metricas.json`, `avaliacao.json`, `replay_kotlin.json`,
`diagnostico_preprocessamento.csv`, `relatorio_offline.md`, dois MP4 `_anotado`, dois
MP4 `_tracking`, gráficos SVG e pastas com frames/máscaras/fixtures. Somente artefatos
efetivamente gerados são descritos; diretórios volumosos ignorados pelo Git.
Logs e resumos pequenos preservados em `Projeto_Android/validation/videos_2026-11-12/`.

## 11. Limitações e pendências abertas

Os vídeos não validam orientação física do usuário, cobertura direita/esquerda/frente
de uma travessia guiada, posição dos pés, captura→áudio, webcam USB, X5, TTS Android,
TalkBack ou segurança em trânsito. Também não validam execução em kernel 16 KB.

Prioridades: falhas de faixa/veículo e possível confusão com marcações triangulares;
procedência de classes; anotações humanas independentes de boxes/IDs/movimento/sinais;
paralaxe/ego-movimento/escala; orientação calibrada por fonte; aplicabilidade semafórica;
contexto físico de travessia e prazo operacional. Não foi solicitado conectar celular
para concluir as atividades disponíveis no computador.

Lista atualizada: [`PENDENCIAS_SENSEWALK_FINALIZACAO.md`](PENDENCIAS_SENSEWALK_FINALIZACAO.md).
