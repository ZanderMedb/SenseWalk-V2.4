# Pendências SenseWalk V2.4 — 12/11/2026

Estado: **build e validação computacional disponíveis; detecção visual e fluxo assistivo ainda não aprovados**.
Detalhes/evidências: [RELATORIO_TESTES_VIDEOS_SENSEWALK.md](RELATORIO_TESTES_VIDEOS_SENSEWALK.md).

## Implementado/verificado nesta finalização

- Pipeline reproduzível com os dois MP4 reais, assets Android intocados, YOLO + FFNet,
  CSV, PNG, máscaras, gráficos, vídeos anotados e replay das classes Kotlin reais.
- Contraprova Skia/OpenCV: corrigido padding vertical do validador; paridade geométrica,
  decode/NMS e argmax/ROI verificada com entradas/saídas compartilhadas.
- Expiração FFNet/laterais considera a idade da inferência atual. Teste que já estava
  na árvore falhou inicialmente e passou após a correção.
- Faixa não amadurece por timer durante ausência de novas detecções.
- Rotação/escala visual 2D e resíduo do fundo estimados; compensação apenas translacional
  recusada quando incompatível. Evidência integrada a `CrossingVerification`.
- Movimento para o centro da imagem produz **possível conflito**, não afirmação de
  aproximação física. Trajetória varrida de imagem pode acrescentar alerta sobre faixa;
  retração da box não é interpretada automaticamente como veículo parado/seguro.
- Verde geométrico não conclui favorável sem associação independente. Alertas mantidos.
- Início manual chama a sequência guiada e habilita contexto FFNet independente da
  preferência de monitoramento automático.
- Mensagem de mão conectada ao resultado com ausência de sinal **comprovada** e
  verificações completas. Essa ausência ainda não tem produtor real no app.
- Controles essenciais permanecem visíveis ao ocultar informações do painel. Descrição
  acessível Parar corrigida no automático. Autoescuta volta a respeitar a preferência.
- Comandos USB e confirmação declarada da câmera; Stop prioritário, negações e limites
  de palavras no parser. Confirmação por voz, assim como botão, não prova cobertura.
- Configurações sem efeito de tamanho do tensor/proximidade retiradas da UI; tempo
  de confirmação de ausência de movimento ligado à preferência e renomeado.
- Textos USB e principais textos X5/configurações traduzidos PT/EN/ES.
- Quantização FFNet por LUT preserva todos os 256 valores de canal; TTS anterior preservado.

## Ajuste MVP 12/11/2026 — FFNet → YOLO e orientação por voz

- FFNet virou apenas gatilho: ≥3 amostras de pista frescas e consecutivas cobrindo o tempo de
  confirmação iniciam UMA análise; calçada não é exigida; sem pergunta sim/não. Durante a
  análise a FFNet não infere (manual ou automática) e não participa da decisão de travessia.
- Sequência direita → esquerda → frente avança sozinha (movimento visual + estabilização, ou
  tempo limite registrando direção NÃO verificada). Botão é opcional. Lembrete único por etapa.
  Perigo interrompe e retoma a MESMA etapa; resultado é terminal (sem reinício automático).
- Comandos: iniciar análise, pronto, continuar, repetir, parar, cancelar, analisar novamente.
  Janelas de escuta automáticas durante a análise, fechadas enquanto o TTS fala; erros reais
  com recuo e desativação após 5 falhas (limitação anunciada uma vez).
- "Parar" mantém tudo parado até novo início (estado de execução, não altera a preferência).
- Pendente no aparelho: limiar de movimento visual (`motionThreshold`), bipe do reconhecedor a
  cada janela, eco do TTS, comando "Parar" falado durante uma fala do app (microfone fechado —
  usar o botão), comportamento com USB/X5 sem fluxo óptico confiável.
- Falha pré-existente mantida: `VoiceCommandValidationTest.stopRemainsAvailableInConsentDialog…`
  (Robolectric `Resources$NotFoundException` com `manifest=NONE`; já falhava antes do ajuste).

## Ajuste pós-teste no celular 12/11/2026

- Direita/esquerda: "Aponte a câmera para a direita. Quando terminar, diga pronto." Microfone abre após
  o TTS; "pronto"/"continuar" (também por resultado parcial) inicia a observação e a etapa avança sozinha.
  Segundo "pronto" durante a observação é ignorado. Frente começa sozinha (~5 s). Movimento visual é
  só evidência registrada. Falha do reconhecedor: recria o SpeechRecognizer, anuncia uma vez, tenta a cada 15 s.
