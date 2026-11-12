import unittest
import numpy as np
from validar_videos_tflite import decode_detections, DEFAULT_MODEL


class VideoDecoderAuditTest(unittest.TestCase):
    def prediction(self, bbox):
        output = np.zeros((1, 15, 20), dtype=np.float32)
        output[0, :4, 0] = bbox
        output[0, 12, 0] = .9  # class 8, crosswalk
        return output

    def test_normalized_crosswalk_is_unletterboxed_to_full_frame(self):
        result = decode_detections(self.prediction([.5,.5,.5,.25]),1280,720,.5,0,140,.2,.45)
        self.assertEqual(1,len(result))
        self.assertEqual((8,320,200,960,520),(result[0][0],*result[0][2:]))

    def test_padding_only_box_is_not_sorted_back_into_image(self):
        self.assertEqual([],decode_detections(self.prediction([.5,.05,.5,.02]),1280,720,.5,0,140,.2,.45))

    def test_pixel_contract_can_be_explicit(self):
        result = decode_detections(self.prediction([320,320,320,160]),1280,720,.5,0,140,.2,.45,coordinates="pixels")
        self.assertEqual((320,200,960,520),result[0][2:])

    def test_default_is_the_model_actually_loaded_by_android(self):
        self.assertTrue(DEFAULT_MODEL.is_file())
        self.assertEqual("assets",DEFAULT_MODEL.parent.name)


if __name__ == "__main__":
    unittest.main()
