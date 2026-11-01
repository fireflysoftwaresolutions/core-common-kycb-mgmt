"""Negative probes for the source guard; no JVM or generated sources required."""
import unittest

from check_implementation_names import violations


class ImplementationNamesTest(unittest.TestCase):
    path = 'module/src/main/java/Example.java'

    def test_nested_inherited_implementation_requires_suffix(self):
        self.assertTrue(violations(self.path, 'class ExampleImpl { class Hidden extends Adapter {} }', {}))

    def test_anonymous_spi_implementation_requires_a_named_class(self):
        self.assertTrue(violations(self.path, 'class ExampleImpl { Object o = new Adapter(call()) { public void run() {} }; }', {}))

    def test_abstract_record_interface_and_enum_names_are_exempt(self):
        self.assertFalse(violations(self.path, 'abstract class Example {} class ExampleImpl extends Adapter {} record Data(String value) {} interface Port {} enum Status { READY }', {}))

    def test_data_exception_cannot_hide_handwritten_behavior(self):
        self.assertTrue(violations(self.path, 'class Example { public void run() {} }', {self.path: 'Example'}))
        self.assertFalse(violations(self.path, 'class Example { private String value; }', {self.path: 'Example'}))

    def test_comments_and_literals_are_not_declarations(self):
        self.assertFalse(violations(self.path, 'class ExampleImpl { String s="class Wrong {}"; /* class Wrong {} */ }', {}))


if __name__ == '__main__':
    unittest.main()
