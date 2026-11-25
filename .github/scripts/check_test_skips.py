#!/usr/bin/env python3
"""CI 的 B 层证据（U15）。

打印逐类的 tests / skipped / failures / errors，并断言 9 个 Linux-only 类在 Linux 上
**tests > 0 且 skipped == 0** —— 「CI 绿」不能代替「B 层真跑过」：Windows 上这些类被
`assumeLinux()` 跳过，整条流水线照样绿。

清单对应 app/src/test/java/dev/kair/app/core/LinuxOnlyGuardTest.kt 的 allowed；
这里只按类名断言，不写死用例数（计数随增删漂移，白名单在单测里钉住）。
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

# 与 LinuxOnlyGuardTest.allowed 的九个文件一一对应（类名，不含包）。
LINUX_ONLY = (
    "ToolsTest",
    "WorkspacePermissionRepairTest",
    "TarExtractorTest",
    "CommandRunnerTest",
    "FileBackendTest",
    "DynamicPluginHostTest",
    "ProotCommandRunnerTest",
    "RootfsManagerTest",
    "RootCommandRunnerTest",
)

DEFAULT_RESULTS = "app/build/test-results/testDebugUnitTest"


def main() -> int:
    results = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_RESULTS
    paths = sorted(glob.glob(os.path.join(results, "TEST-*.xml")))
    if not paths:
        print(f"找不到测试结果 XML：{results}/TEST-*.xml", file=sys.stderr)
        return 1

    suites = {}
    for path in paths:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            print(f"XML 解析失败：{path}: {exc}", file=sys.stderr)
            return 1
        name = root.attrib.get("name", os.path.basename(path))
        suites[name] = root
        print(
            f"{name}: tests={root.attrib.get('tests', '?')} "
            f"skipped={root.attrib.get('skipped', '?')} "
            f"failures={root.attrib.get('failures', '?')} "
            f"errors={root.attrib.get('errors', '?')}"
        )

    problems = []
    for cls in LINUX_ONLY:
        matched = [name for name in suites if name == cls or name.endswith("." + cls)]
        if not matched:
            problems.append(f"{cls}: 结果里没有这个类（被删除 / 改名 / 没执行）")
            continue
        for name in matched:
            suite = suites[name]
            if int(suite.attrib.get("tests", "0")) <= 0:
                problems.append(f"{name}: tests=0，等于没跑")
            if int(suite.attrib.get("skipped", "0")) != 0:
                skipped_cases = [
                    case.attrib.get("name", "?")
                    for case in suite.findall("testcase")
                    if case.find("skipped") is not None
                ]
                problems.append(
                    f"{name}: skipped={suite.attrib.get('skipped')}"
                    f"（{', '.join(skipped_cases) or '未标注用例名'}）"
                )

    if problems:
        print("\nB 层证据不成立：", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        print(
            "这 9 类必须在 Linux 上整类执行；Windows 的 skipped≠0 不能当 CI 证据。",
            file=sys.stderr,
        )
        return 1

    print(f"\nLinux-only 类 {len(LINUX_ONLY)} 个全部 tests>0 且 skipped=0。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
