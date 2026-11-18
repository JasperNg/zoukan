"""Tests of changed_line_coverage.py on a tiny diff and Kover report.

    python3 -m unittest discover -s .github/scripts -p 'test_*.py'
"""

import contextlib
import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import changed_line_coverage as clc  # noqa: E402

DIFF = """\
diff --git a/desktop/src/main/kotlin/zoukan/desktop/Thing.kt b/desktop/src/main/kotlin/zoukan/desktop/Thing.kt
index 1111111..2222222 100644
--- a/desktop/src/main/kotlin/zoukan/desktop/Thing.kt
+++ b/desktop/src/main/kotlin/zoukan/desktop/Thing.kt
@@ -3,0 +4,3 @@ class Thing {
+    fun a() = 1
+    fun b() = 2
+    // a comment
@@ -10 +13 @@ class Thing {
-    val old = 0
+    val new = 0
@@ -20,2 +23,0 @@ class Thing {
-    gone()
-    gone()
diff --git a/android-compat/src/main/java/android/os/Looper.java b/android/os/Looper.java
--- a/android-compat/src/main/java/android/os/Looper.java
+++ b/android-compat/src/main/java/android/os/Looper.java
@@ -1,0 +2,4 @@
+    int vendored = 1;
+    // ZOUKAN -->
+    int own = 2;
+    // ZOUKAN <--
diff --git a/desktop/src/test/kotlin/zoukan/desktop/ThingTest.kt b/desktop/src/test/kotlin/zoukan/desktop/ThingTest.kt
--- /dev/null
+++ b/desktop/src/test/kotlin/zoukan/desktop/ThingTest.kt
@@ -0,0 +1,2 @@
+class ThingTest
+
diff --git a/app/src/main/java/eu/kanade/AndroidOnly.kt b/app/src/main/java/eu/kanade/AndroidOnly.kt
--- a/app/src/main/java/eu/kanade/AndroidOnly.kt
+++ b/app/src/main/java/eu/kanade/AndroidOnly.kt
@@ -5 +5 @@
-x
+y
diff --git a/desktop/src/main/kotlin/zoukan/desktop/Removed.kt b/desktop/src/main/kotlin/zoukan/desktop/Removed.kt
--- a/desktop/src/main/kotlin/zoukan/desktop/Removed.kt
+++ /dev/null
@@ -1,2 +0,0 @@
-gone
-gone
diff --git a/android-compat/src/main/java/android/os/Handler.java b/android-compat/src/main/java/android/os/Handler.java
--- a/android-compat/src/main/java/android/os/Handler.java
+++ b/android-compat/src/main/java/android/os/Handler.java
@@ -1 +1 @@
-a
+b
"""

REPORT = """\
<?xml version="1.0" ?>
<report name="test">
<package name="zoukan/desktop">
<class name="zoukan/desktop/Thing" sourcefilename="Thing.kt">
<counter type="LINE" missed="1" covered="2"/>
</class>
<sourcefile name="Thing.kt">
<line nr="4" mi="0" ci="3" mb="0" cb="0"/>
<line nr="5" mi="2" ci="0" mb="0" cb="0"/>
<line nr="13" mi="0" ci="1" mb="0" cb="0"/>
<line nr="30" mi="1" ci="0" mb="0" cb="0"/>
</sourcefile>
</package>
<package name="android/os">
<sourcefile name="Looper.java">
<line nr="2" mi="1" ci="0" mb="0" cb="0"/>
<line nr="4" mi="2" ci="0" mb="0" cb="0"/>
</sourcefile>
<sourcefile name="Handler.java">
<line nr="1" mi="1" ci="0" mb="0" cb="0"/>
</sourcefile>
</package>
<package name="zoukan/other">
<sourcefile name="Thing.kt">
<line nr="5" mi="0" ci="1" mb="0" cb="0"/>
</sourcefile>
</package>
</report>
"""

LOOPER_AT_HEAD = "class Looper {\n    int vendored = 1;\n    // ZOUKAN -->\n    int own = 2;\n    // ZOUKAN <--\n}\n"
HANDLER_AT_HEAD = "b\n"


class ChangedLineCoverageTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.report_path = os.path.join(self.dir.name, "report.xml")
        with open(self.report_path, "w", encoding="utf-8") as f:
            f.write(REPORT)
        self.files = {
            "android-compat/src/main/java/android/os/Looper.java": LOOPER_AT_HEAD,
            "android-compat/src/main/java/android/os/Handler.java": HANDLER_AT_HEAD,
        }

    def tearDown(self):
        self.dir.cleanup()

    def test_diff_gives_the_new_side_lines_and_skips_deletions(self):
        changed = clc.parse_diff(DIFF)
        self.assertEqual(changed["desktop/src/main/kotlin/zoukan/desktop/Thing.kt"], {4, 5, 6, 13})
        self.assertEqual(changed["android-compat/src/main/java/android/os/Looper.java"], {2, 3, 4, 5})
        self.assertNotIn("desktop/src/main/kotlin/zoukan/desktop/Removed.kt", changed)

    def test_report_lines_by_package_folder(self):
        report = clc.parse_report(self.report_path)
        self.assertEqual(report["zoukan/desktop/Thing.kt"].covered, {4: True, 5: False, 13: True, 30: False})
        # The other package's Thing.kt is another file
        self.assertEqual(report["zoukan/other/Thing.kt"].covered, {5: True})

    def test_a_path_finds_its_package_not_a_file_of_the_same_name(self):
        report = clc.parse_report(self.report_path)
        entry = clc.find_in_report("desktop/src/main/kotlin/zoukan/desktop/Thing.kt", report)
        self.assertIs(entry, report["zoukan/desktop/Thing.kt"])
        # Two files of that name and neither package matches: not guessed
        self.assertIsNone(clc.find_in_report("lib/src/Thing.kt", report))
        # One file of that name: found even if the folder does not follow the package
        self.assertIs(clc.find_in_report("x/y/Looper.java", report), report["android/os/Looper.java"])

    def test_only_lines_inside_markers_count_in_android_compat(self):
        own = clc.own_lines("android-compat/src/main/java/android/os/Looper.java", LOOPER_AT_HEAD)
        self.assertEqual(own, {3, 4, 5})
        self.assertIsNone(clc.own_lines("desktop/src/main/kotlin/A.kt", "anything"))
        inline = clc.own_lines("android-compat/A.java", "a\nb /* ZOUKAN --> */c/* ZOUKAN <-- */\n")
        self.assertEqual(inline, {2})
        whole = clc.own_lines("android-compat/B.kt", "// ZOUKAN: whole file -->\nx\n// ZOUKAN: whole file <--\n")
        self.assertEqual(whole, {1, 2, 3})

    def test_summary(self):
        summary = clc.summarize(clc.parse_diff(DIFF), clc.parse_report(self.report_path), self.files.get)
        # Thing.kt: lines 4 (run), 5 (not run), 13 (run); 6 is a comment. Looper.java: line 4 only
        self.assertIn("**2 of 4** changed executable lines (50%)", summary)
        self.assertIn("| `desktop/src/main/kotlin/zoukan/desktop/Thing.kt` | 2/3 | 5 |", summary)
        self.assertIn("| `android-compat/src/main/java/android/os/Looper.java` | 0/1 | 4 |", summary)
        self.assertIn("- `app/src/main/java/eu/kanade/AndroidOnly.kt`", summary)
        self.assertIn("Changed only outside the ZOUKAN markers", summary)
        self.assertIn("- `android-compat/src/main/java/android/os/Handler.java`", summary)
        self.assertNotIn("ThingTest", summary)
        self.assertNotIn("Removed.kt", summary)

    def test_nothing_measured(self):
        summary = clc.summarize({"README.md": {1}}, clc.parse_report(self.report_path), self.files.get)
        self.assertIn("No changed executable line is in the coverage report.", summary)

    def test_ranges(self):
        self.assertEqual(clc.ranges([7, 1, 2, 3, 9, 10]), "1-3, 7, 9-10")
        self.assertEqual(clc.ranges([]), "")

    def test_main_reads_a_saved_diff_and_fails_loudly_without_a_report(self):
        diff_path = os.path.join(self.dir.name, "pr.diff")
        with open(diff_path, "w", encoding="utf-8") as f:
            f.write(DIFF)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            status = clc.main(["--report", self.report_path, "--diff-file", diff_path, "--repo", self.dir.name])
        self.assertEqual(status, 0)
        self.assertIn("## Coverage of changed lines", out.getvalue())
        with self.assertRaises(FileNotFoundError):
            clc.main(["--report", os.path.join(self.dir.name, "missing.xml"), "--diff-file", diff_path])
        empty = os.path.join(self.dir.name, "empty.xml")
        with open(empty, "w", encoding="utf-8") as f:
            f.write("<report/>")
        with self.assertRaises(ValueError):
            clc.main(["--report", empty, "--diff-file", diff_path])

    def test_a_diff_without_b_prefix_is_an_error(self):
        with self.assertRaises(ValueError):
            clc.parse_diff("+++ desktop/A.kt\n@@ -1 +1 @@\n")


if __name__ == "__main__":
    unittest.main()
