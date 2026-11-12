import unittest
import numpy as np
from offline_video_validation import annotation_metrics, ffnet_input, ffnet_labels, road_metrics, decode_detections, letterbox


class OfflineValidationTest(unittest.TestCase):
    def test_unknown_and_unreviewed_frames_are_not_false_negatives(self):
        rows = [dict(timestamp_s=i,crosswalk_confidence=.8 if i==2 else 0) for i in range(4)]
        annotations = [dict(video="v",class_name="crosswalk",start_s=1,end_s=1,presence="present"),
                       dict(video="v",class_name="crosswalk",start_s=2,end_s=2,presence="absent")]
        result = annotation_metrics(rows,annotations,"v")
        self.assertEqual(2,result["annotated_samples"])
        self.assertEqual(1,result["fn_presence"])
        self.assertEqual(1,result["fp_presence"])

    def test_conflicting_annotations_fail(self):
        rows = [dict(timestamp_s=1,crosswalk_confidence=.8)]
        annotations = [dict(video="v",class_name="crosswalk",start_s=0,end_s=2,presence=s) for s in ["present","absent"]]
        with self.assertRaises(ValueError): annotation_metrics(rows,annotations,"v")

    def test_android_fractional_geometry_and_inclusive_threshold(self):
        padded,ratio,px,py = letterbox(np.zeros((480,854,3),np.uint8),640,android=True)
        self.assertEqual(140.5,py)
        self.assertTrue(np.all(padded[140] == 114))
        self.assertTrue(np.all(padded[141] == 0))
        raw = np.zeros((1,15,8400),np.float32)
        raw[0,:4,0] = [.5,.5,.5,.25]; raw[0,12,0] = .20
        # Duplicate suppressed, different class retained at identical location.
        raw[0,:,1] = raw[0,:,0]; raw[0,:4,2] = raw[0,:4,0]; raw[0,4,2] = .8
        dets = decode_detections(raw,854,480,ratio,px,py,.20,.45,android=True)
        self.assertEqual({0,8},{d[0] for d in dets}); self.assertEqual(2,len(dets))

    def test_ffnet_interpolates_scores_before_argmax(self):
        scores = np.zeros((1,128,256,19),np.uint8)
        scores[0,:,:,0] = 5
        scores[0,:,127,1] = 100
        labels = ffnet_labels(scores,(0,0,1024,512))
        self.assertTrue(np.all(labels[:,127] == 1)); self.assertTrue(np.all(labels[:,128] == 0))

    def test_road_only_is_not_boundary(self):
        self.assertEqual((1.,0.,0.,False),road_metrics(np.zeros((128,256),np.uint8)))

    def test_quantization_rgb_order_and_padding(self):
        image,geometry = ffnet_input(np.full((480,854,3),(0,128,255),np.uint8),.003920366987586021,0)
        left,top,w,h = geometry
        self.assertTrue(np.all(image[0,top+h//2,left+w//2] == [255,128,0]))
        self.assertTrue(np.all(image[0,:,0] == 0))


if __name__ == "__main__": unittest.main()
