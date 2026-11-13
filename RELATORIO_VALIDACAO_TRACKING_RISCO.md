# Relatório de validação — tracking, movimento, orientação e risco (SenseWalk V2.4)

**12/11/2026.** Protótipo para testes controlados e apresentação acadêmica. **Nada aqui autoriza travessias
reais.** Métricas sem ground truth independente são *indicativas* e estão marcadas como tal.

## 1. Conjunto de dados

| Vídeo | Origem | Resolução | Frames decodificados | Amostras no replay |
|---|---|---|---:|---:|
| `teste_01_faixa_veiculo` | `videos/WhatsApp Video 2026-10-15.mp4` | 854×480 | 928 | 310 (stride 3, ~10 Hz) |
| `teste_02_faixa_semaforo` | `videos/WhatsApp Video 2026-10-15 at 21.05.41.mp4` | 854×480 | 948 | 316 |

Replay: `OfflineVideoReplayTest` executa as classes Kotlin de produção (tracker, `Rastreador`, risco,
sequência guiada) sobre as detecções/máscaras reais já exportadas em `videos_teste/resultados/`.
O replay foi rodado numa **cópia** dos resultados para não sobrescrever as evidências originais.

## 2. Anotações disponíveis

- `anotacoes_revisao_astra.json`: visibilidade de faixa em 31 amostras, **feita pelo assistente**, não por humano independente.
- **Não existem** anotações de identidade de veículos, caixas de referência, movimento real, semáforo
  aplicável nem orientação da câmera. Portanto **não foram calculados** MOTA/IDF1, trocas de ID reais,
  precisão/recall de veículos, alertas corretos/perdidos nem tempo até alerta.

## 3. Metodologia

1. Cenários sintéticos determinísticos (JUnit) para cada comportamento pedido: 11 de tracking, 18 de
   movimento/compensação, 12 de orientação, 7 de semáforo, 7 de acessibilidade, além da sequência guiada.
2. Replay com vídeos reais, **antes** (tracker/regras anteriores) × **depois** (versão atual), com as
   métricas indicativas de `Projeto_Android/tools/tracking_proxy_metrics.py` (com teste próprio).
3. Medição no aparelho (Samsung SM-S731B) pelo logcat `SenseWalkPerf`, em execução passiva (sem toque).

Resultados brutos: `Projeto_Android/validation/tracking_2026-11-12/metricas_proxy_antes_depois.json`.

## 4. Métricas indicativas de tracking (replay real)

| Métrica (proxy, sem GT) | V1 antes | V1 depois | V2 antes | V2 depois |
|---|---:|---:|---:|---:|
| Observações reais (linhas não-ghost) | 47 | 47 | 217 | 217 |
| IDs únicos | 2 | 2 | 22 | 22 |
| Observações por ID (mediana) | 23,5 | 23,5 | 5 | 5 |
| Fragmentos curtos (≤ 2 obs.) | 0 | 0 | 8 | 7 |
| Recuperações após perda | 9 | 9 | 50 | 51 |

**Interpretação.** Nos dois vídeos a quantidade de IDs **não mudou**. A análise das lacunas do vídeo 2
mostrou que **todo ID novo surge após ≥ 2,1 s sem detecção do YOLO**, acima do limite de persistência sem
observação (1,5 s). A fragmentação nesses vídeos vem de **perdas de detecção**, não de erro de associação.
Aumentar a persistência para "colar" esses IDs arriscaria fundir veículos diferentes; não foi feito.
**Não se declara melhora quantitativa de tracking com estes dados.**

## 5. Movimento e risco (replay real)

| Métrica (proxy) | V1 antes | V1 depois | V2 antes | V2 depois |
|---|---:|---:|---:|---:|
| Observações `PARADO` | 17 | 14 | 24 | 13 |
| `MOVIMENTO_INCERTO` | 24 | 28 | 191 | 185 |
| `POTENCIAL_CONFLITO` | 4 | 4 | 0 | 8 |
| `POSSIVEL_APROXIMACAO` (nova) | — | 0 | — | 7 |
| Frames com perigo | 16 | 20 | 77 | 86 |
| Frames favoráveis | 0 | 0 | 0 | 0 |

- Menos `PARADO`: agora exige ≥ 1 s de histórico e câmera estimada (antes uma caixa parada bastava).
- Mais alertas no V2: expansões com câmera parada viraram `POSSIVEL_APROXIMACAO` (alerta). **Sem GT não é
  possível dizer se são alertas corretos ou falsos.** No V2 isso faz a sequência ficar retornando à
  direita entre perigos (transições `VERIFY_RIGHT → DANGER → VERIFY_RIGHT …`), comportamento esperado
  diante de perigos repetidos.
- Nenhum frame favorável em nenhum vídeo (sem cobertura comprovada nem sinal aplicável).

## 6. Desempenho medido no aparelho (antes das últimas otimizações de concorrência)

Janela de 5 s na espera por rua, CameraX, pipeline RGBA:

| Etapa | p50 | p95 |
|---|---:|---:|
| Conversão ImageProxy→Bitmap | 0,2 ms | 3,7 ms |
| Pré-processamento YOLO | 3,0 ms | 8,0 ms |
| **Inferência YOLO** (XNNPACK, 308/312 nós) | **299 ms** | **438 ms** |
| Pós-processamento YOLO | 0,4 ms | 12,7 ms |
| Fluxo óptico | 2,0 ms | 7,0 ms |
| **Tracker** | **0,0 ms** | **0,2 ms** |
| **FFNet** | **340 ms** | **542 ms** |
| Frame inteiro (YOLO + FFNet em série) | 643 ms | 1028 ms |
| Idade do frame ao terminar | 769 ms | 1141 ms |
| Interface (Choreographer) | ~60–78 quadros/s | jank ≤ 0,5/s |

