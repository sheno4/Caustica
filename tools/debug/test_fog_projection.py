import unittest
import numpy as np
from check_fog_shafts import latch_frozen_time
from analyze_fog import shaft_false_dark, fog_reference_error
from plan_fog_shafts import captured_rays, expected_lengths, fixture_endpoints


class CapturedProjectionTest(unittest.TestCase):
    def projection(self, jitter=(.25, -.125)):
        # Perspective unprojection toward +Z with Minecraft screen-right=-X.
        matrix = np.array([[-2, 0, 0, 0], [0, 1, 0, 0], [0, 0, 0, 1], [0, 0, 1, 0.]])
        return dict(inverseProjectionView=matrix.flatten(order='F').tolist(),
                    cameraWorld=[104, 68.5, 200.5], renderWidth=4, renderHeight=2,
                    jitterPixels=list(jitter))

    def test_top_down_rows_and_column_major_matrix(self):
        rays = captured_rays(self.projection(), 4, 2)
        expected = np.array([1.5, .5, 1.])
        np.testing.assert_allclose(rays[0, 0], expected / np.linalg.norm(expected))
        self.assertGreater(rays[0, 0, 1], 0)
        self.assertLess(rays[1, 0, 1], 0)

    def test_jitter_applies_in_vulkan_rows_only_for_primary(self):
        rays = captured_rays(self.projection(), 4, 2, primary_depth=True)
        expected = np.array([1.25, .375, 1.])
        np.testing.assert_allclose(rays[0, 0], expected / np.linalg.norm(expected))
        np.testing.assert_array_equal(captured_rays(self.projection(), 8, 4),
                                      captured_rays(self.projection((0, 0)), 8, 4))
        with self.assertRaises(ValueError):
            captured_rays(self.projection(), 8, 4, primary_depth=True)

    def test_closing_slit_excludes_changed_first_surface(self):
        eye = np.array([4., 4.5, .5])
        rays = np.array([[6, 3.6, 8], [5, 0, 13.5], [0, 0, 13.5]], dtype=float)
        rays /= np.linalg.norm(rays, axis=-1, keepdims=True)
        endpoint, common = fixture_endpoints(eye, rays)
        np.testing.assert_array_equal(common, [False, True, True])
        self.assertTrue(np.isfinite(endpoint).all())

    def test_false_dark_uses_signed_rgb_mean_only_inside_lit_mask(self):
        values = np.array([[[0.,0.,0.], [1.,2.,3.]], [[-1.,-1.,-1.], [1000.,1000.,1000.]]])
        metric = shaft_false_dark(values, np.array([[True,True],[True,False]]))
        self.assertAlmostEqual(metric['meanLitDelta'], 1/3)
        self.assertEqual(metric['falseDarkPixels'], 2)
        self.assertAlmostEqual(metric['falseDarkFraction'], 2/3)

    def test_absent_shaft_is_inconclusive_not_zero_holes(self):
        metric = shaft_false_dark(np.zeros((2,2,3)), np.ones((2,2),dtype=bool))
        self.assertFalse(metric['responsePresent'])
        self.assertIsNone(metric['falseDarkFraction'])

    def test_nonzero_bands_pass_hole_metric_but_have_reference_curvature(self):
        reference = np.ones((8,8,3))
        candidate = reference.copy()
        candidate[:, ::2] *= 1.2
        mask = np.ones((8,8),dtype=bool)
        self.assertEqual(shaft_false_dark(candidate,mask)['falseDarkFraction'],0)
        error = fog_reference_error(candidate,reference,mask)
        self.assertAlmostEqual(error['relativeP99ResidualCurvature'],.4)
        self.assertAlmostEqual(error['relativeMeanAbsoluteError'],.1)

    def test_smooth_reference_and_masked_boundary_have_no_residual_bands(self):
        reference = np.broadcast_to(np.arange(8)[None,:,None]+1.,(8,8,3)).copy()
        candidate = reference.copy(); candidate[:,0] = 1000
        mask = np.ones((8,8),dtype=bool); mask[:,0] = False
        result = fog_reference_error(candidate,reference,mask)
        self.assertEqual(result['relativeMeanAbsoluteError'],0)
        self.assertEqual(result['relativeP99ResidualCurvature'],0)

    def test_frozen_clock_latch_restarts_after_late_packet(self):
        state={'time':0.,'tick':100}
        def wait(frames):
            self.assertEqual(frames,120)
            state['time']+=.5
            if state['time']>=1:state['tick']=101
        tick,observations=latch_frozen_time(lambda:{'world':{'gameTime':state['tick']}},wait,lambda:state['time'])
        self.assertEqual(tick,101)
        self.assertEqual(state['time'],3)
        self.assertEqual(observations[-1]['elapsed'],3)

    def test_frozen_clock_latch_rejects_continuously_advancing_time(self):
        state={'time':0.}
        def wait(frames):state['time']+=.5
        with self.assertRaises(RuntimeError):
            latch_frozen_time(lambda:{'world':{'gameTime':int(state['time']*2)}},wait,lambda:state['time'])
        self.assertEqual(state['time'],15)

    def test_captured_eye_is_relative_to_fixture_not_scene_origin(self):
        p = self.projection((0, 0))
        p['cameraWorld'] = [109, 68.5, 200.5]
        p['inverseProjectionView'] = np.array([[-1, 0, 0, 0], [0, 1, 0, 0],
                                               [0, 0, 0, 1], [0, 0, 1, 0.]]).flatten(order='F').tolist()
        length, known = expected_lengths(1, 1, projection=p, fixture_origin=[100, 64, 200])
        self.assertTrue(known[0, 0])
        self.assertAlmostEqual(length[0, 0], 13.5 - 4.5 / np.sqrt(3))


if __name__ == '__main__':
    unittest.main()
