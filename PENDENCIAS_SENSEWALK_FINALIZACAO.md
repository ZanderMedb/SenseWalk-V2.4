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

## Bloqueadores e trabalho restante

| Prioridade | Pendência | Evidência / próximo passo |
|---|---|---|
| Alta | Falhas de faixa nos vídeos reais | 0/310 e 17/316 amostras com detecção; revisar dados/exportação e exemplos negativos. Não reduzir limiar arbitrariamente nem retreinar nesta tarefa. |
| Alta | Vínculo nominal de classes ao hash YOLO | Shape/saídas confirmados; asset não possui nomes de classe. Recuperar manifesto de exportação verificável e confirmar classes com conjunto anotado. |
| Alta | Veículos não detectados e fragmentação de IDs | Replay/CSVs expõem perdas. Anotar boxes, IDs persistentes, oclusões e movimento próprio; medir MOT/alertas com ground truth independente. |
| Alta | Orientação/cobertura física direita/esquerda/frente | Estimativa 2D não mede yaw/FOV nem direção do corpo. Faltam calibração e evidência por fonte; webcam independente não herda IMU do celular. Permanece inconclusivo. |
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
