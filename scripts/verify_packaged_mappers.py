#!/usr/bin/env python3
"""Run mapper assembly using production classes/dependencies extracted from the final Boot JAR."""
import hashlib
import json
import io
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SYSTEM = ROOT / 'robot-services/robot-system'


def safe_target(directory, name):
    base = Path(directory).resolve()
    target = (base / name).resolve()
    if not target.is_relative_to(base):
        raise ValueError('unsafe artifact path')
    return target


def verify():
    jars = list((SYSTEM / 'target').glob('robot-system-*.jar'))
    if len(jars) != 1:
        raise ValueError('exactly one freshly built system Boot JAR is required')
    jar = jars[0]
    cp_file = SYSTEM / 'target/test-classpath.txt'
    dependencies = cp_file.read_text().strip().split(os.pathsep)
    # Only H2 is borrowed from the test classpath. Production classes/libs MUST come from the artifact.
    h2 = [entry for entry in dependencies if Path(entry).name.startswith('h2-') and Path(entry).suffix == '.jar']
    if len(h2) != 1:
        raise ValueError('exactly one test H2 driver required')
    with tempfile.TemporaryDirectory(prefix='robot-packaged-mapper-') as folder:
        task_dir = Path(folder).resolve()
        probe_classes = task_dir / 'probe'
        probe_package = Path('com/robot/system/contract')
        for compiled in (SYSTEM / 'target/test-classes' / probe_package).glob('PackagedMapperProbe*.class'):
            target = probe_classes / probe_package / compiled.name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(compiled.read_bytes())
        if not (probe_classes / probe_package / 'PackagedMapperProbe.class').exists():
            raise ValueError('packaged mapper probe was not compiled')
        with zipfile.ZipFile(jar) as archive:
            names = archive.namelist()
            if 'BOOT-INF/classes/com/robot/system/RobotSystemApplication.class' not in names:
                raise ValueError('artifact is not the repackaged system Boot JAR')
            if archive.read('META-INF/LICENSE') != (ROOT / 'LICENSE').read_bytes():
                raise ValueError('packaged project license differs from repository license')
            connectors = [name for name in names if name.startswith('BOOT-INF/lib/mysql-connector-j-') and name.endswith('.jar')]
            if len(connectors) != 1:
                raise ValueError('exactly one actual MySQL connector is required')
            with zipfile.ZipFile(io.BytesIO(archive.read(connectors[0]))) as connector:
                if not any(name.endswith('LICENSE') and b'UNIVERSAL FOSS' in connector.read(name).upper()
                           for name in connector.namelist()):
                    raise ValueError('bundled connector must retain original GPL and Universal FOSS notice')
            manifest = json.loads((ROOT / 'contracts/database/migrations.json').read_text())
            artifact_sql = [name for name in names if name.startswith('BOOT-INF/classes/db/migration/V') and name.endswith('.sql')]
            if len(artifact_sql) != len(manifest['migrations']):
                raise ValueError('packaged migration resource count differs from reviewed manifest')
            for migration in manifest['migrations']:
                resource = 'BOOT-INF/classes/db/migration/' + migration['file']
                if resource not in names or hashlib.sha256(archive.read(resource)).hexdigest() != migration['sha256']:
                    raise ValueError('packaged migration bytes differ from manifest: ' + migration['file'])
            mapper_names = sorted(name.removeprefix('BOOT-INF/classes/').removesuffix('.class').replace('/', '.')
                                  for name in names if name.startswith('BOOT-INF/classes/com/robot/system/')
                                  and '/mapper/' in name and name.endswith('Mapper.class') and '$' not in name)
            if not mapper_names:
                raise ValueError('no packaged mapper interfaces found')
            for name in names:
                if name.endswith('/') or not name.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')):
                    continue
                target = safe_target(task_dir, name)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(name))
        classpath = os.pathsep.join([str(probe_classes), str(task_dir / 'BOOT-INF/classes'),
                                   str(task_dir / 'BOOT-INF/lib/*'), h2[0]])
        java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
        result_file = task_dir / 'mappers.json'
        subprocess.run([java, '-Dfile.encoding=UTF-8', '-cp', classpath,
                        'com.robot.system.contract.PackagedMapperProbe', str(result_file), *mapper_names], check=True)
        evidence = json.loads(result_file.read_text())
        evidence.update({'artifact': str(jar.relative_to(ROOT)), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                         'productionClasspath': 'BOOT-INF/classes + BOOT-INF/lib from final JAR only',
                         'licenseNotices': 'project MIT and bundled MySQL GPL/Universal FOSS retained',
                         'infrastructure': 'offline H2; no Redis/MySQL/Nacos', 'xmlRequired': False})
        output = ROOT / 'target/verification'
        output.mkdir(parents=True, exist_ok=True)
        (output / 'packaged-mappers.json').write_text(json.dumps(evidence, indent=2) + '\n')
        print(f'final JAR mapper assembly verified: {len(mapper_names)} mappers')


if __name__ == '__main__':
    verify()
