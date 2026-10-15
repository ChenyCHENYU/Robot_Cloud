#!/usr/bin/env python3
"""核验同次 Maven 构建的原生质量报告与逐类 JaCoCo 门槛，不生成通过基线。"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


REPORTS = {
    "checkstyle": ("target/checkstyle-result.xml", "checkstyle"),
    "pmd": ("target/pmd.xml", "pmd"),
    "spotbugs": ("target/spotbugsXml.xml", "BugCollection"),
}
ANSI = re.compile(r"\x1b\[[0-9;]*m")
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)
GOAL = re.compile(r"---\s+(?:[\w.]+:)?([\w-]+):([^: ]+):([\w-]+)\s+\([^)]*\)\s+@\s+([^ ]+)\s+---")


def tag(element: ET.Element) -> str:
    """兼容 PMD 的命名空间，不放宽原生报告根节点验证。"""
    return element.tag.rsplit("}", 1)[-1]


def evidence(path: Path, root: Path) -> dict:
    """记录实际输入内容，允许外部日志路径，避免凭工具名宣称执行。"""
    try:
        display = str(path.relative_to(root))
    except ValueError:
        display = str(path)
    data = path.read_bytes()
    return {"path": display, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def modules(root: Path) -> list[dict]:
    """只发现仓库实际 Java 模块；不将聚合 POM 或 target 当作业务源码。"""
    found = []
    for pom in sorted(root.rglob("pom.xml")):
        if any(part in {"target", ".git", "node_modules", ".github"} for part in pom.relative_to(root).parts):
            continue
        source = pom.parent / "src/main/java"
        java = sorted(source.rglob("*.java")) if source.is_dir() else []
        if not java:
            continue
        parsed = ET.parse(pom).getroot()
        artifact = next((e.text for e in parsed if tag(e) == "artifactId"), None)
        if not artifact:
            raise ValueError(f"缺少 artifactId: {pom.relative_to(root)}")
        found.append({"path": pom.parent, "artifact": artifact, "java": java})
    return found


def load_report(path: Path, expected: str, root: Path, newest: float, log_time: float,
                errors: list[str], receipts: list[dict]) -> ET.Element | None:
    """缺失、陈旧、截断、错误格式均失败；XML 本身不能证明 Maven 成功。"""
    label = str(path.relative_to(root))
    if not path.is_file():
        errors.append(f"报告缺失: {label}")
        return None
    receipts.append(evidence(path, root))
    if path.stat().st_mtime < newest:
        errors.append(f"报告早于当前源码/配置: {label}")
    if path.stat().st_mtime > log_time + 2:
        errors.append(f"报告晚于本次 Maven 日志: {label}")
    try:
        data = path.read_bytes()
        # JaCoCo 官方 DOCTYPE 可保留；禁止自定义实体，ET 不加载外部 DTD。
        if b"<!ENTITY" in data:
            raise ValueError("报告不能声明自定义 XML 实体")
        parsed = ET.fromstring(data)
        if tag(parsed) != expected:
            raise ValueError(f"根节点必须为 {expected}")
        return parsed
    except (ET.ParseError, ValueError) as exc:
        errors.append(f"报告不可解析: {label}: {exc}")
        return None


def check_static(tree: ET.Element, tool: str, label: str, errors: list[str], sources: list[Path], module_path: Path) -> None:
    """检查真实原生 finding 与工具处理错误，不能将空壳报告当作成功。"""
    if tool == "checkstyle":
        files = [node for node in tree.iter() if tag(node) == "file"]
        if not files:
            errors.append(f"Checkstyle 未扫描任何文件: {label}")
        scanned = [node.get("name", "").replace("\\", "/") for node in files]
        for source in sources:
            relative = source.relative_to(module_path).as_posix()
            if not any(name == relative or name.endswith("/" + relative) for name in scanned):
                errors.append(f"Checkstyle 遗漏当前源码文件: {relative}")
        for node in tree.iter():
            if tag(node) == "error" and node.get("severity", "error") != "ignore":
                errors.append(f"Checkstyle finding: {label}:{node.get('line', '?')} {node.get('message', '')}")
    elif tool == "pmd":
        if not tree.get("version"):
            errors.append(f"PMD 缺少工具版本: {label}")
        elif not tree.get("version", "").startswith("7."):
            errors.append(f"PMD 必须使用 Java 17 配套的 PMD 7: {label}")
        for node in tree.iter():
            if tag(node) in {"violation", "error", "configerror"}:
                errors.append(f"PMD {tag(node)}: {label}: {' '.join(''.join(node.itertext()).split())}")
    else:
        summary = next((node for node in tree if tag(node) == "FindBugsSummary"), None)
        if summary is None or int(summary.get("total_classes", "0")) <= 0:
            errors.append(f"SpotBugs 未分析任何 class: {label}")
        for node in tree.iter():
            if tag(node) in {"BugInstance", "Error", "MissingClass"}:
                errors.append(f"SpotBugs {tag(node)}: {label}: {node.get('type', '')} {' '.join(''.join(node.itertext()).split())}")
            if tag(node) == "Errors" and any(int(node.get(key, "0")) for key in ("errors", "missingClasses")):
                errors.append(f"SpotBugs 分析不完整: {label}")


def source_kind(path: Path) -> tuple[str, str | None, bool]:
    """覆盖率按实际包层级分类，避免仅按 *Service 文件名漏掉辅助业务类。"""
    text = path.read_text(encoding="utf-8")
    match = PACKAGE.search(text)
    if not match:
        raise ValueError(f"源码缺少 package: {path}")
    package = match.group(1).replace(".", "/")
    parts = package.split("/")
    kind = "service" if "service" in parts else "controller" if "controller" in parts else None
    without_comments = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.DOTALL)
    pure_interface = bool(re.search(r"\binterface\s+" + re.escape(path.stem) + r"\b", without_comments))
    has_body = bool(re.search(r"\)\s*(?:throws\s+[\w.,\s]+)?\{", without_comments))
    return package, kind, pure_interface and not has_body


def counters(element: ET.Element) -> dict[str, tuple[int, int]]:
    """缺失或负计数不能作为零分母绕过。"""
    result = {}
    for node in element:
        if tag(node) != "counter":
            continue
        covered, missed = int(node.attrib["covered"]), int(node.attrib["missed"])
        if covered < 0 or missed < 0 or node.attrib["type"] in result:
            raise ValueError("JaCoCo 计数非法或重复")
        result[node.attrib["type"]] = (covered, covered + missed)
    return result


def check_coverage(tree: ET.Element, module: dict, errors: list[str], rows: list[dict]) -> None:
    """逐 CLASS 校验，不使用 PACKAGE/BUNDLE 平均值替代业务类门槛。"""
    expected = {}
    for java in module["java"]:
        package, kind, pure_interface = source_kind(java)
        if kind:
            expected[(package, java.name)] = (kind, pure_interface, java)
    seen = set()
    seen_classes = set()
    for package in tree:
        if tag(package) != "package":
            continue
        for cls in package:
            if tag(cls) != "class":
                continue
            key = (package.attrib["name"], cls.get("sourcefilename", ""))
            classified = expected.get(key)
            if not classified:
                if {"service", "controller"}.intersection(package.attrib["name"].split("/")):
                    errors.append(f"JaCoCo class 无对应当前业务源码: {cls.attrib['name']}")
                continue
            seen.add(key)
            seen_classes.add(cls.attrib["name"])
            values = counters(cls)
            line = values.get("LINE")
            if line is None:
                # 原生报告会省略无可执行代码的接口/常量类/生成方法计数。
                if values.get("INSTRUCTION", (0, 0))[1]:
                    errors.append(f"JaCoCo class 缺少 LINE: {cls.attrib['name']}")
                continue
            if line[1] == 0:
                continue
            kind = classified[0]
            row = {"module": module["artifact"], "class": cls.attrib["name"], "kind": kind,
                   "line": {"covered": line[0], "total": line[1], "minimumPercent": 70 if kind == "service" else 50}}
            if line[0] * 100 < line[1] * row["line"]["minimumPercent"]:
                errors.append(f"CLASS LINE 门槛不足: {cls.attrib['name']} {line[0]}/{line[1]} < {row['line']['minimumPercent']}%")
            # JaCoCo XML 对零分母计数省略元素；这是无分支，不是可降低的门槛。
            branch = values.get("BRANCH", (0, 0))
            if kind == "service":
                row["branch"] = {"covered": branch[0], "total": branch[1], "minimumPercent": 60}
                if branch[1] and branch[0] * 100 < branch[1] * 60:
                    errors.append(f"CLASS BRANCH 门槛不足: {cls.attrib['name']} {branch[0]}/{branch[1]} < 60%")
            rows.append(row)
    for key, (_kind, pure_interface, java) in expected.items():
        compiled = module["path"] / "target/classes" / key[0] / (java.stem + ".class")
        if not compiled.is_file():
            errors.append(f"业务源码没有本次编译 class: {compiled}")
        elif compiled.stat().st_mtime < java.stat().st_mtime:
            errors.append(f"编译 class 早于业务源码: {compiled}")
        elif (key not in seen or f"{key[0]}/{java.stem}" not in seen_classes) and not pure_interface:
            errors.append(f"JaCoCo 遗漏业务源码 class: {key[0]}/{java.stem}")


def verify(root: Path, maven_log: Path) -> dict:
    """输出可追踪事实；脚本测试通过不能替代实际 Maven 门禁通过。"""
    root, maven_log = root.resolve(), maven_log.resolve()
    errors, receipts, rows = [], [], []
    java_modules = modules(root)
    if not java_modules:
        errors.append("未发现 Java 模块")
    inputs = []
    for module in java_modules:
        inputs.extend(module["java"])
        inputs.extend((module["path"] / "src/test").rglob("*.java"))
    inputs.extend(p for p in root.rglob("pom.xml") if not any(part in {"target", ".git", "node_modules", ".github"} for part in p.relative_to(root).parts))
    inputs.extend((root / "quality").glob("*"))
    inputs = sorted({p for p in inputs if p.is_file()})
    newest = max((p.stat().st_mtime for p in inputs), default=0)
    receipts.extend(evidence(p, root) for p in inputs)
    log_time = 0.0
    goals = set()
    if not maven_log.is_file():
        errors.append(f"真实 Maven 日志缺失: {maven_log}")
    else:
        receipts.append(evidence(maven_log, root))
        log_time = maven_log.stat().st_mtime
        log = ANSI.sub("", maven_log.read_text(encoding="utf-8"))
        if log.count("BUILD SUCCESS") != 1 or "BUILD FAILURE" in log or log.count("Scanning for projects") != 1:
            errors.append("Maven 日志须来自单次非追加成功执行，不得合并历史 BUILD SUCCESS")
        if log_time < newest:
            errors.append("Maven 日志早于当前源码/配置")
        tools = r"(?:spotless|checkstyle|pmd|spotbugs)"
        skipped_goal = rf"\b(?:skipping|skipped)\s+(?:the\s+)?{tools}\b|\b{tools}\s+(?:(?:check|analysis|execution|goal)\s+)?(?:is\s+)?(?:skipping|skipped)\b|\b{tools}\.skip\s*[=:]\s*true\b"
        goals = set()
        headers = list(GOAL.finditer(log))
        java_artifacts = {module["artifact"] for module in java_modules}
        for index, header in enumerate(headers):
            plugin, _version, goal, artifact = header.groups()
            normalized = plugin.removesuffix("-maven-plugin")
            if normalized.startswith("maven-") and normalized.endswith("-plugin"):
                normalized = normalized.removeprefix("maven-").removesuffix("-plugin")
            goals.add((normalized, goal, artifact))
            end = headers[index + 1].start() if index + 1 < len(headers) else len(log)
            # 聚合 POM 没有 Java 输入，其原生 skip 不代表 Java leaf 工具被跳过。
            if artifact in java_artifacts and normalized in {"spotless", "checkstyle", "pmd", "spotbugs"}:
                if re.search(skipped_goal, log[header.end():end], flags=re.IGNORECASE):
                    errors.append(f"Maven 日志包含质量工具跳过: {normalized}:{goal} @ {artifact}")
    for module in java_modules:
        name = module["artifact"]
        module_sources = module["java"] + sorted((module["path"] / "src/test/java").rglob("*.java"))
        for plugin, goal in (("clean", "clean"), ("checkstyle", "check"), ("pmd", "check"), ("spotbugs", "check"), ("spotless", "check")):
            if (plugin, goal, name) not in goals:
                errors.append(f"缺少实际 Maven goal: {plugin}:{goal} @ {name}")
        for tool, (relative, expected) in REPORTS.items():
            path = module["path"] / relative
            parsed = load_report(path, expected, root, newest, log_time, errors, receipts)
            if parsed is not None:
                check_static(parsed, tool, str(path.relative_to(root)), errors, module_sources, module["path"])
        if any(source_kind(java)[1] for java in module["java"]):
            path = module["path"] / "target/site/jacoco/jacoco.xml"
            parsed = load_report(path, "report", root, newest, log_time, errors, receipts)
            if parsed is not None:
                check_coverage(parsed, module, errors, rows)
    return {"ok": not errors, "gate": "robot-java17-quality-v1", "javaModules": [m["artifact"] for m in java_modules],
            "thresholds": {"service": {"linePercent": 70, "branchPercent": 60}, "controller": {"linePercent": 50}, "element": "CLASS"},
            "errors": errors, "coverage": rows, "evidence": receipts}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--maven-log", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify(args.root, args.maven_log)
    except (OSError, ValueError, ET.ParseError, KeyError) as exc:
        result = {"ok": False, "errors": [f"质量证据验证失败: {exc}"]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
