#!/usr/bin/env python3
"""Generate the dashboard's mock synced-records file.

This is a Python mirror of the Android certificate signer. It exists because the
dashboard's Verify page has to be able to *actually* verify — a hand-written
`signatureHash` would fail every check and the demo would prove nothing. So the
hashes in `public/mock/records.json` are real SHA-256 digests over the same
fields, in the same order, joined by the same separator, with the same salt as
`app/src/main/java/com/minesafear/certificate/CertificateSigner.kt`.

If that file changes, change this one.

Usage:
    python3 tools/generate_mock_data.py
    python3 tools/generate_mock_data.py --now 2026-09-01   # reproducible output

Issue dates are generated *relative to the run date* so the "expiring in the
next 30 days" bucket is never empty at demo time. Re-run it whenever the data
starts to look stale; every hash is recomputed, so the file stays internally
consistent.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import pathlib
import random
import uuid

# --- The signing mirror -------------------------------------------------------
# Keep in lockstep with CertificateSigner.kt.

LOCAL_SIGNING_SALT = "MineSafeAR/offline-prototype/v1/not-a-real-secret"
# ASCII unit separator, written as an escape because a raw control character
# in source is invisible and one stray edit would silently change every hash.
FIELD_SEPARATOR = "\u001f"


def signature(cert_id: str, user_id: str, score: int, issued_date: int) -> str:
    """SHA-256 of cert_id + user_id + score + issued_date + salt, lowercase hex.

    Expiry is deliberately not an input: it is derived from issued_date, and the
    verifier re-derives it rather than trusting the value it was handed. That is
    what makes a stretched expiry detectable.
    """
    joined = FIELD_SEPARATOR.join(
        [cert_id, user_id, str(score), str(issued_date), LOCAL_SIGNING_SALT]
    )
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()


VALIDITY_DAYS = 365
VALIDITY_MILLIS = VALIDITY_DAYS * 24 * 60 * 60 * 1000
PASS_THRESHOLD = 80  # ScoringEngine.PASS_THRESHOLD_PERCENT

DAY_MS = 24 * 60 * 60 * 1000

# --- Reference data -----------------------------------------------------------
# Collieries are invented. They are named in the register of a real coal belt
# (Jharkhand / Odisha / West Bengal) so the dashboard reads plausibly, but no
# operating mine's safety record is being represented here.

SITES = [
    {
        "id": "site_dhanwar_east",
        "name": "Dhanwar East Opencast",
        "shortName": "Dhanwar East OC",
        "district": "Bokaro",
        "state": "Jharkhand",
    },
    {
        "id": "site_kesla_4",
        "name": "Kesla No. 4 Underground",
        "shortName": "Kesla No. 4 UG",
        "district": "Dhanbad",
        "state": "Jharkhand",
    },
    {
        "id": "site_sitalpur",
        "name": "Sitalpur Washery",
        "shortName": "Sitalpur Washery",
        "district": "Paschim Bardhaman",
        "state": "West Bengal",
    },
    {
        "id": "site_bhojudih",
        "name": "Bhojudih Incline",
        "shortName": "Bhojudih Incline",
        "district": "Angul",
        "state": "Odisha",
    },
]

# `status` records what is actually in the APK today, so the dashboard cannot
# imply the app ships four modules. Only fire_explosion_response is built;
# gas_leak_protocol is specified but not yet written, and is marked live here
# because the register is demo data for the state the app is heading towards.
# `required` drives the syllabus-coverage advisory on the worker detail view.
MODULES = [
    {
        "id": "fire_explosion_response",
        "name": "Fire & explosion response",
        "status": "live",
        "required": True,
    },
    {
        "id": "gas_leak_protocol",
        "name": "Gas leak & confined space protocol",
        "status": "live",
        "required": True,
    },
    {
        "id": "machinery_lockout",
        "name": "Machinery lockout & tagout",
        "status": "planned",
        "required": False,
    },
    {
        "id": "ground_support_awareness",
        "name": "Ground support & roof awareness",
        "status": "planned",
        "required": False,
    },
]

LIVE_MODULES = [m["id"] for m in MODULES if m["status"] == "live"]

# Ordinary, common names from the coal belt. Nobody in particular.
NAMES = [
    "Ramesh Mahato",
    "Sunita Devi",
    "Birsa Murmu",
    "Abdul Rashid",
    "Pradeep Kumar Singh",
    "Mangal Soren",
    "Kavita Kumari",
    "Sanjay Bauri",
    "Dilip Mandal",
    "Anita Hembram",
    "Rajesh Prasad",
    "Sukhram Oraon",
    "Farida Khatun",
    "Nitesh Chauhan",
    "Bishnu Charan Pradhan",
    "Lakhan Turi",
    "Meena Kisku",
    "Arvind Yadav",
    "Sushil Ganguly",
    "Phulmani Baske",
    "Md. Sabir Ansari",
    "Gopal Rana",
    "Rekha Sahu",
    "Jitendra Ravidas",
]

ROLES = [
    "Dumper operator",
    "Mining sirdar",
    "Shift overman",
    "Support fitter",
    "Belt attendant",
    "Winding engine driver",
    "Blasting assistant",
    "Pump khalasi",
    "Trammer",
    "Electrician",
    "Gas testing officer",
    "Timber mistry",
]

LANGUAGES = ["en", "hi", "sat"]

# One archetype per worker, in order, so the register always contains a useful
# spread: certified, about to lapse, lapsed, mid-training, never started, plus
# the two whose certificate predates a module being added to the syllabus.
#
#   certified   both modules passed, certificate comfortably in date
#   expiring    valid, but inside the 30-day window the Overview counts
#   lapsed      past expiry
#   partial     certified on one module only — the CertificateIssuer gap
#   progress    has attempts, no certificate
#   fresh       no attempts at all
ARCHETYPES = [
    "certified", "certified", "expiring", "lapsed", "certified", "progress",
    "certified", "expiring", "certified", "fresh", "certified", "lapsed",
    "partial", "certified", "expiring", "progress", "certified", "certified",
    "lapsed", "progress", "partial", "expiring", "fresh", "progress",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--now",
        help="ISO date (YYYY-MM-DD) to treat as today, for reproducible output.",
    )
    parser.add_argument(
        "--out",
        default=None,
        help="Output path. Defaults to ../public/mock/records.json.",
    )
    return parser.parse_args()


def module_result(rng: random.Random, worker_id: str, module_id: str, score: int,
                  when_ms: int) -> dict:
    """One drill attempt, in ModuleResultDto shape.

    Tap counts are illustrative rather than a faithful replay of the drill: the
    dashboard only displays them. Two correct taps is what module 1 asks for
    (right extinguisher, right exit), and wrong taps are worked back from the
    score so the numbers on screen never contradict each other.
    """
    incorrect = max(0, round((100 - score) / 20))
    return {
        "id": str(uuid.UUID(int=rng.getrandbits(128), version=4)),
        "moduleId": module_id,
        "userId": worker_id,
        "score": score,
        "timestamp": when_ms,
        "passed": score >= PASS_THRESHOLD,
        "durationSeconds": rng.randint(48, 265),
        "correctTaps": 2,
        "incorrectTaps": incorrect,
    }


def best_scores(results: list[dict]) -> dict[str, int]:
    """Best attempt per module. Mirrors CertificateIssuer."""
    best: dict[str, int] = {}
    for result in results:
        module_id = result["moduleId"]
        if result["score"] > best.get(module_id, -1):
            best[module_id] = result["score"]
    return best


def average(scores: list[int]) -> int:
    """Floored integer mean, as CertificateIssuer computes it."""
    return sum(scores) // len(scores) if scores else 0


def build(now_ms: int, rng: random.Random) -> dict:
    workers = []

    for index, (name, archetype) in enumerate(zip(NAMES, ARCHETYPES)):
        site = SITES[index % len(SITES)]
        worker_id = str(uuid.UUID(int=rng.getrandbits(128), version=4))
        code_prefix = "".join(part[0] for part in site["shortName"].split()[:2]).upper()

        results: list[dict] = []
        certificate = None

        if archetype == "fresh":
            pass

        elif archetype == "progress":
            # Attempts that did not get there: either one module short, or a
            # failing score on the second.
            attempted = LIVE_MODULES if rng.random() < 0.5 else LIVE_MODULES[:1]
            for module_id in attempted:
                score = rng.choice([42, 55, 61, 68, 74, 79])
                results.append(
                    module_result(rng, worker_id, module_id, score,
                                  now_ms - rng.randint(3, 40) * DAY_MS)
                )

        else:
            if archetype == "partial":
                attempted = LIVE_MODULES[:1]
            else:
                attempted = LIVE_MODULES

            if archetype == "expiring":
                issued_ago_days = rng.randint(337, 361)
            elif archetype == "lapsed":
                issued_ago_days = rng.randint(372, 505)
            else:
                issued_ago_days = rng.randint(12, 240)

            issued_date = now_ms - issued_ago_days * DAY_MS

            for module_id in attempted:
                # Some workers needed two goes. The first attempt is a fail, so
                # "best attempt" and the floored average are actually exercised.
                if rng.random() < 0.35:
                    results.append(
                        module_result(rng, worker_id, module_id,
                                      rng.choice([58, 64, 71, 77]),
                                      issued_date - rng.randint(4, 15) * DAY_MS)
                    )
                results.append(
                    module_result(rng, worker_id, module_id,
                                  rng.choice([80, 83, 85, 88, 90, 92, 95, 100]),
                                  issued_date - rng.randint(0, 3) * DAY_MS)
                )

            best = best_scores(results)
            passed = sorted(m for m, s in best.items() if s >= PASS_THRESHOLD)
            score = average(list(best.values()))

            certificate = {
                "certId": str(uuid.UUID(int=rng.getrandbits(128), version=4)),
                "userId": worker_id,
                "userName": name,
                "score": score,
                "modulesCompleted": passed,
                "issuedDate": issued_date,
                "expiryDate": issued_date + VALIDITY_MILLIS,
            }
            certificate["signatureHash"] = signature(
                certificate["certId"], worker_id, score, issued_date
            )

        workers.append({
            "workerId": worker_id,
            "employeeCode": f"{code_prefix}-{1000 + index * 37}",
            "fullName": name,
            "siteId": site["id"],
            "jobRole": ROLES[index % len(ROLES)],
            "preferredLanguage": LANGUAGES[index % len(LANGUAGES)],
            "moduleResults": results,
            "certificate": certificate,
        })

    anomalies = plant_anomalies(workers, now_ms)

    return {
        "generatedAtMillis": now_ms,
        "generatedAt": dt.datetime.fromtimestamp(
            now_ms / 1000, dt.timezone.utc
        ).isoformat(timespec="seconds"),
        "note": (
            "Synthetic demo data for the MineSafeAR admin dashboard. Signature "
            "hashes are genuine SHA-256 digests produced by "
            "tools/generate_mock_data.py, which mirrors CertificateSigner.kt, so "
            "the Verify page really does verify. Certification status is not "
            "stored here — the dashboard derives it from the certificate on "
            "record. Two certificates have been altered on purpose; see "
            "plantedAnomalies."
        ),
        "plantedAnomalies": anomalies,
        "sites": SITES,
        "modules": MODULES,
        "workers": workers,
    }


def plant_anomalies(workers: list[dict], now_ms: int) -> list[dict]:
    """Break two certificates on purpose, so the integrity check has work to do.

    Both edits happen *after* signing, which is the whole point: this is what a
    card altered after issue looks like on the wire, and it is what the Verify
    page has to catch.
    """
    planted = []
    certified = [w for w in workers if w["certificate"]]

    # 1. Score raised after signing. The hash still covers the original score,
    #    so CertificateVerifier answers SIGNATURE_MISMATCH.
    target = next(
        w for w in certified
        if w["certificate"]["expiryDate"] > now_ms
    )
    cert = target["certificate"]
    original = cert["score"]
    cert["score"] = min(100, original + 7)
    planted.append({
        "certId": cert["certId"],
        "workerName": target["fullName"],
        "kind": "SIGNATURE_MISMATCH",
        "detail": (
            f"score edited from {original} to {cert['score']} after signing; "
            "the hash still covers the original"
        ),
    })

    # 2. Expiry stretched by 180 days on a certificate that had already lapsed,
    #    so it now reads as in-date. The signature still matches, because expiry
    #    is not one of its inputs — the verifier catches this only because it
    #    re-derives expiry from the issue date instead of trusting the value it
    #    was handed. This is the most useful anomaly in the set: it is the one a
    #    naive verifier would wave through.
    target = next(
        w for w in certified
        if w["certificate"]["expiryDate"] <= now_ms
    )
    cert = target["certificate"]
    was = cert["expiryDate"]
    cert["expiryDate"] = cert["issuedDate"] + VALIDITY_MILLIS + 180 * DAY_MS
    planted.append({
        "certId": cert["certId"],
        "workerName": target["fullName"],
        "kind": "EXPIRY_ALTERED",
        "detail": (
            "a lapsed certificate's expiry was pushed out by 180 days so it "
            "reads as current; the signature still matches because expiry is "
            "not signed, so the verifier re-derives it"
        ),
        "expiryWas": was,
        "expiryNow": cert["expiryDate"],
    })

    return planted


def main() -> None:
    args = parse_args()

    if args.now:
        day = dt.date.fromisoformat(args.now)
        now_ms = int(
            dt.datetime(day.year, day.month, day.day, 9, 30,
                        tzinfo=dt.timezone.utc).timestamp() * 1000
        )
    else:
        now_ms = int(dt.datetime.now(dt.timezone.utc).timestamp() * 1000)

    # Fixed seed: the register is the same register every run, only the dates
    # (and therefore the hashes) move.
    rng = random.Random(20260901)

    data = build(now_ms, rng)

    here = pathlib.Path(__file__).resolve().parent
    out = pathlib.Path(args.out) if args.out else here.parent / "public" / "mock" / "records.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    certs = [w for w in data["workers"] if w["certificate"]]
    print(f"Wrote {out}")
    print(f"  {len(data['workers'])} workers, {len(certs)} certificates, "
          f"{sum(len(w['moduleResults']) for w in data['workers'])} drill attempts")
    for anomaly in data["plantedAnomalies"]:
        print(f"  planted {anomaly['kind']}: {anomaly['workerName']} "
              f"({anomaly['certId'][:8]}…)")


if __name__ == "__main__":
    main()
