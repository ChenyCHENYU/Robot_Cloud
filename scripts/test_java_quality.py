#!/usr/bin/env python3
"""质量证据解析器的结构/负例测试；测试 XML 夹具不能冒充实际工具报告。"""

import os
from pathlib import Path
import tempfile
import time
import unittest

from check_java_quality import verify


class JavaQualityEvidenceTest(unittest.TestCase):
    """只验证解析和拒绝能力，真实 Maven 门禁由 scripts/verify.sh 运行。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module = self.root / "robot-example"
        self.java = self.module / "src/main/java/com/robot/service/ExampleService.java"
        self.write(self.module / "pom.xml", "<project><artifactId>robot-example</artifactId></project>")
        self.write(self.java, "package com.robot.service; public class ExampleService { public int run() { return 1; } }")
        self.write(self.module / "target/classes/com/robot/service/ExampleService.class", "compiled-test-fixture")
        self.checkstyle = self.module / "target/checkstyle-result.xml"
        self.pmd = self.module / "target/pmd.xml"
        self.spotbugs = self.module / "target/spotbugsXml.xml"
        self.jacoco = self.module / "target/site/jacoco/jacoco.xml"
        self.log = self.root / "target/verification/maven-verify.log"
        self.write(self.checkstyle, f'<checkstyle version="9.3"><file name="{self.java}"/></checkstyle>')
        self.write(self.pmd, '<pmd xmlns="http://pmd.sourceforge.net/report/2.0.0" version="7.17.0"/>')
        self.write(self.spotbugs, '<BugCollection version="4.8.6"><Errors errors="0" missingClasses="0"/><FindBugsSummary total_classes="1"/></BugCollection>')
        self.coverage()
        self.write(self.log, self.maven_log())

    def write(self, path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        # 保证同次测试夹具时间排序可重复，不依赖文件系统纳秒精度。
        timestamp = time.time() + 1
        os.utime(path, (timestamp, timestamp))

    def maven_log(self):
        return "[INFO] Scanning for projects...\n" + "".join(
            f"[INFO] --- {plugin}:1.0:{goal} (fixture) @ robot-example ---\n"
            for plugin, goal in [("clean", "clean"), ("checkstyle", "check"), ("pmd", "check"), ("spotbugs", "check"), ("spotless", "check")]
        ) + "[INFO] BUILD SUCCESS\n"

    def coverage(self, line=(7, 3), branch=(3, 2), extra="", classname="ExampleService", package="com/robot/service"):
        branch_xml = f'<counter type="BRANCH" covered="{branch[0]}" missed="{branch[1]}"/>' if branch is not None else ""
        self.write(self.jacoco, '<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">'
                   f'<report name="fixture"><package name="{package}"><class name="{package}/{classname}" sourcefilename="{classname}.java">'
                   f'<counter type="INSTRUCTION" covered="7" missed="3"/><counter type="LINE" covered="{line[0]}" missed="{line[1]}"/>'
                   f'{branch_xml}</class>{extra}</package><counter type="LINE" covered="999" missed="0"/></report>')
        self.write(self.log, self.maven_log())

    def result(self):
        return verify(self.root, self.log)

    def assert_blocked(self, phrase):
        result = self.result()
        self.assertFalse(result["ok"], result)
        self.assertTrue(any(phrase in error for error in result["errors"]), result["errors"])

    def test_exact_class_boundaries_pass_and_evidence_is_hashed(self):
        result = self.result()
        self.assertTrue(result["ok"], result["errors"])
        self.assertEqual(result["coverage"][0]["line"]["minimumPercent"], 70)
        self.assertTrue(all(len(e["sha256"]) == 64 for e in result["evidence"]))

    def test_package_average_cannot_hide_low_service(self):
        self.coverage(line=(0, 10))
        self.assert_blocked("CLASS LINE 门槛不足")

    def test_branch_threshold_is_independent_of_line(self):
        self.coverage(line=(100, 0), branch=(2, 3))
        self.assert_blocked("CLASS BRANCH 门槛不足")

    def test_no_branches_is_not_fabricated_as_failed_coverage(self):
        self.coverage(branch=None)
        self.assertTrue(self.result()["ok"], self.result()["errors"])

    def test_controller_line_boundary_50_without_branch_target(self):
        self.java.unlink()
        self.java = self.module / "src/main/java/com/robot/controller/ExampleController.java"
        self.write(self.java, "package com.robot.controller; public class ExampleController { public int run() { return 1; } }")
        self.write(self.module / "target/classes/com/robot/controller/ExampleController.class", "compiled-test-fixture")
        self.write(self.checkstyle, f'<checkstyle version="9.3"><file name="{self.java}"/></checkstyle>')
        for report in (self.checkstyle, self.pmd, self.spotbugs):
            self.write(report, report.read_text(encoding="utf-8"))
        self.coverage(line=(5, 5), branch=(0, 100), classname="ExampleController", package="com/robot/controller")
        self.assertTrue(self.result()["ok"], self.result()["errors"])
        self.coverage(line=(4, 6), classname="ExampleController", package="com/robot/controller")
        self.assert_blocked("CLASS LINE 门槛不足")

    def test_missing_native_report_is_not_pass(self):
        self.jacoco.unlink()
        self.assert_blocked("报告缺失")

    def test_missing_business_class_is_not_pass(self):
        self.write(self.jacoco, '<report name="fixture"/>')
        self.assert_blocked("JaCoCo 遗漏业务源码 class")

    def test_absent_compilation_is_not_pass(self):
        (self.module / "target/classes/com/robot/service/ExampleService.class").unlink()
        self.assert_blocked("业务源码没有本次编译 class")

    def test_old_source_report_is_not_pass(self):
        os.utime(self.jacoco, (1, 1))
        self.assert_blocked("报告早于当前源码/配置")

    def test_appended_success_cannot_reuse_old_receipt(self):
        self.write(self.log, self.maven_log() + self.maven_log())
        self.assert_blocked("单次非追加成功执行")

    def test_build_success_without_spotless_goal_is_not_pass(self):
        self.write(self.log, self.maven_log().replace("[INFO] --- spotless:1.0:check (fixture) @ robot-example ---\n", ""))
        self.assert_blocked("spotless:check")

    def test_full_plugin_artifact_ids_are_supported(self):
        log = self.maven_log().replace("checkstyle:1.0", "maven-checkstyle-plugin:1.0").replace("spotless:1.0", "spotless-maven-plugin:1.0")
        self.write(self.log, log)
        self.assertTrue(self.result()["ok"], self.result()["errors"])

    def test_skipped_spotless_cannot_pass(self):
        self.write(self.log, self.maven_log() + "[INFO] Skipping Spotless check\n")
        self.assert_blocked("质量工具跳过")

    def test_native_spotless_cache_summary_is_not_skipped_goal(self):
        self.write(self.log, self.maven_log() + "[INFO] Spotless.Java is keeping 1 files clean - 0 were skipped because caching\n")
        self.assertTrue(self.result()["ok"], self.result()["errors"])

    def test_aggregate_pom_skip_does_not_mask_or_block_java_leaf_checks(self):
        aggregate = "[INFO] --- pmd:3.28.0:check (robot-pmd) @ robot-parent ---\n[INFO] Skipping PMD execution\n"
        self.write(self.log, self.maven_log().replace("[INFO] Scanning for projects...\n", "[INFO] Scanning for projects...\n" + aggregate))
        self.assertTrue(self.result()["ok"], self.result()["errors"])

    def test_leaf_pmd_skip_remains_blocked_even_with_all_goal_headers(self):
        log = self.maven_log().replace("[INFO] --- pmd:1.0:check (fixture) @ robot-example ---\n", "[INFO] --- pmd:1.0:check (fixture) @ robot-example ---\n[INFO] Skipping PMD execution\n")
        self.write(self.log, log)
        self.assert_blocked("pmd:check @ robot-example")

    def test_checkstyle_finding_blocks(self):
        self.write(self.checkstyle, '<checkstyle><file name="ExampleService.java"><error line="3" severity="error" message="NeedBraces"/></file></checkstyle>')
        self.assert_blocked("Checkstyle finding")

    def test_pmd_namespaced_processing_error_blocks(self):
        self.write(self.pmd, '<pmd xmlns="http://pmd.sourceforge.net/report/2.0.0" version="7.17.0"><error filename="ExampleService.java" msg="parse failed">failure</error></pmd>')
        self.assert_blocked("PMD error")

    def test_spotbugs_bug_and_missing_dependency_block(self):
        self.write(self.spotbugs, '<BugCollection><BugInstance type="NP_NULL_ON_SOME_PATH"/><Errors errors="0" missingClasses="1"><MissingClass>missing.Type</MissingClass></Errors><FindBugsSummary total_classes="1"/></BugCollection>')
        self.assert_blocked("SpotBugs BugInstance")
        self.assert_blocked("SpotBugs 分析不完整")

    def test_nested_class_is_individually_checked(self):
        self.coverage(extra='<class name="com/robot/service/ExampleService$Helper" sourcefilename="ExampleService.java"><counter type="LINE" covered="0" missed="10"/></class>')
        self.assert_blocked("ExampleService$Helper")

    def test_empty_or_wrong_xml_is_not_pass(self):
        self.write(self.pmd, '<checks/>')
        self.assert_blocked("报告不可解析")

    def test_checkstyle_missing_source_footprint_blocks(self):
        self.write(self.checkstyle, '<checkstyle version="9.3"><file name="unrelated.java"/></checkstyle>')
        self.assert_blocked("Checkstyle 遗漏当前源码文件")

    def test_pmd6_report_cannot_claim_pmd7_scan(self):
        self.write(self.pmd, '<pmd version="6.55.0"/>')
        self.assert_blocked("PMD 必须使用")


if __name__ == "__main__":
    unittest.main()
