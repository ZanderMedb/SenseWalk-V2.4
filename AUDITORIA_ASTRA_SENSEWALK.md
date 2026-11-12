# Auditoria Astra — SenseWalk V2.4

**Data:** 12/11/2026. **Resultado: REPROVADO para a próxima etapa de validação funcional da travessia.**

Esta classificação não é certificação de segurança nem autorização para uso autônomo em trânsito. Os testes de bancada podem continuar para resolver os bloqueadores descritos aqui.

## A. Resumo executivo

**Os dez requisitos não foram integralmente cumpridos.** Há inferência real, tracking, análise semântica, consentimento e políticas de alerta integrados. Entretanto, não existe comprovação física da direção/cobertura da câmera, nem avaliação validada de trajetória/conflito, nem comprovação de melhora da precisão ou do desempenho no aparelho.

A árvore recebida **não compilava**. Depois da correção mínima de compilação, seus 71 testes existentes passaram. Testes adicionais da auditoria reproduziram falhas que esses testes não capturavam: crash previsível no layout horizontal, avanço direcional sem cobertura, manutenção indevida de evidências no monitoramento, interrupção de perigo por perguntas/comandos, fala favorável não cancelada e anúncio de ausência de veículos após alerta lateral.

Após as correções:

- **92 testes JVM/Robolectric: zero falhas, erros ou ignorados.** São 71 testes preexistentes e 21 adicionados nesta auditoria.
- **4 testes Python: passaram.** Corrigido também o decodificador de boxes usado na validação offline.
- `assembleDebug`, `assembleDebugAndroidTest` e `lintDebug`: concluídos. Lint: **0 erros, 148 avisos**.
- APK recém-gerado: **16 bibliotecas ARM64/x86_64 aprovadas em LOAD/RELRO/ZIP**, além de `zipalign -c -P 16 4` com código de saída zero.
- Os dois assets TFLite executaram em CPU **Linux**, com saída finita. Isso não equivale à execução do pipeline Android ou à validação de classes/acurácia.
- **Nenhum teste instrumentado foi executado nesta auditoria.** `adb devices -l` não listou aparelhos; não havia emulador conectado. Não houve instalação do APK.

**Bloqueadores remanescentes:** orientação/cobertura não implementadas; movimento próprio não distinguido de paralaxe/rotação/escala de câmera de forma confiável; relevância de veículos baseada em heurísticas de imagem; idade física dos frames USB/X5 desconhecida; associação semafórica apenas aproximada; ausência de conjunto sequencial anotado e de benchmark pós-alteração. A orientação para levantar a mão existe como texto, mas **não é chamada**.

### A.1 Escopo, origem e preservação

- Repositório local: `/home/zander_mb/SenseWalk-V2.4`; remoto conferido: `https://github.com/ZanderMedb/SenseWalk-V2.4.git`.
- Branch: `master`; HEAD: `896f6674c5d63fe966eb0018471ea1339ebbded7`.
- Histórico examinado: `896f667` (FFNet/USB/voz por estado), `34238df` (X5/contexto/GPS anterior) e `e3ffdae` (substituição do TFLite compacto), além do log dos dez commits mais recentes.
- Entrada: **23 arquivos rastreados modificados**, mais arquivos não rastreados. `git diff --stat`: 510 inserções/168 remoções, sem contar não rastreados. `git diff --check` inicialmente limpo.
- Os commits não identificam um autor chamado Sol. As alterações recebidas foram auditadas pelo conteúdo, sem atribuir autoria individual não demonstrada. Já existiam, inclusive, arquivos chamados `AstraSafetyRegressionTest.kt` e `RastreadorAuditTest.kt`: **não foram criados nesta execução**.
- Snapshot das fontes recebidas e diff inicial preservados em `/tmp/opencode/astra-audit/`. O status inicial foi copiado para a pasta de evidências abaixo. Não houve push, merge, reset, retreinamento ou alteração dos pesos.
- A documentação anterior divergia do próprio código recebido: dizia que YOLO pausava antes do consentimento, que botões completavam as três direções e que havia orientação de mão integrada. Na árvore recebida, o monitoramento já executava YOLO, a cobertura já era sempre `false` e a fala da mão não tinha chamador.

### A.2 Evidências reproduzíveis

Pasta: **`Projeto_Android/validation/astra_2026-11-12/`**.

| Arquivo | Conteúdo |
|---|---|
| `initial-status.txt` | Estado anterior às intervenções desta auditoria |
| `build-before.log` | Falha de compilação em `CrossingVerification.invalidate()` |
| `tests-baseline.log` | Suíte recebida após reparar somente a compilação |
| `regressions-before.log` | 6 testes direcionados: 5 falharam antes da correção |
| `voice-before.log` | 5 testes sobre VoiceSystem: 4 falharam antes da correção |
| `integration-intermediate.log` | Reprodução da contradição perigo lateral → ausência de veículos |
| `build-validation.log` | Execução final de testes/builds/lint Android |
| `jvm-tests.json` | Nomes e contagens das 15 suítes/92 casos executados |
| `python-tests.log` | Ambiente e execução dos quatro testes Python |
| `model-contract.json` | Contratos, hashes e inferências host dos assets reais |
| `artifacts.json` | Hashes dos APKs novos e dos modelos dentro do APK |
| `checks.md` | Verificações complementares e contraprova do validador Python |

## B. Matriz de conformidade

Nesta matriz, aprovação com ressalvas cobre **somente o comportamento explicitamente testado**, não o sistema em trânsito. Abreviações de caminhos: **K** = `Projeto_Android/app/src/main/java/com/travessia/segura/`; **T** = `Projeto_Android/app/src/test/java/com/travessia/segura/`. Os caminhos completos são obtidos concatenando esses prefixos.

