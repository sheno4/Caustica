"""Small HTTP client for the opt-in Caustica debug service; usable from scripts or CLI."""

import argparse
import json
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen


class Client:
    def __init__(self, session="run/caustica-debug/session.json", timeout=60):
        self.session = json.loads(Path(session).read_text(encoding="utf-8"))
        self.timeout = timeout

    def request(self, op, **arguments):
        request = Request(
            self.session["baseUrl"].rstrip("/") + "/api",
            data=json.dumps({"op": op, **arguments}).encode(),
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer " + self.session["token"]},
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:
                result = json.load(response)
        except HTTPError as error:
            raise RuntimeError(error.read().decode()) from error
        if not result["ok"]:
            raise RuntimeError(result.get("error", result))
        return result

    def call(self, op, **arguments):
        response = self.request(op, **arguments)
        if "jobId" not in response:
            return response.get("result")
        job_id = response["jobId"]
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            job = self.request("job", jobId=job_id)["result"]
            if job["state"] == "completed":
                return job.get("result")
            if job["state"] == "failed":
                raise RuntimeError(job.get("error", job))
            time.sleep(0.05)
        raise TimeoutError(f"Job {job_id} did not finish within {self.timeout}s; query op=job for its state")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", default="run/caustica-debug/session.json")
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("op", help="Operation, e.g. status, command, screenshot, jfr.start")
    parser.add_argument("arguments", nargs="?", default="{}", help="JSON argument object")
    parser.add_argument("--json-file", type=Path, help="Read arguments from a UTF-8 JSON file")
    args = parser.parse_args()
    arguments = json.loads(args.json_file.read_text(encoding="utf-8") if args.json_file else args.arguments)
    result = Client(args.session, args.timeout).call(args.op, **arguments)
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
