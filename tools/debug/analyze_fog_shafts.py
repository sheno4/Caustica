"""Offline post roof-slit placement assessment using submitted camera metadata."""
import argparse, json
from pathlib import Path
import numpy as np
import OpenEXR
from analyze_fog import shaft_false_dark
from plan_fog_shafts import expected_lengths, fixture_endpoints, captured_rays


def interior(mask, radius=8):
    size = 2 * radius + 1
    padded = np.pad(mask.astype(np.int32), radius)
    integral = np.pad(padded, ((1,0),(1,0))).cumsum(0).cumsum(1)
    count = integral[size:,size:] - integral[:-size,size:] - integral[size:,:-size] + integral[:-size,:-size]
    return count == size * size



def main():
    p = argparse.ArgumentParser(); p.add_argument('directory', type=Path); a = p.parse_args()
    manifest = json.loads((a.directory / 'manifest.json').read_text())
    if not manifest['complete'] or len(manifest['samples']) != 24:
        raise ValueError('Require complete 24-bundle capture')
    origin = manifest['fixture']['bounds'][:3]
    preliminary = a.directory/'placement-analysis-preliminary.json'
    if not preliminary.exists() and (a.directory/'placement-analysis.json').exists():
        preliminary.write_bytes((a.directory/'placement-analysis.json').read_bytes())
    report = {'notes':['POST direct scattering open-minus-closed comparison only.',
                       'Counter snapshots in before/after status may predate captures; raw frame serials identify images.',
                       'Temporal SE uses three repeat spatial means, not independent per-pixel samples.'], 'views':{}}
    for lateral in (0, .25):
        groups = {}; all_finite = True; frame_ids = []
        known = lit = dark = None
        depth_match = None
        depth_stats = []
        for sample in [s for s in manifest['samples'] if s['lateral'] == lateral]:
            image = next(i for i in sample['raw']['images'] if i['metadata']['name'] == 'scene-color')
            meta = image['metadata']; frame_ids.append(meta['frameSerial'])
            assert len({i['metadata']['frameSerial'] for i in sample['raw']['images']}) == 1
            with OpenEXR.File(image['path']) as f: values = f.channels()['RGBA'].pixels[..., :3].astype(float)
            all_finite &= bool(np.isfinite(values).all())
            if sample['signal'] == 'S': values /= meta['preExposure']
            length, mask = expected_lengths(meta['width'],meta['height'],projection=meta['projection'],fixture_origin=origin)
            depth_image = next(i for i in sample['raw']['images'] if i['metadata']['name']=='primary-depth')
            dm = depth_image['metadata']; projection = dm['projection']
            with OpenEXR.File(depth_image['path']) as f: depth = f.channels()['RGBA'].pixels[...,0].astype(float)
            rays = captured_rays(projection,dm['width'],dm['height'],primary_depth=True)
            endpoint, native_common = fixture_endpoints(np.asarray(projection['cameraWorld'])-origin,rays)
            inverse = np.asarray(projection['inverseProjectionView']).reshape(4,4,order='F')
            point = np.concatenate([rays*np.where(np.isfinite(endpoint),endpoint,0)[...,None],
                                    np.ones((*depth.shape,1))],axis=-1)
            clip = point @ np.linalg.inv(inverse).T
            predicted = np.divide(clip[...,2],clip[...,3],out=np.zeros_like(depth),where=clip[...,3]!=0)
            match = native_common & (depth>0) & (np.abs(depth-predicted)<=np.maximum(1e-8,np.abs(predicted)*.002))
            depth_stats.append(dict(frame=dm['frameSerial'],nativeCommon=int(native_common.sum()),
                                    nativeMatched=int(match.sum())))
            sy,sx = meta['height']//dm['height'],meta['width']//dm['width']
            assert (sy*dm['height'],sx*dm['width'])==(meta['height'],meta['width'])
            # Explicit conservative native-cell expansion; subsequent erosion excludes boundaries.
            mapped = np.repeat(np.repeat(match,sy,axis=0),sx,axis=1)
            depth_match = mapped if depth_match is None else depth_match & mapped
            current_lit = interior(mask & (length > 1)); current_dark = interior(mask & (length == 0))
            lit = current_lit if lit is None else lit & current_lit
            dark = current_dark if dark is None else dark & current_dark
            known = mask if known is None else known & mask
            groups.setdefault((sample['roof'],sample['signal']),[]).append(values)
        depth_match = interior(depth_match)
        lit &= depth_match; dark &= depth_match; known &= depth_match
        means = {key: np.mean(v, axis=0) for key,v in groups.items()}
        t = np.concatenate([v[known].ravel() for key,values in groups.items() if key[1]=='T' for v in values])
        tdiff = np.abs(means['open','T']-means['closed','T'])[known]
        invariant = float(np.percentile(tdiff,99)) <= .0005
        delta = means['open','S']-means['closed','S']
        regions = {}
        for name, mask in [('lit',lit),('dark',dark)]:
            if not mask.any(): raise ValueError(f'No interior {name} mask at {lateral}')
            om = np.array([v[mask].mean() for v in groups['open','S']])
            cm = np.array([v[mask].mean() for v in groups['closed','S']])
            regions[name] = dict(pixels=int(mask.sum()), meanDifference=float(om.mean()-cm.mean()),
                                 standardError=float(np.sqrt(om.var(ddof=1)/3+cm.var(ddof=1)/3)))
        L,D = regions['lit'],regions['dark']
        frozen_design = 'frozenGameTime' in manifest
        nonvacuum = float(np.median(1-t)) >= 4 * 2**-11 if frozen_design else float(np.median(t)) < .995
        fixed_time = frozen_design and all(s[when]['world']['gameTime']==manifest['frozenGameTime']
                                          for s in manifest['samples'] for when in ('before','after'))
        valid = all_finite and bool(t.min()>=0 and t.max()<=1) and nonvacuum and invariant and fixed_time
        report['views'][str(lateral)] = dict(frameIds=frame_ids,finite=all_finite,depthAgreement=depth_stats,fixedProceduralTime=fixed_time,
             nonvacuumCriterion="median(1-T)>=4*2^-11" if frozen_design else "legacy median(T)<.995",
             transmittance=dict(min=float(t.min()),mean=float(t.mean()),max=float(t.max()),
                                median=float(np.median(t)),openClosedP99=float(np.percentile(tdiff,99))),
             regions=regions,controlValid=valid, samplingCoverage=shaft_false_dark(delta,lit),
             placementPass=bool(valid and L['meanDifference']>max(5*L['standardError'],1e-8)
                                and abs(D['meanDifference'])<=max(5*D['standardError'],1e-8)))
        np.savez_compressed(a.directory/f'placement-{lateral}.npz',deltaScattering=delta,lit=lit,dark=dark,known=known)
        from PIL import Image, ImageDraw
        panels = []
        scale = max(float(np.percentile(means['open','S'][known],99)),1e-8)
        for label,values in [('Open S',means['open','S']),('Closed S',means['closed','S']),('Delta S',np.maximum(delta,0))]:
            rgb = np.clip(values/scale,0,1)**(1/2.2)
            preview = Image.fromarray((rgb*255).astype(np.uint8)); preview.thumbnail((960,540))
            ImageDraw.Draw(preview).text((8,8),label,fill='white'); panels.append(preview)
        overlay = np.clip(np.maximum(delta,0)/scale,0,1)**(1/2.2)
        overlay[lit] = .5*overlay[lit]+np.array([0,.5,0])
        overlay[dark] = .5*overlay[dark]+np.array([0,0,.5])
        preview = Image.fromarray((overlay*255).astype(np.uint8)); preview.thumbnail((960,540))
        ImageDraw.Draw(preview).text((8,8),'Delta: green=lit, blue=dark; common endpoint + measured depth',fill='white')
        panels.append(preview)
        contact = Image.new('RGB',(1920,1080))
        for i,panel in enumerate(panels): contact.paste(panel,((i%2)*960,(i//2)*540))
        contact.save(a.directory/f'placement-preview-{lateral}.png')
    (a.directory/'placement-analysis.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))


if __name__ == '__main__': main()
