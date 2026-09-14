"""Build bounded persistent fog fixtures at an explicit origin in a disposable copied save."""

import argparse
import json
from pathlib import Path

from caustica_debug import Client
from check_fog import validate_world


def apply_block_command(client, command):
    try:
        return client.call("command", command=command)
    except RuntimeError as error:
        # Minecraft reports an idempotent fill as a command failure when every block already matches.
        if command.startswith("fill ") and "CommandSyntaxException: No blocks were filled" in str(error):
            return {"result": 0, "noChange": True}
        raise


def fixtures(origin):
    ox, y, z = origin
    result = []
    for index, name in enumerate(("oblique-wall", "roof-slit", "glass-water", "local-emitter", "open-water-basin")):
        x = ox + index * 48
        commands = [f"fill {x} {y} {z} {x + 16} {y + 12} {z + 16} minecraft:air",
                    f"fill {x} {y} {z} {x + 16} {y} {z + 16} minecraft:smooth_sandstone"]
        cameras = {"front": dict(x=x + 8.5, y=y + 2.5, z=z + 2.5, yaw=0, pitch=0),
                   "above": dict(x=x + 8.5, y=y + 19, z=z + 8.5, yaw=0, pitch=89)}
        if name == "oblique-wall":
            commands.append(f"fill {x} {y + 1} {z + 14} {x + 16} {y + 12} {z + 14} minecraft:white_concrete")
            cameras["front"] = dict(x=x + 8.5, y=y + 6, z=z + 10, yaw=20, pitch=15)
            cameras["reference-close"] = dict(x=x + 8.5, y=y + 6, z=z + 12, yaw=20, pitch=15)
        elif name == "roof-slit":
            commands.extend([
                f"fill {x + 2} {y + 8} {z + 2} {x + 7} {y + 8} {z + 14} minecraft:stone",
                f"fill {x + 10} {y + 8} {z + 2} {x + 14} {y + 8} {z + 14} minecraft:stone",
                f"fill {x + 2} {y + 1} {z + 14} {x + 14} {y + 8} {z + 14} minecraft:stone"])
            cameras["under-roof"] = dict(x=x + 8.5, y=y + 3, z=z + 3, yaw=0, pitch=-15)
        elif name == "glass-water":
            commands.extend([
                f"fill {x + 3} {y + 1} {z + 8} {x + 13} {y + 6} {z + 14} minecraft:glass hollow",
                f"fill {x + 8} {y + 2} {z + 9} {x + 8} {y + 5} {z + 13} minecraft:glass",
                f"fill {x + 4} {y + 2} {z + 9} {x + 7} {y + 5} {z + 13} minecraft:water",
                f"fill {x + 4} {y + 1} {z + 13} {x + 12} {y + 1} {z + 13} minecraft:red_concrete"])
            cameras["inside-water"] = dict(x=x + 5.5, y=y + 2, z=z + 11.5, yaw=180, pitch=0)
            cameras["inside-air"] = dict(x=x + 10.5, y=y + 2, z=z + 11.5, yaw=180, pitch=0)
        elif name == "local-emitter":
            commands.extend([
                f"fill {x + 3} {y + 1} {z + 12} {x + 5} {y + 3} {z + 14} minecraft:glowstone",
                f"fill {x + 10} {y + 1} {z + 12} {x + 12} {y + 3} {z + 14} minecraft:glowstone",
                f"fill {x + 9} {y + 1} {z + 10} {x + 13} {y + 7} {z + 10} minecraft:stone"])
        elif name == "open-water-basin":
            commands.extend([
                f"fill {x + 2} {y + 1} {z + 2} {x + 14} {y + 6} {z + 14} minecraft:smooth_sandstone",
                f"fill {x + 3} {y + 2} {z + 3} {x + 13} {y + 7} {z + 13} minecraft:air",
                f"fill {x + 3} {y + 2} {z + 3} {x + 13} {y + 5} {z + 13} minecraft:water"])
            cameras["front"] = dict(x=x + 8.5, y=y + 8, z=z + 1, yaw=0, pitch=35)
            cameras["below-surface"] = dict(x=x + 8.5, y=y + 2, z=z + 8.5, yaw=0, pitch=-75)
            cameras["outside-above"] = dict(x=x + 8.5, y=y + 10, z=z + 8.5, yaw=0, pitch=75)
        result.append({"name": name, "bounds": [x, y, z, x + 16, y + 12, z + 16],
                       "cameras": cameras, "commands": commands})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--copied-world", required=True, help="Exact disposable save directory name")
    parser.add_argument("--origin", nargs=3, type=int, required=True, metavar=("X", "Y", "Z"),
                        help="Five 17x13x17 regions spaced 48 blocks apart are overwritten here")
    parser.add_argument("--session", default="run/caustica-debug/session.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build", action="store_true", help="Apply the recipes; omission writes the plan only")
    args = parser.parse_args()
    recipe = {"copiedWorld": args.copied_world, "fixtures": fixtures(args.origin), "applied": []}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    def save():
        args.output.write_text(json.dumps(recipe, indent=2), encoding="utf-8")
    save()
    if args.build:
        client = Client(args.session, 180)
        initial = client.call("status")
        validate_world(initial, args.copied_world)
        recipe["initial"] = initial
        player = initial["player"]
        gamemode = client.call("command", command="data get entity @s playerGameType")["result"]
        try:
            client.call("input.set")
            client.call("command", command="gamemode spectator")
            for fixture in recipe["fixtures"]:
                camera = fixture["cameras"]["above"]
                client.call("command", command=f"tp @s {camera['x']} {camera['y']} {camera['z']}")
                client.call("wait", ticks=60, timeoutMs=180000)
                for command in fixture["commands"]:
                    result = apply_block_command(client, command)
                    recipe["applied"].append({"command": command, "result": result})
                    save()
        finally:
            recipe["cleanupErrors"] = []
            cleanup = [("input.set", {"flyingSpeed": player["currentFlyingSpeed"]}),
                       ("command", {"command": f"tp @s {player['x']} {player['y']} {player['z']} {player['yaw']} {player['pitch']}"}),
                       ("command", {"command": "gamemode " + ["survival", "creative", "adventure", "spectator"][gamemode]})]
            for op, arguments in cleanup:
                try:
                    client.call(op, **arguments)
                except Exception as error:
                    recipe["cleanupErrors"].append({"op": op, "error": str(error)})
            save()
            if recipe["cleanupErrors"]:
                raise RuntimeError(f"Cleanup failed: {recipe['cleanupErrors']}")


if __name__ == "__main__":
    main()
