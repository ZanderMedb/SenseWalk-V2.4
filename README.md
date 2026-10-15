# SenseWalk 2.4

- `Projeto_Android/`: projeto do Android Studio. Abra esta pasta no Android Studio.
- `Dados_IA/best.pt`: novo modelo PyTorch, copiado do treinamento `yolov8m_baseline_640-3`.
- `Dados_IA/backup/model.tflite`: copia do modelo original usado pelo aplicativo.
- `Dados_IA/dados_treinamento_ia/runs/`: resultados de validacao.
- `Dados_IA/dados_treinamento_ia/runs_sensewalk/`: configuracoes, metricas, imagens e pesos dos treinamentos.

O aplicativo carrega `Projeto_Android/app/src/main/assets/model.tflite`.
O arquivo `best.pt` precisa ser convertido para TensorFlow Lite antes de substituir o modelo do aplicativo.
Os arquivos dos treinamentos foram preservados, incluindo seus caminhos historicos em `args.yaml`.
Os datasets de imagens referenciados nesses arquivos nao fazem parte das duas pastas de resultados.

Os pesos `.pt` usam Git LFS. Para baixar os modelos apos clonar, instale Git LFS e execute `git lfs pull`.
