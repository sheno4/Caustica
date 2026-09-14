"""Record eight paired off/post 4K flight-and-return intervals without image readbacks."""
import argparse
import json
import math
import time
from pathlib import Path
from caustica_debug import Client
from check_fog import validate_world


def parse_args(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--copied-world',required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--session',default='run/caustica-debug/session.json')
    p.add_argument('--origin',nargs=3,type=float,help='Flight start XYZ; defaults to initial player position')
    p.add_argument('--headings',nargs=4,type=float,default=[47.52295,-43,137,-137])
    p.add_argument('--times',nargs=4,type=int,default=[1000,1000,12000,12000])
    p.add_argument('--diagnostics',action='store_true',help='Record ShadowTraversal from a separately instrumented binary')
    a=p.parse_args(argv)
    if not all(math.isfinite(v) for v in a.headings+(a.origin or [])):
        p.error('Headings and origin must be finite')
    if any(v<0 or v>2147483647 for v in a.times):
        p.error('Times must be nonnegative signed32-bit tick counts')
    return a


def run(a):
    c=Client(a.session,180)
    initial=c.call('status');validate_world(initial,a.copied_world)
    mc=c.call('settings.get',feature='caustica:minecraft')
    saved={k:mc[k]['value'] for k in ('fog.enabled','fog.density','fog.debug','fog.samples','fog.resolution-divisor')}
    pose=initial['player']
    origin=a.origin or [pose[k] for k in ('x','y','z')]
    if not all(math.isfinite(v) for v in origin):raise ValueError('Initial player origin must be finite')
    def command(text):return c.call('command',command=text)['result']
    saved_time=command('time query time')
    saved_advance=bool(command('gamerule minecraft:advance_time'))
    saved_mode=command('data get entity @s playerGameType')
    r={'initial':initial,'savedFog':saved,'savedTime':saved_time,'savedAdvanceTime':saved_advance,
       'savedGameMode':saved_mode,'origin':origin,'headings':a.headings,'times':a.times,
       'samples':[],'cleanupErrors':[],'complete':False,'restorationComplete':False,'diagnostics':a.diagnostics,
       'recipe':{'flightTicks':200,'turnDegreesPerSecond':8,'flyingSpeed':.2,'flightSettleFrames':120,
                 'returnPitch':65,'returnFrames':600,'outputWidth':3840,'outputHeight':2160},
       'notes':['JFR contains flight,120settleframes,return and600returnframes; no image readbacks.',
                'Begin without held inputs and with unfrozen ticks. Capturing ShadowTraversal requires an instrumented binary.',
                'Terrain drain before flight does not prevent new streaming during flight.']}
    a.output.mkdir(parents=True,exist_ok=True)
    def save():(a.output/'manifest.json').write_text(json.dumps(r,indent=2),encoding='utf-8')
    def wait(frames):c.call('wait',frames=frames,timeoutMs=180000)
    def settle():
        deadline=time.monotonic()+90
        while time.monotonic()<deadline:
            wait(120)
            status=c.call('status');validate_world(status,a.copied_world)
            if status['terrainOutstandingBuilds']==0:return status
        raise RuntimeError('Terrain did not settle before flight within90seconds')
    def teleport(yaw,pitch):
        command('tp @s '+' '.join(f'{v:.6f}' for v in (*origin,yaw,pitch)))
    try:
        save();c.call('input.set');command('gamemode spectator')
        c.call('window.resize',width=3840,height=2160)
        command('gamerule minecraft:advance_time false')
        for yaw,daytime in zip(a.headings,a.times):
            for enabled in (False,True):
                command(f'time set {daytime}');teleport(yaw,0)
                c.call('settings.set',feature='caustica:minecraft',values={'fog.enabled':enabled,'fog.density':1,
                       'fog.debug':0,'fog.samples':64,'fog.resolution-divisor':8})
                before=settle()
                if (before['window']['width'],before['window']['height'])!=(3840,2160):
                    raise RuntimeError('Flight requires actual3840x2160 framebuffer dimensions')
                sample={'label':f'{"post" if enabled else "off"}-yaw{yaw}-time{daytime}','before':before,
                        'turnDegreesPerSecond':8,'flightTicks':200}
                r['samples'].append(sample);save()
                events=['Frame','GpuStage','GpuWait','FrameCounter']+(['ShadowTraversal'] if a.diagnostics else [])
                c.call('jfr.start',events=events)
                try:
                    c.call('input.set',forward=True,sprint=True,flyingSpeed=.2,turnDegreesPerSecond=8)
                    c.call('wait',ticks=200,timeoutMs=180000)
                    c.call('input.set',flyingSpeed=.05)
                    sample['flightEnd']=c.call('status')
                    wait(120);teleport(yaw,65)
                    sample['return']=c.call('status');wait(600)
                finally:
                    try:c.call('input.set',flyingSpeed=.05)
                    finally:
                        try:sample['recording']=c.call('jfr.stop')
                        finally:save()
                    sample['after']=c.call('status');save()
                print(sample['label'],flush=True)
        r['complete']=True
    finally:
        operations=[('input.set',{'flyingSpeed':pose['currentFlyingSpeed']}),
                    ('settings.set',{'feature':'caustica:minecraft','values':saved}),
                    ('command',{'command':f'time set {saved_time}'}),
                    ('command',{'command':f'gamerule minecraft:advance_time {str(saved_advance).lower()}'}),
                    ('command',{'command':'tp @s '+' '.join(str(pose[k]) for k in ('x','y','z','yaw','pitch'))}),
                    ('command',{'command':'gamemode '+['survival','creative','adventure','spectator'][saved_mode]}),
                    ('window.resize',{'width':initial['window']['screenWidth'],'height':initial['window']['screenHeight']})]
        for op,arguments in operations:
            try:c.call(op,**arguments)
            except Exception as error:r['cleanupErrors'].append({'op':op,'error':str(error)})
        r['restorationComplete']=not r['cleanupErrors'];save()
        if r['cleanupErrors']:raise RuntimeError(r['cleanupErrors'])


if __name__=='__main__':run(parse_args())