| Requisito | Status | Evidência | Falha encontrada | Correção / limite |
|---|---|---|---|---|
| Detecção de faixa | NÃO VALIDADO | K`detection/YoloDetector.kt::detectar`, K`road/SceneEvidence.kt::update`; `model-contract.json`; T`road/CrossingVerificationTest.kt` | Sem dataset limpo/anotado; ID nominal 8 não provado por metadados; thresholds não calibrados | Estabilização real existe; não há prova de melhoria de precisão, faixa inclinada/desgastada ou rejeição de estacionamento |
| Detecção de rua | NÃO VALIDADO | K`road/RoadSegmentationManager.kt::segment`, `RoadSceneAnalyzer::analyze`; T`road/RoadMonitoringTest.kt` | FFNet real, mas máscara/contato pista-calçada não comprovam local apropriado; sem teste visual dos cenários requeridos | Políticas sintéticas verificadas; sem faixa a conclusão favorável é bloqueada |
| Carros distantes | REPROVADO | K`road/CrossingRiskAssessment.kt::analyze`; T`road/AstraSafetyRegressionTest.kt` | Não há trajetória até região de conflito/TTC/distância; qualquer movimento incerto bloqueia conclusão, inclusive box pequena | Não descartar incerteza é correto, mas não atende à diferenciação exigida; corrigida fala falsa de ausência no legado |
| Carros estacionados | NÃO VALIDADO | K`tracking/Rastreador.kt::classificar`; T`tracking/RastreadorAuditTest.kt`, `OpticalFlowAuditTest.kt` | Expansão isolada deixou de provar aproximação; paralaxe/rotação/escala não são compensadas | Negativo de expansão e positivo de movimento lateral passaram em sequências sintéticas; generalização real pendente |
| Máquina de estados | REPROVADO | K`road/CrossingVerification.kt::observe`, K`logic/StateMachine.kt::processar`; T`road/AstraAuditFindingsTest.kt` | Compilação quebrada; expiração/monitoramento incompletos; fluxo favorável inacessível no adaptador real | Reparadas falhas demonstradas; bloqueio por cobertura ausente preservado e explicado |
| Verificação lateral | NÃO IMPLEMENTADO | Ambos os processadores passam `coverageVerified=false`; `directionConfirmed` sempre falso | Nenhum sensor, referência visual ou medição física de direção/cobertura | Botão não completa mais nem a primeira lateral sem cobertura; termina inconclusivo, sem loop automático impossível |
| Faixa sem sinal / mão | NÃO IMPLEMENTADO | K`road/RoadText.kt` contém `verify_hand`; pesquisa de chamadas | Texto sem execução; `NOT_APPLICABLE_CONFIRMED` não tem produtor real | Não inventada confirmação de ausência de sinal; implementação funcional permanece pendente |
| Semáforos | APROVADO COM RESSALVAS | K`road/SceneEvidence.kt::update`; T`road/CrossingVerificationTest.kt::crosswalkNeedsTwoSeparatedDetectionsAndVehicleSignalIsNotPedestrianSignal`, `pedestrianLightRequiresAssociationAndGreenClockResetsOnLoss` | Tipo é separado, mas aplicabilidade depende de heurística espacial sem validação | Verde de carros não alimenta verde pedestre; vermelho/ocupação bloqueiam; perda de sinal invalida; sem previsão de tempo restante |
| Repetição | APROVADO COM RESSALVAS | K`road/CrossingVerification.kt::retryCurrentDirection/observe`; T`road/CrossingVerificationTest.kt`, `AstraAuditFindingsTest.kt` | Antes, etapa podia avançar sem cobertura; capacidade ausente não seria resolvida repetindo timers | Sem redução de limiar por tentativas; expiração e lacunas testadas; limitação permanente agora explicada uma vez |
| Alertas | APROVADO COM RESSALVAS | K`logic/StateMachine.kt::processar`, K`road/InferenceCoordinator.kt::process`; T`road/AndroidSafetyIntegrationTest.kt`, T`voice/VoiceSystemAuditTest.kt` | Perigo lateral podia ser seguido de “nenhum veículo”; perguntas/comandos cortavam SAFETY | Correções passaram com TTS simulado; perigo continua antes do consentimento e após resultado; áudio real pendente |
| Desempenho | NÃO VALIDADO | K`benchmark/BenchmarkLogger.kt`, K`road/RoadPerformanceLogger.kt`; CSV histórico | Sem medições pareadas antes/depois; conversão JPEG e alocações continuam | Corrigidos preview sem limite e cálculo do custo FFNet; nenhum ganho percentual alegado |
| Boxes / overlay | NÃO VALIDADO | K`ui/OverlayView.kt::updateDetections/transformToView`; ambos os processadores | Separação de renderização e inferência existe; alinhamento real entre crops CameraX/preview não testado | Ocultar boxes não condiciona YOLO/tracking no código; geometria física/renderização ainda dependem de aparelho |
| Detecção automática | APROVADO COM RESSALVAS | K`config/AppConfig.kt::automaticRoadDetection`, K`ui/MainActivity.kt::resumeRoadMonitoring`; T`road/AndroidSafetyIntegrationTest.kt::automaticDefaultAndExplicitDisablePersistAcrossConfigInstances`, `hazardInferenceRunsBeforeConsentAndStopsWithMonitoring` | Default e persistência demonstrados em Android simulado; permissões e UX não exercitados fisicamente | Default `true`; `false` explícito persiste; consentimento separado de alertas; parar bloqueia processamento |
| Câmeras | NÃO VALIDADO | K`camera/{FrameAnalyzer,UsbCameraClient,X5CameraClient,ExternalFrameProcessor}.kt`; testes USB/X5 passaram | Sem aparelho; timestamp de exposição externo não disponível | Fontes preservadas; protocolos/codecs unitários e build não comprovam captura, reconexão ou latência física |
| Voz e idiomas | APROVADO COM RESSALVAS | K`voice/VoiceSystem.kt`; T`voice/VoiceSystemAuditTest.kt`, T`road/AndroidSafetyIntegrationTest.kt`; K`road/RoadText.kt` | Prioridades/cancelamento defeituosos; UI USB/X5 ainda tem PT fixo; autoescuta desabilitada no código | Prioridades e cancelamento testados; PT/EN/ES preservados; reconhecimento, pronúncia, TalkBack e audição pendentes |

