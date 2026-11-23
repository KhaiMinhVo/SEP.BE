import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("diagnostics", Path(__file__).parents[1] / "deploy_diagnostics.py")
diagnostics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnostics)


class DiagnosticsTest(unittest.TestCase):
    def test_smtp_failure_without_credentials(self):
        result = str(diagnostics.fingerprints("WARN o.s.b.a.mail.MailHealthIndicator: password=private-value\nCaused by: jakarta.mail.AuthenticationFailedException: authentication failed user=private@example.test token=private-token"))
        for expected in ["MailHealthIndicator", "AuthenticationFailedException", "authentication-failed"]:
            self.assertIn(expected, result)
        for secret in ["private-value", "private@example.test", "private-token", "password="]:
            self.assertNotIn(secret, result)

    def test_database_and_migration_are_distinguished(self):
        result = str(diagnostics.fingerprints("Caused by: org.postgresql.util.PSQLException: connection refused\nERROR org.flywaydb.core.api.exception.FlywayValidateException: checksum mismatch"))
        self.assertIn("connection-refused", result)
        self.assertIn("migration-validation-failed", result)

    def test_unrelated_logs_are_omitted(self):
        self.assertEqual(diagnostics.fingerprints("INFO Authorization: Bearer private-token\nINFO user message"), [])