- Falas rotineiras de faixa silenciadas (`StateMachine.CROSSWALK_ROUTINE_SPEECH = false`); detecção, boxes,
  logs e estados preservados. Alerta "veículo no caminho da travessia" mantido sem citar faixa.
- Fluidez: CameraX RGBA já rotacionado (sem YUV→JPEG→Bitmap), Bitmap/canvas/tensor reutilizados, frame
  descartado antes da conversão quando nenhum modelo está devido (frequência do YOLO inalterada).
  Métricas p50/p95 no logcat `SenseWalkPerf`; A/B com `--ez perf_fast_pipeline false`.
- Pendente no aparelho: medir antes/depois (sem números ainda), confirmar detecção com entrada RGBA
  (sem artefatos JPEG; não é bit-idêntica ao caminho antigo), reconhecimento de "pronto" no Samsung.
- Teste antes falho `stopRemainsAvailableInConsentDialog…` corrigido com `isIncludeAndroidResources`.

## Melhorias de tracking, movimento, orientação e acessibilidade — 12/11/2026

Detalhes e métricas: [RELATORIO_VALIDACAO_TRACKING_RISCO.md](RELATORIO_VALIDACAO_TRACKING_RISCO.md).

- **Tracking:** previsão cinemática pelo intervalo real entre frames, observado ≠ previsto (ghost), janela que
  cresce com velocidade/tempo, coerência de direção, supressão de duplicatas, limite de tracks. 11 cenários
  sintéticos passam. Vídeos reais: IDs inalterados (fragmentação causada por perdas do YOLO > 1,5 s).
- **Movimento:** classes qualificadas (`POSSIVEL_APROXIMACAO` alerta; `POSSIVEL_AFASTAMENTO` incerto, nunca
  seguro); escala do fundo corrige a largura; câmera andando/girando/paralaxe → incerto; `PARADO` exige ≥ 1 s.
- **Compensação:** modelos translação/similaridade/paralaxe/inconsistente no ajuste do fundo; rejeição explícita.
- **Orientação (CameraX):** `OrientationCoverageTracker` + GAME_ROTATION_VECTOR; janela lateral derivada do FOV
  real (medido: 74° em paisagem → [58°,122°]); cobertura comprovada avança a etapa sem "pronto"; giro errado
  gera uma dica. USB: sempre inconclusivo.
- **Semáforo:** rastreamento com cor estável exigida; sinais distantes/laterais excluídos; associação continua
  desconhecida (sem fonte independente).
- **X5 removida** (cliente RTSP, protocolo, configurações, textos e permissões de rede/Wi-Fi).
- **Acessibilidade:** ordem TalkBack com Parar no início, alvos ≥ 48dp sem altura fixa, vibração ao tocar.
- **Desempenho:** gargalo = inferência (YOLO ~300 ms, FFNet ~340 ms); FFNet movida para thread própria
  (ganho ainda não medido no aparelho).
- Testes: 193 unitários (1 opcional pulado), 9 Python; APK, APK de testes e lint (0 erros).
- **Pendente físico:** orientação real, TalkBack, USB, tracking/semáforos reais, FPS prolongado, latência de alerta.

## Sequência direcional rápida — 12/11/2026

- Resultado por direção: `NO_CONFLICT_OBSERVED` / `POTENTIAL_DANGER` / `INCONCLUSIVE` / `NOT_OBSERVED`
  (resultado limitado da observação, nunca "seguro"). Perigo novo invalida conclusões anteriores.
- 1º frame coberto já observa; sem evidência em 3 s → "Não consegui confirmar. Aponte novamente." (uma vez)
  e avança como inconclusiva; frente sem espera fixa quando há sensor. Falas curtas ("Agora, esquerda.").
- Simulado: 12,0 → 10,2 s (limpo) e 21,9 → 16,2 s (30% incerteza). **Pendente medir no aparelho.**

## "Aponte e mantenha" — 12/11/2026

- Sem "pronto": a observação começa 1,5 s após o TTS terminar a instrução (limite de 4 s se o TTS falhar), ou antes
  quando o sensor comprova a direção. Falas: "Aponte a câmera para a direita e mantenha." / "Agora, esquerda.
  Mantenha." / "Agora, à frente. Mantenha.".
- Giro lateral mínimo derivado do FOV (quadro deve alcançar ~75°): ~43° em paisagem. Calibração da frente nunca
  bloqueia (mediana após 1,5 s).
- Resumo factual quando as três direções terminam sem conflito: "Nenhum veículo em aproximação detectado nas três
  direções. A câmera pode não ver tudo; a decisão é sua." (não autoriza; perigo na sessão impede).
