"""Numerical reference for the post-fog distance grid; does not execute GPU shaders."""
import unittest
import numpy as np


def intervals(surface_distance, maximum_distance=256.0, steps=64):
    boundaries = maximum_distance * np.linspace(0, 1, steps + 1) ** 2
    widths = np.maximum(0, np.minimum(boundaries[1:], surface_distance) - boundaries[:-1])
    return boundaries[:-1], widths


class FogQuadratureTest(unittest.TestCase):
    def test_batches_cover_distance_without_gaps_or_overlaps(self):
        for steps in (32,64,512):
            for maximum in (2.56,256,25600):
                for fraction in (0,.001,.0546875,.25,.999,1):
                    depth=maximum*fraction
                    starts,widths=intervals(depth,maximum,steps)
                    self.assertAlmostEqual(widths.sum(),depth)
                    self.assertTrue(np.all(widths>=0))
                    self.assertAlmostEqual(widths.reshape(-1,8).sum(axis=1).sum(),depth)
                    active=np.flatnonzero(widths)
                    if len(active):
                        np.testing.assert_allclose(starts[active[:-1]]+widths[active[:-1]],starts[active[1:]])
                        self.assertAlmostEqual(starts[active[-1]]+widths[active[-1]],depth)

    def test_surface_depth_only_changes_terminal_interval(self):
        for steps in (32,64,512):
            near_starts,near_widths=intervals(14,steps=steps)
            far_starts,far_widths=intervals(30,steps=steps)
            complete=near_starts+far_widths<=14
            np.testing.assert_array_equal(near_starts,far_starts)
            np.testing.assert_array_equal(near_widths[complete],far_widths[complete])
            active=np.flatnonzero(near_widths)
            self.assertAlmostEqual(near_starts[active[-1]]+near_widths[active[-1]],14)
            self.assertTrue(np.all(np.diff(intervals(256,steps=steps)[1])>0))

    def test_beer_lambert_and_constant_source_are_partition_invariant(self):
        for steps in (32,64,512):
            for depth in (.001,14,64,256):
                starts,widths=intervals(depth,steps=steps)
                # Affine density integrates exactly at interval midpoints.
                sigma=.002+.0001*(starts+widths/2)
                transmittance,scattering=1.,0.
                for batch in (sigma*widths).reshape(-1,8):
                    for optical_depth in batch:
                        segment_t=np.exp(-optical_depth)
                        scattering+=transmittance*(1-segment_t)
                        transmittance*=segment_t
                exact_t=np.exp(-.002*depth-.00005*depth**2)
                self.assertAlmostEqual(transmittance,exact_t,places=13)
                self.assertAlmostEqual(scattering,1-exact_t,places=13)

    def test_midpoint_error_bound_matches_quadratic_integrand_at_all_ranges(self):
        for steps in (32,64,512):
            for depth in (14,64,256):
                starts,widths=intervals(depth,steps=steps)
                measured_error=depth**3/3-np.sum((starts+widths/2)**2*widths)
                # f(t)=t^2 has constant f''=2, so the midpoint bound is attained exactly.
                bound=np.sum(widths**3)/12
                self.assertAlmostEqual(measured_error,bound,delta=2e-9)
        # Near-distance concentration does not guarantee a better full-domain bound.
        full64=intervals(256)[1]
        uniform48=np.full(48,256/48)
        self.assertGreater(np.sum(full64**3),np.sum(uniform48**3))


if __name__=='__main__':unittest.main()