### B.1 Inventário dos componentes e integração efetiva

“Tempo real” abaixo significa **chamado no processamento contínuo**, não garantia de prazo/FPS. Builds/testes atuais não validam retrospectivamente os APKs históricos.

| Componente (prefixo K) | Alteração recebida / execução real | Teste e parecer |
|---|---|---|
| `camera/FrameAnalyzer.kt` | CameraX → conversão → coordenador → fluxo óptico/YOLO/tracking/cena/risco/estados; filtra idade e ghosts; overlay opcional | Integrado por `MainActivity.initEngine/startCamera`; não é código morto. Compilado; captura nativa e timestamp source de outros aparelhos pendentes |
| `camera/ExternalFrameProcessor.kt` | Mesmo encadeamento para USB e X5, bitmap já orientado | Integrado em `iniciarProcessamentoExterno`; relógio local e geração protegem processamento, não medem exposição/remessa da câmera |
| `detection/YoloDetector.kt` | Verifica shape/tipo, RGB float [0,1], letterbox 114, NMS por classe; buffer de pixels reutilizado; exceção em falha de inferência | Asset executado no host; Android compilado. Heurística de formato por faixa numérica permanece; não houve ensaio de precisão |
| `detection/YoloGeometry.kt` | Corrige reutilização de imagem quadrada que não tem dimensão do tensor | Usado em `letterbox`; matemática inspecionada. Não comprova overlay nem pré-processamento pixel a pixel no aparelho |
| `detection/AdvancedVehicleTracker.kt` | Matching em duas etapas, velocidades com timestamps, expiração em 1,5 s, ghosts e tracks não observados separados | Executado nos dois processadores; política de track perdido testada por entrada sintética. ID switches/oclusões reais não medidos |
| `tracking/Rastreador.kt` / `VehicleMotionRules.kt` | Expansão/velocidade vertical não provam aproximação; crescimento pode gerar incerteza; fluxo ruim limpa histórico | Testes negativos/positivos e baixa frequência executados. Aproximação continua sendo heurística lateral, não prova física de movimento independente |
| `vision/SparseOpticalFlowEstimator.kt` | Block matching reduzido, exclusão das boxes anteriores, mediana de fundo, três quadrantes | Testes com textura/translação conhecida passaram. Sem rotação/escala/homografia/profundidade; confiança de matching não certifica ego-movimento |
| `road/RoadSegmentationManager.kt` | FFNet existente real, resize-pad, quantização UINT8 e interpolação de escores antes do argmax | Não substituído por simulação. Host confirmou contrato; testes instrumentados antigos não reexecutados |
| `road/RoadSceneAnalyzer.kt` | Pista/calçada na ROI inferior central; proporções e contatos | Máscaras sintéticas em `RoadMonitoringTest`; limites não comprovam meio-fio nem posição do usuário |
| `road/AutomaticDetectionManager.kt` | Mudança recebida exige `possibleRoadBoundary` para perguntar; consentimento/persistência/histerese | Suíte de 19 testes de rua/política passou. Decisão sobre lugar apropriado para atravessar não implementada |
| `road/InferenceCoordinator.kt` / `HazardMonitoringPolicy.kt` | Serializa os modelos, YOLO antes de FFNet e antes do consentimento, intervalos e geração de sessão | Teste Android simulado verifica chamada antes do consentimento e nenhuma após parar. Astra corrigiu idade do último YOLO, limpeza entre sessões e custo FFNet |
| `road/CrossingConsentController.kt` | Pergunta com botão/voz local, sim estrito, timeout e tentativa limitada | Parser/política testados; fluxo Android físico não. Astra removeu interrupção de SAFETY ao perguntar/cancelar/responder |
| `road/SceneEvidence.kt` | Faixa temporal com IoU; sinais veiculares e pedestres separados; verde observado | Testes de continuidade/perda/regressão temporal passaram. `NOT_APPLICABLE_CONFIRMED` só é fornecido por testes |
| `road/CrossingRiskAssessment.kt` | Corredor, overlap de faixa, obstáculo, aproximação, incerteza e track perdido | Integrado e testado com boxes/classificações sintéticas. Sem modelo de trajetória na via, distância métrica, cobertura ou TTC |
| `road/CrossingVerification.kt` | Sequência consentida e análise de janelas por direção | Recebido com erro de compilação. Astra corrigiu e reforçou invalidação. Favorável/monitoramento só demonstrados com cobertura atestada **injetada no teste** |
| `logic/StateMachine.kt` | Conserva contexto legado e alertas fora/dentro da sequência | Agora não exclui carros laterais para depois anunciar ausência. Teste sobre a classe real com TTS simulado reproduziu e validou correção |
| `voice/VoiceSystem.kt` / `VoicePriority.kt` | TTS, fila de três, expiração, estados e prioridades; logs de atraso | `VoiceSystemAuditTest` testa a classe real com ShadowTextToSpeech. Não mede geração acústica nem interação com reconhecimento/TalkBack |
| `config/AppConfig.kt` | Default automático ligado, captura configurável, preferências e classes | Persistência testada. Classes/configuração já tinham 11 IDs antes desta auditoria; não são 9 como o README antigo dizia |
| `config/LanguageManager.kt` / `road/RoadText.kt` | Novas mensagens PT/EN/ES | Textos existentes preservados; mensagem específica de orientação indisponível acrescentada. Algumas mensagens antigas USB/X5 não são traduzidas |
| `ui/MainActivity.kt` | Integra todos os componentes, botão direcional, callbacks, ciclo de vida e fontes | Defeito de landscape corrigido; preview limitado. Não foi iniciado em aparelho; caminhos de permissão/reconexão continuam pendentes |
| `ui/SettingsActivity.kt` | Seleção de largura de captura adicional | Compila. Tamanho de inferência continua determinado pelo modelo, apesar do seletor legado; não há validação física de reaplicação de resolução |
| `ui/OverlayView.kt` | Elimina IDs COCO incorretos no helper; desenho opcional | `getColorForDetection` só serve ao helper `setDetections`, que não é chamado pelo pipeline atual. O fluxo real envia `BoxInfo` diretamente |
| `benchmark/BenchmarkLogger.kt` / `road/RoadPerformanceLogger.kt` | Idade de frame e métricas de processamento; exportação CSV | Registros reais novos ausentes. Sessão `frames.csv` começa no modo manual; no automático predomina `road_*.csv` |
| `camera/LatestFrameDispatcher.kt` | **Adicionado pelo Astra:** uma entrega pendente para preview externo | Teste mantém 1 callback ao oferecer 1.000 frames, libera os 999 substituídos e testa parada/reinício |

