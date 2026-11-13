#!/usr/bin/env python3
"""Métricas INDICATIVAS de tracking/risco a partir dos CSVs do replay Kotlin (OfflineVideoReplayTest).

Sem ground truth anotado de identidades, estas métricas NÃO são MOTA/IDF1 nem recall de veículos:
- ids_unicos: IDs distintos com pelo menos uma observação real (não ghost);
- obs_por_id: observações reais por ID (média/mediana); trajetórias mais longas sugerem menos fragmentação,
  mas também podem esconder trocas de identidade;
- fragmentos_curtos: IDs com <= 2 observações (candidatos a fragmentação ou falso positivo);
- recuperacoes: IDs que voltam a ser observados depois de linhas ghost (perda temporária recuperada);
- classificacoes: distribuição das classes de movimento nas observações reais;
- estados_risco: contagem de frames por etapa e frames com perigo (cena_kotlin.csv).
Contagem de boxes não é quantidade de objetos únicos.

Uso: python3 tracking_proxy_metrics.py <dir_antes> [<dir_depois>] [--json saida.json]
"""
import csv
import json
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path


def tracking_metrics(rows):
    per_video = defaultdict(list)
    for r in rows:
        per_video[r["video"]].append(r)
    out = {}
    for video, rs in sorted(per_video.items()):
        rs.sort(key=lambda r: (int(r["frame"]), int(r["id"])))
        obs = defaultdict(int)
        seq = defaultdict(list)
        classes = Counter()
        for r in rs:
            ghost = r["ghost"].strip().lower() == "true"
            seq[r["id"]].append(ghost)
            if not ghost:
                obs[r["id"]] += 1
                classes[r["classification"]] += 1
        counts = list(obs.values())
        recoveries = 0
        for flags in seq.values():
            for i in range(1, len(flags)):
                if flags[i - 1] and not flags[i]:
                    recoveries += 1
        def transitions(column):
            per_id = defaultdict(list)
            for r in rs:
                if r["ghost"].strip().lower() != "true" and r.get(column):
                    per_id[r["id"]].append(r[column])
            return sum(sum(1 for a, b in zip(v, v[1:]) if a != b) for v in per_id.values())
        out[video] = {
            # Mudanças de estado por veículo (observações reais consecutivas do mesmo ID).
            "trocas_estado_bruto": transitions("classification"),
            "trocas_estado_estabilizado": transitions("stable_classification") if rs and "stable_classification" in rs[0] else None,
            "linhas_observadas": sum(counts),
            "linhas_ghost": sum(1 for r in rs if r["ghost"].strip().lower() == "true"),
            "ids_unicos": len(counts),
            "obs_por_id_media": round(statistics.mean(counts), 2) if counts else 0,
            "obs_por_id_mediana": statistics.median(counts) if counts else 0,
            "fragmentos_curtos": sum(1 for c in counts if c <= 2),
            "recuperacoes_apos_perda": recoveries,
            "classificacoes": dict(classes.most_common()),
        }
    return out


def risk_metrics(rows):
    per_video = defaultdict(list)
    for r in rows:
        per_video[r["video"]].append(r)
    out = {}
    for video, rs in sorted(per_video.items()):
        stages = [r["stage"] for r in rs]
        transitions = [stages[0]] + [b for a, b in zip(stages, stages[1:]) if a != b] if stages else []
        out[video] = {
            "frames": len(rs),
            "frames_perigo": sum(1 for r in rs if r["danger"].strip().lower() == "true"),
            "motivos": dict(Counter(m for r in rs for m in r["reasons"].split("|") if m).most_common()),
            "etapas": dict(Counter(stages).most_common()),
            "transicoes": transitions,
            "favoravel": stages.count("FAVORABLE_OBSERVATION") + stages.count("MONITORING"),
        }
    return out


def load(folder):
    folder = Path(folder)
    with open(folder / "tracking.csv", newline="") as f:
        tracks = list(csv.DictReader(f))
    with open(folder / "cena_kotlin.csv", newline="") as f:
        scene = list(csv.DictReader(f))
    return {"tracking": tracking_metrics(tracks), "risco": risk_metrics(scene)}


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    json_out = argv[argv.index("--json") + 1] if "--json" in argv else None
    if json_out:
        args.remove(json_out)
    if not args:
        print(__doc__)
        return 2
    result = {"antes": load(args[0])}
    if len(args) > 1:
        result["depois"] = load(args[1])
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if json_out:
        Path(json_out).write_text(text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
