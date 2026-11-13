import unittest

from tracking_proxy_metrics import risk_metrics, tracking_metrics


def row(frame, tid, ghost=False, cls="PARADO", video="v"):
    return {"video": video, "frame": str(frame), "id": str(tid), "ghost": "true" if ghost else "false",
            "classification": cls}


class TrackingProxyMetricsTest(unittest.TestCase):
    def test_state_transitions_counted_per_id_for_raw_and_stable_columns(self):
        rows = []
        for i, (raw, st) in enumerate([("PARADO", "X"), ("MOVIMENTO_INCERTO", "X"), ("PARADO", "X"), ("PARADO", "Y")]):
            r = row(i, 1, cls=raw); r["stable_classification"] = st; rows.append(r)
        m = tracking_metrics(rows)["v"]
        self.assertEqual(m["trocas_estado_bruto"], 2)
        self.assertEqual(m["trocas_estado_estabilizado"], 1)

    def test_recovery_fragments_and_ghosts_are_not_counted_as_observations(self):
        rows = [row(1, 0), row(2, 0, ghost=True), row(3, 0), row(4, 0), row(5, 7)]
        m = tracking_metrics(rows)["v"]
        self.assertEqual(m["ids_unicos"], 2)
        self.assertEqual(m["linhas_observadas"], 4)
        self.assertEqual(m["linhas_ghost"], 1)
        self.assertEqual(m["recuperacoes_apos_perda"], 1)
        self.assertEqual(m["fragmentos_curtos"], 1)

    def test_ghost_only_id_is_not_a_unique_object(self):
        m = tracking_metrics([row(1, 3, ghost=True)])["v"]
        self.assertEqual(m["ids_unicos"], 0)

    def test_risk_transitions_and_favorable_frames(self):
        rows = [{"video": "v", "stage": s, "danger": d, "reasons": r} for s, d, r in [
            ("AWAIT_RIGHT", "false", ""), ("AWAIT_RIGHT", "false", "MOTION_UNCERTAIN"),
            ("DANGER", "true", "POTENTIAL_TRAFFIC_CONFLICT|MOTION_UNCERTAIN"), ("INCONCLUSIVE", "false", "")]]
        m = risk_metrics(rows)["v"]
        self.assertEqual(m["transicoes"], ["AWAIT_RIGHT", "DANGER", "INCONCLUSIVE"])
        self.assertEqual(m["frames_perigo"], 1)
        self.assertEqual(m["motivos"]["MOTION_UNCERTAIN"], 2)
        self.assertEqual(m["favoravel"], 0)


if __name__ == "__main__":
    unittest.main()
