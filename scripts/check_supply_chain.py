#!/usr/bin/env python3
"""Evaluate the actual CycloneDX SBOM against OSV and reviewed license policy.

Network failures, unknown licenses and expired exceptions fail closed. The report
records query time; OSV does not provide a global database snapshot timestamp.
No dependency list or clean result is inferred from the source POM.
"""
import argparse
import datetime as dt
import hashlib
import json
import re
import urllib.parse
import urllib.request
from pathlib import Path

OSV = "https://api.osv.dev/v1/"


def license_expression(expression, allowed):
    """Parse SPDX boolean syntax; WITH binds before AND, which binds before OR."""
    if not isinstance(expression, str) or not expression.strip():
        raise ValueError("empty license expression")
    tokens = re.findall(r"\(|\)|[A-Za-z0-9][A-Za-z0-9.:+\-]*", expression)
    if "".join(tokens) != re.sub(r"\s+", "", expression):
        raise ValueError("invalid SPDX license expression characters")
    position = 0

    def atom():
        nonlocal position
        if position >= len(tokens):
            raise ValueError("incomplete SPDX expression")
        token = tokens[position]
        position += 1
        if token == "(":
            result = either()
            if position >= len(tokens) or tokens[position] != ")":
                raise ValueError("unbalanced SPDX parentheses")
            position += 1
            return result
        if token in {"AND", "OR", "WITH", ")"} or "+" in token[:-1]:
            raise ValueError("invalid SPDX license identifier")
        if position < len(tokens) and tokens[position] == "WITH":
            position += 1
            if position >= len(tokens) or tokens[position] in {"AND", "OR", "WITH", "(", ")"}:
                raise ValueError("WITH requires a license exception identifier")
            exception = tokens[position]
            position += 1
            if "+" in exception:
                raise ValueError("invalid SPDX exception identifier")
            token += " WITH " + exception
        return token in allowed

    def both():
        nonlocal position
        result = atom()
        while position < len(tokens) and tokens[position] == "AND":
            position += 1
            right = atom()
            result = result and right
        return result

    def either():
        nonlocal position
        result = both()
        while position < len(tokens) and tokens[position] == "OR":
            position += 1
            right = both()
            result = result or right
        return result

    result = either()
    if position != len(tokens):
        raise ValueError("unexpected SPDX license token")
    return result


