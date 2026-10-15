import datetime as dt
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from check_supply_chain import HttpEvidence, evaluate, license_expression


class SupplyChainTest(unittest.TestCase):
    def setUp(self):
        self.bom = {"bomFormat": "CycloneDX", "components": [{"purl": "pkg:maven/org.example/demo@1.0.0",
                    "licenses": [{"license": {"id": "Apache-2.0"}}]}]}
        self.policy = {"schemaVersion": 1, "allowedLicenses": ["Apache-2.0"], "exceptions": []}
        self.today = dt.date(2026, 10, 14)

    def test_actual_query_covers_every_component(self):
        def fetch(endpoint, payload):
            self.assertEqual("querybatch", endpoint)
            self.assertEqual("org.example:demo", payload["queries"][0]["package"]["name"])
            self.assertEqual("1.0.0", payload["queries"][0]["version"])
            return {"results": [{}]}
        self.assertTrue(evaluate(self.bom, self.policy, fetch, self.today)["ok"])

    def test_incomplete_response_does_not_pass(self):
        with self.assertRaisesRegex(ValueError, "incomplete"):
            evaluate(self.bom, self.policy, lambda *args: {"results": []}, self.today)

    def test_unknown_license_is_blocked(self):
        self.bom["components"][0]["licenses"] = []
        self.assertFalse(evaluate(self.bom, self.policy, lambda *args: {"results": [{}]}, self.today)["ok"])

    def test_network_error_is_not_an_empty_clean_scan(self):
        def broken(*args):
            raise TimeoutError("offline")
        with self.assertRaises(TimeoutError):
            evaluate(self.bom, self.policy, broken, self.today)

    def test_vulnerability_and_expired_waiver_block(self):
        def fetch(endpoint, payload=None):
            return {"results": [{"vulns": [{"id": "OSV-test"}]}]} if endpoint == "querybatch" else {"id": "OSV-test", "modified": "2026-10-01T00:00:00Z", "affected": [{"package": {"ecosystem": "Maven", "name": "org.example:demo"}}]}
        self.assertFalse(evaluate(self.bom, self.policy, fetch, self.today)["ok"])
        self.policy["exceptions"] = [{"package": "org.example:demo", "version": "1.0.0", "kind": "vulnerability",
                                      "finding": "OSV-test", "owner": "maintainer", "reason": "reviewed",
                                      "evidence": "test", "expires": "2026-10-13"}]
        with self.assertRaisesRegex(ValueError, "expired"):
            evaluate(self.bom, self.policy, fetch, self.today)

    def test_external_snapshot_is_blocked(self):
        self.bom["components"][0]["purl"] = "pkg:maven/org.example/demo@1-SNAPSHOT"
        with self.assertRaisesRegex(ValueError, "fixed released"):
            evaluate(self.bom, self.policy, lambda *args: {}, self.today)

    def test_spdx_precedence_parentheses_and_with_are_not_flat_strings(self):
        allowed = {"MIT", "Apache-2.0", "GPL-2.0-only WITH Classpath-exception-2.0"}
        self.assertTrue(license_expression("MIT OR GPL-3.0-only AND Apache-2.0", allowed))
        self.assertFalse(license_expression("(MIT OR Apache-2.0) AND GPL-3.0-only", allowed))
        self.assertTrue(license_expression("GPL-2.0-only WITH Classpath-exception-2.0", allowed))
        self.assertFalse(license_expression("GPL-2.0-only", allowed))

    def test_spdx_malformed_remainder_and_with_do_not_pass(self):
        for expression in ["MIT OR", "MIT AND (Apache-2.0", "MIT Apache-2.0", "MIT WITH", "(MIT OR Apache-2.0) WITH Classpath-exception-2.0", "MIT || Apache-2.0"]:
            with self.subTest(expression=expression), self.assertRaises(ValueError):
                license_expression(expression, {"MIT", "Apache-2.0"})

    def test_multiple_license_ids_do_not_invent_an_or_grant(self):
        self.bom["components"][0]["licenses"] = [{"license": {"id": "Apache-2.0"}}, {"license": {"id": "GPL-3.0-only"}}]
        self.assertFalse(evaluate(self.bom, self.policy, lambda *args: {"results": [{}]}, self.today)["ok"])

    def test_each_query_follows_its_own_pagination_token(self):
        calls = []
        def fetch(endpoint, payload=None):
            calls.append((endpoint, payload))
            if endpoint == "querybatch" and "page_token" not in payload["queries"][0]:
                return {"results": [{"next_page_token": "page-2"}]}
            if endpoint == "querybatch":
                self.assertEqual(payload["queries"][0]["page_token"], "page-2")
                return {"results": [{"vulns": [{"id": "OSV-second-page"}]}]}
            return {"id": "OSV-second-page", "modified": "2026-10-01T00:00:00Z", "affected": [{"package": {"ecosystem": "Maven", "name": "org.example:demo"}}]}
        result = evaluate(self.bom, self.policy, fetch, self.today)
        self.assertFalse(result["ok"])
        self.assertEqual(len(calls), 3)

    def test_repeated_pagination_token_is_incomplete_instead_of_loop_or_clean(self):
        with self.assertRaisesRegex(ValueError, "repeated"):
            evaluate(self.bom, self.policy, lambda *args: {"results": [{"next_page_token": "same"}]}, self.today)

    def test_malformed_vulnerability_arrays_and_error_records_fail(self):
        for response in [{"vulns": {}}, {"vulns": None}, {"vulns": "empty"}, {"error": "unsupported"}, {"vulns": [{"id": None}]}]:
            with self.subTest(response=response), self.assertRaises(ValueError):
                evaluate(self.bom, self.policy, lambda *args: {"results": [response]}, self.today)

    def test_unknown_withdrawn_shape_cannot_hide_a_finding(self):
        def fetch(endpoint, payload=None):
            return {"results": [{"vulns": [{"id": "OSV-test"}]}]} if endpoint == "querybatch" else {"id": "OSV-test", "withdrawn": True}
        with self.assertRaisesRegex(ValueError, "withdrawn"):
            evaluate(self.bom, self.policy, fetch, self.today)

    def test_nested_sbom_components_are_not_omitted(self):
        self.bom["components"][0]["components"] = [{"purl": "pkg:maven/org.example/nested@1.0.0", "licenses": []}]
        result = evaluate(self.bom, self.policy, lambda *args: {"results": [{}, {}]}, self.today)
        self.assertEqual(result["dependenciesChecked"], 2)
        self.assertFalse(result["ok"])

    def test_conflicting_duplicate_metadata_does_not_silently_replace_unknown(self):
        self.bom["components"].append({"purl": "pkg:maven/org.example/demo@1.0.0?classifier=variant", "licenses": []})
        with self.assertRaisesRegex(ValueError, "conflicting"):
            evaluate(self.bom, self.policy, lambda *args: {}, self.today)

    def test_exact_license_metadata_evidence_does_not_apply_to_another_version(self):
        self.bom["components"][0]["licenses"] = [{"license": {"name": "Vendor MIT Name"}}]
        self.policy["licenseDeclarations"] = [{"package": "org.example:demo", "version": "1.0.0", "declared": ["Vendor MIT Name"], "expression": "Apache-2.0", "owner": "maintainer", "reason": "exact license text evidence", "evidence": "https://example.com/license", "reviewedOn": "2026-10-14", "expires": "2027-01-12"}]
        self.assertTrue(evaluate(self.bom, self.policy, lambda *args: {"results": [{}]}, self.today)["ok"])
        self.bom["components"][0]["purl"] = "pkg:maven/org.example/demo@1.0.1"
        self.assertFalse(evaluate(self.bom, self.policy, lambda *args: {"results": [{}]}, self.today)["ok"])

    def test_exception_cannot_slide_beyond_its_actual_review_date(self):
        def fetch(endpoint, payload=None):
            return {"results": [{"vulns": [{"id": "OSV-test"}]}]} if endpoint == "querybatch" else {"id": "OSV-test", "modified": "2026-10-01T00:00:00Z", "affected": [{"package": {"ecosystem": "Maven", "name": "org.example:demo"}}]}
        self.policy["exceptions"] = [{"package": "org.example:demo", "version": "1.0.0", "kind": "vulnerability", "finding": "OSV-test", "owner": "maintainer", "reason": "reviewed", "evidence": "https://example.com/advisory", "reviewedOn": "2026-01-01", "expires": "2026-10-15"}]
        with self.assertRaisesRegex(ValueError, "90 days"):
            evaluate(self.bom, self.policy, fetch, self.today)

    def test_detail_for_different_package_cannot_claim_complete_scan(self):
        def fetch(endpoint, payload=None):
            return {"results": [{"vulns": [{"id": "OSV-test"}]}]} if endpoint == "querybatch" else {"id": "OSV-test", "modified": "2026-10-01T00:00:00Z", "affected": [{"package": {"ecosystem": "Maven", "name": "org.other:unrelated"}}]}
        with self.assertRaisesRegex(ValueError, "identity"):
            evaluate(self.bom, self.policy, fetch, self.today)

    def conditional_policy(self, license_bytes):
        expression = "GPL-2.0-only WITH Universal-FOSS-exception-1.0"
        self.bom["components"][0]["licenses"] = [{"license": {"name": "Vendor UFOSS Name"}}]
        self.policy["licenseDeclarations"] = [{"package": "org.example:demo", "version": "1.0.0",
            "declared": ["Vendor UFOSS Name"], "expression": expression, "approvedExpression": expression,
            "projectLicense": {"id": "MIT", "path": "LICENSE", "sha256": hashlib.sha256(license_bytes).hexdigest()},
            "owner": "maintainer", "reason": "only MIT source distribution", "evidence": "https://example.com/license",
            "reviewedOn": "2026-10-14", "expires": "2027-01-12"}]

    def test_conditional_ufoss_approval_requires_unchanged_project_license(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = b"MIT License\nCopyright test\n"
            (root / "LICENSE").write_bytes(raw)
            self.conditional_policy(raw)
            self.assertTrue(evaluate(self.bom, self.policy, lambda *args: {"results": [{}]}, self.today, root)["ok"])
            (root / "LICENSE").write_bytes(b"Proprietary License\n")
            with self.assertRaisesRegex(ValueError, "basis changed"):
                evaluate(self.bom, self.policy, lambda *args: {"results": [{}]}, self.today, root)

    def test_conditional_approval_cannot_be_global_or_classpath_substitution(self):
        self.conditional_policy(b"MIT License\n")
        rule = self.policy["licenseDeclarations"][0]
        rule["approvedExpression"] = "GPL-2.0-only WITH Classpath-exception-2.0"
        with self.assertRaisesRegex(ValueError, "exact expression"):
            evaluate(self.bom, self.policy, lambda *args: {"results": [{}]}, self.today)
        rule["approvedExpression"] = rule["expression"]
        rule.pop("projectLicense")
        with self.assertRaisesRegex(ValueError, "project license basis"):
            evaluate(self.bom, self.policy, lambda *args: {"results": [{}]}, self.today)

    def test_license_review_expiry_does_not_silently_accept(self):
        self.conditional_policy(b"MIT License\n")
        self.policy["licenseDeclarations"][0]["expires"] = "2026-10-13"
        with self.assertRaisesRegex(ValueError, "expired"):
            evaluate(self.bom, self.policy, lambda *args: {"results": [{}]}, self.today)

    def test_http_evidence_preserves_actual_response_and_rejects_appending_runs(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "scan"
            evidence = HttpEvidence(path)
            request, response = b'{"queries": []}', b'{"results": []}'
            evidence.record("querybatch", request, response, 200)
            records = json.loads((path / "index.json").read_text())
            self.assertEqual((path / records[0]["response"]).read_bytes(), response)
            self.assertEqual(records[0]["responseSha256"], hashlib.sha256(response).hexdigest())
            with self.assertRaises(FileExistsError):
                HttpEvidence(path)

    def test_first_party_is_exact_identity_instead_of_a_group_wide_exclusion(self):
        self.bom["components"].append({"purl": "pkg:maven/com.robot/new-external@1.0.0", "licenses": []})
        result = evaluate(self.bom, self.policy, lambda *args: {"results": [{}, {}]}, self.today)
        self.assertEqual(result["dependenciesChecked"], 2)
        self.assertFalse(result["ok"])
        self.bom["components"][1]["purl"] = "pkg:maven/com.robot/robot-core@1.0.0-SNAPSHOT"
        with self.assertRaisesRegex(ValueError, "fixed released"):
            evaluate(self.bom, self.policy, lambda *args: {"results": [{}]}, self.today)
        self.policy["firstPartyComponents"] = [{"package": "com.robot:robot-core", "version": "1.0.0-SNAPSHOT"}]
        self.assertEqual(evaluate(self.bom, self.policy, lambda *args: {"results": [{}]}, self.today)["dependenciesChecked"], 1)


if __name__ == "__main__":
    unittest.main()