### B.2 Modelo, pré-processamento e detecção de faixa

Inspeção direta dos arquivos, sem conversão/retreinamento:

| Asset | SHA-256 | Entrada | Saída |
|---|---|---|---|
| `Projeto_Android/app/src/main/assets/model.tflite` | `b11df3830eb40b413fa1dea0a845c17f1d2af38ebc3fb1e599aff50c0ac4a8fd` | FLOAT32 `[1,640,640,3]` | FLOAT32 `[1,15,8400]` |
| `Projeto_Android/app/src/main/assets/ffnet_122ns_lowres.tflite` | `814ed92970669c0fe1f436e07733cdd41247cddc741c21657d51dadabfdff34f` | UINT8 `[1,512,1024,3]`; escala 0,00392036699 / zero 0 | UINT8 `[1,128,256,19]`; escala 0,203151256 / zero 153 |

Ambos também têm esses hashes **dentro do APK novo**. A descrição do YOLO é `onnx2tf flatbuffer_direct (dynamic_range_quantized)`; não há metadados nominais. O script de exportação desativa explicitamente a inclusão de metadados. A lista de preparação/exportação é indício da ordem, não comprovação vinculada ao hash do asset.

Execução host: LiteRT Python **2.3.0**, duas threads, XNNPACK; Android usa LiteRT **1.4.2**. Rodados fundo cinza sintético e o mosaico existente `Dados_IA/dados_treinamento_ia/runs/detect/runs_sensewalk/val_best_agnostic/val_batch0_labels.jpg`. As saídas foram finitas. No mosaico houve 45 candidatos brutos de classe configurada `car` acima de 0,20 e **zero de `crosswalk`**. São candidatos **antes de NMS**, não 45 veículos confirmados. O mosaico tem textos/boxes incorporados e várias imagens redimensionadas: não é conjunto válido para medir recall, precisão ou concluir falha geral da faixa.

O YOLO retornou coordenadas de magnitude normalizada (máximo ~1,124 na imagem cinza e ~1,045 no mosaico); o Android multiplica pelo tamanho de entrada quando detecta esse contrato. O validador Python antigo não fazia isso e ainda ordenava limites negativos de volta para dentro de uma “box”. Contraprova: uma box sintética que deveria virar `(320,200,960,520)` produzia `(0,-278,1,0)`. Corrigido e testado.

Não há vídeos no diretório de validação do repositório. Não foram medidos: vagas de estacionamento confundidas com faixa, faixa próxima/inclinada/desgastada, iluminação, sinais ocluídos ou generalização das classes. **Não se afirma melhora da precisão.**

### B.3 Heurísticas questionadas

| Regra encontrada | Limitação |
|---|---|
| Confiança YOLO padrão 0,20; faixa também exige ≥0,20 | Sem curva de calibração para este asset/câmeras. A preferência global pode alterar sensibilidade; confiança do detector não mede ausência de perigo |
| Faixa: ≥2 hits, ≥400 ms, lacuna/hold ≤850 ms, IoU ≥0,25 | Pode perder faixa com giro, baixa taxa de inferência ou desgaste; retenção não equivale a nova observação |
| FFNet: pista ≥12%, calçada ≥5%, contato ≥0,2% da ROI | Não distingue passagem legal, estacionamento asfaltado, largura da pista ou posição dos pés |
| Optical flow: amostra de largura 160, busca ±5, inliers ±2, confiança mínima configurável 0,35 | Confiança descreve correspondência de textura, não ausência de paralaxe; fundo dominante pode não representar a profundidade do carro |
| Giro/movimento “grande” >12 pixels/frame | Não é normalizado por resolução/intervalo; comportamento varia com fonte e FPS |
| Velocidades 18/30 px/s, escaladas pela largura; ≥5 amostras para aproximação | Sem distância métrica; poucos frames em dois segundos podem impedir classificação. Movimento lateral em direção ao centro não prova aproximação da travessia |
| Corredor x=25–75%; obstáculo bottom≥78% e largura≥23%; conflito potencial largura≥12% e bottom≥65% | Heurísticas de projeção, não corredor do usuário no mundo. Box pequena não prova distância; câmera lateral muda completamente a interpretação |
| Sinal pedestre acima da faixa e centro a até 38% da largura | Pode associar o sinal de outra travessia; falta associação topológica da via |
| Frame ≤1.500 ms, janela ≥1.500 ms, retry após 7 s, laterais ≤12 s | Limites sem justificativa baseada em velocidade de veículos/campo de visão. São condições internas, não um orçamento validado para trânsito |

### B.4 Cinco riscos prioritários

