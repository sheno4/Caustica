"""Bounded copied-world post-fog roof acceptance capture. No work on import."""
import argparse
import json
import time
from pathlib import Path
from caustica_debug import Client
from check_fog import validate_world
from fog_fixtures import apply_block_command


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--copied-world', required=True)
    p.add_argument('--fixtures', type=Path, required=True, help='Existing fog_fixtures.py recipe for the selected copied world')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--session', default='run/caustica-debug/session.json')
    a = p.parse_args()
    recipe = json.loads(a.fixtures.read_text())
    if recipe['copiedWorld'] != a.copied_world:
        raise ValueError('Fixture recipe belongs to another copied world')
    fixture = next(f for f in recipe['fixtures'] if f['name'] == 'roof-slit')
    x, y, z = fixture['bounds'][:3]
    c = Client(a.session, 180)
    initial = c.call('status'); validate_world(initial, a.copied_world)
    cmd = lambda text: c.call('command', command=text)['result']
    mc = c.call('settings.get', feature='caustica:minecraft')
    bloom = c.call('settings.get', feature='caustica:bloom')
    changes = {'fog.enabled': True, 'fog.mode': 'POST_PROCESS', 'fog.density': 4,
               'fog.debug': 0, 'fog.resolution-divisor': 4,
               'sky.sun-noon-south-tilt-degrees': 30, 'sky.sun-angular-radius-degrees': .1}
    saved_mc = {k: mc[k]['value'] for k in changes}
    saved_renderer = {k: initial['settings'][k]['value'] for k in ['exposure.mode', 'exposure.manual-ev']}
    saved_time = cmd('time query time'); saved_advance = bool(cmd('gamerule minecraft:advance_time'))
    saved_mode = cmd('data get entity @s playerGameType'); player = initial['player']
    r = dict(initial=initial, savedMinecraft=saved_mc, savedRenderer=saved_renderer,
             savedTime=saved_time, savedAdvanceTime=saved_advance, fixture=fixture,
             samples=[], roofJournal=[], cleanupErrors=[], complete=False)
    a.output.mkdir(parents=True, exist_ok=True)
    def save(): (a.output / 'manifest.json').write_text(json.dumps(r, indent=2))
    def wait(n): c.call('wait', frames=n, timeoutMs=180000)
    def settled():
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            wait(120)
            status = c.call('status'); validate_world(status, a.copied_world)
            if status['terrainOutstandingBuilds'] == 0:
                wait(90)
                if c.call('status')['terrainOutstandingBuilds'] == 0: return
        raise RuntimeError('Fixture terrain failed to settle within bounded wait')
    def roof(block):
        text = f'fill {x+8} {y+8} {z+2} {x+9} {y+8} {z+13} minecraft:{block}'
        r['roofJournal'].append(dict(command=text, result=apply_block_command(c, text))); save()
    roof_verified = False
    tick_frozen = False
    frozen_game_time = None
    r['design'] = {'mediumTimeControl':'tick freeze after initial terrain settle; gameTime invariant required',
                   'daytimeOnlyFreezeInsufficient':True,
                   'density':4, 'densityDeclaredMaximum':4,
                   'nonvacuumCriterion':'median(1-T) >= 4 * 2^-11 on common endpoint mask; four half-float steps near one',
                   'requiresInitiallyRunningTicks':True}
    try:
        save(); c.call('input.set'); c.call('view.set', name='off')
        c.call('window.resize', width=1920, height=1080)
        cmd('gamemode spectator'); cmd('gamerule minecraft:advance_time false'); cmd('time set 6000')
        c.call('settings.set', feature='caustica:minecraft', values=changes)
        c.call('settings.set', feature='caustica:bloom', values={'bloom.enabled': False})
        c.call('settings.set', values={'exposure.mode': 'manual', 'exposure.manual-ev': -12})
        cmd(f'tp @s {x+4:.6f} {y+2.88:.6f} {z+.5:.6f} 0 0'); settled()
        r['tickQueryBefore'] = c.call('command', command='tick query')
        cmd('tick freeze'); tick_frozen = True
        wait(60); frozen_game_time = c.call('status')['world']['gameTime']
        r['frozenGameTime'] = frozen_game_time; save()
        # Require the authored open slot before editing; restoration is then exact.
        for bx in (x+8, x+9):
            for bz in range(z+2, z+14):
                if cmd(f'execute if block {bx} {y+8} {bz} minecraft:air') != 1:
                    raise RuntimeError('Roof slit differs from authored open fixture')
        roof_verified = True
        for lateral in (0, .25):
            cmd(f'tp @s {x+4+lateral:.6f} {y+2.88:.6f} {z+.5:.6f} 0 0'); settled()
            camera = c.call('status')['camera']
            if max(abs(camera[k] - target) for k, target in zip(('x','y','z'), (x+4+lateral,y+4.5,z+.5))) > .01:
                raise RuntimeError(f'Unexpected camera eye: {camera}')
            for state, block in [('open','air'), ('closed','stone')]:
                roof(block); settled()
                for debug, signal in [(1,'T'), (2,'S')]:
                    c.call('settings.set', feature='caustica:minecraft', values={'fog.debug': debug}); wait(60)
                    for index in range(3):
                        sample = dict(label=f'x{lateral}-{state}-{signal}-{index}', lateral=lateral,
                                      roof=state, signal=signal, before=c.call('status'))
                        r['samples'].append(sample); save()
                        if sample['before']['world']['gameTime'] != frozen_game_time:
                            raise RuntimeError('Procedural game time changed while tick freeze required')
                        sample['raw'] = c.call('image.capture', names=['primary-depth','scene-color'])
                        sample['capturedFrameIds'] = [image['metadata']['frameSerial'] for image in sample['raw']['images']]
                        sample['after'] = c.call('status')
                        if sample['after']['world']['gameTime'] != frozen_game_time:
                            raise RuntimeError('Procedural game time changed during capture')
                        save(); print(sample['label'], flush=True); wait(2)
            roof('air')
        r['complete'] = True
    finally:
        operations = [('input.set', {'flyingSpeed': player['currentFlyingSpeed']})]
        if roof_verified:
            try: roof('air')
            except Exception as e: r['cleanupErrors'].append(str(e))
        if tick_frozen:
            operations.append(('command', {'command':'tick unfreeze'}))
        operations += [('settings.set', {'feature':'caustica:minecraft','values':saved_mc}),
                       ('settings.set', {'feature':'caustica:bloom','values':{'bloom.enabled':bloom['bloom.enabled']['value']}}),
                       ('settings.set', {'values':saved_renderer}),
                       ('settings.set', {'values':{'composite.debug-view':initial['settings']['composite.debug-view']['value']}}),
                       ('command', {'command':f'time set {saved_time}'}),
                       ('command', {'command':f'gamerule minecraft:advance_time {str(saved_advance).lower()}'}),
                       ('command', {'command':f"tp @s {player['x']} {player['y']} {player['z']} {player['yaw']} {player['pitch']}"}),
                       ('command', {'command':'gamemode '+['survival','creative','adventure','spectator'][saved_mode]}),
                       ('window.resize', {'width':initial['window']['screenWidth'],'height':initial['window']['screenHeight']})]
        for op, args in operations:
            try: c.call(op, **args)
            except Exception as e: r['cleanupErrors'].append(dict(op=op,error=str(e)))
        save()
        if r['cleanupErrors']: raise RuntimeError(r['cleanupErrors'])


if __name__ == '__main__': main()