- Simulado com fala de 1,8 s incluída: ~15,6 s do início ao resultado (limpo). **Pendente medir no aparelho.**

## Memória temporária da sessão — 12/11/2026

- `CrossingSessionMemory` (só RAM, limitada a 64 registros, expira em 30 s): ID, classe, caixa, direção,
  estado, posições, confiança, última observação real, conflito, sessão. Limpa em Parar/troca de câmera/nova sessão.
- Estado estabilizado: `APARENTEMENTE_PARADO` após 3 observações reais distintas; histerese de 3 para mudar;
  conflito/`EM_MOVIMENTO` imediatos; câmera não compensada invalida "aparentemente parado" na hora.
- Histórico por direção preservado com validade decrescente; revisita da mesma direção vincula só com geometria
  inequívoca e exige reconfirmação. Memória nunca alimenta conclusão favorável.
- Vídeos reais: trocas de estado por veículo 17 → 13 (V2), 3 → 3 (V1); frames de perigo 86 → 91 (conflito
  mantido até 3 observações contrárias). Custo no host < 1 ms/frame; `memory_ms` a medir no aparelho.

## Bloqueadores e trabalho restante

| Prioridade | Pendência | Evidência / próximo passo |
|---|---|---|
| Alta | Falhas de faixa nos vídeos reais | 0/310 e 17/316 amostras com detecção; revisar dados/exportação e exemplos negativos. Não reduzir limiar arbitrariamente nem retreinar nesta tarefa. |
| Alta | Vínculo nominal de classes ao hash YOLO | Shape/saídas confirmados; asset não possui nomes de classe. Recuperar manifesto de exportação verificável e confirmar classes com conjunto anotado. |
| Alta | Veículos não detectados e fragmentação de IDs | Replay/CSVs expõem perdas. Anotar boxes, IDs persistentes, oclusões e movimento próprio; medir MOT/alertas com ground truth independente. |
| Alta | Orientação/cobertura física direita/esquerda/frente | CameraX: sensor de rotação + FOV implementados e testados sinteticamente; falta validar no aparelho. Webcam USB não herda IMU do celular: permanece inconclusivo. |
| Alta | Distância, estacionamento, aproximação/afastamento físicos | Trajetória/escalas são da imagem. Sem profundidade/ego-movimento validado, não há metros, TTC físico ou prova de estacionamento. |
| Alta | Paralaxe, giros grandes e profundidades múltiplas | Agora há rejeição de alguns modelos incompatíveis; não há compensação homográfica/3D validada. Expandir sequências controladas anotadas. |
| Alta | Aplicabilidade e tipo dos sinais | Cor/tipo alternam no detector. Associação espacial não comprova a travessia; ausência de sinal detectado não comprova inexistência. |
| Alta | Idade real de USB/X5 | Timestamp continua de chegada/decodificação. Medir exposição, transporte, buffers e som no aparelho; não estimar por tempos Linux. |
| Alta | Segmentação como geometria de travessia | FFNet encontra pista, mas máscaras não provam meio-fio/pés/legalidade; calçada/contato oscilam. Anotar máscaras e avaliar câmera em movimento. |
| Média | Mão sem produtor real de sinal ausente | Encadeamento e bloqueios testados sinteticamente; não ativar por `NOT_OBSERVED`. |
| Média | Fluxo manual/automático no aparelho | Compilado e políticas testadas; integração de câmera, permissões e reconhecimento/TTS real permanece aberta. |
| Média | Acessibilidade e UI completa | TalkBack, fonte ampliada, telas pequenas/landscape, mensagens técnicas X5 e reconexão não validados fisicamente. |
| Média | Desempenho móvel | YUV→JPEG e alocações de imagem continuam; sem benchmark Android pareado, térmica ou memória sustentada. |
| Média | Avaliação de risco/MOT | Formato de anotação preparado; avaliador quantitativo implementado para presença de faixa. Métricas de IDs/risco exigem anotações e avaliador específico. |
| Baixa | Lint | 145 avisos, zero erros; não foram suprimidos para aprovar o build. |

## Artefatos e limites

- APK: `Projeto_Android/app/build/outputs/apk/debug/app-debug.apk`.
- APK de teste: `Projeto_Android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- Evidências locais: `videos_teste/resultados/`; cópias pequenas em
  `Projeto_Android/validation/videos_2026-11-12/`.
- Não executados testes instrumentados, instalação, USB/X5 física, TTS real,
  TalkBack ou execução em kernel 16 KB. Alinhamento ELF/ZIP verificado.
- Nenhum push, merge, retreinamento ou alteração de pesos.