1. **Estacionado versus aproximação:** crescimento isolado não gera `APROXIMANDO` nos testes. O negativo prioritário foi exercitado também com **frames de textura traduzida**, calculando o fluxo real do estimador, e boxes sintéticas crescentes. Resultado: `MOVIMENTO_INCERTO`. O positivo com movimento lateral próprio começando depois de repouso gera `APROXIMANDO`. Isso demonstra dois casos controlados, não elimina falsos alertas: um carro parado próximo pode ter paralaxe diferente do fundo e produzir resíduo lateral que a regra interpreta como aproximação. Aproximação frontal real baseada sobretudo em expansão permanece incerta; o alerta de obstáculo/conflito potencial depende da geometria visível.
2. **Distantes versus relevantes:** um carro marcado `PARADO`, pequeno e fora de conflito pode não adicionar razões de risco. Porém os testes fornecem essa classificação; não demonstram que o pipeline reconheça um carro distante real como irrelevante. Veículos `EM_MOVIMENTO`/`MOVIMENTO_INCERTO` continuam inconclusivos independentemente do tamanho. Faltam trajetórias e qualidade/oclusão do tracking integradas de forma suficiente à decisão.
3. **Favorável sem verificações válidas:** ambos os processadores recebidos já passavam cobertura `false`; portanto **não há evidência de conclusão favorável emitida no aplicativo real**. Havia falhas latentes no controlador, demonstradas com cobertura verdadeira sintética: laterais não expiravam no monitoramento, cobertura podia desaparecer e ausência de sinal comprovada podia virar mera não detecção. Reparadas. Antes também avançava de direita para esquerda sem cobertura; agora informa a impossibilidade e mantém inconclusivo. Não substituímos o sensor ausente pelo botão.
4. **Alerta deixar de funcionar:** política de monitoramento chama YOLO antes do consentimento e independente da sequência. Testes reais de `VoiceSystem` com TTS simulado revelaram e corrigiram preempção inversa por pergunta/status. O estado legado também anunciava ausência de veículos após detectar aproximação lateral: corrigido. Persistência sonora, reconhecimento simultâneo e avisos iguais competindo ainda exigem ensaio físico.
5. **Frames antigos:** idade monotônica/geração/filas de inferência são reais. Corrigidos refresh artificial da idade ao terminar YOLO, contexto retido entre sessões e preview externo com fila de callbacks ilimitada. **Risco remanescente:** USB marca chegada ao callback nativo; X5 marca entrega após decodificação. Buffers de driver/rede anteriores a esses instantes não entram na idade medida. Não há prova de prazo exposição→decisão→som. Callbacks de status/overlay já postados à UI também não carregam o token original para uma segunda validação; uma atualização visual superada pode reaparecer após troca/parada. Isso não é usado como evidência no controlador, mas requer correção/ensaio de integração.

### B.5 Estados, comunicações e cenários

- Existem monitoramento, candidato, consentimento, análise inicial, espera/verificação direita/esquerda/frente, inconclusivo, perigo, favorável e monitoramento posterior. Cancelar retorna a `IDLE`; erro de vídeo invalida. Não há estados específicos de câmera separados dentro de `CrossingVerification`; a integração vem do coordenador/watchdog.
- `ANALYZING` usa “Verificando o trânsito. Mantenha a câmera nesta direção.”. Favorável tem o texto limitado esperado, porém não é alcançável pelos adaptadores atuais. Perigo recebe avisos específicos de aproximação, ocupação ou obstáculo da máquina de trânsito, além da mensagem genérica do controlador.
- A sequência começa no aceite do consentimento automático. O início manual continua no estado legado e **não chama `crossing.start()`**: não demonstrar fluxo guiado completo no modo manual.
- Antes da correção, dizer “Verificação concluída. Agora vire…” após uma janela sem cobertura era indevido. Agora não se chega à próxima etapa dessa forma. A mensagem PT/EN/ES explica que direção/cobertura não podem ser comprovadas; os alertas continuam.
- O cancelamento geral e o comando/botão Parar existem. Não há comando de voz específico para confirmar a orientação; o botão depende de TalkBack/toque e de permanecer visível no painel. Ocultar o painel inferior também esconde controles essenciais: pendência de acessibilidade.
- Não há contador de tentativas convertendo incerteza em favorável, nem redução progressiva de thresholds. Sem faixa, `unsupportedGeometry` bloqueia conclusão. Sem orientação, `unsupportedDirection` evita uma sequência automática interminável.
- Vermelho e veículo ocupando a faixa prevalecem sobre verde. Verde de carros é `VEHICLE_ONLY`, nunca verde pedestre. O relógio de verde é tempo observado, não tempo restante. Associação correta ao cruzamento continua não validada.

| Cenário solicitado | O que foi executado | Resultado / pendência |
|---|---|---|
| 1 — Faixa e veículos distantes | Boxes/cenas sintéticas em `CrossingVerificationTest` e `AstraSafetyRegressionTest`; inferência host no mosaico | Política parcial demonstrada. Sem orientação física, sinal ausente comprovado e vídeo anotado, cenário completo **não validado** |
| 2 — Faixa ocupada | Ocupação/obstáculo, verde de veículos, vermelho e perigo após favorável em testes sintéticos | Bloqueios passaram. Não é demonstração visual de um carro real sobre faixa com sinal aplicável |
| 3 — Rua sem faixa | Máscaras FFNet sintéticas, persistência/consentimento e `roadWithoutCrosswalkCannotBecomeFavorableEvenAfterThreeDirections` | Pergunta e bloqueio de favorável demonstrados como política; análise real de lugar apropriado **não implementada** |
| 4 — Carro estacionado | Sequência de boxes com expansão/translação; sequência de textura alimentando optical flow; positivo lateral após repouso | Negativos e positivos passaram no domínio sintético. Sem vídeo conhecido, paralaxe, escala, mudança de velocidade e alerta físico permanecem pendentes |

