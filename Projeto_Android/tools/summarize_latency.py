"""Resumo de amostras reais (não calcula precisão nem latência física fora da câmera).

Uso: python3 tools/summarize_latency.py caminho/frames.csv [caminho/road_*.csv]
Também aceita validation/ffnet_s25fe_cpu_repeat.csv (ignora iteração 0).
"""
import csv
import math
import sys
from collections import defaultdict


def percentile(values, percent):
    ordered = sorted(values)
    position = (len(ordered) - 1) * percent
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


for path in sys.argv[1:]:
    with open(path, newline="", encoding="utf-8") as source:
        rows = list(csv.DictReader(source))
    groups = defaultdict(list)
    for row in rows:
        if "iteration" in row and row["iteration"] == "0":
            continue
        groups[row.get("mode", "session")].append(row)
    print(path)
    for mode, samples in groups.items():
        print(f"  {mode} (n={len(samples)})")
        for column in ("total_ms", "inference_ms", "optical_flow_ms", "frame_age_ms",
                       "ffnet_ms", "yolo_pipeline_ms", "thread_cpu_ms"):
            values = []
            for row in samples:
                try:
                    value = float(row[column])
                    if math.isfinite(value) and value >= 0:
                        values.append(value)
                except (KeyError, ValueError, TypeError):
                    pass
            if values:
                print(f"    {column}: n={len(values)} p50={percentile(values, .5):.2f} "
                      f"p95={percentile(values, .95):.2f} ms")
