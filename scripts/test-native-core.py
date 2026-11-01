"""Run the isolated native JVM suite with cached tools and bounded memory."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument(
        "--dependency-cache", type=Path,
        default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
        / "caches/modules-2/files-2.1",
    )
    args = parser.parse_args()
    java_home = args.java_home or ROOT / ".jdk/jdk-21.0.2"
    java = java_home / ("bin/java.exe" if os.name == "nt" else "bin/java")
    if not java.is_file():
        parser.error("Supply --java-home or JAVA_HOME pointing to JDK 21.")

    def jar(group, artifact, version):
        matches = list((args.dependency_cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
        if len(matches) != 1:
            raise RuntimeError(f"Expected one cached {group}:{artifact}:{version}")
        return str(matches[0])

    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21")
    annotations = jar("org.jetbrains", "annotations", "13.0")
    compiler = [
        jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.0.21"), stdlib,
        jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.0.21"),
        jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        jar("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.0.21"),
        jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
        jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.6.4"), annotations,
    ]
    dependencies = [
        stdlib, annotations, jar("junit", "junit", "4.13.2"),
        jar("org.hamcrest", "hamcrest-core", "1.3"),
        jar("org.jetbrains.kotlin", "kotlin-test", "2.0.21"),
        jar("org.jetbrains.kotlin", "kotlin-test-junit", "2.0.21"),
    ] + [jar("org.bouncycastle", artifact, "1.79") for artifact in (
        "bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on",
    )]
    main_sources = sorted((ROOT / "netcore/src/main/kotlin").rglob("*.kt"))
    test_sources = sorted((ROOT / "netcore/src/test/kotlin").rglob("*.kt"))
    test_classes = []
    for source in test_sources:
        text = source.read_text(encoding="utf-8")
        package = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)
        for match in re.finditer(r"^class\s+(\w+Test)\b", text, re.MULTILINE):
            test_classes.append(package.group(1) + "." + match.group(1))
    if not test_classes:
        raise RuntimeError("No JVM test classes found")

    output = ROOT / "port/.run/native-core-review"
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="compiler-", dir=output) as directory:
        classes = Path(directory) / "classes.jar"
        classpath = os.pathsep.join(dependencies)
        subprocess.run([
            str(java), "-Xmx256m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
            "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17",
            "-classpath", classpath, "-d", str(classes),
            *map(str, main_sources + test_sources),
        ], check=True, cwd=ROOT / "netcore")
        runner_classes = Path(directory) / "runner"
        runner_classes.mkdir()
        javac = java_home / ("bin/javac.exe" if os.name == "nt" else "bin/javac")
        subprocess.run([
            str(javac), "-J-Xmx64m", "-classpath", classpath,
            "-d", str(runner_classes), str(ROOT / "port/tests/native-core/NativeJUnitReport.java"),
        ], check=True, cwd=ROOT)
        resources = ROOT / "netcore/src/test/resources"
        subprocess.run([
            str(java), "-Xmx256m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
            "-Ddsha.live=", "-cp", os.pathsep.join([
                str(runner_classes), str(classes), str(resources), classpath,
            ]),
            "NativeJUnitReport", str(output / "junit-summary.json"), *test_classes,
        ], check=True, cwd=ROOT / "netcore")
    print(f"Accepted {len(test_classes)} JVM suites; no application or payload was changed.")


if __name__ == "__main__":
    main()
