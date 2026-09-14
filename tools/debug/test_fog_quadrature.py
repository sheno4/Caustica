"""Numerical reference for the post-fog distance grid; does not execute GPU shaders."""
import unittest

import numpy as np


def intervals(surface_distance, maximum_distance=256.0, steps=96):
    boundaries = maximum_distance * np.linspace(0, 1, steps + 1) ** 2
    widths = np.maximum(0, np.minimum(boundaries[1:], surface_distance) - boundaries[:-1])
    return boundaries[:-1], widths


class FogQuadratureTest(unittest.TestCase):
    def test_batches_cover_distance_without_gaps_or_overlaps(self):
        for maximum in (2.56, 256, 25600):
            for fraction in (0, 0.001, 0.0546875, 0.25, 0.999, 1):
                depth = maximum * fraction
                starts, widths = intervals(depth, maximum)
                self.assertAlmostEqual(widths.sum(), depth)
                self.assertTrue(np.all(widths >= 0))
                # Twelve eight-ray batches cover the same integral as a single pass.
                self.assertAlmostEqual(widths.reshape(12, 8).sum(axis=1).sum(), depth)
                active = np.flatnonzero(widths)
                if len(active):
                    np.testing.assert_allclose(starts[active[:-1]] + widths[active[:-1]], starts[active[1:]])
                    self.assertAlmostEqual(starts[active[-1]] + widths[active[-1]], depth)

    def test_surface_depth_only_changes_terminal_interval(self):
        near_starts, near_widths = intervals(14)
        far_starts, far_widths = intervals(30)
        complete = near_starts + far_widths <= 14
        np.testing.assert_array_equal(near_starts, far_starts)
        np.testing.assert_array_equal(near_widths[complete], far_widths[complete])
        self.assertEqual(np.count_nonzero(near_widths), 23)
        self.assertLessEqual(far_widths.max(), 256 / 48)
        self.assertLessEqual(intervals(256)[1].max(), 256 / 48)

    def test_beer_lambert_and_constant_source_are_partition_invariant(self):
        for depth in (0.001, 14, 64, 256):
            starts, widths = intervals(depth)
            # Affine density integrates exactly at interval midpoints.
            sigma = 0.002 + 0.0001 * (starts + widths / 2)
            transmittance, scattering = 1.0, 0.0
            for batch in (sigma * widths).reshape(12, 8):
                for optical_depth in batch:
                    segment_t = np.exp(-optical_depth)
                    scattering += transmittance * (1 - segment_t)
                    transmittance *= segment_t
            exact_t = np.exp(-0.002 * depth - 0.00005 * depth ** 2)
            self.assertAlmostEqual(transmittance, exact_t, places=13)
            self.assertAlmostEqual(scattering, 1 - exact_t, places=13)

    def test_smooth_midpoint_error_bound_improves_near_and_full_range(self):
        for depth in (14, 64, 256):
            widths = intervals(depth)[1]
            uniform = np.diff(np.minimum(np.linspace(0, 256, 49), depth))
            # With the same bound on the integrand second derivative, midpoint error
            # is at most max(abs(f'')) * sum(width**3) / 24.
            self.assertLess(np.sum(widths ** 3), np.sum(uniform ** 3))


if __name__ == "__main__":
    unittest.main()