## C. Resultados dos testes e comandos

### C.1 Ambiente

Linux; SDK Android existente em `/home/zander_mb/Android/Sdk`; Gradle Wrapper 8.13, AGP 8.13.2, Kotlin 1.9.24, compile/target SDK 35, min SDK 26. Java inicialmente ausente. A primeira tentativa falhou com `JAVA_HOME is not set`.

Foi baixado Temurin JDK **17.0.21+9** para `/tmp/opencode/astra-audit/jdk-17.0.21+9`, sem alterar o projeto nem configuração global. Python 3.14 do sistema não tinha `ensurepip`; a tentativa de venv falhou. Para testes de modelos/Python, foi criado ambiente isolado via `uv` com Python **3.12.16**, LiteRT 2.3.0, tflite 2.18.0, NumPy 2.5.4, Pillow 13.0.0 e OpenCV headless 5.0.0.93. Nenhum retreinamento foi executado.

### C.2 Comandos efetivamente executados

Na raiz:

```bash
git status --short
git diff --check
git diff --stat
git log -10 --oneline
git log -3 --format=fuller --stat
git remote -v
git branch --show-current
git rev-parse HEAD
adb devices -l
```

Em `Projeto_Android`, com `JAVA_HOME` e `PATH` apontando para o JDK isolado:

```bash
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain
# Falhou inicialmente: seis referências não resolvidas em CrossingVerification.invalidate().

./gradlew testDebugUnitTest --console=plain
# Passou após a correção mínima de compilação: 71 casos preexistentes.

./gradlew testDebugUnitTest --tests '*AstraAuditFindingsTest' --tests '*MainLayoutContractTest' --console=plain
# 6 executados; 5 falhas reproduzidas antes das correções correspondentes.

./gradlew testDebugUnitTest --tests '*VoiceSystemAuditTest' --console=plain
# 5 executados; 4 falhas reproduzidas antes da correção de voz.

./gradlew testDebugUnitTest --tests '*AndroidSafetyIntegrationTest' --tests '*VoiceSystemAuditTest' --tests '*AstraAuditFindingsTest' --tests '*MainLayoutContractTest' --console=plain
# 15 executados; restou a contradição de perigo lateral. Corrigida depois.

./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --console=plain
# BUILD SUCCESSFUL; 92 casos passaram; APKs gerados; lint sem erros.

python3 tools/check_16kb.py app/build/outputs/apk/debug/app-debug.apk
/home/zander_mb/Android/Sdk/build-tools/35.0.0/zipalign -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk
# 16 bibliotecas de 64 bits passaram; zipalign retornou zero.
```

Na raiz, usando o Python isolado para as dependências de inferência:

```bash
/tmp/opencode/astra-audit/runtime/bin/python Projeto_Android/tools/audit_model_contract.py \
  --image Dados_IA/dados_treinamento_ia/runs/detect/runs_sensewalk/val_best_agnostic/val_batch0_labels.jpg
/tmp/opencode/astra-audit/runtime/bin/python -m unittest discover \
  -s Dados_IA/dados_treinamento_ia -p test_validar_videos_tflite.py -v
python3 Projeto_Android/tools/summarize_latency.py Projeto_Android/validation/ffnet_s25fe_cpu_repeat.csv
```

Também executada comparação direta do decodificador Python em HEAD versus corrigido, com o mesmo tensor sintético normalizado. Resultado anterior `(0,-278,1,0)`; corrigido `(320,200,960,520)`.

### C.3 Alcance e regressões

- **Executados:** políticas de rua/consentimento, parser, semáforos sintéticos, tracking cinemático, fluxo óptico sintético, estados com tempo controlado, fila de preview, prioridade/cancelamento de TTS simulado, geração/idade de frames, preferências Android simuladas, protocolos X5 e codecs/qualidade USB, contrato XML dos controles nos dois layouts.
- **Compilados, não executados:** `FFNetRuntimeTest`, `UsbRuntimeTest` e APK instrumentado. Atualizada a expectativa do primeiro: YOLO agora deve executar antes do consentimento para alertas; não há mais expectativa de zero chamadas nessa fase.
- **Não executados:** `connectedDebugAndroidTest`, instalação, câmera nativa/UVC/X5 reais, troca/desconexão sob carga, reconhecimento de voz, TalkBack, navegação Login→Main→Settings no aparelho, rotação com vídeo, permissões negadas/concedidas, teste em kernel 16 KB, bateria/térmica/memória sustentadas, latência exposição→som.
- Login permanece como seleção de idioma, não autenticação de conta. PT/EN/ES continuam disponíveis. Autoescuta configurável continua comentada em `MainActivity.onResume`; não foi declarada funcional.
- Lint tem 148 avisos, incluindo textos fixos/i18n, orientação travada, dependências e recomendações de API/estilo. Eles não foram suprimidos para obter o build. Não são 148 falhas novas atribuíveis às alterações.