**Gargalo:** a inferência dos modelos, não a conversão nem o tracking. Na espera, FFNet e YOLO rodavam em
série (~1 YOLO/s). **Correção aplicada:** FFNet numa thread própria, com cópia do frame e no máximo uma
inferência em andamento. **O ganho ainda não foi medido** (o adb caiu logo após a instalação). Durante a
análise a FFNet já fica suspensa; o YOLO fica limitado pelo próprio custo (~3 inferências/s). O custo das
novas regras (tracker, classificação, orientação) é desprezível frente às inferências.

## 7. Casos inconclusivos e falhas identificadas

- **Webcam USB:** sem sensor associado à câmera → direções sempre inconclusivas (por projeto).
  Fluxo óptico não fornece ângulo absoluto sem calibração; não foi fabricado.
- **Câmera andando ou girando:** movimento dos veículos fica `MOVIMENTO_INCERTO` (sem compensação 3D).
- **Paralaxe** (camadas do fundo com translações diferentes) rejeita a compensação.
- **Semáforo:** associação geométrica + cor estável são indícios; `signalAssociationVerified` continua
  `false` (sem fonte independente). Conclusão favorável com sinal continua impossível em produção.
- **Faixa:** detecções raras nos vídeos reais (0/310 e 17/316 amostras), já relatadas.
- **Perdas do YOLO** > 1,5 s são a principal causa de fragmentação de IDs nos vídeos.

## 8. Recomendações para testes físicos (local protegido, sem atravessar)

1. Anotar IDs/caixas em 2–3 minutos de vídeo (CVAT/Label Studio) para calcular MOTA/IDF1 e trocas de ID.
2. Gravar cenas controladas: carro parado com câmera parada/andando/girando; carro aproximando/afastando.
3. Orientação: apontar frente → girar 90° à direita → 90° à esquerda → frente; observar os logs
   `SenseWalkOrientation` (`hfov`, `status`, `yaw`) e se as etapas avançam sem "pronto".
4. Medir `SenseWalkPerf` por 2 min na espera e na análise (FFNet paralela), e comparar com a seção 6.
5. Repetir com TalkBack ligado (checklist na seção 9).

## 9. Checklist TalkBack (validação física pendente)

- [ ] Ao abrir, o TalkBack lê título → estado → **Iniciar/Parar** antes dos demais controles.
- [ ] Todos os botões têm rótulo falado claro (Iniciar/Parar análise, Voz, Fonte da câmera, Configurações).
- [ ] Com fonte ampliada (Configurações → Tamanho da fonte máximo), nenhum texto de botão é cortado.
- [ ] Em paisagem, todos os controles ficam alcançáveis e na mesma ordem.
- [ ] Ocultar painéis nas Configurações não esconde Parar/Voz do TalkBack.
- [ ] Alertas de perigo continuam falados pelo TTS com TalkBack ativo.
- [ ] Comando de voz "parar" funciona entre falas; o botão Parar funciona sempre.
- [ ] Sem reconhecimento de voz disponível, a mensagem é falada uma vez e o botão continua funcionando.
- [ ] Permissões de câmera e microfone podem ser concedidas com TalkBack.

## 10. Sequência direcional mais rápida (ajuste posterior, 12/11/2026)

**Resultados SIMULADOS** (`SequenceTimingSimulationTest`, frames a 300 ms como no aparelho, usuário leva
1,5 s para girar, semente fixa). Arquivos: `Projeto_Android/validation/sequencia_2026-11-12/`.

| Cenário simulado | Antes total | Depois total | Antes por direção | Depois por direção | Verificações ≥ 3 s antes → depois |
|---|---:|---:|---|---|---|
| CameraX, frames limpos | 12,0 s | 10,2 s | 3,9 / 3,9 / 3,9 s | 3,3 / 3,3 / 3,3 s | 0 → 0 |
| CameraX, 30% frames com incerteza | 21,9 s | 16,2 s | 9,3 / 5,7 / 6,6 s | 7,8 / 4,8 / 3,3 s | 3 → 2 |
| USB sem sensor, "pronto" após 3 s | 14,7 s | 14,7 s | 4,8 s cada | 4,8 s cada | 0 → 0 |

- A evidência para "sem conflito observado" **não foi reduzida** (≥ 3 frames limpos e cobertos ao longo
  de 1,5 s). Os ganhos vêm de: o 1º frame coberto já conta; o tempo limite sem evidência caiu de 7 s
  para 3 s, seguido de uma única nova tentativa; a frente começa no 1º frame coberto (sem a espera fixa de 5 s).
- Replay dos vídeos reais (sem sensor no harness): transições idênticas antes/depois; nesses vídeos o
  tempo é dominado por períodos de perigo, não pelas janelas de observação.
- Falas da sequência (caracteres, pt-BR; estimativa, sem medir o TTS): direita 61 → 31, esquerda 68 → 16,
  frente 36 → 16, "verificando" 56 → 12, rua identificada 118 → 43.
- **No aparelho ainda não medido.** Métricas preparadas: `SenseWalkSeq` (duração por etapa, frames usados,
  resultado por direção) e `SenseWalkPerf` (`seq_*_ms`, `seq_retries`, `tts_speech_ms`,
  `decision_to_tts_ms`, `alert_decision_to_tts_ms`).
