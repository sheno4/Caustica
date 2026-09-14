"""Independent density quadrature experiments, not execution of renderer shaders."""
import unittest

import numpy as np


def occupied_band(distance, start, width=3.0):
    phase = (np.asarray(distance) - start) / width
    return np.where((phase > 0) & (phase < 1), np.sin(np.pi * phase) ** 2, 0.0)


def gauss4_integral(function, width):
    nodes, weights = np.polynomial.legendre.leggauss(4)
    return np.dot(weights, function((nodes + 1) * width / 2)) * width / 2


class FogDensityQuadratureTest(unittest.TestCase):
    def test_midpoint_misses_smooth_occupied_band_in_distant_cell(self):
        # The farthest cell of a 64-cell quadratic grid is almost eight metres wide.
        width = 256 * (1 - (63 / 64) ** 2)
        density = lambda distance: occupied_band(distance, 0.5)
        dense_positions = (np.arange(200_000) + 0.5) * width / 200_000
        reference = np.mean(density(dense_positions)) * width
        midpoint = density(width / 2) * width
        gauss = gauss4_integral(density, width)
        self.assertAlmostEqual(reference, 1.5, places=9)
        self.assertEqual(midpoint, 0.0)
        self.assertLess(abs(gauss - reference) / reference, 0.1)

    def test_four_nodes_reduce_error_as_band_moves_across_cell(self):
        width = 256 * (1 - (63 / 64) ** 2)
        positions = (np.arange(20_000) + 0.5) * width / 20_000
        midpoint_errors, gauss_errors = [], []
        for start in np.linspace(-1.0, width - 2.0, 101):
            density = lambda distance: occupied_band(distance, start)
            reference = np.mean(density(positions)) * width
            midpoint_errors.append(density(width / 2) * width - reference)
            gauss_errors.append(gauss4_integral(density, width) - reference)
        self.assertLess(np.mean(np.square(gauss_errors)), np.mean(np.square(midpoint_errors)) / 4)


if __name__ == "__main__":
    unittest.main()