APK debug: `Projeto_Android/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `e18971ba2fb80f241fbf7fe581ad0646255bb097f8855bb8db7612e7555bdbf6`. O APK instrumentado tem hash em `artifacts.json`. A aprovação de alinhamento ELF/ZIP **não comprova execução em kernel de páginas de 16 KB**.

## D. Benchmark

**Não há comparação antes/depois válida.** Os números abaixo são releitura do CSV histórico `Projeto_Android/validation/ffnet_s25fe_cpu_repeat.csv`, não uma nova coleta Android. Cinco amostras por modo após excluir a iteração zero, mesma imagem estática; diferentes fases não permitem calcular ganho do processamento combinado.

| Métrica | Antes / histórico disponível | Depois desta alteração | Evidência / limite |
|---|---|---|---|
| FPS de captura | Não disponível | Não medido | Callbacks não são FPS físico do sensor |
| FPS de inferência sustentada | Não disponível | Não medido | Não derivado de 1/mediana de imagem estática |
| Latência YOLO | p50 482 ms; p95 485,4 ms | Não medida | `total_ms` histórico do modo YOLO inclui pré/pós; não apenas `Interpreter.run` |
| Latência FFNet | p50 579 ms; p95 592,8 ms | Não medida | Total histórico com pré/pós, não custo isolado do grafo |
| Ambos em série | p50 1.058 ms; p95 1.058,8 ms | Não medida | Mesma imagem, n=5; não operação com câmera |
| Idade dos frames | Não disponível nesse CSV | Não medida | Guarda de 1.500 ms é política, não medição da latência física |
| Latência até decisão | Não disponível | Não medida | Faltam correlação entre captura, janela e decisão em aparelho |
| Latência até início de áudio | Não disponível | Não medida | Logs de `onStart` implementados; não coletados nem equivalentes a som físico |
| Memória | Máximo 407.333 KiB PSS no CSV de repetição | Não medida | Fases compartilham processo; não atribuir diferença a otimização |

Mudanças concretas de processamento existem: buffer de pixels YOLO reutilizado, logs reduzidos, captura CameraX configurável, YOLO priorizado, controle de idade, filas de inferência limitadas e boxes opcionais. Correções Astra: preview externo limitado e custo FFNet separado do YOLO anterior. Permanecem YUV→NV21→JPEG→Bitmap por frame, alocações/rotações/letterbox, cópia de preview externo e inferências serializadas na CPU. Aumentar FPS configurado não acelera os modelos. Não há delegate novo ou medição de melhoria percentual.

## E. Falhas, severidade e correções realizadas pelo Astra

### E.1 Corrigidas nesta execução

| ID | Severidade | Falha comprovada | Arquivos / correção / evidência |
|---|---|---|---|
| A01 | ALTA | `invalidate()` usava `capturedAtMs/risk/scene` fora de escopo e se chamaria recursivamente | K`road/CrossingVerification.kt`: validação transferida para `observe`; compilação e 92 testes passaram |
| A02 | ALTA | Layout horizontal não tinha `btnCrossing`, acessado como não nulo; Main é `sensorLandscape` | `app/src/main/res/layout-land/activity_main.xml`: controle inserido; `MainLayoutContractTest` falhava antes e passou |
| A03 | ALTA | Janela sem orientação/cobertura avançava para próxima direção, anunciando conclusão | K`road/CrossingVerification.kt`, `RoadText.kt`, K`ui/MainActivity.kt`: inconclusivo com explicação persistente; teste de primeira lateral passou |
| C01 | CRÍTICA, latente no controlador | Favorável podia sobreviver a laterais vencidas, perda de cobertura ou perda de evidência de sinal | K`road/CrossingVerification.kt`: validade reavaliada no monitoramento. Três contraprovas falharam antes/passaram depois. Cobertura era injetada; não afirmar ocorrência real no adaptador |
| C02 | CRÍTICA | Pergunta/comando podia cortar alerta SAFETY | K`voice/VoiceSystem.kt`, `VoicePriority.kt`, K`road/CrossingConsentController.kt`: prioridade respeitada, cancelamento só da pergunta, botões preservados após interrupção; testes TTS simulados passaram |
| C03 | CRÍTICA | Favorável já enviado ao TTS continuava mesmo após mudança de estado | `VoiceSystem`: cancela a fala do tópico invalidado; `MainActivity`: invalidação limpa tópico, monitoramento equivalente não corta o anúncio válido; teste passou |
| A04 | ALTA | Mensagem interrompida era marcada como falada e não voltava a ser tentada | `VoiceSystem`: marca somente após `onDone` bem-sucedido, evita duplicar fala em curso, usa relógio monotônico; teste de interrupção/retomada passou |
| A05 | ALTA | Estado legado excluía veículo lateral e podia dizer “Nenhum veículo detectado” após alerta de aproximação | K`logic/StateMachine.kt`: observa lista inteira e razões de risco; `AndroidSafetyIntegrationTest::lateralDangerCannotBeFollowedByNoVehiclesMessage` passou |
| C04 | CRÍTICA, contrato de validade | Último YOLO era datado no término, renovando validade de captura já velha; contexto podia sobreviver a nova sessão | K`road/InferenceCoordinator.kt`, ambos os processadores: timestamp da captura e limpeza de contexto/contadores; teste 1.400+101 ms e token anterior passaram |
| A06 | ALTA | Cada preview externo copiava bitmap e enfileirava runnable, sem limite se a UI atrasasse | K`camera/LatestFrameDispatcher.kt`, K`ui/MainActivity.kt`: entrega mais recente e descarte; teste 1.000→1 e parada/reinício passou |
| A07 | ALTA na validação offline | Validador tratava coordenadas normalizadas como pixels e produzia limites negativos | `Dados_IA/dados_treinamento_ia/validar_videos_tflite.py`: contrato explícito, clipping, default para asset Android; quatro testes Python passaram |
| M01 | MÉDIA | Custo “FFNet” usado para agendamento incluía YOLO anterior | K`road/InferenceCoordinator.kt`: medir segmento separadamente; build/testes passaram, efeito de desempenho não medido |
| M02 | MÉDIA | Expectativa instrumentada contradizia política atual de alertas pré-consentimento | `app/src/androidTest/.../road/FFNetRuntimeTest.kt`: expectativa de chamada atualizada; APK compilado, execução pendente |
| B01 | BAIXA | README dizia 9 classes e não integração apesar do commit/asset de 11 classes | README atualizado com evidência e limite de procedência; documentação anterior marcada como histórica |

Adicionados testes `AstraAuditFindingsTest`, `AndroidSafetyIntegrationTest`, `MainLayoutContractTest`, `VoiceSystemAuditTest`, `OpticalFlowAuditTest`, `LatestFrameDispatcherTest` e `test_validar_videos_tflite.py`. Adicionado `tools/audit_model_contract.py`. `app/build.gradle.kts` recebeu **somente dependência de teste Robolectric 4.14.1**, sem nova dependência de produção. Os demais diffs recebidos não devem ser creditados a esta auditoria.

### E.2 Abertas

| Severidade | Pendência | Efeito / encaminhamento |
|---|---|---|
| ALTA | Orientação e cobertura físicas inexistentes | Impedem completar as verificações obrigatórias. Necessário projeto e teste específico por fonte: IMU do celular não comprova direção de uma webcam/X5 independente |
| ALTA | Compensação só translacional, sem prova de movimento independente | Paralaxe/rotação/escala podem gerar resíduo de movimento; aproximação frontal real também pode permanecer incerta. Exigir sequências com ground truth, sem resolver por thresholds arbitrários |
| ALTA | Trajetória/região de conflito e relevância distante insuficientes | Ausência de estimativa válida de tempo/posição futura; tanto bloqueios excessivos quanto alertas imprecisos permanecem possíveis |
| ALTA | Timestamp físico externo não conhecido | Frame antigo no driver/rede pode parecer recente após callback/decodificação. Necessário instrumentar captura/transporte e limites de buffer/reconexão |
| ALTA | Aplicabilidade de sinal por geometria aproximada e classes sem vínculo nominal ao hash | Validar classes exportadas e associação à travessia com exemplos anotados; não basta reconhecer a cor |
| ALTA | Prazo operacional/qualidade visual não demonstrados | 1,5 s/12 s não são limites calibrados; oclusão, luz e velocidade do tráfego não entram adequadamente na confiança |
| MÉDIA | Texto de mão não integrado; sem fonte de ausência de sinal aplicável | Requisito não implementado; não ativar a orientação pela simples ausência de detecção |
| MÉDIA | Atualizações UI de status/boxes sem revalidar token na entrega | Possível visualização superada após pausa/troca; requer teste de integração com fila UI bloqueada e todas as fontes |
| MÉDIA | Preferências sem efeito funcional completo | `inferenciaTamanho` é sobreposto pelo modelo; `tempoParadoSeguro` não controla constantes de 3 s; `limiarProximoPct` só alimenta `ehProximo`, sem chamador; autoescuta comentada |
| MÉDIA | Acessibilidade e idiomas incompletos em fontes externas | Ocultar painel pode ocultar Parar/direção; mensagens USB/X5 hardcoded em PT; reconhecimento/TalkBack não testados fisicamente |
| BAIXA | Código auxiliar sem chamador e documentação histórica divergente | `ImageUtils.preprocessar`, `OverlayView.setDetections`, `Rastreador.ehProximo` e `verify_hand` não demonstram recursos ativos; descrições antigas foram preservadas e sinalizadas |

## F. Pendências de validação

1. **Conjunto sequencial prioritário:** carro parado com câmera caminhando para frente/lateral, paralaxe em várias profundidades, giro/zoom/tremor, início posterior de movimento do carro, aproximação frontal e lateral reais. Anotar movimento da câmera/veículo, timestamps, IDs e oclusões; medir falsos alertas e alertas perdidos, não só contagem de boxes.
2. **Faixas/ruas:** imagens originais e vídeos anotados com e sem faixa, estacionamento, calçada sem rua, faixas próximas/inclinadas/desgastadas, pista vazia, pouca luz e câmera desalinhada. Vincular classes ao hash TFLite antes de medir precisão.
3. **Sinais:** verde de carros, verde/vermelho pedestre, cruzamentos múltiplos, sinal de outra travessia, perda parcial/total, mudança durante verificações e veículo ocupando a faixa. Cobertura lateral continua necessária.
4. **Orientação:** definir evidência física válida e cobertura mínima por direção para CameraX, UVC e X5. Botão, tempo e IMU de outro dispositivo não bastam. Validar expiração das laterais com tráfego em movimento.
5. **Hardware/regressão:** instalar os APKs novos; testar permissões, Login/idiomas, Main/Settings, persistência, iniciar/parar, rotação, ocultar overlay/painéis, câmera nativa/USB/X5, desconexão/troca sob inferência e retorno do background.
6. **Áudio:** TTS real e offline PT/EN/ES, reconhecimento local, microfone sem serviço offline, comandos durante perigo, cancelamento, fila congestionada, callback de erro/stop, TalkBack e volume. Medir início físico do som correlacionado à captura e à decisão.
7. **Benchmark pareado:** mesmo aparelho/cena/iluminação/resolução/energia, sessões sustentadas, idade real de captura e perdas, memória/GC/temperatura/bateria. Verificar execução em kernel 16 KB; alinhamento do APK já foi verificado.

## G. Conclusão

**REPROVADO.** O projeto agora compila, possui testes adicionais e corrige falhas demonstráveis de estado, voz, validade temporal, layout, fila de preview e validação offline. Isso melhora a base para investigação, mas **não cumpre integralmente o fluxo de travessia solicitado**.

O que foi demonstrado: contratos dos modelos, execução host, regras sintéticas negativas/positivas de movimento, políticas de consentimento/estado, alertas com TTS simulado, preferências, protocolos/codecs e build/alinhamento nativo. O que não foi demonstrado: precisão visual, distinção física robusta de movimento, relevância de veículos na travessia, orientação/cobertura real, condições favoráveis no aplicativo, latência assistiva e funcionamento integrado em aparelho.

Não se deve chamar essa entrega de “dez melhorias concluídas”, nem inferir segurança porque 92 testes passaram. A conclusão favorável continua bloqueada quando falta evidência de cobertura; esse bloqueio é uma proteção necessária e também evidencia que a funcionalidade principal está incompleta.
