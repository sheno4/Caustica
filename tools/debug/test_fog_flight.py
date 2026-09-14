import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock,patch
from check_fog_flight import parse_args,run


class FogFlightTest(unittest.TestCase):
    def test_rejects_nonfinite_route_before_client_access(self):
        with patch('sys.stderr'):
            for flags in (['--origin','nan','1','2'],['--headings','1','2','3','inf'],['--times','1','2','3','-1']):
                with self.assertRaises(SystemExit):parse_args(['--copied-world','Copy','--output','unused']+flags)

    def test_failed_flight_stops_jfr_and_restores_original_state(self):
        initial={'ready':True,'paused':False,'frameActive':True,'world':{'directory':'Copy'},
                 'player':dict(x=4.5,y=180,z=-6.25,yaw=20,pitch=30,currentFlyingSpeed=.07),
                 'window':dict(width=1280,height=720,screenWidth=1280,screenHeight=720),
                 'terrainOutstandingBuilds':0}
        window=dict(initial['window'])
        client=Mock()
        settings={'fog.enabled':True,'fog.density':2,'fog.debug':0,'fog.samples':128,'fog.resolution-divisor':4}
        def call(op,**args):
            if op=='status':return {**initial,'window':dict(window)}
            if op=='settings.get':return {k:{'value':v} for k,v in settings.items()}
            if op=='command':
                return {'result':{'time query time':12345,'gamerule minecraft:advance_time':1,'data get entity @s playerGameType':1}.get(args['command'],1)}
            if op=='window.resize':window.update(args)
            if op=='wait' and args.get('ticks')==200:raise RuntimeError('flight interrupted')
            if op=='jfr.stop':return {'path':'recording.jfr'}
            return {}
        client.call.side_effect=call
        with tempfile.TemporaryDirectory() as directory:
            args=parse_args(['--copied-world','Copy','--output',directory])
            with patch('check_fog_flight.Client',return_value=client):
                with self.assertRaisesRegex(RuntimeError,'flight interrupted'):run(args)
            manifest=json.loads((Path(directory)/'manifest.json').read_text())
        self.assertEqual(manifest['origin'],[4.5,180,-6.25])
        self.assertFalse(manifest['complete']);self.assertTrue(manifest['restorationComplete'])
        self.assertEqual(manifest['samples'][0]['before']['window']['width'],3840)
        operations=[c.args[0] for c in client.call.call_args_list]
        self.assertEqual(operations.count('jfr.stop'),1)
        self.assertNotIn('image.capture',operations);self.assertNotIn('screenshot',operations)
        commands=[c.kwargs['command'] for c in client.call.call_args_list if c.args[0]=='command']
        self.assertIn('time set 12345',commands)
        self.assertIn('tp @s 4.5 180 -6.25 20 30',commands)
        self.assertEqual(commands[-1],'gamemode creative')
        self.assertEqual(window['width'],1280);self.assertEqual(window['height'],720)
        restored=[c.kwargs['values'] for c in client.call.call_args_list if c.args[0]=='settings.set'][-1]
        self.assertEqual(restored,settings)


if __name__=='__main__':unittest.main()
