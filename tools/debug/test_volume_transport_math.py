"""Independent statistical references for medium sampling; these do not execute the shader."""

import unittest

import numpy as np


def poisson_tracking_reference(extinction_layers, layer_lengths, albedo, majorant, samples=200000):
    """Generate an ordered Poisson point process directly, independent of exponential stepping."""
    rng = np.random.default_rng(902104)
    extinction_layers = np.asarray(extinction_layers)
    boundaries = np.cumsum(layer_lengths)
    length = boundaries[-1]
    counts = rng.poisson(majorant * length, samples)
    points = rng.uniform(0, length, (samples, counts.max()))
    points[np.arange(counts.max())[None, :] >= counts[:, None]] = np.inf
    points.sort(axis=1)
    ratio = np.ones((samples, 3))
    shadow = np.ones((samples, 3))
    scattered = np.zeros((samples, 3))
    collided = np.zeros(samples, dtype=bool)
    for column in range(points.shape[1]):
        indices = np.flatnonzero(np.isfinite(points[:, column]))
        layers = np.searchsorted(boundaries, points[indices, column], side="right")
        extinction = extinction_layers[layers]
        shadow[indices] *= 1 - extinction / majorant
        active = ~collided[indices]
        indices, extinction = indices[active], extinction[active]
        rate = extinction.max(axis=1)
        accepted = rng.random(indices.size) < rate / majorant
        event_indices = indices[accepted]
        scattered[event_indices] = ratio[event_indices] * extinction[accepted] * albedo / rate[accepted, None]
        collided[event_indices] = True
        null_indices = indices[~accepted]
        ratio[null_indices] *= (1 - extinction[~accepted] / majorant) / (1 - rate[~accepted, None] / majorant)
    return (~collided)[:, None] * ratio, scattered, shadow, collided


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

    def assert_monte_carlo_mean(self, samples, expected):
        error = np.abs(samples.mean(axis=0) - expected)
        tolerance = 6 * samples.std(axis=0) / np.sqrt(samples.shape[0]) + 0.0001
        self.assertTrue(np.all(error <= tolerance), f"error={error}, tolerance={tolerance}")

    def test_delta_and_ratio_tracking_homogeneous_unequal_rgb(self):
        extinction = np.array([[0.025, 0.065, 0.1]])
        albedo = np.array([0.4, 0.7, 0.95])
        length = 15.0
        unscattered, scattering, shadow, collided = poisson_tracking_reference(
            extinction, [length], albedo, majorant=0.17)
        expected_t = np.exp(-extinction[0] * length)
        self.assert_monte_carlo_mean(unscattered, expected_t)
        self.assert_monte_carlo_mean(scattering, albedo * (1 - expected_t))
        self.assert_monte_carlo_mean(shadow, expected_t)
        self.assert_monte_carlo_mean(collided, 1 - np.exp(-extinction.max() * length))

    def test_delta_and_ratio_tracking_heterogeneous_two_layers(self):
        extinction = np.array([[0.09, 0.01, 0.04], [0.015, 0.12, 0.07]])
        lengths = np.array([3.0, 9.0])
        albedo = np.array([0.8, 0.6, 0.9])
        unscattered, scattering, shadow, collided = poisson_tracking_reference(
            extinction, lengths, albedo, majorant=0.2)
        expected_t = np.exp(-np.sum(extinction * lengths[:, None], axis=0))
        self.assert_monte_carlo_mean(unscattered, expected_t)
        self.assert_monte_carlo_mean(scattering, albedo * (1 - expected_t))
        self.assert_monte_carlo_mean(shadow, expected_t)
        self.assert_monte_carlo_mean(collided, 1 - np.exp(-np.dot(extinction.max(axis=1), lengths)))

    def test_vacuum_with_positive_majorant_has_exact_unit_shadow(self):
        unscattered, scattering, shadow, collided = poisson_tracking_reference(
            np.zeros((1, 3)), [20.0], np.ones(3), majorant=0.1, samples=10000)
        np.testing.assert_array_equal(shadow, 1)
        np.testing.assert_array_equal(unscattered, 1)
        np.testing.assert_array_equal(scattering, 0)
        self.assertFalse(collided.any())

    def test_exponential_collision_and_rgb_ratio_weights_match_beer_lambert(self):
        uniform = (np.arange(262144) + 0.5) / 262144
        extinction = np.array([0.01, 0.05, 0.12])
        albedo = np.array([0.4, 0.7, 0.95])
        length = 12.0
        rate = extinction.max()
        distance = -np.log1p(-uniform) / rate
        collision = distance < length
        ratio = np.exp((rate - extinction) * np.minimum(distance, length)[:, None])
        unscattered = np.mean((~collision)[:, None] * ratio, axis=0)
        scattering = np.mean(collision[:, None] * ratio * extinction * albedo / rate, axis=0)
        expected_t = np.exp(-extinction * length)
        np.testing.assert_allclose(unscattered, expected_t, atol=5e-6, rtol=0)
        np.testing.assert_allclose(scattering, albedo * (1 - expected_t), atol=5e-6, rtol=0)
        self.assertAlmostEqual(collision.mean(), 1 - np.exp(-rate * length), delta=5e-6)

    def test_henyey_greenstein_normalization_and_sampled_mean_cosine(self):
        cosine, weights = np.polynomial.legendre.leggauss(256)
        uniform = (np.arange(65536) + 0.5) / 65536
        for g in (-0.85, -0.3, 0, 0.3, 0.85):
            with self.subTest(g=g):
                phase = (1 - g * g) / (4 * np.pi * (1 + g * g - 2 * g * cosine) ** 1.5)
                self.assertAlmostEqual(float(np.sum(phase * weights) * 2 * np.pi), 1, places=10)
                if g == 0:
                    samples = 2 * uniform - 1
                else:
                    ratio = (1 - g * g) / (1 - g + 2 * g * uniform)
                    samples = (1 + g * g - ratio * ratio) / (2 * g)
                self.assertTrue(np.all(np.abs(samples) <= 1))
                self.assertAlmostEqual(float(samples.mean()), g, delta=1e-7)

    def test_compensated_shadow_roulette_preserves_rgb_mean(self):
        uniform = (np.arange(262144) + 0.5) / 262144
        source = np.array([0.02, 0.04, 0.08])
        for optical_weight in (0.001, 0.02, 0.1):
            probability = min(optical_weight * 16, 1)
            estimate = (uniform[:, None] < probability) * source / probability
            np.testing.assert_allclose(estimate.mean(axis=0), source, atol=1e-5, rtol=0)


if __name__ == "__main__":
    unittest.main()
