"""Independent references for retained fog phase and shadow continuation; these do not execute the shader."""

import unittest

import numpy as np


class VolumeTransportMathTest(unittest.TestCase):
    def test_grazing_shadow_restart_clears_plane_in_float32(self):
        epsilon = np.float32(0.001)
        hit = np.array([128, 128, -128], dtype=np.float32)
        for side in (-1, 1):
            direction = np.array([1, side * 1e-4, 0], dtype=np.float32)
            direction /= np.linalg.norm(direction)
            normal = np.array([0, 1, 0], dtype=np.float32)
            old_origin = hit + direction * epsilon
            # A changed origin does not imply clearance: its tangential coordinate moves only.
            self.assertNotEqual(old_origin[0], hit[0])
            self.assertEqual(old_origin[1], hit[1])
            remaining = np.float32(10)
            new_origin = hit + normal * side * min(2 * epsilon, remaining)
            clearance = np.dot(new_origin - hit, normal) * side
            self.assertGreater(clearance, 0.0019)
            # The same plane is now behind the continuation ray for entry and exit.
            plane_t = (hit[1] - new_origin[1]) / direction[1]
            self.assertLess(plane_t, 0)
            forward = np.dot(new_origin - hit, direction)
            new_range = remaining - forward
            self.assertGreaterEqual(new_range, 0)
            self.assertAlmostEqual(float(new_range + forward), float(remaining), places=6)
            self.assertLess(forward, clearance / 1000)




    def test_henyey_greenstein_phase_normalization(self):
        cosine, weights = np.polynomial.legendre.leggauss(256)
        for g in (-0.85, -0.3, 0, 0.3, 0.85):
            with self.subTest(g=g):
                phase = (1 - g * g) / (4 * np.pi * (1 + g * g - 2 * g * cosine) ** 1.5)
                self.assertAlmostEqual(float(np.sum(phase * weights) * 2 * np.pi), 1, places=10)

    def test_compensated_shadow_roulette_preserves_rgb_mean(self):
        uniform = (np.arange(262144) + 0.5) / 262144
        source = np.array([0.02, 0.04, 0.08])
        for optical_weight in (0.001, 0.02, 0.1):
            probability = min(optical_weight * 16, 1)
            estimate = (uniform[:, None] < probability) * source / probability
            np.testing.assert_allclose(estimate.mean(axis=0), source, atol=1e-5, rtol=0)


if __name__ == "__main__":
    unittest.main()
