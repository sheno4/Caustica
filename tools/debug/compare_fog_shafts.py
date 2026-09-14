"""Compare same-frozen-field shaft integration against a denser, not automatically converged reference."""
import argparse
import json
from pathlib import Path
import numpy as np
from analyze_fog import fog_reference_error


def projection_for(manifest,lateral):
    sample=next(s for s in manifest['samples'] if s['lateral']==lateral)
    image=next(i for i in sample['raw']['images'] if i['metadata']['name']=='scene-color')
    return image['metadata']['projection']


def compare(candidate,reference):
    cm=json.loads((candidate/'manifest.json').read_text())
    rm=json.loads((reference/'manifest.json').read_text())
    if not cm['complete'] or not rm['complete'] or cm['frozenGameTime']!=rm['frozenGameTime']:
        raise ValueError('Require complete captures from the identical frozen game time')
    if cm['fixture']!=rm['fixture'] or cm['initial']['world']!=rm['initial']['world']:
        raise ValueError('Require the same world and fixture')
    result={'candidateSamples':cm.get('integrationSamples'),'referenceSamples':rm.get('integrationSamples'),
            'frozenGameTime':cm['frozenGameTime'],'views':{},
            'limitation':'The denser reference must itself pass a convergence check before absolute accuracy acceptance. Different sample layouts can retain visibility integration error.'}
    for lateral in (0,.25):
        cp,rp=projection_for(cm,lateral),projection_for(rm,lateral)
        if not np.allclose(cp['cameraWorld'],rp['cameraWorld'],rtol=0,atol=1e-6) or not np.allclose(cp['inverseProjectionView'],rp['inverseProjectionView'],rtol=0,atol=1e-6):
            raise ValueError('Reference camera or projection differs')
        c=np.load(candidate/f'placement-{lateral}.npz');r=np.load(reference/f'placement-{lateral}.npz')
        view={}
        for region in ('lit','dark','known'):
            mask=c[region]&r[region]
            if region=='dark':
                # Dark reference is near zero; normalize leakage to reference lit radiance.
                scale=np.abs(r['deltaScattering'][c['lit']&r['lit']]).mean()
                error=np.abs((c['deltaScattering']-r['deltaScattering'])[mask])/scale
                view[region]={'pixels':int(mask.sum()),'relativeMeanAbsoluteError':float(error.mean()),
                              'relativeP99AbsoluteError':float(np.percentile(error,99))}
            else:
                view[region]=fog_reference_error(c['deltaScattering'],r['deltaScattering'],mask)
                scale=view[region]['referenceMeanAbsoluteRadiance']
                view[region]['relativeSignedMeanError']=float((c['deltaScattering']-r['deltaScattering'])[mask].mean()/scale)
                view[region]['pixels']=int(mask.sum())
        result['views'][str(lateral)]=view
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('candidate',type=Path);p.add_argument('reference',type=Path)
    p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    result=compare(a.candidate,a.reference)
    a.output.write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2))


if __name__=='__main__':main()