def request_json(endpoint, payload=None, evidence=None):
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(OSV + endpoint, data=data,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        raw = response.read()
        if evidence:
            evidence.record(endpoint, data, raw, response.status)
        return json.loads(raw)


class HttpEvidence:
    """Keep the actual public OSV exchanges from one scan, without appending runs."""

    def __init__(self, directory):
        self.directory = directory
        directory.mkdir(parents=True, exist_ok=False)
        self.records = []

    def record(self, endpoint, request, response, status):
        prefix = f"{len(self.records) + 1:04d}"
        request = request or b""
        for suffix, raw in [("request.json", request), ("response.json", response)]:
            (self.directory / f"{prefix}-{suffix}").write_bytes(raw)
        self.records.append({"endpoint": OSV + endpoint, "httpStatus": status,
                             "receivedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                             "request": f"{prefix}-request.json", "response": f"{prefix}-response.json",
                             "requestSha256": hashlib.sha256(request).hexdigest(),
                             "responseSha256": hashlib.sha256(response).hexdigest()})
        (self.directory / "index.json").write_text(json.dumps(self.records, indent=2) + "\n")

    def fetch(self, endpoint, payload=None):
        return request_json(endpoint, payload, self)


def osv_timestamp(value, field):
    if not isinstance(value, str):
        raise ValueError(f"malformed OSV {field} timestamp")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("timezone missing")
    except ValueError as error:
        raise ValueError(f"malformed OSV {field} timestamp") from error
    return value


def components(sbom, first_party=()):
    if sbom.get("bomFormat") != "CycloneDX" or not sbom.get("components"):
        raise ValueError("a populated CycloneDX SBOM is required")
    result = {}
    pending = list(sbom["components"])
    while pending:
        component = pending.pop(0)
        if not isinstance(component, dict):
            raise ValueError("malformed SBOM component")
        nested = component.get("components", [])
        if not isinstance(nested, list):
            raise ValueError("malformed nested SBOM components")
        pending.extend(nested)
        purl = component.get("purl", "")
        if not purl.startswith("pkg:maven/"):
            raise ValueError(f"unsupported component identity: {purl or component.get('name')}")
        identity = urllib.parse.unquote(purl[10:].split("?", 1)[0].split("#", 1)[0])
        package, separator, version = identity.rpartition("@")
        group, slash, name = package.rpartition("/")
        if separator and slash and (group + ":" + name, version) in first_party:
            continue
        if not separator or not slash or not version or "SNAPSHOT" in version.upper():
            raise ValueError(f"external dependency must have a fixed released version: {purl}")
        if not group or not name or not version:
            raise ValueError("incomplete Maven package identity")
        key = (group + ":" + name, version)
        if key in result and result[key].get("licenses", []) != component.get("licenses", []):
            raise ValueError(f"conflicting license metadata for {key}")
        result[key] = component
    if not result:
        raise ValueError("SBOM has no external Maven dependencies")
    return result


def exemption(policy, package, version, kind, finding, today):
    for rule in policy.get("exceptions", []):
        if (rule.get("package"), rule.get("version"), rule.get("kind"), rule.get("finding")) != (
                package, version, kind, finding):
            continue
        if not rule.get("owner") or not rule.get("reason") or not rule.get("evidence"):
            raise ValueError("exception needs owner, reason and evidence")
        expiry = dt.date.fromisoformat(rule["expires"])
        if expiry < today or expiry > today + dt.timedelta(days=90):
            raise ValueError("exception expired or exceeds the 90-day review window")
        reviewed = dt.date.fromisoformat(rule.get("reviewedOn", ""))
        if reviewed > today or expiry > reviewed + dt.timedelta(days=90):
            raise ValueError("exception needs a non-future review and at most 90 days from that review")
        return rule
    return None


def reviewed_license(policy, package, version, declared, today, project_root):
    """Exact artifact metadata corrections are evidence, never a wildcard waiver."""
    for rule in policy.get("licenseDeclarations", []):
        if (rule.get("package"), rule.get("version"), rule.get("declared")) != (package, version, declared):
            continue
        if not rule.get("owner") or not rule.get("reason") or not rule.get("evidence"):
            raise ValueError("license metadata review needs owner, reason and evidence")
        reviewed = dt.date.fromisoformat(rule.get("reviewedOn", ""))
        expiry = dt.date.fromisoformat(rule.get("expires", ""))
        if reviewed > today or expiry < today or expiry > reviewed + dt.timedelta(days=90):
            raise ValueError("license metadata review expired or exceeds 90 days")
        if not isinstance(rule.get("expression"), str):
            raise ValueError("license metadata review needs an SPDX expression")
        approval = rule.get("approvedExpression")
        if approval:
            if approval != rule["expression"] or not rule.get("projectLicense"):
                raise ValueError("conditional license approval needs an exact expression and project license basis")
            basis = rule["projectLicense"]
            if not isinstance(basis, dict) or basis.get("id") != "MIT":
                raise ValueError("unsupported project license basis")
            relative = Path(basis.get("path", ""))
            root = project_root.resolve()
            path = (root / relative).resolve()
            if relative.is_absolute() or not path.is_relative_to(root) or not path.is_file():
                raise ValueError("project license basis must be a repository file")
            raw = path.read_bytes()
            if hashlib.sha256(raw).hexdigest() != basis.get("sha256") or not raw.startswith(b"MIT License\n"):
                raise ValueError("project license basis changed; conditional approval must be reviewed")
        return rule
    return None


def evaluate(sbom, policy, fetch=request_json, today=None, project_root=None):
    today = today or dt.datetime.now(dt.timezone.utc).date()
    project_root = project_root or Path(__file__).resolve().parent.parent
    if policy.get("schemaVersion") != 1:
        raise ValueError("unsupported supply-chain policy")
    first_party = set()
    for component in policy.get("firstPartyComponents", []):
        if (not isinstance(component, dict) or set(component) != {"package", "version"}
                or not isinstance(component["package"], str)
                or not component["package"].startswith("com.robot:")
                or not isinstance(component["version"], str) or not component["version"]):
            raise ValueError("first-party identities must name exact Robot module versions")
        first_party.add((component["package"], component["version"]))
    dependencies = components(sbom, first_party)
    allowed = set(policy.get("allowedLicenses", []))
    findings = []
    license_reviews = []
    for (package, version), component in dependencies.items():
        licenses = []
        for entry in component.get("licenses", []):
            licenses.append(entry.get("expression") or entry.get("license", {}).get("id")
                            or entry.get("license", {}).get("name") or "unknown")
        reviewed = reviewed_license(policy, package, version, licenses, today, project_root)
        expressions = [reviewed["expression"]] if reviewed else licenses
        accepted_licenses = allowed | ({reviewed["approvedExpression"]}
                                      if reviewed and reviewed.get("approvedExpression") else set())
        if reviewed:
            license_reviews.append({"package": package, "version": version, "review": reviewed})
        accepted = bool(licenses)
        license_error = None
        # Multiple unordered license entries do not prove an OR grant; accept all
        # declared obligations unless an exact package review supplies an expression.
        for license_id in expressions:
            try:
                accepted = license_expression(license_id, accepted_licenses) and accepted
            except ValueError as error:
                accepted = False
                license_error = str(error)
        if not accepted:
            identifier = " | ".join(licenses) or "unknown"
            findings.append({"kind": "license", "package": package, "version": version,
                             "finding": identifier,
                             "interpretation": "all declared license entries required",
                             "parseError": license_error,
                             "exception": exemption(policy, package, version, "license", identifier, today)})
    items = list(dependencies)
    modified = []
    for offset in range(0, len(items), 100):
        batch = items[offset:offset + 100]
        pending = [(package, version, None) for package, version in batch]
        seen_tokens, seen_findings = set(), set()
        while pending:
            queries = []
            for package, version, token in pending:
                query = {"package": {"ecosystem": "Maven", "name": package}, "version": version}
                if token:
                    query["page_token"] = token
                queries.append(query)
            result = fetch("querybatch", {"queries": queries})
            if not isinstance(result, dict):
                raise ValueError("malformed OSV batch response")
            responses = result.get("results")
            if not isinstance(responses, list) or len(responses) != len(pending):
                raise ValueError("OSV returned incomplete dependency coverage")
            following = []
            for (package, version, _previous), response in zip(pending, responses):
                if not isinstance(response, dict) or set(response) - {"vulns", "next_page_token"}:
                    raise ValueError("malformed OSV result")
                vulnerabilities = response.get("vulns", [])
                if not isinstance(vulnerabilities, list):
                    raise ValueError("OSV vulns must be an array")
                token = response.get("next_page_token")
                if token is not None:
                    if not isinstance(token, str) or not token or (package, version, token) in seen_tokens:
                        raise ValueError("malformed or repeated OSV page token")
                    seen_tokens.add((package, version, token))
                    if len(seen_tokens) > 10000:
                        raise ValueError("OSV pagination exceeded bounded complete-scan budget")
                    following.append((package, version, token))
                for vulnerability in vulnerabilities:
                    if not isinstance(vulnerability, dict) or not isinstance(vulnerability.get("id"), str) or not vulnerability["id"]:
                        raise ValueError("malformed OSV vulnerability identity")
                    identifier = vulnerability["id"]
                    if (package, version, identifier) in seen_findings:
                        continue
                    seen_findings.add((package, version, identifier))
                    detail = fetch("vulns/" + urllib.parse.quote(identifier, safe=""))
                    if not isinstance(detail, dict) or detail.get("id") != identifier:
                        raise ValueError("OSV detail identity mismatch")
                    if detail.get("withdrawn"):
                        osv_timestamp(detail["withdrawn"], "withdrawn")
                        continue
                    modified.append(osv_timestamp(detail.get("modified"), "modified"))
                    affected = detail.get("affected")
                    if not isinstance(affected, list) or not affected or not all(isinstance(row, dict) for row in affected):
                        raise ValueError("OSV advisory affected package coverage is incomplete")
                    if not any(row.get("package", {}).get("name") == package
                               and row.get("package", {}).get("ecosystem") == "Maven" for row in affected):
                        raise ValueError("OSV advisory package identity does not match query")
                    for field in ["aliases", "severity", "references"]:
                        if not isinstance(detail.get(field, []), list):
                            raise ValueError(f"malformed OSV {field}")
                    findings.append({"kind": "vulnerability", "package": package, "version": version,
                                     "finding": identifier, "aliases": detail.get("aliases", []),
                                     "severity": detail.get("severity", []), "modified": detail.get("modified"),
                                     "references": detail.get("references", []), "affected": detail.get("affected", []),
                                     "exception": exemption(policy, package, version, "vulnerability", identifier, today)})
            pending = following
    blockers = [finding for finding in findings if finding["exception"] is None]
    return {"schemaVersion": 1, "source": OSV, "queriedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "databaseSnapshotAt": None, "advisoryLatestModified": max(filter(None, modified), default=None),
            "dependenciesChecked": len(dependencies), "coverage": "complete",
            "findings": findings, "licenseMetadataReviews": license_reviews,
            "blockers": len(blockers), "ok": not blockers}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sbom", type=Path, default=Path("target/bom.json"))
    parser.add_argument("--policy", type=Path, default=Path("quality/supply-chain-policy.json"))
    parser.add_argument("--output", type=Path, default=Path("target/verification/supply-chain.json"))
    parser.add_argument("--evidence-dir", type=Path, help="new directory for raw public OSV exchanges")
    args = parser.parse_args()
    inputs = {}
    try:
        sbom_data, policy_data = args.sbom.read_bytes(), args.policy.read_bytes()
        inputs = {"sbom": {"path": str(args.sbom), "sha256": hashlib.sha256(sbom_data).hexdigest()},
                  "policy": {"path": str(args.policy), "sha256": hashlib.sha256(policy_data).hexdigest()}}
        evidence_dir = args.evidence_dir or args.output.with_name(args.output.stem + "-osv")
        evidence = HttpEvidence(evidence_dir)
        inputs["osvEvidence"] = {"path": str(evidence_dir)}
        report = evaluate(json.loads(sbom_data), json.loads(policy_data), evidence.fetch)
    except Exception as error:
        report = {"schemaVersion": 1, "ok": False, "coverage": "incomplete",
                  "queriedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                  "error": f"{type(error).__name__}: {error}"}
    report["inputs"] = inputs
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: report.get(key) for key in ("ok", "coverage", "dependenciesChecked", "blockers", "error")}))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
