import pathlib
import tempfile
import unittest

from tools.check_locale_keys import (
    compute_drift,
    evaluate,
    locale_strings_files,
    parse_string_keys,
)

DEFAULT_XML = """\
<resources>
    <string name="app_name">Dead Air</string>
    <string name="send">Send</string>
    <string name="retry">Retry send</string>
</resources>
"""


def make_res_dir(tmp: pathlib.Path) -> pathlib.Path:
    """Create a minimal res tree: values + one locale with one drift."""
    values = tmp / "values"
    values.mkdir(parents=True)
    (values / "strings.xml").write_text(DEFAULT_XML, encoding="utf-8")

    locale = tmp / "values-fr"
    locale.mkdir(parents=True)
    (locale / "strings.xml").write_text(
        """\
<resources>
    <string name="app_name">Dead Air</string>
    <string name="send">Envoyer</string>
</resources>
""",
        encoding="utf-8",
    )

    # Theme-only folder with no strings.xml must be skipped, not crash.
    (tmp / "values-night").mkdir(parents=True)
    (tmp / "values-night" / "themes.xml").write_text(
        "<resources/>", encoding="utf-8"
    )
    return tmp


class ParseStringKeysTest(unittest.TestCase):
    def test_extracts_only_string_elements(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "strings.xml"
            path.write_text(DEFAULT_XML, encoding="utf-8")
            self.assertEqual(
                {"app_name", "send", "retry"},
                parse_string_keys(path),
            )

    def test_rejects_string_without_name_attribute(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "strings.xml"
            path.write_text(
                "<resources><string>no name here</string></resources>",
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                parse_string_keys(path)


class LocaleStringsFilesTest(unittest.TestCase):
    def test_skips_locales_without_strings_xml(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = pathlib.Path(directory)
            make_res_dir(tmp)
            self.assertEqual(
                [("values-fr", tmp / "values-fr" / "strings.xml")],
                locale_strings_files(tmp),
            )


class ComputeDriftTest(unittest.TestCase):
    def test_reports_missing_keys_per_locale(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = pathlib.Path(directory)
            make_res_dir(tmp)
            drift = compute_drift(tmp)
            self.assertEqual(
                {"missing": ["retry"], "extra": []},
                drift["values-fr"],
            )

    def test_reports_extra_keys(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp = pathlib.Path(directory)
            make_res_dir(tmp)
            (tmp / "values-fr" / "strings.xml").write_text(
                """\
<resources>
    <string name="app_name">Dead Air</string>
    <string name="send">Envoyer</string>
    <string name="retry">Renvoyer</string>
    <string name="stale_key">Deprecated</string>
</resources>
""",
                encoding="utf-8",
            )
            drift = compute_drift(tmp)
            self.assertEqual(
                {"missing": [], "extra": ["stale_key"]},
                drift["values-fr"],
            )


class EvaluateTest(unittest.TestCase):
    def test_passes_when_baseline_covers_drift(self) -> None:
        baseline = {"values-fr": {"missing": ["retry"], "extra": []}}
        drift = {"values-fr": {"missing": ["retry"], "extra": []}}
        errors, notes = evaluate(drift, baseline)
        self.assertEqual([], errors)
        self.assertEqual([], notes)

    def test_fails_on_new_missing_key(self) -> None:
        baseline = {"values-fr": {"missing": ["retry"], "extra": []}}
        drift = {
            "values-fr": {"missing": ["retry", "new_key"], "extra": []}
        }
        errors, notes = evaluate(drift, baseline)
        self.assertEqual(1, len(errors))
        self.assertIn("new_key", errors[0])

    def test_fails_on_new_extra_key(self) -> None:
        baseline = {"values-fr": {"missing": ["retry"], "extra": []}}
        drift = {
            "values-fr": {"missing": ["retry"], "extra": ["stale_key"]}
        }
        errors, notes = evaluate(drift, baseline)
        self.assertEqual(1, len(errors))
        self.assertIn("stale_key", errors[0])

    def test_notes_when_baseline_entries_now_fixed(self) -> None:
        baseline = {"values-fr": {"missing": ["retry"], "extra": []}}
        drift = {"values-fr": {"missing": [], "extra": []}}
        errors, notes = evaluate(drift, baseline)
        self.assertEqual([], errors)
        self.assertEqual(1, len(notes))

    def test_missing_baseline_means_all_drift_is_new(self) -> None:
        drift = {"values-fr": {"missing": ["retry"], "extra": []}}
        errors, notes = evaluate(drift, {})
        self.assertEqual(1, len(errors))

    def test_fails_when_baseline_locale_loses_strings_xml(self) -> None:
        baseline = {"values-fr": {"missing": ["retry"], "extra": []}}
        drift: dict[str, dict[str, list[str]]] = {}
        errors, notes = evaluate(drift, baseline)
        self.assertEqual(1, len(errors))
        self.assertIn("values-fr", errors[0])
        self.assertIn("missing entirely", errors[0])


if __name__ == "__main__":
    unittest.main()
