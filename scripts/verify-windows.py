"""Run Windows regression checks and write a machine-readable release report."""
from pathlib import Path
import json
import sys
import unittest
from PySide6.QtCore import qVersion
root = Path(__file__).resolve().parents[1]
result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.discover(str(root/'windows/tests')))
report = {'version':'0.8.0','tests':result.testsRun,'failures':len(result.failures),
          'errors':len(result.errors),'skipped':len(result.skipped),'qt':qVersion(),
          'realInferenceTested':False,'paidCloudTested':False}
out = root/'build/verification/windows-tests-0.8.0.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, indent=2), encoding='utf-8')
sys.exit(not result.wasSuccessful())
